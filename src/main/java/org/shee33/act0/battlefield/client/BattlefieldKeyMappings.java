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
 * 客户端按键注册：默认 B 键打开大战场 GUI，让玩家不需要输入命令。
 */
@Mod.EventBusSubscriber(modid = Act0Battlefield.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class BattlefieldKeyMappings {

    public static final KeyMapping OPEN_MENU = new KeyMapping(
            "key.act0_battlefield.open_menu",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_B,
            "key.categories.act0_battlefield");

    private BattlefieldKeyMappings() {
    }

    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(OPEN_MENU);
    }
}
