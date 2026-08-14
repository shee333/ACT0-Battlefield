package org.shee33.act0.battlefield.bot.mc;

import net.minecraft.server.level.ServerPlayer;
import org.shee33.act0.battlefield.bot.RedeployPolicy;
import org.shee33.act0.battlefield.bot.RevivePolicy;
import org.shee33.act0.battlefield.bot.Steering;
import org.shee33.act0.battlefield.data.BattlefieldData;
import org.shee33.act0.battlefield.network.DownedActionPacket;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * AI 士兵的生命周期驱动：救援队友、自己倒地时的取舍、以及阵亡后的重新部署。
 *
 * <p>三件事放在一起，是因为它们共用同一个前提——本模组的"死"是一个多阶段流程（倒地 → 流血 →
 * 重部选点 → 落地），而这三步之间的取舍互相牵连：值不值得去救人取决于自己血量，倒地要不要放弃
 * 取决于有没有人来得及救，重部落哪儿取决于想去哪个据点。拆成三个类会让这些牵连变成跨类的隐式耦合。
 *
 * <p><b>全部通过本模组既有的公开入口驱动，不复制其收尾逻辑。</b>救援走
 * {@code handleReviveHeartbeat}（每 tick 调一次等同于真人按住 F），放弃走
 * {@code handleDownedAction(GIVE_UP)}，重部走 {@code handleDeployAction}。这样票数结算、
 * 配装发放、出生保护等一整套账都由本体记，AI 侧不会与之漂移。
 */
final class BotLifeDriver {

    /**
     * 步行速度估算（格/秒），用于判断能否在队友流血结束前赶到。
     *
     * <p>取步行而非疾跑：救援路上很可能被迫交火而掉速，按疾跑估算会高估自己的赶路能力，
     * 从而接下一堆赶不到的救援。
     */
    private static final double WALK_SPEED_BLOCKS_PER_SECOND = 4.3D;

    private final BotPlayer bot;

    /** 上一 tick 是否在给某人扶起——用于在脱离距离后主动收回心跳。 */
    @Nullable
    private UUID reviving;

    BotLifeDriver(BotPlayer bot) {
        this.bot = bot;
    }

    /** 本 bot 当前是否倒地。 */
    boolean downed(BotMatchContext context) {
        return context.match().isDowned(bot.getUUID());
    }

    /**
     * 自己倒地时的处理：算清有没有人来得及救，赶不到就主动放弃，不白躺 15 秒。
     *
     * <p>不能一倒地就放弃——那会让真人玩家的救援永远来不及，救援机制形同不存在。
     */
    void tickDowned(BotMatchContext context) {
        int secondsLeft = context.match().downedSeconds(bot.getUUID());
        double rescuerSecondsAway = fastestRescuerSeconds(context);
        if (RedeployPolicy.shouldGiveUp(secondsLeft, rescuerSecondsAway)) {
            context.match().handleDownedAction(bot, DownedActionPacket.Action.GIVE_UP);
        }
    }

    /** 最快的潜在救援者预计多少秒能完成救援；无人可救返回 {@link Double#MAX_VALUE}。 */
    private double fastestRescuerSeconds(BotMatchContext context) {
        double best = Double.MAX_VALUE;
        for (UUID mate : context.match().membersOf(context.faction())) {
            if (mate.equals(bot.getUUID()) || context.match().isDowned(mate)) {
                continue;
            }
            ServerPlayer p = player(mate);
            if (p == null || !p.isAlive() || p.isSpectator()) {
                continue;
            }
            double dist = Math.sqrt(p.distanceToSqr(bot));
            double travel = Math.max(0.0D, dist - RevivePolicy.REVIVE_RANGE_BLOCKS)
                    / WALK_SPEED_BLOCKS_PER_SECOND;
            best = Math.min(best, travel + RevivePolicy.REVIVE_SECONDS);
        }
        return best;
    }

    /**
     * 选出此刻最该去救的倒地队友；不值得去（或没有）时返回 {@code null}。
     *
     * <p>"是否已有更近的友军在赶去"用同队里更近的存活者近似——精确判断需要读别人的意图，
     * 而那是 AI 之间才有的信息，真人队友不会告诉我们他要去扶谁。
     */
    @Nullable
    UUID pickReviveTarget(BotMatchContext context) {
        List<RevivePolicy.Candidate> candidates = new ArrayList<>();
        for (UUID mate : context.match().membersOf(context.faction())) {
            if (mate.equals(bot.getUUID()) || !context.match().isDowned(mate)) {
                continue;
            }
            ServerPlayer downedMate = player(mate);
            if (downedMate == null) {
                continue;
            }
            double dist = Math.sqrt(downedMate.distanceToSqr(bot));
            candidates.add(new RevivePolicy.Candidate(mate, dist,
                    context.match().downedSeconds(mate),
                    context.match().canRevive(bot, mate),
                    false,
                    someoneCloser(context, downedMate, dist)));
        }
        RevivePolicy.Situation situation = new RevivePolicy.Situation(
                context.healthFraction(), WALK_SPEED_BLOCKS_PER_SECOND, false);
        return RevivePolicy.pick(candidates, situation).map(RevivePolicy.Candidate::targetId)
                .orElse(null);
    }

    private boolean someoneCloser(BotMatchContext context, ServerPlayer downedMate, double myDist) {
        for (UUID other : context.match().membersOf(context.faction())) {
            if (other.equals(bot.getUUID()) || context.match().isDowned(other)) {
                continue;
            }
            ServerPlayer p = player(other);
            if (p == null || !p.isAlive() || p.isSpectator()) {
                continue;
            }
            if (Math.sqrt(p.distanceToSqr(downedMate)) < myDist - 1.0D) {
                return true;
            }
        }
        return false;
    }

    /**
     * 驱动扶起：在生效距离内且大致朝向目标时每 tick 发一次心跳，等同真人按住 F。
     *
     * <p>朝向要求来自本体的 {@code REVIVE_VIEW_DOT = 0.5}（约 60° 视锥）；这里直接把头部转过去，
     * 因为扶人时枪口指向已无意义。
     *
     * @return 是否正在扶起
     */
    boolean tickRevive(BotMatchContext context, @Nullable UUID targetId) {
        if (targetId == null) {
            releaseRevive(context);
            return false;
        }
        ServerPlayer target = player(targetId);
        if (target == null || !context.match().isDowned(targetId)) {
            releaseRevive(context);
            return false;
        }
        double dist = Math.sqrt(target.distanceToSqr(bot));
        if (!RevivePolicy.inRange(dist)) {
            releaseRevive(context);
            return false;
        }
        faceTowards(target);
        context.match().handleReviveHeartbeat(bot, target.getId(), true);
        reviving = targetId;
        return true;
    }

    /** 脱离救援：显式发一次 {@code active=false}，否则本体要等心跳超时才收手。 */
    private void releaseRevive(BotMatchContext context) {
        if (reviving == null) {
            return;
        }
        ServerPlayer target = player(reviving);
        if (target != null) {
            context.match().handleReviveHeartbeat(bot, target.getId(), false);
        }
        reviving = null;
    }

    private void faceTowards(ServerPlayer target) {
        double dx = target.getX() - bot.getX();
        double dy = target.getEyeY() - bot.getEyeY();
        double dz = target.getZ() - bot.getZ();
        float yaw = Steering.yawToward(dx, dz);
        bot.setYRot(yaw);
        bot.setYHeadRot(yaw);
        bot.setXRot(Steering.pitchToward(dy, Math.sqrt(dx * dx + dz * dz)));
    }

    /**
     * 待部署时选落点并申请重部。
     *
     * <p>落点按到<b>目标据点</b>的距离排序（{@link RedeployPolicy}），而不是本体给真人的"离前线最近"
     * ——AI 已经选好了要去的点，从基地跑回来常常比在远侧翼的队友身上重生更快到位。
     *
     * <p>本体的 5 秒重部门控由它自己把关：门控未过时 {@code handleDeployAction} 只会重发状态，
     * 因此这里每 tick 调用是安全的，不需要 AI 侧再记一份计时。
     */
    void tickRedeploy(BotMatchContext context) {
        RedeployPolicy.best(deployOptions(context)).ifPresent(option -> {
            String kind = switch (option.kind()) {
                case SQUADMATE -> "squad";
                case POINT -> "point";
                case BASE -> "base";
            };
            context.match().handleDeployAction(bot, kind, option.targetId());
        });
    }

    private List<RedeployPolicy.Option> deployOptions(BotMatchContext context) {
        List<RedeployPolicy.Option> options = new ArrayList<>();
        net.minecraft.world.phys.Vec3 goal = objectiveCenter(context);
        for (BotMatchContext.PointState state : context.points()) {
            if (state.view().owner() != context.faction()) {
                continue;
            }
            options.add(new RedeployPolicy.Option(RedeployPolicy.Kind.POINT,
                    Integer.toString(state.view().pointId()),
                    distance(goal, state.view().center()), true));
        }
        for (UUID mate : context.squadMates()) {
            ServerPlayer p = player(mate);
            if (p == null || !p.isAlive() || p.isSpectator() || context.match().isDowned(mate)) {
                continue;
            }
            options.add(new RedeployPolicy.Option(RedeployPolicy.Kind.SQUADMATE, mate.toString(),
                    distance(goal, p.position()), true));
        }
        BattlefieldData.BaseSpawn base = BattlefieldData.get(context.match().level())
                .base(context.faction());
        if (base != null) {
            options.add(new RedeployPolicy.Option(RedeployPolicy.Kind.BASE, "",
                    distance(goal, new net.minecraft.world.phys.Vec3(base.x(), base.y(), base.z())),
                    true));
        }
        return options;
    }

    private net.minecraft.world.phys.Vec3 objectiveCenter(BotMatchContext context) {
        return context.objective()
                .map(o -> context.pointCenter(o.pointId()))
                .orElse(bot.position());
    }

    private static double distance(net.minecraft.world.phys.Vec3 a,
                                   @Nullable net.minecraft.world.phys.Vec3 b) {
        if (b == null) {
            return Double.MAX_VALUE;
        }
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    @Nullable
    private ServerPlayer player(UUID id) {
        return bot.serverLevel().getServer().getPlayerList().getPlayer(id);
    }
}
