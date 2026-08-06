package org.shee33.act0.battlefield.client;

/**
 * 客户端"比赛开局"全屏黑屏转场状态：倒计时结束→COMBAT 阶段开始那一刻触发，三段式
 * 淡入(变黑)→停留(纯黑)→淡出(恢复) —— 参照《战地》系列部署时的黑屏转场仪式感。
 *
 * <p>与 {@link ClientDeployFx}（"落地时黑屏快速消退"，仅淡出）语义不同、状态字段完全独立：
 * 那是"你降落了"的落地反馈，这里是"比赛开始了"的开局转场，两者互不覆盖触发时间，并存不干扰。
 *
 * <p>三段耗时总计 1000ms，控制在需求给定的 1~1.5s 量级内，纯黑停留期玩家看不清战场但时间
 * 很短，不影响开局节奏。曲线计算抽成 {@link #computeAlpha(long)} 纯函数（不读系统时钟），
 * 供 JUnit 直接覆盖三段边界值，不依赖 Minecraft classpath。
 */
public final class ClientMatchStartFx {

    /** 淡入阶段：0→255，ease-in 三次曲线（先慢后快，呼应黑幕缓缓合拢的降临感）。 */
    private static final long FADE_IN_MS = 350L;
    /** 停留阶段：保持纯黑 255。 */
    private static final long HOLD_MS = 300L;
    /** 淡出阶段：255→0，ease-out 三次曲线（先快后慢，与 {@link ClientDeployFx#fadeAlpha()} 同款曲线）。 */
    private static final long FADE_OUT_MS = 350L;

    private static final long HOLD_END_MS = FADE_IN_MS + HOLD_MS;
    private static final long TOTAL_MS = HOLD_END_MS + FADE_OUT_MS;

    private static long startedMs;

    private ClientMatchStartFx() {
    }

    /** 触发比赛开局黑屏转场。 */
    public static void trigger() {
        startedMs = System.currentTimeMillis();
    }

    /** 断开服务器连接时兜底清空，防止下次连到另一个世界/服务器时播放上一局遗留的黑屏转场。 */
    static void reset() {
        startedMs = 0L;
    }

    /** 全屏黑幕 alpha（0~255），三段式曲线，从触发时刻起算。 */
    public static int fadeAlpha() {
        if (startedMs <= 0L) {
            return 0;
        }
        return computeAlpha(System.currentTimeMillis() - startedMs);
    }

    /**
     * 纯函数：给定"触发后经过多少毫秒"，返回对应的黑幕 alpha（0~255）。不读系统时钟，
     * 供单元测试直接覆盖淡入开始/淡入结束/停留期间/淡出开始/淡出结束/淡出后归零等边界值。
     */
    static int computeAlpha(long elapsedMs) {
        if (elapsedMs < 0L) {
            return 0;
        }
        if (elapsedMs < FADE_IN_MS) {
            float t = elapsedMs / (float) FADE_IN_MS;
            float eased = t * t * t;
            return Math.round(255 * eased);
        }
        if (elapsedMs < HOLD_END_MS) {
            return 255;
        }
        if (elapsedMs >= TOTAL_MS) {
            return 0;
        }
        float t = (elapsedMs - HOLD_END_MS) / (float) FADE_OUT_MS;
        float eased = 1.0f - (float) Math.pow(1.0 - t, 3.0);
        return Math.round(255 * (1.0f - eased));
    }
}
