package org.shee33.act0.battlefield.client;

/**
 * 客户端倒地反馈状态：屏幕四角暗色 vignette（250ms 淡入到位后静止不闪烁）+ 顶部常驻
 * "倒地 · 等待救援"横幅；被救起后 vignette 300ms 内淡出，并短暂显示"已被 X 救起"提示
 * （停留 2000ms / 淡出 300ms）。
 */
public final class ClientDownedFeedback {

    private static final long VIGNETTE_IN_MS = 250L;
    private static final long REVIVE_OUT_MS = 300L;
    private static final long TOAST_HOLD_MS = 2000L;
    private static final long TOAST_TOTAL_MS = TOAST_HOLD_MS + REVIVE_OUT_MS;

    private static boolean downed;
    private static long downedStartedMs;
    private static long revivedStartedMs;
    private static String reviverName = "";

    private ClientDownedFeedback() {
    }

    /** 倒地开始。 */
    public static void triggerDowned() {
        downed = true;
        downedStartedMs = System.currentTimeMillis();
        revivedStartedMs = 0L;
    }

    /** 被队友救起。 */
    public static void triggerRevived(String reviver) {
        downed = false;
        reviverName = reviver != null ? reviver : "";
        revivedStartedMs = System.currentTimeMillis();
    }

    /** 强制清除（如重生传送落地时），覆盖倒地超时被迫重生这条不下发"救起"包的路径。 */
    public static void clear() {
        downed = false;
        downedStartedMs = 0L;
        revivedStartedMs = 0L;
    }

    public static boolean isDowned() {
        return downed;
    }

    public static String reviverName() {
        return reviverName;
    }

    /** 四角 vignette 的强度（0~1）：倒地时 250ms ease-out 淡入后保持 1.0；救起后 300ms 内线性淡出到 0。 */
    public static float vignetteAlpha() {
        if (downed) {
            long dt = System.currentTimeMillis() - downedStartedMs;
            if (dt >= VIGNETTE_IN_MS) {
                return 1.0f;
            }
            float t = dt / (float) VIGNETTE_IN_MS;
            return 1.0f - (float) Math.pow(1.0 - t, 3.0);
        }
        if (revivedStartedMs > 0L) {
            long dt = System.currentTimeMillis() - revivedStartedMs;
            if (dt >= REVIVE_OUT_MS) {
                return 0f;
            }
            float t = dt / (float) REVIVE_OUT_MS;
            float eased = 1.0f - (float) Math.pow(1.0 - t, 3.0);
            return 1.0f - eased;
        }
        return 0f;
    }

    /** "已被 X 救起"提示透明度：停留 2000ms，随后 300ms 内淡出。 */
    public static float revivedToastAlpha() {
        if (revivedStartedMs <= 0L) {
            return 0f;
        }
        long dt = System.currentTimeMillis() - revivedStartedMs;
        if (dt >= TOAST_TOTAL_MS) {
            return 0f;
        }
        if (dt < TOAST_HOLD_MS) {
            return 1.0f;
        }
        return 1.0f - ((dt - TOAST_HOLD_MS) / (float) REVIVE_OUT_MS);
    }
}
