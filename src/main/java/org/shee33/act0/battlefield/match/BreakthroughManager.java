package org.shee33.act0.battlefield.match;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.shee33.act0.battlefield.deployable.DeployableKind;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import org.shee33.act0.battlefield.Act0Battlefield;
import org.shee33.act0.battlefield.BattlefieldConfig;
import org.shee33.act0.battlefield.command.BreakthroughCommand;
import org.shee33.act0.battlefield.core.BreakthroughRules;
import org.shee33.act0.battlefield.core.Faction;
import org.shee33.act0.battlefield.core.LatecomerAssignment;
import org.shee33.act0.battlefield.core.MatchCapacity;
import org.shee33.act0.battlefield.data.BattlefieldData;
import org.shee33.act0.battlefield.data.ControlPointDef;
import org.shee33.act0.battlefield.network.BattlefieldRoomDto;
import org.shee33.act0.battlefield.network.DownedActionPacket;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;

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

    /**
     * 加入候选名单，阵营由服务端随机分配（玩家不再自行选边）。
     *
     * @return 分到的阵营；名单已满返回 {@code null}
     */
    @Nullable
    public Faction join(ServerLevel level, ServerPlayer player) {
        if (!joinLobby(player, level)) {
            return null;
        }
        broadcastStatus(player.getServer());
        Act0Battlefield.broadcastRoomList(player.getServer());
        tryAutoStart(level);
        return lobbyFor(level).get(player.getUUID());
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
        Act0Battlefield.broadcastRoomList(player.getServer());
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

    /**
     * 对局浏览器快照：涵盖"运行中"的对局与"地图已布置完毕、正等待玩家凑够人数"的待命世界
     * （见 {@link BattlefieldData#isBreakthroughReady()}）。与 {@link ConquestManager#snapshotRooms}
     * 同一套实现模式。
     */
    public List<BattlefieldRoomDto> snapshotRooms(MinecraftServer server, UUID viewerId) {
        List<BattlefieldRoomDto> rows = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            int minPlayers = minPlayersFor(level);
            int maxPlayers = maxPlayersFor(level);
            ResourceKey<Level> key = level.dimension();
            BreakthroughMatch match = activeByWorld.get(key);
            if (match != null && !match.isEnded()) {
                rows.add(new BattlefieldRoomDto(
                        "bt@" + key.location(),
                        battleNames.getOrDefault(key, defaultBattleName(key)),
                        true,
                        mapNameOrFallback(level, key),
                        true,
                        match.totalMembers(),
                        maxPlayers,
                        minPlayers,
                        match.contains(viewerId),
                        Faction.ALPHA.coloredName(),
                        Faction.BRAVO.coloredName(),
                        match.displayTickets(Faction.ALPHA),
                        match.displayTickets(Faction.BRAVO),
                        match.startingTicketsHint(),
                        elapsedSecondsFor(key, server)));
                continue;
            }
            BattlefieldData data = BattlefieldData.get(level);
            if (!data.isBreakthroughReady()) {
                continue;
            }
            Map<UUID, Faction> lobby = lobbies.get(key);
            int cur = lobby != null ? lobby.size() : 0;
            boolean viewerIn = lobby != null && lobby.containsKey(viewerId);
            rows.add(new BattlefieldRoomDto(
                    "bt@" + key.location(),
                    defaultBattleName(key),
                    true,
                    mapNameOrFallback(level, key),
                    false,
                    cur,
                    maxPlayers,
                    minPlayers,
                    viewerIn,
                    Faction.ALPHA.coloredName(),
                    Faction.BRAVO.coloredName(),
                    0, 0, 0, 0));
        }
        return rows;
    }

    private static String mapNameOrFallback(ServerLevel level, ResourceKey<Level> key) {
        String name = BattlefieldData.get(level).mapName();
        return name.isBlank() ? key.location().toString() : name;
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

    /**
     * 供 ACT0-Arcade 房间浏览器调用：把玩家加入一个正在进行中的突破对局（非候选名单）。
     * 与 {@link ConquestManager#quickJoin} 同一套实现模式：按 roomKey 定位对局，校验
     * 存在/未满/玩家未在其中，自动分配到人少的一方，再调用 {@link BreakthroughMatch#addLatecomer}。
     *
     * @param key {@code browserRows()} 中返回的 room key（{@code bt@<dimension>} 格式），
     *            也接受裸维度 key（不带 {@code bt@} 前缀）
     */
    public void quickJoin(ServerPlayer player, String key) {
        ResourceKey<Level> levelKey = resolveQuickJoinKey(key);
        BreakthroughMatch match = levelKey != null ? activeByWorld.get(levelKey) : activeFor(player.serverLevel());
        if (match == null || match.isEnded()) {
            // 没有进行中对局：这个 key 可能对应一个待命世界，加入方式等价于候选名单加入
            // （随机分配阵营），凑够人数后自动开局（与 ConquestManager#quickJoin 同一套模式）。
            quickJoinStandby(player, key);
            return;
        }
        if (match.contains(player.getUUID())) {
            player.displayClientMessage(Component.literal("§e你已在该突破对局中"), true);
            return;
        }
        // 中途加入随机分配阵营，并执行该地图的人数上限（与 ConquestManager 同构）。
        Faction faction = assignFactionForMatch(match.level(), match);
        if (faction == null) {
            player.displayClientMessage(Component.literal("§c该突破对局已满员"), true);
            return;
        }
        if (match.addLatecomer(player, faction)) {
            ResourceKey<Level> battleKey = levelKey != null ? levelKey : player.serverLevel().dimension();
            player.displayClientMessage(Component.literal("§a已加入 "
                + battleNames.getOrDefault(battleKey, defaultBattleName(battleKey)) + " §7- " + faction.coloredName()), true);
        } else {
            player.displayClientMessage(Component.literal("§c无法加入该突破对局"), true);
        }
    }

    @Nullable
    private ResourceKey<Level> resolveQuickJoinKey(String key) {
        String raw = normalizeQuickJoinKey(key);
        if (raw.isBlank()) {
            return null;
        }
        if (raw.startsWith("bt@")) {
            raw = raw.substring(3).trim();
        }
        for (Map.Entry<ResourceKey<Level>, BreakthroughMatch> e : activeByWorld.entrySet()) {
            if (e.getValue().isEnded()) {
                continue;
            }
            if (e.getKey().location().toString().equals(raw)) {
                return e.getKey();
            }
        }
        return null;
    }

    private static String normalizeQuickJoinKey(String key) {
        if (key == null) {
            return "";
        }
        String raw = key.trim();
        while (raw.length() >= 2 && ((raw.startsWith("\"") && raw.endsWith("\""))
                || (raw.startsWith("'") && raw.endsWith("'")))) {
            raw = raw.substring(1, raw.length() - 1).trim();
        }
        return raw;
    }

    /** 该世界生效的自动开始人数（地图自定义优先，未设则用全局配置）。 */
    public int minPlayersFor(ServerLevel level) {
        return BattlefieldData.get(level).effectiveMinPlayers(BattlefieldConfig.MIN_PLAYERS_TO_START.get());
    }

    /** 该世界生效的对局人数上限（地图自定义优先，未设则用全局配置）。 */
    public int maxPlayersFor(ServerLevel level) {
        return BattlefieldData.get(level).effectiveMaxPlayers(BattlefieldConfig.MAX_PLAYERS.get());
    }

    /**
     * 为加入进行中对局的玩家随机分配阵营，同时执行该地图的人数上限。总量校验必须排在单边
     * 分配之前，理由见 {@link MatchCapacity#perSideCap}（单边容量是向上取整的）。
     *
     * @return 分到的阵营；已满员返回 {@code null}
     */
    @Nullable
    private Faction assignFactionForMatch(ServerLevel level, BreakthroughMatch match) {
        int max = maxPlayersFor(level);
        if (!MatchCapacity.hasRoom(match.totalMembers(), max)) {
            return null;
        }
        int sideCap = MatchCapacity.perSideCap(max);
        return LatecomerAssignment.randomFaction(
                match.memberCount(Faction.ALPHA), sideCap,
                match.memberCount(Faction.BRAVO), sideCap);
    }

    /**
     * 把玩家加入待命世界的候选名单，阵营随机分配。
     *
     * @return {@code false} 表示名单已满
     */
    private boolean joinLobby(ServerPlayer player, ServerLevel level) {
        Map<UUID, Faction> lobby = lobbyFor(level);
        if (lobby.containsKey(player.getUUID())) {
            return true;
        }
        int max = maxPlayersFor(level);
        if (!MatchCapacity.hasRoom(lobby.size(), max)) {
            return false;
        }
        int sideCap = MatchCapacity.perSideCap(max);
        int alphaCount = (int) lobby.values().stream().filter(f -> f == Faction.ALPHA).count();
        int bravoCount = (int) lobby.values().stream().filter(f -> f == Faction.BRAVO).count();
        Faction faction = LatecomerAssignment.randomFaction(alphaCount, sideCap, bravoCount, sideCap);
        if (faction == null) {
            return false;
        }
        lobby.put(player.getUUID(), faction);
        return true;
    }

    private Map<UUID, Faction> lobbyFor(ServerLevel level) {
        return lobbies.computeIfAbsent(level.dimension(), ignored -> new LinkedHashMap<>());
    }

    /** {@link #quickJoin}在key没有对应进行中对局时的降级路径：加入待命世界的候选名单。 */
    private void quickJoinStandby(ServerPlayer player, String key) {
        MinecraftServer server = player.getServer();
        ServerLevel standbyLevel = resolveStandbyLevel(server, key, player.serverLevel());
        if (standbyLevel == null || !BattlefieldData.get(standbyLevel).isBreakthroughReady()) {
            player.displayClientMessage(Component.literal("§c该突破对局不存在或已结束"), true);
            return;
        }
        Map<UUID, Faction> lobby = lobbyFor(standbyLevel);
        if (lobby.containsKey(player.getUUID())) {
            player.displayClientMessage(Component.literal("§e你已在候选名单中"), true);
            return;
        }
        if (!joinLobby(player, standbyLevel)) {
            player.displayClientMessage(Component.literal("§c该突破对局已满员"), true);
            return;
        }
        Faction faction = lobby.get(player.getUUID());
        player.displayClientMessage(Component.literal("§a已加入候选名单 §7- " + faction.coloredName()), true);
        Act0Battlefield.broadcastRoomList(server);
        tryAutoStart(standbyLevel);
    }

    /** 把 {@code "bt@<dimension>"} 或裸维度字符串解析为已加载的 {@link ServerLevel}；空 key 回退到调用者当前世界。 */
    @Nullable
    private ServerLevel resolveStandbyLevel(MinecraftServer server, String key, ServerLevel fallbackLevel) {
        String raw = normalizeQuickJoinKey(key);
        if (raw.isBlank()) {
            return fallbackLevel;
        }
        if (raw.startsWith("bt@")) {
            raw = raw.substring(3).trim();
        }
        ResourceLocation loc = ResourceLocation.tryParse(raw);
        if (loc == null) {
            return null;
        }
        return server.getLevel(ResourceKey.create(Registries.DIMENSION, loc));
    }

    /** 候选名单凑够该地图的自动开始人数（见 {@link #minPlayersFor}）且地图已布置完毕时自动开局。 */
    private void tryAutoStart(ServerLevel level) {
        if (activeFor(level) != null) {
            return;
        }
        Map<UUID, Faction> lobby = lobbies.get(level.dimension());
        if (lobby == null || !BattlefieldData.get(level).isBreakthroughReady()) {
            return;
        }
        if (lobby.size() < minPlayersFor(level)) {
            return;
        }
        start(level, BreakthroughRules.standard());
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
        if (!data.isBreakthroughReady()) {
            return "§c该世界尚未布置完毕（据点/双方基地/突破区域未设置）。";
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
            Act0Battlefield.broadcastRoomList(server);
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
        if (active.isEnemyHit(victim.getUUID(), attacker)) {
            active.sendHitMarker(attacker);
        }
        active.onHurt(victim.getUUID(), attacker);
    }

    @SubscribeEvent
    public void onLivingJump(LivingEvent.LivingJumpEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        BreakthroughMatch active = activeContaining(player.getUUID());
        if (active == null || !active.isDowned(player.getUUID())) {
            return;
        }
        // 注意：对真正联网的 ServerPlayer 而言，这段清零本身并不能阻止玩家看起来跳起来——
        // MC 的移动同步是"客户端预测、上报绝对坐标，服务端在合理范围内信任接受"（见
        // ServerGamePacketListenerImpl.handleMovePlayer，最终位置来自
        // absMoveTo(clampVertical(packet.getY(...)), ...)，与这里的 deltaMovement 无关），
        // 服务端执行到这里时客户端早已用自己的物理预测算出并上报了跳起来的 Y 坐标。真正生效的
        // 拦截在客户端 BattlefieldClientInput.onLivingJump（同一个事件也会在本机玩家的客户端
        // aiStep() 里同步触发，在 travel() 消费 deltaMovement 之前拦下）；
        // BreakthroughMatch.tickDownedPlayers() 里还有一层按 tick 校验 Y 位移的服务端反作弊兜底。
        // 这里保留清零仅作为无害的防御性收尾（对非联网路径/其他读取 deltaMovement 的逻辑有意义），
        // 不再是本 mod 阻止倒地起跳的主要依据。
        Vec3 v = player.getDeltaMovement();
        player.setDeltaMovement(v.x, 0.0D, v.z);
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

    public void handleDownedAction(ServerPlayer player, DownedActionPacket.Action action) {
        BreakthroughMatch match = activeContaining(player.getUUID());
        if (match != null) {
            match.handleDownedAction(player, action);
        }
    }

    public boolean handleDeployGadget(ServerPlayer player, DeployableKind kind, ItemStack display) {
        BreakthroughMatch match = activeContaining(player.getUUID());
        return match != null && match.handleDeployGadget(player, kind, display);
    }

    public boolean handleSquadAction(ServerPlayer player, int kind, int targetSquadId) {
        BreakthroughMatch match = activeContaining(player.getUUID());
        return match != null && match.handleSquadAction(player, kind, targetSquadId);
    }

    public boolean handleSyringeRevive(ServerPlayer reviver, ServerPlayer target) {
        BreakthroughMatch match = activeContaining(reviver.getUUID());
        return match != null && match.handleSyringeRevive(reviver, target);
    }

    public void handleReviveHeartbeat(ServerPlayer reviver, int targetEntityId, boolean active) {
        BreakthroughMatch match = activeContaining(reviver.getUUID());
        if (match != null) {
            match.handleReviveHeartbeat(reviver, targetEntityId, active);
        }
    }

    public void handleDeploySlotOverride(ServerPlayer player, int slotIndex, String itemName) {
        BreakthroughMatch match = activeContaining(player.getUUID());
        if (match != null) {
            match.handleDeploySlotOverride(player, slotIndex, itemName);
        }
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
