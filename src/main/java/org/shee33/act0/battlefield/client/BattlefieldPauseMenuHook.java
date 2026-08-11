package org.shee33.act0.battlefield.client;

import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.shee33.act0.battlefield.Act0Battlefield;

/**
 * 大战场对局内把原版 ESC 暂停菜单整体替换为 {@link BattlefieldPauseScreen}。
 *
 * <p>取代原先的 {@code BattlefieldPauseMenuExitButton}——那个版本只是往原版 {@code PauseScreen}
 * 上加一个红色「退出对局」按钮，与《战地暂停菜单动效规格文档》要求的整套战地风格菜单不相容：
 * 原版菜单会暂停单人世界、居中堆按钮、且没有任何对局状态。
 *
 * <p>拦截点选 {@link ScreenEvent.Opening} 而非 {@code Init.Post}：前者能在 Screen 真正被装上之前
 * 整体换掉（{@code setNewScreen}），后者只能往已经建好的原版菜单上打补丁。
 *
 * <p>本类<b>没有任何静态初始化</b>：带 {@code @Mod.EventBusSubscriber} 的类在 mod CONSTRUCT 阶段
 * 就被加载，此时 Forge 的注册表尚未就绪，任何非常量静态初始化都可能 NPE 并让客户端直接起不来
 * （见 {@code EventSubscriberStaticInitTest} 记录的真实事故）。
 */
@Mod.EventBusSubscriber(modid = Act0Battlefield.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class BattlefieldPauseMenuHook {

    private BattlefieldPauseMenuHook() {
    }

    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        if (!(event.getNewScreen() instanceof PauseScreen)
                || event.getCurrentScreen() instanceof BattlefieldPauseScreen
                || !BattlefieldPauseScreen.isInBattlefield()) {
            return;
        }
        event.setNewScreen(new BattlefieldPauseScreen());
    }
}
