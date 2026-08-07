package org.shee33.act0.battlefield.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.shee33.act0.battlefield.Act0Battlefield;

import java.util.List;
import java.util.function.Supplier;

/** C2S：请求刷新对局浏览器房间列表（无载荷；打开浏览器时/客户端周期性轮询时发出）。 */
public final class RequestBattlefieldRoomListPacket {

    public RequestBattlefieldRoomListPacket() {
    }

    public static void encode(RequestBattlefieldRoomListPacket msg, FriendlyByteBuf buf) {
    }

    public static RequestBattlefieldRoomListPacket decode(FriendlyByteBuf buf) {
        return new RequestBattlefieldRoomListPacket();
    }

    public static void handle(RequestBattlefieldRoomListPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            MinecraftServer server = player.getServer();
            if (server == null) {
                return;
            }
            List<BattlefieldRoomDto> rows = Act0Battlefield.snapshotAllRooms(server, player.getUUID());
            BattlefieldNetwork.sendRoomList(player, rows);
        });
        context.setPacketHandled(true);
    }
}
