package com.kltyton.companioncall.storage.transfer;

/** Per-entity transfer identity persisted by the native entity serializer. */
public interface TransferMark {
    String companionCall$nonce();
    String companionCall$role();
    void companionCall$mark(String nonce, String role);
    boolean companionCall$loading();
}
