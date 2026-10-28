package com.kltyton.companioncall.navigation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import net.minecraft.server.MinecraftServer;

/**
 * Fair, budgeted planning queue owned by the server thread.
 *
 * <p>Each tick has both a real A* expansion cap and an elapsed-time cap measured before every
 * turn. A session gets at most 16 expansions per turn, so one large request cannot starve every
 * other companion. A full cycle of zero-work turns stops the loop without disturbing queue order.</p>
 */
public final class NavigationScheduler {
    public static final int GLOBAL_EXPANSIONS_PER_TICK = 256;
    public static final int EXPANSIONS_PER_SESSION_TURN = 16;
    public static final long GLOBAL_TIME_BUDGET_NANOS = 1_000_000L;

    private static final class State {
        final LinkedHashSet<NavigationSession> members = new LinkedHashSet<>();
        final ArrayDeque<NavigationSession> queue = new ArrayDeque<>();
    }

    private static final Map<MinecraftServer, State> STATES = new IdentityHashMap<>();

    private NavigationScheduler() {}

    public static void tick(MinecraftServer server) {
        requireServerThread(server);
        State state = STATES.get(server);
        if (state == null || state.members.isEmpty()) return;
        int budget = GLOBAL_EXPANSIONS_PER_TICK;
        long deadline = System.nanoTime() + GLOBAL_TIME_BUDGET_NANOS;
        int idleLimit = Math.max(1, state.members.size());
        int idleTurns = 0;
        while (budget > 0 && !state.queue.isEmpty() && System.nanoTime() < deadline) {
            NavigationSession session = state.queue.pollFirst();
            if (session == null) break;
            if (!session.isScheduled()) {
                state.members.remove(session);
                idleTurns = 0;
                idleLimit = Math.max(1, state.members.size());
                continue;
            }
            int used = session.planTurn(Math.min(EXPANSIONS_PER_SESSION_TURN, budget),
                    server.getTickCount());
            if (used > 0) {
                budget -= used;
                idleTurns = 0;
            } else {
                idleTurns++;
            }
            if (session.isScheduled()) {
                state.queue.addLast(session);
            } else {
                state.members.remove(session);
                idleTurns = 0;
                idleLimit = Math.max(1, state.members.size());
            }
            // A complete cycle with no expansions means only waiting/not-yet-due sessions remain;
            // stop without disturbing their preserved round-robin order.
            if (idleTurns >= idleLimit) break;
        }
    }

    public static void close(MinecraftServer server) {
        requireServerThread(server);
        State state = STATES.remove(server);
        if (state == null) return;
        for (NavigationSession session : new ArrayList<>(state.members)) {
            session.closeFromScheduler();
        }
        state.members.clear();
        state.queue.clear();
    }

    public static int activeSessions(MinecraftServer server) {
        State state = STATES.get(server);
        return state == null ? 0 : state.members.size();
    }

    static void wake(NavigationSession session) {
        MinecraftServer server = session.server();
        requireServerThread(server);
        State state = STATES.computeIfAbsent(server, ignored -> new State());
        if (state.members.add(session)) state.queue.addLast(session);
    }

    static void forget(NavigationSession session) {
        State state = STATES.get(session.server());
        if (state == null) return;
        state.members.remove(session);
        state.queue.remove(session);
    }

    private static void requireServerThread(MinecraftServer server) {
        if (!server.isSameThread()) {
            throw new IllegalStateException("Navigation scheduler must run on the server thread");
        }
    }
}
