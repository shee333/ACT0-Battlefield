package org.shee33.act0.battlefield.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import org.shee33.act0.battlefield.client.ClientDeployPan;

import java.util.function.Supplier;

/**
 * S→C：部署确认"过场相机"的起止位姿快照。
 *
 * <p>只在 {@code RedeployService.beginDeployPan()} 触发过场的那一刻下发一次，不逐 tick 重发。
 * 服务端自己仍然按 tick 小步进真实实体位置（作为权威落点，见 {@code RedeployService.tickDeployPan()}），
 * 但客户端的视觉呈现完全交给 {@link ClientDeployPan} 按渲染帧的真实经过时间自行插值——插值频率
 * 因此和渲染帧率（通常远高于 20Hz）一致，而不是被服务端 tick 频率钳制在 20 次/秒。
 */
public final class DeployPanPacket {

    private final double startX;
    private final double startY;
    private final double startZ;
    private final float startYaw;
    private final float startPitch;
    private final double endX;
    private final double endY;
    private final double endZ;
    private final float endYaw;
    private final float endPitch;
    private final int durationTicks;

    public DeployPanPacket(double startX, double startY, double startZ, float startYaw, float startPitch,
                            double endX, double endY, double endZ, float endYaw, float endPitch,
                            int durationTicks) {
        this.startX = startX;
        this.startY = startY;
        this.startZ = startZ;
        this.startYaw = startYaw;
        this.startPitch = startPitch;
        this.endX = endX;
        this.endY = endY;
        this.endZ = endZ;
        this.endYaw = endYaw;
        this.endPitch = endPitch;
        this.durationTicks = durationTicks;
    }

    public static void encode(DeployPanPacket msg, FriendlyByteBuf buf) {
        buf.writeDouble(msg.startX);
        buf.writeDouble(msg.startY);
        buf.writeDouble(msg.startZ);
        buf.writeFloat(msg.startYaw);
        buf.writeFloat(msg.startPitch);
        buf.writeDouble(msg.endX);
        buf.writeDouble(msg.endY);
        buf.writeDouble(msg.endZ);
        buf.writeFloat(msg.endYaw);
        buf.writeFloat(msg.endPitch);
        buf.writeVarInt(msg.durationTicks);
    }

    public static DeployPanPacket decode(FriendlyByteBuf buf) {
        double startX = buf.readDouble();
        double startY = buf.readDouble();
        double startZ = buf.readDouble();
        float startYaw = buf.readFloat();
        float startPitch = buf.readFloat();
        double endX = buf.readDouble();
        double endY = buf.readDouble();
        double endZ = buf.readDouble();
        float endYaw = buf.readFloat();
        float endPitch = buf.readFloat();
        int durationTicks = buf.readVarInt();
        return new DeployPanPacket(startX, startY, startZ, startYaw, startPitch,
                endX, endY, endZ, endYaw, endPitch, durationTicks);
    }

    public static void handle(DeployPanPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientDeployPan.begin(
                        msg.startX, msg.startY, msg.startZ, msg.startYaw, msg.startPitch,
                        msg.endX, msg.endY, msg.endZ, msg.endYaw, msg.endPitch,
                        msg.durationTicks)));
        context.setPacketHandled(true);
    }
}
