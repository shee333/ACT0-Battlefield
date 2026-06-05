package org.shee33.act0.battlefield.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import org.shee33.act0.battlefield.client.ClientBattlefieldStatus;

import java.util.function.Supplier;

/**
 * S→C：下发大战场状态快照。{@code open=true} 表示玩家主动请求开屏（界面未开则打开）；
 * {@code open=false} 仅刷新已打开的界面，不弹窗（避免打扰未在看界面的玩家）。
 */
public final class SyncStatusPacket {

    private final boolean open;
    private final BattlefieldStatusDto status;

    public SyncStatusPacket(boolean open, BattlefieldStatusDto status) {
        this.open = open;
        this.status = status;
    }

    public static void encode(SyncStatusPacket msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.open);
        msg.status.encode(buf);
    }

    public static SyncStatusPacket decode(FriendlyByteBuf buf) {
        boolean open = buf.readBoolean();
        return new SyncStatusPacket(open, BattlefieldStatusDto.decode(buf));
    }

    public static void handle(SyncStatusPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientBattlefieldStatus.accept(msg.open, msg.status)));
        context.setPacketHandled(true);
    }
}
