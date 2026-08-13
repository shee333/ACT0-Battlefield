package org.shee33.act0.battlefield.bot;

/**
 * 交火姿态决策：按与敌人的距离选择推进／保持／后撤，并管理侧移方向的翻转节奏。MC-free，可单测。
 *
 * <p><b>为什么需要"保持距离"这一档。</b>只有推进与站桩两种状态时，bot 会一路贴到玩家脸上，
 * 交火退化成互相贴脸乱扫——枪法、掩体、距离全部失去意义。中距离带内改为横向机动，
 * 才能得到战地系列那种"互相压制、找角度"的步兵交火，也让玩家的走位真正有用。
 *
 * <p>三档的边界刻意留出很宽的保持带（{@value #DEFAULT_ENGAGE_RANGE} 到
 * {@value #DEFAULT_TOO_CLOSE_RANGE} 格），使 bot 在带内往复移动时不会反复跨过阈值
 * 而在推进与后撤之间抽搐。
 */
public final class CombatStance {

    /** 超过此距离转为推进（格）。TaCZ 步枪在此距离内命中稳定，也是战地步兵交火的典型中距离。 */
    public static final double DEFAULT_ENGAGE_RANGE = 16.0D;

    /** 近于此距离转为后撤（格）。再近就成了贴脸互怼，枪法与走位都不再可读。 */
    public static final double DEFAULT_TOO_CLOSE_RANGE = 5.0D;

    /**
     * 侧移方向的翻转间隔（tick）。
     *
     * <p>30 tick（1.5 秒）与玩家的跟枪节奏相当：更短会让 bot 看起来在原地抽搐且难以被命中，
     * 更长则退化为单向平移、玩家一跟就中。
     */
    public static final int DEFAULT_STRAFE_FLIP_TICKS = 30;

    /** 交火时的移动姿态。 */
    public enum Mode {
        /** 距离过远，向敌人推进。 */
        ADVANCE,
        /** 处于交火距离，横向机动。 */
        HOLD,
        /** 距离过近，后撤拉开。 */
        RETREAT
    }

    private final double engageRange;
    private final double tooCloseRange;
    private final int strafeFlipTicks;

    private int strafeSign = 1;
    private int ticksSinceFlip;

    public CombatStance() {
        this(DEFAULT_ENGAGE_RANGE, DEFAULT_TOO_CLOSE_RANGE, DEFAULT_STRAFE_FLIP_TICKS);
    }

    public CombatStance(double engageRange, double tooCloseRange, int strafeFlipTicks) {
        this.engageRange = engageRange;
        this.tooCloseRange = tooCloseRange;
        this.strafeFlipTicks = Math.max(1, strafeFlipTicks);
    }

    /** 按当前距离选择姿态。 */
    public Mode modeFor(double distance) {
        if (distance > engageRange) {
            return Mode.ADVANCE;
        }
        if (distance < tooCloseRange) {
            return Mode.RETREAT;
        }
        return Mode.HOLD;
    }

    /**
     * 推进一 tick 的侧移记账。
     *
     * <p>撞墙立即翻向而不等计时器：贴着墙继续推同一方向的 bot 会原地卡住不动，
     * 那比走错方向更糟——玩家看到的是一个明显坏掉的假人。
     *
     * @param blocked 上一 tick 是否发生水平碰撞
     */
    public void tick(boolean blocked) {
        ticksSinceFlip++;
        if (blocked || ticksSinceFlip >= strafeFlipTicks) {
            flip();
        }
    }

    /** 当前侧移方向：{@code +1} 或 {@code -1}，与世界侧向基向量同号。 */
    public int strafeSign() {
        return strafeSign;
    }

    /** 立即翻转侧移方向并重置计时。 */
    public void flip() {
        strafeSign = -strafeSign;
        ticksSinceFlip = 0;
    }

    /** 距上次翻向经过的 tick 数，供测试与调试观察节奏。 */
    public int ticksSinceFlip() {
        return ticksSinceFlip;
    }
}
