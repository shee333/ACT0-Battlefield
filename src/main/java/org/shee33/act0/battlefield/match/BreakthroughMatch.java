package org.shee33.act0.battlefield.match;

import net.minecraft.ChatFormatting;
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
import org.shee33.act0.battlefield.core.Sector;
import org.shee33.act0.battlefield.data.BattlefieldData;
import org.shee33.act0.battlefield.data.ControlPointDef;
import org.shee33.act0.battlefield.integration.ArcadeLoadoutBridge;
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
    private final Map<UUID, Faction> factionOf = new LinkedHashMap<>();
    private final SquadManager squadManager;
    private final KillTracker killTracker;
    private final RedeployService redeployService;
    private final ConquestRules captureRules;
    private final List<PlayerTeam> nameTagTeams = new ArrayList<>();

    private final Map<UUID, Long> downedUntil = new LinkedHashMap<>();
    private final Map<UUID, Long> lastHurtTick = new LinkedHashMap<>();
    private final Map<UUID, UUID> revivingTarget = new LinkedHashMap<>();
    private final Map<UUID, Long> revivingStarted = new LinkedHashMap<>();
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
        this.squadSize = BattlefieldConfig.SQUAD_SIZE.get();
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
        this.redeployService = new RedeployService(level, data, factionOf, squadManager, points, defs,
                downedUntil, escapeTicks, lastHurtTick, this::cancelRevive,
                spawnProtectionTicks, redeployDelayTicks);
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
                p.sendSystemMessage(Component.literal("§6突破模式即将开始！你属于 " + e.getValue().coloredName()
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
        squadManager.buildSquads();
        setupNameTagTeams();
        beginRedeploy(player, faction);
        broadcast("§b" + player.getGameProfile().getName() + " §7加入了 " + faction.coloredName() + "§7。");
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
        lastHurtTick.remove(id);
        escapeTicks.remove(id);
        downedUntil.remove(id);
        pendingDeaths.remove(id);
        cancelRevive(id);
        lastHudHash.remove(id);
        callHelpCooldownUntil.remove(id);
        squadManager.onPlayerLeave(id);
        squadManager.buildSquads();
        setupNameTagTeams();
        broadcast("§e" + player.getGameProfile().getName() + " §7退出了本对局。");
        player.sendSystemMessage(Component.literal("§7已退出大战场。"));
        if (factionOf.isEmpty()) {
            ended = true;
            clearNameTagTeams();
        }
        return true;
    }

    // ---- 每刻 ----

    public void tick() {
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
            broadcast("§a§l" + Faction.ALPHA.coloredName() + " §a占领了区域 §e" + current.displayName()
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
                        squadBroadcast(e.getKey(), "§a" + capturer.coloredName()
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
        RelativeTeamSync.sync(viewer, factionOf.keySet(), this::player,
                id -> mine != null && mine == factionOf.get(id));
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
        Vec3 eyes = viewer.getEyePosition();
        Vec3 toTarget = target.getEyePosition().subtract(eyes);
        if (toTarget.lengthSqr() < 0.0001D) {
            return true;
        }
        return viewer.getViewVector(1.0F).normalize().dot(toTarget.normalize()) >= enemyMarkViewDot;
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

    private void enterDowned(ServerPlayer p, Faction f, @Nullable UUID killerId) {
        UUID id = p.getUUID();
        long until = server.getTickCount() + downedDurationTicks;
        downedUntil.put(id, until);
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
        p.sendSystemMessage(Component.literal("§c你被 " + killerName + " 击倒了！§7长按空格放弃 · 右键队友救你"));
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
            if (p != null && p.getPose() != Pose.SWIMMING) {
                p.setPose(Pose.SWIMMING);
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
            if (reviver == null || target == null || !downedUntil.containsKey(targetId)
                    || target.distanceToSqr(reviver) > 16.0D) {
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
            ServerPlayer target = player(targetId);
            ServerPlayer reviver = player(reviverId);
            if (target != null && reviver != null) {
                downedUntil.remove(targetId);
                pendingDeaths.remove(targetId);
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

    private void cancelRevive(UUID reviverId) {
        revivingTarget.remove(reviverId);
        revivingStarted.remove(reviverId);
        ServerPlayer reviver = player(reviverId);
        if (reviver != null) {
            reviver.displayClientMessage(Component.literal("§c救援中断"), true);
        }
    }

    public boolean reviveDownedPlayer(UUID targetId, ServerPlayer reviver) {
        UUID reviverId = reviver.getUUID();
        if (!downedUntil.containsKey(targetId)) {
            return false;
        }
        Faction tf = factionOf.get(targetId);
        Faction rf = factionOf.get(reviverId);
        if (tf == null || rf == null || tf != rf) {
            return false;
        }
        ServerPlayer target = player(targetId);
        if (target == null || target.distanceToSqr(reviver) > 16.0D) {
            return false;
        }
        if (revivingStarted.containsKey(reviverId)) {
            return true;
        }
        revivingTarget.put(reviverId, targetId);
        revivingStarted.put(reviverId, (long) server.getTickCount());
        reviver.displayClientMessage(Component.literal("§a正在救援 " + target.getGameProfile().getName() + "..."), true);
        return true;
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
        if (action == DownedActionPacket.Action.GIVE_UP) {
            consumePendingDeath(id);
            downedUntil.remove(id);
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
        broadcast("§6§l对局结束：" + w.coloredName() + " §6§l获胜！");
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
        lastHurtTick.clear();
        escapeTicks.clear();
        downedUntil.clear();
        pendingDeaths.clear();
        revivingTarget.clear();
        revivingStarted.clear();
        callHelpCooldownUntil.clear();
        killTracker.clearTransient();
        clearAllEnemyGlows();
        clearAllRelativeTeams();
        clearNameTagTeams();
        sendFireLockToAll(false);
    }

    /** 强制中止（服务器关闭/管理员停止）。 */
    public void abort() {
        if (ended) {
            return;
        }
        ended = true;
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
        lastHurtTick.clear();
        escapeTicks.clear();
        downedUntil.clear();
        pendingDeaths.clear();
        revivingTarget.clear();
        revivingStarted.clear();
        callHelpCooldownUntil.clear();
        killTracker.clearTransient();
        clearAllEnemyGlows();
        clearAllRelativeTeams();
        clearNameTagTeams();
        sendFireLockToAll(false);
    }

    private void sendPersonalResult(ServerPlayer player, Faction mine, Faction w) {
        boolean won = mine == w;
        sendTitle(player, won ? "§a§l胜利" : "§c§l失败",
                w.coloredName() + " §7取得胜利", 5, 60, 15);
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

        return new BreakthroughHudDto(true,
                (int) attackerTickets, rules.startingTickets(),
                currentSectorIndex, sectors.size(),
                pointDtos, squad, phase, winnerCode,
                focus.name(), focus.state(), focus.progress());
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
                if (p == null || p.level() != level || !p.isAlive() || p.isSpectator()) {
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
                player.isAlive() || downed, self, downed, isSquadLeader));
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
            if (now - last >= breathHealDelayTicks) {
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
        return 64;
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
