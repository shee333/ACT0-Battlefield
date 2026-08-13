package org.shee33.act0.battlefield.bot;

/**
 * AI 士兵的瞄准模型：难度的<b>唯一</b>来源。MC-free 纯数据 + 纯函数，可单测。
 *
 * <p>本类的 {@link Difficulty} 枚举只提供<b>内置默认值</b>；运行时生效值取自
 * {@link BotDifficultyRegistry}，由 {@code config/act0_arcade/bot/difficulty.json} 覆盖，
 * 以便反复调手感时无需重编译。
 *
 * <p><b>为什么难度必须落在瞄准而非决策上。</b>玩家对"这个 bot 强不强"的感知约九成来自枪法手感，
 * 一成来自战术走位。若靠"让弱 bot 决策更差"来降难度，得到的是<b>笨</b>（走错路、卡墙）而不是
 * <b>菜</b>（枪法差）——玩家能立刻分辨这两者，前者直接出戏。COD 系全靠这套参数造难度梯度，
 * 决策逻辑五档共用一份。
 *
 * <p><b>误差圆为何拆成五个参数。</b>合并成"一个准度值"就无法分别控制两件本质不同的事：
 * 刚发现你时准不准（{@link #errorInitialDegrees} → {@link #errorSettledDegrees} 的收敛），
 * 以及连射之后散不散（{@link #errorPerShotDegrees} 累积 / {@link #errorRecoveryPerTick} 回落）。
 * 前者决定"探头对枪"是否可行，后者决定"拉开距离等他打空"是否可行——都是玩家的可用战术。
 *
 * <p><b>后坐力的建模方式。</b>对 bot 而言后坐力不是镜头抬升（它没有镜头），而是
 * <b>每发之后误差圆增大、停火后回落</b>。这样"压枪能力"成为一个可调数值，而非一段需要模拟的物理。
 *
 * <p><b>角度到格数的标定锚点。</b>距离 {@code d} 处的横向偏移约 {@code d·tan(误差角)}；
 * 30 格处 1° ≈ 0.52 格，而玩家碰撞箱宽 0.6 格。因此<b>1° 误差在 30 格上恰好是"擦边命中"</b>
 * ——下方各档的数值都以此为基准校准。
 */
public record AimModel(
        int reactionTicks,
        float fovHalfAngleDegrees,
        float turnRateDegPerTick,
        float errorInitialDegrees,
        float errorSettledDegrees,
        int errorConvergeTicks,
        float errorPerShotDegrees,
        float errorRecoveryPerTick,
        int burstMinShots,
        int burstMaxShots,
        int burstPauseTicks,
        int reacquireTicks) {

    /** 误差圆上限，防止长时间连射后 bot 朝天乱打。 */
    public static final float MAX_ERROR_DEGREES = 20.0F;

    public AimModel {
        if (reactionTicks < 0) {
            throw new IllegalArgumentException("reactionTicks must be >= 0, got " + reactionTicks);
        }
        if (fovHalfAngleDegrees <= 0.0F || fovHalfAngleDegrees > 180.0F) {
            throw new IllegalArgumentException("fovHalfAngleDegrees must be in (0,180], got " + fovHalfAngleDegrees);
        }
        if (turnRateDegPerTick <= 0.0F) {
            throw new IllegalArgumentException("turnRateDegPerTick must be > 0, got " + turnRateDegPerTick);
        }
        if (errorInitialDegrees < errorSettledDegrees) {
            throw new IllegalArgumentException("误差必须随跟踪时间收敛，要求 initial >= settled");
        }
        if (errorSettledDegrees < 0.0F) {
            throw new IllegalArgumentException("errorSettledDegrees must be >= 0");
        }
        if (errorConvergeTicks <= 0) {
            throw new IllegalArgumentException("errorConvergeTicks must be > 0, got " + errorConvergeTicks);
        }
        if (burstMinShots < 1 || burstMaxShots < burstMinShots) {
            throw new IllegalArgumentException("要求 1 <= burstMinShots <= burstMaxShots");
        }
        if (burstPauseTicks < 0 || reacquireTicks < 0) {
            throw new IllegalArgumentException("burstPauseTicks / reacquireTicks must be >= 0");
        }
    }

    /**
     * 五档难度。
     *
     * <p>各档只是参数集差异，决策逻辑完全共用——与 CS 的 {@code BotProfile.db}、
     * COD 的难度设定同构。
     *
     * <p><b>四档纯递增 + 一档旁支。</b>{@link #ROOKIE} → {@link #NORMAL} → {@link #ADVANCED}
     * → {@link #ULTIMATE} 是一条各项全面变强的阶梯，由 {@link BotDifficultyRegistry} 的单调性
     * 检查守住。{@link #REALISTIC} 不在这条链上：它<b>刻意</b>在感知维度弱于 ADVANCED，
     * 换取接近 ULTIMATE 的枪法，因此单调性检查会跳过它（详见该档说明）。
     */
    public enum Difficulty {

        /**
         * 新手：反应 600ms，收敛后 30 格上横向偏移约 1.57 格（碰撞箱宽 0.6，约 2.6 倍箱宽，
         * 远距离基本打不中），后坐力几乎压不住。面向新手，交火中玩家有充裕时间反应。
         */
        ROOKIE(12, 50.0F, 6.0F, 6.0F, 3.0F, 30, 1.20F, 0.15F, 2, 4, 18, 10),

        /** 普通：反应 400ms，收敛后 30 格上偏移约 0.79 格（约 1.3 倍箱宽，擦边命中），对枪互有胜负。 */
        NORMAL(8, 60.0F, 10.0F, 4.0F, 1.5F, 24, 0.90F, 0.25F, 3, 5, 14, 20),

        /** 高级：反应 250ms，收敛后 30 格上偏移约 0.37 格（略大于半个箱宽，稳定命中），压枪较好。 */
        ADVANCED(5, 70.0F, 16.0F, 3.0F, 0.7F, 16, 0.60F, 0.40F, 4, 7, 10, 40),

        /**
         * 写实：<b>枪法强、感知弱</b>——打起来像一个枪法很好的真人，而不是一台机器。
         *
         * <p>这一档不是"高级与终极之间插一档"，而是有意的旁支。枪法侧全部取在 ADVANCED 与
         * ULTIMATE 之间（反应 200ms 即人类极限、收敛误差 0.40°、压枪较稳），但两项感知参数
         * <b>刻意低于 ADVANCED</b>：
         * <ul>
         *   <li>视野半角 ±60°（ADVANCED 为 ±70°）——真人的有效注意锥远窄于理论周边视觉，
         *       故写实档可以被绕侧、被贴身，玩家的走位重新变得有用。</li>
         *   <li>目标记忆 1.2 秒（ADVANCED 为 2 秒）——真人会跟丢躲进掩体的敌人，
         *       "闪掩体断视线"因此在本档真正有效。</li>
         * </ul>
         *
         * <p>结果是一个<b>可被战术击败但难以对枪击败</b>的对手。这正是"写实"应有的形态：
         * 提高的是枪法上限，让出的是超人般的全知感知。若把它做成纯递增的一档，
         * 它就只是"次一级的终极"，没有独立存在的意义。
         */
        REALISTIC(4, 60.0F, 20.0F, 2.5F, 0.40F, 14, 0.50F, 0.50F, 4, 6, 9, 24),

        /**
         * 终极：反应 150ms（接近职业选手），收敛后近乎必中，压枪极稳，记忆你的位置长达 3 秒。
         * 刻意保留非零误差与反应延迟——零误差会让玩家判定为作弊而非强。
         */
        ULTIMATE(3, 80.0F, 24.0F, 2.0F, 0.25F, 10, 0.35F, 0.60F, 5, 9, 7, 60);

        private final AimModel model;

        Difficulty(int reactionTicks, float fov, float turnRate,
                   float errInit, float errSettled, int convergeTicks,
                   float errPerShot, float errRecovery,
                   int burstMin, int burstMax, int burstPause, int reacquire) {
            this.model = new AimModel(reactionTicks, fov, turnRate, errInit, errSettled,
                    convergeTicks, errPerShot, errRecovery,
                    burstMin, burstMax, burstPause, reacquire);
        }

        /**
         * 本档的<b>内置默认</b>参数。
         *
         * <p>刻意不叫 {@code model()}：生效值一律取自 {@link BotDifficultyRegistry}（配置可覆盖），
         * 直接读枚举会绕过配置。改名后这类误用在编译期即显形。
         */
        public AimModel defaults() {
            return model;
        }

        /** 面向玩家的中文档位名，供房间大厅与命令反馈使用。 */
        public String displayName() {
            return switch (this) {
                case ROOKIE -> "新手";
                case NORMAL -> "普通";
                case ADVANCED -> "高级";
                case REALISTIC -> "写实";
                case ULTIMATE -> "终极";
            };
        }

        /**
         * 本档是否属于"各项全面递增"的主阶梯。
         *
         * <p>{@link #REALISTIC} 返回 {@code false}：它以更窄的视野与更短的目标记忆换取更强的枪法，
         * 与主阶梯不可比。{@link BotDifficultyRegistry#monotonicityViolations()} 据此跳过它，
         * 否则那份"更高难度必须更强"的自检会把本档的设计意图报成一堆错误。
         */
        public boolean onEscalationLadder() {
            return this != REALISTIC;
        }
    }

    /**
     * 持续跟踪 {@code ticksOnTarget} tick 后的基础误差圆半径（度）。
     *
     * <p>刻意用线性收敛而非指数：策划看"多少 tick 后收敛到多少度"比看时间常数直观，
     * 满足 {@code AGENTS.md} 对"数值必须有设计依据"的要求。
     */
    public float baseErrorDegrees(int ticksOnTarget) {
        if (ticksOnTarget <= 0) {
            return errorInitialDegrees;
        }
        if (ticksOnTarget >= errorConvergeTicks) {
            return errorSettledDegrees;
        }
        float t = (float) ticksOnTarget / (float) errorConvergeTicks;
        return errorInitialDegrees + (errorSettledDegrees - errorInitialDegrees) * t;
    }

    /** 叠加后坐力累积后的总误差圆半径（度），已钳制到 {@link #MAX_ERROR_DEGREES}。 */
    public float totalErrorDegrees(int ticksOnTarget, float recoilDegrees) {
        float total = baseErrorDegrees(ticksOnTarget) + Math.max(0.0F, recoilDegrees);
        return Math.min(MAX_ERROR_DEGREES, total);
    }

    /** 目标是否落在视野锥内（{@code angleToTarget} 为与当前朝向的夹角绝对值）。 */
    public boolean withinFov(float angleToTargetDegrees) {
        return Math.abs(angleToTargetDegrees) <= fovHalfAngleDegrees;
    }

    /**
     * 给定距离下，某误差角对应的横向偏移（格）。
     *
     * <p>供调参时把角度换算成"能不能打中"的直观量：玩家碰撞箱宽 0.6 格，
     * 偏移超过 0.3 格即为脱靶。
     */
    public static double lateralOffsetBlocks(float errorDegrees, double distanceBlocks) {
        return distanceBlocks * Math.tan(Math.toRadians(errorDegrees));
    }
}
