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
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;
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
import org.shee33.act0.battlefield.network.BattlefieldNetwork;
import org.shee33.act0.battlefield.network.BreakthroughHudDto;
import org.shee33.act0.battlefield.network.BreakthroughPointDto;
import org.shee33.act0.battlefield.network.CapturePointEventPacket;
import org.shee33.act0.battlefield.network.DownedActionPacket;
import org.shee33.act0.battlefield.network.SquadMateHudDto;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 一场突破对局：攻击方（ALPHA）按扇区推进占领，防守方（BRAVO）严防死守。
 *
 * <p>每 {@link #captureInterval} 刻结算占点：统计各据点区域内双方人数，按扇区顺序
 * 解锁推进。攻击方票数归零判负；攻占全部扇区判胜。死亡接管与 ConquestMatch 一致。
 */
public final class BreakthroughMatch {

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

    private final MinecraftServer server;
    private final ServerLevel level;
    private final ServerLevel lobbyLevel;
    private final BreakthroughRules rules;
    private final BattlefieldData data;
    private final List<ControlPointDef> defs;
    private final List<CapturePoint> points;
    private final List<Sector> sectors;
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
        this.attackerTickets = rules.startingTickets();
        this.currentSectorIndex = 0;
        this.defs = new ArrayList<>(defs);
        this.points = new ArrayList<>(defs.size());
        for (ControlPointDef def : this.defs) {
            this.points.add(new CapturePoint(def.pointId(), def.name()));
        }
        this.sectors = data.sectors();
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
            BattlefieldNetwork.sendBreakthroughHud(p, buildHudFor(p));
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
                    || prevStatus == CapturePoint.CaptureStatus.CAPTURING;
            CapturePoint.CaptureStatus st = points.get(i).tick(alpha, bravo, captureRules, captureDelta);
            lastCaptureStatus.put(pointId, st);
            // 突破模式单向推进，无需 LOST/CAPTURED_RECOVERED 语义：只发 STARTED（首次进入争夺/推进）
            // 与 CAPTURED_NEW（首次占领确认），驱动 HUD 顶部横幅一次性反馈。
            if (st == CapturePoint.CaptureStatus.CAPTURED) {
                Faction owner = points.get(i).owner();
                if (owner != null) {
                    sendCapturePointEvent(pointId, CapturePointEventPacket.Kind.CAPTURED_NEW, factionCode(owner));
                }
            } else if (st == CapturePoint.CaptureStatus.CONTESTED) {
                if (!wasActiveContest) {
                    sendCapturePointEvent(pointId, CapturePointEventPacket.Kind.STARTED, 0);
                }
            } else if (st == CapturePoint.CaptureStatus.CAPTURING) {
                if (!wasActiveContest) {
                    Faction pushing = alpha > 0 ? Faction.ALPHA : Faction.BRAVO;
                    sendCapturePointEvent(pointId, CapturePointEventPacket.Kind.STARTED, factionCode(pushing));
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
        killTracker.clearTransient();
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
        killTracker.clearTransient();
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
            pointDtos.add(new BreakthroughPointDto(
                    def.pointId(), cp.displayName(), owner, pressure, progress, locked));
        }

        List<SquadMateHudDto> squad = squadHudFor(viewer);

        return new BreakthroughHudDto(true,
                (int) attackerTickets, rules.startingTickets(),
                currentSectorIndex, sectors.size(),
                pointDtos, squad, phase, winnerCode);
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
