package org.shee33.act0.battlefield.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import org.shee33.act0.battlefield.client.ClientKillFeed;

import java.util.function.Supplier;

public final class KillFeedPacket {

    private final String killer, victim, weapon;
    private final int killerFaction, victimFaction;

    public KillFeedPacket(String killer, String victim, int killerFaction, int victimFaction, String weapon) {
        this.killer = killer != null ? killer : "";
        this.victim = victim != null ? victim : "";
        this.killerFaction = killerFaction;
        this.victimFaction = victimFaction;
        this.weapon = weapon != null ? weapon : "";
    }

    public static void encode(KillFeedPacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.killer);
        buf.writeUtf(msg.victim);
        buf.writeVarInt(msg.killerFaction);
        buf.writeVarInt(msg.victimFaction);
        buf.writeUtf(msg.weapon);
    }

    public static KillFeedPacket decode(FriendlyByteBuf buf) {
        return new KillFeedPacket(buf.readUtf(), buf.readUtf(), buf.readVarInt(), buf.readVarInt(), buf.readUtf());
    }

    public static void handle(KillFeedPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientKillFeed.add(msg.killer, msg.victim, msg.killerFaction, msg.victimFaction, msg.weapon)));
        context.setPacketHandled(true);
    }
}