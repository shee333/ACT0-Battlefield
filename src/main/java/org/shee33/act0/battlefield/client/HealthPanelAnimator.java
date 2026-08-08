package org.shee33.act0.battlefield.client;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 小队与自身血量的动效状态 —— 《作战HUD动效规格文档》§5。
 *
 * <p>核心是掉血四联动：填充层 150ms 快落、白色损耗残段停顿 280ms 后再收缩、整行红闪、
 * 跨越三段变色阈值时血条厚度脉冲一次。残段是格斗游戏式的"刚掉了这么多"读数，停顿是它的
 * 全部意义所在——立刻收缩就退化成普通血条了。
 *
 * <p>每个成员一份状态，按玩家名索引（{@code SquadMateHudDto} 没带 UUID，名字是唯一可用的
 * 稳定键；同局内重名玩家不存在，MC 用户名本身唯一）。
 */
final class HealthPanelAnimator {

    private static final long FILL_MS = 150L;
    private static final long GHOST_HOLD_MS = 280L;
    private static final long GHOST_SHRINK_MS = 240L;
    private static final long ROW_FLASH_MS = 380L;
    private static final long REVIVE_FLASH_MS = 500L;
    private static final long THRESHOLD_PULSE_MS = 300L;
    private static final long HEAL_MS = 350L;
    private static final long ROW_INTRO_MS = 280L;
    private static final long ROW_INTRO_STAGGER_MS = 90L;
    private static final long DOWN_ICON_MS = 340L;

    /** 单个成员的动画状态。 */
    static final class MemberState {
        int shownPct = 100;
        int fromPct = 100;
        int lossPct;
        long changeStartMs;
        long thresholdStartMs = -1L;
        long hurtFlashStartMs = -1L;
        long reviveFlashStartMs = -1L;
        long downedStartMs = -1L;
        boolean healing;
    }

    private static final Map<String, MemberState> STATES = new LinkedHashMap<>();
    private static long introStartMs;

    private HealthPanelAnimator() {
    }

    static void playIntro(long now) {
        introStartMs = now;
    }

    static void clear() {
        STATES.clear();
        introStartMs = 0L;
    }

    static float rowIntroProgress(int rowIndex, long now) {
        if (introStartMs <= 0L) {
            return 1f;
        }
        long delay = 150L + rowIndex * ROW_INTRO_STAGGER_MS;
        return Tween.Ease.OUT_CUBIC.apply(clamp01((now - introStartMs - delay) / (float) ROW_INTRO_MS));
    }

    /**
     * 喂入某成员的最新血量与倒地状态，内部据此判断是掉血还是治疗并起对应动画。
     *
     * @param hpPct  0~100
     * @param downed 是否倒地
     */
    static MemberState feed(String name, int hpPct, boolean downed, long now) {
        MemberState s = STATES.computeIfAbsent(name, k -> {
            MemberState fresh = new MemberState();
            fresh.shownPct = hpPct;
            fresh.fromPct = hpPct;
            return fresh;
        });

        if (downed && s.downedStartMs < 0L) {
            s.downedStartMs = now;
        } else if (!downed && s.downedStartMs >= 0L) {
            s.downedStartMs = -1L;
            s.reviveFlashStartMs = now;
            s.shownPct = hpPct;
            s.fromPct = hpPct;
            s.lossPct = 0;
        }

        if (hpPct != s.shownPct && !downed) {
            boolean isDamage = hpPct < s.shownPct;
            s.fromPct = s.shownPct;
            s.lossPct = isDamage ? s.shownPct - hpPct : 0;
            s.healing = !isDamage;
            s.changeStartMs = now;
            if (isDamage) {
                s.hurtFlashStartMs = now;
            }
            if (CombatHudMath.crossedThreshold(s.shownPct, hpPct)) {
                s.thresholdStartMs = now;
            }
            s.shownPct = hpPct;
        }
        return s;
    }

    /** 移除本帧未出现的成员（离队/退出），避免状态表无限增长。 */
    static void retainOnly(Set<String> presentNames) {
        STATES.keySet().removeIf(k -> !presentNames.contains(k));
    }

    /** 填充层当前宽度百分比：掉血 150ms outCubic 快落，治疗 350ms outCubic 回升。 */
    static float fillPct(MemberState s, long now) {
        long dur = s.healing ? HEAL_MS : FILL_MS;
        float t = Tween.Ease.OUT_CUBIC.apply(clamp01((now - s.changeStartMs) / (float) dur));
        return s.fromPct + (s.shownPct - s.fromPct) * t;
    }

    /**
     * 白色损耗残段的当前宽度百分比：停顿 {@code GHOST_HOLD_MS} 后 inCubic 收缩到 0。
     * 治疗时无残段。
     */
    static float ghostPct(MemberState s, long now) {
        if (s.healing || s.lossPct <= 0) {
            return 0f;
        }
        long age = now - s.changeStartMs;
        if (age < GHOST_HOLD_MS) {
            return s.lossPct;
        }
        float t = clamp01((age - GHOST_HOLD_MS) / (float) GHOST_SHRINK_MS);
        return s.lossPct * (1f - Tween.Ease.IN_CUBIC.apply(t));
    }

    /** 整行受击红闪透明度 0.16→0。 */
    static float hurtFlashAlpha(MemberState s, long now) {
        if (s.hurtFlashStartMs < 0L) {
            return 0f;
        }
        float t = clamp01((now - s.hurtFlashStartMs) / (float) ROW_FLASH_MS);
        return 0.16f * (1f - Tween.Ease.OUT_CUBIC.apply(t));
    }

    /** 救起确认绿闪透明度 0.14→0。 */
    static float reviveFlashAlpha(MemberState s, long now) {
        if (s.reviveFlashStartMs < 0L) {
            return 0f;
        }
        float t = clamp01((now - s.reviveFlashStartMs) / (float) REVIVE_FLASH_MS);
        return 0.14f * (1f - Tween.Ease.OUT_CUBIC.apply(t));
    }

    /** 跨阈值厚度脉冲，峰值 ×1.9；未触发或已结束返回 1。 */
    static float thresholdPulse(MemberState s, long now) {
        if (s.thresholdStartMs < 0L) {
            return 1f;
        }
        float raw = clamp01((now - s.thresholdStartMs) / (float) THRESHOLD_PULSE_MS);
        if (raw >= 1f) {
            s.thresholdStartMs = -1L;
            return 1f;
        }
        return CombatHudMath.thresholdPulseScale(Tween.Ease.OUT_BACK.apply(raw));
    }

    /** 倒地图标旋转换形进度 0..1（340ms outBack）。 */
    static float downedIconProgress(MemberState s, long now) {
        if (s.downedStartMs < 0L) {
            return 0f;
        }
        return Tween.Ease.OUT_BACK.apply(clamp01((now - s.downedStartMs) / (float) DOWN_ICON_MS));
    }

    /**
     * 倒地流血剩余比例 1→0。这是救援紧迫度的读数，耗尽即阵亡。
     *
     * <p>服务端权威的剩余秒数若可得应优先采用；此处只在拿不到时按本地计时兜底。
     */
    static float bleedRemaining(MemberState s, long now) {
        if (s.downedStartMs < 0L) {
            return 0f;
        }
        return clamp01(1f - (now - s.downedStartMs) / (float) CombatHudMath.BLEED_MS);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : Math.min(v, 1f);
    }
}
