package org.shee33.act0.battlefield.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import org.shee33.act0.battlefield.client.ClientFireLock;

import java.util.function.Supplier;

/** S→C：大战场开局倒计时禁用攻击/使用物品输入。 */
public final class SyncFireLockPacket {

    private final boolean locked;

    public SyncFireLockPacket(boolean locked) {
        this.locked = locked;
    }

    public static void encode(SyncFireLockPacket msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.locked);
    }

    public static SyncFireLockPacket decode(FriendlyByteBuf buf) {
        return new SyncFireLockPacket(buf.readBoolean());
    }

    public static void handle(SyncFireLockPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientFireLock.setLocked(msg.locked)));
        context.setPacketHandled(true);
    }
}
