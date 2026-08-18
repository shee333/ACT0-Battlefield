package org.shee33.act0.battlefield.network;

import com.mojang.logging.LogUtils;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.shee33.act0.battlefield.loadout.LoadoutConfigService;
import org.slf4j.Logger;

import java.util.function.Supplier;

/**
 * C2S：请求某张地图的配装数据。
 *
 * <p>{@code mapName} 传空串表示"随便给我第一张"，供配装界面首次打开时使用——客户端此时
 * 还不知道服务端有哪些地图，不该被迫先猜一个名字。
 */
public final class RequestLoadoutConfigPacket {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final String mapName;

    public RequestLoadoutConfigPacket(String mapName) {
        this.mapName = mapName != null ? mapName : "";
    }

    public static void encode(RequestLoadoutConfigPacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.mapName);
    }

    public static RequestLoadoutConfigPacket decode(FriendlyByteBuf buf) {
        return new RequestLoadoutConfigPacket(buf.readUtf());
    }

    public static void handle(RequestLoadoutConfigPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) {
                try {
                    LoadoutConfigService.sendSnapshot(player, msg.mapName);
                } catch (Throwable t) {
                    LOGGER.warn("RequestLoadoutConfigPacket handler failed for {}",
                            player.getGameProfile().getName(), t);
                }
            }
        });
        context.setPacketHandled(true);
    }
}
