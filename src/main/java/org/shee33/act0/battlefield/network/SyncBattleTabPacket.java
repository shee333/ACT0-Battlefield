package org.shee33.act0.battlefield.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import org.shee33.act0.battlefield.client.ClientBattleTab;

import java.util.List;
import java.util.function.Supplier;

/** S→C：自定义 TAB 战绩面板快照。 */
public final class SyncBattleTabPacket {

    private final boolean show;
    private final BattleTabDto tab;

    public SyncBattleTabPacket(boolean show, BattleTabDto tab) {
        this.show = show;
        this.tab = tab;
    }

    public static void encode(SyncBattleTabPacket msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.show);
        if (msg.show) {
            msg.tab.encode(buf);
        }
    }

    public static SyncBattleTabPacket decode(FriendlyByteBuf buf) {
        boolean show = buf.readBoolean();
        BattleTabDto tab = show ? BattleTabDto.decode(buf)
                : new BattleTabDto(0, 0, 0, List.of(), List.of());
        return new SyncBattleTabPacket(show, tab);
    }

    public static void handle(SyncBattleTabPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientBattleTab.accept(msg.show, msg.tab)));
        context.setPacketHandled(true);
    }
}
