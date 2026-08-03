package org.shee33.act0.battlefield.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;
import org.shee33.act0.battlefield.Act0Battlefield;

/**
 * 客户端按键注册：默认 B 键打开共享配装界面。大战场菜单仍可通过战地终端或命令打开。
 */
@Mod.EventBusSubscriber(modid = Act0Battlefield.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class BattlefieldKeyMappings {

    public static final KeyMapping OPEN_MENU = new KeyMapping(
            "key.act0_battlefield.open_menu",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_B,
            "key.categories.act0_battlefield");

    public static final KeyMapping SPOT_ENEMY = new KeyMapping(
            "key.act0_battlefield.spot_enemy",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_Q,
            "key.categories.act0_battlefield");

    public static final KeyMapping SPECTATE_NEXT = new KeyMapping(
            "key.act0_battlefield.spectate_next",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_V,
            "key.categories.act0_battlefield");

    /** 救援键：按住瞄准倒地队友即持续救援，松开或转头/走远即取消（见 BattlefieldClientInput）。
     * 默认绑定 F——原版 F 键默认无功能（不含交互菜单场景），不会像 E 那样弹出物品栏抢夺鼠标光标、
     * 挡住准星导致救援检测打不开；与本 mod 已有的 Q(标记敌人)/V(切换观战视角)/B(打开配装) 同属一套
     * 自定义按键，不占用任何原版核心操作。 */
    public static final KeyMapping REVIVE = new KeyMapping(
            "key.act0_battlefield.revive",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F,
            "key.categories.act0_battlefield");

    private BattlefieldKeyMappings() {
    }

    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(OPEN_MENU);
        event.register(SPOT_ENEMY);
        event.register(SPECTATE_NEXT);
        event.register(REVIVE);
    }
}
