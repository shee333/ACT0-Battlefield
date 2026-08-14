package org.shee33.act0.battlefield.bot.mc;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.shee33.act0.battlefield.bot.AimModel;
import org.shee33.act0.battlefield.bot.BotDifficultyRegistry;
import org.shee33.act0.battlefield.bot.BotNames;
import org.shee33.act0.battlefield.core.Faction;
import org.shee33.act0.battlefield.data.BattlefieldData;
import org.shee33.act0.battlefield.match.ConquestMatch;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;

/**
 * AI 士兵的生命周期与驱动循环，是 bot 子系统对外的唯一入口。
 *
 * <p><b>加入方式是"对进行中的对局手动补人"</b>，而非街机那套房间席位：管理员用
 * {@code /battlefield bot add} 把 bot 塞进当前对局，走的是本体既有的
 * {@link ConquestMatch#addLatecomer} 中途加入路径——因此分阵营、进小队、发配装、部署落点
 * 全部由本体处理，bot 侧不复制任何一条。
 */
public final class BotManager {

    public static final BotManager INSTANCE = new BotManager();

    /** 生效难度参数。当前只用内置默认值——Battlefield 侧还没有对应的 JSON 调参通道。 */
    private static final BotDifficultyRegistry DIFFICULTY = new BotDifficultyRegistry();

    private final Map<UUID, BotTask> tasks = new LinkedHashMap<>();

    private BiPredicate<ServerPlayer, ServerPlayer> hostility = BotHostility::isEnemy;

    private BotManager() {
    }

    static BotDifficultyRegistry difficultyRegistry() {
        return DIFFICULTY;
    }

    // ---------------- 生命周期 ----------------

    /**
     * 往一场进行中的对局里补入 AI 士兵。
     *
     * <p>先在该方基地生成实体，再交给 {@link ConquestMatch#addLatecomer}——顺序不可颠倒：
     * addLatecomer 会立刻部署这名玩家，实体必须已经在对局所在的维度里。
     *
     * @return 实际加入成功的 bot 名字
     */
    public List<String> addToMatch(MinecraftServer server, ConquestMatch match, Faction faction,
                                   int count) {
        BattlefieldData.BaseSpawn base = BattlefieldData.get(match.level()).base(faction);
        if (base == null) {
            return List.of();
        }
        Set<String> taken = new LinkedHashSet<>(activeNames());
        List<String> names = BotNames.pick(count, taken, server.getTickCount());
        List<String> added = new ArrayList<>();
        ServerLevel level = match.level();
        for (String name : names) {
            BotPlayer bot = BotSpawner.spawn(server, level, name,
                    base.x(), base.y(), base.z(), base.yaw(), base.pitch());
            if (bot == null) {
                continue;
            }
            if (!match.addLatecomer(bot, faction)) {
                BotSpawner.despawn(server, bot);
                continue;
            }
            tasks.put(bot.getUUID(), new BotTask(bot));
            added.add(name);
        }
        return added;
    }

    /**
     * 在指定位置裸生成 AI 士兵，<b>不加入任何对局</b>。
     *
     * <p>用途是让 bot 能充当"需要玩家在场"的管理命令（{@code base set} / {@code start}）的执行者，
     * 从而使一整套开局流程在没有真人在线时也能被脚本化地跑通。裸 bot 不属于任何阵营，
     * 也不会自主行动（见 {@code BotTask#tick} 对无对局的处理）。
     *
     * @return 实际生成的 bot 名字
     */
    public List<String> spawnBare(MinecraftServer server, ServerLevel level,
                                  double x, double y, double z, int count) {
        Set<String> taken = new LinkedHashSet<>(activeNames());
        List<String> added = new ArrayList<>();
        for (String name : BotNames.pick(count, taken, server.getTickCount())) {
            BotPlayer bot = BotSpawner.spawn(server, level, name, x, y, z, 0.0f, 0.0f);
            if (bot == null) {
                continue;
            }
            tasks.put(bot.getUUID(), new BotTask(bot));
            added.add(name);
        }
        return added;
    }

    /** 撤走一名 bot；返回是否确有其人。 */
    public boolean despawn(MinecraftServer server, String name) {
        BotTask task = tasks.remove(BotNames.uuidOf(name));
        if (task == null) {
            return false;
        }
        detach(server, task);
        return true;
    }

    /** 撤走全部 bot；返回数量。 */
    public int despawnAll(MinecraftServer server) {
        List<BotTask> all = new ArrayList<>(tasks.values());
        tasks.clear();
        for (BotTask task : all) {
            detach(server, task);
        }
        BotSquadBoard.INSTANCE.clear();
        BotMatchContext.clearCache();
        return all.size();
    }

    /**
     * 让 bot 干净离场：先退出对局（结清票数与小队席位），再释放导航资源、移除实体。
     *
     * <p>不退出对局就直接移除实体，会在 {@code factionOf} 里留下一个查不到玩家的 UUID——
     * 占领人数统计会把它当成"暂时不在线"，而它永远不会回来。
     */
    private void detach(MinecraftServer server, BotTask task) {
        ConquestMatch match = org.shee33.act0.battlefield.Act0Battlefield.manager()
                .activeContaining(task.bot.getUUID());
        if (match != null) {
            match.quitPlayer(task.bot);
        }
        task.releaseNavigation();
        BotSpawner.despawn(server, task.bot);
    }

    // ---------------- 查询与调试指令 ----------------

    public List<String> activeNames() {
        List<String> names = new ArrayList<>(tasks.size());
        for (BotTask task : tasks.values()) {
            names.add(task.bot.getGameProfile().getName());
        }
        return names;
    }

    public int activeCount() {
        return tasks.size();
    }

    /** 切换某个 bot 的难度档；返回是否确有其人。 */
    public boolean setDifficulty(String name, AimModel.Difficulty difficulty) {
        BotTask task = tasks.get(BotNames.uuidOf(name));
        if (task == null) {
            return false;
        }
        task.rebuildWeapon(difficulty);
        return true;
    }

    /** 全体切换难度档；返回受影响的数量。 */
    public int setDifficultyForAll(AimModel.Difficulty difficulty) {
        for (BotTask task : tasks.values()) {
            task.rebuildWeapon(difficulty);
        }
        return tasks.size();
    }

    @Nullable
    public AimModel.Difficulty difficultyOf(String name) {
        BotTask task = tasks.get(BotNames.uuidOf(name));
        return task != null ? task.difficulty : null;
    }

    public void setHostility(BiPredicate<ServerPlayer, ServerPlayer> hostility) {
        this.hostility = hostility;
    }

    /** bot 复活时复位其战术状态；传入非 bot 的 UUID 无副作用。 */
    public void onRespawn(UUID playerId) {
        BotTask task = tasks.get(playerId);
        if (task != null) {
            task.onRespawn();
        }
    }

    /** 对局收尾时清掉该维度的共享状态；不清则每局都留下永不再读的键。 */
    public void onMatchEnded(ResourceKey<Level> dimension) {
        BotSquadBoard.INSTANCE.clearMatch(dimension);
        BotMatchContext.forgetMatch(dimension);
    }

    // ---------------- 驱动循环 ----------------

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        // 必须在 START 阶段：世界的实体循环随后才推进物理，此时写入的意图才能在本 tick 生效。
        if (event.phase != TickEvent.Phase.START || tasks.isEmpty()) {
            return;
        }
        MinecraftServer server = event.getServer();
        tasks.values().removeIf(task -> isStale(server, task));
        for (BotTask task : tasks.values()) {
            task.tick(server, hostility);
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        despawnAll(event.getServer());
    }

    /**
     * bot 是否已不再有效。
     *
     * <p>bot 可能被外部途径移除（管理员 {@code /kick}、维度卸载、异常清理）。继续驱动一个已从
     * 世界剥离的实体会静默失效并泄漏引用，因此每 tick 做一次归属校验。
     */
    private static boolean isStale(MinecraftServer server, BotTask task) {
        return task.bot.isRemoved()
                || server.getPlayerList().getPlayer(task.bot.getUUID()) != task.bot;
    }
}
