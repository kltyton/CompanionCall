package com.kltyton.companioncall.config.client;

import java.util.Objects;
import java.util.function.Supplier;

/** Loader-neutral values backed by the platform's registered Forge configuration spec. */
public final class ClientOptions {
    public record Values(boolean hudEnabled, boolean selectorEnabled, boolean entityPreviews,
                         boolean managementEnabled, boolean riddenShortcutEnabled, int hudLeft, int hudBottom) {}
    private static final Values DEFAULT = new Values(true, true, true, true, true, 20, 22);
    private static Supplier<Values> values = () -> DEFAULT;

    private ClientOptions() {}
    public static void install(Supplier<Values> source) { values = Objects.requireNonNull(source); }
    public static Values get() { return values.get(); }
}
