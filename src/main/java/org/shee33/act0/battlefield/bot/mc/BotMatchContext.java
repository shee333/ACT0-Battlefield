package org.shee33.act0.battlefield.bot.mc;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.shee33.act0.battlefield.Act0Battlefield;
import org.shee33.act0.battlefield.bot.ConquestTactics;
import org.shee33.act0.battlefield.core.Faction;
import org.shee33.act0.battlefield.core.MatchPhase;
import org.shee33.act0.battlefield.match.ConquestMatch;
import org.shee33.act0.battlefield.match.SquadManager;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 一个 AI 士兵当前所处征服对局的只读视图。
 *
 * <p><b>点内人数按「对局 × tick」缓存共享。</b>{@link ConquestMatch#occupancyOf} 要遍历全体参战者，
 * 而据点态势是每个 bot 每 tick 都要读的东西。若各 bot 各算一遍，16 个 bot × 5 个据点 × 64 人
 * = 每 tick 五千次遍历。缓存后每 tick 每对局只算一遍（5 次遍历），全体 bot 共用。
 *
 * <p>缓存以 tick 号为版本：同一 tick 内复用，跨 tick 自动失效，因此不存在读到过期态势的可能。
 */
final class BotMatchContext {

    /** 一个据点的完整态势：归属/进度/几何 + 本 tick 的点内人数。 */
    record PointState(ConquestMatch.PointView view, ConquestMatch.ZoneOccupancy occupancy) {
    }

    private record CachedPoints(long tick, List<PointState> points) {
    }

    private static final Map<ResourceKey<Level>, CachedPoints> POINT_CACHE = new HashMap<>();

    private final ConquestMatch match;
    private final BotPlayer bot;
    private final Faction faction;
    private final List<PointState> points;

    private BotMatchContext(ConquestMatch match, BotPlayer bot, Faction faction,
                            List<PointState> points) {
        this.match = match;
        this.bot = bot;
        this.faction = faction;
        this.points = points;
    }

    /**
     * 建立本 tick 的视图；不在进行中的征服对局里（或尚无阵营）返回 {@code null}。
     */
    @Nullable
    static BotMatchContext of(BotPlayer bot, long tick) {
        ConquestMatch match = Act0Battlefield.manager().activeContaining(bot.getUUID());
        if (match == null || match.isEnded()) {
            return null;
        }
        Faction faction = match.factionOf(bot.getUUID());
        if (faction == null) {
            return null;
        }
        return new BotMatchContext(match, bot, faction, pointsOf(match, tick));
    }

    private static List<PointState> pointsOf(ConquestMatch match, long tick) {
        ResourceKey<Level> key = match.level().dimension();
        CachedPoints cached = POINT_CACHE.get(key);
        if (cached != null && cached.tick() == tick) {
            return cached.points();
        }
        List<PointState> states = new ArrayList<>();
        for (ConquestMatch.PointView view : match.pointViews()) {
            states.add(new PointState(view, match.occupancyOf(view.zone())));
        }
        List<PointState> immutable = List.copyOf(states);
        POINT_CACHE.put(key, new CachedPoints(tick, immutable));
        return immutable;
    }

    /**
     * 对局结束时清掉缓存。
     *
     * <p>按维度索引：本模组一个维度最多一场征服对局（见 {@code ConquestManager.activeByWorld}），
     * 因此维度键就是对局的天然标识。不清则每结束一局都留下一条永不再读的记录。
     */
    static void forgetMatch(ResourceKey<Level> dimension) {
        POINT_CACHE.remove(dimension);
    }

    static void clearCache() {
        POINT_CACHE.clear();
    }

    ConquestMatch match() {
        return match;
    }

    Faction faction() {
        return faction;
    }

    MatchPhase phase() {
        return match.phase();
    }

    /** 是否已经开打——倒计时阶段 bot 不该冲点。 */
    boolean live() {
        return match.phase() == MatchPhase.LIVE && !match.isPaused();
    }

    List<PointState> points() {
        return points;
    }

    float healthFraction() {
        float max = Math.max(1.0F, bot.getMaxHealth());
        return bot.getHealth() / max;
    }

    int squadId() {
        return match.squadIdOf(bot.getUUID());
    }

    /**
     * 本小队当前的进攻/防守指令；无指令返回 {@code null}。
     *
     * <p>指令由真人小队长下达（{@code /aew1 order}），AI 服从它——玩家下了指令却看到
     * AI 各干各的，是比走错点严重得多的体验问题。
     */
    @Nullable
    SquadManager.SquadOrder squadOrder() {
        return match.squads().getOrder(squadId());
    }

    /** 同小队的其他成员（不含自己），按 UUID 稳定排序以便角色分配可复现。 */
    List<UUID> squadMates() {
        List<UUID> mates = new ArrayList<>();
        java.util.LinkedHashSet<UUID> members = match.squads().getSquads().get(squadId());
        if (members != null) {
            for (UUID id : members) {
                if (!id.equals(bot.getUUID())) {
                    mates.add(id);
                }
            }
        }
        mates.sort(UUID::compareTo);
        return mates;
    }

    /** 本 bot 在小队里的稳定序号（0 起），供压制／绕侧角色分配。 */
    int squadRank() {
        int rank = 0;
        for (UUID mate : squadMates()) {
            if (mate.compareTo(bot.getUUID()) < 0) {
                rank++;
            }
        }
        return rank;
    }

    /** 把据点态势翻译成 {@link ConquestTactics} 的输入。 */
    List<ConquestTactics.PointAssessment> assessments() {
        SquadManager.SquadOrder order = squadOrder();
        List<ConquestTactics.PointAssessment> out = new ArrayList<>(points.size());
        for (PointState state : points) {
            ConquestMatch.PointView view = state.view();
            Faction owner = view.owner();
            ConquestTactics.PointStance stance = owner == null
                    ? ConquestTactics.PointStance.NEUTRAL
                    : (owner == faction ? ConquestTactics.PointStance.MINE
                            : ConquestTactics.PointStance.ENEMY);
            // level 以 ALPHA 为正，统一翻转成"以本方为正"
            double levelForMe = faction == Faction.ALPHA ? view.level() : -view.level();
            Vec3 center = view.center();
            double dx = center.x - bot.getX();
            double dz = center.z - bot.getZ();
            out.add(new ConquestTactics.PointAssessment(
                    view.pointId(), stance, levelForMe, Math.sqrt(dx * dx + dz * dz),
                    state.occupancy().of(faction), state.occupancy().of(faction.opponent()),
                    order != null && order.pointId() == view.pointId()));
        }
        return out;
    }

    ConquestTactics.Situation situation() {
        int mine = match.ownedPoints(faction);
        int theirs = match.ownedPoints(faction.opponent());
        double ratio = match.startingTicketsHint() <= 0 ? 1.0D
                : match.displayTickets(faction) / (double) match.startingTicketsHint();
        return new ConquestTactics.Situation(mine, theirs, ratio);
    }

    /** 此刻最该去的据点。 */
    Optional<ConquestTactics.PointAssessment> objective() {
        return ConquestTactics.pick(assessments(), situation());
    }

    /** 某据点的寻路落点（区域中心的地面高度）。 */
    @Nullable
    Vec3 pointCenter(int pointId) {
        for (PointState state : points) {
            if (state.view().pointId() == pointId) {
                return state.view().center();
            }
        }
        return null;
    }
}
