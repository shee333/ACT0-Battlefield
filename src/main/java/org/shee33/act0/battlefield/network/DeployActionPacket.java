package org.shee33.act0.battlefield.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.shee33.act0.battlefield.Act0Battlefield;

import java.util.function.Supplier;

/**
 * C→S：部署界面操作。选择小队/据点/基地；若倒计时已结束则直接部署。
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

    public DeployActionPacket(DeployKind kind) {
        this.kind = kind;
    }

    public static void encode(DeployActionPacket msg, FriendlyByteBuf buf) {
        buf.writeEnum(msg.kind);
    }

    public static DeployActionPacket decode(FriendlyByteBuf buf) {
        return new DeployActionPacket(buf.readEnum(DeployKind.class));
    }

    public static void handle(DeployActionPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null && Act0Battlefield.manager().active() != null) {
                Act0Battlefield.manager().active().handleDeployAction(player, msg.kind.id());
            }
        });
        context.setPacketHandled(true);
    }
}
