package org.shee33.act0.battlefield.bot.mc;

import com.mojang.logging.LogUtils;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.shee33.act0.battlefield.bot.AimModel;
import org.shee33.act0.battlefield.bot.AimTracker;
import org.shee33.act0.battlefield.bot.ShootOutcome;
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

    /** 同一开火故障的最短重报间隔（tick）：够稀疏不刷屏，又够密集让整局都看得见。 */
    private static final long REPORT_INTERVAL_TICKS = 20L * 60L;

    /** 上一次已上报的开火失败原因；开火成功后清空以便问题复发时能立即再报一次。 */
    @Nullable
    private String lastReportedFailure;

    /**
     * 上一次上报所处的游戏刻，与 {@link #REPORT_INTERVAL_TICKS} 一同构成限流。
     *
     * <p>初值取 0 而非 {@code Long.MIN_VALUE}：后者会让首次比较的差值溢出成负数。首次上报本身
     * 由 {@code lastReportedFailure == null} 放行，不依赖这个初值。
     */
    private long lastReportTick;

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
        switch (ShootOutcome.actionFor(result)) {
            case RELOAD -> BotGunBridge.reload(bot);
            case BOLT -> BotGunBridge.bolt(bot);
            // 正常情况下配装发放时就已代 bot 完成持枪（见 RedeployService#drawIssuedGunForBot）；
            // 这里是绕过部署流程的路径（调试指令直接生成、对局外混战）的兜底。
            case DRAW -> {
                if (!BotGunBridge.drawMainHand(bot)) {
                    reportFailure("NOT_DRAW（补持枪失败：主手不是 TaCZ 枪械，多半是配装没发到武器）");
                }
            }
            case REPORT -> reportFailure(result);
            case NONE -> {
            }
        }
    }

    /**
     * 记录一条开火故障，同一原因按 {@link #REPORT_INTERVAL_TICKS} 限流。
     *
     * <p><b>限流而非只报一次。</b>先前是"与上次相同就永不再报"，于是像"bot 根本没拿到枪"这种
     * 贯穿整局的故障只在开局留下一行、随后彻底沉默，实际表现成 bot 全程一枪不发却查无实据。
     * 故障持续多久就该被看见多久；开火成功会清空记录，使问题复发时能立即再报一次。
     */
    private void reportFailure(String reason) {
        long now = bot.serverLevel().getGameTime();
        if (reason.equals(lastReportedFailure) && now - lastReportTick < REPORT_INTERVAL_TICKS) {
            return;
        }
        lastReportedFailure = reason;
        lastReportTick = now;
        LOGGER.warn("[ACT0] bot {} 开火被 TaCZ 拒绝：{}", bot.getGameProfile().getName(), reason);
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
