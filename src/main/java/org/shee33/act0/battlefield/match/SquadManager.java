package org.shee33.act0.battlefield.match;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.shee33.act0.battlefield.core.Faction;
import org.shee33.act0.battlefield.data.BattlefieldData;
import org.shee33.act0.battlefield.network.DeploySquadMateDto;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Manages squad membership and squad-spawn logic for a Conquest match.
 *
 * <p>Squad state (player→squad mapping, squad→member set) lives here.
 * Deploy-related methods that require match-level context receive that
 * context via {@link #initDeployContext} after construction.
 */
public final class SquadManager {

    private final int squadSize;
    private final Map<UUID, Faction> factionOf;
    private final Map<UUID, Integer> squadOf = new LinkedHashMap<>();
    private final Map<Integer, LinkedHashSet<UUID>> squads = new LinkedHashMap<>();
    private final Map<Integer, UUID> squadLeaders = new LinkedHashMap<>();
    private final Map<Integer, Map<UUID, Long>> orderRequests = new LinkedHashMap<>();

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

    /** 按阵营自动分队：每个小队最多 squadSize 人，北大西洋公约/无邦军团各自独立连续编号。 */
    public void buildSquads() {
        squadOf.clear();
        squads.clear();
        int alphaSquad = 1;
        int bravoSquad = 101;
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
    }

    // ---- Squad leader ----

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
