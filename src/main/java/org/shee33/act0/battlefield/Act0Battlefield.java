package org.shee33.act0.battlefield;

import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.shee33.act0.battlefield.match.ConquestManager;
import org.shee33.act0.battlefield.network.BattlefieldNetwork;
import org.shee33.act0.battlefield.reg.BattlefieldRegistry;
import org.slf4j.Logger;

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

    public Act0Battlefield() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        BattlefieldRegistry.register(modEventBus);
        BattlefieldNetwork.register();

        MinecraftForge.EVENT_BUS.register(MANAGER);

        LOGGER.info("{}constructed", LOG_PREFIX);
    }

    /** 全局征服对局管理器入口，供命令层使用。 */
    public static ConquestManager manager() {
        return MANAGER;
    }
}
