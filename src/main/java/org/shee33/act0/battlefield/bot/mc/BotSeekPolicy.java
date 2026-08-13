package org.shee33.act0.battlefield.bot.mc;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

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
        Vec3 objective = context.objective()
                .map(o -> context.pointCenter(o.pointId()))
                .orElse(null);
        if (objective != null && !insideObjective(context, objective)) {
            return objective;
        }
        if (nearestEnemy != null) {
            return nearestEnemy.position();
        }
        return objective;
    }

    /**
     * 是否已经站进目标据点。
     *
     * <p>已在点内还继续以中心为目标，会让 bot 无视身边的敌人往几何中心挤——交火反而被荒废。
     * 用据点自身的 AABB 判定而不是半径近似，与本体的占领判定同源。
     */
    private boolean insideObjective(BotMatchContext context, Vec3 center) {
        for (BotMatchContext.PointState state : context.points()) {
            if (state.view().center().equals(center)) {
                return state.view().zone().contains(bot.getX(), bot.getY(), bot.getZ());
            }
        }
        return false;
    }
}
