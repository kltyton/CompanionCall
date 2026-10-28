package com.kltyton.companioncall;

import com.kltyton.companioncall.api.CompanionCallApi;
import com.kltyton.companioncall.entity.ownership.VanillaPetAdapter;
import com.kltyton.companioncall.server.CompanionServer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CompanionCall {
    public static final String MOD_ID = "companion_call";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean();
    private static final Map<MinecraftServer, CompanionServer> SERVERS = new ConcurrentHashMap<>();

    private CompanionCall() {}

    public static void initialize() {
        if (INITIALIZED.compareAndSet(false, true)) {
            CompanionCallApi.registerAdapter("companion_call:vanilla", 0, new VanillaPetAdapter());
        }
    }

    public static CompanionServer server(MinecraftServer server) {
        initialize();
        return SERVERS.computeIfAbsent(server, CompanionServer::new);
    }

    public static void stop(MinecraftServer server) {
        var runtime = SERVERS.remove(server);
        if (runtime != null) runtime.close();
    }

    public static CompanionServer activeServer(MinecraftServer server) {
        return SERVERS.get(server);
    }
}
