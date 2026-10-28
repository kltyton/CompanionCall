package com.kltyton.companioncall.storage.pet;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;

/** Server-thread-owned index. Absence from loaded chunks never invalidates a pet. */
public final class PetIndex {
    private final Map<UUID, PetRecord> pets = new HashMap<>();
    private final Map<UUID, UUID> selected = new HashMap<>();
    private final Map<UUID, Set<UUID>> blacklists = new HashMap<>();
    private final Map<UUID, UUID> bindings = new HashMap<>();
    private long nextOrder;
    private long revision;

    public PetIndex(List<PetRecord> records, Map<UUID, UUID> selections, long nextOrder,
                    Map<UUID, Set<UUID>> blacklists, Map<UUID, UUID> bindings) {
        records.forEach(pet -> pets.put(pet.id(), pet));
        selected.putAll(selections);
        blacklists.forEach((owner, ids) -> this.blacklists.put(owner, new HashSet<>(ids)));
        this.bindings.putAll(bindings);
        this.nextOrder = Math.max(nextOrder, records.stream().mapToLong(PetRecord::order).max().orElse(-1) + 1);
    }

    public long order(UUID id, UUID owner) {
        var existing = pets.get(id);
        return existing == null || !owner.equals(existing.owner()) ? nextOrder++ : existing.order();
    }

    public void observe(PetRecord record) {
        if (!record.equals(pets.put(record.id(), record))) revision++;
    }

    public PetRecord get(UUID id) { return pets.get(id); }
    public long revision() { return revision; }

    public List<PetRecord> owned(UUID owner) {
        return pets.values().stream().filter(pet -> pet.valid() && owner.equals(pet.owner()) && !blacklisted(owner, pet.id()))
                .sorted(Comparator.comparingLong(PetRecord::order)).toList();
    }

    public UUID selected(UUID owner) {
        UUID id = selected.get(owner);
        var record = pets.get(id);
        if (record != null && record.valid() && owner.equals(record.owner()) && !blacklisted(owner, id)) return id;
        var owned = owned(owner);
        UUID replacement = owned.isEmpty() ? null : owned.get(0).id();
        if (replacement == null) {
            if (selected.remove(owner) != null) revision++;
        } else if (!replacement.equals(selected.put(owner, replacement))) revision++;
        return replacement;
    }

    public boolean select(UUID owner, UUID id) {
        var pet = pets.get(id);
        if (pet == null || !pet.valid() || !owner.equals(pet.owner()) || blacklisted(owner, id)) return false;
        if (!id.equals(selected.put(owner, id))) revision++;
        return true;
    }

    public void invalidate(UUID id) {
        var pet = pets.get(id);
        if (pet != null && pet.valid()) observe(pet.invalidate());
    }

    public boolean blacklisted(UUID owner, UUID id) {
        return blacklists.getOrDefault(owner, Set.of()).contains(id);
    }

    public List<PetRecord> blocked(UUID owner) {
        return pets.values().stream().filter(pet -> owner.equals(pet.owner()) && blacklisted(owner, pet.id()))
                .sorted(Comparator.comparingLong(PetRecord::order)).toList();
    }

    public boolean blacklist(UUID owner, UUID id, boolean blocked) {
        PetRecord record = pets.get(id);
        if (record == null || !owner.equals(record.owner()) || !blocked && !record.valid()) return false;
        Set<UUID> ids = blacklists.computeIfAbsent(owner, ignored -> new HashSet<>());
        if (blocked ? ids.add(id) : ids.remove(id)) revision++;
        if (ids.isEmpty()) blacklists.remove(owner);
        selected(owner);
        return true;
    }

    public void bind(UUID entity, UUID owner) {
        if (!owner.equals(bindings.put(entity, owner))) revision++;
    }

    public UUID bindingOwner(UUID entity) { return bindings.get(entity); }
    public void unbind(UUID entity) { if (bindings.remove(entity) != null) revision++; }

    public Map<UUID, UUID> bindings() { return new HashMap<>(bindings); }

    public Map<UUID, Set<UUID>> blacklists() {
        var result = new HashMap<UUID, Set<UUID>>();
        blacklists.forEach((owner, ids) -> result.put(owner, Set.copyOf(ids)));
        return Map.copyOf(result);
    }

    public List<PetRecord> records() { return new ArrayList<>(pets.values()); }
    public Map<UUID, UUID> selections() { return new HashMap<>(selected); }
    public long nextOrder() { return nextOrder; }
}
