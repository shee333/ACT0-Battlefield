package org.shee33.act0.battlefield.bot.mc;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.shee33.act0.battlefield.bot.AimModel;
import org.shee33.act0.battlefield.bot.SquadTactics;

import javax.annotation.Nullable;
import java.util.UUID;
import java.util.function.BiPredicate;

/**
 * 单个在场 AI 士兵的完整状态与每 tick 编排。包内可见，由 {@link BotManager} 独占持有与驱动。
 *
 * <p><b>分支次序是本类的核心。</b>倒地与待部署必须排在一切战斗行为之前——倒地的 bot 不能开枪也
 * 不能移动，待部署的 bot 是观察者，对它做寻路只会白跑一遍寻路器。倒计时阶段同样先行返回，
 * 否则 bot 会在还不能交火的阶段就冲出基地。
 */
final class BotTask {

    static final AimModel.Difficulty DEFAULT_DIFFICULTY = AimModel.Difficulty.NORMAL;

    /** 目标重新评估间隔（tick）：感知含 raycast，不该每 tick 对每个 bot 都跑一遍。 */
    private static final int TARGET_SCAN_INTERVAL_TICKS = 10;

    final BotPlayer bot;
    BotWeaponController weapon;
    AimModel.Difficulty difficulty = DEFAULT_DIFFICULTY;

    private final BotLocomotion locomotion;
    private final BotTactics tactics;
    private final BotLifeDriver life;

    /** 自主移动的去向：最近的敌人，<b>不要求可见</b>——否则看不见敌人的 bot 只能站桩。 */
    @Nullable
    private ServerPlayer moveGoal;

    BotTask(BotPlayer bot) {
        this.bot = bot;
        this.weapon = newWeapon(bot, DEFAULT_DIFFICULTY);
        this.locomotion = new BotLocomotion(bot);
        this.tactics = new BotTactics(bot);
        this.life = new BotLifeDriver(bot);
    }

    void tick(MinecraftServer server, BiPredicate<ServerPlayer, ServerPlayer> hostility) {
        BotMatchContext context = BotMatchContext.of(bot, server.getTickCount());
        tactics.tick(context);
        if (context == null || !context.live()) {
            BotMovementDriver.halt(bot);
            return;
        }
        if (life.downed(context)) {
            life.tickDowned(context);
            return;
        }
        // beginRedeploy 会把玩家切成观察者，这是"正在等重新部署"最直接可观察的信号。
        if (bot.isSpectator()) {
            life.tickRedeploy(context);
            return;
        }

        UUID reviveTarget = life.pickReviveTarget(context);
        boolean reviving = life.tickRevive(context, reviveTarget);

        if (!reviving) {
            rescan(server, hostility, context);
            weapon.tick();
        }
        locomotion.tick(server, reviving, weapon.target(), moveGoal, reviveTarget, context, tactics);
    }

    /** 复活时复位撤退迟滞，避免上一条命的残血状态跟着新生命走。 */
    void onRespawn() {
        tactics.onRespawn();
    }

    void releaseNavigation() {
        locomotion.release();
    }

    /**
     * 按间隔重新评估交火目标与行军去向；用实体 id 给扫描相位错开，避免所有 bot 同 tick 做 raycast。
     *
     * <p>扫描无果时刻意不清空交火目标——那会绕过 {@code AimTracker} 的目标记忆，而"敌人闪进掩体后
     * bot 仍短暂压制"正是玩家绕后需要够快的原因。目标过期交由 {@link BotWeaponController} 判定。
     */
    private void rescan(MinecraftServer server, BiPredicate<ServerPlayer, ServerPlayer> hostility,
                        BotMatchContext context) {
        if ((server.getTickCount() + bot.getId()) % TARGET_SCAN_INTERVAL_TICKS != 0) {
            return;
        }
        ServerPlayer found = BotPerception.findTarget(
                bot, weapon.aimTracker().model(), candidate -> hostility.test(bot, candidate),
                weapon.target(), BotPerception.DEFAULT_SEARCH_RADIUS,
                candidateId -> focusFireBonus(context, candidateId));
        if (found != null && found != weapon.target()) {
            weapon.setTarget(found);
        }
        moveGoal = BotPerception.findNearestHostile(
                bot, candidate -> hostility.test(bot, candidate), BotPerception.DEFAULT_SEARCH_RADIUS);
        publishEngagement(context);
    }

    /** 集火加成：已有<b>别的</b>队友在打这个目标时给一份额外权重。 */
    private float focusFireBonus(BotMatchContext context, UUID candidateId) {
        boolean engaged = BotSquadBoard.INSTANCE.teammateEngaging(
                context.match().level().dimension(), context.squadId(), bot.getUUID(), candidateId);
        return SquadTactics.focusFireBonus(engaged);
    }

    /** 把本 bot 的交火目标登记到小队黑板，供队友做集火。 */
    private void publishEngagement(BotMatchContext context) {
        Entity target = weapon.target();
        BotSquadBoard.INSTANCE.reportEngagement(context.match().level().dimension(),
                context.squadId(), bot.getUUID(), target != null ? target.getUUID() : null);
    }

    /** 用当前配置里的生效参数重建武器控制器，并沿用原交火目标。 */
    void rebuildWeapon(AimModel.Difficulty difficulty) {
        Entity current = weapon.target();
        this.weapon = newWeapon(bot, difficulty);
        this.weapon.setTarget(current);
        this.difficulty = difficulty;
    }

    /** 随机种子取自 bot 的 UUID：身份稳定，故点射与瞄准抖动跨重启可复现。 */
    private static BotWeaponController newWeapon(BotPlayer bot, AimModel.Difficulty difficulty) {
        AimModel model = BotManager.difficultyRegistry().get(difficulty);
        return new BotWeaponController(bot, model, bot.getUUID().hashCode());
    }
}
