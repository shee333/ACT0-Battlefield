package org.shee33.act0.battlefield.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.shee33.act0.battlefield.Act0Battlefield;

import java.util.function.Supplier;

/**
 * C2S：救援心跳。客户端持续按住救援键且瞄准倒地队友时每隔数刻上报一次（active=true，附带目标
 * 实体 ID）；按键松开或瞄准脱离目标时上报一次 active=false 作为停止信号。
 *
 * <p>服务端不会仅凭这个信号维持救援：{@code ConquestMatch}/{@code BreakthroughMatch} 每 tick
 * 仍独立复核距离与视线朝向，心跳只用于探测"客户端停止信号没送达"（网络掉线/延迟）的边界情况——
 * 超过容忍窗口没收到新心跳同样会被判定为取消。
 */
public final class ReviveHeartbeatPacket {

    private final int targetEntityId;
    private final boolean active;

    public ReviveHeartbeatPacket(int targetEntityId, boolean active) {
        this.targetEntityId = targetEntityId;
        this.active = active;
    }

    public static void encode(ReviveHeartbeatPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.targetEntityId);
        buf.writeBoolean(msg.active);
    }

    public static ReviveHeartbeatPacket decode(FriendlyByteBuf buf) {
        int targetEntityId = buf.readVarInt();
        boolean active = buf.readBoolean();
        return new ReviveHeartbeatPacket(targetEntityId, active);
    }

    public static void handle(ReviveHeartbeatPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) {
                Act0Battlefield.manager().handleReviveHeartbeat(player, msg.targetEntityId, msg.active);
                Act0Battlefield.BREAKTHROUGH_MANAGER.handleReviveHeartbeat(player, msg.targetEntityId, msg.active);
            }
        });
        context.setPacketHandled(true);
    }
}
