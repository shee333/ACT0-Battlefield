package org.shee33.act0.battlefield.match;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
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
import org.shee33.act0.battlefield.network.DeployAllyDto;
import org.shee33.act0.battlefield.network.DeployLoadoutDto;
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
    /**
     * 部署界面左上角"动态模式标签"展示用的对局模式名（如"征服模式"/"突破模式"），由
     * {@link ConquestMatch}/{@link BreakthroughMatch} 构造本类时传入——本 mod 一场对局从始至终
     * 只对应单一模式，这里只是一个只读展示字符串，不参与任何判定逻辑。
     */
    private final String matchModeName;

    // --- State owned by RedeployService ---
    private final Map<UUID, Long> redeployReadyTick = new LinkedHashMap<>();
    private final Map<UUID, String> deploySelection = new LinkedHashMap<>();
    private final Map<UUID, String> deployTarget = new LinkedHashMap<>();
    private final Map<UUID, GameType> redeployOriginalMode = new LinkedHashMap<>();
    private final Map<UUID, Long> protectedUntil = new LinkedHashMap<>();
    private final Map<UUID, Integer> spectateTarget = new LinkedHashMap<>();
    private final Map<UUID, PanState> deployPanState = new LinkedHashMap<>();
    /**
     * 玩家 UUID → (槽位序号 → 覆盖后物品名) 的本次对局会话覆盖状态。玩家在部署界面武器更换
     * 面板里选中的槖位覆盖只影响"这一命"落地时应用的装备，不改变 Arcade 里保存的配装本身。
     *
     * <p>生命周期：一旦写入就持续有效，直到玩家自己再改（覆盖同一槽位）或退出对局/对局结束
     * ——每次重生之间<b>不</b>自动重置，这更符合"这条命换的装备一直用到我再换"的直觉，而不是
     * 每次死亡重生都被静默清空。见 {@link #clearLoadoutOverride}（退出对局时调用）与
     * {@link #clearAll}（对局结束/中止时调用）。
     */
    private final Map<UUID, Map<Integer, String>> loadoutOverrides = new LinkedHashMap<>();
    /**
     * 玩家 UUID → 上次成功处理 {@code DeploySlotOverridePacket} 的 tick（P1-2 修复）。每收到
     * 这个小包都要跑一遍反射开销不小的 {@link ArcadeLoadoutBridge#readDeployLoadout}，
     * 无节流会让恶意/异常客户端狂发小包造成主线程反射风暴+回包带宽放大；参照
     * {@link ConquestMatch} 里 CALL_HELP 呼救冷却同款"记录上次处理 tick，间隔太短直接丢弃"写法。
     */
    private final Map<UUID, Long> lastSlotOverrideTick = new LinkedHashMap<>();
    /** 换装覆盖包最小处理间隔：100ms（20 tick/s，2 tick）。 */
    private static final int MIN_SLOT_OVERRIDE_INTERVAL_TICKS = 2;

    /** Deploy-overview-to-spawn camera pan duration. Kept well under 1s (20 ticks). */
    private static final int PAN_DURATION_TICKS = 18;

    private boolean fireLocked;

    /**
     * Immutable snapshot of an in-flight "deploy pan" – the short eased camera move from the
     * deploy overview point to the resolved spawn point, so the confirm action never hard-cuts
     * the player's view.
     */
    private record PanState(
            Faction faction, String kind, String targetId, boolean hasSpawn,
            double startX, double startY, double startZ, float startYaw, float startPitch,
            double endX, double endY, double endZ, float endYaw, float endPitch,
            long startTick) {
    }

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
            int redeployDelayTicks,
            String matchModeName) {
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
        this.matchModeName = matchModeName;
    }

    // ---- Query helpers for ConquestMatch ----

    public boolean isRedeploying(UUID id) {
        return redeployReadyTick.containsKey(id) || deployPanState.containsKey(id);
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
        // Resolve the nearest living squadmate BEFORE teleporting the player away to the
        // deploy overview, so the distance is measured from the player's actual death spot
        // (post-teleport the player floats above the whole map, making distance meaningless).
        // This is only a *hint* for the client's V-key squad-cycle (see DeployStatusDto.spectateEntityId());
        // it must not be treated as an implicit selection (see deploySelection below).
        spectateTarget.put(id, nearestSquadmateEntityId(player, id, faction));
        long readyTick = server.getTickCount() + redeployDelayTicks;
        redeployReadyTick.put(id, readyTick);
        // 死亡瞬间默认不选中任何具体部署目标：观战相机据此保持全局俯瞰，直到玩家在部署列表里
        // 主动点选某个据点/基地/队友（见 deployStatus() 里对空选择的保留、不再自动补齐）。
        deploySelection.put(id, "");
        deployTarget.put(id, "");
        redeployOriginalMode.putIfAbsent(id, player.gameMode.getGameModeForPlayer());
        player.setGameMode(GameType.SPECTATOR);
        player.setInvulnerable(true);
        player.setDeltaMovement(0.0, 0.0, 0.0);
        teleportToDeployOverview(player, faction);
        BattlefieldNetwork.sendDeploy(player, true, deployStatus(player));
        BattlefieldNetwork.sendDeployLoadout(player, deployLoadoutFor(player));
        player.sendSystemMessage(Component.literal("§6选择部署点，准备重返战场。"));
    }

    public void processRedeployTick() {
        tickDeployPan();
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

    /**
     * 部署界面底部武器更换面板提交的槖位覆盖选择（{@code DeploySlotOverridePacket}）。
     * 只在部署界面确实开着时受理，物品名必须在该槖位当前的已解锁可选列表内才会被接受——
     * 校验逻辑见 {@link DeployLoadoutDto#isValidOverride}（纯函数，不依赖本类）。
     */
    public void handleSlotOverride(ServerPlayer player, int slotIndex, String itemName) {
        UUID id = player.getUUID();
        if (!redeployReadyTick.containsKey(id)) {
            return;
        }
        long now = server.getTickCount();
        if (isSlotOverrideThrottled(lastSlotOverrideTick.get(id), now)) {
            // 请求间隔太短(<100ms):直接丢弃,不回复任何东西——正常客户端点击换装面板的频率
            // 不会撞上这个门槛,只有异常/恶意客户端狂发这个C2S小包才会被限制住(P1-2修复)。
            return;
        }
        lastSlotOverrideTick.put(id, now);
        DeployLoadoutDto base = ArcadeLoadoutBridge.readDeployLoadout(player);
        String item = itemName != null ? itemName : "";
        if (base.isValidOverride(slotIndex, item)) {
            loadoutOverrides.computeIfAbsent(id, ignored -> new LinkedHashMap<>()).put(slotIndex, item);
        }
        BattlefieldNetwork.sendDeployLoadout(player, base.withOverrides(loadoutOverrides.get(id)));
    }

    /**
     * 纯函数(P1-2修复):给定"上次处理这个包的tick"与"当前tick",判断这次请求是否应该被节流
     * 拒绝——间隔小于 {@link #MIN_SLOT_OVERRIDE_INTERVAL_TICKS}(100ms)时拒绝。不依赖
     * {@code ServerPlayer}/{@code MinecraftServer},可直接单测。
     */
    static boolean isSlotOverrideThrottled(@Nullable Long lastTick, long nowTick) {
        return lastTick != null && nowTick - lastTick < MIN_SLOT_OVERRIDE_INTERVAL_TICKS;
    }

    /** 把本次对局会话覆盖叠加到 Arcade 原始配装快照上，供部署界面展示。 */
    private DeployLoadoutDto deployLoadoutFor(ServerPlayer player) {
        DeployLoadoutDto base = ArcadeLoadoutBridge.readDeployLoadout(player);
        return base.withOverrides(loadoutOverrides.get(player.getUUID()));
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
            beginDeployPan(player, faction, normalized, target);
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
        spectateTarget.remove(id);
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
        beginDeployPan(player, faction, kind, bestDeployTarget(player.getUUID(), faction, kind));
    }

    public void clearAll() {
        redeployReadyTick.clear();
        deploySelection.clear();
        deployTarget.clear();
        redeployOriginalMode.clear();
        protectedUntil.clear();
        spectateTarget.clear();
        deployPanState.clear();
        loadoutOverrides.clear();
        lastSlotOverrideTick.clear();
    }

    /** 玩家退出对局时调用：清除其本次对局的槽位覆盖会话状态，避免残留造成内存泄漏。 */
    public void clearLoadoutOverride(UUID id) {
        loadoutOverrides.remove(id);
        lastSlotOverrideTick.remove(id);
    }

    /** Entity id of the living squadmate closest to {@code player}, or -1 if none. */
    private int nearestSquadmateEntityId(ServerPlayer player, UUID id, Faction faction) {
        int best = -1;
        double bestDist = Double.MAX_VALUE;
        for (DeploySquadMateDto mate : squadManager.deploySquadMateDtos(id, faction)) {
            double dx = mate.x() - player.getX();
            double dy = mate.y() - player.getY();
            double dz = mate.z() - player.getZ();
            double dist = dx * dx + dy * dy + dz * dz;
            if (dist < bestDist) {
                bestDist = dist;
                best = mate.entityId();
            }
        }
        return best;
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
        List<DeployAllyDto> allyDtos = deployAllyDtos(id, faction);
        boolean canSquad = squadDtos.stream().anyMatch(DeploySquadMateDto::deployable);
        boolean canPoint = pointDtos.stream().anyMatch(DeployPointDto::deployable);
        boolean canBase = base != null;
        long readyTick = redeployReadyTick.getOrDefault(id, (long) server.getTickCount());
        int remain = (int) Math.max(0L, readyTick - server.getTickCount());
        String selected = deploySelection.getOrDefault(id, "");
        String target = deployTarget.getOrDefault(id, "");
        if (!selected.isBlank() && !canDeployTo(id, faction, selected, target)) {
            // 已选中的目标失效了（队友阵亡/据点易主等）：退回"未选中"而不是静默换绑到另一个目标，
            // 这样客户端的观战相机会据此回落到全局俯瞰，而不是无提示地跳到别的跟随对象。
            selected = "";
            target = "";
            deploySelection.put(id, selected);
            deployTarget.put(id, target);
        }
        org.shee33.act0.battlefield.core.BattleArea area = data.effectiveArea();
        boolean areaExplicit = data.areaOverride().isSet();
        // 地图名：直接取存档/世界名(server.properties 的 level-name)，本 mod 没有独立的"地图轮换"
        // 系统，一个世界即对应一张固定的对局地图。
        String mapName = server.getWorldData().getLevelName();
        return new DeployStatusDto(true, canSquad, canPoint, canBase, selected, target, remain,
                base != null ? base.x() : 0, base != null ? base.y() + 1.0 : 0, base != null ? base.z() : 0,
                squad != null ? squad.x() : 0, squad != null ? squad.y() + 1.0 : 0, squad != null ? squad.z() : 0,
                pointDtos, squadDtos, allyDtos,
                area.isSet(),
                area.minX(), area.minY(), area.minZ(),
                area.maxX(), area.maxY(), area.maxZ(),
                areaExplicit,
                spectateTarget.getOrDefault(id, -1),
                matchModeName, mapName);
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

    /**
     * 同阵营(非小队)存活玩家坐标标记——见《部署界面动效规格文档》2.2 节"同阵营玩家"一行：
     * 蓝色实心圆、纯展示不可交互。与 {@link SquadManager#deploySquadMateDtos} 互斥：已经在
     * 小队成员列表里的人不会再在这里重复出现一次，避免地图上同一个人被画成两种标记。
     */
    private List<DeployAllyDto> deployAllyDtos(UUID self, Faction faction) {
        List<DeployAllyDto> list = new ArrayList<>();
        for (UUID otherId : filterAllyCandidateIds(self, faction, factionOf, squadManager)) {
            ServerPlayer other = player(otherId);
            if (other == null || other.level() != level || !other.isAlive() || other.isSpectator()
                    || downedUntil.containsKey(otherId)) {
                continue;
            }
            list.add(new DeployAllyDto(otherId.toString(), other.getGameProfile().getName(), other.getId(),
                    other.getX(), other.getY() + 1.0, other.getZ()));
        }
        return list;
    }

    /**
     * 纯函数：从阵营归属表里筛出"应作为同阵营点位标记显示"的候选玩家 ID——排除自己、排除敌方、
     * 排除同小队成员。真实的存活/旁观者/倒地/所在世界校验依赖 {@code ServerPlayer}，由调用方
     * （{@link #deployAllyDtos}）在此基础上再筛一遍。不依赖 {@code ServerPlayer}/
     * {@code ServerLevel}，可直接单测。
     */
    static List<UUID> filterAllyCandidateIds(UUID self, Faction selfFaction, Map<UUID, Faction> factionOf,
                                              SquadManager squadManager) {
        List<UUID> out = new ArrayList<>();
        for (Map.Entry<UUID, Faction> e : factionOf.entrySet()) {
            UUID id = e.getKey();
            if (id.equals(self) || e.getValue() != selfFaction) {
                continue;
            }
            if (squadManager.isSameSquad(self, id)) {
                continue;
            }
            out.add(id);
        }
        return out;
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

    @Nullable
    private BattlefieldData.BaseSpawn resolveSpawn(UUID id, Faction f, String kind, String targetId) {
        BattlefieldData.BaseSpawn spawn = switch (kind) {
            case "squad" -> squadManager.squadMateSpawn(id, f, targetId);
            case "point" -> pointSpawn(f, targetId);
            default -> data.base(f);
        };
        return spawn != null ? spawn : data.base(f);
    }

    /**
     * Confirm-deploy entry point. Instead of hard-teleporting straight to the spawn, this
     * records the current (deploy-overview) pose as the pan start and the resolved spawn as the
     * pan end. {@link #tickDeployPan()} keeps nudging the player's real entity position toward
     * the end pose once per tick (authoritative landing spot), while a one-shot
     * {@code DeployPanPacket} hands the same start/end/duration to the client so it can ease the
     * *rendered* camera every render frame instead of every 20Hz tick — see {@code ClientDeployPan}
     * / {@code DeployPanCameraHandler}.
     */
    private void beginDeployPan(ServerPlayer p, Faction f, String kind, String targetId) {
        UUID id = p.getUUID();
        BattlefieldData.BaseSpawn spawn = resolveSpawn(id, f, kind, targetId);
        boolean hasSpawn = spawn != null;
        double endX = hasSpawn ? spawn.x() : p.getX();
        double endY = hasSpawn ? spawn.y() : p.getY();
        double endZ = hasSpawn ? spawn.z() : p.getZ();
        float endYaw = hasSpawn ? spawn.yaw() : p.getYRot();
        float endPitch = hasSpawn ? spawn.pitch() : p.getXRot();
        redeployReadyTick.remove(id);
        deploySelection.remove(id);
        deployTarget.remove(id);
        double startX = p.getX();
        double startY = p.getY();
        double startZ = p.getZ();
        float startYaw = p.getYRot();
        float startPitch = p.getXRot();
        deployPanState.put(id, new PanState(f, kind, targetId, hasSpawn,
                startX, startY, startZ, startYaw, startPitch,
                endX, endY, endZ, endYaw, endPitch,
                server.getTickCount()));
        // 客户端插值频率提升：过场的视觉呈现完全交给客户端每渲染帧自算(见 ClientDeployPan /
        // DeployPanCameraHandler),这里只在过场开始时把起止位姿+总时长发一次,不逐 tick 重发。
        // tickDeployPan() 仍然逐 tick 推进真实实体位置,作为服务端权威落点,不受这次改造影响。
        BattlefieldNetwork.sendDeployPan(p, startX, startY, startZ, startYaw, startPitch,
                endX, endY, endZ, endYaw, endPitch, PAN_DURATION_TICKS);
        BattlefieldNetwork.sendDeploy(p, false, DeployStatusDto.inactive());
    }

    /**
     * Advances every in-flight deploy pan's real entity position by one tick; called every
     * server tick, unthrottled. This remains the authoritative landing-spot driver — the
     * client-visible camera smoothness during the pan is handled independently and at a much
     * higher rate by {@code ClientDeployPan} (see {@code beginDeployPan}), so this method no
     * longer needs to run at a higher-than-tick rate to look smooth.
     */
    private void tickDeployPan() {
        if (deployPanState.isEmpty()) {
            return;
        }
        for (UUID id : new ArrayList<>(deployPanState.keySet())) {
            PanState pan = deployPanState.get(id);
            ServerPlayer p = player(id);
            if (p == null) {
                deployPanState.remove(id);
                continue;
            }
            long elapsed = server.getTickCount() - pan.startTick();
            if (elapsed >= PAN_DURATION_TICKS) {
                deployPanState.remove(id);
                finishDeploy(p, pan);
                continue;
            }
            float t = easeOutCubic(Mth.clamp(elapsed / (float) PAN_DURATION_TICKS, 0f, 1f));
            double x = Mth.lerp(t, pan.startX(), pan.endX());
            double y = Mth.lerp(t, pan.startY(), pan.endY());
            double z = Mth.lerp(t, pan.startZ(), pan.endZ());
            float yaw = pan.startYaw() + Mth.wrapDegrees(pan.endYaw() - pan.startYaw()) * t;
            float pitch = Mth.lerp(t, pan.startPitch(), pan.endPitch());
            p.connection.teleport(x, y, z, yaw, pitch);
            p.setDeltaMovement(0.0, 0.0, 0.0);
        }
    }

    private static float easeOutCubic(float t) {
        float inv = 1f - t;
        return 1f - inv * inv * inv;
    }

    /** Snaps the player onto the resolved spawn and runs the original post-teleport deploy logic. */
    private void finishDeploy(ServerPlayer p, PanState pan) {
        UUID id = p.getUUID();
        p.teleportTo(level, pan.endX(), pan.endY(), pan.endZ(), pan.endYaw(), pan.endPitch());
        p.setDeltaMovement(0.0, 0.0, 0.0);
        p.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 40, 0, false, false));
        if (pan.hasSpawn()) {
            BattlefieldNetwork.sendDeploySpawnFx(p, deployLocationLabel(pan.kind(), pan.targetId(), pan.faction()));
            BattlefieldFx.deployLanding(level, pan.endX(), pan.endY(), pan.endZ(), pan.faction());
        }
        clearRedeployState(p, false);
        escapeTicks.remove(id);
        downedUntil.remove(id);
        cancelRevive.accept(id);
        p.setPose(Pose.STANDING);
        ArcadeLoadoutBridge.apply(p);
        purgeInvalidOverrides(p, id);
        ArcadeLoadoutBridge.applyOverrides(p, loadoutOverrides.get(id));
        p.setHealth(p.getMaxHealth());
        p.getFoodData().setFoodLevel(20);
        lastHurtTick.remove(id);
        p.removeEffect(MobEffects.REGENERATION);
        protectedUntil.put(id, (long) server.getTickCount() + spawnProtectionTicks);
        p.sendSystemMessage(Component.literal("§a已部署，短暂无敌保护已启动。"));
        BattlefieldNetwork.sendDeploy(p, false, DeployStatusDto.inactive());
        BattlefieldNetwork.sendFireLock(p, fireLocked);
    }

    /**
     * P1-1 修复：落地这一刻用最新的 Arcade 解锁/职业快照重新校验本次对局会话覆盖，原地移除
     * 任何此刻已不再合法的槽位覆盖。{@code loadoutOverrides} 只在收到覆盖包那一刻校验过一次
     * （见 {@link #handleSlotOverride}），但覆盖状态跨越整场对局持续有效——玩家保存的 Arcade
     * 配装引用的物品后来被撤销解锁，或对局中途 Arcade 激活配装的职业发生变化，旧覆盖都可能
     * 变成越权物品/跨职业武器夹带。在 {@link ArcadeLoadoutBridge#applyOverrides} 真正把
     * ItemStack 写进背包之前拦住这个窗口。
     */
    private void purgeInvalidOverrides(ServerPlayer player, UUID id) {
        Map<Integer, String> ov = loadoutOverrides.get(id);
        if (ov == null || ov.isEmpty()) {
            return;
        }
        DeployLoadoutDto fresh = ArcadeLoadoutBridge.readDeployLoadout(player);
        ov.entrySet().removeIf(e -> !fresh.isValidOverride(e.getKey(), e.getValue()));
    }

    private String deployLocationLabel(String kind, String targetId, Faction f) {
        if ("point".equals(kind)) {
            for (ControlPointDef def : defs) {
                if (Integer.toString(def.pointId()).equals(targetId)) {
                    return def.name();
                }
            }
        }
        if ("squad".equals(kind)) {
            return "小队信标";
        }
        return f == Faction.ALPHA ? "ALPHA基地" : "BRAVO基地";
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
