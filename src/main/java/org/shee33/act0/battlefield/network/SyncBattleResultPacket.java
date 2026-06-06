package org.shee33.act0.battlefield.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import org.shee33.act0.battlefield.client.ClientBattleResult;

import java.util.function.Supplier;

/** S→C：打开对局结束战报界面。 */
public final class SyncBattleResultPacket {

    private final BattleResultDto result;

    public SyncBattleResultPacket(BattleResultDto result) {
        this.result = result;
    }

    public static void encode(SyncBattleResultPacket msg, FriendlyByteBuf buf) {
        msg.result.encode(buf);
    }

    public static SyncBattleResultPacket decode(FriendlyByteBuf buf) {
        return new SyncBattleResultPacket(BattleResultDto.decode(buf));
    }

    public static void handle(SyncBattleResultPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientBattleResult.open(msg.result)));
        context.setPacketHandled(true);
    }
}
