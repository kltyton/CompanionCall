package com.kltyton.companioncall.entity.ownership;

import java.util.UUID;

/** Explicit recall ownership stored with the original entity. */
public interface ManualOwnershipCarrier {
    UUID companionCall$manualOwner();
    void companionCall$manualOwner(UUID owner);
}
