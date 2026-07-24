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
import net.minecraft.world.level.GameType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;
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
import org.shee33.act0.battlefield.network.ControlPointHudDto;
import org.shee33.act0.battlefield.network.DeployPointDto;
import org.shee33.act0.battlefield.network.DeployStatusDto;
import org.shee33.act0.battlefield.network.DeploySquadMateDto;
import org.shee33.act0.battlefield.network.DownedMateDto;
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
import java.util.Set;
import java.util.UUID;

/**
 * 一场征服对局：驱动据点争夺、票数流失、死亡接管与据点前进出生，直到一方票数归零。
 *
 * <p>每 {@link #CAPTURE_INTERVAL} 刻（0.5 秒）结算一次占点与流失：统计每个据点区域内双方人数推进
 * 争夺进度，按双方控制据点数差流失败方票数。死亡<b>不走原版死亡流程</b>（接管后立即满血并在出生点重新部署，
 * 扣除本方 1 票），避免死亡画面与丢装备，并支持在己方控制的据点前进出生。
 */
public final class ConquestMatch {

    private static final int CAPTURE_INTERVAL = 10; // 0.5s
    private static final double CAPTURE_DELTA = CAPTURE_INTERVAL / 20.0;
    private static final int HUD_INTERVAL = 10;
    private static final int SQUAD_SIZE = 4;
    private static final int START_COUNTDOWN_TICKS = 5 * 20;
    private static final int REDEPLOY_DELAY_TICKS = 5 * 20;
    private static final int SPAWN_PROTECTION_TICKS = 3 * 20;
    private static final double SQUAD_DEPLOY_ENEMY_BLOCK_RADIUS = 12.0;
    private static final int IFF_SYNC_INTERVAL = 2;
    private static final double ENEMY_MARK_DISTANCE = 96.0;
    private static final double ENEMY_MARK_DISTANCE_SQR = ENEMY_MARK_DISTANCE * ENEMY_MARK_DISTANCE;
    private static final double ENEMY_MARK_VIEW_DOT = 0.30;
    private static final int BREATH_HEAL_DELAY_TICKS = 5 * 20;
    private static final int ESCAPE_BOUNDARY_TICKS = 10 * 20;
    private static final int DOWNED_DURATION_TICKS = 15 * 20;
    private static final int REVIVE_DURATION_TICKS = 3 * 20;

    private final MinecraftServer server;
    private final ServerLevel level;
    private final ConquestRules rules;
    private final TicketPool tickets;
    private final List<ControlPointDef> defs;
    private final List<CapturePoint> points;
    private final Map<UUID, Faction> factionOf = new LinkedHashMap<>();
    private final Map<UUID, Integer> squadOf = new LinkedHashMap<>();
    private final Map<Integer, LinkedHashSet<UUID>> squads = new LinkedHashMap<>();
    private final Map<UUID, Long> redeployReadyTick = new LinkedHashMap<>();
    private final Map<UUID, String> deploySelection = new LinkedHashMap<>();
    private final Map<UUID, String> deployTarget = new LinkedHashMap<>();
    private final Map<UUID, GameType> redeployOriginalMode = new LinkedHashMap<>();
    private final Map<UUID, Integer> kills = new LinkedHashMap<>();
    private final Map<UUID, Integer> deaths = new LinkedHashMap<>();
    private final Map<UUID, Long> protectedUntil = new LinkedHashMap<>();
    private final Map<UUID, Long> lastHurtTick = new LinkedHashMap<>();
    private final List<PlayerTeam> nameTagTeams = new ArrayList<>();
    private final Map<UUID, Set<UUID>> visibleEnemyGlows = new LinkedHashMap<>();
    private final BattlefieldData data;
    private final Map<UUID, Integer> escapeTicks = new LinkedHashMap<>();
    private final Map<UUID, Long> downedUntil = new LinkedHashMap<>();
    private final Map<UUID, UUID> revivingTarget = new LinkedHashMap<>();
    private final Map<UUID, Long> revivingStarted = new LinkedHashMap<>();
    private final Map<UUID, Map<UUID, Long>> recentHits = new LinkedHashMap<>();
    private final Map<UUID, Integer> killStreak = new LinkedHashMap<>();
    private final Map<UUID, UUID> lastKilledBy = new LinkedHashMap<>();
    private boolean firstBlood;

    private boolean ended;
    @Nullable
    private Faction winner;
    private int captureAccum;
    private int hudAccum;
    private int iffAccum;
    private long startedTick;
    private int startCountdownTicks;
    private int startCountdownLastSecond = -1;

    public ConquestMatch(ServerLevel level, ConquestRules rules,
                         List<ControlPointDef> defs, Map<UUID, Faction> roster,
                         BattlefieldData data) {
        this.server = level.getServer();
        this.level = level;
        this.rules = rules;
        this.data = data;
        this.tickets = new TicketPool(rules.startingTickets());
        this.defs = new ArrayList<>(defs);
        this.points = new ArrayList<>(defs.size());
        for (ControlPointDef def : this.defs) {
            this.points.add(new CapturePoint(def.pointId(), def.name()));
        }
        this.factionOf.putAll(roster);
        for (UUID id : roster.keySet()) {
            kills.put(id, 0);
            deaths.put(id, 0);
        }
        buildSquads();
    }

    /** 按阵营自动分队：每个小队最多 4 人，北大西洋公约/无邦军团各自独立连续编号。 */
    private void buildSquads() {
        squadOf.clear();
        squads.clear();
        int alphaSquad = 1;
        int bravoSquad = 101;
        int alphaCount = 0;
        int bravoCount = 0;
        for (Map.Entry<UUID, Faction> e : factionOf.entrySet()) {
            int squadId;
            if (e.getValue() == Faction.ALPHA) {
                if (alphaCount > 0 && alphaCount % SQUAD_SIZE == 0) {
                    alphaSquad++;
                }
                squadId = alphaSquad;
                alphaCount++;
            } else {
                if (bravoCount > 0 && bravoCount % SQUAD_SIZE == 0) {
                    bravoSquad++;
                }
                squadId = bravoSquad;
                bravoCount++;
            }
            squadOf.put(e.getKey(), squadId);
            squads.computeIfAbsent(squadId, ignored -> new LinkedHashSet<>()).add(e.getKey());
        }
    }

    /** 开局：把所有参战玩家部署到各自基地。 */
    public void begin() {
        startCountdownTicks = START_COUNTDOWN_TICKS;
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
        kills.put(id, 0);
        deaths.put(id, 0);
        buildSquads();
        setupNameTagTeams();
        beginRedeploy(player, faction);
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
            player.teleportTo(level, base.x(), base.y(), base.z(), base.yaw(), base.pitch());
        }
        player.getInventory().clearContent();
        BattlefieldNetwork.clearHud(player);
        BattlefieldNetwork.sendFireLock(player, false);
        kills.remove(id);
        deaths.remove(id);
        protectedUntil.remove(id);
        lastHurtTick.remove(id);
        escapeTicks.remove(id);
        downedUntil.remove(id);
        cancelRevive(id);
        killStreak.remove(id);
        lastKilledBy.remove(id);
        recentHits.remove(id);
        buildSquads();
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

    // ---- 每刻 ----

    public void tick() {
        if (ended) {
            return;
        }
        if (startCountdownTicks > 0) {
            tickStartCountdown();
            if (++hudAccum >= HUD_INTERVAL) {
                hudAccum = 0;
                broadcastHud();
            }
            return;
        }
        if (++captureAccum >= CAPTURE_INTERVAL) {
            captureAccum = 0;
            resolveCaptureAndBleed();
        }
        processRedeployTick();
        if (++iffAccum >= IFF_SYNC_INTERVAL) {
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
        if (++hudAccum >= HUD_INTERVAL) {
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
                if (p == null || p.level() != level || !p.isAlive() || p.isSpectator()) {
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
            CapturePoint.CaptureStatus st = point.tick(alpha, bravo, rules, CAPTURE_DELTA);
            if (st == CapturePoint.CaptureStatus.CAPTURED) {
                Faction owner = point.owner();
                if (owner != null) {
                    broadcast(owner.coloredName() + " §7占领了据点 §e" + point.displayName());
                    playToAll(SoundEvents.NOTE_BLOCK_BELL.value(), 1.0f);
                    actionBarNear(point.displayName(), zone, owner.coloredName() + " §a已控制 " + point.displayName());
                }
            } else if (st == CapturePoint.CaptureStatus.NEUTRALIZED) {
                broadcast("§7据点 §e" + point.displayName() + " §7已被中立化");
                playToAll(SoundEvents.NOTE_BLOCK_BASS.value(), 0.7f);
            } else if (st == CapturePoint.CaptureStatus.CONTESTED) {
                playNear(point.displayName(), zone, SoundEvents.NOTE_BLOCK_HAT.value(), 0.4f);
            } else if (st == CapturePoint.CaptureStatus.CAPTURING) {
                Faction pushing = alpha > 0 ? Faction.ALPHA : Faction.BRAVO;
                actionBarNear(point.displayName(), zone, pushing.coloredName() + " §7正在占领 " + point.displayName());
            }
        }

        int alphaPoints = ownedCount(Faction.ALPHA);
        int bravoPoints = ownedCount(Faction.BRAVO);
        tickets.bleed(alphaPoints, bravoPoints, rules, CAPTURE_DELTA);

        Faction w = tickets.winner();
        if (w != null) {
            end(w);
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
        deaths.merge(victimId, 1, Integer::sum);
        tickets.onDeath(f, rules);
        lastHurtTick.remove(victimId);
        handleKillCredit(victimId, killerId);
        Faction w = tickets.winner();
        if (w != null) {
            end(w);
        } else {
            broadcastHud();
        }
        return true;
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
            recentHits.computeIfAbsent(victimId, k -> new LinkedHashMap<>())
                    .put(attackerId, (long) server.getTickCount());
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

    private void handleKillCredit(UUID victimId, @Nullable UUID killerId) {
        if (killerId == null || killerId.equals(victimId)) {
            return;
        }
        Faction victimFaction = factionOf.get(victimId);
        Faction killerFaction = factionOf.get(killerId);
        if (victimFaction == null || killerFaction == null || victimFaction == killerFaction) {
            return;
        }
        kills.merge(killerId, 1, Integer::sum);
        ServerPlayer killer = player(killerId);
        ServerPlayer victim = player(victimId);
        String killerName = killer != null ? killer.getGameProfile().getName() : "未知";
        String victimName = victim != null ? victim.getGameProfile().getName() : "未知";
        for (UUID id : factionOf.keySet()) {
            ServerPlayer viewer = player(id);
            if (viewer != null) {
                BattlefieldNetwork.sendKillFeed(viewer, killerName, victimName,
                        factionCode(killerFaction), factionCode(victimFaction));
            }
        }
        if (killer != null) {
            killer.playNotifySound(SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.MASTER, 0.6f, 1.35f);

            // 连杀（仅个人 ActionBar）
            killStreak.merge(killerId, 1, Integer::sum);
            int streak = killStreak.get(killerId);
            if (streak == 3 || streak == 5 || streak == 10 || streak == 15) {
                killer.displayClientMessage(Component.literal("§7" + streak + " 连杀"), true);
            }

            // 首杀（仅 ActionBar）
            if (!firstBlood) {
                firstBlood = true;
                killer.displayClientMessage(Component.literal("§e首杀"), true);
            }

            // 复仇
            UUID prev = lastKilledBy.remove(killerId);
            if (prev != null && prev.equals(victimId)) {
                killer.displayClientMessage(Component.literal("§7复仇"), true);
            }

            // 守点击杀
            for (int i = 0; i < points.size(); i++) {
                if (points.get(i).owner() == killerFaction
                        && defs.get(i).zone().contains(killer.getX(), killer.getY(), killer.getZ())) {
                    killer.displayClientMessage(Component.literal("§b守点击杀 §e" + defs.get(i).name()), true);
                    break;
                }
            }
        }
        killStreak.put(victimId, 0);
        lastKilledBy.put(victimId, killerId);

        // 助攻
        Map<UUID, Long> hitsOnVictim = recentHits.remove(victimId);
        if (hitsOnVictim != null) {
            long now = server.getTickCount();
            for (Map.Entry<UUID, Long> e : hitsOnVictim.entrySet()) {
                if (e.getKey().equals(killerId)) {
                    continue;
                }
                if (now - e.getValue() <= 200L) {
                    ServerPlayer assister = player(e.getKey());
                    if (assister != null) {
                        assister.displayClientMessage(Component.literal("§e助攻 " + victimName), true);
                        assister.playNotifySound(SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.MASTER, 0.4f, 1.1f);
                    }
                }
            }
        }
    }

    /** 是否应取消某玩家受到的伤害：部署中/出生保护/友伤/倒地均取消。 */
    public boolean shouldCancelDamage(UUID victimId, @Nullable UUID attackerId) {
        if (!factionOf.containsKey(victimId)) {
            return false;
        }
        if (startCountdownTicks > 0) {
            return true;
        }
        if (redeployReadyTick.containsKey(victimId)) {
            return true;
        }
        if (downedUntil.containsKey(victimId)) {
            return true;
        }
        ServerPlayer victim = player(victimId);
        if (victim != null && victim.isSpectator()) {
            return true;
        }
        Long until = protectedUntil.get(victimId);
        if (until != null) {
            if (server.getTickCount() < until) {
                return true;
            }
            protectedUntil.remove(victimId);
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
            player.removeAllEffects();
            player.setPose(Pose.STANDING);
            player.sendSystemMessage(Component.literal("§c你掉线时倒地过久，已阵亡。"));
            beginRedeploy(player, faction);
            return;
        }
        if (redeployReadyTick.containsKey(id)) {
            redeployOriginalMode.putIfAbsent(id, player.gameMode.getGameModeForPlayer());
            player.setGameMode(GameType.SPECTATOR);
            player.setInvulnerable(true);
            player.setDeltaMovement(0.0, 0.0, 0.0);
            teleportToDeployOverview(player, faction);
            BattlefieldNetwork.sendDeploy(player, true, deployStatus(player));
        } else {
            player.setInvulnerable(false);
            BattlefieldNetwork.sendBattleHud(player, buildHudFor(player));
            BattlefieldNetwork.sendBattleTab(player, buildTabFor(player));
        }
    }

    private void beginRedeploy(ServerPlayer player, Faction faction) {
        UUID id = player.getUUID();
        long readyTick = server.getTickCount() + REDEPLOY_DELAY_TICKS;
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
        player.sendSystemMessage(Component.literal("§6选择部署点，准备重返战场。"));
    }

    private void processRedeployTick() {
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

    public void handleDeployAction(ServerPlayer player, String kind) {
        UUID id = player.getUUID();
        Faction faction = factionOf.get(id);
        handleDeployAction(player, kind, faction != null ? bestDeployTarget(id, faction, normalizeDeployKind(kind)) : "");
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

    private DeployStatusDto deployStatus(ServerPlayer player) {
        UUID id = player.getUUID();
        Faction faction = factionOf.get(id);
        if (faction == null || !redeployReadyTick.containsKey(id)) {
            return DeployStatusDto.inactive();
        }
        BattlefieldData.BaseSpawn squad = bestSquadSpawn(id, faction);
        BattlefieldData.BaseSpawn base = data.base(faction);
        List<DeployPointDto> pointDtos = deployPointDtos(faction);
        List<DeploySquadMateDto> squadDtos = deploySquadMateDtos(id, faction);
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

    private List<DeploySquadMateDto> deploySquadMateDtos(UUID self, Faction faction) {
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
            ServerPlayer mate = player(mateId);
            if (mate == null || mate.level() != level || !mate.isAlive() || mate.isSpectator()) {
                continue;
            }
            boolean deployable = !enemyNear(mate, faction, SQUAD_DEPLOY_ENEMY_BLOCK_RADIUS);
                list.add(new DeploySquadMateDto(mateId.toString(), mate.getGameProfile().getName(), mate.getId(),
                    deployable, mate.getX(), mate.getY() + 1.0, mate.getZ()));
        }
        return list;
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

    private String bestDeployKind(UUID id, Faction faction) {
        if (firstDeployableSquadMate(id, faction) != null) {
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
            DeploySquadMateDto mate = firstDeployableSquadMate(id, faction);
            return mate != null ? mate.id() : "";
        }
        return "";
    }

    private boolean canDeployTo(UUID id, Faction faction, String kind, String targetId) {
        return switch (kind) {
            case "squad" -> squadMateSpawn(id, faction, targetId) != null;
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

    private String firstDeployablePointId(Faction faction) {
        for (int i = 0; i < points.size(); i++) {
            if (points.get(i).owner() == faction) {
                return Integer.toString(defs.get(i).pointId());
            }
        }
        return null;
    }

    @Nullable
    private DeploySquadMateDto firstDeployableSquadMate(UUID self, Faction faction) {
        for (DeploySquadMateDto mate : deploySquadMateDtos(self, faction)) {
            if (mate.deployable()) {
                return mate;
            }
        }
        return null;
    }

    @Nullable
    private BattlefieldData.BaseSpawn bestSquadSpawn(UUID self, Faction faction) {
        DeploySquadMateDto first = firstDeployableSquadMate(self, faction);
        return first != null ? squadMateSpawn(self, faction, first.id()) : null;
    }

    @Nullable
    private BattlefieldData.BaseSpawn squadMateSpawn(UUID self, Faction faction, String targetId) {
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
        ServerPlayer mate = player(mateUuid);
        if (mate == null || mate.level() != level || !mate.isAlive() || mate.isSpectator()) {
            return null;
        }
        if (enemyNear(mate, faction, SQUAD_DEPLOY_ENEMY_BLOCK_RADIUS)) {
            return null;
        }
        return new BattlefieldData.BaseSpawn(mate.getX(), mate.getY(), mate.getZ(), mate.getYRot(), mate.getXRot());
    }

    private boolean enemyNear(ServerPlayer origin, Faction faction, double radius) {
        double r2 = radius * radius;
        for (Map.Entry<UUID, Faction> e : factionOf.entrySet()) {
            if (e.getValue() == faction) {
                continue;
            }
            ServerPlayer enemy = player(e.getKey());
            if (enemy == null || enemy.level() != level || !enemy.isAlive() || enemy.isSpectator()) {
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

    private void deploy(ServerPlayer p, Faction f) {
        String kind = bestDeployKind(p.getUUID(), f);
        deploy(p, f, kind, bestDeployTarget(p.getUUID(), f, kind));
    }

    private void deploy(ServerPlayer p, Faction f, String kind, String targetId) {
        UUID id = p.getUUID();
        BattlefieldData.BaseSpawn spawn = switch (kind) {
            case "squad" -> squadMateSpawn(id, f, targetId);
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
        cancelRevive(id);
        p.setPose(Pose.STANDING);
        ArcadeLoadoutBridge.apply(p);
        p.setHealth(p.getMaxHealth());
        p.getFoodData().setFoodLevel(20);
        lastHurtTick.remove(id);
        p.removeEffect(MobEffects.REGENERATION);
        protectedUntil.put(id, (long) server.getTickCount() + SPAWN_PROTECTION_TICKS);
        p.sendSystemMessage(Component.literal("§a已部署，短暂无敌保护已启动。"));
        BattlefieldNetwork.sendDeploy(p, false, DeployStatusDto.inactive());
        BattlefieldNetwork.sendFireLock(p, startCountdownTicks > 0);
    }

    private void clearRedeployState(ServerPlayer player, boolean restoreOriginalMode) {
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

    /** Squad respawn point: living squadmate if available. */
    @Nullable
    private BattlefieldData.BaseSpawn livingSquadmateSpawn(UUID self) {
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
            ServerPlayer mate = player(mateId);
            if (mate != null && mate.level() == level && mate.isAlive() && !mate.isSpectator()) {
                return new BattlefieldData.BaseSpawn(mate.getX(), mate.getY(), mate.getZ(), mate.getYRot(), mate.getXRot());
            }
        }
        return null;
    }

    /**
     * 据点前进出生：在己方控制且最靠近敌方基地的据点上方出生；无己方据点或无敌方基地则回退到基地。
     */
    @Nullable
    private BattlefieldData.BaseSpawn forwardSpawn(Faction f) {
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

    // ---- 结束 ----

    private void end(Faction w) {
        this.ended = true;
        this.winner = w;
        broadcast("§6§l对局结束：" + w.coloredName() + " §6§l取得票数压制。");
        broadcastServerResult(w);
        broadcastMatchResult(w);
        for (Map.Entry<UUID, Faction> e : factionOf.entrySet()) {
            ServerPlayer p = player(e.getKey());
            if (p != null) {
                if (redeployReadyTick.containsKey(e.getKey())) {
                    clearRedeployState(p, true);
                } else {
                    p.setInvulnerable(false);
                }
                sendPersonalResult(p, e.getValue(), w);
                BattlefieldNetwork.sendBattleResult(p, buildResultFor(p, w));
                BattlefieldData.BaseSpawn base = data.base(e.getValue());
                if (base != null) {
                    p.teleportTo(level, base.x(), base.y(), base.z(), base.yaw(), base.pitch());
                }
                p.getInventory().clearContent();
                BattlefieldNetwork.clearHud(p);
            }
        }
        redeployReadyTick.clear();
        deploySelection.clear();
        deployTarget.clear();
        redeployOriginalMode.clear();
        protectedUntil.clear();
        lastHurtTick.clear();
        escapeTicks.clear();
        downedUntil.clear();
        revivingTarget.clear();
        revivingStarted.clear();
        recentHits.clear();
        killStreak.clear();
        lastKilledBy.clear();
        firstBlood = false;
        clearAllEnemyGlows();
        clearAllRelativeTeams();
        clearNameTagTeams();
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
            + " §8| §7你的 K/D §e" + kills.getOrDefault(id, 0) + "§7/§c" + deaths.getOrDefault(id, 0)));
    }

    private void broadcastServerResult(Faction winner) {
        TopKiller top = topKiller();
        String mvp = top.kills() > 0 ? " §8| §7击杀王 §e" + top.name() + " §7(" + top.kills() + "杀)" : "";
        Component message = Component.literal("§6[ACT0赛果] §f大战场 · 征服 §8| §a"
                + winner.displayName() + " §7胜出 §8| §7票数 §9北大西洋公约 §f"
                + tickets.displayTickets(Faction.ALPHA) + " §8/ §c无邦军团 §f"
                + tickets.displayTickets(Faction.BRAVO) + mvp);
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
        for (Map.Entry<UUID, Integer> e : kills.entrySet()) {
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

    private record TopKiller(String name, int kills) {
    }

    private BattleResultDto buildResultFor(ServerPlayer viewer, Faction winner) {
        List<TabEntryDto> entries = new ArrayList<>();
        for (Map.Entry<UUID, Faction> e : factionOf.entrySet()) {
            UUID id = e.getKey();
            ServerPlayer p = player(id);
            String name = p != null ? p.getGameProfile().getName() : id.toString().substring(0, 8);
            int ping = p != null ? p.latency : -1;
            entries.add(new TabEntryDto(name, factionCode(e.getValue()),
                    kills.getOrDefault(id, 0), deaths.getOrDefault(id, 0), ping, p == null ? 2 : 0));
        }
        entries.sort(Comparator
                .comparingInt(TabEntryDto::kills).reversed()
                .thenComparingInt(TabEntryDto::deaths)
                .thenComparing(TabEntryDto::name));
        UUID viewerId = viewer.getUUID();
        return new BattleResultDto(factionCode(winner), factionCode(factionOf.get(viewerId)),
                tickets.displayTickets(Faction.ALPHA), tickets.displayTickets(Faction.BRAVO),
                kills.getOrDefault(viewerId, 0), deaths.getOrDefault(viewerId, 0), entries);
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
                if (redeployReadyTick.containsKey(id)) {
                    clearRedeployState(p, true);
                } else {
                    p.setInvulnerable(false);
                }
                p.getInventory().clearContent();
                BattlefieldNetwork.clearHud(p);
            }
        }
        redeployReadyTick.clear();
        deploySelection.clear();
        deployTarget.clear();
        redeployOriginalMode.clear();
        protectedUntil.clear();
        lastHurtTick.clear();
        escapeTicks.clear();
        downedUntil.clear();
        revivingTarget.clear();
        revivingStarted.clear();
        recentHits.clear();
        killStreak.clear();
        lastKilledBy.clear();
        firstBlood = false;
        clearAllEnemyGlows();
        clearAllRelativeTeams();
        clearNameTagTeams();
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

    private void syncEnemyIdentification() {
        for (UUID viewerId : new ArrayList<>(factionOf.keySet())) {
            ServerPlayer viewer = player(viewerId);
            if (!canViewerIdentify(viewer)) {
                clearEnemyGlowFor(viewerId);
                continue;
            }
            syncRelativeTeams(viewer, viewerId);
            Set<UUID> active = visibleEnemyGlows.computeIfAbsent(viewerId, ignored -> new HashSet<>());
            Set<UUID> shouldKeep = new HashSet<>();
            for (UUID targetId : factionOf.keySet()) {
                if (targetId.equals(viewerId)) {
                    continue;
                }
                ServerPlayer target = player(targetId);
                boolean show = shouldShowFriendlyGlow(viewer, target) || shouldShowEnemyGlow(viewer, target);
                if (show) {
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

    private void syncRelativeTeams(ServerPlayer viewer, UUID viewerId) {
        Faction mine = factionOf.get(viewerId);
        RelativeTeamSync.sync(viewer, factionOf.keySet(), this::player,
                id -> mine != null && mine == factionOf.get(id));
    }

    private boolean canViewerIdentify(@Nullable ServerPlayer viewer) {
        if (viewer == null || viewer.level() != level || !viewer.isAlive() || viewer.isSpectator()) {
            return false;
        }
        return !redeployReadyTick.containsKey(viewer.getUUID());
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
        if (redeployReadyTick.containsKey(targetId)) {
            return false;
        }
        if (viewer.distanceToSqr(target) > ENEMY_MARK_DISTANCE_SQR) {
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
        return viewerFaction != null && viewerFaction == targetFaction && !redeployReadyTick.containsKey(targetId);
    }

    private boolean isInFrontOf(ServerPlayer viewer, ServerPlayer target) {
        Vec3 eyes = viewer.getEyePosition();
        Vec3 toTarget = target.getEyePosition().subtract(eyes);
        if (toTarget.lengthSqr() < 0.0001D) {
            return true;
        }
        return viewer.getViewVector(1.0F).normalize().dot(toTarget.normalize()) >= ENEMY_MARK_VIEW_DOT;
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
                    || redeployReadyTick.containsKey(id)) {
                continue;
            }
            if (p.getHealth() >= p.getMaxHealth()) {
                p.removeEffect(MobEffects.REGENERATION);
                lastHurtTick.remove(id);
                continue;
            }
            long last = lastHurtTick.getOrDefault(id, now);
            if (now - last >= BREATH_HEAL_DELAY_TICKS) {
                p.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 60, 1, false, false, true));
            }
        }
    }

    // ---- HUD ----

    private void broadcastHud() {
        for (UUID id : factionOf.keySet()) {
            ServerPlayer p = player(id);
            if (p != null) {
                BattlefieldNetwork.sendBattleHud(p, buildHudFor(p));
                BattlefieldNetwork.sendBattleTab(p, buildTabFor(p));
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
            int state = p == null ? 2 : (p.isSpectator() || redeployReadyTick.containsKey(id) ? 1 : 0);
            int ping = p != null ? p.latency : -1;
            TabEntryDto dto = new TabEntryDto(name, factionCode(e.getValue()),
                    kills.getOrDefault(id, 0), deaths.getOrDefault(id, 0), ping, state);
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
                    def.markerScale(), def.markerDistance()));
        }

        Faction viewerFaction = factionOf.get(viewer.getUUID());
        List<SquadMateHudDto> squad = squadHudFor(viewer);
        FocusHud focus = focusFor(viewer, viewerFaction);
        List<DownedMateDto> downedMates = getDownedMateDtos(viewer.getUUID());
        String revivingName = getRevivingName(viewer.getUUID());
        int revivingProgress = getRevivingProgress(viewer.getUUID());

        return new BattleHudDto(
                factionCode(viewerFaction),
                tickets.displayTickets(Faction.ALPHA),
                tickets.displayTickets(Faction.BRAVO),
                Math.max(1, (int) Math.ceil(rules.startingTickets())),
                pointDtos,
                squad,
                focus.name(), focus.state(), focus.progress(), focus.faction(),
                downedMates, revivingName != null ? revivingName : "", revivingProgress);
    }

    private List<SquadMateHudDto> squadHudFor(ServerPlayer viewer) {
        List<SquadMateHudDto> squad = new ArrayList<>();
        Integer squadId = squadOf.get(viewer.getUUID());
        LinkedHashSet<UUID> members = squadId == null ? null : squads.get(squadId);
        if (members == null || members.isEmpty()) {
            addSquadMate(squad, viewer, true);
            return squad;
        }
        // 自己固定排第一。
        addSquadMate(squad, viewer, true);
        for (UUID mateId : members) {
            if (squad.size() >= SQUAD_SIZE) {
                break;
            }
            if (mateId.equals(viewer.getUUID())) {
                continue;
            }
            ServerPlayer mate = player(mateId);
            if (mate != null) {
                addSquadMate(squad, mate, false);
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

    private void addSquadMate(List<SquadMateHudDto> squad, ServerPlayer player, boolean self) {
        int hp = (int) Math.ceil((player.getHealth() / Math.max(1.0f, player.getMaxHealth())) * 100.0f);
        hp = Math.max(0, Math.min(100, hp));
        boolean downed = downedUntil.containsKey(player.getUUID());
        squad.add(new SquadMateHudDto(player.getGameProfile().getName(), hp,
                player.isAlive() || downed, self, downed));
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
            if (redeployReadyTick.containsKey(id)) {
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
            int remain = Math.max(0, ESCAPE_BOUNDARY_TICKS - ticks);
            if (remain <= 0) {
                p.kill();
                p.sendSystemMessage(Component.literal("§c你已离开战斗区域过久，被击杀。"));
                escapeTicks.remove(id);
            } else if (ticks % 60 == 0) {
                p.displayClientMessage(Component.literal("§c⚠ 返回作战区域！" + (remain / 20) + " 秒后将被击杀"), true);
                playToAll(SoundEvents.NOTE_BLOCK_BASS.value(), 0.6f);
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

    private void enterDowned(ServerPlayer p, Faction f, @Nullable UUID killerId) {
        UUID id = p.getUUID();
        long until = server.getTickCount() + DOWNED_DURATION_TICKS;
        downedUntil.put(id, until);
        p.setHealth(1.0f);
        p.setPose(Pose.SWIMMING);
        p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, DOWNED_DURATION_TICKS, 5, false, false));
        p.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, DOWNED_DURATION_TICKS, 5, false, false));
        p.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 40, 0, false, false));
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
        p.sendSystemMessage(Component.literal("§c你被 " + killerName + " 击倒了！等待队友救援 §7(" + (DOWNED_DURATION_TICKS / 20) + " 秒)"));
        p.displayClientMessage(Component.literal("§c§l倒地！等待队友救援"), true);
        for (UUID mateId : factionOf.keySet()) {
            if (mateId.equals(id)) {
                continue;
            }
            if (factionOf.get(mateId) == f) {
                ServerPlayer mate = player(mateId);
                if (mate != null) {
                    mate.displayClientMessage(Component.literal("§c" + p.getGameProfile().getName() + " 倒地 · 右键救援"), true);
                }
            }
        }
    }

    private void tickDownedPlayers() {
        if (downedUntil.isEmpty()) {
            return;
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
            if (now >= e.getValue() + REVIVE_DURATION_TICKS) {
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
                target.removeAllEffects();
                target.setPose(Pose.STANDING);
                target.setHealth(target.getMaxHealth() * 0.5f);
                target.sendSystemMessage(Component.literal("§a" + reviver.getGameProfile().getName() + " §a救起了你！"));
                target.displayClientMessage(Component.literal("§a已被救起"), true);
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

    /** 开始救援倒地队友（返回 true 表示已开始）。 */
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
        return (int) Math.min(100, Math.round((double) elapsed / REVIVE_DURATION_TICKS * 100.0));
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
        return factionOf.containsKey(id) && redeployReadyTick.containsKey(id);
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

    public int killsOf(UUID id) {
        return kills.getOrDefault(id, 0);
    }

    public int deathsOf(UUID id) {
        return deaths.getOrDefault(id, 0);
    }
}
