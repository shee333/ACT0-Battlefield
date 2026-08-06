package org.shee33.act0.battlefield.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
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

    /**
     * 真正阻止倒地玩家跳跃的地方（ConquestManager/BreakthroughManager 里那份服务端 onLivingJump
     * 只是兜底，对本机玩家的跳跃动作本身无效——MC 的玩家位置同步是"客户端预测、上报绝对坐标，
     * 服务端在合理范围内信任接受"（{@code ServerGamePacketListenerImpl.handleMovePlayer} 里最终
     * 位置来自 {@code absMoveTo(clampVertical(packet.getY(...)), ...)}，跟服务端 {@code
     * deltaMovement} 无关），服务端清零 deltaMovement 时客户端早已经用自己的物理预测算出并上报了
     * 跳起来的 Y 坐标。
     * <p>但 {@code LivingEntity.jumpFromGround()} 本身（设置 deltaMovement.y 为跳跃速度、随后
     * {@code ForgeHooks.onLivingJump} 同步 post 出 {@link LivingEvent.LivingJumpEvent}）在本机
     * 玩家（{@code LocalPlayer}）身上也会照常于客户端 {@code aiStep()} 内执行一次——同一次
     * {@code aiStep()} 调用里，"jump" 阶段设置完 deltaMovement 并同步触发这个事件之后，紧接着的
     * "travel" 阶段才会读取 deltaMovement 施加重力/位移。所以在这里于客户端拦截同一个事件、
     * 把本机玩家的竖直速度归零，是在 travel() 消费它之前完成的，能真正让本机玩家这一 tick 不产生
     * 向上位移，从而也不会把"跳起来的" Y 坐标上报给服务端。这与 Manager 里那份服务端处理程序
     * 用的是完全相同的手法，只是注册在客户端事件总线、作用在本机玩家实体上，因此才真正生效。
     */
    @SubscribeEvent
    public static void onLivingJump(LivingEvent.LivingJumpEvent event) {
        Minecraft mc = Minecraft.getInstance();
        Player self = mc.player;
        if (self == null || event.getEntity() != self || !ClientDownedFeedback.isDowned()) {
            return;
        }
        Vec3 v = self.getDeltaMovement();
        self.setDeltaMovement(v.x, 0.0D, v.z);
    }

    /** 断开服务器连接时兜底清空，防止残留的救援/长按状态带进下一个世界/服务器。 */
    static void reset() {
        spaceHeldMs = 0L;
        lastReviveTargetId = -1;
        reviveHeartbeatCooldown = 0;
    }
}