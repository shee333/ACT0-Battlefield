package org.shee33.act0.battlefield.bot;

import java.util.Objects;
import java.util.Random;

/**
 * 单个 AI 士兵的瞄准状态机：把 {@link AimModel} 的静态参数变成随时间演化的行为。MC-free，可单测。
 *
 * <p>承载三件事，每一件都对应一个玩家可利用的战术：
 * <ul>
 *   <li><b>反应延迟</b>——刚获得目标后需持续跟踪 {@link AimModel#reactionTicks()} tick 才允许开火，
 *       因此"探头即缩"对 bot 有效；</li>
 *   <li><b>点射纪律</b>——每轮点射发数在 {@code [burstMin, burstMax]} 内随机，打完强制停顿。
 *       这段停顿是留给玩家的进攻窗口，也是 bot 不像"持续激光"的原因；</li>
 *   <li><b>目标记忆</b>——失去视线后仍记住目标 {@link AimModel#reacquireTicks()} tick，
 *       期间绕后有效但拖延过久 bot 就会遗忘。</li>
 * </ul>
 *
 * <p><b>后坐力用"始终衰减 + 开火时叠加"实现</b>而非"开火时不衰减"：每 tick 无条件回落一档，
 * 开火时叠加的量大于单 tick 回落量，因此连射期间净增长、停火后自然回落。少一个"本 tick 是否开火"
 * 的状态位，也就少一处漏更新的可能。
 *
 * <p>每 tick 必须先调 {@link #tick(boolean)} 再查询 {@link #canFire()}。
 */
public final class AimTracker {

    private final AimModel model;
    private final Random random;

    private int ticksOnTarget;
    private int ticksSinceSeen;
    private float recoilDegrees;
    private int shotsInBurst;
    private int burstLength;
    private int burstPauseRemaining;

    public AimTracker(AimModel model, long seed) {
        this.model = Objects.requireNonNull(model, "model");
        this.random = new Random(seed);
        this.burstLength = rollBurstLength();
    }

    /**
     * 推进一 tick。
     *
     * @param targetVisible 本 tick 是否能看见目标（调用方负责视野锥与视线判定）
     */
    public void tick(boolean targetVisible) {
        if (targetVisible) {
            ticksSinceSeen = 0;
            ticksOnTarget++;
        } else {
            ticksSinceSeen++;
            if (ticksSinceSeen > model.reacquireTicks()) {
                // 超出记忆期：彻底遗忘，下次重新获得目标要重新走一遍反应延迟。
                ticksOnTarget = 0;
            }
        }
        recoilDegrees = Math.max(0.0F, recoilDegrees - model.errorRecoveryPerTick());
        if (burstPauseRemaining > 0) {
            burstPauseRemaining--;
        }
    }

    /** 是否允许扣扳机（仅判断反应延迟与点射节奏；视线、朝向、武器冷却由调用方另行判断）。 */
    public boolean canFire() {
        return burstPauseRemaining <= 0 && ticksOnTarget >= model.reactionTicks();
    }

    /** 当前瞄准误差圆半径（度），含后坐力累积。 */
    public float errorDegrees() {
        return model.totalErrorDegrees(ticksOnTarget, recoilDegrees);
    }

    /** 登记开出一发：累积后坐力，并在打满本轮点射后进入停顿。 */
    public void onShotFired() {
        shotsInBurst++;
        recoilDegrees += model.errorPerShotDegrees();
        if (shotsInBurst >= burstLength) {
            shotsInBurst = 0;
            burstLength = rollBurstLength();
            burstPauseRemaining = model.burstPauseTicks();
        }
    }

    /**
     * 在当前误差圆内均匀采样一个瞄准偏移。
     *
     * <p>用 {@code r = R·√u} 而非 {@code r = R·u} 采样半径：后者会让样本向圆心聚集，
     * 使 bot 的实际命中率显著高于误差圆所声称的水平，难度标定随之失真。
     *
     * <p><b>偏移施加在 bot 的真实朝向上，而非只偏转弹道。</b>若朝向精确指向目标却让子弹偏出，
     * 旁观者看到的是"枪口对着我却打不中"，观感是弹道出错；施加在朝向上则能看到 bot 的瞄准在抖动，
     * 视觉与弹道一致。
     */
    public AimOffset rollAimOffset() {
        float radius = errorDegrees();
        double angle = random.nextDouble() * 2.0D * Math.PI;
        double r = radius * Math.sqrt(random.nextDouble());
        return new AimOffset((float) (Math.cos(angle) * r), (float) (Math.sin(angle) * r));
    }

    public record AimOffset(float yawDegrees, float pitchDegrees) {

        /** 偏移的角距离，用于校验其落在误差圆内。 */
        public float magnitudeDegrees() {
            return (float) Math.hypot(yawDegrees, pitchDegrees);
        }
    }

    /** 失去视线后是否仍在目标记忆期内（决定 bot 该继续压制还是转为搜索）。 */
    public boolean hasTargetMemory() {
        return ticksOnTarget > 0 && ticksSinceSeen <= model.reacquireTicks();
    }

    /** 主动清空目标（换目标或对局重置时调用）。 */
    public void forgetTarget() {
        ticksOnTarget = 0;
        ticksSinceSeen = model.reacquireTicks() + 1;
        shotsInBurst = 0;
        burstPauseRemaining = 0;
    }

    /** 连续跟踪当前目标的 tick 数。 */
    public int ticksOnTarget() {
        return ticksOnTarget;
    }

    /** 当前后坐力带来的额外误差（度）。 */
    public float recoilDegrees() {
        return recoilDegrees;
    }

    /** 本轮点射剩余停顿 tick 数；{@code > 0} 即为玩家的进攻窗口。 */
    public int burstPauseRemaining() {
        return burstPauseRemaining;
    }

    public AimModel model() {
        return model;
    }

    private int rollBurstLength() {
        int span = model.burstMaxShots() - model.burstMinShots() + 1;
        return model.burstMinShots() + random.nextInt(span);
    }
}
