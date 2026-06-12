package org.shee33.act0.battlefield.client;

/** 客户端准心命中反馈状态。 */
public final class ClientHitFeedback {
    private static long startedMs;
    private static boolean kill;

    private ClientHitFeedback() {
    }

    public static void trigger(boolean isKill) {
        startedMs = System.currentTimeMillis();
        kill = isKill;
    }

    public static long startedMs() {
        return startedMs;
    }

    public static boolean isKill() {
        return kill;
    }

    public static boolean active() {
        long life = kill ? 900L : 650L;
        return startedMs > 0L && System.currentTimeMillis() - startedMs < life;
    }
}
