package org.shee33.act0.battlefield.match;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.shee33.act0.battlefield.command.BattlefieldCommand;
import org.shee33.act0.battlefield.core.ConquestRules;
import org.shee33.act0.battlefield.core.Faction;
import org.shee33.act0.battlefield.data.BattlefieldData;
import org.shee33.act0.battlefield.data.ControlPointDef;
import org.shee33.act0.battlefield.network.ActionPacket;
import org.shee33.act0.battlefield.network.BattlefieldNetwork;
import org.shee33.act0.battlefield.network.BattlefieldStatusDto;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 征服对局管理器：按世界维护候选名单（lobby）与进行中的 {@link ConquestMatch}，
 * 把 Forge 事件（服务器刻、死亡、注册命令、玩家登出、服务器关闭）路由到对局。
 *
 * <p>注册到 Forge 事件总线（{@code MinecraftForge.EVENT_BUS.register(manager)}）。同一时间可在不同世界开多场。
 */
public final class ConquestManager {

    /** 每个世界的候选名单：玩家 → 阵营，开局时转入该世界对局。 */
    private final Map<ResourceKey<Level>, Map<UUID, Faction>> lobbies = new LinkedHashMap<>();

    /** 每个世界最多一场进行中的对局。 */
    private final Map<ResourceKey<Level>, ConquestMatch> activeByWorld = new LinkedHashMap<>();

    // ---- 候选名单 ----

    public void join(ServerPlayer player, Faction faction) {
        lobbyFor(player.serverLevel()).put(player.getUUID(), faction);
        broadcastStatus(player.getServer());
    }

    public void leaveLobby(UUID id) {
        for (Map<UUID, Faction> lobby : lobbies.values()) {
            lobby.remove(id);
        }
    }

    public boolean leaveMatch(ServerPlayer player) {
        ConquestMatch match = activeContaining(player.getUUID());
        if (match == null) {
            return false;
        }
        boolean ok = match.quitPlayer(player);
        if (match.isEnded()) {
            activeByWorld.values().removeIf(ConquestMatch::isEnded);
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
    public ConquestMatch active() {
        return activeByWorld.values().stream().filter(m -> !m.isEnded()).findFirst().orElse(null);
    }

    @Nullable
    public ConquestMatch activeFor(ServerLevel level) {
        ConquestMatch match = activeByWorld.get(level.dimension());
        return match != null && !match.isEnded() ? match : null;
    }

    @Nullable
    public ConquestMatch activeContaining(UUID playerId) {
        for (ConquestMatch match : activeByWorld.values()) {
            if (!match.isEnded() && match.contains(playerId)) {
                return match;
            }
        }
        return null;
    }

    /** 供 ACT0-Arcade 游戏浏览器反射读取的大战场对局行。 */
    public List<String[]> browserRows(ServerPlayer viewer) {
        List<String[]> rows = new ArrayList<>();
        for (Map.Entry<ResourceKey<Level>, ConquestMatch> e : activeByWorld.entrySet()) {
            ConquestMatch match = e.getValue();
            if (match.isEnded()) {
                continue;
            }
            rows.add(new String[]{
                    "bf@" + e.getKey().location(),
                    "大战场 · 征服",
                    e.getKey().location().toString(),
                    "-",
                    Integer.toString(match.totalMembers()),
                    Integer.toString(match.capacityHint()),
                    "进行中",
                    Boolean.toString(match.contains(viewer.getUUID())),
                    "true",
                    match.participantNames(),
                    Integer.toString(match.elapsedSeconds())
            });
        }
        return rows;
    }

    public void quickJoin(ServerPlayer player, String key) {
        ResourceKey<Level> levelKey = null;
        if (key != null && key.startsWith("bf@")) {
            String raw = key.substring(3);
            for (ResourceKey<Level> candidate : activeByWorld.keySet()) {
                if (candidate.location().toString().equals(raw)) {
                    levelKey = candidate;
                    break;
                }
            }
        }
        ConquestMatch match = levelKey != null ? activeByWorld.get(levelKey) : activeFor(player.serverLevel());
        if (match == null || match.isEnded()) {
            player.displayClientMessage(Component.literal("§c该大战场已结束"), true);
            return;
        }
        if (match.contains(player.getUUID())) {
            player.displayClientMessage(Component.literal("§e你已在该大战场中"), true);
            return;
        }
        Faction faction = match.memberCount(Faction.ALPHA) <= match.memberCount(Faction.BRAVO)
                ? Faction.ALPHA : Faction.BRAVO;
        if (match.addLatecomer(player, faction)) {
            player.displayClientMessage(Component.literal("§a已加入 " + faction.coloredName()), true);
        } else {
            player.displayClientMessage(Component.literal("§c无法加入该大战场"), true);
        }
    }

    private Map<UUID, Faction> lobbyFor(ServerLevel level) {
        return lobbies.computeIfAbsent(level.dimension(), ignored -> new LinkedHashMap<>());
    }

    /**
     * 用当前候选名单开局。
     *
     * @return 失败原因；{@code null} 表示成功
     */
    @Nullable
    public String start(ServerLevel level, ConquestRules rules) {
        if (activeFor(level) != null) {
            return "§c该世界已有进行中的大战场对局。";
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
        ConquestMatch active = new ConquestMatch(level, rules, defs, new LinkedHashMap<>(lobby), data);
        activeByWorld.put(level.dimension(), active);
        lobby.clear();
        active.begin();
        return null;
    }

    /** 中止当前对局。 */
    public boolean stop(ServerLevel level) {
        ConquestMatch active = activeFor(level);
        if (active != null) {
            active.abort();
            activeByWorld.remove(level.dimension());
            return true;
        }
        return false;
    }

    // ---- 加入界面（UI）----

    /** 处理加入界面的玩家操作，并回推最新状态。 */
    public void handleAction(ServerPlayer player, ActionPacket.Action action) {
        switch (action) {
            case JOIN_ALPHA, JOIN_BRAVO -> {
                ConquestMatch active = activeFor(player.serverLevel());
                if (active != null) {
                    Faction faction = ActionPacket.factionOf(action);
                    if (active.addLatecomer(player, faction)) {
                        return;
                    }
                    player.sendSystemMessage(Component.literal("§c该世界对局进行中，无法加入。"));
                } else {
                    lobbyFor(player.serverLevel()).put(player.getUUID(), ActionPacket.factionOf(action));
                    broadcastStatus(player.getServer());
                    return;
                }
            }
            case LEAVE -> leaveLobby(player.getUUID());
            case START -> {
                if (!player.hasPermissions(2)) {
                    player.sendSystemMessage(Component.literal("§c只有管理员可以开局。"));
                } else {
                    String err = start(player.serverLevel(), ConquestRules.standard());
                    if (err != null) {
                        player.sendSystemMessage(Component.literal(err));
                    } else {
                        broadcastStatus(player.getServer());
                        return;
                    }
                }
            }
            case STOP -> {
                if (!player.hasPermissions(2)) {
                    player.sendSystemMessage(Component.literal("§c只有管理员可以停止。"));
                } else {
                    stop(player.serverLevel());
                    broadcastStatus(player.getServer());
                    return;
                }
            }
            case OPEN -> {
                openFor(player);
                return;
            }
            case OPEN_LOADOUT -> {
                openArcadeLoadout(player);
                return;
            }
            case REFRESH -> {
            }
        }
        // 单人即时反馈（刷新自己的界面）
        BattlefieldNetwork.sendStatus(player, false, snapshotFor(player));
    }

    /** 主动为玩家打开加入界面。 */
    public void openFor(ServerPlayer player) {
        BattlefieldNetwork.sendStatus(player, true, snapshotFor(player));
    }

    private void openArcadeLoadout(ServerPlayer player) {
        ConquestMatch active = activeContaining(player.getUUID());
        if (active == null || active.factionOf(player.getUUID()) == null) {
            return;
        }
        try {
            Class<?> network = Class.forName("org.shee33.act0.arcade.network.ArcadeNetwork");
            network.getMethod("openLoadoutSelector", ServerPlayer.class).invoke(null, player);
        } catch (ReflectiveOperationException e) {
            player.displayClientMessage(Component.literal("§c未安装 ACT0-Arcade，无法打开配装"), true);
        }
    }

    /** 为某玩家构建一份状态快照。 */
    public BattlefieldStatusDto snapshotFor(ServerPlayer player) {
        boolean canManage = player.hasPermissions(2);
        UUID id = player.getUUID();
        ConquestMatch active = activeFor(player.serverLevel());
        if (active != null) {
            int my = factionToCode(active.factionOf(id));
            return new BattlefieldStatusDto(true, canManage, my,
                    active.memberCount(Faction.ALPHA), active.memberCount(Faction.BRAVO),
                    active.displayTickets(Faction.ALPHA), active.displayTickets(Faction.BRAVO),
                    active.ownedPoints(Faction.ALPHA), active.ownedPoints(Faction.BRAVO),
                    active.totalPoints());
        }
        Map<UUID, Faction> lobby = lobbyFor(player.serverLevel());
        Faction mine = lobby.get(id);
        int alpha = 0;
        int bravo = 0;
        for (Faction f : lobby.values()) {
            if (f == Faction.ALPHA) {
                alpha++;
            } else {
                bravo++;
            }
        }
        return new BattlefieldStatusDto(false, canManage, factionToCode(mine),
                alpha, bravo, 0, 0, 0, 0, 0);
    }

    private static int factionToCode(@Nullable Faction f) {
        if (f == Faction.ALPHA) {
            return 1;
        }
        if (f == Faction.BRAVO) {
            return 2;
        }
        return 0;
    }

    /** 向所有在线玩家刷新状态（仅刷新已打开的界面）。 */
    public void broadcastStatus(@Nullable net.minecraft.server.MinecraftServer server) {
        if (server == null) {
            return;
        }
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            BattlefieldNetwork.sendStatus(p, false, snapshotFor(p));
        }
    }

    // ---- 事件 ----

    @SubscribeEvent
    public void onCommand(CommandEvent event) {
        if (!(event.getParseResults().getContext().getSource().getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (player.hasPermissions(2) || activeContaining(player.getUUID()) == null) {
            return;
        }
        String raw = event.getParseResults().getReader().getString().trim();
        String cmd = raw.startsWith("/") ? raw.substring(1) : raw;
        if (cmd.equals("suicide") || cmd.startsWith("battlefield leave")) {
            return;
        }
        event.setCanceled(true);
        player.sendSystemMessage(Component.literal("§c大战场中只能使用 /battlefield leave 或 /suicide。"));
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        BattlefieldCommand.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || activeByWorld.isEmpty()) {
            return;
        }
        List<ResourceKey<Level>> ended = new ArrayList<>();
        for (Map.Entry<ResourceKey<Level>, ConquestMatch> e : activeByWorld.entrySet()) {
            e.getValue().tick();
            if (e.getValue().isEnded()) {
                ended.add(e.getKey());
            }
        }
        if (!ended.isEmpty()) {
            for (ResourceKey<Level> key : ended) {
                activeByWorld.remove(key);
            }
            broadcastStatus(event.getServer());
        }
    }

    @SubscribeEvent
    public void onLivingDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer victim)) {
            return;
        }
        ConquestMatch active = activeContaining(victim.getUUID());
        if (active == null) {
            return;
        }
        if (active.onDeath(victim.getUUID(), resolveKiller(event.getSource().getEntity(), event.getSource().getDirectEntity()))) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onLivingHurt(LivingHurtEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer victim)) {
            return;
        }
        ConquestMatch active = activeContaining(victim.getUUID());
        if (active == null) {
            return;
        }
        UUID attacker = resolveKiller(event.getSource().getEntity(), event.getSource().getDirectEntity());
        if (active.shouldCancelDamage(victim.getUUID(), attacker)) {
            event.setCanceled(true);
        }
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
        leaveLobby(id);
    }
    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ConquestMatch active = activeContaining(player.getUUID());
            if (active == null) {
                return;
            }
            active.onPlayerLogin(player);
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        for (ConquestMatch match : activeByWorld.values()) {
            match.abort();
        }
        activeByWorld.clear();
        lobbies.clear();
    }
}
