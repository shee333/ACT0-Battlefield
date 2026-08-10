package org.shee33.act0.battlefield.client;

/**
 * 命中标记 / 受击红晕 / 屏幕微抖的状态 —— 《作战HUD动效规格文档》§2、§5.2。
 *
 * <p>自绘准星与开火扩散已按需求移除（准星交还原版/TaCZ），因此这里不再有 spread/onFire。
 *
 * <p>屏幕抖动<b>只位移 HUD 层，绝不动相机</b>（规格 §8 移植对照明确要求）：动相机会和游戏内
 * 的开火后坐力混在一起，玩家分不清哪个是"我被打了"哪个是"我在开枪"。
 */
final class CombatFeedbackAnimator {

    private static final long HITMARK_MS = 310L;
    private static final long VIGNETTE_MS = 420L;
    private static final long SHAKE_MS = 200L;

    private static long hitmarkStartMs = -1L;
    private static boolean hitmarkKill;
    private static long hurtStartMs = -1L;
    private static float shakeSeedX;
    private static float shakeSeedY;
    private static long lastHitmarkSourceMs;

    private CombatFeedbackAnimator() {
    }

    static void clear() {
        hitmarkStartMs = -1L;
        hurtStartMs = -1L;
        lastHitmarkSourceMs = 0L;
    }

    /** 自身受击：红晕闪 + 屏幕微抖。抖动方向每次随机，避免规律感。 */
    static void onSelfHurt(long now) {
        hurtStartMs = now;
        shakeSeedX = (float) (Math.random() * 4 - 2);
        shakeSeedY = (float) (Math.random() * 3 - 1.5);
    }

    /** 轮询服务端命中反馈（{@link ClientHitFeedback}），同一次命中只消费一次。 */
    static void pollHitFeedback(long now) {
        long src = ClientHitFeedback.startedMs();
        if (src > 0L && src != lastHitmarkSourceMs && ClientHitFeedback.active()) {
            lastHitmarkSourceMs = src;
            hitmarkStartMs = now;
            hitmarkKill = ClientHitFeedback.isKill();
        }
    }

    /** 命中 X 标记透明度；0 表示不画。 */
    static float hitmarkAlpha(long now) {
        if (hitmarkStartMs < 0L) {
            return 0f;
        }
        float t = clamp01((now - hitmarkStartMs) / (float) HITMARK_MS);
        return 1f - Tween.Ease.IN_CUBIC.apply(t);
    }

    /** 命中标记的一次性放大（0~90ms 内 1→1.25）。 */
    static float hitmarkScale(long now) {
        if (hitmarkStartMs < 0L) {
            return 1f;
        }
        float t = clamp01((now - hitmarkStartMs) / 90f);
        return 1f + 0.25f * Tween.Ease.OUT_CUBIC.apply(t);
    }

    static boolean hitmarkIsKill() {
        return hitmarkKill;
    }

    /**
     * 受击红晕透明度：一次性闪光与濒死呼吸底噪取较大者，这样低血时红晕不会在两次受击之间
     * 完全消失（规格 §5.2）。
     */
    static float vignetteAlpha(long now, int selfHpPct, boolean downed) {
        float base = CombatHudMath.vignetteBase(selfHpPct, downed);
        if (hurtStartMs < 0L) {
            return base;
        }
        float t = clamp01((now - hurtStartMs) / (float) VIGNETTE_MS);
        return Math.max(base, 0.4f * (1f - Tween.Ease.OUT_CUBIC.apply(t)));
    }

    /** HUD 层抖动偏移 [dx, dy]，随时间衰减。 */
    static float[] shakeOffset(long now) {
        if (hurtStartMs < 0L) {
            return NO_SHAKE;
        }
        float t = clamp01((now - hurtStartMs) / (float) SHAKE_MS);
        if (t >= 1f) {
            return NO_SHAKE;
        }
        float decay = 1f - Tween.Ease.OUT_CUBIC.apply(t);
        return new float[]{shakeSeedX * decay, shakeSeedY * decay};
    }

    private static final float[] NO_SHAKE = {0f, 0f};

    private static float clamp01(float v) {
        return v < 0f ? 0f : Math.min(v, 1f);
    }
}
