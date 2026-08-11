package org.shee33.act0.battlefield.core;

/**
 * "按住渐出、松开渐进复原"的补间。
 *
 * <p>做成可打断的形式：每次按下/松开时记录<b>当前值</b>作为新的起点，而不是每次都从 0 或 1 开始。
 * 否则在渐出播到一半时松手，画面会先瞬间跳回全亮再往回走——这种回跳比不做动效还难看。
 */
public final class HoldFade {

    /** 渐出/渐入时长（毫秒）。够短，不至于让"想快速瞄一眼比分"变得拖沓。 */
    public static final int DURATION_MS = 180;

    private HoldFade() {
    }

    /** 从 {@code from} 按 ease-out 推进到 {@code target}，超时后钳在 {@code target}。 */
    public static float eased(float from, float target, long elapsedMs, int durationMs) {
        if (durationMs <= 0 || elapsedMs >= durationMs) {
            return target;
        }
        if (elapsedMs <= 0) {
            return from;
        }
        return from + (target - from) * easeOutCubic((float) elapsedMs / durationMs);
    }

    /** {@code 1-(1-t)^3}，收尾平缓，与本仓库其他菜单动效同一条曲线。 */
    public static float easeOutCubic(float t) {
        float c = t < 0f ? 0f : Math.min(1f, t);
        float inv = 1f - c;
        return 1f - inv * inv * inv;
    }
}
