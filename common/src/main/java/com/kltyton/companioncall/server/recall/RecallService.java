package com.kltyton.companioncall.server.recall;

import com.kltyton.companioncall.CompanionCall;
import com.kltyton.companioncall.api.Approach;
import com.kltyton.companioncall.api.CompanionCallApi;
import com.kltyton.companioncall.api.PetAdapter;
import com.kltyton.companioncall.api.RecallArrival;
import com.kltyton.companioncall.api.RecallPursuit;
import com.kltyton.companioncall.entity.glow.RecallGlow;
import com.kltyton.companioncall.mixin.access.EntityPassengers;
import com.kltyton.companioncall.entity.navigation.BoardingApproach;
import com.kltyton.companioncall.entity.navigation.EntityRecallNavigation;
import com.kltyton.companioncall.entity.ownership.RecallPermissions;
import com.kltyton.companioncall.platform.ChunkLease;
import com.kltyton.companioncall.platform.NativeVersion;
import com.kltyton.companioncall.server.CompanionServer;
import com.kltyton.companioncall.world.recall.LandingFinder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

public final class RecallService implements AutoCloseable {
    private static final double REMOTE_DISTANCE = 96;
    private static final class Request {
        final UUID pet;
        final UUID owner;
        final String destinationDimension;
        final long started;
        final int glowColor;
        ChunkLease sourceLease;
        Entity entity;
        Entity glowingEntity;
        Entity sharedMotionEntity;
        long sharedMotionTick = Long.MIN_VALUE;
        Vec3 ownerPosition;
        Vec3 ownerVelocity;
        Vec3 ownerMeasuredVelocity = Vec3.ZERO;
        long ownerSample;
        RecallPursuit pursuit;
        long forecastTick = Long.MIN_VALUE;
        Entity forecastEntity;
        double forecastRouteDistance;
        boolean sourceWasUnloaded;
        boolean approaching;
        double bestDistance = Double.POSITIVE_INFINITY;
        long lastProgress;
        Vec3 progressPosition;
        boolean transferring;
        boolean directTransfer;
        boolean remoteTransferred;
        long lastBoardFailure = Long.MIN_VALUE;

        Request(UUID pet, ServerPlayer owner, long now, boolean directTransfer) {
            this.pet = pet;
            this.owner = owner.getUUID();
            this.destinationDimension = NativeVersion.dimension(owner.level());
            this.started = now;
            this.glowColor = RecallGlow.color(this.owner);
            this.ownerPosition = owner.position();
            this.ownerVelocity = NativeVersion.knownMovement(owner, owner.getDeltaMovement());
            this.ownerSample = now;
            this.lastProgress = now;
            this.directTransfer = directTransfer;
        }
    }

    private final CompanionServer runtime;
    private final Map<UUID, Request> active = new HashMap<>();
    private final Map<UUID, Request> activeOwners = new HashMap<>();
    private final Map<UUID, String> statuses = new HashMap<>();

    public RecallService(CompanionServer runtime) { this.runtime = runtime; }

    public String status(UUID owner) { return statuses.getOrDefault(owner, "ready"); }

    public void status(ServerPlayer owner, String status) {
        statuses.put(owner.getUUID(), status);
        runtime.sync(owner);
    }

    public void request(ServerPlayer owner) {
        request(owner, null, false);
    }

    public boolean request(ServerPlayer owner, UUID requested, boolean forceTransfer) {
        if (!owner.isAlive() || owner.isSpectator()) {
            status(owner, "unavailable");
            return false;
        }
        UUID selected = requested == null ? runtime.index.selected(owner.getUUID()) : requested;
        if (selected == null) {
            status(owner, "empty");
            return false;
        }
        if (active.containsKey(selected) || runtime.transfers.locked(selected)) return false;
        var record = runtime.index.get(selected);
        if (runtime.index.blacklisted(owner.getUUID(), selected)) {
            status(owner, "blacklisted");
            return false;
        }
        if (record == null || !record.valid() || !owner.getUUID().equals(record.owner())) {
            status(owner, "not_owned");
            return false;
        }
        var request = new Request(selected, owner, runtime.gameTime(), forceTransfer);
        request.entity = runtime.findLoaded(selected);
        if (request.entity == null) {
            request.sourceWasUnloaded = true;
            ServerLevel source = runtime.server.getLevel(NativeVersion.levelKey(record.dimension()));
            if (source == null) {
                status(owner, "dimension_missing");
                return false;
            }
            request.sourceLease = NativeVersion.retain(source, record.chunkX(), record.chunkZ(), UUID.randomUUID());
        }
        for (var previous : new ArrayList<>(active.values())) {
            if (previous.owner.equals(owner.getUUID())) cancel(previous.pet, "cancelled");
        }
        if (requested != null) {
            runtime.index.select(owner.getUUID(), selected);
            runtime.persist();
        }
        active.put(selected, request);
        activeOwners.put(request.owner, request);
        CompanionCall.LOGGER.debug("Recall accepted for pet {}, force={}", selected, forceTransfer);
        NativeVersion.whistle(owner);
        status(owner, "locating");
        return true;
    }

    public void tick() {
        long now = runtime.gameTime();
        for (var request : new ArrayList<>(active.values())) {
            var owner = runtime.server.getPlayerList().getPlayer(request.owner);
            if (owner == null || !owner.isAlive() || !request.pet.equals(runtime.index.selected(request.owner))
                    || !request.destinationDimension.equals(NativeVersion.dimension(owner.level()))) {
                cancel(request.pet, "cancelled");
                continue;
            }
            sampleOwner(request, owner, now);
            if (request.transferring) continue;
            Entity entity = runtime.findLoaded(request.pet);
            if (entity == null) {
                if (now - request.started >= 400) cancel(request.pet, "location_unknown");
                continue;
            }
            if (request.entity != entity) {
                request.lastProgress = now;
                request.progressPosition = entity.position();
            }
            request.entity = entity;
            var adapter = CompanionCallApi.adapter(entity);
            if (!entity.isAlive() || adapter == null || !request.owner.equals(adapter.owner(entity))) {
                runtime.observe(entity);
                cancel(request.pet, entity.isAlive() ? "not_owned" : "dead");
                continue;
            }
            RecallPermissions.detachForRecall(entity, request.owner);
            if (entity.isPassenger() || RecallPermissions.carriesOtherPlayer(entity, request.owner)) {
                cancel(request.pet, "pet_riding");
                continue;
            }
            followGlow(request, entity);
            double distance = entity.position().distanceTo(owner.position());
            updateForecast(request, entity, owner, adapter);
            if (entity.level() == owner.level() && owner.getRootVehicle() == entity) {
                finish(request, entity, owner);
                continue;
            }
            boolean crossDimension = entity.level() != owner.level();
            boolean unloadedSource = request.sourceWasUnloaded && !request.approaching;
            boolean remote = !request.remoteTransferred && !crossDimension && distance >= REMOTE_DISTANCE;
            if (!request.directTransfer && !crossDimension && !unloadedSource && !remote) {
                request.approaching = true;
                adapter.stand(entity);
                if (distance + 0.05 < request.bestDistance) {
                    request.bestDistance = distance;
                    request.lastProgress = now;
                }
                Approach approach = adapter.approach(entity, owner, request.pursuit);
                if (request.progressPosition == null) request.progressPosition = entity.position();
                if (approach == Approach.MOVING && entity.position().distanceToSqr(request.progressPosition) >= 0.25) {
                    request.progressPosition = entity.position();
                    request.lastProgress = now;
                }
                if (hasArrived(entity, owner, adapter)
                        || approach == Approach.ARRIVED && !willBoard(entity, owner, adapter)) {
                    finish(request, entity, owner);
                    continue;
                }
                boolean stalled = now - request.lastProgress >= runtime.config.stuckTicks();
                if (approach != Approach.UNREACHABLE || !stalled) {
                    String state = stalled ? "blocked" : "approaching";
                    if (!state.equals(status(request.owner))) status(owner, state);
                    continue;
                }
            }
            adapter.stopApproach(entity);
            BoardingApproach.clear(entity);
            boolean force = request.directTransfer;
            request.directTransfer = false;
            if (remote) request.remoteTransferred = true;
            LandingFinder.Landing landing;
            try {
                var boarding = !remote && willBoard(entity, owner, adapter)
                        ? LandingFinder.boarding(entity, owner, adapter.travelModes(entity), request.pursuit.target())
                        : java.util.Optional.<LandingFinder.Landing>empty();
                landing = boarding.orElseGet(() -> LandingFinder.findForRecall(entity, owner, adapter.travelModes(entity)));
            } catch (LandingFinder.NoLandingSpace error) {
                cancel(request.pet, "no_landing");
                continue;
            }
            if (CompanionCall.LOGGER.isDebugEnabled()) {
                String reason = force ? "force" : crossDimension ? "cross_dimension"
                        : unloadedSource ? "source_unloaded" : remote ? "remote" : "no_route";
                CompanionCall.LOGGER.debug("Recall transfer for pet {}, reason={}, distance={}, elapsedTicks={}",
                        request.pet, reason, distance, now - request.started);
            }
            request.transferring = true;
            status(owner, "transferring");
            runtime.transfers.begin(entity, owner, landing, transferred -> {
                if (active.get(request.pet) != request) return;
                var currentOwner = runtime.server.getPlayerList().getPlayer(request.owner);
                if (currentOwner != null && currentOwner.level() == transferred.level()
                        && request.destinationDimension.equals(NativeVersion.dimension(currentOwner.level()))) {
                    request.entity = transferred;
                    followGlow(request, transferred);
                    request.transferring = false;
                    request.sourceWasUnloaded = false;
                    request.approaching = true;
                    Entity arrived = transferred;
                    var arrivedAdapter = CompanionCallApi.adapter(arrived);
                    if (!remote && arrivedAdapter != null && willBoard(arrived, currentOwner, arrivedAdapter)) {
                        if (!RecallArrival.isAtBoardingPosition(arrived, currentOwner)) {
                            var capture = LandingFinder.boarding(arrived, currentOwner,
                                    arrivedAdapter.travelModes(arrived), currentOwner.position());
                            if (capture.isPresent()) {
                                arrived = NativeVersion.transfer(arrived, (ServerLevel) currentOwner.level(), capture.get().position());
                                request.entity = arrived;
                                followGlow(request, arrived);
                                runtime.protectionDeadline(request.pet, capture.get().protectedArrival());
                            }
                        }
                        if (tryBoard(arrived, currentOwner)) {
                            finish(request, arrived, currentOwner);
                            return;
                        }
                    }
                    request.bestDistance = Double.POSITIVE_INFINITY;
                    request.lastProgress = runtime.gameTime();
                    request.progressPosition = arrived.position();
                    status(currentOwner, "approaching");
                } else cancel(request.pet, "owner_moved");
            }, failure -> {
                if (active.get(request.pet) == request) cancel(request.pet, failure);
            });
        }
    }

    private void finish(Request request, Entity pet, ServerPlayer owner) {
        if (pet.level() != owner.level()) {
            cancel(request.pet, "owner_moved");
            return;
        }
        var adapter = CompanionCallApi.adapter(pet);
        if (adapter == null || !pet.isAlive() || !owner.getUUID().equals(adapter.owner(pet))) {
            cancel(request.pet, "not_owned");
            return;
        }
        if (willBoard(pet, owner, adapter) && !RecallArrival.isAtBoardingPosition(pet, owner)) return;
        if (willBoard(pet, owner, adapter) && !tryBoard(pet, owner)) return;
        if (CompanionCall.LOGGER.isDebugEnabled()) {
            CompanionCall.LOGGER.debug("Recall arrived for pet {}, boarded={}, distance={}, elapsedTicks={}",
                    request.pet, owner.getRootVehicle() == pet, pet.distanceTo(owner), runtime.gameTime() - request.started);
        }
        adapter.stopApproach(pet);
        runtime.observe(pet);
        release(request);
        status(owner, "arrived");
        runtime.persist();
    }

    private static boolean hasArrived(Entity pet, ServerPlayer owner, PetAdapter adapter) {
        if (pet.level() != owner.level()) return false;
        if (owner.getRootVehicle() == pet) return true;
        if (willBoard(pet, owner, adapter)) {
            return RecallArrival.isAtBoardingPosition(pet, owner);
        }
        return pet.distanceToSqr(owner) <= 6.25;
    }

    private static boolean willBoard(Entity pet, ServerPlayer owner, PetAdapter adapter) {
        return !owner.isPassenger() && adapter.canRide(pet, owner)
                && ((EntityPassengers) pet).companionCall$canAddPassenger(owner);
    }

    /** Captures actual contact during entity movement; durable request cleanup stays in the server tick. */
    public boolean tryBoard(Entity pet, ServerPlayer owner) {
        Request request = active.get(pet.getUUID());
        if (request == null || request.transferring || !request.owner.equals(owner.getUUID())
                || !pet.isAlive() || pet.isRemoved() || !owner.isAlive() || owner.isSpectator()
                || pet.level() != owner.level() || pet.isPassenger()
                || RecallPermissions.carriesOtherPlayer(pet, request.owner)) return false;
        if (owner.getRootVehicle() == pet) return true;
        var adapter = CompanionCallApi.adapter(pet);
        if (adapter == null || !request.owner.equals(adapter.owner(pet)) || !willBoard(pet, owner, adapter)
                || !RecallArrival.isAtBoardingPosition(pet, owner)) return false;
        if (!NativeVersion.mount(owner, pet, false)) {
            if (CompanionCall.LOGGER.isDebugEnabled()) {
                long now = runtime.gameTime();
                if (request.lastBoardFailure == Long.MIN_VALUE || now - request.lastBoardFailure >= 20) {
                    request.lastBoardFailure = now;
                    CompanionCall.LOGGER.debug("Recall boarding rejected for pet {}", request.pet);
                }
            }
            return false;
        }
        owner.setDeltaMovement(pet.getDeltaMovement());
        owner.resetFallDistance();
        return true;
    }

    /** Stops a native movement at first actual boarding contact, before either entity crosses through. */
    public Vec3 captureMotion(Entity moving, Vec3 movement) {
        Request request = contactRequest(moving);
        if (request == null || request.transferring || request.entity == null) return movement;
        Entity pet = request.entity;
        ServerPlayer owner = runtime.server.getPlayerList().getPlayer(request.owner);
        var adapter = CompanionCallApi.adapter(pet);
        if (owner == null || adapter == null || !pet.isAlive() || pet.isRemoved() || !owner.isAlive()
                || owner.isSpectator() || pet.level() != owner.level() || pet.isPassenger()
                || !request.owner.equals(adapter.owner(pet)) || !willBoard(pet, owner, adapter)
                || RecallPermissions.carriesOtherPlayer(pet, request.owner)) return movement;
        if (!RecallArrival.sideReady(pet, owner)) return movement;
        Vec3 seat = RecallArrival.seatPosition(pet, owner);
        Vec3 relative = owner.position().subtract(seat);
        Vec3 change = moving == pet ? movement.scale(-1) : movement;
        boolean grounded = owner.onGround() && (moving != owner || movement.y >= 0);
        double body = boardingContactFraction(pet, owner, seat, relative, change, grounded, true);
        double seatContact = grounded ? Double.POSITIVE_INFINITY
                : boardingContactFraction(pet, owner, seat, relative, change, false, false);
        double fraction = Math.min(body, seatContact);
        return Double.isInfinite(fraction) ? movement : movement.scale(fraction);
    }

    private static double boardingContactFraction(Entity pet, ServerPlayer owner, Vec3 seat, Vec3 relative,
                                                   Vec3 change, boolean grounded, boolean bodyContact) {
        double radius = RecallArrival.horizontalRadius(pet, owner, grounded);
        double enter = 0, exit = 1;
        if (bodyContact) {
            var ownerBox = owner.getBoundingBox();
            var petBox = pet.getBoundingBox();
            double minX = petBox.minX - ownerBox.maxX - .001;
            double maxX = petBox.maxX - ownerBox.minX + .001;
            double minZ = petBox.minZ - ownerBox.maxZ - .001;
            double maxZ = petBox.maxZ - ownerBox.minZ + .001;
            if (Math.abs(change.x) < 1.0e-9) {
                if (minX > 0 || maxX < 0) return Double.POSITIVE_INFINITY;
            } else {
                double first = minX / change.x;
                double last = maxX / change.x;
                enter = Math.max(enter, Math.min(first, last));
                exit = Math.min(exit, Math.max(first, last));
            }
            if (Math.abs(change.z) < 1.0e-9) {
                if (minZ > 0 || maxZ < 0) return Double.POSITIVE_INFINITY;
            } else {
                double first = minZ / change.z;
                double last = maxZ / change.z;
                enter = Math.max(enter, Math.min(first, last));
                exit = Math.min(exit, Math.max(first, last));
            }
        } else {
            double a = change.x * change.x + change.z * change.z;
            double c = relative.x * relative.x + relative.z * relative.z - radius * radius;
            if (a < 1.0e-18) {
                if (c > 0) return Double.POSITIVE_INFINITY;
            } else {
                double b = 2 * (relative.x * change.x + relative.z * change.z);
                double discriminant = b * b - 4 * a * c;
                if (discriminant < 0) return Double.POSITIVE_INFINITY;
                double root = Math.sqrt(discriminant);
                enter = Math.max(enter, (-b - root) / (2 * a));
                exit = Math.min(exit, (-b + root) / (2 * a));
            }
        }
        double minY = RecallArrival.minimumRelativeY(pet, seat, grounded);
        double maxY = RecallArrival.maximumRelativeY(pet, seat, grounded);
        if (Math.abs(change.y) < 1.0e-9) {
            if (relative.y < minY || relative.y > maxY) return Double.POSITIVE_INFINITY;
        } else {
            double first = (minY - relative.y) / change.y;
            double last = (maxY - relative.y) / change.y;
            enter = Math.max(enter, Math.min(first, last));
            exit = Math.min(exit, Math.max(first, last));
        }
        if (enter >= exit) return Double.POSITIVE_INFINITY;
        return (enter + Math.min(exit, enter + 1.0e-6)) * .5;
    }

    public void contact(Entity moving) {
        Request request = contactRequest(moving);
        if (request == null || request.entity == null) return;
        ServerPlayer owner = runtime.server.getPlayerList().getPlayer(request.owner);
        if (owner != null) tryBoard(request.entity, owner);
    }

    /** Refreshes the owner snapshot at the actual drive call, sharing the result with the service tick. */
    public RecallPursuit refreshPursuit(Entity entity, RecallPursuit fallback) {
        Request request = active.get(entity.getUUID());
        if (request == null || request.entity != entity || !request.approaching || request.transferring) return fallback;
        var owner = runtime.server.getPlayerList().getPlayer(request.owner);
        var adapter = CompanionCallApi.adapter(entity);
        if (owner == null || owner.level() != entity.level() || adapter == null) return fallback;
        sampleOwner(request, owner, runtime.gameTime());
        updateForecast(request, entity, owner, adapter);
        return request.pursuit;
    }

    private void updateForecast(Request request, Entity entity, ServerPlayer owner, PetAdapter adapter) {
        long tick = runtime.server.getTickCount();
        double routeDistance = EntityRecallNavigation.remainingRouteDistance(entity);
        if (request.forecastTick == tick && request.forecastEntity == entity && request.pursuit != null
                && request.pursuit.position().equals(owner.position()) && request.pursuit.velocity().equals(request.ownerVelocity)
                && request.pursuit.grounded() == owner.onGround() && request.forecastRouteDistance == routeDistance) return;
        request.pursuit = RecallPursuit.predict(entity, owner, request.ownerVelocity, adapter.travelModes(entity), routeDistance);
        request.forecastTick = tick;
        request.forecastEntity = entity;
        request.forecastRouteDistance = routeDistance;
    }

    /** Native controllers keep their animation state while the shared driver owns recall motion. */
    public boolean usesSharedMotion(Entity entity) {
        Request request = active.get(entity.getUUID());
        if (request == null || request.entity != entity || request.sharedMotionEntity != entity
                || request.sharedMotionTick < (long) entity.tickCount - 1 || !request.approaching || request.transferring) return false;
        var owner = runtime.server.getPlayerList().getPlayer(request.owner);
        return owner != null && owner.getRootVehicle() != entity;
    }

    public void sharedMotionPrepared(Entity entity) {
        Request request = active.get(entity.getUUID());
        if (request != null && request.entity == entity) {
            request.sharedMotionEntity = entity;
            request.sharedMotionTick = entity.tickCount;
        }
    }

    private Request contactRequest(Entity moving) {
        if (active.isEmpty()) return null;
        Request request = active.get(moving.getUUID());
        return request == null && moving instanceof ServerPlayer ? activeOwners.get(moving.getUUID()) : request;
    }

    private static void sampleOwner(Request request, ServerPlayer owner, long now) {
        long elapsed = now - request.ownerSample;
        if (elapsed <= 0) return;
        Vec3 velocity = owner.position().subtract(request.ownerPosition).scale(1.0 / elapsed);
        request.ownerMeasuredVelocity = velocity;
        request.ownerPosition = owner.position();
        request.ownerVelocity = NativeVersion.knownMovement(owner, velocity);
        request.ownerSample = now;
    }

    /** Moves the transient outline as soon as native migration creates the destination entity. */
    public void transferred(Entity source, Entity arrived) {
        Request request = active.get(source.getUUID());
        if (request != null && request.transferring && request.entity == source) followGlow(request, arrived);
    }

    private static void followGlow(Request request, Entity entity) {
        if (request.glowingEntity == entity) return;
        if (request.glowingEntity != null) RecallGlow.clear(request.glowingEntity);
        request.glowingEntity = entity;
        RecallGlow.show(entity, request.glowColor);
    }

    public void cancel(UUID pet, String reason) {
        var request = active.get(pet);
        if (request == null) return;
        CompanionCall.LOGGER.debug("Recall ended for pet {}, reason={}, elapsedTicks={}", pet, reason, runtime.gameTime() - request.started);
        if (!runtime.transfers.locked(pet) && request.entity != null && !request.entity.isRemoved()) {
            var adapter = CompanionCallApi.adapter(request.entity);
            if (adapter != null) adapter.stopApproach(request.entity);
        }
        release(request);
        var owner = runtime.server.getPlayerList().getPlayer(request.owner);
        if (owner != null) status(owner, reason);
    }

    private void release(Request request) {
        if (!active.remove(request.pet, request)) return;
        activeOwners.remove(request.owner, request);
        if (request.entity != null) BoardingApproach.clear(request.entity);
        if (request.glowingEntity != null) {
            RecallGlow.clear(request.glowingEntity);
            request.glowingEntity = null;
        }
        if (request.sourceLease != null) request.sourceLease.close();
    }

    @Override public void close() {
        for (var request : new ArrayList<>(active.values())) cancel(request.pet, "cancelled");
    }
}
