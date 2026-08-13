package org.shee33.act0.battlefield.bot.mc;

import com.mojang.logging.LogUtils;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.shee33.act0.battlefield.bot.AimModel;
import org.shee33.act0.battlefield.bot.AimTracker;
import org.shee33.act0.battlefield.bot.Steering;

import javax.annotation.Nullable;
import java.util.Objects;

/**
 * 单个 AI 士兵的武器驱动：把瞄准模型的判断变成每 tick 的朝向与扳机动作。
 *
 * <p>职责边界刻意收窄——<b>本类不选目标</b>。目标由上层（调试指令，日后的感知与战术层）指定，
 * 本类只负责"给定一个目标，如何像人一样把枪指过去并开火"。这样目标选择策略可以独立演进，
 * 而交火手感这一层保持稳定。
 *
 * <p>决策部分全在 MC-free 的 {@link AimTracker}（反应延迟、点射纪律、目标记忆、后坐力累积），
 * 本类只做三件 MC 相关的事：视线遮挡判定、朝向写入、调用 {@link BotGunBridge} 扣扳机。
 *
 * <p><b>换弹窗口是真实的。</b>早先自建弹道的方案需要人为造出"假换弹停顿"作为玩家的进攻窗口；
 * 改用 TaCZ 后弹药与换弹是真的，本类只需在 TaCZ 报告弹尽时触发换弹，
 * 那段带动画与音效的真实空档自然成为可利用的战术窗口。
 */
public final class BotWeaponController {

    /**
     * 瞄点在目标碰撞箱高度上的比例。
     *
     * <p>0.85 约为上胸／颈部：既非脚下（会被地形挡住）也非头顶（过窄导致 bot 命中率异常低），
     * 与战地系列 bot 默认瞄点一致。作为 bot 瞄点的唯一来源，避免各处各写一个魔数。
     */
    public static final double AIM_HEIGHT_RATIO = 0.85D;

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final AimTracker.AimOffset ZERO_OFFSET = new AimTracker.AimOffset(0.0F, 0.0F);

    private final BotPlayer bot;
    private final AimTracker aim;

    private AimTracker.AimOffset offset = ZERO_OFFSET;

    @Nullable
    private Entity target;

    /**
     * 最后一次<b>确实看见</b>目标时的瞄点。
     *
     * <p>失去视线后瞄这里而非目标的活体位置——后者等于穿墙追踪。
     */
    @Nullable
    private Vec3 lastKnownAimPoint;

    /** 上一次已上报的开火失败原因，用于去重；开火成功后清空以便问题复发时能再报一次。 */
    @Nullable
    private String lastReportedFailure;

    public BotWeaponController(BotPlayer bot, AimModel model, long seed) {
        this.bot = Objects.requireNonNull(bot, "bot");
        this.aim = new AimTracker(Objects.requireNonNull(model, "model"), seed);
    }

    /** 目标瞄点：上胸高度。 */
    public static Vec3 aimPointOf(Entity target) {
        return target.position().add(0.0D, target.getBbHeight() * AIM_HEIGHT_RATIO, 0.0D);
    }

    /** 指定交火目标；传 {@code null} 表示脱离交火。 */
    public void setTarget(@Nullable Entity target) {
        if (this.target != target) {
            aim.forgetTarget();
            lastKnownAimPoint = null;
            // 获得新目标时必须立刻重掷偏移。偏移原本只在开火成功后重掷，而其初值为零，
            // 于是每场交火的第一枪都精确命中——正是"探头即被爆头"这种必须避免的手感。
            // 此处紧接 forgetTarget() 调用，因此掷出的是"初见目标"那个最大的误差圆内的偏移。
            offset = target == null ? ZERO_OFFSET : aim.rollAimOffset();
        }
        this.target = target;
    }

    @Nullable
    public Entity target() {
        return target;
    }

    public AimTracker aimTracker() {
        return aim;
    }

    /**
     * 推进一 tick：更新瞄准、写入朝向、按节奏开火。
     *
     * <p>必须在世界实体循环之前调用，与移动驱动同处 {@code ServerTickEvent.Phase.START}。
     */
    public void tick() {
        if (!isTargetAlive()) {
            releaseTarget();
            return;
        }

        Vec3 eye = bot.getEyePosition();
        Vec3 livePoint = aimPointOf(target);
        AimModel model = aim.model();

        // 与 BotPerception 一致取头部朝向。交火中三个朝向被 applyRotation 写成同值，
        // 故此处对已交火的行为无变化；差别只体现在刚发现目标的那一刻。
        boolean inFov = model.withinFov(Steering.angleBetween(
                bot.getYHeadRot(), Steering.yawToward(livePoint.x - eye.x, livePoint.z - eye.z)));
        boolean visible = inFov && BotPerception.hasClearLineOfSight(bot, eye, livePoint);
        aim.tick(visible);

        if (visible) {
            lastKnownAimPoint = livePoint;
        } else if (!aim.hasTargetMemory()) {
            releaseTarget();
            return;
        }

        // 失去视线时瞄"最后已知位置"而非活体位置。跟着看不见的目标转动就是穿墙追踪
        // ——玩家会（正确地）判定为作弊。记忆期内保持指向最后所见处，是压制与"绕后要够快"
        // 这一玩法赖以存在的行为；期满则由上方分支丢弃目标。
        Vec3 aimAt = visible ? livePoint : lastKnownAimPoint;
        if (aimAt == null) {
            // 目标自被指定起从未被看见（例如隔墙 engage）：无从得知其位置，只等记忆期满。
            return;
        }

        double dx = aimAt.x - eye.x;
        double dy = aimAt.y - eye.y;
        double dz = aimAt.z - eye.z;
        float idealYaw = Steering.yawToward(dx, dz);
        float idealPitch = Steering.pitchToward(dy, Math.sqrt(dx * dx + dz * dz));

        // 朝向朝"理想方向 + 当前误差偏移"转动：偏移只在每次射击后重掷，
        // 因此转动是平滑的，而非每 tick 抖动（后者观感像抽搐而非瞄不准）。
        float rate = model.turnRateDegPerTick();
        applyRotation(Steering.turnToward(bot.getYRot(), idealYaw + offset.yawDegrees(), rate),
                Steering.turnToward(bot.getXRot(), idealPitch + offset.pitchDegrees(), rate));

        BotGunBridge.aim(bot, true);
        if (visible) {
            tryFire();
        }
    }

    private void releaseTarget() {
        target = null;
        lastKnownAimPoint = null;
        BotGunBridge.aim(bot, false);
    }

    private void tryFire() {
        if (!aim.canFire() || !weaponReady()) {
            return;
        }
        String result = BotGunBridge.shoot(bot, bot.getXRot(), bot.getYRot());
        if (BotGunBridge.RESULT_SUCCESS.equals(result)) {
            aim.onShotFired();
            offset = aim.rollAimOffset();
            lastReportedFailure = null;
            return;
        }
        // 弹尽、待上膛、未持枪都不是错误，而是应当就地自愈的正常状态。
        switch (result) {
            case "NO_AMMO" -> BotGunBridge.reload(bot);
            case "NEED_BOLT" -> BotGunBridge.bolt(bot);
            // 配装发枪后没有任何人替 bot 完成 TaCZ 的"持枪就绪"，于是每一枪都是 NOT_DRAW。
            // 在此就地补 draw 而非要求上层调用：换枪、复活重新发装备都会让 draw 失效，
            // 由开火失败驱动重新持枪是唯一不会漏掉任何路径的做法。
            case "NOT_DRAW" -> {
                // 补 draw 若也失败（例如手里根本不是枪），必须上报：否则又成了静默重试。
                if (!BotGunBridge.drawMainHand(bot)) {
                    reportUnexpectedFailure("NOT_DRAW（补持枪失败，检查 bot 主手是否为 TaCZ 枪械）");
                }
            }
            // 抽枪动画期。它紧跟在上面补的那次 draw 之后必然出现，属正常过渡而非故障；
            // 若不在此拦下，每个 bot 首次交火都会记一条假警报，把诊断日志的信噪比冲掉。
            case "IS_DRAWING" -> {
            }
            default -> reportUnexpectedFailure(result);
        }
    }

    /**
     * 记录非预期的开火失败，同一原因只报一次。
     *
     * <p>这里原本是空的 {@code default} 分支，于是 {@code NOT_DRAW} 被静默吞掉、bot 在对局里
     * 一枪不发却毫无线索，只能靠人肉上客户端才发现。集成层异常不该影响主玩法，
     * 但也不该无声无息——去重后即便每 tick 触发也只留一行。
     */
    private void reportUnexpectedFailure(String result) {
        if (result.equals(lastReportedFailure)) {
            return;
        }
        lastReportedFailure = result;
        LOGGER.warn("[ACT0] bot {} 开火被 TaCZ 拒绝：{}", bot.getGameProfile().getName(), result);
    }

    private boolean weaponReady() {
        return BotGunBridge.shootCoolDownMillis(bot) <= 0L
                && !BotGunBridge.isReloading(bot)
                && !BotGunBridge.isBolting(bot);
    }

    /**
     * 脱锁距离（格）。
     *
     * <p>比获取半径 {@value BotPerception#DEFAULT_SEARCH_RADIUS} 略宽，留出迟滞：
     * 目标在边界附近来回时不会反复锁定又丢失，那会让 bot 的动作看起来像在抽搐。
     */
    private static final double DROP_RANGE = BotPerception.DEFAULT_SEARCH_RADIUS * 1.125D;

    /**
     * 目标是否仍是合法交火对象；<b>不含</b>可见性判定，那由每 tick 的视线检测负责。
     *
     * <p><b>距离检查不可省。</b>获取目标时有 64 格上限，但此前一旦锁定就再不检查距离，
     * 只查存活与视线——空旷地形上几百格外的射线检测照样通过，于是 bot 会永远锁着那个人
     * 一路直线走过去，既不巡逻也不扫视。实测中它就这样走到了 370 格外仍在追。
     */
    private boolean isTargetAlive() {
        return target != null && target.isAlive() && !target.isRemoved()
                && target.level() == bot.level()
                && target.distanceToSqr(bot) <= DROP_RANGE * DROP_RANGE;
    }

    private void applyRotation(float yaw, float pitch) {
        bot.setYRot(yaw);
        bot.setYHeadRot(yaw);
        bot.setYBodyRot(yaw);
        bot.setXRot(pitch);
    }
}
