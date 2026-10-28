package com.kltyton.companioncall.config;

import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.UncheckedIOException;
import com.kltyton.companioncall.platform.NativeVersion;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public record RecallConfig(int holdMillis, int stuckTicks, int protectionTicks) {
    public RecallConfig {
        if (holdMillis <= 0 || stuckTicks <= 0 || protectionTicks <= 0) {
            throw new IllegalArgumentException("Recall settings must be positive finite values");
        }
    }

    public static RecallConfig legacyDefaults() {
        Path directory = NativeVersion.configDirectory();
        Path legacy = directory.resolve("companion_call.json");
        if (Files.exists(directory.resolve("companion_call-common.toml")) || !Files.exists(legacy)) {
            return new RecallConfig(300, 100, 200);
        }
        try {
            var result = new GsonBuilder().create().fromJson(Files.readString(legacy, StandardCharsets.UTF_8), RecallConfig.class);
            if (result == null) throw new IOException("Empty recall configuration: " + legacy);
            return result;
        } catch (IOException error) {
            throw new UncheckedIOException("Cannot import existing recall settings", error);
        }
    }
}
