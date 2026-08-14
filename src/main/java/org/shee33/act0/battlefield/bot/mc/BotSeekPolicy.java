package org.shee33.act0.battlefield.bot.mc;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.shee33.act0.battlefield.bot.ConquestTactics;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * 未交火时"该往哪走"的优先级链：救援队友 → 目标据点 → 最近敌人 → 原地。
 *
 * <p><b>救援排在据点之前</b>，是因为救起一个队友同时保住了一张票和一个能占点的人，而据点随时还在
 * 那里；倒地只有 15 秒。{@link org.shee33.act0.battlefield.bot.RevivePolicy} 已经把"赶不到就不去"
 * 过滤掉了，所以能排到这一位的救援一定是划算的。
 *
 * <p><b>据点排在敌人之前</b>，是征服模式与街机死斗最本质的差别：票数由控点数之差决定，追着人跑
 * 是负收益。街机那套"没敌人就巡逻"在这里不需要——征服地图上永远有据点可去。
 */
final class BotSeekPolicy {

    private final BotPlayer bot;

    BotSeekPolicy(BotPlayer bot) {
        this.bot = bot;
    }

    /**
     * 选出本 tick 的行军去向；{@code null} 表示无处可去（应当停下）。
     *
     * @param reviveTarget 已决定要去救的倒地队友；{@code null} 表示本 tick 不救人
     * @param nearestEnemy 最近的敌人（不要求可见）
     */
    @Nullable
    Vec3 destination(BotMatchContext context, @Nullable UUID reviveTarget,
                     @Nullable Entity nearestEnemy) {
        if (reviveTarget != null) {
            var mate = bot.serverLevel().getServer().getPlayerList().getPlayer(reviveTarget);
            if (mate != null) {
                return mate.position();
            }
        }
        BotMatchContext.PointState objective = objectiveState(context);
        if (objective == null) {
            return nearestEnemy != null ? nearestEnemy.position() : null;
        }
        Vec3 center = objective.view().center();
        if (!objective.view().zone().contains(bot.getX(), bot.getY(), bot.getZ())) {
            return center;
        }
        // 已在点内：只有敌人还在据点附近才值得追出去，否则守住——见 worthChasingOffPoint 的说明。
        if (nearestEnemy != null && ConquestTactics.worthChasingOffPoint(
                horizontalDistance(center, nearestEnemy.position()), halfWidthOf(objective))) {
            return nearestEnemy.position();
        }
        return center;
    }

    /** 本 bot 当前选定的目标据点；无目标（或据点已被移除）时返回 {@code null}。 */
    @Nullable
    private static BotMatchContext.PointState objectiveState(BotMatchContext context) {
        var chosen = context.objective();
        if (chosen.isEmpty()) {
            return null;
        }
        int pointId = chosen.get().pointId();
        for (BotMatchContext.PointState state : context.points()) {
            if (state.view().pointId() == pointId) {
                return state;
            }
        }
        return null;
    }

    /** 判定区半宽（格）：AABB 在 X 轴上的一半跨度，与 {@code ControlPointDef.radius} 同量。 */
    private static double halfWidthOf(BotMatchContext.PointState state) {
        var zone = state.view().zone();
        return (zone.maxX - zone.minX) / 2.0D;
    }

    private static double horizontalDistance(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }
}
