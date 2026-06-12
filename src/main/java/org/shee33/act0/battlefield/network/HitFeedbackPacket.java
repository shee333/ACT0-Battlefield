package org.shee33.act0.battlefield.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import org.shee33.act0.battlefield.client.ClientHitFeedback;

import java.util.function.Supplier;

/** S→C：准心命中/击杀反馈。 */
public final class HitFeedbackPacket {
    private final boolean kill;

    public HitFeedbackPacket(boolean kill) {
        this.kill = kill;
    }

    public static void encode(HitFeedbackPacket msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.kill);
    }

    public static HitFeedbackPacket decode(FriendlyByteBuf buf) {
        return new HitFeedbackPacket(buf.readBoolean());
    }

    public static void handle(HitFeedbackPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientHitFeedback.trigger(msg.kill)));
        context.setPacketHandled(true);
    }
}
