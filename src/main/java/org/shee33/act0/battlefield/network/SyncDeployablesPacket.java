package org.shee33.act0.battlefield.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import org.shee33.act0.battlefield.client.ClientDeployables;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** S2C：同阵营可见的已部署补给物列表，驱动地面提示圆的绘制。 */
public final class SyncDeployablesPacket {

    private final List<DeployableDto> deployables;

    public SyncDeployablesPacket(List<DeployableDto> deployables) {
        this.deployables = deployables;
    }

    public static void encode(SyncDeployablesPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.deployables.size());
        for (DeployableDto d : msg.deployables) {
            d.encode(buf);
        }
    }

    public static SyncDeployablesPacket decode(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<DeployableDto> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add(DeployableDto.decode(buf));
        }
        return new SyncDeployablesPacket(list);
    }

    public static void handle(SyncDeployablesPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientDeployables.accept(msg.deployables)));
        context.setPacketHandled(true);
    }
}
