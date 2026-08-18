package org.shee33.act0.battlefield.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import org.shee33.act0.battlefield.client.ClientLoadoutConfig;

import java.util.function.Supplier;

public final class SyncLoadoutConfigPacket {

    private final LoadoutConfigDto dto;

    public SyncLoadoutConfigPacket(LoadoutConfigDto dto) {
        this.dto = dto;
    }

    public static void encode(SyncLoadoutConfigPacket msg, FriendlyByteBuf buf) {
        msg.dto.encode(buf);
    }

    public static SyncLoadoutConfigPacket decode(FriendlyByteBuf buf) {
        return new SyncLoadoutConfigPacket(LoadoutConfigDto.decode(buf));
    }

    public static void handle(SyncLoadoutConfigPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientLoadoutConfig.accept(msg.dto)));
        ctx.get().setPacketHandled(true);
    }
}
