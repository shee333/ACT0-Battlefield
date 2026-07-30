package org.shee33.act0.battlefield.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import org.shee33.act0.battlefield.client.ClientDeployFx;
import org.shee33.act0.battlefield.client.ClientDownedFeedback;

import java.util.function.Supplier;

/**
 * S→C：部署传送落地反馈。
 *
 * <p>玩家部署/重生传送完成后触发：屏幕黑幕淡出 + 底部"已部署 · 据点名"提示。同时清除客户端残留的倒地
 * vignette/横幅状态（覆盖"倒地超时被迫重生"这条不会单独下发"倒地结束"包的路径）。
 */
public final class DeploySpawnFxPacket {
    private final String pointLabel;

    public DeploySpawnFxPacket(String pointLabel) {
        this.pointLabel = pointLabel;
    }

    public static void encode(DeploySpawnFxPacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.pointLabel);
    }

    public static DeploySpawnFxPacket decode(FriendlyByteBuf buf) {
        return new DeploySpawnFxPacket(buf.readUtf());
    }

    public static void handle(DeploySpawnFxPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> {
                    ClientDownedFeedback.clear();
                    ClientDeployFx.trigger(msg.pointLabel);
                }));
        context.setPacketHandled(true);
    }
}
