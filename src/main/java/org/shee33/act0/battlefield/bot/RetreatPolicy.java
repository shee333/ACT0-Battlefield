package org.shee33.act0.battlefield.bot;

/**
 * 低血脱离与再交火的状态机。MC-free，可单测。
 *
 * <p><b>为什么必须带迟滞。</b>只用一条血线判断"该不该撤"会得到最难看的一种 bot：血量在阈值附近
 * 抖动时它前进一步、后退一步，原地抽搐。因此脱离与再交火用两条不同的线
 * （{@value #DEFAULT_REENGAGE_HEALTH} 远高于脱离线），再叠一条最短脱离时长
 * ——三者共同保证一次脱离是"一段可被玩家读懂的行为"，而不是一次数值颤动。
 *
 * <p><b>与呼吸回血的配合。</b>本模组的回血需要先脱离伤害若干秒才开始（见
 * {@code BattlefieldConfig#BREATH_HEAL_DELAY_TICKS}，默认 5 秒）。这意味着脱离后的 bot 事实上会
 * 在 {@link State#BREAK_OFF} 停留远超最短时长——最短时长防的是抖动，真正决定何时回来的是回血速度。
 * 玩家体验到的就是战地系列那种"打退一个人，他躲一阵又从别的角度回来"的节奏。
 */
public final class RetreatPolicy {

    /** 默认脱离血线：30%。 */
    public static final float DEFAULT_BREAK_OFF_HEALTH = 0.30F;

    /**
     * 默认再交火血线：70%。
     *
     * <p>与脱离线拉开 40 个百分点，是为了让"撤退—回血—再战"成为一个完整的循环。若取到 40%，
     * bot 会在刚够站住时就冲回去，随即再次被打到脱离线，表现为在掩体口反复进出。
     */
    public static final float DEFAULT_REENGAGE_HEALTH = 0.70F;

    /** 默认最短脱离时长（tick）：3 秒。纯粹用于抑制阈值抖动。 */
    public static final int DEFAULT_MIN_BREAK_OFF_TICKS = 60;

    /** 交火意愿状态。 */
    public enum State {
        /** 正常交火。 */
        FIGHT,
        /** 主动脱离：拉开距离、断视线、等待回血。 */
        BREAK_OFF
    }

    private final float breakOffHealth;
    private final float reengageHealth;
    private final int minBreakOffTicks;

    private State state = State.FIGHT;
    private int ticksInState;

    public RetreatPolicy() {
        this(DEFAULT_BREAK_OFF_HEALTH, DEFAULT_REENGAGE_HEALTH, DEFAULT_MIN_BREAK_OFF_TICKS);
    }

    /**
     * @param breakOffHealth   脱离血线（0~1）
     * @param reengageHealth   再交火血线（0~1）；必须严格高于脱离线，否则迟滞不成立
     * @param minBreakOffTicks 最短脱离时长（tick）
     */
    public RetreatPolicy(float breakOffHealth, float reengageHealth, int minBreakOffTicks) {
        if (reengageHealth <= breakOffHealth) {
            throw new IllegalArgumentException(
                    "再交火血线必须高于脱离血线，否则没有迟滞：breakOff=" + breakOffHealth
                            + " reengage=" + reengageHealth);
        }
        this.breakOffHealth = clamp01(breakOffHealth);
        this.reengageHealth = clamp01(reengageHealth);
        this.minBreakOffTicks = Math.max(0, minBreakOffTicks);
    }

    private static float clamp01(float v) {
        return Math.max(0.0F, Math.min(1.0F, v));
    }

    /**
     * 推进一 tick 并返回新状态。
     *
     * @param healthFraction 当前血量比例（当前值 / 上限），0~1
     */
    public State tick(float healthFraction) {
        ticksInState++;
        State next = switch (state) {
            case FIGHT -> healthFraction <= breakOffHealth ? State.BREAK_OFF : State.FIGHT;
            case BREAK_OFF -> ticksInState >= minBreakOffTicks && healthFraction >= reengageHealth
                    ? State.FIGHT : State.BREAK_OFF;
        };
        if (next != state) {
            state = next;
            ticksInState = 0;
        }
        return state;
    }

    public State state() {
        return state;
    }

    public boolean shouldBreakOff() {
        return state == State.BREAK_OFF;
    }

    /** 已在当前状态停留的 tick 数，供测试与调试观察节奏。 */
    public int ticksInState() {
        return ticksInState;
    }

    /**
     * 复活或换局时重置为交火状态。
     *
     * <p>不重置会让"上一条命残血脱离中"的状态跟着新生命走：满血复活的 bot 会莫名继续躲。
     */
    public void reset() {
        state = State.FIGHT;
        ticksInState = 0;
    }

    public float breakOffHealth() {
        return breakOffHealth;
    }

    public float reengageHealth() {
        return reengageHealth;
    }
}
