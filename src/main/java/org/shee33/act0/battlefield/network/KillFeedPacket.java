package org.shee33.act0.battlefield.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import org.shee33.act0.battlefield.client.ClientKillFeed;

import java.util.function.Supplier;

/**
 * S→C：战中击杀提示。客户端右上角显示短暂击杀信息。
 */
public final class KillFeedPacket {

    private final String killer;
    private final String victim;
    private final int killerFaction;
    private final int victimFaction;

    public KillFeedPacket(String killer, String victim, int killerFaction, int victimFaction) {
        this.killer = killer != null ? killer : "";
        this.victim = victim != null ? victim : "";
        this.killerFaction = killerFaction;
        this.victimFaction = victimFaction;
    }

    public static void encode(KillFeedPacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.killer);
        buf.writeUtf(msg.victim);
        buf.writeVarInt(msg.killerFaction);
        buf.writeVarInt(msg.victimFaction);
    }

    public static KillFeedPacket decode(FriendlyByteBuf buf) {
        return new KillFeedPacket(buf.readUtf(), buf.readUtf(), buf.readVarInt(), buf.readVarInt());
    }

    public static void handle(KillFeedPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientKillFeed.add(msg.killer, msg.victim, msg.killerFaction, msg.victimFaction)));
        context.setPacketHandled(true);
    }
}
