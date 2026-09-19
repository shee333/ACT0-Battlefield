package org.shee33.act0.battlefield.client;

/**
 * 客户端"离开作战区域"状态缓存 —— 同时驱动底部倒计时横幅与画面灰度。
 *
 * <p>与 {@code ClientDownedFeedback} / {@code ClientDeployFx} 同源：服务端给权威状态（含不规则边界的
 * 判定与倒计时），客户端只负责表现。所有对外取值都是"实时时钟采样、每帧重新计算"的纯函数，不持有
 * MC tick 状态、也不随帧率变化（与 {@code ArcadeHudOverlay} / {@code MenuTween} 同一纪律）。
 */
public final class ClientOutOfBounds {

    /** 出界后灰度起步值：立刻给出明显反馈，再随倒计时加深到 1.0（全灰），保留"越来越糟"的压迫感。 */
    private static final float INTENSITY_FLOOR = 0.35f;

    /** 横幅/灰度的进入时长——出界是危险信号，要立刻看得见。 */
    private static final float FADE_IN_MS = 250f;
    /** 恢复时长——回到界内稍慢一点淡出，不做硬切（方向即语义）。 */
    private static final float FADE_OUT_MS = 320f;

    private static boolean active;
    private static int remainingSeconds;
    private static int totalSeconds;
    /** 最近一次状态翻转的时刻；淡入淡出完全由它推算。 */
    private static long changedAtMs;
    /** 退出时的灰度起点（最后一次界内的目标强度），保证淡出从当前深度往回走而非硬切归零。 */
    private static float lastIntensity;

    private ClientOutOfBounds() {
    }

    /** 服务端包到达时调用。 */
    public static void update(boolean active, int remainingSeconds, int totalSeconds) {
        if (active != ClientOutOfBounds.active) {
            ClientOutOfBounds.changedAtMs = System.currentTimeMillis();
        }
        ClientOutOfBounds.active = active;
        ClientOutOfBounds.remainingSeconds = Math.max(0, remainingSeconds);
        ClientOutOfBounds.totalSeconds = Math.max(0, totalSeconds);
        if (active) {
            ClientOutOfBounds.lastIntensity = targetIntensity();
        }
    }

    public static boolean isActive() {
        return active;
    }

    /** 剩余秒数（服务端每秒推送）。 */
    public static int remainingSeconds() {
        return remainingSeconds;
    }

    /** 断开连接 / 换对局时兜底清空，防止残留状态把下一局画面染灰。 */
    public static void clear() {
        active = false;
        remainingSeconds = 0;
        totalSeconds = 0;
        changedAtMs = 0L;
        lastIntensity = 0f;
    }

    /** 横幅可见度 [0,1]：出界 {@link #FADE_IN_MS} 淡入，回界 {@link #FADE_OUT_MS} 淡出。 */
    public static float visibility() {
        float t = elapsedRatio(active ? FADE_IN_MS : FADE_OUT_MS);
        float eased = 1f - (float) Math.pow(1f - t, 3f);
        return active ? eased : 1f - eased;
    }

    /**
     * 画面灰度强度 [0,1]：未出界且已淡出完为 0；出界后从 {@link #INTENSITY_FLOOR} 起步随倒计时加深到
     * 1.0；回界内时从退出瞬间的深度平滑回落到 0。
     */
    public static float intensity() {
        if (active) {
            return targetIntensity() * visibility();
        }
        if (lastIntensity <= 0f) {
            return 0f;
        }
        return lastIntensity * visibility();
    }

    private static float targetIntensity() {
        int total = Math.max(1, totalSeconds);
        float elapsed = 1f - Math.min(total, remainingSeconds) / (float) total;
        return INTENSITY_FLOOR + (1f - INTENSITY_FLOOR) * elapsed;
    }

    private static float elapsedRatio(float durationMs) {
        if (changedAtMs <= 0L) {
            return 1f;
        }
        return Math.min(1f, (System.currentTimeMillis() - changedAtMs) / durationMs);
    }
}