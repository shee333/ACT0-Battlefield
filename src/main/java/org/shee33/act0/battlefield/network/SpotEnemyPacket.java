package org.shee33.act0.battlefield.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.shee33.act0.battlefield.Act0Battlefield;

import java.util.function.Supplier;

/**
 * C2S: 玩家按 Q 标记敌人（索敌），服务端将被标记实体 setGlowingTag(true) 并通过 GlowSync 向队友同步。
 */
public final class SpotEnemyPacket {

    private final int targetId;

    public SpotEnemyPacket(int targetId) {
        this.targetId = targetId;
    }

    public static void encode(SpotEnemyPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.targetId);
    }

    public static SpotEnemyPacket decode(FriendlyByteBuf buf) {
        return new SpotEnemyPacket(buf.readVarInt());
    }

    public static void handle(SpotEnemyPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) {
                Act0Battlefield.manager().spotEnemy(player, msg.targetId);
            }
        });
        context.setPacketHandled(true);
    }
}