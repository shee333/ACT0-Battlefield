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
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;
import org.shee33.act0.battlefield.BattlefieldConfig;
import org.shee33.act0.battlefield.core.CapturePoint;
import org.shee33.act0.battlefield.core.ConquestRules;
import org.shee33.act0.battlefield.core.Faction;
import org.shee33.act0.battlefield.core.TicketPool;
import org.shee33.act0.battlefield.data.BattlefieldData;
import org.shee33.act0.battlefield.data.ControlPointDef;
import org.shee33.act0.battlefield.integration.ArcadeLoadoutBridge;
import org.shee33.act0.battlefield.integration.MatchResultBroadcaster;
import org.shee33.act0.battlefield.network.BattleHudDto;
import org.shee33.act0.battlefield.network.BattleResultDto;
import org.shee33.act0.battlefield.network.BattleTabDto;
import org.shee33.act0.battlefield.network.BattlefieldNetwork;
import org.shee33.act0.battlefield.network.CapturePointEventPacket;
import org.shee33.act0.battlefield.network.ControlPointHudDto;
import org.shee33.act0.battlefield.network.DeployPointDto;
import org.shee33.act0.battlefield.network.DeployStatusDto;
import org.shee33.act0.battlefield.network.DeploySquadMateDto;
import org.shee33.act0.battlefield.network.DownedActionPacket;
import org.shee33.act0.battlefield.network.DownedMateDto;
import org.shee33.act0.battlefield.network.SquadMateHudDto;
import org.shee33.act0.battlefield.network.TabEntryDto;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 一场征服对局：驱动据点争夺、票数流失、死亡接管与据点前进出生，直到一方票数归零。
 *
 * <p>每 {@link #captureInterval} 刻结算一次占点与流失：统计每个据点区域内双方人数推进
 * 争夺进度，按双方控制据点数差流失败方票数。死亡<b>不走原版死亡流程</b>（接管后立即满血并在出生点重新部署，
 * 扣除本方 1 票），避免死亡画面与丢装备，并支持在己方控制的据点前进出生。
 */
public final class ConquestMatch {

    // Config-driven values (read from BattlefieldConfig at construction time).
    private final int captureInterval;
    private final double captureDelta;
    private final int hudInterval;
    private final int iffSyncInterval;
    private static final int IFF_CHUNK_SIZE = 16;           // 16-block grid cells for spatial partitioning
    private static final int IFF_CHUNK_RADIUS = 6;          // ceil(ENEMY_MARK_DISTANCE / IFF_CHUNK_SIZE) = ceil(96/16)
    private final double enemyMarkDistance;
    private final double enemyMarkDistanceSqr;
    private final double enemyMarkViewDot;
    private final int breathHealDelayTicks;
    private final int escapeBoundaryTicks;
    private final int downedDurationTicks;
    private final int reviveDurationTicks;

    private final MinecraftServer server;
    private final ServerLevel level;
    private final ServerLevel lobbyLevel;
    private final ConquestRules rules;
    private final TicketPool tickets;
    private final List<ControlPointDef> defs;
    private final List<CapturePoint> points;
    private final Map<UUID, Faction> factionOf = new LinkedHashMap<>();
    private final SquadManager squadManager;
    private final KillTracker killTracker;
    private final RedeployService redeployService;
    private final Map<UUID, Long> lastHurtTick = new LinkedHashMap<>();
    private final List<PlayerTeam> nameTagTeams = new ArrayList<>();
    private final Map<UUID, Set<UUID>> visibleEnemyGlows = new LinkedHashMap<>();
    private final BattlefieldData data;
    private final Map<UUID, Integer> escapeTicks = new LinkedHashMap<>();
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
    private final Map<UUID, UUID> revivingTarget = new LinkedHashMap<>();
    private final Map<UUID, Long> revivingStarted = new LinkedHashMap<>();
    /** reviverId → 收到的最近一次客户端救援心跳所在 tick，见 {@link #handleReviveHeartbeat}。 */
    private final Map<UUID, Long> revivingHeartbeat = new LinkedHashMap<>();
    /** 救援朝向判定阈值：比 IFF 远距离标敌（{@link #enemyMarkViewDot}）更宽松，救援本就要求近距离
     * （≤4 格），只需大致朝向目标即可，不必是精确瞄准。 */
    private static final double REVIVE_VIEW_DOT = 0.5D;
    /** 救援心跳容忍窗口（tick）：超过这个时长没收到新心跳视为按键松开/掉线，避免网络抖动误取消。 */
    private static final int REVIVE_HEARTBEAT_TIMEOUT_TICKS = 10;
    private final Map<UUID, Long> spottedUntil = new LinkedHashMap<>();
    /** 标记目标"首次被标记"的tick，标记时长固定从这里起算——重复标记同一目标不会无限延长发光
     * 时间（P0安全修复：此前每次标记都把spottedUntil刷新为now+5s，恶意客户端持续spam可让
     * 敌方永久发光，见.omo审计报告）。 */
    private final Map<UUID, Long> spotFirstTick = new LinkedHashMap<>();
    /** 每个标记者的上次标记tick，用于节流（P0安全修复：SpotEnemyPacket此前完全无频率限制，
     * spam可打出O(N)次GlowSync同步的CPU/带宽放大攻击）。 */
    private final Map<UUID, Long> lastSpotTick = new LinkedHashMap<>();
    private static final long SPOT_DURATION_TICKS = 5 * 20;
    private static final long SPOT_MIN_INTERVAL_TICKS = 4;
    private final Map<UUID, Integer> captureTime = new LinkedHashMap<>();
    private final Map<UUID, PendingDeath> pendingDeaths = new LinkedHashMap<>();
    private final Map<UUID, Integer> lastHudHash = new LinkedHashMap<>();
    private final Map<UUID, Integer> lastTabHash = new LinkedHashMap<>();
    private final Map<UUID, Long> callHelpCooldownUntil = new LinkedHashMap<>();
    private static final int CALL_HELP_COOLDOWN_TICKS = 60;
    private final Map<Integer, Long> defendNotificationCooldown = new LinkedHashMap<>();
    private final Map<Integer, CapturePoint.CaptureStatus> lastCaptureStatus = new LinkedHashMap<>();

    private boolean ended;
    private boolean paused;
    @Nullable
    private Faction winner;
    private int captureAccum;
    private int hudAccum;
    private int iffAccum;
    private long startedTick;
    private int startCountdownTicks;
    private int startCountdownLastSecond = -1;
    /** 某一阵营人数清零起算的tick，-1表示当前非空。超过{@link #EMPTY_FACTION_TIMEOUT_TICKS}
     * 未恢复则判另一方获胜——此前只有两阵营都清空才会结束对局，单阵营清空(如ALPHA全体退出但
     * BRAVO还在)时对局会永久挂起(没人推进据点，纯靠票数流失又太慢)。 */
    private long alphaEmptySinceTick = -1L;
    private long bravoEmptySinceTick = -1L;
    private static final long EMPTY_FACTION_TIMEOUT_TICKS = 60 * 20;

    public ConquestMatch(ServerLevel level, ServerLevel lobbyLevel, ConquestRules rules,
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
        int squadSize = BattlefieldConfig.SQUAD_SIZE.get();
        int redeployDelayTicks = BattlefieldConfig.REDEPLOY_DELAY_TICKS.get();
        int spawnProtectionTicks = BattlefieldConfig.SPAWN_PROTECTION_TICKS.get();
        double squadDeployEnemyBlockRadius = BattlefieldConfig.SQUAD_DEPLOY_ENEMY_BLOCK_RADIUS.get();
        this.iffSyncInterval = BattlefieldConfig.IFF_SYNC_INTERVAL.get();
        this.enemyMarkDistance = BattlefieldConfig.ENEMY_MARK_DISTANCE.get();
        this.enemyMarkDistanceSqr = this.enemyMarkDistance * this.enemyMarkDistance;
        this.enemyMarkViewDot = BattlefieldConfig.ENEMY_MARK_VIEW_DOT.get();
        this.breathHealDelayTicks = BattlefieldConfig.BREATH_HEAL_DELAY_TICKS.get();
        this.escapeBoundaryTicks = BattlefieldConfig.ESCAPE_BOUNDARY_TICKS.get();
        this.downedDurationTicks = BattlefieldConfig.DOWNED_DURATION_TICKS.get();
        this.reviveDurationTicks = BattlefieldConfig.REVIVE_DURATION_TICKS.get();
        this.tickets = new TicketPool(rules.startingTickets());
        this.defs = new ArrayList<>(defs);
        this.points = new ArrayList<>(defs.size());
        for (ControlPointDef def : this.defs) {
            this.points.add(new CapturePoint(def.pointId(), def.name()));
        }
        this.factionOf.putAll(roster);
        this.killTracker = new KillTracker(this.factionOf, this.server, this.points, this.defs);
        for (UUID id : roster.keySet()) {
            killTracker.initPlayer(id);
        }
        this.squadManager = new SquadManager(squadSize, factionOf);
        squadManager.buildSquads();
        squadManager.initDeployContext(this::player, level, downedUntil, squadDeployEnemyBlockRadius);
        this.redeployService = new RedeployService(level, data, factionOf, squadManager, points, defs,
                downedUntil, escapeTicks, lastHurtTick, this::cancelRevive,
                spawnProtectionTicks, redeployDelayTicks, "征服模式");
    }
    /** 开局：把所有参战玩家部署到各自基地。 */
    public void begin() {
        startCountdownTicks = BattlefieldConfig.START_COUNTDOWN_TICKS.get();
        startCountdownLastSecond = -1;
        setupNameTagTeams();
        for (Map.Entry<UUID, Faction> e : factionOf.entrySet()) {
            ServerPlayer p = player(e.getKey());
            if (p != null) {
                deploy(p, e.getValue());
                BattlefieldNetwork.sendFireLock(p, true);
                p.sendSystemMessage(Component.literal("§6大战场即将开始！你属于 " + e.getValue().coloredName()
                        + "§6，占领据点压制敌方票数。"));
            }
        }
        showTitle("§e准备", "§7大战场将在 5 秒后开始", 5, 30, 8);
        broadcastHud();
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
            // 开局倒计时期间加入：直接部署到基地等待倒计时结束(与begin()对初始名单的处理一致)，
            // 不走beginRedeploy()的"重生选点"观战流程——那是为死亡玩家设计的语义，此前误用在
            // "第一次加入"上会导致中途加入的玩家卡在观战模式，需要自己手动选点才能真正进场。
            deploy(player, faction);
            BattlefieldNetwork.sendFireLock(player, true);
        } else {
            beginRedeploy(player, faction);
        }
        broadcast("§b" + player.getGameProfile().getName() + " §7加入了 " + faction.coloredName() + "§7。");
        broadcastHud();
        return true;
    }

    public boolean quitPlayer(ServerPlayer player) {
        UUID id = player.getUUID();
        Faction faction = factionOf.remove(id);
        if (faction == null) {
            return false;
        }
        clearRelativeTeamsFor(player);
        clearEnemyGlowFor(player);
        clearEnemyGlowTarget(player);
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
        lastTabHash.remove(id);
        callHelpCooldownUntil.remove(id);
        squadManager.removeMember(id);
        setupNameTagTeams();
        broadcast("§e" + player.getGameProfile().getName() + " §7退出了本对局。");
        player.sendSystemMessage(Component.literal("§7已退出大战场。"));
        if (factionOf.isEmpty()) {
            ended = true;
            clearAllEnemyGlows();
            clearAllRelativeTeams();
            clearNameTagTeams();
        } else {
            broadcastHud();
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
        if (ended || paused) {
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
            resolveCaptureAndBleed();
            // resolveCaptureAndBleed() 可能在票数耗尽时调用 end()（内部已做全部清理，包括
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
        tickSpotted();
        if (ended) {
            return;
        }
        if (++hudAccum >= hudInterval) {
            hudAccum = 0;
            broadcastHud();
        }
    }

    private void resolveCaptureAndBleed() {
        for (int i = 0; i < points.size(); i++) {
            CapturePoint point = points.get(i);
            AABB zone = defs.get(i).zone();
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
                    if (point.owner() == e.getValue()) {
                        captureTime.merge(e.getKey(), captureInterval, Integer::sum);
                    }
                }
            }
            // Comeback boost: when a faction has <70% of its starting tickets, its in-zone count
            // counts as 1.5x for CapturePoint.tick, letting the trailing side flip points faster.
            double maxTickets = Math.max(1.0, rules.startingTickets());
            int alphaEffective = alpha;
            int bravoEffective = bravo;
            if (tickets.tickets(Faction.ALPHA) / maxTickets < 0.7) {
                alphaEffective = (int) Math.ceil(alpha * 1.5);
            }
            if (tickets.tickets(Faction.BRAVO) / maxTickets < 0.7) {
                bravoEffective = (int) Math.ceil(bravo * 1.5);
            }
            int pointId = defs.get(i).pointId();
            Faction ownerBeforeTick = point.owner();
            CapturePoint.CaptureStatus prevStatus = lastCaptureStatus.getOrDefault(
                    pointId, CapturePoint.CaptureStatus.IDLE);
            boolean wasActiveContest = prevStatus == CapturePoint.CaptureStatus.CONTESTED
                    || prevStatus == CapturePoint.CaptureStatus.CAPTURING
                    || prevStatus == CapturePoint.CaptureStatus.NEUTRALIZED;
            CapturePoint.CaptureStatus st = point.tick(alphaEffective, bravoEffective, rules, captureDelta);
            lastCaptureStatus.put(pointId, st);
            if (st == CapturePoint.CaptureStatus.CAPTURED) {
                Faction owner = point.owner();
                if (owner != null) {
                    broadcast(owner.coloredName() + " §7占领了据点 §e" + point.displayName());
                    playToAll(SoundEvents.NOTE_BLOCK_BELL.value(), 1.0f);
                    actionBarNear(point.displayName(), zone, owner.coloredName() + " §a已控制 " + point.displayName());
                    rewardAttackOrder(defs.get(i).pointId(), owner);
                    CapturePointEventPacket.Kind kind = ownerBeforeTick == null
                            ? CapturePointEventPacket.Kind.CAPTURED_NEW
                            : CapturePointEventPacket.Kind.CAPTURED_RECOVERED;
                    sendCapturePointEvent(pointId, kind, factionCode(owner));
                    Vec3 fxPos = zone.getCenter();
                    BattlefieldFx.captureBurst(level, fxPos.x, fxPos.y, fxPos.z, owner);
                }
            } else if (st == CapturePoint.CaptureStatus.NEUTRALIZED) {
                broadcast("§7据点 §e" + point.displayName() + " §7已被中立化");
                playToAll(SoundEvents.NOTE_BLOCK_BASS.value(), 0.7f);
                clearDefendOrder(defs.get(i).pointId());
                sendCapturePointEvent(pointId, CapturePointEventPacket.Kind.LOST, factionCode(ownerBeforeTick));
                Vec3 fxPos = zone.getCenter();
                BattlefieldFx.lost(level, fxPos.x, fxPos.y, fxPos.z);
            } else if (st == CapturePoint.CaptureStatus.CONTESTED) {
                playNear(point.displayName(), zone, SoundEvents.NOTE_BLOCK_HAT.value(), 0.4f);
                notifyDefendOrder(point, defs.get(i).pointId());
                if (!wasActiveContest) {
                    sendCapturePointEvent(pointId, CapturePointEventPacket.Kind.STARTED, 0);
                    Vec3 fxPos = zone.getCenter();
                    BattlefieldFx.contestStart(level, fxPos.x, fxPos.y, fxPos.z);
                }
            } else if (st == CapturePoint.CaptureStatus.CAPTURING) {
                Faction pushing = alpha > 0 ? Faction.ALPHA : Faction.BRAVO;
                actionBarNear(point.displayName(), zone, pushing.coloredName() + " §7正在占领 " + point.displayName());
                if (!wasActiveContest) {
                    sendCapturePointEvent(pointId, CapturePointEventPacket.Kind.STARTED, factionCode(pushing));
                    Vec3 fxPos = zone.getCenter();
                    BattlefieldFx.contestStart(level, fxPos.x, fxPos.y, fxPos.z);
                }
            }
        }

        int alphaPoints = ownedCount(Faction.ALPHA);
        int bravoPoints = ownedCount(Faction.BRAVO);
        tickets.bleed(alphaPoints, bravoPoints, rules, captureDelta);

        Faction w = tickets.winner();
        if (w != null) {
            end(w);
        }
    }

    private void rewardAttackOrder(int pointId, Faction capturer) {
        for (Map.Entry<Integer, SquadManager.SquadOrder> e : squadManager.getActiveOrders().entrySet()) {
            SquadManager.SquadOrder order = e.getValue();
            if (order.attack() && order.pointId() == pointId) {
                LinkedHashSet<UUID> members = squadManager.getSquads().get(e.getKey());
                if (members != null && !members.isEmpty()) {
                    UUID first = members.iterator().next();
                    if (factionOf.get(first) == capturer) {
                        squadBroadcast(e.getKey(), "§6★ 小队完成了攻击命令！据点已占领。");
                        tickets.addTickets(capturer, 5);
                        squadBroadcast(e.getKey(), "§a" + capturer.coloredName() + " §7获得 +5 票数奖励。");
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

    private void tickStartCountdown() {
        int secs = Math.max(0, (int) Math.ceil(startCountdownTicks / 20.0));
        if (secs != startCountdownLastSecond) {
            startCountdownLastSecond = secs;
            if (secs > 0) {
                showTitle("§e§l" + secs, "§7准备进入大战场", 0, 16, 4);
                playToAll(SoundEvents.NOTE_BLOCK_HAT.value(), 1.0f + (5 - secs) * 0.12f);
            }
        }
        startCountdownTicks--;
        if (startCountdownTicks <= 0) {
            startCountdownTicks = 0;
            startedTick = server.getTickCount();
            sendFireLockToAll(false);
            sendMatchStartFxToAll();
            showTitle("§a§l战斗开始", "", 2, 24, 8);
            playToAll(SoundEvents.PLAYER_LEVELUP, 1.0f);
            broadcast("§a大战场正式开始！");
        }
    }

    // ---- 死亡接管 ----

    /**
    * 接管一名参战玩家的死亡：取消原版死亡，切换到旁观者部署等待，倒计时结束后由部署界面选择出生点。
     *
     * @return 是否由本对局接管（调用方据此取消原版死亡）
     */
    public boolean onDeath(UUID victimId, @Nullable UUID killerId) {
        if (ended) {
            return false;
        }
        // 防止双倒：已倒地的玩家不再触发
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
        // 延迟死亡判定：不在此扣票/计死亡/计击杀
        // 将 PendingDeath 暂存，放血或放弃时 consumePendingDeath 消费
        Faction killerFaction = killerId != null ? factionOf.get(killerId) : null;
        pendingDeaths.put(victimId, new PendingDeath(killerId, killerFaction, f));
        lastHurtTick.remove(victimId);
        // 重置受害者连杀（在倒地时而不是放血时）
        killTracker.onDowned(victimId, killerId);
        Faction w = tickets.winner();
        if (w != null) {
            end(w);
        } else {
            broadcastHud();
        }
        return true;
    }

    /** 消费一笔待处理死亡：扣票、计死亡、计击杀。在放血或放弃时调用。 */
    private void consumePendingDeath(UUID victimId) {
        PendingDeath pending = pendingDeaths.remove(victimId);
        if (pending == null) {
            return;
        }
        Faction victimFaction = pending.victimFaction();
        killTracker.recordDeath(victimId);
        tickets.onDeath(victimFaction, rules);
        killTracker.handleKillCredit(victimId, pending.killerId());
    }

    public void onHurt(UUID victimId, @Nullable UUID attackerId) {
        if (ended || !factionOf.containsKey(victimId)) {
            return;
        }
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

    public boolean isEnemyHit(UUID victimId, @Nullable UUID attackerId) {
        if (victimId == null || attackerId == null || victimId.equals(attackerId)) {
            return false;
        }
        Faction victimFaction = factionOf.get(victimId);
        Faction attackerFaction = factionOf.get(attackerId);
        return victimFaction != null && attackerFaction != null && victimFaction != attackerFaction;
    }


    /** 是否应取消某玩家受到的伤害：部署中/出生保护/友伤/倒地均取消。 */
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
        if (!redeployService.isRedeploying(id)) {
            player.setInvulnerable(false);
            BattlefieldNetwork.sendBattleHud(player, buildHudFor(player));
            BattlefieldNetwork.sendBattleTab(player, buildTabFor(player));
        }
    }

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

    public void handleDeployAction(ServerPlayer player, String kind) {
        redeployService.handleDeployAction(player, kind);
    }

    public void refreshDeployStatus(ServerPlayer player) {
        redeployService.refreshDeployStatus(player);
    }

    public void handleDeployAction(ServerPlayer player, String kind, String targetId) {
        redeployService.handleDeployAction(player, kind, targetId);
    }

    public void handleDeploySlotOverride(ServerPlayer player, int slotIndex, String itemName) {
        redeployService.handleSlotOverride(player, slotIndex, itemName);
    }

    private void deploy(ServerPlayer p, Faction f) {
        redeployService.deployDirect(p, f);
    }

    private void clearRedeployState(ServerPlayer player, boolean restoreOriginalMode) {
        redeployService.clearRedeployState(player, restoreOriginalMode);
    }

    /** Squad respawn point: living squadmate if available. */
    @Nullable
    private BattlefieldData.BaseSpawn livingSquadmateSpawn(UUID self) {
        return squadManager.livingSquadmateSpawn(self);
    }

    /**
     * 据点前进出生：在己方控制且最靠近敌方基地的据点上方出生；无己方据点或无敌方基地则回退到基地。
     */
    @Nullable
    private BattlefieldData.BaseSpawn forwardSpawn(Faction f) {
        return redeployService.forwardSpawn(f);
    }

    // ---- 结束 ----

    private void end(Faction w) {
        // 幂等性守卫：同一tick内两名玩家的死亡结算都可能触发end()(如consumePendingDeath的票数
        // 判负检查)，缺这层守卫会导致双重广播/双重传送/双重结算包(与abort()和
        // BreakthroughMatch.end()现有写法保持一致)。
        if (ended) {
            return;
        }
        this.ended = true;
        this.winner = w;
        // 先解散阵营名牌 Scoreboard 队伍：一旦 ended=true，管理器下一 tick 就会把这场对局从
        // activeByWorld 中摘除并丢弃引用；如果下面逐人结算（网络发包/传送）抛出异常，必须保证
        // 这行已经执行过，否则队伍会永久残留在服务器 Scoreboard 里，再也没有代码路径能碰到它。
        clearNameTagTeams();
        broadcast("§6§l对局结束：" + w.coloredName() + " §6§l取得票数压制。");
        broadcastServerResult(w);
        broadcastMatchResult(w);
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
            }
        }
        redeployService.clearAll();
        lastHurtTick.clear();
        escapeTicks.clear();
        downedUntil.clear();
        downedLastGoodY.clear();
        downedInventoryCache.clear();
        pendingDeaths.clear();
        revivingTarget.clear();
        revivingStarted.clear();
        revivingHeartbeat.clear();
        killTracker.clearTransient();
        spottedUntil.clear();
        spotFirstTick.clear();
        lastSpotTick.clear();
        captureTime.clear();
        callHelpCooldownUntil.clear();
        // 此前end()/abort()都没清这4个map，跨对局残留可能导致下一局误报"防御通知"冷却、
        // HUD/Tab首帧因hash碰撞不刷新。
        defendNotificationCooldown.clear();
        lastCaptureStatus.clear();
        lastHudHash.clear();
        lastTabHash.clear();
        clearAllEnemyGlows();
        clearAllRelativeTeams();
        sendFireLockToAll(false);
    }

    private void sendPersonalResult(ServerPlayer player, Faction mine, Faction winner) {
        boolean won = mine == winner;
        sendTitle(player, won ? "§a§l胜利" : "§c§l失败",
                winner.coloredName() + " §7取得胜利", 5, 60, 15);
        UUID id = player.getUUID();
        player.sendSystemMessage(Component.literal("§6战报 §8| " + (won ? "§a胜利" : "§c失败")
                + " §8| §7剩余票数 §9北大西洋公约 §f" + tickets.displayTickets(Faction.ALPHA)
                + " §8/ §c无邦军团 §f" + tickets.displayTickets(Faction.BRAVO)
            + " §8| §7你的 K/D §e" + killTracker.killsOf(id) + "§7/§c" + killTracker.deathsOf(id)));
    }

    private void broadcastServerResult(Faction winner) {
        TopKiller top = topKiller();
        String mvp = top.kills() > 0 ? " §8| §7击杀王 §e" + top.name() + " §7(" + top.kills() + "杀)" : "";

        String cap = "";
        int capTime = 0;
        for (Map.Entry<UUID, Integer> e : captureTime.entrySet()) {
            if (e.getValue() > capTime) { capTime = e.getValue(); ServerPlayer p = player(e.getKey()); cap = p != null ? p.getGameProfile().getName() : "?"; }
        }
        String cp = capTime > 0 ? " §8| §7占点王 §e" + cap + " §7(" + (capTime / 20) + "秒)" : "";

        int bestK = 0, bestId = 0;
        for (Map.Entry<Integer, LinkedHashSet<UUID>> e : squadManager.getSquads().entrySet()) {
            int t = e.getValue().stream().mapToInt(uid -> killTracker.killsOf(uid)).sum();
            if (t > bestK) { bestK = t; bestId = e.getKey(); }
        }
        String sq = bestK > 0 ? " §8| §7最佳小队 §e第" + displaySquad(bestId) + "小队 §7(" + bestK + "杀)" : "";

        Component message = Component.literal("§6[ACT0赛果] §f大战场 · 征服 §8| §a"
                + winner.displayName() + " §7胜出 §8| §7票数 §9北大西洋公约 §f"
                + tickets.displayTickets(Faction.ALPHA) + " §8/ §c无邦军团 §f"
                + tickets.displayTickets(Faction.BRAVO) + mvp + cp + sq);
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            online.sendSystemMessage(message);
        }
    }

    private void broadcastMatchResult(Faction winner) {
        TopKiller top = topKiller();
        MatchResultBroadcaster.sendBattlefieldResult(
                battleId(),
                "大战场",
                elapsedSeconds(),
                winner.displayName(),
                List.of(winner.displayName()),
                top.name(),
                top.kills(),
                "北大西洋公约 " + tickets.displayTickets(Faction.ALPHA)
                        + " / 无邦军团 " + tickets.displayTickets(Faction.BRAVO));
    }

    private String battleId() {
        return level.dimension().location() + ":" + startedTick;
    }

    private TopKiller topKiller() {
        UUID topId = null;
        int topKills = -1;
        for (Map.Entry<UUID, Integer> e : killTracker.getKills().entrySet()) {
            int value = e.getValue() == null ? 0 : e.getValue();
            if (topId == null || value > topKills
                    || (value == topKills && nameOf(e.getKey()).compareToIgnoreCase(nameOf(topId)) < 0)) {
                topId = e.getKey();
                topKills = value;
            }
        }
        if (topId == null) {
            return new TopKiller("暂无击杀王", 0);
        }
        return new TopKiller(nameOf(topId), Math.max(0, topKills));
    }

    private String nameOf(UUID id) {
        ServerPlayer p = player(id);
        return p != null ? p.getGameProfile().getName() : id.toString().substring(0, 8);
    }

    private static int displaySquad(int squadId) {
        return squadId >= 101 ? squadId - 100 : Math.max(0, squadId);
    }

    private record TopKiller(String name, int kills) {
    }

    private record PendingDeath(UUID killerId, Faction killerFaction, Faction victimFaction) {
    }

    private BattleResultDto buildResultFor(ServerPlayer viewer, Faction winner) {
        List<TabEntryDto> entries = new ArrayList<>();
        for (Map.Entry<UUID, Faction> e : factionOf.entrySet()) {
            UUID id = e.getKey();
            ServerPlayer p = player(id);
            String name = p != null ? p.getGameProfile().getName() : id.toString().substring(0, 8);
            int ping = p != null ? p.latency : -1;
            entries.add(new TabEntryDto(name, factionCode(e.getValue()),
                    killTracker.killsOf(id), killTracker.deathsOf(id), ping, p == null ? 2 : (downedUntil.containsKey(id) ? 3 : 0),
                    displaySquad(squadManager.getSquadOf().getOrDefault(id, 0))));
        }
        entries.sort(Comparator
                .comparingInt(TabEntryDto::kills).reversed()
                .thenComparingInt(TabEntryDto::deaths)
                .thenComparing(TabEntryDto::name));
        UUID viewerId = viewer.getUUID();

        String topCapturer = "";
        int topCapturerTime = 0;
        for (Map.Entry<UUID, Integer> e : captureTime.entrySet()) {
            if (e.getValue() > topCapturerTime) {
                topCapturerTime = e.getValue();
                ServerPlayer p = player(e.getKey());
                topCapturer = p != null ? p.getGameProfile().getName() : "?";
            }
        }

        int bestSquadKills = 0;
        int bestSquadId = 0;
        for (Map.Entry<Integer, LinkedHashSet<UUID>> e : squadManager.getSquads().entrySet()) {
            int total = e.getValue().stream().mapToInt(id -> killTracker.killsOf(id)).sum();
            if (total > bestSquadKills) { bestSquadKills = total; bestSquadId = e.getKey(); }
        }
        String bestSquad = bestSquadId > 0 ? "第" + displaySquad(bestSquadId) + "小队" : "";

        return new BattleResultDto(factionCode(winner), factionCode(factionOf.get(viewerId)),
                tickets.displayTickets(Faction.ALPHA), tickets.displayTickets(Faction.BRAVO),
                killTracker.killsOf(viewerId), killTracker.deathsOf(viewerId), entries,
                topCapturer, topCapturerTime / 20, bestSquad, bestSquadKills,
                elapsedSeconds(), 0, 0);
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
            }
        }
        redeployService.clearAll();
        lastHurtTick.clear();
        escapeTicks.clear();
        downedUntil.clear();
        downedLastGoodY.clear();
        downedInventoryCache.clear();
        pendingDeaths.clear();
        revivingTarget.clear();
        revivingStarted.clear();
        revivingHeartbeat.clear();
        killTracker.clearTransient();
        spottedUntil.clear();
        spotFirstTick.clear();
        lastSpotTick.clear();
        captureTime.clear();
        callHelpCooldownUntil.clear();
        // 此前end()/abort()都没清这4个map，跨对局残留可能导致下一局误报"防御通知"冷却、
        // HUD/Tab首帧因hash碰撞不刷新。
        defendNotificationCooldown.clear();
        lastCaptureStatus.clear();
        lastHudHash.clear();
        lastTabHash.clear();
        clearAllEnemyGlows();
        clearAllRelativeTeams();
        sendFireLockToAll(false);
    }

    // ---- 敌我识别 ----

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
        String base = "bf" + Integer.toHexString(System.identityHashCode(this)) + suffix;
        return base.length() <= 16 ? base : base.substring(0, 16);
    }

    /**
     * IFF (Identify Friend/Foe) sync: manages per-player enemy glow visibility.
     *
     * <p>Uses a 16-block chunk-based spatial index to reduce O(n²) ray tracing.
     * For each viewer, only targets within {@code IFF_CHUNK_RADIUS} chunks (6 × 16 = 96 blocks,
     * matching {@code ENEMY_MARK_DISTANCE}) undergo the expensive ray-trace visibility check.
     * Far-away targets still receive friendly glow (cheap, no ray trace) to preserve
     * unlimited-range squad/faction identification.
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
            return downedUntil.containsKey(id)
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
            if (now - last >= breathHealDelayTicks) {
                p.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 60, 1, false, false, true));
            }
        }
    }

    // ---- HUD ----

    private void broadcastHud() {
        for (UUID id : factionOf.keySet()) {
            ServerPlayer p = player(id);
            if (p == null) {
                continue;
            }

            BattleHudDto hud = buildHudFor(p);
            int hudHash = Objects.hash(
                    hud.alphaTickets(), hud.bravoTickets(),
                    hud.points().hashCode(), hud.focusState(), hud.focusProgress(),
                    hud.squad().hashCode(), hud.downedMates().hashCode(),
                    hud.revivingName(), hud.revivingProgress(), hud.isSquadLeader(), hud.streak(), hud.focusFaction(),
                    hud.squadOrderPointId(), hud.squadOrderAttack());
            Integer prevHudHash = lastHudHash.get(id);
            if (prevHudHash == null || prevHudHash != hudHash) {
                BattlefieldNetwork.sendBattleHud(p, hud);
                lastHudHash.put(id, hudHash);
            }

            BattleTabDto tab = buildTabFor(p);
            int tabHash = Objects.hash(
                    tab.alphaTickets(), tab.bravoTickets(), tab.myFaction(),
                    tab.alpha().hashCode(), tab.bravo().hashCode());
            Integer prevTabHash = lastTabHash.get(id);
            if (prevTabHash == null || prevTabHash != tabHash) {
                BattlefieldNetwork.sendBattleTab(p, tab);
                lastTabHash.put(id, tabHash);
            }
        }
    }

    private BattleTabDto buildTabFor(ServerPlayer viewer) {
        List<TabEntryDto> alpha = new ArrayList<>();
        List<TabEntryDto> bravo = new ArrayList<>();
        for (Map.Entry<UUID, Faction> e : factionOf.entrySet()) {
            UUID id = e.getKey();
            ServerPlayer p = player(id);
            String name = p != null ? p.getGameProfile().getName() : id.toString().substring(0, 8);
            int state = p == null ? 2 : (downedUntil.containsKey(id) ? 3 :
                    (p.isSpectator() || redeployService.isRedeploying(id) ? 1 : 0));
            int ping = p != null ? p.latency : -1;
            int sq = displaySquad(squadManager.getSquadOf().getOrDefault(id, 0));
            TabEntryDto dto = new TabEntryDto(name, factionCode(e.getValue()),
                    killTracker.killsOf(id), killTracker.deathsOf(id), ping, state, sq);
            if (e.getValue() == Faction.ALPHA) {
                alpha.add(dto);
            } else {
                bravo.add(dto);
            }
        }
        Comparator<TabEntryDto> order = Comparator
                .comparingInt(TabEntryDto::kills).reversed()
                .thenComparingInt(TabEntryDto::deaths)
                .thenComparing(TabEntryDto::name);
        alpha.sort(order);
        bravo.sort(order);
        return new BattleTabDto(factionCode(factionOf.get(viewer.getUUID())),
                tickets.displayTickets(Faction.ALPHA), tickets.displayTickets(Faction.BRAVO), alpha, bravo);
    }

    private BattleHudDto buildHudFor(ServerPlayer viewer) {
        List<ControlPointHudDto> pointDtos = new ArrayList<>(points.size());
        for (int i = 0; i < points.size(); i++) {
            CapturePoint point = points.get(i);
            ControlPointDef def = defs.get(i);
            int owner = factionCode(point.owner());
            int pressure = point.level() > 0.02 ? 1 : (point.level() < -0.02 ? 2 : 0);
            int progress = Math.min(100, Math.max(0, (int) Math.round(Math.abs(point.level()) * 100.0)));
            pointDtos.add(new ControlPointHudDto(point.displayName(), owner, pressure, progress,
                    def.pos().getX() + 0.5 + def.markerOffsetX(),
                    def.pos().getY() + def.markerOffsetY(),
                    def.pos().getZ() + 0.5 + def.markerOffsetZ(),
                    def.markerScale(), def.markerDistance(), def.pointId()));
        }

        Faction viewerFaction = factionOf.get(viewer.getUUID());
        List<SquadMateHudDto> squad = squadHudFor(viewer);
        FocusHud focus = focusFor(viewer, viewerFaction);
        List<DownedMateDto> downedMates = getDownedMateDtos(viewer.getUUID());
        String revivingName = getRevivingName(viewer.getUUID());
        int revivingProgress = getRevivingProgress(viewer.getUUID());
        String beingRevivedByName = getBeingRevivedByName(viewer.getUUID());
        int beingRevivedProgress = getBeingRevivedProgress(viewer.getUUID());

        return new BattleHudDto(
                factionCode(viewerFaction),
                tickets.displayTickets(Faction.ALPHA),
                tickets.displayTickets(Faction.BRAVO),
                Math.max(1, (int) Math.ceil(rules.startingTickets())),
                pointDtos,
                squad,
                focus.name(), focus.state(), focus.progress(), focus.faction(),
                downedMates, revivingName != null ? revivingName : "", revivingProgress,
                beingRevivedByName != null ? beingRevivedByName : "", beingRevivedProgress,
                squadManager.isSquadLeader(viewer.getUUID()),
                killTracker.killStreakOf(viewer.getUUID()),
                squadOrderPointId(viewer.getUUID()), squadOrderAttack(viewer.getUUID()));
    }

    private int squadOrderPointId(UUID playerId) {
        Integer squadId = squadManager.getSquadOf().get(playerId);
        if (squadId == null) return 0;
        SquadManager.SquadOrder order = squadManager.getOrder(squadId);
        return order != null ? order.pointId() : 0;
    }

    private boolean squadOrderAttack(UUID playerId) {
        Integer squadId = squadManager.getSquadOf().get(playerId);
        if (squadId == null) return false;
        SquadManager.SquadOrder order = squadManager.getOrder(squadId);
        return order != null && order.attack();
    }

    private List<SquadMateHudDto> squadHudFor(ServerPlayer viewer) {
        List<SquadMateHudDto> squad = new ArrayList<>();
        Integer squadId = squadManager.getSquadOf().get(viewer.getUUID());
        LinkedHashSet<UUID> members = squadId == null ? null : squadManager.getSquads().get(squadId);
        if (members == null || members.isEmpty()) {
            addSquadMate(squad, viewer, true, false);
            return squad;
        }
        // 自己固定排第一。
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

    private FocusHud focusFor(ServerPlayer viewer, @Nullable Faction viewerFaction) {
        if (viewerFaction == null) {
            return FocusHud.NONE;
        }
        for (int i = 0; i < defs.size(); i++) {
            ControlPointDef def = defs.get(i);
            if (!def.zone().contains(viewer.getX(), viewer.getY(), viewer.getZ())) {
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
            boolean contested = alpha > 0 && bravo > 0;
            int progress = captureProgressFor(point, viewerFaction);
            if (contested) {
                return new FocusHud(point.displayName(), 3, progress, 0);
            }
            if (point.owner() == viewerFaction) {
                return new FocusHud(point.displayName(), 2, progress, factionCode(viewerFaction));
            }
            return new FocusHud(point.displayName(), 1, progress, factionCode(viewerFaction));
        }
        return FocusHud.NONE;
    }

    /** 从“敌方满控/中立/己方满控”映射为 0~100 的本地占领进度。 */
    private static int captureProgressFor(CapturePoint point, Faction faction) {
        double signed = faction == Faction.ALPHA ? point.level() : -point.level();
        double normalized = (signed + 1.0) * 0.5;
        return Math.max(0, Math.min(100, (int) Math.round(normalized * 100.0)));
    }

    private record FocusHud(String name, int state, int progress, int faction) {
        private static final FocusHud NONE = new FocusHud("", 0, 0, 0);
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

    static int factionCode(@Nullable Faction faction) {
        if (faction == Faction.ALPHA) {
            return 1;
        }
        if (faction == Faction.BRAVO) {
            return 2;
        }
        return 0;
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

    private void actionBarNear(String pointName, AABB zone, String msg) {
        for (UUID id : factionOf.keySet()) {
            ServerPlayer p = player(id);
            if (p != null && zone.contains(p.getX(), p.getY(), p.getZ())) {
                p.displayClientMessage(Component.literal(msg), true);
            }
        }
    }

    private void playNear(String pointName, AABB zone, net.minecraft.sounds.SoundEvent sound, float pitch) {
        for (UUID id : factionOf.keySet()) {
            ServerPlayer p = player(id);
            if (p != null && zone.contains(p.getX(), p.getY(), p.getZ())) {
                p.playNotifySound(sound, SoundSource.MASTER, 0.4f, pitch);
            }
        }
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
                p.connection.teleport(p.getX(), p.getY(), p.getZ(), yaw, pitch);
            }
        }
        String killerName = killerId != null ? nameOf(killerId) : "未知";
        p.sendSystemMessage(Component.literal("§c你被 " + killerName + " 击倒了！§7长按空格放弃 · 队友按住F瞄准你即可救援"));
        p.displayClientMessage(Component.literal("§c§l倒地！等待队友救援"), true);
        BattlefieldNetwork.sendDownedFeedback(p);
        BattlefieldFx.downed(level, p.getX(), p.getY(), p.getZ());
        for (UUID mateId : factionOf.keySet()) {
            if (mateId.equals(id)) continue;
            if (factionOf.get(mateId) == f) {
                ServerPlayer mate = player(mateId);
                if (mate != null) {
                    mate.displayClientMessage(Component.literal("§c" + p.getGameProfile().getName() + " 倒地 · 按住F瞄准救援"), true);
                }
            }
        }
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
                p.connection.teleport(p.getX(), lastGoodY, p.getZ(), p.getYRot(), p.getXRot());
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
            } else {
                int secs = (int) Math.max(1, (e.getValue() - now) / 20);
                if ((e.getValue() - now) % 20L == 0L) {
                    ServerPlayer p = player(id);
                    if (p != null) {
                        p.displayClientMessage(Component.literal("§c倒地 " + secs + " 秒后阵亡..."), true);
                    }
                }
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
                    || target.distanceToSqr(reviver) > 16.0D || !isInFrontOf(reviver, target, REVIVE_VIEW_DOT)) {
                toCancel.add(reviverId);
                continue;
            }
            Long lastHeartbeat = revivingHeartbeat.get(reviverId);
            if (lastHeartbeat == null || now - lastHeartbeat > REVIVE_HEARTBEAT_TIMEOUT_TICKS) {
                toCancel.add(reviverId);
                continue;
            }
            if (now >= e.getValue() + reviveDurationTicks) {
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
            ServerPlayer target = player(targetId);
            ServerPlayer reviver = player(reviverId);
            if (target != null && reviver != null) {
                downedUntil.remove(targetId);
                downedLastGoodY.remove(targetId);
                pendingDeaths.remove(targetId); // 扶起退款
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
            }
        }
    }

    /**
     * 中断某人正在进行的救援。幂等：只有确实存在进行中的救援才会提示"救援中断"——否则
     * 每个从未救人的玩家在退出/倒地时都会莫名收到一条中断提示。
     */
    private void cancelRevive(UUID reviverId) {
        revivingTarget.remove(reviverId);
        boolean wasReviving = revivingStarted.remove(reviverId) != null;
        revivingHeartbeat.remove(reviverId);
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
        Faction tf = factionOf.get(targetId);
        Faction rf = factionOf.get(reviverId);
        if (tf == null || rf == null || tf != rf) {
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
            reviver.displayClientMessage(Component.literal("§a正在救援 " + target.getGameProfile().getName() + "..."), true);
        } else if (!targetId.equals(revivingTarget.get(reviverId))) {
            revivingTarget.put(reviverId, targetId);
            revivingStarted.put(reviverId, now);
        }
    }

    public boolean isDowned(UUID id) {
        return downedUntil.containsKey(id);
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
        if (action == org.shee33.act0.battlefield.network.DownedActionPacket.Action.GIVE_UP) {
            consumePendingDeath(id);
            downedUntil.remove(id);
            downedLastGoodY.remove(id);
            player.removeAllEffects();
            player.setPose(Pose.STANDING);
            player.sendSystemMessage(Component.literal("§7你放弃了救援。"));
            beginRedeploy(player, f);
        } else if (action == org.shee33.act0.battlefield.network.DownedActionPacket.Action.CALL_HELP) {
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

    public void spotEnemy(ServerPlayer spotter, int targetEntityId) {
        if (spotter == null) {
            return;
        }
        long now = server.getTickCount();
        Long lastTick = lastSpotTick.get(spotter.getUUID());
        if (lastTick != null && now - lastTick < SPOT_MIN_INTERVAL_TICKS) {
            // 200ms内重复标记请求直接丢弃：正常单次按键标记不会撞上这个门槛，只有异常/
            // 恶意客户端狂发这个C2S小包才会被限制住。
            return;
        }
        org.shee33.act0.battlefield.core.Faction spotterFaction = factionOf.get(spotter.getUUID());
        if (spotterFaction == null) {
            return;
        }
        net.minecraft.world.entity.Entity target = level.getEntity(targetEntityId);
        if (!(target instanceof ServerPlayer enemy) || enemy == spotter) {
            return;
        }
        org.shee33.act0.battlefield.core.Faction enemyFaction = factionOf.get(enemy.getUUID());
        if (enemyFaction == null || enemyFaction == spotterFaction) {
            return;
        }
        lastSpotTick.put(spotter.getUUID(), now);
        UUID targetUuid = enemy.getUUID();
        long firstTick = spotFirstTick.computeIfAbsent(targetUuid, ignored -> now);
        spottedUntil.put(targetUuid, firstTick + SPOT_DURATION_TICKS);
        for (UUID mateId : factionOf.keySet()) {
            if (factionOf.get(mateId) != spotterFaction) {
                continue;
            }
            ServerPlayer mate = player(mateId);
            if (mate != null) {
                GlowSync.showGlowTo(mate, enemy);
            }
        }
        spotter.displayClientMessage(Component.literal("§e已标记敌人 §c" + enemy.getGameProfile().getName()), true);
    }

    private void tickSpotted() {
        if (spottedUntil.isEmpty()) {
            return;
        }
        long now = server.getTickCount();
        List<UUID> expired = new ArrayList<>();
        for (Map.Entry<UUID, Long> e : spottedUntil.entrySet()) {
            if (now >= e.getValue()) {
                expired.add(e.getKey());
            }
        }
        for (UUID id : expired) {
            spottedUntil.remove(id);
            spotFirstTick.remove(id);
            ServerPlayer target = player(id);
            if (target != null) {
                target.setGlowingTag(false);
            }
        }
    }

    public int downedSeconds(UUID id) {
        Long until = downedUntil.get(id);
        if (until == null) {
            return 0;
        }
        return (int) Math.max(0, (until - server.getTickCount()) / 20);
    }

    List<DownedMateDto> getDownedMateDtos(UUID viewerId) {
        Faction viewerFaction = factionOf.get(viewerId);
        if (viewerFaction == null) {
            return List.of();
        }
        List<DownedMateDto> list = new ArrayList<>();
        for (Map.Entry<UUID, Long> e : downedUntil.entrySet()) {
            Faction f = factionOf.get(e.getKey());
            if (f != viewerFaction) {
                continue;
            }
            ServerPlayer p = player(e.getKey());
            if (p == null) {
                continue;
            }
            int remain = (int) Math.max(0, (e.getValue() - server.getTickCount()) / 20);
            list.add(new DownedMateDto(p.getGameProfile().getName(), p.getX(), p.getY() + 1.0, p.getZ(), remain));
        }
        return list;
    }

    int getRevivingProgress(UUID reviverId) {
        Long started = revivingStarted.get(reviverId);
        if (started == null) {
            return 0;
        }
        long elapsed = server.getTickCount() - started;
        return (int) Math.min(100, Math.round((double) elapsed / reviveDurationTicks * 100.0));
    }

    @Nullable
    String getRevivingName(UUID reviverId) {
        UUID targetId = revivingTarget.get(reviverId);
        if (targetId == null) {
            return null;
        }
        ServerPlayer target = player(targetId);
        return target != null ? target.getGameProfile().getName() : null;
    }

    int getBeingRevivedProgress(UUID targetId) {
        UUID reviverId = findReviverOf(targetId);
        return reviverId == null ? 0 : getRevivingProgress(reviverId);
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

    // ---- 工具 ----

    private int ownedCount(Faction f) {
        int n = 0;
        for (CapturePoint p : points) {
            if (p.owner() == f) {
                n++;
            }
        }
        return n;
    }

    @Nullable
    private ServerPlayer player(UUID id) {
        return server.getPlayerList().getPlayer(id);
    }

    private void broadcast(String msg) {
        for (UUID id : factionOf.keySet()) {
            ServerPlayer p = player(id);
            if (p != null) {
                p.sendSystemMessage(Component.literal(msg));
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

    /**
     * 向双方阵营的每个玩家单独下发一次据点状态边沿事件（HUD 横幅 + 小地图提亮），
     * 与 {@link #broadcast(String)}/{@link #playToAll} 的全局聊天/音效广播并列、不替代。
     */
    private void sendCapturePointEvent(int pointId, CapturePointEventPacket.Kind kind, int factionCode) {
        for (UUID id : factionOf.keySet()) {
            ServerPlayer p = player(id);
            if (p != null) {
                BattlefieldNetwork.sendCapturePointEvent(p, pointId, kind, factionCode);
            }
        }
    }

    public boolean isEnded() {
        return ended;
    }

    public void pause() {
        this.paused = true;
    }

    public void resume() {
        this.paused = false;
    }

    public boolean isPaused() {
        return paused;
    }

    public void setTickets(Faction faction, int amount) {
        tickets.setTickets(faction, amount);
    }

    public void addTickets(Faction faction, int amount) {
        tickets.addTickets(faction, amount);
    }

    public void subTickets(Faction faction, int amount) {
        tickets.subTickets(faction, amount);
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

    /** 起始票数（取整），供对局浏览器换算票数对峙条比例。 */
    public int startingTicketsHint() {
        return (int) Math.ceil(rules.startingTickets());
    }

    public int elapsedSeconds() {
        return (int) Math.max(0L, (server.getTickCount() - startedTick) / 20L);
    }

    public String participantNames() {
        List<String> names = new ArrayList<>();
        for (UUID id : factionOf.keySet()) {
            ServerPlayer p = player(id);
            names.add(p != null ? p.getGameProfile().getName() : id.toString().substring(0, 8));
        }
        return String.join(", ", names);
    }

    /** 某玩家所属阵营；不在对局内返回 {@code null}。 */
    @Nullable
    public Faction factionOf(UUID id) {
        return factionOf.get(id);
    }

    public boolean canChangeLoadout(UUID id) {
        return factionOf.containsKey(id) && redeployService.isRedeploying(id);
    }

    @Nullable
    public Faction winner() {
        return winner;
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

    /** 某阵营剩余票数（取整，供 HUD/界面显示）。 */
    public int displayTickets(Faction faction) {
        return tickets.displayTickets(faction);
    }

    /** 某阵营当前控制的据点数。 */
    public int ownedPoints(Faction faction) {
        return ownedCount(faction);
    }

    /** 据点总数。 */
    public int totalPoints() {
        return points.size();
    }

    /** 玩家所属小队编号；不在对局/未分队则返回 0。 */
    public int squadIdOf(UUID id) {
        return squadManager.getSquadOf().getOrDefault(id, 0);
    }

    /** 玩家所属小队人数；不在小队则返回 0。 */
    public int squadSizeOf(UUID id) {
        Integer squadId = squadManager.getSquadOf().get(id);
        if (squadId == null) {
            return 0;
        }
        LinkedHashSet<UUID> members = squadManager.getSquads().get(squadId);
        return members == null ? 0 : members.size();
    }

    public boolean isSquadLeader(UUID playerId) {
        return squadManager.isSquadLeader(playerId);
    }

    /** 两名玩家是否同一小队。 */
    public boolean isSameSquad(UUID a, UUID b) {
        return squadManager.isSameSquad(a, b);
    }

    @Nullable
    public String setSquadOrder(UUID playerId, int pointId, boolean attack) {
        Integer squadId = squadManager.getSquadOf().get(playerId);
        if (squadId == null) {
            return "§c未找到你的小队。";
        }
        if (!squadManager.isSquadLeader(playerId)) {
            return "§c只有小队长可以下达命令。";
        }
        boolean valid = defs.stream().anyMatch(d -> d.pointId() == pointId);
        if (!valid) {
            return "§c找不到编号为 " + pointId + " 的据点。";
        }
        squadManager.setOrder(squadId, new SquadManager.SquadOrder(pointId, attack));
        return null;
    }

    public int killsOf(UUID id) {
        return killTracker.killsOf(id);
    }

    public int deathsOf(UUID id) {
        return killTracker.deathsOf(id);
    }
}
