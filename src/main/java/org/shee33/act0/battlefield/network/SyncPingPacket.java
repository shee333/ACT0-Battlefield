package org.shee33.act0.battlefield.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import org.shee33.act0.battlefield.client.ClientMinimapEvents;

import java.util.function.Supplier;

/** S2C：把某个小队成员打出的战术标记同步给队友，落到小地图上。 */
public final class SyncPingPacket {

    private final double x;
    private final double z;

    public SyncPingPacket(double x, double z) {
        this.x = x;
        this.z = z;
    }

    public static void encode(SyncPingPacket msg, FriendlyByteBuf buf) {
        buf.writeDouble(msg.x);
        buf.writeDouble(msg.z);
    }

    public static SyncPingPacket decode(FriendlyByteBuf buf) {
        return new SyncPingPacket(buf.readDouble(), buf.readDouble());
    }

    public static void handle(SyncPingPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientMinimapEvents.onPing(msg.x, msg.z)));
        context.setPacketHandled(true);
    }
}
