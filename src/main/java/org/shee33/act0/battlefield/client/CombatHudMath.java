package org.shee33.act0.battlefield.client;

/**
 * 第一人称作战 HUD 的纯逻辑与配色，对应《作战HUD动效规格文档》§2/§3/§4/§5 的参数表。
 * 不依赖任何 Minecraft 类型，可直接 JUnit 单测。
 *
 * <p>规格 demo 的舞台是 900x540 的网页画布，而 MC 的 GUI 正交空间在常见 GUI 缩放下只有
 * 480~640 宽。像素值若原样照搬，右下武器栏会直接压到左下小队面板上。因此本类里的尺寸常量
 * 是按 MC 坐标系重新定标的（保持规格的<b>相对比例</b>与视觉层级，而非绝对像素）。
 */
final class CombatHudMath {

    // ---- 规格 §2 配色 ----
    static final int GREEN = 0xFF6EE27E;
    static final int GOLD = 0xFFFFD76A;
    static final int RED = 0xFFFF6A5E;
    static final int TEAM_BLUE = 0xFF4FA8FF;
    static final int TEXT = 0xFFE8EDF2;
    static final int PANEL_BG = 0x80101519;
    static final int BAR_TRACK = 0x26FFFFFF;
    static final int GHOST = 0xD9FFFFFF;

    /** 规格 §3.2：击杀提示整体透明度上限。 */
    static final float KILL_PROMPT_MAX_ALPHA = 0.88f;
    /** 规格 §3.3：连杀窗口。 */
    static final long STREAK_WINDOW_MS = 4000L;
    /** 规格 §3.2：无新击杀后的退场延迟。 */
    static final long KILL_PROMPT_HOLD_MS = 2000L;
    /** 规格 §5.1：倒地流血倒计时，与服务端 downedDurationTicks(15s) 对齐。 */
    static final long BLEED_MS = 15000L;

    private CombatHudMath() {
    }

    /** 规格 §5.2 三段变色：>60 绿 / 25~60 黄 / ≤25 红。 */
    static int healthColor(int hpPct) {
        if (hpPct > 60) {
            return GREEN;
        }
        return hpPct > 25 ? GOLD : RED;
    }

    /** 血量是否跨越了三段变色阈值——跨越时血条要做一次厚度脉冲。 */
    static boolean crossedThreshold(int oldPct, int newPct) {
        return healthColor(oldPct) != healthColor(newPct);
    }

    /** 规格 §5.2：濒死(≤25)判定，触发血条心跳脉冲与自身红晕呼吸。 */
    static boolean isCritical(int hpPct) {
        return hpPct <= 25;
    }

    /**
     * 规格 §3.3 连杀分档：1=白(0)、2~3=金(1)、≥4=红(2)。故障强度与配色都由档位驱动。
     */
    static int streakTier(int streak) {
        if (streak >= 4) {
            return 2;
        }
        return streak >= 2 ? 1 : 0;
    }

    static int tierColor(int tier) {
        return switch (tier) {
            case 2 -> RED;
            case 1 -> GOLD;
            default -> 0xFFFFFFFF;
        };
    }

    /** 规格 §3.2：故障时长 = 220 + tier×120 ms。 */
    static long glitchDurationMs(int tier) {
        return 220L + tier * 120L;
    }

    /** 规格 §3.2：故障幅度 = 2 + tier×1.5 px。 */
    static float glitchAmplitude(int tier) {
        return 2f + tier * 1.5f;
    }

    /** 规格 §3.3：单次击杀得分 = 100 + (streak−1)×25，逐次<b>累加</b>而非重置。 */
    static int killScore(int streak) {
        return 100 + Math.max(0, streak - 1) * 25;
    }

    /**
     * 规格 §5.2 濒死心跳：填充层透明度 {@code 0.65 + 0.35·sin(t/180)}。
     * sin 取绝对时间毫秒，不走补间对象（规格 §8「持续脉冲」移植对照）。
     */
    static float criticalPulseAlpha(long nowMs) {
        return 0.65f + 0.35f * (float) Math.sin(nowMs / 180.0);
    }

    /** 规格 §5.3 倒地呼吸：图标 {@code 0.6 + 0.4·sin(t/250)}。 */
    static float downedIconPulse(long nowMs) {
        return 0.6f + 0.4f * (float) Math.sin(nowMs / 250.0);
    }

    /** 规格 §5.3 倒地标签慢闪：{@code 0.55 + 0.45·sin(t/300)}。 */
    static float downedTagPulse(long nowMs) {
        return 0.55f + 0.45f * (float) Math.sin(nowMs / 300.0);
    }

    /** 规格 §5.2：自身濒死时红晕保持低幅呼吸 {@code 0.12 + 0.06·sin(t/300)}；未濒死为 0。 */
    static float vignetteBase(int selfHpPct, boolean downed) {
        if (downed || !isCritical(selfHpPct)) {
            return 0f;
        }
        return 0.12f + 0.06f * (float) Math.sin(System.currentTimeMillis() / 300.0);
    }

    /**
     * 规格 §5.2 阈值厚度脉冲：{@code 1 + 0.9·sin(v·π)}，峰值 ×1.9 后回到 1。
     * 传入的是<b>已缓动</b>的进度 v。
     */
    static float thresholdPulseScale(float easedV) {
        return 1f + 0.9f * (float) Math.sin(Math.min(1f, Math.max(0f, easedV)) * Math.PI);
    }

    /**
     * 规格 §3.2 名字乱码解码：按线性进度逐字锁定，未锁定的位用伪随机字符替换。
     *
     * <p>字符集与 seed 同时决定输出，seed 由调用方按帧传入（用绘制帧号而非 Random 实例，
     * 保证同一帧内多次调用结果一致、且无需持有可变随机源即可单测）。
     */
    static String scramble(String finalText, float progress, long seed) {
        if (finalText == null || finalText.isEmpty()) {
            return "";
        }
        int locked = (int) Math.floor(Math.min(1f, Math.max(0f, progress)) * finalText.length());
        if (locked >= finalText.length()) {
            return finalText;
        }
        StringBuilder sb = new StringBuilder(finalText.length());
        for (int i = 0; i < finalText.length(); i++) {
            if (i < locked) {
                sb.append(finalText.charAt(i));
            } else {
                sb.append(GLYPHS.charAt(Math.floorMod(seed * 31 + i * 17L, GLYPHS.length())));
            }
        }
        return sb.toString();
    }

    private static final String GLYPHS = "ABCDEFGHJKLMNPQRSTUVWXYZ0123456789#$%";

    // ---- 规格 §4.1 武器栏几何（按 MC GUI 坐标系重新定标） ----
    /** 收纳态槽高。 */
    static final int SLOT_H = 17;
    /** 手持态槽高（规格 24→35 的等比缩放）。 */
    static final int SLOT_H_ACTIVE = 25;
    /** 手持态宽度系数。 */
    static final float SLOT_ACTIVE_W_MUL = 1.12f;
    /** 斜切角度（规格 −8°）。 */
    static final float SKEW_DEG = -8f;
    static final int SLOT_GAP = 4;

    /**
     * 槽位宽度，对应 Arcade {@code LoadoutSlot} 的六槽配装：0 主武器 / 1 副武器 / 2 近战 /
     * 3-4 装置 / 5 投掷物。规格的 56/44/34 三档按 MC 坐标系缩放为 40/32/24。
     *
     * <p>这里刻意<b>不</b>反射去读 Arcade 的 {@code LoadoutSlot}：战地对 Arcade 是可选依赖，
     * 且这只是一张视觉宽度表——Arcade 缺席时玩家仍在用原版快捷栏前六格，这张表依然成立。
     */
    static int slotWidth(int hotbarIndex) {
        return switch (hotbarIndex) {
            case 0 -> 40;
            case 1 -> 32;
            default -> 24;
        };
    }

    /** 武器栏槽位数（= 配装六槽）。 */
    static final int SLOT_COUNT = 6;

    /** 槽位行总宽（全部收纳态，用于右对齐布局起点计算）。 */
    static int slotRowWidth() {
        int total = 0;
        for (int i = 0; i < SLOT_COUNT; i++) {
            total += slotWidth(i);
        }
        return total + SLOT_GAP * (SLOT_COUNT - 1);
    }

    /** 武器信息块（武器名/弹药/冷却条）宽度与它跟槽位行之间的间隔。 */
    static final int INFO_GAP = 10;
    static final int INFO_W = 68;
    /** 队友面板与武器栏之间必须留出的最小间隙。 */
    static final int COLLISION_PAD = 6;
    /**
     * 血条最窄可接受宽度。取 24 而非更大值，是因为 1280x720 屏在 GUI scale 3 下 guiWidth 只有
     * 426，小地图(84)+间距+武器栏(266)之后留给队友面板的横向空间就只剩三十来像素；下限定高了
     * 会把面板顶到武器栏上，定成 24 则刚好能在这个真实存在的分辨率下无重叠且仍读得出比例。
     */
    static final int SQUAD_BAR_MIN_W = 24;

    /** 右下武器栏的最左像素。 */
    static int weaponBarLeft(int guiWidth, int margin) {
        return guiWidth - margin - (slotRowWidth() + INFO_GAP + INFO_W);
    }

    /**
     * 队友面板可用的右边界。面板贴在小地图右侧，右边不得撞上武器栏。
     *
     * <p>MC 的 GUI 宽度随缩放变化很大（GUI scale 4 下 1920 屏只有 480，某些分辨率甚至 427），
     * 而规格的像素值来自 900 宽的网页舞台。窄屏下两侧面板必然相撞，因此这里返回的是"允许的
     * 右边界"，由渲染侧据此压缩血条宽度——宁可血条短一点，也不能让两个面板叠在一起。
     */
    static int squadPanelMaxRight(int guiWidth, int margin, int panelLeft) {
        return Math.max(panelLeft + SQUAD_BAR_MIN_W, weaponBarLeft(guiWidth, margin) - COLLISION_PAD);
    }

    /**
     * 队友面板血条的实际宽度：在偏好宽度与剩余空间之间取小，再受最小宽度托底。
     *
     * <p>渲染侧必须走这个方法而不是自己算——之前渲染侧自带一个更大的下限，会把面板顶出
     * {@link #squadPanelMaxRight} 划定的边界，让防重叠形同虚设。
     *
     * @param barIndent 血条相对面板左缘的缩进（图标列宽度）
     */
    static int squadBarWidth(int panelLeft, int panelMaxRight, int barIndent, int preferredW) {
        int room = panelMaxRight - panelLeft - barIndent;
        return Math.max(SQUAD_BAR_MIN_W, Math.min(preferredW, room));
    }
}
