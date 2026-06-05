package org.shee33.act0.battlefield.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import org.shee33.act0.battlefield.client.ClientDeployStatus;

import java.util.function.Supplier;

/**
 * S→C：死亡后部署界面状态。{@code open=true} 表示打开/保持部署界面。
 */
public final class SyncDeployPacket {

    private final boolean open;
    private final DeployStatusDto status;

    public SyncDeployPacket(boolean open, DeployStatusDto status) {
        this.open = open;
        this.status = status;
    }

    public static void encode(SyncDeployPacket msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.open);
        msg.status.encode(buf);
    }

    public static SyncDeployPacket decode(FriendlyByteBuf buf) {
        return new SyncDeployPacket(buf.readBoolean(), DeployStatusDto.decode(buf));
    }

    public static void handle(SyncDeployPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientDeployStatus.accept(msg.open, msg.status)));
        context.setPacketHandled(true);
    }
}
