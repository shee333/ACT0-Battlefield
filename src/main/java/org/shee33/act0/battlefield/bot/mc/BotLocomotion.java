package org.shee33.act0.battlefield.bot.mc;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.shee33.act0.battlefield.bot.MarchTactics;
import org.shee33.act0.battlefield.bot.SquadTactics;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * 单个 AI 士兵的位移层：把"该往哪走"翻译成每 tick 的移动输入。去处的决策在
 * {@link BotSeekPolicy}，本类只负责走过去。
 *
 * <p><b>朝向归属是核心约定。</b>交火时朝向由 {@link BotWeaponController} 独占（枪要压在敌人身上），
 * 此时位移只能走 {@link BotMovementDriver#driveRelative} 的侧移分解；未交火时朝向才交还位移层。
 * 扶起队友是第三种情况：那时朝向归 {@link BotLifeDriver}（必须看着被扶的人），位移则应当停下。
 */
final class BotLocomotion {

    private final BotPlayer bot;
    private final BotSeekPolicy seek;
    private final BotPathFollower path;

    BotLocomotion(BotPlayer bot) {
        this.bot = bot;
        this.seek = new BotSeekPolicy(bot);
        this.path = new BotPathFollower(bot);
    }

    /**
     * 自主移动。四条互斥分支，按优先级排列：<b>正在扶人</b> → <b>低血脱离</b> → <b>交火机动</b>
     * → <b>行军</b>。
     *
     * <p>扶人排最前：扶起需要在 4 格内保持不动并看住目标，任何位移都会打断它。脱离排第二，
     * 因为它是覆盖性决策——残血时无论当前距离处在哪一档，正解都是拉开。
     *
     * @param reviving  本 tick 是否正在给队友扶起
     */
    void tick(MinecraftServer server, boolean reviving, @Nullable Entity aimTarget,
              @Nullable Entity seekGoal, @Nullable UUID reviveTarget,
              BotMatchContext context, BotTactics tactics) {
        if (reviving) {
            BotMovementDriver.halt(bot);
            return;
        }
        Entity engaging = alive(aimTarget);
        if (engaging != null && tactics.breakingOff()) {
            disengage(engaging, tactics.separation(context));
            return;
        }
        if (engaging != null && reviveTarget == null) {
            manoeuvre(server, engaging, context, tactics);
            return;
        }
        march(server, reviveTarget, alive(seekGoal), context, tactics);
        applyScan(server);
    }

    /** bot 撤走时释放导航资源，避免影子 Mob 随之泄漏。 */
    void release() {
        path.release();
    }

    /**
     * 行军：去处交由 {@link BotSeekPolicy} 决定，叠加队友排斥位移后走过去。
     *
     * <p>排斥位移让整队即使目标据点相同也会散成一个面而不是叠成一条线——征服的票数由控点数之差
     * 决定，散开守点远比抱团有价值。
     */
    private void march(MinecraftServer server, @Nullable UUID reviveTarget,
                       @Nullable Entity seekGoal, BotMatchContext context, BotTactics tactics) {
        Vec3 destination = seek.destination(context, reviveTarget, seekGoal);
        if (destination == null) {
            BotMovementDriver.halt(bot);
            return;
        }
        Vec3 adjusted = destination.add(tactics.separation(context));
        double dx = adjusted.x - bot.getX();
        double dz = adjusted.z - bot.getZ();
        boolean sprint = MarchTactics.shouldSprint(false, Math.sqrt(dx * dx + dz * dz));
        if (path.follow(server, adjusted, false, sprint) != BotPathFollower.Outcome.MOVING) {
            BotMovementDriver.halt(bot);
        }
    }

    /**
     * 未交火时让头部相对身体扫视，身体朝向仍归行进方向。
     *
     * <p>只登记偏移量，不直接写头部朝向：本方法运行在 tick 开始阶段，随后的原版实体 tick 会把
     * 玩家头部重新对齐到身体。真正的施加点在 {@link BotPlayer#tick()} 末尾。
     */
    private void applyScan(MinecraftServer server) {
        bot.setHeadYawOffset(MarchTactics.scanOffsetDegrees(server.getTickCount(), bot.getId()));
    }

    /** 交火机动：姿态与方向一律相对<b>正在交火的那个目标</b>，而非最近的敌人。 */
    private void manoeuvre(MinecraftServer server, Entity target, BotMatchContext context,
                           BotTactics tactics) {
        double dx = target.getX() - bot.getX();
        double dz = target.getZ() - bot.getZ();
        switch (tactics.stance().modeFor(Math.sqrt(dx * dx + dz * dz))) {
            case ADVANCE -> advance(server, target, context, tactics, dx, dz);
            case HOLD -> strafe(dx, dz, tactics);
            case RETREAT -> retreat(dx, dz, tactics);
        }
    }

    /**
     * 推进：压制角色直冲目标，绕侧角色改走一个偏离轴线的落点。
     *
     * <p>落点取"目标位置沿旋转后方向退回一段"，而不是直接旋转移动输入——后者会让 bot 沿切线一直
     * 平移、永远接不上火。落点仍以目标为锚，所以绕侧是"从侧面靠过去"而非"绕着跑圈"。
     */
    private void advance(MinecraftServer server, Entity target, BotMatchContext context,
                         BotTactics tactics, double dx, double dz) {
        double[] dir = tactics.approachDirection(context, dx, dz);
        Vec3 destination = target.position();
        if (dir[0] != dx || dir[1] != dz) {
            double len = Math.sqrt(dir[0] * dir[0] + dir[1] * dir[1]);
            if (len > 1.0e-4D) {
                double back = Math.min(len, SquadTactics.SPACING_MIN_BLOCKS * 2.0D);
                destination = destination.subtract(dir[0] / len * back, 0.0D, dir[1] / len * back);
            }
        }
        Vec3 adjusted = destination.add(tactics.separation(context));
        if (path.follow(server, adjusted, true, false) != BotPathFollower.Outcome.MOVING) {
            BotMovementDriver.halt(bot);
        }
    }

    /** 低血脱离：背离目标拉开并叠加队友排斥；脱离期间不停火，边退边打。 */
    private void disengage(Entity target, Vec3 separation) {
        double dx = bot.getX() - target.getX() + separation.x;
        double dz = bot.getZ() - target.getZ() + separation.z;
        BotMovementDriver.driveRelative(bot, dx, dz, false);
    }

    /** 后撤：背离敌人；退无可退时改为贴墙横移，换来动作可读性。 */
    private void retreat(double dx, double dz, BotTactics tactics) {
        if (bot.horizontalCollision) {
            strafe(dx, dz, tactics);
            return;
        }
        BotMovementDriver.driveRelative(bot, -dx, -dz, false);
    }

    /** 横向机动：沿垂直于"自己→敌人"的方向平移，方向按节奏与碰撞翻转。 */
    private void strafe(double dx, double dz, BotTactics tactics) {
        tactics.stance().tick(bot.horizontalCollision);
        int sign = tactics.stance().strafeSign();
        BotMovementDriver.driveRelative(bot, -dz * sign, dx * sign, false);
    }

    @Nullable
    private static Entity alive(@Nullable Entity entity) {
        return entity != null && entity.isAlive() ? entity : null;
    }
}
