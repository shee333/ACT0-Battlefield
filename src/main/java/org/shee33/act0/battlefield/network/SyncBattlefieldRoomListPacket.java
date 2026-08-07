package org.shee33.act0.battlefield.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import org.shee33.act0.battlefield.client.ClientBattlefieldRoomList;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** S→C：下发对局浏览器的完整房间列表快照（等待中 + 运行中）。 */
public final class SyncBattlefieldRoomListPacket {

    private final List<BattlefieldRoomDto> rooms;

    public SyncBattlefieldRoomListPacket(List<BattlefieldRoomDto> rooms) {
        this.rooms = rooms;
    }

    public static void encode(SyncBattlefieldRoomListPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.rooms.size());
        for (BattlefieldRoomDto room : msg.rooms) {
            room.encode(buf);
        }
    }

    public static SyncBattlefieldRoomListPacket decode(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<BattlefieldRoomDto> rooms = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            rooms.add(BattlefieldRoomDto.decode(buf));
        }
        return new SyncBattlefieldRoomListPacket(rooms);
    }

    public static void handle(SyncBattlefieldRoomListPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientBattlefieldRoomList.accept(msg.rooms)));
        context.setPacketHandled(true);
    }
}
