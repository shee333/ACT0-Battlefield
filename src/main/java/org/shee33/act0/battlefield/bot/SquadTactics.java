package org.shee33.act0.battlefield.bot;

/**
 * 小队协同的纯逻辑：集火加权、队友散开、压制／绕侧角色分配。MC-free，可单测。
 *
 * <p><b>三件事为什么放在一起。</b>它们共用同一个前提——"我知道队友是谁、在哪、在打谁"。这个前提
 * 由 MC 层的共享黑板提供（见 {@code BotSquadBoard}），本类只做拿到这些信息之后的算术。分开成三个类
 * 会让同一份输入被抄三遍。
 *
 * <p><b>协同必须可被玩家读懂。</b>看不出来的配合等于没有配合：集火要让玩家感到"被两个人夹击"，
 * 散开要让玩家看到队形而不是一坨，绕侧要让玩家意识到"有人从侧面来了"。因此各项参数都取到
 * 玩家能察觉的量级，而不是取到数学上最优。
 */
public final class SquadTactics {

    /**
     * 集火加成：队友已在交火的目标获得的额外威胁权重。
     *
     * <p>取 0.25 而非更高，是为了压在 {@link TargetScoring#DEFAULT_STICKINESS}（0.30）<b>之下</b>。
     * 这样集火只影响"还没锁定目标时选谁"，不会把已经在对枪的 bot 从当前目标上拽走——后者会退化成
     * 全队枪口随队友视野来回摆，正是黏滞机制要消除的那种"AI 味"。
     */
    public static final float FOCUS_FIRE_BONUS = 0.25F;

    /**
     * 队友间的期望最小间距（格）。
     *
     * <p>取 4 格：小于这个距离时两个 bot 会共享同一个掩体、被同一个连发扫到、并且从玩家视角看
     * 就是"一坨人"。再大则在走廊等窄地形里会互相把对方推离路径。
     */
    public static final double SPACING_MIN_BLOCKS = 4.0D;

    /** 低于此绕侧倾向就全员正面压制——独狼模式下绕侧只是把侧身送人。 */
    public static final float FLANK_ROLE_MIN_BIAS = 0.30F;

    /**
     * 绕侧者相对"直冲目标"方向的偏移角（度）。
     *
     * <p>60° 是能让玩家察觉"这人不是正面来的"的最小量级；再大会绕成横向平移、迟迟接不上火，
     * 压制方要独自顶太久。
     */
    public static final float FLANK_OFFSET_DEGREES = 60.0F;

    private SquadTactics() {
    }

    /** 小队中的战术角色。 */
    public enum Role {
        /** 保持中距离正面压制，把目标的注意力钉住。 */
        SUPPRESS,
        /** 走偏离轴线的路径接近，制造夹角。 */
        FLANK
    }

    /**
     * 集火加成：队友已在打这个目标时返回 {@link #FOCUS_FIRE_BONUS}，否则 0。
     *
     * <p>返回的是加到 {@link TargetScoring#score} 黏滞参数上的增量，而不是直接改分——这样集火与
     * 黏滞走同一条量纲，两者的相对强弱一眼可比。
     */
    public static float focusFireBonus(boolean teammateEngagingTarget) {
        return teammateEngagingTarget ? FOCUS_FIRE_BONUS : 0.0F;
    }

    /**
     * 队友散开的排斥强度，{@code 0}（间距充足，不干预）到 {@code 1}（完全重合，全力推开）。
     *
     * <p>用线性斜坡而非开关：开关会让 bot 在阈值上反复被推入推出，表现为贴着队友抽搐。
     *
     * @param distanceToNearestTeammate 最近队友的水平距离（格）；无队友时传
     *                                  {@link Double#MAX_VALUE}
     */
    public static double separationStrength(double distanceToNearestTeammate) {
        if (distanceToNearestTeammate >= SPACING_MIN_BLOCKS) {
            return 0.0D;
        }
        if (distanceToNearestTeammate <= 0.0D) {
            return 1.0D;
        }
        return 1.0D - distanceToNearestTeammate / SPACING_MIN_BLOCKS;
    }

    /**
     * 按队内稳定序号分配角色。
     *
     * <p><b>序号必须稳定</b>（调用方按队友 UUID 排序得出），否则角色每 tick 重排，bot 会在压制与
     * 绕侧之间反复切换、原地打转。序号 0 恒为压制：小队里必须有人钉住目标，否则两人一起绕侧
     * 就是一起脱火。
     */
    public static Role roleFor(int rank, float flankBias) {
        if (flankBias < FLANK_ROLE_MIN_BIAS) {
            return Role.SUPPRESS;
        }
        return rank > 0 && rank % 2 == 1 ? Role.FLANK : Role.SUPPRESS;
    }

    /**
     * 绕侧者的接近方向偏移（度）；压制角色恒返回 0。
     *
     * <p>符号按序号交替，使两名绕侧者分走目标两侧而不是叠在同一边。
     */
    public static float flankOffsetDegrees(int rank, float flankBias) {
        if (roleFor(rank, flankBias) != Role.FLANK) {
            return 0.0F;
        }
        boolean mirrored = (rank / 2) % 2 == 1;
        return mirrored ? -FLANK_OFFSET_DEGREES : FLANK_OFFSET_DEGREES;
    }

    /**
     * 把"自己→目标"的方向按绕侧角旋转，得到绕侧者应当行进的方向。
     *
     * <p>返回长度为 2 的数组 {@code {x, z}}，模长与输入一致（只转向不缩放），供 MC 层直接乘上
     * 期望距离得到落点。旋转矩阵用的是标准二维旋转，{@code x'=x·cos−z·sin}、{@code z'=x·sin+z·cos}。
     */
    public static double[] rotateApproach(double dirX, double dirZ, float offsetDegrees) {
        double rad = Math.toRadians(offsetDegrees);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);
        return new double[]{dirX * cos - dirZ * sin, dirX * sin + dirZ * cos};
    }
}
