package org.shee33.act0.battlefield.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import org.shee33.act0.battlefield.client.ClientDeployLoadout;

import java.util.function.Supplier;

public final class SyncDeployLoadoutPacket {

    private final DeployLoadoutDto dto;

    public SyncDeployLoadoutPacket(DeployLoadoutDto dto) {
        this.dto = dto;
    }

    public static void encode(SyncDeployLoadoutPacket msg, FriendlyByteBuf buf) {
        msg.dto.encode(buf);
    }

    public static SyncDeployLoadoutPacket decode(FriendlyByteBuf buf) {
        return new SyncDeployLoadoutPacket(DeployLoadoutDto.decode(buf));
    }

    public static void handle(SyncDeployLoadoutPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientDeployLoadout.accept(msg.dto)));
        ctx.get().setPacketHandled(true);
    }
}
