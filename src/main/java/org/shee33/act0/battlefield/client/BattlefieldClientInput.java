package org.shee33.act0.battlefield.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;
import org.shee33.act0.battlefield.Act0Battlefield;
import org.shee33.act0.battlefield.network.ActionPacket;
import org.shee33.act0.battlefield.network.BattlefieldNetwork;
import org.shee33.act0.battlefield.network.DownedActionPacket;
import org.shee33.act0.battlefield.network.ReviveHeartbeatPacket;
import org.shee33.act0.battlefield.network.SpotEnemyPacket;

@Mod.EventBusSubscriber(modid = Act0Battlefield.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class BattlefieldClientInput {

    /** 救援心跳发送间隔（tick）：不必每 tick 都发包，服务端容忍窗口留足余量即可（见
     * ConquestMatch/BreakthroughMatch 的 REVIVE_HEARTBEAT_TIMEOUT_TICKS）。 */
    private static final int REVIVE_HEARTBEAT_INTERVAL_TICKS = 4;

    private static long spaceHeldMs;
    private static int lastReviveTargetId = -1;
    private static int reviveHeartbeatCooldown;

    private BattlefieldClientInput() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) return;

        while (BattlefieldKeyMappings.OPEN_MENU.consumeClick()) {
            BattlefieldNetwork.CHANNEL.sendToServer(new ActionPacket(ActionPacket.Action.OPEN_LOADOUT));
        }
        while (BattlefieldKeyMappings.SPOT_ENEMY.consumeClick()) {
            if (mc.hitResult instanceof EntityHitResult hit && hit.getEntity() instanceof Player) {
                BattlefieldNetwork.CHANNEL.sendToServer(new SpotEnemyPacket(hit.getEntity().getId()));
            }
        }

        if (BattlefieldKeyMappings.REVIVE.isDown()
                && mc.hitResult instanceof EntityHitResult reviveHit
                && reviveHit.getEntity() instanceof Player targetPlayer
                && targetPlayer != mc.player) {
            int targetId = reviveHit.getEntity().getId();
            if (targetId != lastReviveTargetId || reviveHeartbeatCooldown <= 0) {
                BattlefieldNetwork.CHANNEL.sendToServer(new ReviveHeartbeatPacket(targetId, true));
                reviveHeartbeatCooldown = REVIVE_HEARTBEAT_INTERVAL_TICKS;
            } else {
                reviveHeartbeatCooldown--;
            }
            lastReviveTargetId = targetId;
        } else {
            if (lastReviveTargetId != -1) {
                BattlefieldNetwork.CHANNEL.sendToServer(new ReviveHeartbeatPacket(-1, false));
            }
            lastReviveTargetId = -1;
            reviveHeartbeatCooldown = 0;
        }

        long win = mc.getWindow().getWindow();
        boolean spaceDown = org.lwjgl.glfw.GLFW.glfwGetKey(win, GLFW.GLFW_KEY_SPACE) == GLFW.GLFW_PRESS;
        if (spaceDown) {
            spaceHeldMs += 50;
            if (spaceHeldMs == 1000) {
                BattlefieldNetwork.CHANNEL.sendToServer(new DownedActionPacket(DownedActionPacket.Action.GIVE_UP));
            }
        } else {
            spaceHeldMs = 0;
        }

        boolean leftDown = org.lwjgl.glfw.GLFW.glfwGetMouseButton(win, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        if (leftDown) {
            BattlefieldNetwork.CHANNEL.sendToServer(new DownedActionPacket(DownedActionPacket.Action.CALL_HELP));
        }
    }
}