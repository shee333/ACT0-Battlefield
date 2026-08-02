package org.shee33.act0.battlefield.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.shee33.act0.battlefield.Act0Battlefield;
import org.shee33.act0.battlefield.match.ConquestMatch;

import java.util.function.Supplier;

/**
 * C2S: deploy screen action. Supports selecting a concrete point target.
 */
public final class DeployActionPacket {

    public enum DeployKind {
        SQUAD("squad"), POINT("point"), BASE("base"), REFRESH("");

        private final String id;

        DeployKind(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }

    private final DeployKind kind;
    private final String targetId;

    public DeployActionPacket(DeployKind kind) {
        this(kind, "");
    }

    public DeployActionPacket(DeployKind kind, String targetId) {
        this.kind = kind;
        this.targetId = targetId != null ? targetId : "";
    }

    public static void encode(DeployActionPacket msg, FriendlyByteBuf buf) {
        buf.writeEnum(msg.kind);
        buf.writeUtf(msg.targetId);
    }

    public static DeployActionPacket decode(FriendlyByteBuf buf) {
        int ordinal = buf.readVarInt();
        DeployKind[] kinds = DeployKind.values();
        DeployKind kind = (ordinal >= 0 && ordinal < kinds.length) ? kinds[ordinal] : DeployKind.REFRESH;
        return new DeployActionPacket(kind, buf.readUtf());
    }

    public static void handle(DeployActionPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            ConquestMatch match = player != null ? Act0Battlefield.manager().activeContaining(player.getUUID()) : null;
            if (player != null && match != null) {
                if (msg.kind == DeployKind.REFRESH) {
                    match.refreshDeployStatus(player);
                } else {
                    match.handleDeployAction(player, msg.kind.id(), msg.targetId);
                }
            }
        });
        context.setPacketHandled(true);
    }
}
