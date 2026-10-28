package com.kltyton.companioncall.platform;

/** Releases only the loading responsibility acquired by this recall. */
@FunctionalInterface
public interface ChunkLease extends AutoCloseable {
    @Override void close();
}
