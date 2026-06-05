package org.shee33.act0.battlefield.client;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 客户端击杀提示缓存。只保留最近 5 条，每条显示约 5 秒。
 */
public final class ClientKillFeed {

    private static final long TTL_MS = 5000L;
    private static final int MAX = 5;
    private static final Deque<Entry> ENTRIES = new ArrayDeque<>();

    private ClientKillFeed() {
    }

    public static void add(String killer, String victim, int killerFaction, int victimFaction) {
        ENTRIES.addFirst(new Entry(killer, victim, killerFaction, victimFaction, System.currentTimeMillis() + TTL_MS));
        while (ENTRIES.size() > MAX) {
            ENTRIES.removeLast();
        }
    }

    public static Deque<Entry> entries() {
        long now = System.currentTimeMillis();
        ENTRIES.removeIf(e -> e.expiresAt() < now);
        return new ArrayDeque<>(ENTRIES);
    }

    public record Entry(String killer, String victim, int killerFaction, int victimFaction, long expiresAt) {
    }
}
