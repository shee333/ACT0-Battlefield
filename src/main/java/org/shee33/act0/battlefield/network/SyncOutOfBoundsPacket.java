package org.shee33.act0.battlefield.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import org.shee33.act0.battlefield.client.ClientOutOfBounds;

import java.util.function.Supplier;

/**
 * S→C：本地玩家当前的"离开作战区域"状态，驱动底部倒计时横幅与画面灰度。
 *
 * <p>由服务端 {@code tickEscapeBoundary} 在状态变化或剩余秒数变化时推送：{@code active}
 * 表示是否在界外，{@code remainingSeconds}/{@code totalSeconds} 供倒计时显示与灰度强度插值。
 * 回到界内时服务端立即推一条 {@code active=false}，客户端据此淡出恢复。
 *
 * <p>玩家自己出界与否是服务端权威判定的（含不规则多边形边界），客户端不自行判定——
 * 与 {@code SyncVanillaHudPacket} 同源的"服务端状态 → 客户端表现"模式。
 */
public final class SyncOutOfBoundsPacket {

    private final boolean active;
    private final int remainingSeconds;
    private final int totalSeconds;

    public SyncOutOfBoundsPacket(boolean active, int remainingSeconds, int totalSeconds) {
        this.active = active;
        this.remainingSeconds = remainingSeconds;
        this.totalSeconds = totalSeconds;
    }

    public static void encode(SyncOutOfBoundsPacket msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.active);
        buf.writeVarInt(msg.remainingSeconds);
        buf.writeVarInt(msg.totalSeconds);
    }

    public static SyncOutOfBoundsPacket decode(FriendlyByteBuf buf) {
        return new SyncOutOfBoundsPacket(buf.readBoolean(), buf.readVarInt(), buf.readVarInt());
    }

    public static void handle(SyncOutOfBoundsPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientOutOfBounds.update(msg.active, msg.remainingSeconds, msg.totalSeconds)));
        context.setPacketHandled(true);
    }
}
