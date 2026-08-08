package org.shee33.act0.battlefield.match;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import org.shee33.act0.battlefield.core.CapturePoint;
import org.shee33.act0.battlefield.core.Faction;
import org.shee33.act0.battlefield.data.ControlPointDef;
import org.shee33.act0.battlefield.network.BattlefieldNetwork;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tracks kills, deaths, streaks, first blood, assists, and revenge for a single match.
 *
 * <p>Extracted from {@link ConquestMatch} to keep kill/death logic self-contained.
 * This class owns the data maps and the {@link #handleKillCredit} orchestration,
 * but reads faction/player/point context via constructor-injected references.
 */
public final class KillTracker {

    private final Map<UUID, Faction> factionOf;
    private final MinecraftServer server;
    private final List<CapturePoint> points;
    private final List<ControlPointDef> defs;

    private final Map<UUID, Integer> kills = new LinkedHashMap<>();
    private final Map<UUID, Integer> deaths = new LinkedHashMap<>();
    private final Map<UUID, Integer> killStreak = new LinkedHashMap<>();
    private final Map<UUID, UUID> lastKilledBy = new LinkedHashMap<>();
    private final Map<UUID, Map<UUID, Long>> recentHits = new LinkedHashMap<>();
    private boolean firstBlood;

    /**
     * @param factionOf shared faction roster (read-only from this tracker's perspective)
     * @param server    the Minecraft server (for tick counts and player lookups)
     * @param points    capture points (for defence-kill detection)
     * @param defs      capture-point definitions (for defence-kill zone/name queries)
     */
    public KillTracker(Map<UUID, Faction> factionOf, MinecraftServer server,
                       List<CapturePoint> points, List<ControlPointDef> defs) {
        this.factionOf = factionOf;
        this.server = server;
        this.points = points;
        this.defs = defs;
    }

    // ---- lifecycle helpers (called by ConquestMatch) ----

    /** Register a new player with zero kills/deaths. */
    public void initPlayer(UUID id) {
        kills.put(id, 0);
        deaths.put(id, 0);
    }

    /** Remove all tracking data for a leaving player. */
    public void removePlayer(UUID id) {
        kills.remove(id);
        deaths.remove(id);
        killStreak.remove(id);
        lastKilledBy.remove(id);
        recentHits.remove(id);
    }

    /** Record a death (increment deaths counter). Called before handleKillCredit. */
    public void recordDeath(UUID victimId) {
        deaths.merge(victimId, 1, Integer::sum);
    }

    /** Called when a player enters the downed state: reset their streak, record killer. */
    public void onDowned(UUID victimId, @Nullable UUID killerId) {
        killStreak.put(victimId, 0);
        lastKilledBy.put(victimId, killerId);
    }

    /** Record a hit from {@code attackerId} on {@code victimId} at the given server tick. */
    public void recordHit(UUID victimId, UUID attackerId, long tick) {
        recentHits.computeIfAbsent(victimId, k -> new LinkedHashMap<>())
                .put(attackerId, tick);
    }

    /** Clear transient tracking state (streaks, hits, first blood) without wiping kills/deaths. */
    public void clearTransient() {
        recentHits.clear();
        killStreak.clear();
        lastKilledBy.clear();
        firstBlood = false;
    }

    // ---- core kill-credit logic (moved verbatim from ConquestMatch) ----

    /**
     * Grant a kill to {@code killerId}, send kill-feed, update streaks,
     * award first-blood / revenge / defence-kill bonuses, and credit assists.
     */
    public void handleKillCredit(UUID victimId, @Nullable UUID killerId) {
        if (killerId == null || killerId.equals(victimId)) {
            return;
        }
        Faction victimFaction = factionOf.get(victimId);
        Faction killerFaction = factionOf.get(killerId);
        if (victimFaction == null || killerFaction == null || victimFaction == killerFaction) {
            return;
        }
        kills.merge(killerId, 1, Integer::sum);
        ServerPlayer killer = server.getPlayerList().getPlayer(killerId);
        ServerPlayer victim = server.getPlayerList().getPlayer(victimId);
        String killerName = killer != null ? killer.getGameProfile().getName() : "未知";
        String victimName = victim != null ? victim.getGameProfile().getName() : "未知";
        String weapon = "";
        if (killer != null) {
            var item = killer.getMainHandItem();
            if (!item.isEmpty()) {
                weapon = item.getHoverName().getString();
            }
        }
        for (UUID id : factionOf.keySet()) {
            ServerPlayer viewer = server.getPlayerList().getPlayer(id);
            if (viewer != null) {
                BattlefieldNetwork.sendKillFeed(viewer, killerName, victimName,
                        ConquestMatch.factionCode(killerFaction),
                        ConquestMatch.factionCode(victimFaction), weapon);
            }
        }
        if (killer != null) {
            BattlefieldNetwork.sendHitFeedback(killer, true);
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
                    killer.displayClientMessage(
                            Component.literal("§b守点击杀 §e" + defs.get(i).name()), true);
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
                    ServerPlayer assister = server.getPlayerList().getPlayer(e.getKey());
                    if (assister != null) {
                        assister.displayClientMessage(
                                Component.literal("§e助攻 " + victimName), true);
                        assister.playNotifySound(SoundEvents.EXPERIENCE_ORB_PICKUP,
                                SoundSource.MASTER, 0.4f, 1.1f);
                    }
                }
            }
        }
    }

    // ---- queries ----

    public int killsOf(UUID id) {
        return kills.getOrDefault(id, 0);
    }

    public int deathsOf(UUID id) {
        return deaths.getOrDefault(id, 0);
    }

    /** Expose kill-streak value for a single player (HUD). */
    public int killStreakOf(UUID id) {
        return killStreak.getOrDefault(id, 0);
    }

    // ---- data accessors (read-only reference to internal maps) ----

    public Map<UUID, Integer> getKills() {
        return kills;
    }

    public Map<UUID, Integer> getDeaths() {
        return deaths;
    }

    public Map<UUID, Integer> getKillStreak() {
        return killStreak;
    }

    public Map<UUID, UUID> getLastKilledBy() {
        return lastKilledBy;
    }

    public Map<UUID, Map<UUID, Long>> getRecentHits() {
        return recentHits;
    }
}
