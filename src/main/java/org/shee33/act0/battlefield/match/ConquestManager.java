package org.shee33.act0.battlefield.match;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.CommandEvent;
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
import org.shee33.act0.battlefield.command.BattlefieldCommand;
import org.shee33.act0.battlefield.core.BattleArea;
import org.shee33.act0.battlefield.core.ConquestRules;
import org.shee33.act0.battlefield.core.Faction;
import org.shee33.act0.battlefield.core.LatecomerAssignment;
import org.shee33.act0.battlefield.core.MapTemplate;
import org.shee33.act0.battlefield.data.BattlefieldData;
import org.shee33.act0.battlefield.data.ControlPointDef;
import org.shee33.act0.battlefield.network.ActionPacket;
import org.shee33.act0.battlefield.network.BattlefieldNetwork;
import org.shee33.act0.battlefield.network.BattlefieldRoomDto;
import org.shee33.act0.battlefield.network.DownedActionPacket;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Path;
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
    /** 进行中大战场名称：世界 → 战役名。 */
    private final Map<ResourceKey<Level>, String> battleNames = new LinkedHashMap<>();
    /** 将每个对局的世界映射回其大厅世界，用于赛后传送。 */
    private final Map<ResourceKey<Level>, ServerLevel> lobbyWorlds = new LinkedHashMap<>();
    /** 地图模板根路径。 */
    private final Path templateBasePath = Path.of("config", "act0_battlefield", "templates");

    /** 每玩家上次"加入候选名单"的时间戳，节流JOIN_ALPHA/JOIN_BRAVO——这两个动作会触发
     * {@link Act0Battlefield#broadcastRoomList}向所有在线玩家广播房间列表快照，恶意客户端
     * spam切边会打出N倍广播放大攻击（P1安全修复）。 */
    private final Map<UUID, Long> lastLobbyJoinMs = new LinkedHashMap<>();
    private static final long LOBBY_JOIN_MIN_INTERVAL_MS = 200L;

    private static final Logger LOGGER = LogUtils.getLogger();

    // ---- 候选名单 ----

    public void join(ServerPlayer player, Faction faction) {
        lobbyFor(player.serverLevel()).put(player.getUUID(), faction);
        Act0Battlefield.broadcastRoomList(player.getServer());
        tryAutoStart(player.serverLevel());
    }

    /** 将当前世界所有在线玩家均衡加入候选名单，方便管理员一键开局。 */
    public int joinAllInWorld(ServerPlayer operator) {
        ServerLevel level = operator.serverLevel();
        if (activeFor(level) != null) {
            operator.sendSystemMessage(Component.literal("§c该世界已有进行中的大战场，不能批量加入候选。"));
            return 0;
        }
        Map<UUID, Faction> lobby = lobbyFor(level);
        int alpha = 0;
        int bravo = 0;
        for (Faction faction : lobby.values()) {
            if (faction == Faction.ALPHA) {
                alpha++;
            } else if (faction == Faction.BRAVO) {
                bravo++;
            }
        }
        int added = 0;
        for (ServerPlayer player : level.players()) {
            leaveLobby(player.getUUID());
            Faction target = alpha <= bravo ? Faction.ALPHA : Faction.BRAVO;
            lobby.put(player.getUUID(), target);
            if (target == Faction.ALPHA) {
                alpha++;
            } else {
                bravo++;
            }
            player.sendSystemMessage(Component.literal("§6你已被加入大战场候选名单：" + target.coloredName()));
            added++;
        }
        Act0Battlefield.broadcastRoomList(operator.getServer());
        operator.sendSystemMessage(Component.literal("§a已将当前世界 §e" + added
                + " §a名玩家加入候选名单 §7(北大西洋公约 " + alpha + " / 无邦军团 " + bravo + ")"));
        tryAutoStart(level);
        return added;
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
            lobbyWorlds.keySet().removeIf(key -> !activeByWorld.containsKey(key)
                    || activeByWorld.get(key).isEnded());
        }
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

    /**
     * 对局浏览器快照：涵盖"运行中"的对局与"地图已布置完毕、正等待玩家凑够人数"的待命世界
     * （见 {@link BattlefieldData#isConquestReady()}）。待命世界的人数取自其候选名单大小，
     * 对局结束后 {@code activeByWorld} 摘除该 key，只要布场数据还在，下次快照就会自然把它
     * 重新归为待命行——不需要额外的"结束后转回等待中"代码。
     */
    public List<BattlefieldRoomDto> snapshotRooms(MinecraftServer server, UUID viewerId) {
        List<BattlefieldRoomDto> rows = new ArrayList<>();
        int minPlayers = BattlefieldConfig.MIN_PLAYERS_TO_START.get();
        for (ServerLevel level : server.getAllLevels()) {
            ResourceKey<Level> key = level.dimension();
            ConquestMatch match = activeByWorld.get(key);
            if (match != null && !match.isEnded()) {
                rows.add(new BattlefieldRoomDto(
                        "bf@" + key.location(),
                        battleNames.getOrDefault(key, defaultBattleName(key)),
                        false,
                        mapNameOrFallback(level, key),
                        true,
                        match.totalMembers(),
                        minPlayers,
                        match.contains(viewerId),
                        Faction.ALPHA.coloredName(),
                        Faction.BRAVO.coloredName(),
                        match.displayTickets(Faction.ALPHA),
                        match.displayTickets(Faction.BRAVO),
                        match.startingTicketsHint(),
                        match.elapsedSeconds()));
                continue;
            }
            BattlefieldData data = BattlefieldData.get(level);
            if (!data.isConquestReady()) {
                continue;
            }
            Map<UUID, Faction> lobby = lobbies.get(key);
            int cur = lobby != null ? lobby.size() : 0;
            boolean viewerIn = lobby != null && lobby.containsKey(viewerId);
            rows.add(new BattlefieldRoomDto(
                    "bf@" + key.location(),
                    defaultBattleName(key),
                    false,
                    mapNameOrFallback(level, key),
                    false,
                    cur,
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

    public void quickJoin(ServerPlayer player, String key) {
        ResourceKey<Level> levelKey = resolveQuickJoinKey(key);
        ConquestMatch match = levelKey != null ? activeByWorld.get(levelKey) : activeFor(player.serverLevel());
        if (match == null || match.isEnded()) {
            // 没有进行中对局：这个 key 可能对应一个"地图已布置完毕、正等待玩家"的待命世界——
            // 加入方式等价于候选名单加入(随机分配阵营)，凑够人数后自动开局，与浏览器"等待中"
            // 行点击加入的语义保持一致。
            quickJoinStandby(player, key);
            return;
        }
        if (match.contains(player.getUUID())) {
            player.displayClientMessage(Component.literal("§e你已在该大战场中"), true);
            return;
        }
        // 中途加入随机分配阵营（不再是"人数少的一方优先"）。本 mod 目前没有"每阵营人数上限"
        // 配置概念——capacityHint() 只是给游戏浏览器展示用的固定值 64，从未在加入流程中被
        // 拿来做人数校验——所以这里把 cap 传 Integer.MAX_VALUE，退化为纯 50/50 随机；
        // randomFaction() 的容量约束分支仍保留，未来真的引入人数上限时无需再改这里。
        Faction faction = LatecomerAssignment.randomFaction(
                match.memberCount(Faction.ALPHA), Integer.MAX_VALUE,
                match.memberCount(Faction.BRAVO), Integer.MAX_VALUE);
        if (faction == null) {
            player.displayClientMessage(Component.literal("§c该大战场已满员"), true);
            return;
        }
        if (match.addLatecomer(player, faction)) {
            ResourceKey<Level> battleKey = levelKey != null ? levelKey : player.serverLevel().dimension();
            player.displayClientMessage(Component.literal("§a已加入 "
                + battleNames.getOrDefault(battleKey, defaultBattleName(battleKey)) + " §7- " + faction.coloredName()), true);
        } else {
            player.displayClientMessage(Component.literal("§c无法加入该大战场"), true);
        }
    }

    @Nullable
    private ResourceKey<Level> resolveQuickJoinKey(String key) {
        String raw = normalizeQuickJoinKey(key);
        if (raw.isBlank()) {
            return null;
        }
        if (raw.startsWith("bf@")) {
            raw = raw.substring(3).trim();
        }
        for (Map.Entry<ResourceKey<Level>, ConquestMatch> e : activeByWorld.entrySet()) {
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

    /** {@link #quickJoin}在key没有对应进行中对局时的降级路径：加入待命世界的候选名单。 */
    private void quickJoinStandby(ServerPlayer player, String key) {
        MinecraftServer server = player.getServer();
        ServerLevel standbyLevel = resolveStandbyLevel(server, key, player.serverLevel());
        if (standbyLevel == null || !BattlefieldData.get(standbyLevel).isConquestReady()) {
            player.displayClientMessage(Component.literal("§c该大战场不存在或已结束"), true);
            return;
        }
        Map<UUID, Faction> lobby = lobbyFor(standbyLevel);
        if (lobby.containsKey(player.getUUID())) {
            player.displayClientMessage(Component.literal("§e你已在候选名单中"), true);
            return;
        }
        int alphaCount = (int) lobby.values().stream().filter(f -> f == Faction.ALPHA).count();
        int bravoCount = (int) lobby.values().stream().filter(f -> f == Faction.BRAVO).count();
        Faction faction = LatecomerAssignment.randomFaction(alphaCount, Integer.MAX_VALUE, bravoCount, Integer.MAX_VALUE);
        if (faction == null) {
            player.displayClientMessage(Component.literal("§c该大战场已满员"), true);
            return;
        }
        lobby.put(player.getUUID(), faction);
        player.displayClientMessage(Component.literal("§a已加入候选名单 §7- " + faction.coloredName()), true);
        Act0Battlefield.broadcastRoomList(server);
        tryAutoStart(standbyLevel);
    }

    /** 把 {@code "bf@<dimension>"} 或裸维度字符串解析为已加载的 {@link ServerLevel}；空 key 回退到调用者当前世界。 */
    @Nullable
    private ServerLevel resolveStandbyLevel(MinecraftServer server, String key, ServerLevel fallbackLevel) {
        String raw = normalizeQuickJoinKey(key);
        if (raw.isBlank()) {
            return fallbackLevel;
        }
        if (raw.startsWith("bf@")) {
            raw = raw.substring(3).trim();
        }
        ResourceLocation loc = ResourceLocation.tryParse(raw);
        if (loc == null) {
            return null;
        }
        return server.getLevel(ResourceKey.create(Registries.DIMENSION, loc));
    }

    /** 候选名单凑够{@link BattlefieldConfig#MIN_PLAYERS_TO_START}人且地图已布置完毕时自动开局。 */
    private void tryAutoStart(ServerLevel level) {
        if (activeFor(level) != null) {
            return;
        }
        Map<UUID, Faction> lobby = lobbies.get(level.dimension());
        if (lobby == null || !BattlefieldData.get(level).isConquestReady()) {
            return;
        }
        if (lobby.size() < BattlefieldConfig.MIN_PLAYERS_TO_START.get()) {
            return;
        }
        start(level, ConquestRules.standard());
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
        return start(level, rules, defaultBattleName(level.dimension()), null);
    }

    /** 用当前候选名单开局并命名战役。 */
    @Nullable
    public String start(ServerLevel level, ConquestRules rules, String battleName) {
        return start(level, rules, battleName, null);
    }

    /**
     * 用当前候选名单开局，可选加载地图模板。
     *
     * @param level        大厅世界（候选名单所在世界）
     * @param rules        对局规则
     * @param battleName   战役名称
     * @param templateName 模板名称；{@code null} 则在大厅世界中直接开局
     * @return 失败原因；{@code null} 表示成功
     */
    @Nullable
    public String start(ServerLevel level, ConquestRules rules, String battleName, @Nullable String templateName) {
        if (activeFor(level) != null) {
            return "§c该世界已有进行中的大战场对局。";
        }
        Map<UUID, Faction> lobby = lobbyFor(level);
        if (lobby.isEmpty()) {
            return "§c还没有玩家选择阵营。";
        }
        BattlefieldData data = BattlefieldData.get(level);
        List<ControlPointDef> defs = data.points();
        if (!data.isConquestReady()) {
            return "§c该世界尚未布置完毕（据点/双方基地未设置）。";
        }
        if (templateName == null || templateName.isBlank()) {
            tryAutoSaveDefaultTemplate(level, data);
        }
        ServerLevel matchLevel;
        if (templateName != null && !templateName.isBlank()) {
            Path regionDir = MapTemplateManager.templateRegionPath(templateName, templateBasePath);
            try {
                MatchDimensionHelper.prepareMatchDimension(level.getServer(), regionDir);
            } catch (IOException e) {
                return "§c模板加载失败：" + e.getMessage();
            }
            matchLevel = MatchDimensionHelper.getMatchLevel(level.getServer());
            if (matchLevel == null) {
                return "§c战场维度尚未创建，请先进入一次该维度。";
            }
            MatchDimensionHelper.configureMatchLevel(matchLevel);
        } else {
            matchLevel = level;
        }
        ConquestMatch active = new ConquestMatch(matchLevel, level, rules, defs, new LinkedHashMap<>(lobby), data);
        activeByWorld.put(matchLevel.dimension(), active);
        battleNames.put(matchLevel.dimension(), normalizeBattleName(battleName, matchLevel.dimension()));
        lobbyWorlds.put(matchLevel.dimension(), level);
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
            battleNames.remove(level.dimension());
            lobbyWorlds.remove(level.dimension());
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
                    long now = System.currentTimeMillis();
                    Long last = lastLobbyJoinMs.get(player.getUUID());
                    if (last != null && now - last < LOBBY_JOIN_MIN_INTERVAL_MS) {
                        return;
                    }
                    lastLobbyJoinMs.put(player.getUUID(), now);
                    lobbyFor(player.serverLevel()).put(player.getUUID(), ActionPacket.factionOf(action));
                    Act0Battlefield.broadcastRoomList(player.getServer());
                    tryAutoStart(player.serverLevel());
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
                        Act0Battlefield.broadcastRoomList(player.getServer());
                        return;
                    }
                }
            }
            case STOP -> {
                if (!player.hasPermissions(2)) {
                    player.sendSystemMessage(Component.literal("§c只有管理员可以停止。"));
                } else {
                    stop(player.serverLevel());
                    Act0Battlefield.broadcastRoomList(player.getServer());
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
        Act0Battlefield.broadcastRoomList(player.getServer());
    }

    /** 主动为玩家打开对局浏览器（该玩家自己所在客户端未必已开屏，需要显式一跳网络包）。 */
    public void openFor(ServerPlayer player) {
        BattlefieldNetwork.sendOpenBrowser(player);
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
        player.sendSystemMessage(Component.literal("§c大战场中无法使用该指令，使用退出按钮可离开。"));
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
            net.minecraft.server.MinecraftServer server = event.getServer();
            for (ResourceKey<Level> key : ended) {
                ServerLevel lobbyLevel = lobbyWorlds.get(key);
                if (lobbyLevel != null) {
                    for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                        ConquestMatch match = activeByWorld.get(key);
                        if (match != null && match.contains(player.getUUID())) {
                            player.teleportTo(lobbyLevel, player.getX(), player.getY(), player.getZ(),
                                    player.getYRot(), player.getXRot());
                        }
                    }
                }
                try {
                    MatchDimensionHelper.cleanupMatchDimension(server);
                } catch (IOException ignored) {
                }
                activeByWorld.remove(key);
                battleNames.remove(key);
                lobbyWorlds.remove(key);
            }
            Act0Battlefield.broadcastRoomList(server);
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
        ConquestMatch active = activeContaining(player.getUUID());
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
        // ConquestMatch.tickDownedPlayers() 里还有一层按 tick 校验 Y 位移的服务端反作弊兜底。
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

    public void spotEnemy(ServerPlayer spotter, int targetId) {
        ConquestMatch match = activeContaining(spotter.getUUID());
        if (match != null) {
            match.spotEnemy(spotter, targetId);
        }
    }

    public void handleDownedAction(ServerPlayer player, DownedActionPacket.Action action) {
        ConquestMatch match = activeContaining(player.getUUID());
        if (match != null) {
            match.handleDownedAction(player, action);
        }
    }

    public void handleReviveHeartbeat(ServerPlayer reviver, int targetEntityId, boolean active) {
        ConquestMatch match = activeContaining(reviver.getUUID());
        if (match != null) {
            match.handleReviveHeartbeat(reviver, targetEntityId, active);
        }
    }

    public void handleDeploySlotOverride(ServerPlayer player, int slotIndex, String itemName) {
        ConquestMatch match = activeContaining(player.getUUID());
        if (match != null) {
            match.handleDeploySlotOverride(player, slotIndex, itemName);
        }
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id = event.getEntity().getUUID();
        if (event.getEntity() instanceof ServerPlayer player) {
            leaveMatch(player);
        }
        leaveLobby(id);
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        // 不再自动归位；玩家可通过大战场/游戏浏览器中途加入。
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        for (ConquestMatch match : activeByWorld.values()) {
            match.abort();
        }
        activeByWorld.clear();
        battleNames.clear();
        lobbyWorlds.clear();
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

    private void tryAutoSaveDefaultTemplate(ServerLevel level, BattlefieldData data) {
        List<MapTemplate> templates;
        try {
            templates = MapTemplateManager.listTemplates(templateBasePath);
        } catch (IOException e) {
            LOGGER.warn("读取模板列表失败，跳过默认模板自动生成: {}", e.getMessage());
            return;
        }
        if (!templates.isEmpty()) {
            return;
        }
        BattleArea area = data.effectiveArea();
        if (!area.isSet()) {
            LOGGER.warn("当前世界战斗区域未设置，跳过默认模板自动生成。");
            return;
        }
        try {
            MapTemplateManager.saveTemplate("default", level, area, templateBasePath);
            Component feedback = Component.literal("§e首次使用，已自动将当前世界战斗区域保存为默认模板。");
            for (ServerPlayer player : level.players()) {
                if (player.hasPermissions(2)) {
                    player.sendSystemMessage(feedback);
                }
            }
        } catch (IOException e) {
            LOGGER.warn("自动保存默认模板失败: {}", e.getMessage());
        } catch (IllegalArgumentException e) {
            LOGGER.warn("默认模板参数无效: {}", e.getMessage());
        }
    }
}
