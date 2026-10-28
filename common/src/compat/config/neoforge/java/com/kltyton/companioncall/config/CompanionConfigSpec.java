package com.kltyton.companioncall.config;

import com.kltyton.companioncall.config.client.ClientOptions;
import net.neoforged.neoforge.common.ModConfigSpec;

public final class CompanionConfigSpec {
    public static final ModConfigSpec CLIENT;
    public static final ModConfigSpec COMMON;
    private static final ModConfigSpec.BooleanValue HUD;
    private static final ModConfigSpec.BooleanValue SELECTOR;
    private static final ModConfigSpec.BooleanValue PREVIEWS;
    private static final ModConfigSpec.BooleanValue MANAGEMENT;
    private static final ModConfigSpec.BooleanValue RIDDEN_SHORTCUT;
    private static final ModConfigSpec.IntValue HUD_LEFT;
    private static final ModConfigSpec.IntValue HUD_BOTTOM;
    private static final ModConfigSpec.IntValue HOLD_MILLIS;
    private static final ModConfigSpec.IntValue STUCK_TICKS;
    private static final ModConfigSpec.IntValue PROTECTION_TICKS;

    static {
        var client = new ModConfigSpec.Builder();
        HUD = client.comment("Show the companion HUD.").define("hudEnabled", true);
        SELECTOR = client.comment("Open the companion selector by holding its key.").define("selectorEnabled", true);
        PREVIEWS = client.comment("Render native entity previews in the HUD and selector.").define("entityPreviews", true);
        MANAGEMENT = client.comment("Show list management inside the selector.").define("managementEnabled", true);
        RIDDEN_SHORTCUT = client.comment("Enable the registered add-ridden-entity key.").define("riddenShortcutEnabled", true);
        HUD_LEFT = client.comment("HUD left margin in UI pixels.").defineInRange("hudLeft", 20, -100000, 100000);
        HUD_BOTTOM = client.comment("HUD bottom margin in UI pixels.").defineInRange("hudBottom", 22, -100000, 100000);
        CLIENT = client.build();
        ClientOptions.install(() -> new ClientOptions.Values(HUD.get(), SELECTOR.get(), PREVIEWS.get(),
                MANAGEMENT.get(), RIDDEN_SHORTCUT.get(), HUD_LEFT.get(), HUD_BOTTOM.get()));
        var legacy = RecallConfig.legacyDefaults();
        var common = new ModConfigSpec.Builder();
        HOLD_MILLIS = common.comment("Hold duration before opening the selector, in milliseconds.")
                .worldRestart().defineInRange("holdMillis", legacy.holdMillis(), 1, Integer.MAX_VALUE);
        STUCK_TICKS = common.comment("Delay before recovering a confirmed unreachable route; not a teleport timer.")
                .worldRestart().defineInRange("stuckTicks", legacy.stuckTicks(), 1, Integer.MAX_VALUE);
        PROTECTION_TICKS = common.comment("Environmental protection after a necessary unsupported landing.")
                .worldRestart().defineInRange("protectionTicks", legacy.protectionTicks(), 1, Integer.MAX_VALUE);
        COMMON = common.build();
    }

    private CompanionConfigSpec() {}

    public static RecallConfig commonValues() {
        return new RecallConfig(HOLD_MILLIS.get(), STUCK_TICKS.get(), PROTECTION_TICKS.get());
    }
}
