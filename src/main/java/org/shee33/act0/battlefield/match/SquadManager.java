package org.shee33.act0.battlefield.match;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.shee33.act0.battlefield.core.Faction;
import org.shee33.act0.battlefield.data.BattlefieldData;
import org.shee33.act0.battlefield.network.DeploySquadMateDto;

import javax.annotation.Nullable;
import java.util.ArrayList;
import org.shee33.act0.battlefield.core.SquadJoinRules;
import java.util.Collections;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

/**
 * Manages squad membership and squad-spawn logic for a Conquest match.
 *
 * <p>Squad state (player→squad mapping, squad→member set) lives here.
 * Deploy-related methods that require match-level context receive that
 * context via {@link #initDeployContext} after construction.
 */
public final class SquadManager {

    /**
     * 小队人数硬上限。配置项可以调小，但不允许调大——小队是战地的最小协作单位，四人是整套
     * 小队机制（重生在队友身上、小队指令、小队语音位）的设计基准；放大到八人会让"跟着小队走"
     * 退化成"跟着半个阵营走"，也会让小队 HUD 面板越界。
     */
    public static final int MAX_SQUAD_SIZE = 4;

    /**
     * 小队编号按阵营分段：ALPHA 从 1 起、BRAVO 从 101 起。
     *
     * <p>分段的副作用是"小队号本身就编码了阵营"，{@link #isSameSquad} 因此天然不会跨阵营命中。
     * 提成常量是为了让 {@link #factionOfSquadId} 与 {@link #buildSquads} 用的是同一个分界点——
     * 两处各写一个 101 的话，改了一处就会让阵营判定与实际分队悄悄对不上。
     */
    public static final int ALPHA_SQUAD_BASE = 1;
    public static final int BRAVO_SQUAD_BASE = 101;

    private final int squadSize;
    private final Map<UUID, Faction> factionOf;
    private final Map<UUID, Integer> squadOf = new LinkedHashMap<>();
    private final Map<Integer, LinkedHashSet<UUID>> squads = new LinkedHashMap<>();
    private final Map<Integer, UUID> squadLeaders = new LinkedHashMap<>();
    private final Set<Integer> lockedSquads = new LinkedHashSet<>();
    private final Map<Integer, Map<UUID, Long>> orderRequests = new LinkedHashMap<>();
    private final Map<Integer, SquadOrder> activeOrders = new LinkedHashMap<>();

    /**
     * Active squad order — attack or defend a specific capture point.
     *
     * @param pointId target capture point ID
     * @param attack  true = attack order, false = defend order
     */
    public record SquadOrder(int pointId, boolean attack) {}

    // Deploy context — set once via initDeployContext after construction.
    private Function<UUID, ServerPlayer> playerLookup;
    private ServerLevel level;
    private Map<UUID, Long> downedUntil;
    private double squadDeployEnemyBlockRadius;

    public SquadManager(int squadSize, Map<UUID, Faction> factionOf) {
        this.squadSize = squadSize;
        this.factionOf = factionOf;
    }

    /**
     * One-time initialisation of the match context needed by deploy-related methods.
     * Must be called before any deploy method is invoked.
     */
    void initDeployContext(Function<UUID, ServerPlayer> playerLookup, ServerLevel level,
                           Map<UUID, Long> downedUntil, double squadDeployEnemyBlockRadius) {
        this.playerLookup = playerLookup;
        this.level = level;
        this.downedUntil = downedUntil;
        this.squadDeployEnemyBlockRadius = squadDeployEnemyBlockRadius;
    }

    // ---- Getters ----

    /** Raw player→squad map (read-only from external callers). */
    public Map<UUID, Integer> getSquadOf() {
        return squadOf;
    }

    /** Raw squad→members map (read-only from external callers). */
    public Map<Integer, LinkedHashSet<UUID>> getSquads() {
        return squads;
    }

    public int getSquadSize() {
        return squadSize;
    }

    // ---- Squad building ----

    /** 按阵营自动分队：每个小队最多 squadSize 人，ALPHA/BRAVO 各自独立连续编号。 */
    public void buildSquads() {
        squadOf.clear();
        squads.clear();
        int alphaSquad = ALPHA_SQUAD_BASE;
        int bravoSquad = BRAVO_SQUAD_BASE;
        int alphaCount = 0;
        int bravoCount = 0;
        for (Map.Entry<UUID, Faction> e : factionOf.entrySet()) {
            int squadId;
            if (e.getValue() == Faction.ALPHA) {
                if (alphaCount > 0 && alphaCount % squadSize == 0) {
                    alphaSquad++;
                }
                squadId = alphaSquad;
                alphaCount++;
            } else {
                if (bravoCount > 0 && bravoCount % squadSize == 0) {
                    bravoSquad++;
                }
                squadId = bravoSquad;
                bravoCount++;
            }
            squadOf.put(e.getKey(), squadId);
            squads.computeIfAbsent(squadId, ignored -> new LinkedHashSet<>()).add(e.getKey());
        }
        // Auto-designate first member of each squad as leader.
        for (Map.Entry<Integer, LinkedHashSet<UUID>> entry : squads.entrySet()) {
            if (!squadLeaders.containsKey(entry.getKey()) && !entry.getValue().isEmpty()) {
                squadLeaders.put(entry.getKey(), entry.getValue().iterator().next());
            }
        }
        squadLeaders.keySet().removeIf(id -> !squads.containsKey(id));
        orderRequests.keySet().removeIf(id -> !squads.containsKey(id));
        activeOrders.keySet().removeIf(id -> !squads.containsKey(id));
    }

    // ---- 中途加入(latecomer)增量分队 ----

    /**
     * 中途加入玩家的增量分队，与 {@link #buildSquads()} 的"全量清空重建"完全不同，专供
     * 对局进行中加入的玩家使用。
     *
     * <p>{@link #buildSquads()} 会先清空 {@link #squadOf}/{@link #squads}，再按
     * {@link #factionOf} 当前的迭代顺序从头重新编号所有小队——开局分队时这没问题，但中途
     * 加入时新玩家的 UUID 是最后插入 {@link #factionOf} 的，在迭代顺序里必然排在最后。一旦
     * 该阵营现有小队恰好都满员，新玩家就会被塞进"下一个新开的小队"，自己一个人形成一个孤零零
     * 的小队；而且全量重建还会把其他所有玩家的 squadOf/squads 也重新算一遍，虽然队长指派逻辑
     * 有"已有队长不覆盖"的保护，但这仍然是不必要的、有风险的全量操作。
     *
     * <p>本方法只做增量：
     * <ol>
     *   <li>找出该阵营现有小队里人数未满 {@code squadSize} 的小队；</li>
     *   <li>如果有，从这些"未满小队"里随机挑一个塞进去（不是塞进最空的那个），该小队原有
     *       队长不变；</li>
     *   <li>如果该阵营现有小队全部满员（或该阵营还没有任何小队），才新开一个小队编号，玩家
     *       自己是这个新小队唯一的成员，也直接指派为队长。</li>
     * </ol>
     * 全程只新增一条 {@link #squadOf}/{@link #squads} 记录，绝不触碰其他玩家已有的
     * squadOf/squads/squadLeaders 映射。
     *
     * @param playerId 中途加入的玩家 UUID（调用前应已写入 {@link #factionOf}）
     * @param faction  玩家所属阵营
     */
    public void assignLatecomer(UUID playerId, Faction faction) {
        List<Integer> underfull = new ArrayList<>();
        for (Map.Entry<Integer, LinkedHashSet<UUID>> entry : squads.entrySet()) {
            LinkedHashSet<UUID> members = entry.getValue();
            if (members.isEmpty() || members.size() >= squadSize) {
                continue;
            }
            UUID firstMember = members.iterator().next();
            if (factionOf.get(firstMember) == faction) {
                underfull.add(entry.getKey());
            }
        }
        boolean opensNewSquad = underfull.isEmpty();
        int squadId = opensNewSquad
                ? nextSquadId(faction)
                : underfull.get(ThreadLocalRandom.current().nextInt(underfull.size()));
        squadOf.put(playerId, squadId);
        squads.computeIfAbsent(squadId, ignored -> new LinkedHashSet<>()).add(playerId);
        if (opensNewSquad) {
            // 新小队没有既存队长，新玩家是唯一成员，直接指派为队长——与 buildSquads() 的
            // 队长自动指派逻辑一致（每个小队的第一个成员即队长）。
            squadLeaders.put(squadId, playerId);
        }
    }

    /**
     * 玩家退出对局时的增量移除，与 {@link #buildSquads()} 的"全量清空重建"相对。
     *
     * <p>此前退出走的是 {@code onPlayerLeave} + {@code buildSquads()}：后者会清空所有映射并按
     * {@link #factionOf} 的迭代顺序从头重新编号。中途有人退出时，排在他后面的每个玩家都会往前
     * 挤一位，跨越小队边界的那些人直接被换到别的小队去——打着打着队友突然全换人。这与
     * {@link #assignLatecomer} 刻意避免全量重建的初衷正好相反。
     *
     * <p>本方法只动退出者自己：从 {@link #squadOf}/{@link #squads} 摘除；若他是队长，则由剩余
     * 成员中的第一个顺延接任（与 {@link #buildSquads()} "第一个成员即队长"的规则一致）；若小队
     * 因此空了，则连同小队编号、队长、待处理的命令请求与生效中的命令一并清理。其余玩家的
     * squadOf/squads/squadLeaders 一律不动。
     *
     * @param playerId 退出对局的玩家 UUID
     */
    public void removeMember(UUID playerId) {
        Integer squadId = squadOf.remove(playerId);
        if (squadId == null) {
            return;
        }
        LinkedHashSet<UUID> members = squads.get(squadId);
        if (members != null) {
            members.remove(playerId);
        }
        Map<UUID, Long> requests = orderRequests.get(squadId);
        if (requests != null) {
            requests.remove(playerId);
        }
        if (members == null || members.isEmpty()) {
            squads.remove(squadId);
            squadLeaders.remove(squadId);
            lockedSquads.remove(squadId);
            orderRequests.remove(squadId);
            activeOrders.remove(squadId);
            return;
        }
        if (playerId.equals(squadLeaders.get(squadId))) {
            squadLeaders.put(squadId, members.iterator().next());
            // 队长换人后，原队长任内积压的命令请求已无意义——新队长应从干净状态开始，
            // 否则旧请求的计时会继续走，可能立刻把某个队员自动提为队长。
            if (requests != null) {
                requests.clear();
            }
            activeOrders.remove(squadId);
        }
    }

    /**
     * 给指定阵营找一个尚未被占用的小队编号，沿用 {@link #buildSquads()} 同样的编号规则
     * （ALPHA 从 1 起、BRAVO 从 101 起，逐个探测直到找到空位）。
     */
    private int nextSquadId(Faction faction) {
        int candidate = faction == Faction.ALPHA ? 1 : 101;
        while (squads.containsKey(candidate)) {
            candidate++;
        }
        return candidate;
    }

    // ---- Squad leader ----

    /** 小队号所属阵营；号段之外返回 {@code null}。 */
    @Nullable
    public Faction factionOfSquadId(int squadId) {
        if (squadId >= BRAVO_SQUAD_BASE) {
            return Faction.BRAVO;
        }
        return squadId >= ALPHA_SQUAD_BASE ? Faction.ALPHA : null;
    }

    /** 该小队是否已被队长锁定（锁定后其他玩家无法主动加入）。 */
    public boolean isLocked(int squadId) {
        return lockedSquads.contains(squadId);
    }

    /**
     * 切换本队锁定状态；仅队长可操作。
     *
     * @return 操作后的锁定状态；无权操作时返回当前状态且不变更
     */
    public boolean toggleLock(UUID playerId) {
        int squadId = squadIdOf(playerId);
        if (!SquadJoinRules.canToggleLock(isSquadLeader(playerId), squadId)) {
            return isLocked(squadId);
        }
        if (lockedSquads.contains(squadId)) {
            lockedSquads.remove(squadId);
            return false;
        }
        lockedSquads.add(squadId);
        return true;
    }

    /**
     * 玩家主动离队，之后处于"未加入任何小队"状态。
     *
     * <p>刻意不自动补进别的小队：规格文档的未加入态是一个玩家可以停留的合法状态（缩略卡显示
     * 空位、子页面显示提示文案）。自动补位会让"离开小队"这个操作看起来根本没生效。
     *
     * @return 是否确实离开了小队
     */
    public boolean leaveSquad(UUID playerId) {
        if (!SquadJoinRules.canLeave(squadIdOf(playerId))) {
            return false;
        }
        removeMember(playerId);
        return true;
    }

    /**
     * 玩家主动加入指定小队。准入判据见 {@link SquadJoinRules#canJoin}。
     *
     * @return 判定结果；{@link SquadJoinRules.Result#OK} 表示已完成搬迁
     */
    public SquadJoinRules.Result joinSquad(UUID playerId, int targetSquadId) {
        Faction mine = factionOf.get(playerId);
        Faction targetFaction = factionOfSquadId(targetSquadId);
        LinkedHashSet<UUID> target = squads.get(targetSquadId);
        SquadJoinRules.Result result = SquadJoinRules.canJoin(squadIdOf(playerId), targetSquadId,
                target == null ? 0 : target.size(), isLocked(targetSquadId),
                mine != null && mine == targetFaction);
        if (result != SquadJoinRules.Result.OK) {
            return result;
        }
        // 先退旧队再进新队：removeMember 会顺带处理"队长走了要升新队长"和"空队要清理"，
        // 自己手写搬迁很容易漏掉其中一条。
        removeMember(playerId);
        squadOf.put(playerId, targetSquadId);
        squads.computeIfAbsent(targetSquadId, ignored -> new LinkedHashSet<>()).add(playerId);
        squadLeaders.putIfAbsent(targetSquadId, playerId);
        return SquadJoinRules.Result.OK;
    }

    /** 某阵营当前存在的小队号，升序。 */
    public List<Integer> squadIdsOf(Faction faction) {
        List<Integer> out = new ArrayList<>();
        for (Integer id : squads.keySet()) {
            if (factionOfSquadId(id) == faction) {
                out.add(id);
            }
        }
        Collections.sort(out);
        return out;
    }

    public boolean isSquadLeader(UUID playerId) {
        Integer squadId = squadOf.get(playerId);
        if (squadId == null) {
            return false;
        }
        return playerId.equals(squadLeaders.get(squadId));
    }

    public void requestOrder(UUID playerId, long currentTick) {
        Integer squadId = squadOf.get(playerId);
        if (squadId == null || isSquadLeader(playerId)) {
            return;
        }
        orderRequests.computeIfAbsent(squadId, k -> new LinkedHashMap<>())
                .putIfAbsent(playerId, currentTick);
    }

    public void promoteLeader(int squadId, UUID newLeader) {
        LinkedHashSet<UUID> members = squads.get(squadId);
        if (members == null || !members.contains(newLeader)) {
            return;
        }
        squadLeaders.put(squadId, newLeader);
        Map<UUID, Long> requests = orderRequests.get(squadId);
        if (requests != null) {
            requests.clear();
        }
    }

    /**
     * Auto-promotion tick: if a squad member's order request goes unanswered
     * for 60 seconds, promote the requesting member to leader.
     */
    public void tick(long currentTick) {
        for (Map.Entry<Integer, Map<UUID, Long>> entry : new ArrayList<>(orderRequests.entrySet())) {
            int squadId = entry.getKey();
            UUID toPromote = null;
            for (Map.Entry<UUID, Long> req : new ArrayList<>(entry.getValue().entrySet())) {
                if (currentTick - req.getValue() >= 1200L) {
                    toPromote = req.getKey();
                    break;
                }
            }
            if (toPromote != null) {
                promoteLeader(squadId, toPromote);
            }
        }
    }

    // ---- Query methods ----

    /** 玩家所属小队编号；不在对局/未分队则返回 0。 */
    public int squadIdOf(UUID id) {
        return squadOf.getOrDefault(id, 0);
    }

    /** 玩家所属小队人数；不在小队则返回 0。 */
    public int squadSizeOf(UUID id) {
        Integer squadId = squadOf.get(id);
        if (squadId == null) {
            return 0;
        }
        LinkedHashSet<UUID> members = squads.get(squadId);
        return members == null ? 0 : members.size();
    }

    /** 两名玩家是否同一小队；任一玩家不在小队（squadId 为 0）时视为不同小队。供聊天染色等关系判断使用。 */
    public boolean isSameSquad(UUID a, UUID b) {
        int squadA = squadIdOf(a);
        return squadA != 0 && squadA == squadIdOf(b);
    }

    // ---- Squad orders ----

    public void setOrder(int squadId, SquadOrder order) {
        activeOrders.put(squadId, order);
    }

    @Nullable
    public SquadOrder getOrder(int squadId) {
        return activeOrders.get(squadId);
    }

    public void clearOrder(int squadId) {
        activeOrders.remove(squadId);
    }

    public Map<Integer, SquadOrder> getActiveOrders() {
        return activeOrders;
    }

    // ---- Squad spawn / deploy methods ----

    public List<DeploySquadMateDto> deploySquadMateDtos(UUID self, Faction faction) {
        List<DeploySquadMateDto> list = new ArrayList<>();
        Integer squadId = squadOf.get(self);
        if (squadId == null) {
            return list;
        }
        LinkedHashSet<UUID> members = squads.get(squadId);
        if (members == null) {
            return list;
        }
        for (UUID mateId : members) {
            if (mateId.equals(self)) {
                continue;
            }
            ServerPlayer mate = playerLookup.apply(mateId);
            if (mate == null || mate.level() != level || !mate.isAlive() || mate.isSpectator()) {
                continue;
            }
            boolean deployable = !enemyNear(mate, faction, squadDeployEnemyBlockRadius)
                    && !downedUntil.containsKey(mateId);
            list.add(new DeploySquadMateDto(mateId.toString(), mate.getGameProfile().getName(), mate.getId(),
                    deployable, mate.getX(), mate.getY() + 1.0, mate.getZ()));
        }
        return list;
    }

    @Nullable
    public DeploySquadMateDto firstDeployableSquadMate(UUID self, Faction faction) {
        for (DeploySquadMateDto mate : deploySquadMateDtos(self, faction)) {
            if (mate.deployable()) {
                return mate;
            }
        }
        return null;
    }

    @Nullable
    public BattlefieldData.BaseSpawn bestSquadSpawn(UUID self, Faction faction) {
        DeploySquadMateDto first = firstDeployableSquadMate(self, faction);
        return first != null ? squadMateSpawn(self, faction, first.id()) : null;
    }

    @Nullable
    public BattlefieldData.BaseSpawn squadMateSpawn(UUID self, Faction faction, String targetId) {
        if (targetId == null || targetId.isBlank()) {
            return bestSquadSpawn(self, faction);
        }
        UUID mateUuid;
        try {
            mateUuid = UUID.fromString(targetId);
        } catch (IllegalArgumentException e) {
            return null;
        }
        Integer squadId = squadOf.get(self);
        LinkedHashSet<UUID> members = squadId == null ? null : squads.get(squadId);
        if (members == null || !members.contains(mateUuid)) {
            return null;
        }
        ServerPlayer mate = playerLookup.apply(mateUuid);
        if (mate == null || mate.level() != level || !mate.isAlive() || mate.isSpectator()
                || downedUntil.containsKey(mateUuid)) {
            return null;
        }
        if (enemyNear(mate, faction, squadDeployEnemyBlockRadius)) {
            return null;
        }
        return new BattlefieldData.BaseSpawn(mate.getX(), mate.getY(), mate.getZ(), mate.getYRot(), mate.getXRot());
    }

    /** Squad respawn point: living squadmate if available. */
    @Nullable
    public BattlefieldData.BaseSpawn livingSquadmateSpawn(UUID self) {
        Integer squadId = squadOf.get(self);
        if (squadId == null) {
            return null;
        }
        LinkedHashSet<UUID> members = squads.get(squadId);
        if (members == null) {
            return null;
        }
        for (UUID mateId : members) {
            if (mateId.equals(self)) {
                continue;
            }
            ServerPlayer mate = playerLookup.apply(mateId);
            if (mate != null && mate.level() == level && mate.isAlive() && !mate.isSpectator()) {
                return new BattlefieldData.BaseSpawn(mate.getX(), mate.getY(), mate.getZ(), mate.getYRot(), mate.getXRot());
            }
        }
        return null;
    }

    // ---- Internal helpers ----

    private boolean enemyNear(ServerPlayer origin, Faction faction, double radius) {
        double r2 = radius * radius;
        for (Map.Entry<UUID, Faction> e : factionOf.entrySet()) {
            if (e.getValue() == faction) {
                continue;
            }
            ServerPlayer enemy = playerLookup.apply(e.getKey());
            if (enemy == null || enemy.level() != level || !enemy.isAlive() || enemy.isSpectator()
                    || downedUntil.containsKey(e.getKey())) {
                continue;
            }
            double dx = enemy.getX() - origin.getX();
            double dz = enemy.getZ() - origin.getZ();
            if (dx * dx + dz * dz <= r2) {
                return true;
            }
        }
        return false;
    }
}
