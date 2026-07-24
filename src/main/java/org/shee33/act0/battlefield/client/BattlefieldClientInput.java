package org.shee33.act0.battlefield.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.shee33.act0.battlefield.Act0Battlefield;
import org.shee33.act0.battlefield.network.ActionPacket;
import org.shee33.act0.battlefield.network.BattlefieldNetwork;
import org.shee33.act0.battlefield.network.SpotEnemyPacket;

/**
 * 客户端输入处理：打开 GUI 键 + Q 标记敌人键。
 */
@Mod.EventBusSubscriber(modid = Act0Battlefield.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class BattlefieldClientInput {

    private BattlefieldClientInput() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) {
            return;
        }
        while (BattlefieldKeyMappings.OPEN_MENU.consumeClick()) {
            BattlefieldNetwork.CHANNEL.sendToServer(new ActionPacket(ActionPacket.Action.OPEN_LOADOUT));
        }
        while (BattlefieldKeyMappings.SPOT_ENEMY.consumeClick()) {
            if (mc.hitResult instanceof EntityHitResult hit
                    && hit.getEntity() instanceof Player) {
                BattlefieldNetwork.CHANNEL.sendToServer(new SpotEnemyPacket(hit.getEntity().getId()));
            }
        }
    }
}