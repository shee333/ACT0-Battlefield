package org.shee33.act0.battlefield.client;

import net.minecraft.Util;
import net.minecraft.util.Mth;

/**
 * 补间引擎 —— 规格文档 §1.1 的六个缓动函数 + 追赶插值手法,port 到 Minecraft 客户端。
 *
 * <p>与文档 demo 的 {@code performance.now()}/{@code requestAnimationFrame}/Promise 不同,这里用
 * {@link Util#getMillis()} 作为真实时间源(不绑 server tick),每帧({@code RenderGuiEvent.Post})
 * 由调用方直接采样 {@link Ease#apply(float)} 计算当前进度 —— Java 没有协程/Promise,所以不做
 * {@code tw()} 那样的可 await 时间轴,而是让调用方持有 {@code startAtMs}/{@code durationMs} 并
 * 每帧轮询(参照 {@link CaptureFocusAnimator} 的用法)。
 */
final class Tween {

    private Tween() {
    }

    /** 真实时间源:不绑 tick,見{@link Util#getMillis()}。 */
    static long now() {
        return Util.getMillis();
    }

    /**
     * 追赶插值(文档 §1.3.1):显示值每帧向真实值追赶 {@code shown += (real - shown) * factor}。
     * 用于环形进度的平滑显示 —— 驱动源是服务端已同步的真实进度,而非固定时长。
     */
    static float chase(float shown, float real, float factor) {
        return shown + (real - shown) * factor;
    }

    /** 文档 §1.1 的六个缓动公式,原样 port(仅由 JS Math.pow 改写为 Java 等价实现)。 */
    enum Ease {
        /** 仅用于进度推进(线性)。 */
        LINEAR,
        /** 入场默认:快出慢停。 */
        OUT_EXPO,
        /** 弹入(有过冲)。 */
        OUT_BACK,
        /** 柔和减速。 */
        OUT_CUBIC,
        /** 平缓飞行(归位用)。 */
        IN_OUT_CUBIC,
        /** 退场默认:慢起快走。 */
        IN_CUBIC;

        float apply(float tRaw) {
            float t = Mth.clamp(tRaw, 0f, 1f);
            return switch (this) {
                case LINEAR -> t;
                case OUT_EXPO -> t >= 1f ? 1f : 1f - (float) Math.pow(2, -10 * t);
                case OUT_BACK -> {
                    float c = 1.70158f;
                    float d = c + 1f;
                    float x = t - 1f;
                    yield 1f + d * x * x * x + c * x * x;
                }
                case OUT_CUBIC -> 1f - (float) Math.pow(1f - t, 3);
                case IN_OUT_CUBIC -> t < 0.5f
                        ? 4f * t * t * t
                        : 1f - (float) Math.pow(-2f * t + 2f, 3) / 2f;
                case IN_CUBIC -> t * t * t;
            };
        }
    }

    /**
     * 轻量"动画实例":记录 startAtMs/durationMs/delayMs/easing,每帧调用 {@link #rawT} / {@link #easedT}
     * 轮询即可,不需要回调注册表(文档要求的"onUpdate 回调每帧遍历更新"在我们这里就是"调用方每帧自己读取
     * 当前值再绘制",效果等价,省去一层不必要的间接)。
     */
    static final class Anim {
        private long startAtMs = -1L;
        private long durationMs = 1L;
        private long delayMs;
        private Ease ease = Ease.LINEAR;

        void start(long nowMs, long durationMs, Ease ease) {
            start(nowMs, durationMs, ease, 0L);
        }

        void start(long nowMs, long durationMs, Ease ease, long delayMs) {
            this.startAtMs = nowMs;
            this.durationMs = Math.max(1L, durationMs);
            this.ease = ease;
            this.delayMs = Math.max(0L, delayMs);
        }

        boolean isRunning() {
            return startAtMs >= 0L;
        }

        /** 计入延迟后的原始线性进度 [0,1];尚在延迟中返回 0。 */
        float rawT(long nowMs) {
            if (startAtMs < 0L) {
                return 1f;
            }
            long elapsed = nowMs - startAtMs - delayMs;
            if (elapsed <= 0L) {
                return 0f;
            }
            return Mth.clamp(elapsed / (float) durationMs, 0f, 1f);
        }

        float easedT(long nowMs) {
            return ease.apply(rawT(nowMs));
        }

        boolean isDone(long nowMs) {
            return startAtMs >= 0L && rawT(nowMs) >= 1f;
        }

        long elapsedMs(long nowMs) {
            return startAtMs < 0 ? 0 : Math.max(0, nowMs - startAtMs - delayMs);
        }

        void reset() {
            startAtMs = -1L;
        }
    }
}
