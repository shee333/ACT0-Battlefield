package org.shee33.act0.battlefield.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import org.shee33.act0.battlefield.client.ClientSquadRoster;

import java.util.function.Supplier;

/** S2C：本阵营小队名册，驱动暂停菜单的小队管理页。 */
public final class SyncSquadRosterPacket {

    private final SquadRosterDto roster;

    public SyncSquadRosterPacket(SquadRosterDto roster) {
        this.roster = roster;
    }

    public static void encode(SyncSquadRosterPacket msg, FriendlyByteBuf buf) {
        msg.roster.encode(buf);
    }

    public static SyncSquadRosterPacket decode(FriendlyByteBuf buf) {
        return new SyncSquadRosterPacket(SquadRosterDto.decode(buf));
    }

    public static void handle(SyncSquadRosterPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientSquadRoster.accept(msg.roster)));
        context.setPacketHandled(true);
    }
}
