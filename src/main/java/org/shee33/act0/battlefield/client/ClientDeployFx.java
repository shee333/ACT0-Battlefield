package org.shee33.act0.battlefield.client;

/**
 * 客户端部署落地反馈状态：屏幕黑幕淡出（300ms，ease-out）+ 底部"已部署 · 据点名"提示
 * （150ms 淡入 / 900ms 停留 / 300ms 淡出）。
 */
public final class ClientDeployFx {

    private static final long FADE_MS = 300L;
    private static final long TOAST_IN_MS = 150L;
    private static final long TOAST_HOLD_MS = 900L;
    private static final long TOAST_OUT_MS = 300L;
    private static final long TOAST_TOTAL_MS = TOAST_IN_MS + TOAST_HOLD_MS + TOAST_OUT_MS;

    private static long startedMs;
    private static String label = "";

    private ClientDeployFx() {
    }

    /** 触发部署落地反馈。 */
    public static void trigger(String pointLabel) {
        startedMs = System.currentTimeMillis();
        label = pointLabel != null ? pointLabel : "";
    }

    /** 断开服务器连接时兜底清空，防止下次连到另一个世界/服务器时播放上一局遗留的落地反馈。 */
    static void reset() {
        startedMs = 0L;
        label = "";
    }

    public static String label() {
        return label;
    }

    /** 全屏黑幕 alpha（0~255），ease-out 曲线，300ms 内由 255 淡出到 0。 */
    public static int fadeAlpha() {
        if (startedMs <= 0L) {
            return 0;
        }
        long dt = System.currentTimeMillis() - startedMs;
        if (dt >= FADE_MS) {
            return 0;
        }
        float t = dt / (float) FADE_MS;
        float eased = 1.0f - (float) Math.pow(1.0 - t, 3.0);
        return Math.round(255 * (1.0f - eased));
    }

    /** 底部据点提示的透明度（0~1）：150ms 淡入 / 900ms 停留 / 300ms 淡出。 */
    public static float toastAlpha() {
        if (startedMs <= 0L) {
            return 0f;
        }
        long dt = System.currentTimeMillis() - startedMs;
        if (dt >= TOAST_TOTAL_MS) {
            return 0f;
        }
        if (dt < TOAST_IN_MS) {
            return dt / (float) TOAST_IN_MS;
        }
        if (dt < TOAST_IN_MS + TOAST_HOLD_MS) {
            return 1.0f;
        }
        return 1.0f - ((dt - TOAST_IN_MS - TOAST_HOLD_MS) / (float) TOAST_OUT_MS);
    }
}
