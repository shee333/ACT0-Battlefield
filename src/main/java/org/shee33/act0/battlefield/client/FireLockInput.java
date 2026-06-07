package org.shee33.act0.battlefield.client;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.shee33.act0.battlefield.Act0Battlefield;

/** 大战场倒计时禁开火：取消攻击键与使用物品键。 */
@Mod.EventBusSubscriber(modid = Act0Battlefield.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class FireLockInput {

    private FireLockInput() {
    }

    @SubscribeEvent
    public static void onInteractionKey(InputEvent.InteractionKeyMappingTriggered event) {
        if (!ClientFireLock.isLocked()) {
            return;
        }
        if (event.isAttack() || event.isUseItem()) {
            event.setSwingHand(false);
            event.setCanceled(true);
        }
    }
}
