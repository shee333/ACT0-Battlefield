package org.shee33.act0.battlefield.match;

import com.mojang.brigadier.context.ParsedCommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.shee33.act0.battlefield.deployable.DeployableKind;
import net.minecraft.world.item.ItemStack;
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
import org.shee33.act0.battlefield.command.Aew1Command;
import org.shee33.act0.battlefield.core.BattleArea;
import org.shee33.act0.battlefield.core.ConquestRules;
import org.shee33.act0.battlefield.core.Faction;
import org.shee33.act0.battlefield.core.FactionNames;
import org.shee33.act0.battlefield.core.LatecomerAssignment;
import org.shee33.act0.battlefield.core.MatchCapacity;
import org.shee33.act0.battlefield.core.MapTemplate;
import org.shee33.act0.battlefield.data.BattlefieldData;
import org.shee33.act0.battlefield.loadout.BattlefieldLoadoutService;
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
import java.util.Set;
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

    /** 每玩家上次"加入候选名单"的时间戳，节流 JOIN——该动作会触发
     * {@link Act0Battlefield#broadcastRoomList}向所有在线玩家广播房间列表快照，恶意客户端
     * spam切边会打出N倍广播放大攻击（P1安全修复）。 */
    private final Map<UUID, Long> lastLobbyJoinMs = new LinkedHashMap<>();
    private static final long LOBBY_JOIN_MIN_INTERVAL_MS = 200L;

    private static final Logger LOGGER = LogUtils.getLogger();

    // ---- 候选名单 ----

    /**
     * 加入当前世界的候选名单，阵营由服务端随机分配（玩家不再自行选边）。
     *
     * @return 分到的阵营；名单已满返回 {@code null}
     */
    @Nullable
    public Faction join(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        if (!joinLobby(player, level)) {
            return null;
        }
        Act0Battlefield.broadcastRoomList(player.getServer());
        tryAutoStart(level);
        return lobbyFor(level).get(player.getUUID());
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
        int max = maxPlayersFor(level);
        for (ServerPlayer player : level.players()) {
            leaveLobby(player.getUUID());
            // 批量加入同样受该地图人数上限约束，否则管理员一键加人会让对局人数越过上限，
            // 与普通加入路径的行为不一致。
            if (!MatchCapacity.hasRoom(lobby.size(), max)) {
                operator.sendSystemMessage(Component.literal("§e已达该地图人数上限 " + max + "，其余玩家未加入。"));
                break;
            }
            Faction target = alpha <= bravo ? Faction.ALPHA : Faction.BRAVO;
            lobby.put(player.getUUID(), target);
            if (target == Faction.ALPHA) {
                alpha++;
            } else {
                bravo++;
            }
            player.sendSystemMessage(Component.literal("§6你已被加入大战场候选名单：" + namesOf(level).colored(target)));
            added++;
        }
        Act0Battlefield.broadcastRoomList(operator.getServer());
        operator.sendSystemMessage(Component.literal("§a已将当前世界 §e" + added
                + " §a名玩家加入候选名单 §7(" + namesOf(level).alpha() + " " + alpha + " / " + namesOf(level).bravo() + " " + bravo + ")"));
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
        for (ServerLevel level : server.getAllLevels()) {
            int minPlayers = minPlayersFor(level);
            int maxPlayers = maxPlayersFor(level);
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
                        maxPlayers,
                        minPlayers,
                        match.contains(viewerId),
                        namesOf(level).colored(Faction.ALPHA),
                        namesOf(level).colored(Faction.BRAVO),
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
                    maxPlayers,
                    minPlayers,
                    viewerIn,
                    namesOf(level).colored(Faction.ALPHA),
                    namesOf(level).colored(Faction.BRAVO),
                    0, 0, 0, 0));
        }
        return rows;
    }

    private static FactionNames namesOf(ServerLevel level) {
        return BattlefieldData.get(level).factionNames();
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
        // 中途加入随机分配阵营，并执行该地图的人数上限（上限来源见 assignFactionForMatch）。
        Faction faction = assignFactionForMatch(match.level(), match);
        if (faction == null) {
            player.displayClientMessage(Component.literal("§c该大战场已满员"), true);
            return;
        }
        if (match.addLatecomer(player, faction)) {
            ResourceKey<Level> battleKey = levelKey != null ? levelKey : player.serverLevel().dimension();
            player.displayClientMessage(Component.literal("§a已加入 "
                + battleNames.getOrDefault(battleKey, defaultBattleName(battleKey)) + " §7- " + namesOf(match.level()).colored(faction)), true);
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
        if (!joinLobby(player, standbyLevel)) {
            player.displayClientMessage(Component.literal("§c该大战场已满员"), true);
            return;
        }
        Faction faction = lobby.get(player.getUUID());
        player.displayClientMessage(Component.literal("§a已加入候选名单 §7- " + namesOf(standbyLevel).colored(faction)), true);
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

    /** 候选名单凑够该地图的自动开始人数（见 {@link #minPlayersFor}）且地图已布置完毕时自动开局。 */
    private void tryAutoStart(ServerLevel level) {
        if (activeFor(level) != null) {
            return;
        }
        Map<UUID, Faction> lobby = lobbies.get(level.dimension());
        if (lobby == null || !BattlefieldData.get(level).isConquestReady()) {
            return;
        }
        if (lobby.size() < minPlayersFor(level)) {
            return;
        }
        // 自动开局使用地图设置的票数（isConquestReady 已要求票数>0）。
        start(level, ConquestRules.builder()
                .startingTickets(BattlefieldData.get(level).ticketsRaw()).build());
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
     * 为加入进行中对局的玩家随机分配阵营，同时执行该地图的人数上限。
     *
     * <p>先校验总人数、再按每阵营上限做随机分配：{@link MatchCapacity#perSideCap} 是向上取整的，
     * 单看单边可能允许两边合计比总上限多 1 个名额，总量校验必须排在前面。
     *
     * @return 分到的阵营；已满员返回 {@code null}
     */
    @Nullable
    private Faction assignFactionForMatch(ServerLevel level, ConquestMatch match) {
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
            case JOIN -> {
                ConquestMatch active = activeFor(player.serverLevel());
                if (active != null) {
                    Faction faction = assignFactionForMatch(player.serverLevel(), active);
                    if (faction == null) {
                        player.sendSystemMessage(Component.literal("§c该对局已满员。"));
                        return;
                    }
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
                    if (!joinLobby(player, player.serverLevel())) {
                        player.sendSystemMessage(Component.literal("§c该大战场候选名单已满。"));
                        return;
                    }
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
                    String err = start(player.serverLevel(), ConquestRules.builder()
                            .startingTickets(BattlefieldData.get(player.serverLevel()).ticketsRaw()).build());
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
            case REFRESH -> {
            }
        }
        Act0Battlefield.broadcastRoomList(player.getServer());
    }

    /** 主动为玩家打开对局浏览器（该玩家自己所在客户端未必已开屏，需要显式一跳网络包）。 */
    public void openFor(ServerPlayer player) {
        BattlefieldNetwork.sendOpenBrowser(player);
    }



    // ---- 事件 ----

    /**
     * 对局中允许非 OP 玩家使用的命令，按<b>解析出的节点路径</b>比对。
     *
     * <p>这里刻意不用原始字符串前缀匹配：{@code /battlefield leave} 改名成 {@code /aew1 leave} 时，
     * 旧的前缀判定既不再匹配新名、也不会报错，结果是玩家在对局中<b>所有</b>命令都被拦下、
     * 连退出都做不到（暂停菜单的退出按钮走的正是这条命令）。节点路径由 Brigadier 解析得出，
     * 与参数写法、空格、引号无关，改名时漏改会在这里表现为"退不出去"而不是静默放行。
     */
    private static final Set<String> ALLOWED_COMMANDS_IN_MATCH = Set.of(
            Aew1Command.CMD_LEAVE,
            Aew1Command.CMD_SUICIDE,
            Aew1Command.CMD_BREAKTHROUGH_LEAVE);

    @SubscribeEvent
    public void onCommand(CommandEvent event) {
        if (!(event.getParseResults().getContext().getSource().getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (player.hasPermissions(2) || activeContaining(player.getUUID()) == null) {
            return;
        }
        if (ALLOWED_COMMANDS_IN_MATCH.contains(commandPath(event))) {
            return;
        }
        event.setCanceled(true);
        player.sendSystemMessage(Component.literal("§c大战场中无法使用该指令，使用退出按钮可离开。"));
    }

    /** 把一条已解析命令还原成以空格分隔的节点名路径（参数节点用其参数名，不含实参值）。 */
    private static String commandPath(CommandEvent event) {
        StringBuilder sb = new StringBuilder();
        for (ParsedCommandNode<CommandSourceStack> node : event.getParseResults().getContext().getNodes()) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(node.getNode().getName());
        }
        return sb.toString();
    }

    /**
     * 注册本模组唯一的命令根 {@code /aew1}（含突破模式与地图军械库子树）。
     *
     * <p>突破模式的子树也在这里一并注册，{@code BreakthroughManager} 不再单独注册命令——
     * 同一个根被两个事件处理器各注册一次会让节点归属散开，改一处可能无声覆盖另一处。
     */
    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        Aew1Command.register(event.getDispatcher());
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

    /**
     * 战术标记：优先派发给玩家所在的征服对局，否则交给突破对局。
     *
     * <p>与 {@link #spotEnemy} 只处理征服不同，这里两模式都覆盖——小地图大修在两个模式下
     * 都启用，只做一半会让突破模式的 Ping 静默失效。
     */
    public void markPing(ServerPlayer player, double x, double z) {
        ConquestMatch match = activeContaining(player.getUUID());
        if (match != null) {
            match.broadcastPing(player, x, z);
            return;
        }
        BreakthroughMatch breakthrough =
                Act0Battlefield.BREAKTHROUGH_MANAGER.activeContaining(player.getUUID());
        if (breakthrough != null) {
            breakthrough.broadcastPing(player, x, z);
        }
    }

    public void handleDownedAction(ServerPlayer player, DownedActionPacket.Action action) {
        ConquestMatch match = activeContaining(player.getUUID());
        if (match != null) {
            match.handleDownedAction(player, action);
        }
    }

    public boolean handleDeployGadget(ServerPlayer player, DeployableKind kind, ItemStack display) {
        ConquestMatch match = activeContaining(player.getUUID());
        return match != null && match.handleDeployGadget(player, kind, display);
    }

    public boolean handleSquadAction(ServerPlayer player, int kind, int targetSquadId) {
        ConquestMatch match = activeContaining(player.getUUID());
        return match != null && match.handleSquadAction(player, kind, targetSquadId);
    }

    public boolean handleSyringeRevive(ServerPlayer reviver, ServerPlayer target) {
        ConquestMatch match = activeContaining(reviver.getUUID());
        return match != null && match.handleSyringeRevive(reviver, target);
    }

    public void handleReviveHeartbeat(ServerPlayer reviver, int targetEntityId, boolean active) {
        ConquestMatch match = activeContaining(reviver.getUUID());
        if (match != null) {
            match.handleReviveHeartbeat(reviver, targetEntityId, active);
        }
    }

    public void handleDeployClassChange(ServerPlayer player, String classId) {
        ConquestMatch match = activeContaining(player.getUUID());
        if (match != null) {
            match.handleDeployClassChange(player, classId);
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
        BattlefieldLoadoutService.resetDiagnostics();
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
