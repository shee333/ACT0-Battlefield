package org.shee33.act0.battlefield.bot;

/**
 * 行军战术：何时疾跑、行进中如何扫视。MC-free，可单测。
 *
 * <p>与 {@link CombatStance} 分开：那一套管的是"已经在对枪时如何走位"，本类管的是
 * "还没接火时如何赶路与观察"。两者的取值依据完全不同，混在一处会让参数含义变得模糊。
 */
public final class MarchTactics {

    private MarchTactics() {
    }

    /**
     * 允许疾跑的最小距离（格）。
     *
     * <p>取 24 而非贴着交火距离 {@value CombatStance#DEFAULT_ENGAGE_RANGE}：疾跑冲进交火距离
     * 是最糟的形态——TaCZ 在疾跑状态下拒绝开火（{@code ShootResult.IS_SPRINTING}），
     * 冲到面前才停下来收枪的 bot 等于把先手白送给玩家。多出的 8 格用于提前减速。
     */
    public static final double SPRINT_MIN_DISTANCE = 24.0D;

    /**
     * 扫视幅度（度，单侧）。
     *
     * <p><b>取值依据不是"修补盲区"。</b>{@link AimModel} 的视野参数本就是<b>半角</b>
     * （五档 ±50°/±60°/±70°/±60°/±80°，即全角 100°~160°），已与真人周边视觉相当，
     * 并不存在"侧面站个人却看不见"那种粗大盲区。
     *
     * <p>因此扫视只做两件事：给最窄的新手档补一点余量，以及让行进中的 bot 有头部动作
     * ——走路时脑袋纹丝不动的 bot 一眼就是机器。故取 30° 这个克制值：
     * 新手档扩到 ±80°、终极档扩到 ±110°，仍为身后留出真实的死角。
     * 若取到 ±50°，终极档将扩到 ±130°，近乎全知，反而成了对玩家不公平的设定。
     */
    public static final float SCAN_AMPLITUDE_DEGREES = 30.0F;

    /**
     * 扫视一个完整往复的周期（tick）。
     *
     * <p>80 tick（4 秒）：慢到让人看出是在"观察"而非抽搐，又快到不会长时间把某一侧全然放空。
     */
    public static final int SCAN_PERIOD_TICKS = 80;

    /**
     * 是否应当疾跑。
     *
     * <p><b>交火时一律不跑</b>：这不是手感偏好而是 API 事实——TaCZ 在疾跑状态下直接拒绝开火。
     * 一旦在交火中疾跑，bot 会变成完全不还手的靶子。
     *
     * @param engaged          是否已有交火目标
     * @param distanceToGoal   到行进目标的水平距离（格）
     */
    public static boolean shouldSprint(boolean engaged, double distanceToGoal) {
        return !engaged && distanceToGoal >= SPRINT_MIN_DISTANCE;
    }

    /**
     * 行进中头部相对身体的扫视偏移（度）。
     *
     * <p>用正弦而非三角波：真人转头在两端会自然减速，匀速往复看起来像机械扫描。
     * 相位以 tick 为自变量，故同一 bot 的扫视节奏稳定可复现，便于排查"是不是没看见"。
     *
     * @param tick        当前游戏刻
     * @param phaseOffset 每个 bot 的相位偏移，避免整队 bot 同步摆头
     */
    public static float scanOffsetDegrees(long tick, int phaseOffset) {
        double phase = 2.0D * Math.PI * ((tick + phaseOffset) % SCAN_PERIOD_TICKS) / SCAN_PERIOD_TICKS;
        return (float) (Math.sin(phase) * SCAN_AMPLITUDE_DEGREES);
    }
}
