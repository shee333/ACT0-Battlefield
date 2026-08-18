package org.shee33.act0.battlefield.match;

import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.GameType;
import org.shee33.act0.battlefield.bot.mc.BotGunBridge;
import org.shee33.act0.battlefield.bot.mc.BotSpawner;
import org.shee33.act0.battlefield.core.CapturePoint;
import org.shee33.act0.battlefield.core.Faction;
import org.shee33.act0.battlefield.core.OverheadViewMath;
import org.shee33.act0.battlefield.data.BattlefieldData;
import org.shee33.act0.battlefield.data.ControlPointDef;
import org.shee33.act0.battlefield.loadout.BattlefieldLoadoutService;
import org.shee33.act0.battlefield.network.BattlefieldNetwork;
import org.shee33.act0.battlefield.network.DeployAllyDto;
import org.shee33.act0.battlefield.network.DeployLoadoutDto;
import org.shee33.act0.battlefield.network.DeployPointDto;
import org.shee33.act0.battlefield.network.DeploySquadMateDto;
import org.shee33.act0.battlefield.network.DeployStatusDto;

import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    private static final Logger LOGGER = LogUtils.getLogger();

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
    /**
     * 本场对局取用的地图目录主键，由对局在构造时从<b>大厅世界</b>解析后传入。
     *
     * <p>不能在本类里现算：用地图模板开局时对局跑在临时创建的维度里，那个维度的每维度存档是空的，
     * 现算只会得到临时维度 ID，与管理员配置武器池时用的图名对不上，玩家将一件装备都拿不到。
     */
    private final String arenaKey;

    // --- State owned by RedeployService ---
    private final Map<UUID, Long> redeployReadyTick = new LinkedHashMap<>();
    private final Map<UUID, String> deploySelection = new LinkedHashMap<>();
    private final Map<UUID, String> deployTarget = new LinkedHashMap<>();
    private final Map<UUID, GameType> redeployOriginalMode = new LinkedHashMap<>();
    private final Map<UUID, Long> protectedUntil = new LinkedHashMap<>();
    private final Map<UUID, Integer> spectateTarget = new LinkedHashMap<>();
    private final Map<UUID, PanState> deployPanState = new LinkedHashMap<>();
    /**
     * 玩家 UUID → 上次成功处理 {@code DeploySlotOverridePacket} 的 tick（P1-2 修复）。每收到
     * 这个小包都要读一遍地图目录并回一个包，无节流会让恶意/异常客户端狂发小包造成主线程压力
     * 与回包带宽放大；参照 {@link ConquestMatch} 里 CALL_HELP 呼救冷却同款"记录上次处理 tick，
     * 间隔太短直接丢弃"写法。
     */
    private final Map<UUID, Long> lastSlotOverrideTick = new LinkedHashMap<>();
    /**
     * 因节流被丢弃过换装请求、需要补发一次快照的玩家。
     *
     * <p>丢包时<b>不能就地回包</b>：那正好把节流要挡的回包放大重新打开，狂发小包的客户端会
     * 收到等量的回复。但也不能什么都不做——真实玩家的一次快速双击同样会被丢掉，而客户端已经
     * 乐观翻转了显示，界面从此停在一个服务端根本没接受的选择上。折中是记下来，在既有的每秒
     * 部署刷新里补发一次：真人在 1 秒内看到纠正，恶意客户端最多每秒收到一个包。
     */
    private final Set<UUID> pendingLoadoutResync = new LinkedHashSet<>();
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
            String matchModeName,
            String arenaKey) {
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
        this.arenaKey = arenaKey;
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
                if (pendingLoadoutResync.remove(id)) {
                    BattlefieldNetwork.sendDeployLoadout(p, deployLoadoutFor(p));
                }
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
     * 部署界面底部武器更换面板提交的槽位选择（{@code DeploySlotOverridePacket}）。
     * 只在部署界面确实开着时受理，物品名必须在该槽位当前的地图目录可选列表内才会被接受——
     * 校验与落库都在 {@link BattlefieldLoadoutService#setPick} 里完成。
     *
     * <p>选择<b>按玩家×地图持久化</b>，不是本次对局的临时覆盖：玩家在这张图惯用的枪下次进来
     * 还在。不合法的提交被静默丢弃后仍回一个最新快照，让客户端的乐观更新回滚到真实状态。
     */
    public void handleSlotOverride(ServerPlayer player, int slotIndex, String itemName) {
        UUID id = player.getUUID();
        if (!redeployReadyTick.containsKey(id)) {
            return;
        }
        long now = server.getTickCount();
        if (isSlotOverrideThrottled(lastSlotOverrideTick.get(id), now)) {
            // 请求间隔太短(<100ms):就地丢弃,不回包——正常客户端点击换装面板的频率不会撞上这个
            // 门槛,只有异常/恶意客户端狂发这个C2S小包才会被限制住(P1-2修复)。补发推迟到下一次
            // 部署刷新,见 pendingLoadoutResync。
            pendingLoadoutResync.add(id);
            return;
        }
        lastSlotOverrideTick.put(id, now);
        BattlefieldLoadoutService.setPick(player, arenaKey, slotIndex, itemName);
        BattlefieldNetwork.sendDeployLoadout(player, deployLoadoutFor(player));
    }

    /**
     * 纯函数(P1-2修复):给定"上次处理这个包的tick"与"当前tick",判断这次请求是否应该被节流
     * 拒绝——间隔小于 {@link #MIN_SLOT_OVERRIDE_INTERVAL_TICKS}(100ms)时拒绝。不依赖
     * {@code ServerPlayer}/{@code MinecraftServer},可直接单测。
     */
    static boolean isSlotOverrideThrottled(@Nullable Long lastTick, long nowTick) {
        return lastTick != null && nowTick - lastTick < MIN_SLOT_OVERRIDE_INTERVAL_TICKS;
    }

    /** 本图目录 + 玩家存档解析出的配装快照，供部署界面展示。 */
    private DeployLoadoutDto deployLoadoutFor(ServerPlayer player) {
        return BattlefieldLoadoutService.readDeployLoadout(player, arenaKey);
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
        // 此前漏清这个map：玩家若在900ms部署过场镜头运镜期间退出对局(quitPlayer调用本方法)，
        // tickDeployPan()仍会按UUID查到人在线(server.getPlayerList().getPlayer(id)不管玩家
        // 是否已离开本场对局)，继续把已退出的玩家插值传送并最终finishDeploy()二次拉回对局
        // 世界。
        deployPanState.remove(id);
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
        lastSlotOverrideTick.clear();
        pendingLoadoutResync.clear();
    }

    /**
     * 玩家退出对局时调用：清除其换装节流记录，避免残留造成内存泄漏。
     *
     * <p>配装选择本身<b>不</b>清除——它按玩家×地图持久化在存档里，退出对局不该让人忘掉自己的选枪。
     */
    public void clearLoadoutOverride(UUID id) {
        lastSlotOverrideTick.remove(id);
        pendingLoadoutResync.remove(id);
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
        // 地图视图用并集区域,保证所有据点都落在投影范围内(见 BattlefieldData#mapViewArea)
        org.shee33.act0.battlefield.core.BattleArea area = data.mapViewArea();
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

    /**
     * 该玩家此刻能否部署到给定落点——与 {@link #handleDeployAction(ServerPlayer, String, String)}
     * 用的是同一份判定，供调用方在提交前自查。
     *
     * <p><b>AI 必须用它来填 {@code RedeployPolicy.Option#safe}。</b>AI 侧每 tick 重算落点并提交，
     * 而提交被拒时本类只是重发一次状态、不做任何提示；若 AI 用一份自己的近似判定（例如只看
     * "队友活着"而漏掉"队友身边 12 格内有敌人"这一条），它会每 tick 选中同一个必被拒的落点，
     * 从而永久卡在待部署。判定只能有一份，且必须是本类这一份。
     *
     * <p>入参归一化（{@code kind} 兜底、{@code targetId} 的 {@code null} 视作空串）与
     * {@code handleDeployAction} 完全一致，否则两边会在边角取值上悄悄漂移。
     */
    public boolean canDeployTo(ServerPlayer player, String kind, String targetId) {
        UUID id = player.getUUID();
        Faction faction = factionOf.get(id);
        if (faction == null) {
            return false;
        }
        return canDeployTo(id, faction, normalizeDeployKind(kind), targetId != null ? targetId : "");
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

    /**
     * 部署俯瞰相机姿态：正对下方、<b>朝北</b>。
     *
     * <p>yaw 必须是 {@link OverheadViewMath#NORTH_UP_YAW}，不能是 0。部署界面同时给玩家看两张
     * 地图——脚下的真实地形俯视画面，和叠加的 2D 缩略图面板；面板按"北上东右"投影，而 yaw=0 在
     * Minecraft 里是朝南，正俯视下屏幕上方成了南、右方成了西，两个轴同时相反，玩家看到的就是
     * 整张缩略图相对实际地形转了 180°。这是曾被连续报告三次的对不上问题的真正根因，
     * 由 {@code OverheadViewMathTest} 钉死。
     */
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
                return new BattlefieldData.BaseSpawn(fallback.x(), fallback.y() + 64.0, fallback.z(), OverheadViewMath.NORTH_UP_YAW, OverheadViewMath.STRAIGHT_DOWN_PITCH);
            }
            return new BattlefieldData.BaseSpawn(0.5, maxY + 64.0, 0.5, OverheadViewMath.NORTH_UP_YAW, OverheadViewMath.STRAIGHT_DOWN_PITCH);
        }
        double cx = (minX + maxX) * 0.5;
        double cz = (minZ + maxZ) * 0.5;
        double span = Math.max(maxX - minX, maxZ - minZ);
        double height = Math.max(48.0, Math.min(140.0, span * 0.65 + 32.0));
        return new BattlefieldData.BaseSpawn(cx + 0.5, maxY + height, cz + 0.5, OverheadViewMath.NORTH_UP_YAW, OverheadViewMath.STRAIGHT_DOWN_PITCH);
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
            ConnectionSafeTeleport.teleport(p, x, y, z, yaw, pitch);
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
        // AI 士兵落地即算新的一条命：不复位撤退迟滞，上一条命的残血状态会跟着新生命走，
        // 满血落地的 bot 会先莫名后退几秒。传入真人 UUID 无副作用。
        org.shee33.act0.battlefield.bot.mc.BotManager.INSTANCE.onRespawn(id);
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
        BattlefieldLoadoutService.apply(p, arenaKey);
        drawIssuedGunForBot(p);
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
     * 替 AI 士兵完成 TaCZ 的"持枪就绪"，并在配装没发到枪时把问题喊出来。
     *
     * <p><b>真人不需要、也不能走这一步。</b>TaCZ 的服务端 {@code draw} 由客户端在手持物变化时
     * 发包触发；bot 没有客户端，这一跳在它身上永远不会发生，于是刚发到手的枪从未被"抽出"过——
     * 不仅开火要靠失败重试兜底，其他玩家看到的第三人称持枪模型与改装属性缓存也停在旧枪上。
     * 配装刚发完正是客户端本会发包的那个时刻，由服务端在此代劳，语义与真人完全对齐。
     *
     * <p>失败即告警：bot 空手落地是配置问题（武器库里没有任何可发的枪），不是运行时抖动，
     * 沉默只会让它以"bot 一枪不发"的形态出现在对局里，而那是最难反查的一类症状。
     */
    private void drawIssuedGunForBot(ServerPlayer p) {
        if (!BotSpawner.isBot(p)) {
            return;
        }
        if (!BotGunBridge.drawMainHand(p)) {
            LOGGER.warn("[ACT0] bot {} 部署后主手没有 TaCZ 枪械，本次配装未发到武器，它将无法开火",
                    p.getGameProfile().getName());
        }
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
