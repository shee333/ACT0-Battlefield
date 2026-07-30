package org.shee33.act0.battlefield.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import org.shee33.act0.battlefield.client.ClientBattleHud;

import java.util.List;
import java.util.function.Supplier;

/**
 * S→C：BF 风格大战场 HUD 快照。{@code show=false} 表示隐藏 HUD。
 */
public final class SyncBattleHudPacket {

    private final boolean show;
    private final BattleHudDto hud;

    public SyncBattleHudPacket(boolean show, BattleHudDto hud) {
        this.show = show;
        this.hud = hud;
    }

    public static void encode(SyncBattleHudPacket msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.show);
        if (msg.show) {
            msg.hud.encode(buf);
        }
    }

    public static SyncBattleHudPacket decode(FriendlyByteBuf buf) {
        boolean show = buf.readBoolean();
        BattleHudDto hud = show ? BattleHudDto.decode(buf)
            : new BattleHudDto(0, 0, 0, 1, List.of(), List.of(), "", 0, 0, 0, List.of(), "", 0, false, 0, 0, false);
        return new SyncBattleHudPacket(show, hud);
    }

    public static void handle(SyncBattleHudPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientBattleHud.accept(msg.show, msg.hud)));
        context.setPacketHandled(true);
    }
}
