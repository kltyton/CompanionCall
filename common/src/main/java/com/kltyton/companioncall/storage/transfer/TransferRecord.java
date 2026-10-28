package com.kltyton.companioncall.storage.transfer;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record TransferRecord(UUID operation, UUID pet, UUID owner, String sourceDimension,
                             double sourceX, double sourceY, double sourceZ,
                             String destinationDimension, double destinationX, double destinationY,
                             double destinationZ, List<UUID> members, Map<UUID, UUID> parents,
                             Map<UUID, PlayerOrigin> players, Phase phase,
                             long protectionDeadline) {
    public record PlayerOrigin(String dimension, double x, double y, double z) {}
    public enum Phase { PREPARED, SOURCE_DURABLE, DESTINATION_DURABLE, COMMITTED, ABORTED, DEAD }

    public TransferRecord {
        members = List.copyOf(members);
        parents = Map.copyOf(parents);
        players = Map.copyOf(players);
    }

    public TransferRecord phase(Phase phase) {
        return new TransferRecord(operation, pet, owner, sourceDimension, sourceX, sourceY, sourceZ,
                destinationDimension, destinationX, destinationY, destinationZ, members, parents, players, phase,
                protectionDeadline);
    }

    public TransferRecord protection(long deadline) {
        return new TransferRecord(operation, pet, owner, sourceDimension, sourceX, sourceY, sourceZ,
                destinationDimension, destinationX, destinationY, destinationZ, members, parents, players,
                phase, deadline);
    }
}
