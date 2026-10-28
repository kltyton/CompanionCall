package com.kltyton.companioncall.storage.pet;

import com.kltyton.companioncall.api.TravelMode;
import java.util.Set;
import java.util.UUID;

public record PetRecord(UUID id, UUID owner, long order, String type, String name,
                        String dimension, double x, double y, double z,
                        float health, float maxHealth, boolean sitting,
                        Set<TravelMode> travelModes, double speed, double jump, boolean valid,
                        String appearance) {
    public PetRecord {
        travelModes = Set.copyOf(travelModes);
    }

    public int chunkX() { return ((int) Math.floor(x)) >> 4; }
    public int chunkZ() { return ((int) Math.floor(z)) >> 4; }

    public PetRecord invalidate() {
        return new PetRecord(id, owner, order, type, name, dimension, x, y, z, health,
                maxHealth, sitting, travelModes, speed, jump, false, appearance);
    }
}
