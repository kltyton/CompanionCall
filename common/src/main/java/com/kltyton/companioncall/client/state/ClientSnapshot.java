package com.kltyton.companioncall.client.state;

import com.kltyton.companioncall.storage.pet.PetRecord;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public record ClientSnapshot(int holdMillis, UUID selected, String status, List<PetRecord> pets, Set<UUID> loaded,
                             List<PetRecord> blocked, PetRecord ridden) {
    public ClientSnapshot {
        if (holdMillis <= 0 || status == null || pets == null || loaded == null) {
            throw new IllegalArgumentException("Incomplete companion state");
        }
        pets = List.copyOf(pets);
        loaded = Set.copyOf(loaded);
        blocked = blocked == null ? List.of() : List.copyOf(blocked);
        if (selected != null && pets.stream().noneMatch(pet -> selected.equals(pet.id()))) {
            throw new IllegalArgumentException("Selected companion is absent from synchronized state");
        }
    }
    public static ClientSnapshot awaiting() { return new ClientSnapshot(300, null, "syncing", List.of(), Set.of(), List.of(), null); }
}
