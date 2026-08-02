package org.shee33.act0.battlefield.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.shee33.act0.battlefield.Act0Battlefield;

/**
 * 部署确认过场的相机渲染覆盖。
 *
 * <p>每渲染帧（不是每 tick）读取 {@link ClientDeployPan} 算出的插值位姿，通过
 * {@link ViewportEvent.ComputeCameraAngles} 直接覆盖朝向，并借助 access transformer 放开的
 * {@code Camera#setPosition(double,double,double)} 覆盖相机位置。这个事件在 vanilla 中只暴露
 * yaw/pitch/roll 字段（没有位置字段），但它在 {@code camera.setup()} 之后、关卡渲染读取
 * {@code camera.getPosition()} 之前触发，所以在这里直接改 {@code event.getCamera()} 的位置，关卡渲染
 * 用的 cull frustum / 视图矩阵会立刻用上覆盖后的坐标——完全脱离服务端 20 tick/s 的驱动频率，插值频率
 * 等于渲染帧率（通常远高于 20Hz），过场因此明显更连续丝滑。
 *
 * <p>只覆盖第一人称眼位置（实体坐标 + 玩家眼高）；第三人称的拉杆偏移是 vanilla
 * {@code Camera.setup()} 基于真实实体位置做的一次性碰撞回退计算，这里不重算——过场期间固定按第一人称
 * 眼位置覆盖即可，精度上不低于此前"每 tick 硬 teleport"版本对第三人称的处理（那版本同样是硬对齐，没有
 * 拉杆平滑）。
 */
@Mod.EventBusSubscriber(modid = Act0Battlefield.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class DeployPanCameraHandler {

    private DeployPanCameraHandler() {
    }

    @SubscribeEvent
    public static void onComputeCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        if (!ClientDeployPan.isActive()) {
            return;
        }
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        ClientDeployPan.Pose pose = ClientDeployPan.currentPose();
        event.getCamera().setPosition(pose.x(), pose.y() + player.getEyeHeight(), pose.z());
        event.setYaw(pose.yaw());
        event.setPitch(pose.pitch());
    }
}
