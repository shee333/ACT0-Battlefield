package org.shee33.act0.battlefield;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.shee33.act0.battlefield.hologram.BattlefieldEntranceHolograms;
import org.shee33.act0.battlefield.match.BreakthroughManager;
import org.shee33.act0.battlefield.match.ConquestManager;
import org.shee33.act0.battlefield.match.MatchChatHandler;
import org.shee33.act0.battlefield.network.BattlefieldNetwork;
import org.shee33.act0.battlefield.network.BattlefieldRoomDto;
import org.shee33.act0.battlefield.reg.BattlefieldRegistry;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * ACT0-Battlefield 主模组类。
 *
 * <p>大战场（征服）玩法的 Forge 入口：双方据点争夺 + 票数流失，方块标记据点、大规模弹性人数、
 * 据点前进出生。与街机模组 {@code act0_arcade} 解耦，独立可编译/可单测。
 *
 * <p>核心域（{@code core/}：票数/据点/规则）为 MC-free 纯逻辑，附带单元测试；MC 依赖集中在
 * {@code block/}、{@code reg/}、{@code match/}、{@code client/} 等桥接层。
 */
@Mod(Act0Battlefield.MODID)
public final class Act0Battlefield {

    /** 模组 id，须与 {@code META-INF/mods.toml} 与 {@code gradle.properties} 的 {@code mod_id} 一致。 */
    public static final String MODID = "act0_battlefield";

    /** 服务端日志前缀，统一品牌标识。 */
    public static final String LOG_PREFIX = "[ACT/0/Battlefield] ";

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 全局征服对局管理器（注册到 Forge 事件总线，路由 ServerTick / LivingDeath）。 */
    private static final ConquestManager MANAGER = new ConquestManager();

    public static final BreakthroughManager BREAKTHROUGH_MANAGER = new BreakthroughManager();

    public Act0Battlefield() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        BattlefieldRegistry.register(modEventBus);
        BattlefieldNetwork.register();

        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, BattlefieldConfig.SPEC);

        MinecraftForge.EVENT_BUS.register(MANAGER);
        MinecraftForge.EVENT_BUS.register(BREAKTHROUGH_MANAGER);
        MinecraftForge.EVENT_BUS.register(BattlefieldEntranceHolograms.INSTANCE);
        MinecraftForge.EVENT_BUS.register(new MatchChatHandler(MANAGER, BREAKTHROUGH_MANAGER));

        LOGGER.info("{}constructed", LOG_PREFIX);
    }

    /** 全局征服对局管理器入口，供命令层使用。 */
    public static ConquestManager manager() {
        return MANAGER;
    }

    /** 对局浏览器快照：合并征服与突破两个管理器各自的房间行。 */
    public static List<BattlefieldRoomDto> snapshotAllRooms(MinecraftServer server, UUID viewerId) {
        List<BattlefieldRoomDto> rows = new ArrayList<>(MANAGER.snapshotRooms(server, viewerId));
        rows.addAll(BREAKTHROUGH_MANAGER.snapshotRooms(server, viewerId));
        return rows;
    }

    /** 向所有在线玩家推送各自视角下的对局浏览器房间列表快照。 */
    public static void broadcastRoomList(MinecraftServer server) {
        if (server == null) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            BattlefieldNetwork.sendRoomList(player, snapshotAllRooms(server, player.getUUID()));
        }
    }
}
