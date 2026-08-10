package org.shee33.act0.battlefield.network;

import com.mojang.logging.LogUtils;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.shee33.act0.battlefield.Act0Battlefield;
import org.slf4j.Logger;

import java.util.function.Supplier;

/**
 * C2S：玩家在世界某处打一个战术标记（Ping），服务端转发给同小队成员。
 *
 * <p>只带 XZ——小地图是 2D 俯视，Y 用不上，少两个字节也少一份可被滥用的信息。
 * 服务端会重新校验坐标是否在玩家可及范围内，不信任客户端给的位置。
 */
public final class MarkPingPacket {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final double x;
    private final double z;

    public MarkPingPacket(double x, double z) {
        this.x = x;
        this.z = z;
    }

    public static void encode(MarkPingPacket msg, FriendlyByteBuf buf) {
        buf.writeDouble(msg.x);
        buf.writeDouble(msg.z);
    }

    public static MarkPingPacket decode(FriendlyByteBuf buf) {
        return new MarkPingPacket(buf.readDouble(), buf.readDouble());
    }

    public static void handle(MarkPingPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) {
                try {
                    Act0Battlefield.manager().markPing(player, msg.x, msg.z);
                } catch (Throwable t) {
                    LOGGER.warn("MarkPingPacket handler failed for {}",
                            player.getGameProfile().getName(), t);
                }
            }
        });
        context.setPacketHandled(true);
    }
}
