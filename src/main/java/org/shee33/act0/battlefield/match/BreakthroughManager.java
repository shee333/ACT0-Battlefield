package org.shee33.act0.battlefield.match;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import org.shee33.act0.battlefield.command.BreakthroughCommand;
import org.shee33.act0.battlefield.core.BreakthroughRules;
import org.shee33.act0.battlefield.core.Faction;
import org.shee33.act0.battlefield.data.BattlefieldData;
import org.shee33.act0.battlefield.data.ControlPointDef;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 突破对局管理器：按世界维护候选名单（lobby）与进行中的 {@link BreakthroughMatch}，
 * 把 Forge 事件（服务器刻、死亡、注册命令、玩家登出、服务器关闭）路由到对局。
 *
 * <p>注册到 Forge 事件总线（{@code MinecraftForge.EVENT_BUS.register(manager)}）。同一时间可在不同世界开多场。
 *
 * <p>当前为骨架阶段：尚未集成地图模板维度，对局与候选名单共享同一个世界。
 */
public final class BreakthroughManager {

    /** 每个世界的候选名单：玩家 → 阵营，开局时转入该世界对局。 */
    private final Map<ResourceKey<Level>, Map<UUID, Faction>> lobbies = new LinkedHashMap<>();

    /** 每个世界最多一场进行中的对局。 */
    private final Map<ResourceKey<Level>, BreakthroughMatch> activeByWorld = new LinkedHashMap<>();
    /** 进行中大战场名称：世界 → 战役名。 */
    private final Map<ResourceKey<Level>, String> battleNames = new LinkedHashMap<>();
    /** 每个对局对应的大厅世界（用于赛后传送等）。当前与对局世界相同，保留字段为后续模板维度集成做准备。 */
    private final Map<ResourceKey<Level>, ServerLevel> lobbyWorlds = new LinkedHashMap<>();
    /** 每个对局开始时所在的服务器 tick，供 browserRows 估算已用秒数。 */
    private final Map<ResourceKey<Level>, Long> matchStartedTick = new LinkedHashMap<>();

    private static final Logger LOGGER = LogUtils.getLogger();

    // ---- 候选名单 ----

    public void join(ServerLevel level, ServerPlayer player, Faction faction) {
        lobbyFor(level).put(player.getUUID(), faction);
        broadcastStatus(player.getServer());
    }

    public void leaveLobby(UUID id) {
        for (Map<UUID, Faction> lobby : lobbies.values()) {
            lobby.remove(id);
        }
    }

    /** 把当前对局中指定玩家移除（quitPlayer 由 match 内部完成传送/清理）。 */
    public boolean leave(ServerPlayer player) {
        BreakthroughMatch match = activeContaining(player.getUUID());
        if (match == null) {
            return false;
        }
        boolean ok = match.quitPlayer(player);
        if (match.isEnded()) {
            activeByWorld.values().removeIf(BreakthroughMatch::isEnded);
            lobbyWorlds.keySet().removeIf(key -> !activeByWorld.containsKey(key)
                    || activeByWorld.get(key).isEnded());
            battleNames.keySet().removeIf(key -> !activeByWorld.containsKey(key));
            matchStartedTick.keySet().removeIf(key -> !activeByWorld.containsKey(key));
        }
        broadcastStatus(player.getServer());
        return ok;
    }

    public Map<UUID, Faction> lobby() {
        Map<UUID, Faction> all = new LinkedHashMap<>();
        for (Map<UUID, Faction> lobby : lobbies.values()) {
            all.putAll(lobby);
        }
        return all;
    }

    public boolean hasActive() {
        return activeByWorld.values().stream().anyMatch(m -> !m.isEnded());
    }

    @Nullable
    public BreakthroughMatch active() {
        return activeByWorld.values().stream().filter(m -> !m.isEnded()).findFirst().orElse(null);
    }

    @Nullable
    public BreakthroughMatch activeFor(ServerLevel level) {
        BreakthroughMatch match = activeByWorld.get(level.dimension());
        return match != null && !match.isEnded() ? match : null;
    }

    @Nullable
    public BreakthroughMatch activeContaining(UUID playerId) {
        for (BreakthroughMatch match : activeByWorld.values()) {
            if (!match.isEnded() && match.contains(playerId)) {
                return match;
            }
        }
        return null;
    }

    /** 供 ACT0-Arcade 游戏浏览器反射读取的突破对局行。 */
    public List<String[]> browserRows(ServerPlayer viewer) {
        List<String[]> rows = new ArrayList<>();
        for (Map.Entry<ResourceKey<Level>, BreakthroughMatch> e : activeByWorld.entrySet()) {
            BreakthroughMatch match = e.getValue();
            if (match.isEnded()) {
                continue;
            }
            String battleName = battleNames.getOrDefault(e.getKey(), defaultBattleName(e.getKey()));
            int elapsed = elapsedSecondsFor(e.getKey(), viewer.getServer());
            rows.add(new String[]{
                    "bt@" + e.getKey().location(),
                    "突破 · " + battleName,
                    e.getKey().location().toString(),
                    "-",
                    Integer.toString(match.totalMembers()),
                    Integer.toString(match.capacityHint()),
                    "进行中",
                    Boolean.toString(match.contains(viewer.getUUID())),
                    "true",
                    participantNamesFor(match, viewer.getServer()),
                    Integer.toString(elapsed)
            });
        }
        return rows;
    }

    private int elapsedSecondsFor(ResourceKey<Level> key, @Nullable MinecraftServer server) {
        Long started = matchStartedTick.get(key);
        if (started == null || server == null) {
            return 0;
        }
        return (int) Math.max(0L, (server.getTickCount() - started) / 20L);
    }

    private String participantNamesFor(BreakthroughMatch match, @Nullable MinecraftServer server) {
        if (server == null) {
            return "";
        }
        List<String> names = new ArrayList<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (match.contains(p.getUUID())) {
                names.add(p.getGameProfile().getName());
            }
        }
        return String.join(", ", names);
    }

    private Map<UUID, Faction> lobbyFor(ServerLevel level) {
        return lobbies.computeIfAbsent(level.dimension(), ignored -> new LinkedHashMap<>());
    }

    /**
     * 用当前候选名单开局，使用默认战役名。
     *
     * @return 失败原因；{@code null} 表示成功
     */
    @Nullable
    public String start(ServerLevel level, BreakthroughRules rules) {
        return start(level, rules, defaultBattleName(level.dimension()), null);
    }

    /** 用当前候选名单开局并命名战役。 */
    @Nullable
    public String start(ServerLevel level, BreakthroughRules rules, String battleName) {
        return start(level, rules, battleName, null);
    }

    /**
     * 用当前候选名单开局，可选加载地图模板。
     *
     * <p>当前为骨架阶段：{@code templateName} 参数被忽略，对局与候选名单共享同一世界（{@code level}）。
     *
     * @param level        大厅世界（候选名单所在世界）
     * @param rules        对局规则
     * @param battleName   战役名称
     * @param templateName 模板名称；当前版本未生效
     * @return 失败原因；{@code null} 表示成功
     */
    @Nullable
    public String start(ServerLevel level, BreakthroughRules rules, String battleName, @Nullable String templateName) {
        if (activeFor(level) != null) {
            return "§c该世界已有进行中的突破对局。";
        }
        Map<UUID, Faction> lobby = lobbyFor(level);
        if (lobby.isEmpty()) {
            return "§c还没有玩家选择阵营。";
        }
        BattlefieldData data = BattlefieldData.get(level);
        List<ControlPointDef> defs = data.points();
        if (defs.isEmpty()) {
            return "§c该世界尚未布置任何据点。";
        }
        if (data.base(Faction.ALPHA) == null || data.base(Faction.BRAVO) == null) {
            return "§c两个阵营的基地出生点都需先设置。";
        }
        if (data.sectors().isEmpty()) {
            return "§c尚未登记任何突破模式区域（sector）。";
        }
        // 模板维度集成尚未启用：直接在大厅世界中开局。
        ServerLevel matchLevel = level;
        BreakthroughMatch active = new BreakthroughMatch(matchLevel, level, rules, defs, new LinkedHashMap<>(lobby), data);
        ResourceKey<Level> key = matchLevel.dimension();
        activeByWorld.put(key, active);
        battleNames.put(key, normalizeBattleName(battleName, key));
        lobbyWorlds.put(key, level);
        matchStartedTick.put(key, (long) matchLevel.getServer().getTickCount());
        lobby.clear();
        active.begin();
        return null;
    }

    /** 中止当前对局。 */
    public boolean stop(ServerLevel level) {
        BreakthroughMatch active = activeFor(level);
        if (active != null) {
            active.abort();
            ResourceKey<Level> key = level.dimension();
            activeByWorld.remove(key);
            battleNames.remove(key);
            lobbyWorlds.remove(key);
            matchStartedTick.remove(key);
            return true;
        }
        return false;
    }

    // ---- 状态广播 ----

    /**
     * 向所有在线玩家刷新候选名单/对局状态。
     *
     * <p>当前为骨架阶段：突破模式尚未提供 {@code BattlefieldNetwork.sendStatus} 等价的网络通道，
     * 仅以聊天消息形式广播候选名单概要；后续可替换为专用 DTO / 网络包。
     */
    public void broadcastStatus(@Nullable MinecraftServer server) {
        if (server == null) {
            return;
        }
        int alpha = 0;
        int bravo = 0;
        for (Map<UUID, Faction> lobby : lobbies.values()) {
            for (Faction f : lobby.values()) {
                if (f == Faction.ALPHA) {
                    alpha++;
                } else if (f == Faction.BRAVO) {
                    bravo++;
                }
            }
        }
        for (BreakthroughMatch match : activeByWorld.values()) {
            if (match.isEnded()) {
                continue;
            }
            int ma = 0;
            int mb = 0;
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                Faction f = match.factionOf(p.getUUID());
                if (f == Faction.ALPHA) {
                    ma++;
                } else if (f == Faction.BRAVO) {
                    mb++;
                }
            }
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (match.contains(p.getUUID())) {
                    p.sendSystemMessage(Component.literal("§7突破进行中 §e" + match.attackerTickets()
                            + " §7票 §8| §9进攻 §f" + ma + " §8/ §c防守 §f" + mb
                            + " §8| §7区域 §f" + match.currentSectorIndex() + "§7/§f" + match.totalSectors()));
                }
            }
        }
        if (alpha + bravo == 0) {
            return;
        }
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            p.sendSystemMessage(Component.literal("§7突破候选 §9进攻 §f" + alpha + " §8/ §c防守 §f" + bravo));
        }
    }

    // ---- 事件 ----

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        BreakthroughCommand.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || activeByWorld.isEmpty()) {
            return;
        }
        MinecraftServer server = event.getServer();
        List<ResourceKey<Level>> ended = new ArrayList<>();
        for (Map.Entry<ResourceKey<Level>, BreakthroughMatch> e : activeByWorld.entrySet()) {
            e.getValue().tick();
            if (e.getValue().isEnded()) {
                ended.add(e.getKey());
            }
        }
        if (!ended.isEmpty()) {
            for (ResourceKey<Level> key : ended) {
                activeByWorld.remove(key);
                battleNames.remove(key);
                lobbyWorlds.remove(key);
                matchStartedTick.remove(key);
            }
            broadcastStatus(server);
        }
    }

    @SubscribeEvent
    public void onLivingDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer victim)) {
            return;
        }
        BreakthroughMatch active = activeContaining(victim.getUUID());
        if (active == null) {
            return;
        }
        UUID attacker = resolveKiller(event.getSource().getEntity(), event.getSource().getDirectEntity());
        if (active.onDeath(victim.getUUID(), attacker)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onLivingHurt(LivingHurtEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer victim)) {
            return;
        }
        BreakthroughMatch active = activeContaining(victim.getUUID());
        if (active == null) {
            return;
        }
        UUID attacker = resolveKiller(event.getSource().getEntity(), event.getSource().getDirectEntity());
        if (active.shouldCancelDamage(victim.getUUID(), attacker)) {
            event.setCanceled(true);
            return;
        }
        active.onHurt(victim.getUUID(), attacker);
    }

    private UUID resolveKiller(Entity attacker, Entity direct) {
        if (attacker instanceof ServerPlayer killer) {
            return killer.getUUID();
        }
        if (direct instanceof Projectile projectile && projectile.getOwner() instanceof ServerPlayer shooter) {
            return shooter.getUUID();
        }
        return null;
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id = event.getEntity().getUUID();
        if (event.getEntity() instanceof ServerPlayer player) {
            leave(player);
        }
        leaveLobby(id);
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        // 不再自动归位；玩家可通过突破命令 / 游戏浏览器中途加入。
    }

    @SubscribeEvent
    public void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        handleRevive(event.getEntity(), event.getTarget(), event);
    }

    @SubscribeEvent
    public void onEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        handleRevive(event.getEntity(), event.getTarget(), event);
    }

    private void handleRevive(Entity clicker, Entity target, PlayerInteractEvent event) {
        if (!(clicker instanceof ServerPlayer reviver)) {
            return;
        }
        if (!(target instanceof ServerPlayer downed)) {
            return;
        }
        BreakthroughMatch match = activeContaining(reviver.getUUID());
        if (match == null) {
            return;
        }
        if (!match.isDowned(downed.getUUID())) {
            return;
        }
        if (match.reviveDownedPlayer(downed.getUUID(), reviver)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        for (BreakthroughMatch match : activeByWorld.values()) {
            match.abort();
        }
        activeByWorld.clear();
        battleNames.clear();
        lobbyWorlds.clear();
        matchStartedTick.clear();
        lobbies.clear();
    }

    private static String defaultBattleName(ResourceKey<Level> key) {
        return key.location().getPath();
    }

    private static String normalizeBattleName(String name, ResourceKey<Level> key) {
        if (name == null || name.isBlank()) {
            return defaultBattleName(key);
        }
        String trimmed = name.trim();
        return trimmed.length() > 32 ? trimmed.substring(0, 32) : trimmed;
    }
}
