package org.shee33.act0.battlefield.client;

import java.util.ArrayDeque;
import java.util.Deque;

public final class ClientKillFeed {

    private static final long TTL_MS = 5000L;
    private static final int MAX = 5;
    private static final Deque<Entry> ENTRIES = new ArrayDeque<>();

    private ClientKillFeed() {}

    public static void add(String killer, String victim, int killerFaction, int victimFaction, String weapon) {
        ENTRIES.addFirst(new Entry(killer, victim, killerFaction, victimFaction, System.currentTimeMillis() + TTL_MS, weapon));
        while (ENTRIES.size() > MAX) ENTRIES.removeLast();
    }

    public static Deque<Entry> entries() {
        long now = System.currentTimeMillis();
        ENTRIES.removeIf(e -> e.expiresAt() < now);
        return new ArrayDeque<>(ENTRIES);
    }

    public record Entry(String killer, String victim, int killerFaction, int victimFaction, long expiresAt, String weapon) {}
}