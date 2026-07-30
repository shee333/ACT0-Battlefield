package org.shee33.act0.battlefield.match;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.GameType;
import org.shee33.act0.battlefield.core.CapturePoint;
import org.shee33.act0.battlefield.core.Faction;
import org.shee33.act0.battlefield.data.BattlefieldData;
import org.shee33.act0.battlefield.data.ControlPointDef;
import org.shee33.act0.battlefield.integration.ArcadeLoadoutBridge;
import org.shee33.act0.battlefield.network.BattlefieldNetwork;
import org.shee33.act0.battlefield.network.DeployPointDto;
import org.shee33.act0.battlefield.network.DeploySquadMateDto;
import org.shee33.act0.battlefield.network.DeployStatusDto;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Handles all redeploy/deploy/spawn logic for a Conquest match.
 *
 * <p>Extracted from {@link ConquestMatch} to separate the deployment state machine
 * and spawn-point resolution from match-level capture/bleed/tick orchestration.
 * Delegates squad-spawn logic to {@link SquadManager}.
 */
public final class RedeployService {

    private final MinecraftServer server;
    private final ServerLevel level;
    private final BattlefieldData data;
    private final Map<UUID, Faction> factionOf;
    private final SquadManager squadManager;
    private final List<CapturePoint> points;
    private final List<ControlPointDef> defs;

    // Shared mutable state that deploy touches (owned by ConquestMatch).
    private final Map<UUID, Long> downedUntil;
    private final Map<UUID, Integer> escapeTicks;
    private final Map<UUID, Long> lastHurtTick;
    private final Consumer<UUID> cancelRevive;

    private final int redeployDelayTicks;
    private final int spawnProtectionTicks;

    // --- State owned by RedeployService ---
    private final Map<UUID, Long> redeployReadyTick = new LinkedHashMap<>();
    private final Map<UUID, String> deploySelection = new LinkedHashMap<>();
    private final Map<UUID, String> deployTarget = new LinkedHashMap<>();
    private final Map<UUID, GameType> redeployOriginalMode = new LinkedHashMap<>();
    private final Map<UUID, Long> protectedUntil = new LinkedHashMap<>();

    private boolean fireLocked;

    public RedeployService(
            ServerLevel level,
            BattlefieldData data,
            Map<UUID, Faction> factionOf,
            SquadManager squadManager,
            List<CapturePoint> points,
            List<ControlPointDef> defs,
            Map<UUID, Long> downedUntil,
            Map<UUID, Integer> escapeTicks,
            Map<UUID, Long> lastHurtTick,
            Consumer<UUID> cancelRevive,
            int spawnProtectionTicks,
            int redeployDelayTicks) {
        this.server = level.getServer();
        this.level = level;
        this.data = data;
        this.factionOf = factionOf;
        this.squadManager = squadManager;
        this.points = points;
        this.defs = defs;
        this.downedUntil = downedUntil;
        this.escapeTicks = escapeTicks;
        this.lastHurtTick = lastHurtTick;
        this.cancelRevive = cancelRevive;
        this.spawnProtectionTicks = spawnProtectionTicks;
        this.redeployDelayTicks = redeployDelayTicks;
    }

    // ---- Query helpers for ConquestMatch ----

    public boolean isRedeploying(UUID id) {
        return redeployReadyTick.containsKey(id);
    }

    public boolean consumeProtection(UUID id) {
        Long until = protectedUntil.get(id);
        if (until != null) {
            if (server.getTickCount() < until) {
                return true;
            }
            protectedUntil.remove(id);
        }
        return false;
    }

    public void removeProtection(UUID id) {
        protectedUntil.remove(id);
    }

    public void setFireLocked(boolean fireLocked) {
        this.fireLocked = fireLocked;
    }

    // ---- Redeploy lifecycle ----

    public void beginRedeploy(ServerPlayer player, Faction faction) {
        UUID id = player.getUUID();
        long readyTick = server.getTickCount() + redeployDelayTicks;
        redeployReadyTick.put(id, readyTick);
        String kind = bestDeployKind(id, faction);
        deploySelection.put(id, kind);
        deployTarget.put(id, bestDeployTarget(id, faction, kind));
        redeployOriginalMode.putIfAbsent(id, player.gameMode.getGameModeForPlayer());
        player.setGameMode(GameType.SPECTATOR);
        player.setInvulnerable(true);
        player.setDeltaMovement(0.0, 0.0, 0.0);
        teleportToDeployOverview(player, faction);
        BattlefieldNetwork.sendDeploy(player, true, deployStatus(player));
        BattlefieldNetwork.sendDeployLoadout(player, ArcadeLoadoutBridge.readDeployLoadout(player));
        player.sendSystemMessage(Component.literal("§6选择部署点，准备重返战场。"));
    }

    public void processRedeployTick() {
        if (redeployReadyTick.isEmpty()) {
            return;
        }
        if (server.getTickCount() % 20L != 0L) {
            return;
        }
        for (UUID id : new ArrayList<>(redeployReadyTick.keySet())) {
            ServerPlayer p = player(id);
            Faction faction = factionOf.get(id);
            if (p != null && faction != null) {
                teleportToDeployOverview(p, faction);
                BattlefieldNetwork.sendDeploy(p, true, deployStatus(p));
            }
        }
    }

    // ---- Deploy actions (public – called from network handlers) ----

    public void handleDeployAction(ServerPlayer player, String kind) {
        UUID id = player.getUUID();
        Faction faction = factionOf.get(id);
        handleDeployAction(player, kind,
                faction != null ? bestDeployTarget(id, faction, normalizeDeployKind(kind)) : "");
    }

    public void refreshDeployStatus(ServerPlayer player) {
        UUID id = player.getUUID();
        Faction faction = factionOf.get(id);
        if (faction != null && redeployReadyTick.containsKey(id)) {
            BattlefieldNetwork.sendDeploy(player, true, deployStatus(player));
        } else {
            BattlefieldNetwork.sendDeploy(player, false, DeployStatusDto.inactive());
        }
    }

    public void handleDeployAction(ServerPlayer player, String kind, String targetId) {
        UUID id = player.getUUID();
        Faction faction = factionOf.get(id);
        if (faction == null || !redeployReadyTick.containsKey(id)) {
            return;
        }
        String normalized = normalizeDeployKind(kind);
        String target = targetId != null ? targetId : "";
        if (!canDeployTo(id, faction, normalized, target)) {
            BattlefieldNetwork.sendDeploy(player, true, deployStatus(player));
            return;
        }
        deploySelection.put(id, normalized);
        deployTarget.put(id, target);
        if (server.getTickCount() >= redeployReadyTick.getOrDefault(id, 0L)) {
            deploy(player, faction, normalized, target);
        } else {
            BattlefieldNetwork.sendDeploy(player, true, deployStatus(player));
        }
    }

    public void onPlayerLogin(ServerPlayer player, Faction faction) {
        UUID id = player.getUUID();
        if (redeployReadyTick.containsKey(id)) {
            redeployOriginalMode.putIfAbsent(id, player.gameMode.getGameModeForPlayer());
            player.setGameMode(GameType.SPECTATOR);
            player.setInvulnerable(true);
            player.setDeltaMovement(0.0, 0.0, 0.0);
            teleportToDeployOverview(player, faction);
            BattlefieldNetwork.sendDeploy(player, true, deployStatus(player));
        }
    }

    public void clearRedeployState(ServerPlayer player, boolean restoreOriginalMode) {
        UUID id = player.getUUID();
        redeployReadyTick.remove(id);
        deploySelection.remove(id);
        deployTarget.remove(id);
        GameType original = redeployOriginalMode.remove(id);
        GameType targetMode = restoreOriginalMode && original != null ? original : GameType.ADVENTURE;
        if (targetMode == GameType.SPECTATOR) {
            targetMode = GameType.ADVENTURE;
        }
        player.setGameMode(targetMode);
        player.setInvulnerable(false);
        player.setDeltaMovement(0.0, 0.0, 0.0);
    }

    public void deployDirect(ServerPlayer player, Faction faction) {
        String kind = bestDeployKind(player.getUUID(), faction);
        deploy(player, faction, kind, bestDeployTarget(player.getUUID(), faction, kind));
    }

    public void clearAll() {
        redeployReadyTick.clear();
        deploySelection.clear();
        deployTarget.clear();
        redeployOriginalMode.clear();
        protectedUntil.clear();
    }

    // ---- Private: deploy status DTOs ----

    private DeployStatusDto deployStatus(ServerPlayer player) {
        UUID id = player.getUUID();
        Faction faction = factionOf.get(id);
        if (faction == null || !redeployReadyTick.containsKey(id)) {
            return DeployStatusDto.inactive();
        }
        BattlefieldData.BaseSpawn squad = squadManager.bestSquadSpawn(id, faction);
        BattlefieldData.BaseSpawn base = data.base(faction);
        List<DeployPointDto> pointDtos = deployPointDtos(faction);
        List<DeploySquadMateDto> squadDtos = squadManager.deploySquadMateDtos(id, faction);
        boolean canSquad = squadDtos.stream().anyMatch(DeploySquadMateDto::deployable);
        boolean canPoint = pointDtos.stream().anyMatch(DeployPointDto::deployable);
        boolean canBase = base != null;
        long readyTick = redeployReadyTick.getOrDefault(id, (long) server.getTickCount());
        int remain = (int) Math.max(0L, readyTick - server.getTickCount());
        String selected = deploySelection.getOrDefault(id, bestDeployKind(id, faction));
        String target = deployTarget.getOrDefault(id, bestDeployTarget(id, faction, selected));
        if (!canDeployTo(id, faction, selected, target)) {
            selected = bestDeployKind(id, faction);
            target = bestDeployTarget(id, faction, selected);
            deploySelection.put(id, selected);
            deployTarget.put(id, target);
        }
        org.shee33.act0.battlefield.core.BattleArea area = data.effectiveArea();
        boolean areaExplicit = data.areaOverride().isSet();
        return new DeployStatusDto(true, canSquad, canPoint, canBase, selected, target, remain,
                base != null ? base.x() : 0, base != null ? base.y() + 1.0 : 0, base != null ? base.z() : 0,
                squad != null ? squad.x() : 0, squad != null ? squad.y() + 1.0 : 0, squad != null ? squad.z() : 0,
                pointDtos, squadDtos,
                area.isSet(),
                area.minX(), area.minY(), area.minZ(),
                area.maxX(), area.maxY(), area.maxZ(),
                areaExplicit);
    }

    private List<DeployPointDto> deployPointDtos(Faction faction) {
        List<DeployPointDto> list = new ArrayList<>();
        for (int i = 0; i < defs.size(); i++) {
            ControlPointDef def = defs.get(i);
            CapturePoint point = points.get(i);
            boolean deployable = point.owner() == faction;
            list.add(new DeployPointDto(Integer.toString(def.pointId()), def.name(), factionCode(point.owner()),
                    deployable, def.pos().getX() + 0.5, def.pos().getY() + 1.5, def.pos().getZ() + 0.5));
        }
        return list;
    }

    // ---- Private: deploy selection helpers ----

    private String bestDeployKind(UUID id, Faction faction) {
        if (squadManager.firstDeployableSquadMate(id, faction) != null) {
            return "squad";
        }
        if (firstDeployablePointId(faction) != null) {
            return "point";
        }
        return "base";
    }

    private String bestDeployTarget(UUID id, Faction faction, String kind) {
        if ("point".equals(kind)) {
            String point = firstDeployablePointId(faction);
            return point != null ? point : "";
        }
        if ("squad".equals(kind)) {
            DeploySquadMateDto mate = squadManager.firstDeployableSquadMate(id, faction);
            return mate != null ? mate.id() : "";
        }
        return "";
    }

    private boolean canDeployTo(UUID id, Faction faction, String kind, String targetId) {
        return switch (kind) {
            case "squad" -> squadManager.squadMateSpawn(id, faction, targetId) != null;
            case "point" -> pointSpawn(faction, targetId) != null;
            case "base" -> data.base(faction) != null;
            default -> false;
        };
    }

    private static String normalizeDeployKind(String kind) {
        if ("squad".equals(kind) || "point".equals(kind) || "base".equals(kind)) {
            return kind;
        }
        return "base";
    }

    @Nullable
    private String firstDeployablePointId(Faction faction) {
        for (int i = 0; i < points.size(); i++) {
            if (points.get(i).owner() == faction) {
                return Integer.toString(defs.get(i).pointId());
            }
        }
        return null;
    }

    // ---- Private: spawn resolution ----

    @Nullable
    private BattlefieldData.BaseSpawn pointSpawn(Faction faction, String targetId) {
        for (int i = 0; i < defs.size(); i++) {
            ControlPointDef def = defs.get(i);
            if (!Integer.toString(def.pointId()).equals(targetId)) {
                continue;
            }
            if (points.get(i).owner() != faction) {
                return null;
            }
            return new BattlefieldData.BaseSpawn(def.pos().getX() + 0.5, def.pos().getY() + 1,
                    def.pos().getZ() + 0.5, 0f, 0f);
        }
        return null;
    }

    private void teleportToDeployOverview(ServerPlayer player, Faction faction) {
        BattlefieldData.BaseSpawn view = deployOverviewSpawn(faction);
        player.teleportTo(level, view.x(), view.y(), view.z(), view.yaw(), view.pitch());
        player.setDeltaMovement(0.0, 0.0, 0.0);
    }

    private BattlefieldData.BaseSpawn deployOverviewSpawn(Faction faction) {
        double minX = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE;
        double maxZ = -Double.MAX_VALUE;
        double maxY = level.getMinBuildHeight() + 64;
        for (ControlPointDef def : defs) {
            minX = Math.min(minX, def.pos().getX());
            maxX = Math.max(maxX, def.pos().getX());
            minZ = Math.min(minZ, def.pos().getZ());
            maxZ = Math.max(maxZ, def.pos().getZ());
            maxY = Math.max(maxY, def.pos().getY());
        }
        BattlefieldData.BaseSpawn a = data.base(Faction.ALPHA);
        BattlefieldData.BaseSpawn b = data.base(Faction.BRAVO);
        for (BattlefieldData.BaseSpawn spawn : new BattlefieldData.BaseSpawn[]{a, b}) {
            if (spawn == null) {
                continue;
            }
            minX = Math.min(minX, spawn.x());
            maxX = Math.max(maxX, spawn.x());
            minZ = Math.min(minZ, spawn.z());
            maxZ = Math.max(maxZ, spawn.z());
            maxY = Math.max(maxY, spawn.y());
        }
        if (minX == Double.MAX_VALUE) {
            BattlefieldData.BaseSpawn fallback = data.base(faction);
            if (fallback != null) {
                return new BattlefieldData.BaseSpawn(fallback.x(), fallback.y() + 64.0, fallback.z(), 0f, 90f);
            }
            return new BattlefieldData.BaseSpawn(0.5, maxY + 64.0, 0.5, 0f, 90f);
        }
        double cx = (minX + maxX) * 0.5;
        double cz = (minZ + maxZ) * 0.5;
        double span = Math.max(maxX - minX, maxZ - minZ);
        double height = Math.max(48.0, Math.min(140.0, span * 0.65 + 32.0));
        return new BattlefieldData.BaseSpawn(cx + 0.5, maxY + height, cz + 0.5, 0f, 90f);
    }

    // ---- Private: deploy execution ----

    private void deploy(ServerPlayer p, Faction f, String kind, String targetId) {
        UUID id = p.getUUID();
        BattlefieldData.BaseSpawn spawn = switch (kind) {
            case "squad" -> squadManager.squadMateSpawn(id, f, targetId);
            case "point" -> pointSpawn(f, targetId);
            default -> data.base(f);
        };
        if (spawn == null) {
            spawn = data.base(f);
        }
        p.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 40, 0, false, false));
        if (spawn != null) {
            p.teleportTo(level, spawn.x(), spawn.y(), spawn.z(), spawn.yaw(), spawn.pitch());
        }
        clearRedeployState(p, false);
        escapeTicks.remove(id);
        downedUntil.remove(id);
        cancelRevive.accept(id);
        p.setPose(Pose.STANDING);
        ArcadeLoadoutBridge.apply(p);
        p.setHealth(p.getMaxHealth());
        p.getFoodData().setFoodLevel(20);
        lastHurtTick.remove(id);
        p.removeEffect(MobEffects.REGENERATION);
        protectedUntil.put(id, (long) server.getTickCount() + spawnProtectionTicks);
        p.sendSystemMessage(Component.literal("§a已部署，短暂无敌保护已启动。"));
        BattlefieldNetwork.sendDeploy(p, false, DeployStatusDto.inactive());
        BattlefieldNetwork.sendFireLock(p, fireLocked);
    }

    // ---- Public: spawn helpers (also used by ConquestMatch) ----

    @Nullable
    public BattlefieldData.BaseSpawn livingSquadmateSpawn(UUID self) {
        return squadManager.livingSquadmateSpawn(self);
    }

    @Nullable
    public BattlefieldData.BaseSpawn forwardSpawn(Faction f) {
        BattlefieldData.BaseSpawn enemyBase = data.base(f.opponent());
        ControlPointDef best = null;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < points.size(); i++) {
            if (points.get(i).owner() != f) {
                continue;
            }
            ControlPointDef def = defs.get(i);
            if (enemyBase == null) {
                best = def;
                break;
            }
            double dx = def.pos().getX() - enemyBase.x();
            double dz = def.pos().getZ() - enemyBase.z();
            double d = dx * dx + dz * dz;
            if (d < bestDist) {
                bestDist = d;
                best = def;
            }
        }
        if (best == null) {
            return null;
        }
        return new BattlefieldData.BaseSpawn(
                best.pos().getX() + 0.5, best.pos().getY() + 1, best.pos().getZ() + 0.5, 0f, 0f);
    }

    @Nullable
    public BattlefieldData.BaseSpawn baseSpawn(Faction f) {
        return data.base(f);
    }

    // ---- Private: utility ----

    @Nullable
    private ServerPlayer player(UUID id) {
        return server.getPlayerList().getPlayer(id);
    }

    private static int factionCode(@Nullable Faction faction) {
        if (faction == Faction.ALPHA) {
            return 1;
        }
        if (faction == Faction.BRAVO) {
            return 2;
        }
        return 0;
    }
}
