package org.shee33.act0.battlefield.integration;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.UUID;

/**
 * 与 {@code act0_arcade} 的配装系统软集成桥。
 *
 * <p>大战场是独立模组，但产品目标是与街机共用同一套配装/解锁/改装。为了避免把两个工程强行改成
 * Gradle multi-project，本类用反射软依赖：
 * <ul>
 *   <li>若服务器装了 {@code act0_arcade}：读取玩家当前激活的 {@code LoadoutSet.active()}，按
 *   {@code LoadoutRuleset.FULL} 发放完整战地配装（含装置槽），并复用 TaCZ 改装安装。</li>
 *   <li>若没装：安静降级，部署流程仍可跑，只是不接管装备。</li>
 * </ul>
 *
 * <p>所有反射都只发生在服务端部署时；失败只记录一次，避免刷屏。
 */
public final class ArcadeLoadoutBridge {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static boolean warnedMissing = false;
    private static boolean warnedFailure = false;

    private ArcadeLoadoutBridge() {
    }

    /**
     * 对玩家应用 ACT0-Arcade 当前激活配装。
     *
     * @return {@code true} 表示成功调用了 Arcade 配装系统；{@code false} 表示 Arcade 不存在或调用失败。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static boolean apply(ServerPlayer player) {
        try {
            MinecraftServer server = player.server;
            UUID playerId = player.getUUID();

            Class<?> act0ArcadeClass = Class.forName("org.shee33.act0.arcade.Act0Arcade");
            Object services = act0ArcadeClass.getMethod("services").invoke(null);

            Object registry = services.getClass().getMethod("registry").invoke(services);
            Object applier = services.getClass().getMethod("applier").invoke(services);

            Class<?> registryClass = Class.forName("org.shee33.act0.arcade.loadout.LoadoutRegistry");
            Class<?> loadoutClass = Class.forName("org.shee33.act0.arcade.loadout.Loadout");
            Class<?> rulesetClass = Class.forName("org.shee33.act0.arcade.loadout.LoadoutRuleset");

            Class<?> defaultCatalogClass = Class.forName("org.shee33.act0.arcade.loadout.DefaultLoadoutCatalog");
            Object fallback = defaultCatalogClass.getMethod("defaultLoadout", registryClass).invoke(null, registry);

            Class<?> storeClass = Class.forName("org.shee33.act0.arcade.storage.ArcadeLoadoutStore");
            Object store = storeClass.getMethod("get", MinecraftServer.class).invoke(null, server);
            Object loadout = storeClass.getMethod("getOrCreate", UUID.class, loadoutClass)
                    .invoke(store, playerId, fallback);

            Class<?> unlocksClass = Class.forName("org.shee33.act0.arcade.storage.ArcadePlayerUnlocks");
            Object unlocksStore = unlocksClass.getMethod("get", MinecraftServer.class).invoke(null, server);
            Object unlocked = unlocksClass.getMethod("unlocked", UUID.class).invoke(unlocksStore, playerId);

            Object fullRuleset = Enum.valueOf((Class<Enum>) rulesetClass.asSubclass(Enum.class), "FULL");
            Class<?> applierClass = Class.forName("org.shee33.act0.arcade.loadout.mc.LoadoutApplier");
            Method apply = applierClass.getMethod("apply", ServerPlayer.class, loadoutClass, rulesetClass,
                    Set.class, boolean.class);
            apply.invoke(applier, player, loadout, fullRuleset, unlocked, true);
            return true;
        } catch (ClassNotFoundException e) {
            if (!warnedMissing) {
                warnedMissing = true;
                LOGGER.info("[ACT/0/Battlefield] act0_arcade not present; battlefield loadout integration disabled");
            }
            return false;
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (!warnedFailure) {
                warnedFailure = true;
                LOGGER.warn("[ACT/0/Battlefield] failed to apply shared ACT0-Arcade loadout: {}", e.toString());
            }
            return false;
        }
    }
}
