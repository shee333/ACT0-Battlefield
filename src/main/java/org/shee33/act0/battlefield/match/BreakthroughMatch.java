package org.shee33.act0.battlefield.match;

import net.minecraft.ChatFormatting;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;
import org.shee33.act0.battlefield.BattlefieldConfig;
import org.shee33.act0.battlefield.core.BreakthroughRules;
import org.shee33.act0.battlefield.core.CapturePoint;
import org.shee33.act0.battlefield.core.ConquestRules;
import org.shee33.act0.battlefield.core.Faction;
import org.shee33.act0.battlefield.core.SoldierClass;
import org.shee33.act0.battlefield.core.FactionNames;
import org.shee33.act0.battlefield.network.SquadRosterDto;
import org.shee33.act0.battlefield.network.SquadActionPacket;
import org.shee33.act0.battlefield.core.SquadJoinRules;
import org.shee33.act0.battlefield.reg.BattlefieldRegistry;
import org.shee33.act0.battlefield.network.DeployableDto;
import org.shee33.act0.battlefield.deployable.DeployableService;
import org.shee33.act0.battlefield.deployable.DeployableKind;
import org.shee33.act0.battlefield.core.SupplyRules;
import org.shee33.act0.battlefield.core.Sector;
import org.shee33.act0.battlefield.data.BattlefieldData;
import org.shee33.act0.battlefield.loadout.BattlefieldLoadoutService;
import org.shee33.act0.battlefield.data.ControlPointDef;
import org.shee33.act0.battlefield.data.ArenaKey;
import org.shee33.act0.battlefield.network.BattleResultDto;
import org.shee33.act0.battlefield.network.BattlefieldNetwork;
import org.shee33.act0.battlefield.network.BreakthroughHudDto;
import org.shee33.act0.battlefield.network.BreakthroughPointDto;
import org.shee33.act0.battlefield.network.CapturePointEventPacket;
import org.shee33.act0.battlefield.network.DownedActionPacket;
import org.shee33.act0.battlefield.network.SquadMateHudDto;
import org.shee33.act0.battlefield.network.TabEntryDto;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 一场突破对局：攻击方（ALPHA）按扇区推进占领，防守方（BRAVO）严防死守。
 *
 * <p>每 {@link #captureInterval} 刻结算占点：统计各据点区域内双方人数，按扇区顺序
 * 解锁推进。攻击方票数归零判负；攻占全部扇区判胜。死亡接管与 ConquestMatch 一致。
 */
public final class BreakthroughMatch {

    /** 小队完成攻击命令（占领指定据点）时，进攻方获得的票数奖励。
     * 与 ConquestMatch 的 +5 票奖励保持同一数值：两模式默认起始票数（300）与每死亡扣票（1.0）
     * 完全相同，因此奖励在两模式中的相对权重（约 5 次阵亡的容错量）也一致，无需按比例调整。 */
    private static final int ATTACK_ORDER_TICKET_REWARD = 5;

    // IFF (敌我识别) 空间分区常量：与 ConquestMatch 完全一致，保持相同的判定平衡性。
    private static final int IFF_CHUNK_SIZE = 16;
    private static final int IFF_CHUNK_RADIUS = 6;

    private final int captureInterval;
    private final double captureDelta;
    private final int hudInterval;
    private final int squadSize;
    private final int redeployDelayTicks;
    private final int spawnProtectionTicks;
    private final double squadDeployEnemyBlockRadius;
    private final int breathHealDelayTicks;
    private final int escapeBoundaryTicks;
    private final int downedDurationTicks;
    private final int reviveDurationTicks;
    private final int iffSyncInterval;
    private final double enemyMarkDistance;
    private final double enemyMarkDistanceSqr;
    private final double enemyMarkViewDot;

    private final MinecraftServer server;
    private final ServerLevel level;
    private final ServerLevel lobbyLevel;
    private final BreakthroughRules rules;
    private final BattlefieldData data;
    private final List<ControlPointDef> defs;
    private final List<CapturePoint> points;
    private final List<Sector> sectors;
    /** 据点 ID → 所属区域索引，供 {@link #buildHudFor} 填充 {@link BreakthroughPointDto#sectorIndex()}
     * 与 {@link #focusFor} 判定"本地玩家站立的目标点是否属于当前激活区域"，一次性从 {@link #sectors}
     * 构建（不修改 {@code Sector} 本身，仅在此处做只读反查）。 */
    private final Map<Integer, Integer> pointSectorIndex = new LinkedHashMap<>();
    /** 战术标记允许的最远距离（格）。 */
    private static final double PING_MAX_RANGE = 256.0;

    private final Map<UUID, Faction> factionOf = new LinkedHashMap<>();
    private final SquadManager squadManager;
    private final KillTracker killTracker;
    private final RedeployService redeployService;

    /** 本图的军械库键，用于解析每名玩家生效的兵种。 */
    private final String arenaKey;
    private final ConquestRules captureRules;
    private final List<PlayerTeam> nameTagTeams = new ArrayList<>();

    private final Map<UUID, Long> downedUntil = new LinkedHashMap<>();
    /** 倒地玩家反作弊 Y 坐标基准：玩家 UUID → 上一次校验通过（或被强制拉回后）的 Y 坐标。
     * MC 的玩家位置同步是"客户端上报、服务端信任范围内接受"（见 {@code
     * ServerGamePacketListenerImpl.handleMovePlayer}：最终位置来自 {@code
     * absMoveTo(clampVertical(packet.getY(...)), ...)}，与服务端 {@code deltaMovement} 无关），
     * 客户端侧拦截跳跃键才是真正阻止倒地玩家起跳的手段（见 {@code BattlefieldClientInput}）。
     * 这里是给失效/被绕过的客户端拦截兜底的服务端反作弊：每 tick 与此基准比较，单 tick 上升
     * 超过阈值即视为异常起跳并强制拉回，见 {@link #tickDownedPlayers()}。 */
    private final Map<UUID, Double> downedLastGoodY = new LinkedHashMap<>();
    /** 倒地玩家单 tick 允许的最大 Y 上升量：明显高于地形噪声/爬行时的台阶误差，
     * 但远低于一次正常起跳产生的位移（约 0.4 格/首 tick），足以拦截"跳跃绕过"同时容忍误差。 */
    private static final double DOWNED_MAX_Y_RISE_PER_TICK = 0.2D;
    /** 倒地期间缓存的完整物品栏（含盔甲/副手），救援成功归还，转入重生时必须移除以免内存泄漏。 */
    private final Map<UUID, NonNullList<ItemStack>> downedInventoryCache = new LinkedHashMap<>();
    private final Map<UUID, Long> lastHurtTick = new LinkedHashMap<>();
    private final Map<UUID, UUID> revivingTarget = new LinkedHashMap<>();
    private final Map<UUID, Long> revivingStarted = new LinkedHashMap<>();
    /** reviverId → 收到的最近一次客户端救援心跳所在 tick，见 {@link #handleReviveHeartbeat}。 */
    private final Map<UUID, Long> revivingHeartbeat = new LinkedHashMap<>();
    /** reviverId → 本次救援所需总 tick 数：完成判定与 HUD 进度条必须共用同一个值，否则
     * 医疗针加速后进度条会先走满而服务端还没救完（或反之）。 */
    private final Map<UUID, Integer> revivingDuration = new LinkedHashMap<>();
    private final DeployableService deployables = new DeployableService();
    private boolean deployablesWereSent = false;
    private final Map<UUID, Integer> lastRosterHash = new LinkedHashMap<>();
    /** 救援朝向判定阈值：比 IFF 远距离标敌（{@link #enemyMarkViewDot}）更宽松，救援本就要求近距离
     * （≤4 格），只需大致朝向目标即可，不必是精确瞄准。 */
    private static final double REVIVE_VIEW_DOT = 0.5D;
    /** 救援心跳容忍窗口（tick）：超过这个时长没收到新心跳视为按键松开/掉线，避免网络抖动误取消。 */
    private static final int REVIVE_HEARTBEAT_TIMEOUT_TICKS = 10;
    private final Map<UUID, PendingDeath> pendingDeaths = new LinkedHashMap<>();
    private final Map<UUID, Integer> escapeTicks = new LinkedHashMap<>();
    private final Map<Integer, CapturePoint.CaptureStatus> lastCaptureStatus = new LinkedHashMap<>();
    private final Map<UUID, Set<UUID>> visibleEnemyGlows = new LinkedHashMap<>();
    private final Map<Integer, Long> defendNotificationCooldown = new LinkedHashMap<>();
    private final Map<UUID, Long> callHelpCooldownUntil = new LinkedHashMap<>();
    private static final int CALL_HELP_COOLDOWN_TICKS = 60;
    private final Map<UUID, Integer> lastHudHash = new LinkedHashMap<>();

    private double attackerTickets;
    private int currentSectorIndex;
    private int startCountdownTicks;
    private int startCountdownLastSecond = -1;
    private long startedTick;
    private boolean ended;
    @Nullable
    private Faction winner;
    private int captureAccum;
    private int hudAccum;
    private int iffAccum;
    /** 某一阵营人数清零起算的tick，-1表示当前非空。超过{@link #EMPTY_FACTION_TIMEOUT_TICKS}
     * 未恢复则判另一方获胜——此前只有两阵营都清空才会结束对局，进攻方(ALPHA)全体退出后防守方
     * (BRAVO)没有票池可扣、无人推进区域，对局会永久挂起。 */
    private long alphaEmptySinceTick = -1L;
    private long bravoEmptySinceTick = -1L;
    private static final long EMPTY_FACTION_TIMEOUT_TICKS = 60 * 20;

    public BreakthroughMatch(ServerLevel level, ServerLevel lobbyLevel, BreakthroughRules rules,
                             List<ControlPointDef> defs, Map<UUID, Faction> roster,
                             BattlefieldData data) {
        this.server = level.getServer();
        this.level = level;
        this.lobbyLevel = lobbyLevel;
        this.rules = rules;
        this.data = data;
        this.captureInterval = BattlefieldConfig.CAPTURE_INTERVAL.get();
        this.captureDelta = this.captureInterval / 20.0;
        this.hudInterval = BattlefieldConfig.HUD_INTERVAL.get();
        this.squadSize = Math.min(SquadManager.MAX_SQUAD_SIZE, BattlefieldConfig.SQUAD_SIZE.get());
        this.redeployDelayTicks = BattlefieldConfig.REDEPLOY_DELAY_TICKS.get();
        this.spawnProtectionTicks = BattlefieldConfig.SPAWN_PROTECTION_TICKS.get();
        this.squadDeployEnemyBlockRadius = BattlefieldConfig.SQUAD_DEPLOY_ENEMY_BLOCK_RADIUS.get();
        this.breathHealDelayTicks = BattlefieldConfig.BREATH_HEAL_DELAY_TICKS.get();
        this.escapeBoundaryTicks = BattlefieldConfig.ESCAPE_BOUNDARY_TICKS.get();
        this.downedDurationTicks = BattlefieldConfig.DOWNED_DURATION_TICKS.get();
        this.reviveDurationTicks = BattlefieldConfig.REVIVE_DURATION_TICKS.get();
        this.iffSyncInterval = BattlefieldConfig.IFF_SYNC_INTERVAL.get();
        this.enemyMarkDistance = BattlefieldConfig.ENEMY_MARK_DISTANCE.get();
        this.enemyMarkDistanceSqr = this.enemyMarkDistance * this.enemyMarkDistance;
        this.enemyMarkViewDot = BattlefieldConfig.ENEMY_MARK_VIEW_DOT.get();
        this.attackerTickets = rules.startingTickets();
        this.currentSectorIndex = 0;
        this.defs = new ArrayList<>(defs);
        this.points = new ArrayList<>(defs.size());
        for (ControlPointDef def : this.defs) {
            this.points.add(new CapturePoint(def.pointId(), def.name()));
        }
        this.sectors = data.sectors();
        for (Sector sector : this.sectors) {
            for (int pid : sector.pointIds()) {
                pointSectorIndex.put(pid, sector.id());
            }
        }
        this.factionOf.putAll(roster);
        this.killTracker = new KillTracker(this.factionOf, this.server, this.points, this.defs);
        for (UUID id : roster.keySet()) {
            killTracker.initPlayer(id);
        }
        this.squadManager = new SquadManager(squadSize, factionOf);
        squadManager.buildSquads();
        squadManager.initDeployContext(this::player, level, downedUntil, squadDeployEnemyBlockRadius);
        this.arenaKey = ArenaKey.of(lobbyLevel);
        this.redeployService = new RedeployService(level, data, factionOf, squadManager, points, defs,
                downedUntil, escapeTicks, lastHurtTick, this::cancelRevive,
                spawnProtectionTicks, redeployDelayTicks, "突破模式", arenaKey);
        this.captureRules = ConquestRules.builder()
                .startingTickets(1)
                .captureSeconds(rules.captureSeconds())
                .maxCaptureBoost(rules.maxCaptureBoost())
                .bleedPerPointPerSecond(0)
                .ticketPerDeath(0)
                .build();
    }

    // ---- 开局 ----

    public void begin() {
        startCountdownTicks = BattlefieldConfig.START_COUNTDOWN_TICKS.get();
        startCountdownLastSecond = -1;
        setupNameTagTeams();
        for (Map.Entry<UUID, Faction> e : factionOf.entrySet()) {
            ServerPlayer p = player(e.getKey());
            if (p != null) {
                deploy(p, e.getValue());
                BattlefieldNetwork.sendFireLock(p, true);
                p.sendSystemMessage(Component.literal("§6突破模式即将开始！你属于 " + coloredFaction(e.getValue())
                        + "§6。" + (e.getValue() == Faction.ALPHA ? "§c进攻方，票数有限！" : "§a防守方，守住防线！")));
            }
        }
        showTitle("§e准备", "§7突破模式将在 5 秒后开始", 5, 30, 8);
    }

    public boolean addLatecomer(ServerPlayer player, Faction faction) {
        UUID id = player.getUUID();
        if (ended || faction == null || factionOf.containsKey(id)) {
            return false;
        }
        factionOf.put(id, faction);
        killTracker.initPlayer(id);
        RelativeTeamSync.reset(id); // 强制下次同步进行全量重建
        squadManager.assignLatecomer(id, faction);
        setupNameTagTeams();
        if (startCountdownTicks > 0) {
            // 开局倒计时期间加入：直接部署到基地等待倒计时结束，不走beginRedeploy()的"重生
            // 选点"观战流程——那是为死亡玩家设计的语义，此前误用在"第一次加入"上会导致中途
            // 加入的玩家卡在观战模式，需要自己手动选点才能真正进场(与ConquestMatch同款修复)。
            deploy(player, faction);
            BattlefieldNetwork.sendFireLock(player, true);
        } else {
            beginRedeploy(player, faction);
        }
        broadcast("§b" + player.getGameProfile().getName() + " §7加入了 " + coloredFaction(faction) + "§7。");
        return true;
    }

    public boolean quitPlayer(ServerPlayer player) {
        UUID id = player.getUUID();
        Faction faction = factionOf.remove(id);
        if (faction == null) {
            return false;
        }
        clearEnemyGlowFor(player);
        clearEnemyGlowTarget(player);
        clearRelativeTeamsFor(player);
        clearRedeployState(player, true);
        BattlefieldData.BaseSpawn base = data.base(faction);
        if (base != null) {
            player.teleportTo(lobbyLevel, base.x(), base.y(), base.z(), base.yaw(), base.pitch());
        }
        player.getInventory().clearContent();
        BattlefieldNetwork.clearHud(player);
        BattlefieldNetwork.sendFireLock(player, false);
        killTracker.removePlayer(id);
        redeployService.removeProtection(id);
        redeployService.clearLoadoutOverride(id);
        lastHurtTick.remove(id);
        escapeTicks.remove(id);
        downedUntil.remove(id);
        downedLastGoodY.remove(id);
        downedInventoryCache.remove(id);
        pendingDeaths.remove(id);
        cancelRevive(id);
        lastHudHash.remove(id);
        callHelpCooldownUntil.remove(id);
        squadManager.removeMember(id);
        setupNameTagTeams();
        broadcast("§e" + player.getGameProfile().getName() + " §7退出了本对局。");
        player.sendSystemMessage(Component.literal("§7已退出大战场。"));
        if (factionOf.isEmpty()) {
            ended = true;
            clearNameTagTeams();
        }
        return true;
    }

    /** 单阵营人数清零超时判负：见{@link #alphaEmptySinceTick}字段注释。 */
    private void checkEmptyFactionTimeout() {
        long now = server.getTickCount();
        boolean alphaEmpty = factionOf.values().stream().noneMatch(f -> f == Faction.ALPHA);
        boolean bravoEmpty = factionOf.values().stream().noneMatch(f -> f == Faction.BRAVO);
        if (alphaEmpty && !bravoEmpty) {
            if (alphaEmptySinceTick < 0) {
                alphaEmptySinceTick = now;
            } else if (now - alphaEmptySinceTick >= EMPTY_FACTION_TIMEOUT_TICKS) {
                end(Faction.BRAVO);
                return;
            }
        } else {
            alphaEmptySinceTick = -1L;
        }
        if (bravoEmpty && !alphaEmpty) {
            if (bravoEmptySinceTick < 0) {
                bravoEmptySinceTick = now;
            } else if (now - bravoEmptySinceTick >= EMPTY_FACTION_TIMEOUT_TICKS) {
                end(Faction.ALPHA);
            }
        } else {
            bravoEmptySinceTick = -1L;
        }
    }

    // ---- 每刻 ----

    public void tick() {
        if (ended) {
            return;
        }
        checkEmptyFactionTimeout();
        if (ended) {
            return;
        }
        if (startCountdownTicks > 0) {
            tickStartCountdown();
            if (++hudAccum >= hudInterval) {
                hudAccum = 0;
                broadcastHud();
            }
            return;
        }
        if (++captureAccum >= captureInterval) {
            captureAccum = 0;
            resolveCapture();
            // resolveCapture() 可能通过 checkSectorAdvance() 调用 end()（内部已做全部清理，包括
            // clearAllRelativeTeams()/clearNameTagTeams()）。一旦 ended=true，必须立即停止本次
            // tick 剩余逻辑，否则 syncEnemyIdentification() 会重新调用 RelativeTeamSync.sync()，
            // 把刚清理掉的虚拟队伍原样重建，而对局已结束、下一 tick 就会被摘除，永远没有机会再清理。
            if (ended) {
                return;
            }
        }
        processRedeployTick();
        squadManager.tick(server.getTickCount());
        if (++iffAccum >= iffSyncInterval) {
            iffAccum = 0;
            syncEnemyIdentification();
        }
        tickBreathHealing();
        tickEscapeBoundary();
        tickDownedPlayers();
        tickRevives();
        deployables.tick(level, server.getTickCount(), this::sameFaction, downedUntil::containsKey);
        if (ended) {
            return;
        }
        if (++hudAccum >= hudInterval) {
            hudAccum = 0;
            broadcastHud();
        }
    }

    // ---- HUD ----

    private void broadcastHud() {
        broadcastDeployables();
        broadcastSquadRosters();
        for (UUID id : factionOf.keySet()) {
            ServerPlayer p = player(id);
            if (p == null) {
                continue;
            }

            BreakthroughHudDto hud = buildHudFor(p);
            int hudHash = Objects.hash(
                    hud.attackerTickets(), hud.maxTickets(), hud.currentSectorId(), hud.totalSectors(),
                    hud.points().hashCode(), hud.squad().hashCode(), hud.phase(), hud.winner());
            Integer prevHudHash = lastHudHash.get(id);
            if (prevHudHash == null || prevHudHash != hudHash) {
                BattlefieldNetwork.sendBreakthroughHud(p, hud);
                lastHudHash.put(id, hudHash);
            }
        }
    }

    private void tickStartCountdown() {
        int secs = Math.max(0, (int) Math.ceil(startCountdownTicks / 20.0));
        if (secs != startCountdownLastSecond) {
            startCountdownLastSecond = secs;
            if (secs > 0) {
                showTitle("§e§l" + secs, "§7准备突破", 0, 16, 4);
                playToAll(SoundEvents.NOTE_BLOCK_HAT.value(), 1.0f + (5 - secs) * 0.12f);
            }
        }
        startCountdownTicks--;
        if (startCountdownTicks <= 0) {
            startCountdownTicks = 0;
            startedTick = server.getTickCount();
            sendFireLockToAll(false);
            sendMatchStartFxToAll();
            showTitle("§a§l突破开始", "§7进攻方推进！防守方守住阵地！", 2, 24, 8);
            playToAll(SoundEvents.PLAYER_LEVELUP, 1.0f);
            broadcast("§a突破模式正式开始！");
        }
    }

    private void resolveCapture() {
        if (currentSectorIndex >= sectors.size()) {
            return;
        }
        for (int i = 0; i < points.size(); i++) {
            ControlPointDef def = defs.get(i);
            int pointId = def.pointId();
            if (!isPointActive(pointId)) {
                continue;
            }
            AABB zone = def.zone();
            int alpha = 0;
            int bravo = 0;
            for (Map.Entry<UUID, Faction> e : factionOf.entrySet()) {
                ServerPlayer p = player(e.getKey());
                if (p == null || p.level() != level || !p.isAlive() || p.isSpectator()
                        || downedUntil.containsKey(e.getKey())) {
                    continue;
                }
                if (zone.contains(p.getX(), p.getY(), p.getZ())) {
                    if (e.getValue() == Faction.ALPHA) {
                        alpha++;
                    } else {
                        bravo++;
                    }
                }
            }
            CapturePoint.CaptureStatus prevStatus = lastCaptureStatus.getOrDefault(
                    pointId, CapturePoint.CaptureStatus.IDLE);
            boolean wasActiveContest = prevStatus == CapturePoint.CaptureStatus.CONTESTED
                    || prevStatus == CapturePoint.CaptureStatus.CAPTURING
                    || prevStatus == CapturePoint.CaptureStatus.NEUTRALIZED;
            CapturePoint.CaptureStatus st = points.get(i).tick(alpha, bravo, captureRules, captureDelta);
            lastCaptureStatus.put(pointId, st);
            // 突破模式单向推进，无需 LOST/CAPTURED_RECOVERED 语义：只发 STARTED（首次进入争夺/推进）
            // 与 CAPTURED_NEW（首次占领确认），驱动 HUD 顶部横幅一次性反馈。
            if (st == CapturePoint.CaptureStatus.CAPTURED) {
                Faction owner = points.get(i).owner();
                if (owner != null) {
                    sendCapturePointEvent(pointId, CapturePointEventPacket.Kind.CAPTURED_NEW, factionCode(owner));
                    rewardAttackOrder(pointId, owner);
                    Vec3 fxPos = zone.getCenter();
                    BattlefieldFx.captureBurst(level, fxPos.x, fxPos.y, fxPos.z, owner);
                }
            } else if (st == CapturePoint.CaptureStatus.NEUTRALIZED) {
                clearDefendOrder(pointId);
                Vec3 fxPos = zone.getCenter();
                BattlefieldFx.lost(level, fxPos.x, fxPos.y, fxPos.z);
            } else if (st == CapturePoint.CaptureStatus.CONTESTED) {
                notifyDefendOrder(points.get(i), pointId);
                if (!wasActiveContest) {
                    sendCapturePointEvent(pointId, CapturePointEventPacket.Kind.STARTED, 0);
                    Vec3 fxPos = zone.getCenter();
                    BattlefieldFx.contestStart(level, fxPos.x, fxPos.y, fxPos.z);
                }
            } else if (st == CapturePoint.CaptureStatus.CAPTURING) {
                if (!wasActiveContest) {
                    Faction pushing = alpha > 0 ? Faction.ALPHA : Faction.BRAVO;
                    sendCapturePointEvent(pointId, CapturePointEventPacket.Kind.STARTED, factionCode(pushing));
                    Vec3 fxPos = zone.getCenter();
                    BattlefieldFx.contestStart(level, fxPos.x, fxPos.y, fxPos.z);
                }
            }
        }
        checkSectorAdvance();
    }

    private boolean isPointActive(int pointId) {
        for (int i = 0; i <= currentSectorIndex && i < sectors.size(); i++) {
            if (sectors.get(i).containsPoint(pointId)) {
                return true;
            }
        }
        return false;
    }

    private void checkSectorAdvance() {
        if (currentSectorIndex >= sectors.size()) {
            end(Faction.ALPHA);
            return;
        }
        Sector current = sectors.get(currentSectorIndex);
        boolean allCaptured = true;
        for (int ptId : current.pointIds()) {
            CapturePoint cp = pointById(ptId);
            if (cp == null || cp.owner() != Faction.ALPHA) {
                allCaptured = false;
                break;
            }
        }
        if (allCaptured) {
            currentSectorIndex++;
            attackerTickets += rules.ticketsPerSector();
            broadcast("§a§l" + coloredFaction(Faction.ALPHA) + " §a占领了区域 §e" + current.displayName()
                    + "§a！+" + rules.ticketsPerSector() + " 票");
            playToAll(SoundEvents.PLAYER_LEVELUP, 1.0f);
            // 客户端靠"currentSectorId 边沿变化"推断区域突破序列（占点 HUD 动效规格文档 §3.3/§3.4）；
            // 若这是最后一个区域，下面 end() 会立刻清空 HUD（show=false），使这个终值永远送不到客户端。
            // 这里在清空前强制补发一次快照（复用既有 broadcastHud，不新增网络包），
            // 保证客户端一定能观察到 currentSectorId 推进到终值，从而正常触发终局演出。
            broadcastHud();
            if (currentSectorIndex >= sectors.size()) {
                end(Faction.ALPHA);
            }
        }
    }

    @Nullable
    private CapturePoint pointById(int pointId) {
        for (int i = 0; i < defs.size(); i++) {
            if (defs.get(i).pointId() == pointId) {
                return points.get(i);
            }
        }
        return null;
    }

    /**
     * 向双方阵营的每个玩家单独下发一次据点状态边沿事件（HUD 顶部横幅一次性反馈），
     * 与 {@link #broadcast(String)} 的全局聊天广播并列、不替代。
     */
    private void sendCapturePointEvent(int pointId, CapturePointEventPacket.Kind kind, int factionCode) {
        for (UUID id : factionOf.keySet()) {
            ServerPlayer p = player(id);
            if (p != null) {
                BattlefieldNetwork.sendCapturePointEvent(p, pointId, kind, factionCode);
            }
        }
    }

    // ---- 小队攻防指令 ----

    /** 小队长下达攻击/防御命令；返回 {@code null} 表示成功，否则为失败原因。 */
    @Nullable
    public String setSquadOrder(UUID playerId, int pointId, boolean attack) {
        Integer squadId = squadManager.getSquadOf().get(playerId);
        if (squadId == null) {
            return "§c未找到你的小队。";
        }
        if (!squadManager.isSquadLeader(playerId)) {
            return "§c只有小队长可以下达命令。";
        }
        if (!isPointActive(pointId)) {
            return "§c据点 " + pointId + " 当前不可指挥（未激活或不在当前突破区域内）。";
        }
        squadManager.setOrder(squadId, new SquadManager.SquadOrder(pointId, attack));
        return null;
    }

    /**
     * 小队完成攻击命令（占领了下令的据点）时发放奖励。
     *
     * <p>与 ConquestMatch 不同：突破模式的票数池（{@link #attackerTickets}）只属于进攻方（ALPHA），
     * 防守方没有对称的票数池可供奖励，因此仅在 ALPHA 完成攻击命令时生效；
     * 防守方的"防御命令"只在 {@link #notifyDefendOrder} 中获得战术提示，不发放票数奖励
     * ——这与 ConquestMatch 本身的行为一致（ConquestMatch 同样只在 rewardAttackOrder 中发票，
     * 防御命令只在 clearDefendOrder 中被清除，从未获得过奖励）。
     */
    private void rewardAttackOrder(int pointId, Faction capturer) {
        if (capturer != Faction.ALPHA) {
            return;
        }
        for (Map.Entry<Integer, SquadManager.SquadOrder> e : squadManager.getActiveOrders().entrySet()) {
            SquadManager.SquadOrder order = e.getValue();
            if (order.attack() && order.pointId() == pointId) {
                LinkedHashSet<UUID> members = squadManager.getSquads().get(e.getKey());
                if (members != null && !members.isEmpty()) {
                    UUID first = members.iterator().next();
                    if (factionOf.get(first) == capturer) {
                        squadBroadcast(e.getKey(), "§6★ 小队完成了攻击命令！据点已占领。");
                        attackerTickets += ATTACK_ORDER_TICKET_REWARD;
                        squadBroadcast(e.getKey(), "§a" + coloredFaction(capturer)
                                + " §7获得 +" + ATTACK_ORDER_TICKET_REWARD + " 票数奖励。");
                        squadManager.clearOrder(e.getKey());
                    }
                }
            }
        }
    }

    private void clearDefendOrder(int pointId) {
        for (Map.Entry<Integer, SquadManager.SquadOrder> e : squadManager.getActiveOrders().entrySet()) {
            SquadManager.SquadOrder order = e.getValue();
            if (!order.attack() && order.pointId() == pointId) {
                squadManager.clearOrder(e.getKey());
                squadBroadcast(e.getKey(), "§7据点已被中立化，防御命令已取消。");
            }
        }
    }

    private void notifyDefendOrder(CapturePoint point, int pointId) {
        Faction owner = point.owner();
        if (owner == null) return;
        long now = server.getTickCount();
        for (Map.Entry<Integer, SquadManager.SquadOrder> e : squadManager.getActiveOrders().entrySet()) {
            SquadManager.SquadOrder order = e.getValue();
            if (order.attack() || order.pointId() != pointId) continue;
            LinkedHashSet<UUID> members = squadManager.getSquads().get(e.getKey());
            if (members == null || members.isEmpty()) continue;
            UUID first = members.iterator().next();
            if (factionOf.get(first) != owner) continue;
            Long last = defendNotificationCooldown.get(e.getKey());
            if (last != null && now - last < 600L) continue;
            defendNotificationCooldown.put(e.getKey(), now);
            squadBroadcast(e.getKey(), "§e▣ 正在防守据点！保住这个位置。");
        }
    }

    private void squadBroadcast(int squadId, String msg) {
        LinkedHashSet<UUID> members = squadManager.getSquads().get(squadId);
        if (members == null) return;
        Component component = Component.literal(msg);
        for (UUID uid : members) {
            ServerPlayer p = player(uid);
            if (p != null) p.sendSystemMessage(component);
        }
    }

    // ---- 敌我识别（阵营名牌颜色） ----

    private void setupNameTagTeams() {
        clearNameTagTeams();
        Scoreboard scoreboard = server.getScoreboard();
        PlayerTeam alpha = createNameTagTeam(scoreboard, Faction.ALPHA);
        PlayerTeam bravo = createNameTagTeam(scoreboard, Faction.BRAVO);
        for (Map.Entry<UUID, Faction> e : factionOf.entrySet()) {
            ServerPlayer p = player(e.getKey());
            if (p == null) {
                continue;
            }
            scoreboard.addPlayerToTeam(p.getScoreboardName(), e.getValue() == Faction.ALPHA ? alpha : bravo);
        }
    }

    private PlayerTeam createNameTagTeam(Scoreboard scoreboard, Faction faction) {
        String teamName = scoreboardTeamName(faction);
        PlayerTeam existing = scoreboard.getPlayerTeam(teamName);
        if (existing != null) {
            scoreboard.removePlayerTeam(existing);
        }
        PlayerTeam team = scoreboard.addPlayerTeam(teamName);
        team.setNameTagVisibility(Team.Visibility.HIDE_FOR_OTHER_TEAMS);
        team.setColor(faction == Faction.ALPHA ? ChatFormatting.BLUE : ChatFormatting.RED);
        nameTagTeams.add(team);
        return team;
    }

    private void clearNameTagTeams() {
        if (nameTagTeams.isEmpty()) {
            return;
        }
        Scoreboard scoreboard = server.getScoreboard();
        for (PlayerTeam team : new ArrayList<>(nameTagTeams)) {
            PlayerTeam live = scoreboard.getPlayerTeam(team.getName());
            if (live != null) {
                scoreboard.removePlayerTeam(live);
            }
        }
        nameTagTeams.clear();
    }

    private String scoreboardTeamName(Faction faction) {
        String suffix = faction == Faction.ALPHA ? "a" : "b";
        String base = "bt" + Integer.toHexString(System.identityHashCode(this)) + suffix;
        return base.length() <= 16 ? base : base.substring(0, 16);
    }

    /**
     * IFF (敌我识别) 同步：管理每名玩家视角下的敌方发光可见性。
     *
     * <p>与 ConquestMatch 完全等价的实现：使用 16 格区块空间索引降低 O(n²) 射线检测开销，
     * 判定阈值（距离、视锥点积）与射线遮挡算法均保持一致，不调整任何平衡数值。
     */
    private void syncEnemyIdentification() {
        Map<IffChunkKey, List<UUID>> spatialIndex = buildIffSpatialIndex();

        for (UUID viewerId : new ArrayList<>(factionOf.keySet())) {
            ServerPlayer viewer = player(viewerId);
            if (!canViewerIdentify(viewer)) {
                clearEnemyGlowFor(viewerId);
                continue;
            }
            syncRelativeTeams(viewer, viewerId);
            Set<UUID> active = visibleEnemyGlows.computeIfAbsent(viewerId, ignored -> new HashSet<>());
            Set<UUID> shouldKeep = new HashSet<>();

            IffChunkKey viewerChunk = iffChunkKey(viewer);

            int minCx = viewerChunk.cx() - IFF_CHUNK_RADIUS;
            int maxCx = viewerChunk.cx() + IFF_CHUNK_RADIUS;
            int minCz = viewerChunk.cz() - IFF_CHUNK_RADIUS;
            int maxCz = viewerChunk.cz() + IFF_CHUNK_RADIUS;
            for (int cx = minCx; cx <= maxCx; cx++) {
                for (int cz = minCz; cz <= maxCz; cz++) {
                    List<UUID> chunk = spatialIndex.get(new IffChunkKey(cx, cz));
                    if (chunk == null) {
                        continue;
                    }
                    for (UUID targetId : chunk) {
                        if (targetId.equals(viewerId)) {
                            continue;
                        }
                        ServerPlayer target = player(targetId);
                        boolean show = shouldShowFriendlyGlow(viewer, target)
                                || shouldShowEnemyGlow(viewer, target);
                        if (show) {
                            shouldKeep.add(targetId);
                            if (active.add(targetId)) {
                                GlowSync.showGlowTo(viewer, target);
                            }
                        } else if (active.remove(targetId) && target != null) {
                            GlowSync.hideGlowFrom(viewer, target);
                        }
                    }
                }
            }

            for (UUID targetId : factionOf.keySet()) {
                if (targetId.equals(viewerId) || shouldKeep.contains(targetId)) {
                    continue;
                }
                ServerPlayer target = player(targetId);
                if (shouldShowFriendlyGlow(viewer, target)) {
                    shouldKeep.add(targetId);
                    if (active.add(targetId)) {
                        GlowSync.showGlowTo(viewer, target);
                    }
                } else if (active.remove(targetId) && target != null) {
                    GlowSync.hideGlowFrom(viewer, target);
                }
            }

            for (UUID stale : new HashSet<>(active)) {
                if (!shouldKeep.contains(stale)) {
                    ServerPlayer target = player(stale);
                    if (target != null) {
                        GlowSync.hideGlowFrom(viewer, target);
                    }
                    active.remove(stale);
                }
            }
        }
    }

    private Map<IffChunkKey, List<UUID>> buildIffSpatialIndex() {
        Map<IffChunkKey, List<UUID>> index = new LinkedHashMap<>();
        for (UUID id : factionOf.keySet()) {
            ServerPlayer p = player(id);
            if (p == null || p.level() != level || !p.isAlive() || p.isSpectator()) {
                continue;
            }
            IffChunkKey key = iffChunkKey(p);
            index.computeIfAbsent(key, k -> new ArrayList<>()).add(id);
        }
        return index;
    }

    private static IffChunkKey iffChunkKey(ServerPlayer p) {
        return new IffChunkKey(
                (int) Math.floor(p.getX() / IFF_CHUNK_SIZE),
                (int) Math.floor(p.getZ() / IFF_CHUNK_SIZE));
    }

    private record IffChunkKey(int cx, int cz) {}

    private void syncRelativeTeams(ServerPlayer viewer, UUID viewerId) {
        Faction mine = factionOf.get(viewerId);
        RelativeTeamSync.sync(viewer, factionOf.keySet(), this::player, id -> {
            if (mine == null || factionOf.get(id) != mine) {
                return RelativeTeamSync.Relation.ENEMY;
            }
            // 高亮与救援共用同一套授权判据：能看见却救不了、或能救却看不见，都会让玩家困惑。
            return downedUntil.containsKey(id) && canRevive(viewer, viewerId, id)
                    ? RelativeTeamSync.Relation.FRIENDLY_DOWNED
                    : RelativeTeamSync.Relation.FRIENDLY;
        });
    }

    private boolean canViewerIdentify(@Nullable ServerPlayer viewer) {
        if (viewer == null || viewer.level() != level || !viewer.isAlive() || viewer.isSpectator()) {
            return false;
        }
        return !redeployService.isRedeploying(viewer.getUUID());
    }

    private boolean shouldShowEnemyGlow(ServerPlayer viewer, @Nullable ServerPlayer target) {
        if (target == null || target.level() != level || !target.isAlive() || target.isSpectator()) {
            return false;
        }
        UUID viewerId = viewer.getUUID();
        UUID targetId = target.getUUID();
        Faction viewerFaction = factionOf.get(viewerId);
        Faction targetFaction = factionOf.get(targetId);
        if (viewerFaction == null || targetFaction == null || viewerFaction == targetFaction) {
            return false;
        }
        if (redeployService.isRedeploying(targetId)) {
            return false;
        }
        if (viewer.distanceToSqr(target) > enemyMarkDistanceSqr) {
            return false;
        }
        if (!isInFrontOf(viewer, target)) {
            return false;
        }
        return hasClearSight(viewer, target);
    }

    private boolean shouldShowFriendlyGlow(ServerPlayer viewer, @Nullable ServerPlayer target) {
        if (target == null || target.level() != level || !target.isAlive() || target.isSpectator()) {
            return false;
        }
        UUID viewerId = viewer.getUUID();
        UUID targetId = target.getUUID();
        Faction viewerFaction = factionOf.get(viewerId);
        Faction targetFaction = factionOf.get(targetId);
        return viewerFaction != null && viewerFaction == targetFaction && !redeployService.isRedeploying(targetId);
    }

    private boolean isInFrontOf(ServerPlayer viewer, ServerPlayer target) {
        return isInFrontOf(viewer, target, enemyMarkViewDot);
    }

    private boolean isInFrontOf(ServerPlayer viewer, ServerPlayer target, double minDot) {
        Vec3 eyes = viewer.getEyePosition();
        Vec3 toTarget = target.getEyePosition().subtract(eyes);
        if (toTarget.lengthSqr() < 0.0001D) {
            return true;
        }
        return viewer.getViewVector(1.0F).normalize().dot(toTarget.normalize()) >= minDot;
    }

    private boolean hasClearSight(ServerPlayer viewer, ServerPlayer target) {
        Vec3 from = viewer.getEyePosition();
        Vec3 toEyes = target.getEyePosition();
        Vec3 toBody = target.position().add(0.0D, target.getBbHeight() * 0.55D, 0.0D);
        return clearBlockRay(viewer, from, toEyes) || clearBlockRay(viewer, from, toBody);
    }

    private boolean clearBlockRay(ServerPlayer viewer, Vec3 from, Vec3 to) {
        HitResult hit = level.clip(new ClipContext(from, to,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, viewer));
        return hit.getType() == HitResult.Type.MISS;
    }

    private void clearEnemyGlowFor(ServerPlayer viewer) {
        if (viewer != null) {
            clearEnemyGlowFor(viewer.getUUID());
            clearRelativeTeamsFor(viewer);
        }
    }

    private void clearEnemyGlowFor(UUID viewerId) {
        Set<UUID> active = visibleEnemyGlows.remove(viewerId);
        if (active == null || active.isEmpty()) {
            return;
        }
        ServerPlayer viewer = player(viewerId);
        if (viewer == null) {
            return;
        }
        for (UUID targetId : active) {
            ServerPlayer target = player(targetId);
            if (target != null) {
                GlowSync.hideGlowFrom(viewer, target);
            }
        }
    }

    private void clearEnemyGlowTarget(ServerPlayer target) {
        if (target == null) {
            return;
        }
        UUID targetId = target.getUUID();
        for (Map.Entry<UUID, Set<UUID>> e : new ArrayList<>(visibleEnemyGlows.entrySet())) {
            if (e.getValue().remove(targetId)) {
                ServerPlayer viewer = player(e.getKey());
                if (viewer != null) {
                    RelativeTeamSync.removeTarget(viewer, target);
                    GlowSync.hideGlowFrom(viewer, target);
                }
            }
        }
    }

    private void clearAllEnemyGlows() {
        for (UUID viewerId : new ArrayList<>(visibleEnemyGlows.keySet())) {
            clearEnemyGlowFor(viewerId);
        }
        visibleEnemyGlows.clear();
    }

    private void clearRelativeTeamsFor(ServerPlayer viewer) {
        RelativeTeamSync.clear(viewer);
    }

    private void clearAllRelativeTeams() {
        for (UUID viewerId : factionOf.keySet()) {
            ServerPlayer viewer = player(viewerId);
            if (viewer != null) {
                clearRelativeTeamsFor(viewer);
            }
        }
    }

    // ---- 死亡接管 ----

    public boolean onDeath(UUID victimId, @Nullable UUID killerId) {
        if (ended) {
            return false;
        }
        if (downedUntil.containsKey(victimId)) {
            return false;
        }
        Faction f = factionOf.get(victimId);
        if (f == null) {
            return false;
        }
        ServerPlayer p = player(victimId);
        if (p != null) {
            clearEnemyGlowFor(p);
            clearEnemyGlowTarget(p);
            p.clearFire();
            p.getFoodData().setFoodLevel(20);
            enterDowned(p, f, killerId);
        }
        Faction killerFaction = killerId != null ? factionOf.get(killerId) : null;
        pendingDeaths.put(victimId, new PendingDeath(killerId, killerFaction, f));
        lastHurtTick.remove(victimId);
        killTracker.onDowned(victimId, killerId);
        if (attackerTickets <= 0) {
            end(Faction.BRAVO);
        }
        return true;
    }

    private void consumePendingDeath(UUID victimId) {
        PendingDeath pending = pendingDeaths.remove(victimId);
        if (pending == null) {
            return;
        }
        killTracker.recordDeath(victimId);
        if (pending.victimFaction() == Faction.ALPHA) {
            attackerTickets -= BattlefieldConfig.TICKET_PER_DEATH.get();
            if (attackerTickets <= 0) {
                attackerTickets = 0;
                end(Faction.BRAVO);
                return;
            }
        }
        killTracker.handleKillCredit(victimId, pending.killerId());
    }

    public void onHurt(UUID victimId, @Nullable UUID attackerId) {
        if (ended || !factionOf.containsKey(victimId)) {
            return;
        }
        sendDamageDirection(victimId, attackerId);
        lastHurtTick.put(victimId, (long) server.getTickCount());
        ServerPlayer p = player(victimId);
        if (p != null) {
            p.removeEffect(MobEffects.REGENERATION);
        }
        if (attackerId != null && !attackerId.equals(victimId)
                && !downedUntil.containsKey(victimId) && !downedUntil.containsKey(attackerId)) {
            killTracker.recordHit(victimId, attackerId, (long) server.getTickCount());
        }
    }

    /** 与 {@code ConquestMatch#broadcastPing} 对称：把战术标记转发给同小队成员。 */
    public void broadcastPing(ServerPlayer from, double x, double z) {
        UUID id = from.getUUID();
        if (!factionOf.containsKey(id)) {
            return;
        }
        double dx = x - from.getX();
        double dz = z - from.getZ();
        if (dx * dx + dz * dz > PING_MAX_RANGE * PING_MAX_RANGE) {
            return;
        }
        for (UUID mateId : factionOf.keySet()) {
            if (!mateId.equals(id) && !squadManager.isSameSquad(id, mateId)) {
                continue;
            }
            ServerPlayer mate = player(mateId);
            if (mate != null) {
                BattlefieldNetwork.sendPing(mate, x, z);
            }
        }
    }

    /** 与 {@code ConquestMatch#sendDamageDirection} 对称：只推送方位角。 */
    public void sendDamageDirection(UUID victimId, @Nullable UUID attackerId) {
        if (attackerId == null || attackerId.equals(victimId)) {
            return;
        }
        ServerPlayer victim = player(victimId);
        ServerPlayer attacker = player(attackerId);
        if (victim == null || attacker == null) {
            return;
        }
        float bearing = (float) Math.atan2(attacker.getX() - victim.getX(),
                -(attacker.getZ() - victim.getZ()));
        BattlefieldNetwork.sendDamageDirection(victim, bearing);
    }

    /** 该对局所在世界，供管理器按地图取人数规则。 */
    public ServerLevel level() {
        return level;
    }

    /** 与 {@code ConquestMatch#isEnemyHit} 对称：攻守双方分属不同阵营才算有效命中。 */
    public boolean isEnemyHit(UUID victimId, @Nullable UUID attackerId) {
        if (victimId == null || attackerId == null || victimId.equals(attackerId)) {
            return false;
        }
        Faction victimFaction = factionOf.get(victimId);
        Faction attackerFaction = factionOf.get(attackerId);
        return victimFaction != null && attackerFaction != null && victimFaction != attackerFaction;
    }

    /** 与 {@code ConquestMatch#sendHitMarker} 对称：向攻击者推送准心命中标记。 */
    public void sendHitMarker(@Nullable UUID attackerId) {
        if (attackerId == null) {
            return;
        }
        ServerPlayer attacker = player(attackerId);
        if (attacker != null) {
            BattlefieldNetwork.sendHitFeedback(attacker, false);
        }
    }

    public boolean shouldCancelDamage(UUID victimId, @Nullable UUID attackerId) {
        if (!factionOf.containsKey(victimId)) {
            return false;
        }
        if (startCountdownTicks > 0) {
            return true;
        }
        if (redeployService.isRedeploying(victimId)) {
            return true;
        }
        if (downedUntil.containsKey(victimId)) {
            return true;
        }
        ServerPlayer victim = player(victimId);
        if (victim != null && victim.isSpectator()) {
            return true;
        }
        if (redeployService.consumeProtection(victimId)) {
            return true;
        }
        if (attackerId != null && !attackerId.equals(victimId)) {
            Faction vf = factionOf.get(victimId);
            Faction af = factionOf.get(attackerId);
            return vf != null && vf == af;
        }
        return false;
    }

    // ---- 倒地救援 ----

    /** 倒地时把玩家整个物品栏（主背包+副手+盔甲）序列化缓存并清空，任何模组的开火/交互逻辑都拿不到物品。 */
    private void cacheAndClearInventoryForDowned(ServerPlayer p) {
        Inventory inv = p.getInventory();
        int size = inv.getContainerSize();
        NonNullList<ItemStack> snapshot = NonNullList.withSize(size, ItemStack.EMPTY);
        for (int i = 0; i < size; i++) {
            snapshot.set(i, inv.getItem(i).copy());
            inv.setItem(i, ItemStack.EMPTY);
        }
        downedInventoryCache.put(p.getUUID(), snapshot);
    }

    /** 救援成功后把缓存的物品栏原样归还；没有缓存时安全跳过。 */
    private void restoreDownedInventory(ServerPlayer p) {
        NonNullList<ItemStack> snapshot = downedInventoryCache.remove(p.getUUID());
        if (snapshot == null) {
            return;
        }
        Inventory inv = p.getInventory();
        int size = Math.min(snapshot.size(), inv.getContainerSize());
        for (int i = 0; i < size; i++) {
            inv.setItem(i, snapshot.get(i));
        }
    }

    private void enterDowned(ServerPlayer p, Faction f, @Nullable UUID killerId) {
        UUID id = p.getUUID();
        // 自己被打倒的同一刻就中断自己正在进行的救援（tickRevives 下一 tick 也会兜住，
        // 但那会让"倒地了却还在读救援进度"多显示一帧）。
        cancelRevive(id);
        long until = server.getTickCount() + downedDurationTicks;
        downedUntil.put(id, until);
        downedLastGoodY.put(id, p.getY());
        cacheAndClearInventoryForDowned(p);
        BattlefieldNetwork.sendFireLock(p, true);
        p.setHealth(1.0f);
        p.setPose(Pose.SWIMMING);
        p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, downedDurationTicks, 5, false, false));
        p.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, downedDurationTicks, 5, false, false));
        p.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 20, 0, false, false));
        if (killerId != null) {
            ServerPlayer killer = player(killerId);
            if (killer != null) {
                Vec3 from = p.getEyePosition();
                Vec3 to = killer.getEyePosition();
                double dx = to.x - from.x;
                double dy = to.y - from.y;
                double dz = to.z - from.z;
                double h = Math.sqrt(dx * dx + dz * dz);
                float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
                float pitch = (float) (-Math.toDegrees(Math.atan2(dy, h)));
                ConnectionSafeTeleport.teleport(p, p.getX(), p.getY(), p.getZ(), yaw, pitch);
            }
        }
        String killerName = killerId != null ? nameOf(killerId) : "未知";
        p.sendSystemMessage(Component.literal("§c你被 " + killerName + " 击倒了！§7长按空格放弃 · 队友按住F瞄准你即可救援"));
        p.displayClientMessage(Component.literal("§c§l倒地！等待队友救援"), true);
        BattlefieldNetwork.sendDownedFeedback(p);
        BattlefieldFx.downed(level, p.getX(), p.getY(), p.getZ());
    }

    private void tickDownedPlayers() {
        if (downedUntil.isEmpty()) {
            return;
        }
        // Re-assert SWIMMING pose every tick. Our tick runs in Phase.END, after the vanilla
        // Player.aiStep() has already reset the pose back to STANDING (player isn't truly
        // swimming/sneaking). Without this, the pose we'd set in enterDowned() is reverted
        // before it ever gets synced to the client.
        for (UUID id : downedUntil.keySet()) {
            ServerPlayer p = player(id);
            if (p == null) {
                continue;
            }
            if (p.getPose() != Pose.SWIMMING) {
                p.setPose(Pose.SWIMMING);
            }
            double lastGoodY = downedLastGoodY.getOrDefault(id, p.getY());
            if (p.getY() - lastGoodY > DOWNED_MAX_Y_RISE_PER_TICK) {
                ConnectionSafeTeleport.teleport(p, p.getX(), lastGoodY, p.getZ(), p.getYRot(), p.getXRot());
                Vec3 v = p.getDeltaMovement();
                if (v.y > 0.0D) {
                    p.setDeltaMovement(v.x, 0.0D, v.z);
                }
            } else {
                downedLastGoodY.put(id, p.getY());
            }
        }
        long now = server.getTickCount();
        List<UUID> expired = new ArrayList<>();
        for (Map.Entry<UUID, Long> e : downedUntil.entrySet()) {
            UUID id = e.getKey();
            if (revivingTarget.containsValue(id)) {
                continue;
            }
            if (now >= e.getValue()) {
                expired.add(id);
            }
        }
        for (UUID id : expired) {
            consumePendingDeath(id);
            downedUntil.remove(id);
            downedLastGoodY.remove(id);
            ServerPlayer p = player(id);
            Faction f = factionOf.get(id);
            if (p != null && f != null) {
                p.removeAllEffects();
                p.setPose(Pose.STANDING);
                p.sendSystemMessage(Component.literal("§4救援时间已过，你阵亡了。"));
                beginRedeploy(p, f);
            }
        }
    }

    private void tickRevives() {
        if (revivingStarted.isEmpty()) {
            return;
        }
        long now = server.getTickCount();
        List<UUID> toComplete = new ArrayList<>();
        List<UUID> toCancel = new ArrayList<>();
        for (Map.Entry<UUID, Long> e : revivingStarted.entrySet()) {
            UUID reviverId = e.getKey();
            UUID targetId = revivingTarget.get(reviverId);
            ServerPlayer reviver = player(reviverId);
            ServerPlayer target = player(targetId);
            // 救援者中途被打倒同样要立刻中断（发起时校验过一次不够——救援持续数秒，期间完全
            // 可能挨枪倒地）。
            if (reviver == null || target == null || !downedUntil.containsKey(targetId)
                    || downedUntil.containsKey(reviverId)
                    || !canRevive(reviver, reviverId, targetId)
                    || target.distanceToSqr(reviver) > 16.0D || !isInFrontOf(reviver, target, REVIVE_VIEW_DOT)) {
                toCancel.add(reviverId);
                continue;
            }
            // 医疗针救援由服务端自行推进，客户端不发心跳；手持期间逐 tick 续期，否则 10 tick 的
            // 心跳超时会在 20 tick 的加速救援完成之前就把它掐掉。
            if (holdsSyringe(reviver)) {
                revivingHeartbeat.put(reviverId, now);
            }
            Long lastHeartbeat = revivingHeartbeat.get(reviverId);
            if (lastHeartbeat == null || now - lastHeartbeat > REVIVE_HEARTBEAT_TIMEOUT_TICKS) {
                toCancel.add(reviverId);
                continue;
            }
            if (now >= e.getValue() + reviveDurationOf(reviverId)) {
                toComplete.add(reviverId);
            }
        }
        for (UUID reviverId : toCancel) {
            cancelRevive(reviverId);
        }
        for (UUID reviverId : toComplete) {
            UUID targetId = revivingTarget.remove(reviverId);
            revivingStarted.remove(reviverId);
            revivingHeartbeat.remove(reviverId);
            revivingDuration.remove(reviverId);
            ServerPlayer target = player(targetId);
            ServerPlayer reviver = player(reviverId);
            if (target != null && reviver != null) {
                downedUntil.remove(targetId);
                downedLastGoodY.remove(targetId);
                pendingDeaths.remove(targetId);
                restoreDownedInventory(target);
                BattlefieldNetwork.sendFireLock(target, false);
                target.removeAllEffects();
                target.setPose(Pose.STANDING);
                target.setHealth(target.getMaxHealth() * 0.5f);
                target.sendSystemMessage(Component.literal("§a" + reviver.getGameProfile().getName() + " §a救起了你！"));
                target.displayClientMessage(Component.literal("§a已被救起"), true);
                BattlefieldNetwork.sendRevivedFeedback(target, reviver.getGameProfile().getName());
                reviver.sendSystemMessage(Component.literal("§a你救起了 " + target.getGameProfile().getName()));
                reviver.playNotifySound(SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.MASTER, 0.8f, 1.2f);
                target.playNotifySound(SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.MASTER, 0.8f, 1.2f);
                if (holdsSyringe(reviver)) {
                    reviver.getCooldowns().addCooldown(BattlefieldRegistry.MEDIC_SYRINGE.get(),
                            SupplyRules.SYRINGE_COOLDOWN_TICKS);
                }
            }
        }
    }

    /**
     * 能否救援该目标：同小队队友随时可救，其余同阵营队友需<b>手持医疗针</b>。
     *
     * <p>兵种系统已随 ACT0-Arcade 一并移除，跨小队救援现在只由医疗针授权——医疗针的唯一用途
     * 就是救人，能不能救队友因此完全取决于玩家自己选了什么装备，而不是一个看不见的职业字段。
     *
     * <p>这里显式比对阵营，不再依赖"同小队蕴含同阵营"：小队编号按阵营分段本来就保证了这一点，
     * 但医疗针会绕过小队判定，跨阵营救人必须由这一行挡住。
     */
    private boolean canRevive(ServerPlayer reviver, UUID reviverId, UUID targetId) {
        Faction rf = factionOf.get(reviverId);
        if (rf == null || rf != factionOf.get(targetId)) {
            return false;
        }
        // 医疗兵是唯一不靠道具就能跨小队救人的兵种；医疗针对所有兵种仍然给速度加成，两者不重叠。
        return squadManager.isSameSquad(reviverId, targetId)
                || holdsSyringe(reviver)
                || classOf(reviver) == SoldierClass.MEDIC;
    }

    private SoldierClass classOf(ServerPlayer player) {
        return BattlefieldLoadoutService.classOf(player, arenaKey);
    }

    /**
     * 突击兵的呼吸回血启动更快。
     *
     * <p>按玩家逐个解析而非开局定死：兵种是在部署界面选的，一名玩家整场里可以换。
     */
    private int breathHealDelayOf(ServerPlayer player) {
        return classOf(player) == SoldierClass.ASSAULT
                ? Math.max(1, breathHealDelayTicks / SoldierClass.ASSAULT_HEAL_DELAY_DIVISOR)
                : breathHealDelayTicks;
    }

    private int deployableLifetimeOf(ServerPlayer owner) {
        return classOf(owner) == SoldierClass.ENGINEER
                ? SupplyRules.LIFETIME_TICKS * SoldierClass.ENGINEER_DEPLOYABLE_LIFETIME_MULTIPLIER
                : SupplyRules.LIFETIME_TICKS;
    }

    private static boolean holdsSyringe(ServerPlayer player) {
        return player.getMainHandItem().is(BattlefieldRegistry.MEDIC_SYRINGE.get())
                || player.getOffhandItem().is(BattlefieldRegistry.MEDIC_SYRINGE.get());
    }

    private int reviveDurationOf(UUID reviverId) {
        Integer d = revivingDuration.get(reviverId);
        return d == null ? reviveDurationTicks : d;
    }

    private boolean sameFaction(UUID a, UUID b) {
        Faction fa = factionOf.get(a);
        return fa != null && fa == factionOf.get(b);
    }

    /**
     * 医疗针点击救援：一次点击即开始，之后由 {@link #tickRevives} 自行推进，不依赖客户端心跳。
     *
     * @return 该玩家是否属于本场对局（{@code false} 时交由另一个模式的管理器处理）
     */
    public boolean handleSyringeRevive(ServerPlayer reviver, ServerPlayer target) {
        UUID reviverId = reviver.getUUID();
        UUID targetId = target.getUUID();
        if (ended || !factionOf.containsKey(reviverId)) {
            return false;
        }
        if (reviver.getCooldowns().isOnCooldown(BattlefieldRegistry.MEDIC_SYRINGE.get())
                || downedUntil.containsKey(reviverId) || !downedUntil.containsKey(targetId)
                || !canRevive(reviver, reviverId, targetId)) {
            return true;
        }
        if (target.distanceToSqr(reviver) > 16.0D) {
            reviver.displayClientMessage(Component.literal("§c距离太远"), true);
            return true;
        }
        long now = server.getTickCount();
        if (!targetId.equals(revivingTarget.get(reviverId))) {
            revivingTarget.put(reviverId, targetId);
            revivingStarted.put(reviverId, now);
            revivingDuration.put(reviverId, SupplyRules.reviveDuration(reviveDurationTicks, true));
            reviver.displayClientMessage(
                    Component.literal("§a正在救援 " + target.getGameProfile().getName() + "..."), true);
        }
        revivingHeartbeat.put(reviverId, now);
        return true;
    }

    /** 在玩家视线前方部署一个补给物。 */
    public boolean handleDeployGadget(ServerPlayer player, DeployableKind kind, ItemStack display) {
        UUID id = player.getUUID();
        if (ended || !factionOf.containsKey(id) || downedUntil.containsKey(id)) {
            return false;
        }
        deployables.deploy(level, player, kind, display, server.getTickCount(), deployableLifetimeOf(player));
        return true;
    }

    /**
     * 把己方补给物下发给同阵营玩家，驱动地面提示圆。
     *
     * <p>列表为空时只再发一次"清空"包便停发，而不是每个 HUD 周期都发一个空列表——绝大多数时间
     * 场上没有补给物，持续广播空包纯属浪费带宽。
     */
    private void broadcastDeployables() {
        long now = server.getTickCount();
        boolean empty = deployables.isEmpty();
        if (empty && !deployablesWereSent) {
            return;
        }
        deployablesWereSent = !empty;
        for (UUID id : factionOf.keySet()) {
            ServerPlayer p = player(id);
            if (p != null) {
                BattlefieldNetwork.sendDeployables(p, deployables.snapshotFor(now, owner -> sameFaction(owner, id)));
            }
        }
    }

    /**
     * 按玩家哈希增量下发小队名册。
     *
     * <p>不在每个变更点逐一调用，而是挂在 HUD 周期上按内容哈希去重——名册除了加入/离开/锁定，
     * 还会随成员倒地状态变化，逐点触发几乎必然漏掉其中一条。
     */
    private void broadcastSquadRosters() {
        for (UUID id : factionOf.keySet()) {
            ServerPlayer p = player(id);
            if (p == null) {
                continue;
            }
            SquadRosterDto roster = squadRosterFor(id);
            int hash = roster.hashCode();
            Integer prev = lastRosterHash.get(id);
            if (prev == null || prev != hash) {
                lastRosterHash.put(id, hash);
                BattlefieldNetwork.sendSquadRoster(p, roster);
            }
        }
    }

    /** 本阵营小队名册（暂停菜单小队管理页数据源）。只含本阵营——敌方编制是战术情报。 */
    public SquadRosterDto squadRosterFor(UUID viewerId) {
        Faction f = factionOf.get(viewerId);
        if (f == null) {
            return SquadRosterDto.empty();
        }
        List<SquadRosterDto.Squad> out = new ArrayList<>();
        for (int squadId : squadManager.squadIdsOf(f)) {
            LinkedHashSet<UUID> members = squadManager.getSquads().get(squadId);
            if (members == null) {
                continue;
            }
            List<SquadRosterDto.Member> list = new ArrayList<>();
            for (UUID memberId : members) {
                ServerPlayer p = player(memberId);
                if (p == null) {
                    continue;
                }
                list.add(new SquadRosterDto.Member(p.getGameProfile().getName(), memberId.equals(viewerId),
                        squadManager.isSquadLeader(memberId), downedUntil.containsKey(memberId)));
            }
            out.add(new SquadRosterDto.Squad(squadId, squadManager.isLocked(squadId), list));
        }
        return new SquadRosterDto(squadManager.squadIdOf(viewerId), out);
    }

    /**
     * 处理暂停菜单的小队操作。服务端完整复核权限与准入，不信任客户端上报。
     *
     * @return 该玩家是否属于本场对局（{@code false} 时交由另一模式的管理器处理）
     */
    public boolean handleSquadAction(ServerPlayer player, int kind, int targetSquadId) {
        UUID id = player.getUUID();
        if (ended || !factionOf.containsKey(id)) {
            return false;
        }
        switch (kind) {
            case SquadActionPacket.KIND_TOGGLE_LOCK -> {
                if (!SquadJoinRules.canToggleLock(squadManager.isSquadLeader(id), squadManager.squadIdOf(id))) {
                    player.displayClientMessage(Component.literal("§c只有队长可以锁定小队"), true);
                    return true;
                }
                boolean locked = squadManager.toggleLock(id);
                player.displayClientMessage(Component.literal(locked ? "§e小队已锁定" : "§a小队已解锁"), true);
            }
            case SquadActionPacket.KIND_LEAVE -> {
                if (!squadManager.leaveSquad(id)) {
                    player.displayClientMessage(Component.literal("§7你当前未加入任何小队"), true);
                    return true;
                }
                player.displayClientMessage(Component.literal("§a已离开小队"), true);
            }
            case SquadActionPacket.KIND_JOIN -> {
                SquadJoinRules.Result r = squadManager.joinSquad(id, targetSquadId);
                if (r != SquadJoinRules.Result.OK) {
                    player.displayClientMessage(Component.literal(switch (r) {
                        case FULL -> "§c该小队已满";
                        case LOCKED -> "§c该小队已锁定";
                        case ALREADY_IN -> "§7你已在该小队中";
                        default -> "§c无法加入该小队";
                    }), true);
                    return true;
                }
                player.displayClientMessage(Component.literal("§a已加入小队 " + targetSquadId), true);
            }
            default -> {
                return true;
            }
        }
        setupNameTagTeams();
        broadcastHud();
        return true;
    }

    /**
     * 中断某人正在进行的救援。幂等：只有确实存在进行中的救援才会提示"救援中断"——否则
     * 每个从未救人的玩家在退出/倒地时都会莫名收到一条中断提示。
     */
    private void cancelRevive(UUID reviverId) {
        revivingTarget.remove(reviverId);
        boolean wasReviving = revivingStarted.remove(reviverId) != null;
        revivingHeartbeat.remove(reviverId);
        revivingDuration.remove(reviverId);
        if (!wasReviving) {
            return;
        }
        ServerPlayer reviver = player(reviverId);
        if (reviver != null) {
            reviver.displayClientMessage(Component.literal("§c救援中断"), true);
        }
    }

    /**
     * C2S 救援心跳：客户端持续按住救援键且瞄准倒地队友时上报（{@code active=true}，附带目标实体
     * ID）；按键松开或瞄准脱离时上报一次 {@code active=false} 停止信号，立即取消救援。
     *
     * <p>{@code active=true} 时仍会做一次完整校验（同队、倒地中、距离、朝向），校验不通过直接
     * 忽略（不会开始/续期救援）；{@link #tickRevives} 每 tick 独立复核同样的条件并检查心跳是否
     * 超时，两者任意一个失败都会 {@link #cancelRevive}——服务端不会仅凭客户端上报的信号驱动救援。
     */
    public void handleReviveHeartbeat(ServerPlayer reviver, int targetEntityId, boolean active) {
        UUID reviverId = reviver.getUUID();
        if (!active) {
            if (revivingStarted.containsKey(reviverId)) {
                cancelRevive(reviverId);
            }
            return;
        }
        // 倒地的人救不了别人：自己都躺在地上等人扶，没有任何理由还能把队友拉起来。
        if (downedUntil.containsKey(reviverId)) {
            return;
        }
        if (!(level.getEntity(targetEntityId) instanceof ServerPlayer target)) {
            return;
        }
        UUID targetId = target.getUUID();
        if (!downedUntil.containsKey(targetId)) {
            return;
        }
        if (!canRevive(reviver, reviverId, targetId)) {
            return;
        }
        if (target.distanceToSqr(reviver) > 16.0D || !isInFrontOf(reviver, target, REVIVE_VIEW_DOT)) {
            return;
        }
        long now = server.getTickCount();
        revivingHeartbeat.put(reviverId, now);
        if (!revivingStarted.containsKey(reviverId)) {
            revivingTarget.put(reviverId, targetId);
            revivingStarted.put(reviverId, now);
            revivingDuration.put(reviverId, SupplyRules.reviveDuration(reviveDurationTicks, holdsSyringe(reviver)));
            reviver.displayClientMessage(Component.literal("§a正在救援 " + target.getGameProfile().getName() + "..."), true);
        } else if (!targetId.equals(revivingTarget.get(reviverId))) {
            revivingTarget.put(reviverId, targetId);
            revivingStarted.put(reviverId, now);
            revivingDuration.put(reviverId, SupplyRules.reviveDuration(reviveDurationTicks, holdsSyringe(reviver)));
        }
    }

    public boolean isDowned(UUID id) {
        return downedUntil.containsKey(id);
    }

    int getBeingRevivedProgress(UUID targetId) {
        UUID reviverId = findReviverOf(targetId);
        if (reviverId == null) {
            return 0;
        }
        Long started = revivingStarted.get(reviverId);
        if (started == null) {
            return 0;
        }
        long elapsed = server.getTickCount() - started;
        return (int) Math.min(100, Math.round((double) elapsed / reviveDurationOf(reviverId) * 100.0));
    }

    @Nullable
    String getBeingRevivedByName(UUID targetId) {
        UUID reviverId = findReviverOf(targetId);
        if (reviverId == null) {
            return null;
        }
        ServerPlayer reviver = player(reviverId);
        return reviver != null ? reviver.getGameProfile().getName() : null;
    }

    @Nullable
    private UUID findReviverOf(UUID targetId) {
        for (Map.Entry<UUID, UUID> e : revivingTarget.entrySet()) {
            if (e.getValue().equals(targetId)) {
                return e.getKey();
            }
        }
        return null;
    }

    public void handleDownedAction(ServerPlayer player, DownedActionPacket.Action action) {
        UUID id = player.getUUID();
        if (!downedUntil.containsKey(id)) {
            return;
        }
        Faction f = factionOf.get(id);
        if (f == null) {
            return;
        }
        if (action == DownedActionPacket.Action.GIVE_UP) {
            consumePendingDeath(id);
            downedUntil.remove(id);
            downedLastGoodY.remove(id);
            player.removeAllEffects();
            player.setPose(Pose.STANDING);
            player.sendSystemMessage(Component.literal("§7你放弃了救援。"));
            beginRedeploy(player, f);
        } else if (action == DownedActionPacket.Action.CALL_HELP) {
            long now = server.getTickCount();
            Long cooldownUntil = callHelpCooldownUntil.get(id);
            if (cooldownUntil != null && now < cooldownUntil) {
                return;
            }
            callHelpCooldownUntil.put(id, now + CALL_HELP_COOLDOWN_TICKS);
            player.displayClientMessage(Component.literal("§e已呼叫救援"), true);
            player.playNotifySound(SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.MASTER, 0.6f, 1.5f);
            for (UUID mateId : factionOf.keySet()) {
                if (factionOf.get(mateId) == f && !mateId.equals(id)) {
                    ServerPlayer mate = player(mateId);
                    if (mate != null) {
                        mate.displayClientMessage(Component.literal("§c§l" + player.getGameProfile().getName() + " §c呼叫救援！"), true);
                        mate.playNotifySound(SoundEvents.NOTE_BLOCK_BELL.value(), SoundSource.MASTER, 0.4f, 1.8f);
                    }
                }
            }
        }
    }

    // ---- 结束 ----

    private void end(Faction w) {
        if (ended) {
            return;
        }
        this.ended = true;
        this.winner = w;
        // 先解散阵营名牌 Scoreboard 队伍：一旦 ended=true，管理器下一 tick 就会把这场对局从
        // activeByWorld 中摘除并丢弃引用；如果下面逐人结算（网络发包/传送）抛出异常，必须保证
        // 这行已经执行过，否则队伍会永久残留在服务器 Scoreboard 里，再也没有代码路径能碰到它。
        clearNameTagTeams();
        broadcast("§6§l对局结束：" + coloredFaction(w) + " §6§l获胜！");
        for (Map.Entry<UUID, Faction> e : factionOf.entrySet()) {
            ServerPlayer p = player(e.getKey());
            if (p != null) {
                if (redeployService.isRedeploying(e.getKey())) {
                    clearRedeployState(p, true);
                } else {
                    p.setInvulnerable(false);
                }
                sendPersonalResult(p, e.getValue(), w);
                BattlefieldNetwork.sendBattleResult(p, buildResultFor(p, w));
                BattlefieldData.BaseSpawn base = data.base(e.getValue());
                if (base != null) {
                    p.teleportTo(lobbyLevel, base.x(), base.y(), base.z(), base.yaw(), base.pitch());
                }
                p.getInventory().clearContent();
                BattlefieldNetwork.clearHud(p);
                BattlefieldNetwork.clearBreakthroughHud(p);
            }
        }
        redeployService.clearAll();
        deployables.clearAll(level);
        lastHurtTick.clear();
        escapeTicks.clear();
        downedUntil.clear();
        downedLastGoodY.clear();
        downedInventoryCache.clear();
        pendingDeaths.clear();
        revivingTarget.clear();
        revivingStarted.clear();
        revivingHeartbeat.clear();
        callHelpCooldownUntil.clear();
        killTracker.clearTransient();
        // 此前end()/abort()都没清这3个map，跨对局残留可能导致下一局误报"防御通知"冷却、
        // HUD首帧因hash碰撞不刷新。
        defendNotificationCooldown.clear();
        lastCaptureStatus.clear();
        lastHudHash.clear();
        clearAllEnemyGlows();
        clearAllRelativeTeams();
        sendFireLockToAll(false);
    }

    /** 强制中止（服务器关闭/管理员停止）。 */
    public void abort() {
        if (ended) {
            return;
        }
        ended = true;
        // 同 end()：先解散名牌队伍，防止下面逐人传送/清 HUD 出异常时把队伍清理漏掉。
        clearNameTagTeams();
        for (UUID id : factionOf.keySet()) {
            ServerPlayer p = player(id);
            if (p != null) {
                if (redeployService.isRedeploying(id)) {
                    clearRedeployState(p, true);
                } else {
                    p.setInvulnerable(false);
                }
                BattlefieldData.BaseSpawn base = data.base(factionOf.get(id));
                if (base != null) {
                    p.teleportTo(lobbyLevel, base.x(), base.y(), base.z(), base.yaw(), base.pitch());
                }
                p.getInventory().clearContent();
                BattlefieldNetwork.clearHud(p);
                BattlefieldNetwork.clearBreakthroughHud(p);
            }
        }
        redeployService.clearAll();
        deployables.clearAll(level);
        lastHurtTick.clear();
        escapeTicks.clear();
        downedUntil.clear();
        downedLastGoodY.clear();
        downedInventoryCache.clear();
        pendingDeaths.clear();
        revivingTarget.clear();
        revivingStarted.clear();
        revivingHeartbeat.clear();
        callHelpCooldownUntil.clear();
        killTracker.clearTransient();
        // 此前end()/abort()都没清这3个map，跨对局残留可能导致下一局误报"防御通知"冷却、
        // HUD首帧因hash碰撞不刷新。
        defendNotificationCooldown.clear();
        lastCaptureStatus.clear();
        lastHudHash.clear();
        clearAllEnemyGlows();
        clearAllRelativeTeams();
        sendFireLockToAll(false);
    }

    private void sendPersonalResult(ServerPlayer player, Faction mine, Faction w) {
        boolean won = mine == w;
        sendTitle(player, won ? "§a§l胜利" : "§c§l失败",
                coloredFaction(w) + " §7取得胜利", 5, 60, 15);
        UUID id = player.getUUID();
        player.sendSystemMessage(Component.literal("§6战报 §8| " + (won ? "§a胜利" : "§c失败")
                + " §8| §7进攻方剩余票数 §f" + (int) attackerTickets
                + " §8| §7你的 K/D §e" + killTracker.killsOf(id) + "§7/§c" + killTracker.deathsOf(id)
                + " §8| §7推进至扇区 §e" + currentSectorIndex + "§7/§e" + sectors.size()));
    }

    /**
     * 构建 FlatTheme 战报界面（{@code BattleResultScreen}）所需的数据快照。
     *
     * <p>与 {@code ConquestMatch#buildResultFor} 共用同一个 {@link BattleResultDto}：
     * 可直接复用的字段（阵营/胜负/击杀死亡/用时）照填；{@code bravoTickets} 在突破模式
     * 里没有真实含义——票数池只属于进攻方（见 {@link #attackerTickets}），防守方没有
     * 对称的票数资源，因此传 0（而非编造一个数字）；{@code topCapturer}/{@code bestSquad}
     * 是 Conquest 专属的"占点王/最佳小队"统计，突破模式没有对应追踪（未维护
     * capture-time 或最佳小队榜单），传空字符串/0 由渲染端隐藏对应板块；
     * {@code sectorsCaptured}/{@code totalSectors} 是突破模式独有的推进进度，
     * Conquest 侧固定传 0/0。
     */
    private BattleResultDto buildResultFor(ServerPlayer viewer, Faction winner) {
        List<TabEntryDto> entries = new ArrayList<>();
        for (Map.Entry<UUID, Faction> e : factionOf.entrySet()) {
            UUID id = e.getKey();
            ServerPlayer p = player(id);
            String name = p != null ? p.getGameProfile().getName() : id.toString().substring(0, 8);
            int ping = p != null ? p.latency : -1;
            entries.add(new TabEntryDto(name, factionCode(e.getValue()),
                    killTracker.killsOf(id), killTracker.deathsOf(id), ping,
                    p == null ? 2 : (downedUntil.containsKey(id) ? 3 : 0),
                    displaySquad(squadManager.getSquadOf().getOrDefault(id, 0))));
        }
        entries.sort(Comparator
                .comparingInt(TabEntryDto::kills).reversed()
                .thenComparingInt(TabEntryDto::deaths)
                .thenComparing(TabEntryDto::name));
        UUID viewerId = viewer.getUUID();

        return new BattleResultDto(factionCode(winner), factionCode(factionOf.get(viewerId)),
                (int) attackerTickets, 0,
                factionName(Faction.ALPHA), factionName(Faction.BRAVO),
                killTracker.killsOf(viewerId), killTracker.deathsOf(viewerId), entries,
                "", 0, "", 0,
                elapsedSeconds(), currentSectorIndex, sectors.size());
    }

    public int elapsedSeconds() {
        return (int) Math.max(0L, (server.getTickCount() - startedTick) / 20L);
    }

    private static int displaySquad(int squadId) {
        return squadId >= 101 ? squadId - 100 : Math.max(0, squadId);
    }

    // ---- 部署 ----

    private void beginRedeploy(ServerPlayer player, Faction faction) {
        // 转入重生：丢弃倒地时缓存的物品栏（重生会重新分配装备），并解除开火锁双重保险。
        downedInventoryCache.remove(player.getUUID());
        BattlefieldNetwork.sendFireLock(player, false);
        // 倒地超时/放弃救援会先经过这里再进入重生选点：客户端此时应立即清除"倒地"横幅与
        // vignette，不必等到真正部署落地（DeploySpawnFxPacket）才清除，否则玩家在观战选点
        // 阶段会一直看到已经不再适用的倒地提示。
        BattlefieldNetwork.sendDownedClearedFeedback(player);
        redeployService.beginRedeploy(player, faction);
    }

    private void processRedeployTick() {
        redeployService.processRedeployTick();
    }

    private void deploy(ServerPlayer p, Faction f) {
        redeployService.deployDirect(p, f);
    }

    private void clearRedeployState(ServerPlayer player, boolean restoreOriginalMode) {
        redeployService.clearRedeployState(player, restoreOriginalMode);
    }

    public void handleDeployAction(ServerPlayer player, String kind) {
        redeployService.handleDeployAction(player, kind);
    }

    public void handleDeployAction(ServerPlayer player, String kind, String targetId) {
        redeployService.handleDeployAction(player, kind, targetId);
    }

    public void handleDeploySlotOverride(ServerPlayer player, int slotIndex, String itemName) {
        redeployService.handleSlotOverride(player, slotIndex, itemName);
    }

    public void refreshDeployStatus(ServerPlayer player) {
        redeployService.refreshDeployStatus(player);
    }

    public void onPlayerLogin(ServerPlayer player) {
        UUID id = player.getUUID();
        Faction faction = factionOf.get(id);
        if (faction == null) {
            return;
        }
        if (downedUntil.containsKey(id)) {
            downedUntil.remove(id);
            downedLastGoodY.remove(id);
            player.removeAllEffects();
            player.setPose(Pose.STANDING);
            player.sendSystemMessage(Component.literal("§c你掉线时倒地过久，已阵亡。"));
            beginRedeploy(player, faction);
            return;
        }
        redeployService.onPlayerLogin(player, faction);
        BattlefieldNetwork.sendBreakthroughHud(player, buildHudFor(player));
    }

    // ---- HUD ----

    /** 构建突破模式 HUD 快照（网络发送由外部调用方负责）。 */
    public BreakthroughHudDto buildHudFor(ServerPlayer viewer) {
        UUID viewerId = viewer.getUUID();
        Faction viewerFaction = factionOf.get(viewerId);
        int phase = startCountdownTicks > 0 ? 0 : (ended ? 2 : 1);
        int winnerCode = winner == null ? 0 : (winner == Faction.ALPHA ? 1 : 2);

        List<BreakthroughPointDto> pointDtos = new ArrayList<>(points.size());
        for (int i = 0; i < points.size(); i++) {
            CapturePoint cp = points.get(i);
            ControlPointDef def = defs.get(i);
            boolean locked = !isPointActive(def.pointId()) || currentSectorIndex >= sectors.size();
            int owner = factionCode(cp.owner());
            int pressure = cp.level() > 0.02 ? 1 : (cp.level() < -0.02 ? 2 : 0);
            int progress = Math.min(100, Math.max(0,
                    (int) Math.round(Math.abs(cp.level()) * 100.0)));
            int sectorIndex = pointSectorIndex.getOrDefault(def.pointId(), -1);
            pointDtos.add(new BreakthroughPointDto(
                    def.pointId(), cp.displayName(), owner, pressure, progress, locked, sectorIndex,
                    def.pos().getX() + 0.5, def.pos().getY() + 0.5, def.pos().getZ() + 0.5));
        }

        List<SquadMateHudDto> squad = squadHudFor(viewer);
        FocusHud focus = focusFor(viewer);
        String beingRevivedByName = getBeingRevivedByName(viewerId);
        int beingRevivedProgress = getBeingRevivedProgress(viewerId);

        return new BreakthroughHudDto(true,
                (int) attackerTickets, rules.startingTickets(),
                currentSectorIndex, sectors.size(),
                pointDtos, squad, phase, winnerCode,
                focus.name(), focus.state(), focus.progress(),
                beingRevivedByName != null ? beingRevivedByName : "", beingRevivedProgress);
    }

    /**
     * 本地玩家 FLIP 特写驱动字段（占点 HUD 动效规格文档 §1.3.2/§3.1）——只看当前激活区域内的目标点，
     * 单圈制、颜色语义绝对（不看 viewerFaction）：{@code state} 0=未站在任何目标点内，
     * 1=正在占领，2=已被 ALPHA 占满，3=争夺中("遭到反击")；{@code progress} 始终是朝 ALPHA 占领方向
     * 的绝对进度（复用 {@link CapturePoint#progressFor(Faction)}，与 ConquestMatch#focusFor 的
     * viewer 相对进度不同，突破模式没有"我方/敌方"相对概念——只有"是否已被进攻方拿下"）。
     */
    private FocusHud focusFor(ServerPlayer viewer) {
        if (currentSectorIndex >= sectors.size()) {
            return FocusHud.NONE;
        }
        Sector activeSector = sectors.get(currentSectorIndex);
        for (int i = 0; i < defs.size(); i++) {
            ControlPointDef def = defs.get(i);
            if (!activeSector.containsPoint(def.pointId())
                    || !def.zone().contains(viewer.getX(), viewer.getY(), viewer.getZ())) {
                continue;
            }
            CapturePoint point = points.get(i);
            int alpha = 0;
            int bravo = 0;
            for (Map.Entry<UUID, Faction> e : factionOf.entrySet()) {
                ServerPlayer p = player(e.getKey());
                if (p == null || p.level() != level || !p.isAlive() || p.isSpectator()
                        || downedUntil.containsKey(e.getKey())) {
                    continue;
                }
                if (def.zone().contains(p.getX(), p.getY(), p.getZ())) {
                    if (e.getValue() == Faction.ALPHA) {
                        alpha++;
                    } else {
                        bravo++;
                    }
                }
            }
            int progress = (int) Math.round(point.progressFor(Faction.ALPHA) * 100.0);
            if (alpha > 0 && bravo > 0) {
                return new FocusHud(point.displayName(), 3, progress);
            }
            if (point.owner() == Faction.ALPHA) {
                return new FocusHud(point.displayName(), 2, progress);
            }
            return new FocusHud(point.displayName(), 1, progress);
        }
        return FocusHud.NONE;
    }

    private record FocusHud(String name, int state, int progress) {
        private static final FocusHud NONE = new FocusHud("", 0, 0);
    }

    private List<SquadMateHudDto> squadHudFor(ServerPlayer viewer) {
        List<SquadMateHudDto> squad = new ArrayList<>();
        Integer squadId = squadManager.getSquadOf().get(viewer.getUUID());
        LinkedHashSet<UUID> members = squadId == null ? null : squadManager.getSquads().get(squadId);
        if (members == null || members.isEmpty()) {
            addSquadMate(squad, viewer, true, false);
            return squad;
        }
        UUID viewerId = viewer.getUUID();
        addSquadMate(squad, viewer, true, squadManager.isSquadLeader(viewerId));
        for (UUID mateId : members) {
            if (squad.size() >= squadManager.getSquadSize()) {
                break;
            }
            if (mateId.equals(viewerId)) {
                continue;
            }
            ServerPlayer mate = player(mateId);
            if (mate != null) {
                addSquadMate(squad, mate, false, squadManager.isSquadLeader(mateId));
            }
        }
        return squad;
    }

    private void addSquadMate(List<SquadMateHudDto> squad, ServerPlayer player, boolean self,
                              boolean isSquadLeader) {
        int hp = (int) Math.ceil((player.getHealth() / Math.max(1.0f, player.getMaxHealth())) * 100.0f);
        hp = Math.max(0, Math.min(100, hp));
        boolean downed = downedUntil.containsKey(player.getUUID());
        squad.add(new SquadMateHudDto(player.getGameProfile().getName(), hp,
                player.isAlive() || downed, self, downed, isSquadLeader,
                player.getX(), player.getZ()));
    }

    // ---- 呼吸回血 ----

    private void tickBreathHealing() {
        long now = server.getTickCount();
        for (UUID id : factionOf.keySet()) {
            ServerPlayer p = player(id);
            if (p == null || p.level() != level || !p.isAlive() || p.isSpectator()
                    || redeployService.isRedeploying(id)) {
                continue;
            }
            if (p.getHealth() >= p.getMaxHealth()) {
                p.removeEffect(MobEffects.REGENERATION);
                lastHurtTick.remove(id);
                continue;
            }
            long last = lastHurtTick.getOrDefault(id, now);
            if (now - last >= breathHealDelayOf(p)) {
                p.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 60, 1, false, false, true));
            }
        }
    }

    private void tickEscapeBoundary() {
        if (server.getTickCount() % 20L != 0L) {
            return;
        }
        org.shee33.act0.battlefield.core.BattleArea area = data.effectiveArea();
        if (!area.isSet()) {
            return;
        }
        for (Map.Entry<UUID, Faction> e : new ArrayList<>(factionOf.entrySet())) {
            UUID id = e.getKey();
            if (redeployService.isRedeploying(id)) {
                continue;
            }
            ServerPlayer p = player(id);
            if (p == null || !p.isAlive() || p.isSpectator()) {
                escapeTicks.remove(id);
                continue;
            }
            if (area.contains(p.getX(), p.getY(), p.getZ())) {
                escapeTicks.remove(id);
                continue;
            }
            int ticks = escapeTicks.merge(id, 20, Integer::sum);
            int remain = Math.max(0, escapeBoundaryTicks - ticks);
            if (remain <= 0) {
                Faction faction = factionOf.get(id);
                beginRedeploy(p, faction);
                escapeTicks.remove(id);
            } else if (ticks % 60 == 0) {
                p.displayClientMessage(Component.literal("§c⚠ 返回作战区域！" + (remain / 20) + " 秒后将被击杀"), true);
                p.playNotifySound(SoundEvents.NOTE_BLOCK_BASS.value(), SoundSource.MASTER, 0.6f, 1.0f);
            }
        }
    }

    // ---- 查询 ----

    public boolean isEnded() {
        return ended;
    }

    public boolean contains(UUID id) {
        return factionOf.containsKey(id);
    }

    public int totalMembers() {
        return factionOf.size();
    }

    public int capacityHint() {
        return BattlefieldConfig.MIN_PLAYERS_TO_START.get();
    }

    /** 某阵营剩余票数（取整）：进攻方(ALPHA)=攻击方剩余票数，防守方(BRAVO)没有票池永远返回0
     * (突破模式本就只有进攻方会因死亡损失票数)，供对局浏览器换算票数对峙条比例。 */
    public int displayTickets(Faction faction) {
        return faction == Faction.ALPHA ? (int) Math.ceil(attackerTickets) : 0;
    }

    /** 起始票数（取整），供对局浏览器换算票数对峙条比例。 */
    public int startingTicketsHint() {
        return rules.startingTickets();
    }

    /** 某阵营当前参战人数。 */
    public int memberCount(Faction faction) {
        int n = 0;
        for (Faction f : factionOf.values()) {
            if (f == faction) {
                n++;
            }
        }
        return n;
    }

    @Nullable
    public Faction factionOf(UUID id) {
        return factionOf.get(id);
    }

    @Nullable
    public Faction winner() {
        return winner;
    }

    public int attackerTickets() {
        return (int) attackerTickets;
    }

    public int currentSectorIndex() {
        return currentSectorIndex;
    }

    public int totalSectors() {
        return sectors.size();
    }

    public int killsOf(UUID id) {
        return killTracker.killsOf(id);
    }

    public int deathsOf(UUID id) {
        return killTracker.deathsOf(id);
    }

    public boolean isSquadLeader(UUID playerId) {
        return squadManager.isSquadLeader(playerId);
    }

    /** 两名玩家是否同一小队。 */
    public boolean isSameSquad(UUID a, UUID b) {
        return squadManager.isSameSquad(a, b);
    }

    public boolean canChangeLoadout(UUID id) {
        return factionOf.containsKey(id) && redeployService.isRedeploying(id);
    }

    // ---- 工具 ----

    @Nullable
    private ServerPlayer player(UUID id) {
        return server.getPlayerList().getPlayer(id);
    }

    private String nameOf(UUID id) {
        ServerPlayer p = player(id);
        return p != null ? p.getGameProfile().getName() : id.toString().substring(0, 8);
    }

    private String factionName(Faction faction) {
        return data.factionNames().name(faction);
    }

    private String coloredFaction(Faction faction) {
        return data.factionNames().colored(faction);
    }

    private void broadcast(String msg) {
        Component component = Component.literal(msg);
        for (UUID id : factionOf.keySet()) {
            ServerPlayer p = player(id);
            if (p != null) {
                p.sendSystemMessage(component);
            }
        }
    }

    private void sendFireLockToAll(boolean locked) {
        for (UUID id : factionOf.keySet()) {
            ServerPlayer p = player(id);
            if (p != null) {
                BattlefieldNetwork.sendFireLock(p, locked);
            }
        }
    }

    /** 向全体参战玩家广播"比赛开局"全屏黑屏转场，倒计时结束、COMBAT 阶段正式开始那一刻调用。 */
    private void sendMatchStartFxToAll() {
        for (UUID id : factionOf.keySet()) {
            ServerPlayer p = player(id);
            if (p != null) {
                BattlefieldNetwork.sendMatchStartFx(p);
            }
        }
    }

    private void showTitle(String title, String sub, int fadeIn, int stay, int fadeOut) {
        ClientboundSetTitlesAnimationPacket anim = new ClientboundSetTitlesAnimationPacket(fadeIn, stay, fadeOut);
        ClientboundSetTitleTextPacket titlePacket = new ClientboundSetTitleTextPacket(Component.literal(title));
        ClientboundSetSubtitleTextPacket subPacket = sub == null || sub.isBlank()
                ? null : new ClientboundSetSubtitleTextPacket(Component.literal(sub));
        for (UUID id : factionOf.keySet()) {
            ServerPlayer p = player(id);
            if (p != null) {
                p.connection.send(anim);
                p.connection.send(titlePacket);
                if (subPacket != null) {
                    p.connection.send(subPacket);
                }
            }
        }
    }

    private void sendTitle(ServerPlayer player, String title, String sub, int fadeIn, int stay, int fadeOut) {
        player.connection.send(new ClientboundSetTitlesAnimationPacket(fadeIn, stay, fadeOut));
        player.connection.send(new ClientboundSetTitleTextPacket(Component.literal(title)));
        if (sub != null && !sub.isBlank()) {
            player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal(sub)));
        }
    }

    private void playToAll(net.minecraft.sounds.SoundEvent sound, float pitch) {
        for (UUID id : factionOf.keySet()) {
            ServerPlayer p = player(id);
            if (p != null) {
                p.playNotifySound(sound, SoundSource.MASTER, 0.7f, pitch);
            }
        }
    }

    static int factionCode(@Nullable Faction faction) {
        if (faction == Faction.ALPHA) {
            return 1;
        }
        if (faction == Faction.BRAVO) {
            return 2;
        }
        return 0;
    }

    private record PendingDeath(UUID killerId, Faction killerFaction, Faction victimFaction) {
    }
}
