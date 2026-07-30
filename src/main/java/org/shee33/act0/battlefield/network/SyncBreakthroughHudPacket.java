package org.shee33.act0.battlefield.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import org.shee33.act0.battlefield.client.ClientBreakthroughHud;

import java.util.function.Supplier;

/**
 * S→C：突破模式 HUD 快照。{@code dto.show()=false} 表示清除 HUD。
 */
public final class SyncBreakthroughHudPacket {

    private final BreakthroughHudDto dto;

    public SyncBreakthroughHudPacket(BreakthroughHudDto dto) {
        this.dto = dto;
    }

    public static void encode(SyncBreakthroughHudPacket msg, FriendlyByteBuf buf) {
        msg.dto.encode(buf);
    }

    public static SyncBreakthroughHudPacket decode(FriendlyByteBuf buf) {
        return new SyncBreakthroughHudPacket(BreakthroughHudDto.decode(buf));
    }

    public static void handle(SyncBreakthroughHudPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientBreakthroughHud.accept(msg.dto)));
        context.setPacketHandled(true);
    }
}
