package org.shee33.act0.battlefield.network;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.shee33.act0.battlefield.Act0Battlefield;
import net.minecraft.network.FriendlyByteBuf;
import org.slf4j.Logger;

import java.util.function.Supplier;

/** C2S: 倒地玩家操作（呼叫救援 / 放弃）。 */
public final class DownedActionPacket {

    private static final Logger LOGGER = LogUtils.getLogger();

    public enum Action { CALL_HELP, GIVE_UP }

    private final Action action;

    public DownedActionPacket(Action action) {
        this.action = action;
    }

    public static void encode(DownedActionPacket msg, FriendlyByteBuf buf) {
        buf.writeEnum(msg.action);
    }

    public static DownedActionPacket decode(FriendlyByteBuf buf) {
        int ordinal = buf.readVarInt();
        Action[] actions = Action.values();
        Action action = (ordinal >= 0 && ordinal < actions.length) ? actions[ordinal] : Action.GIVE_UP;
        return new DownedActionPacket(action);
    }

    public static void handle(DownedActionPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) {
                try {
                    Act0Battlefield.manager().handleDownedAction(player, msg.action);
                    Act0Battlefield.BREAKTHROUGH_MANAGER.handleDownedAction(player, msg.action);
                } catch (Throwable t) {
                    LOGGER.warn("DownedActionPacket handler failed for {}", player.getGameProfile().getName(), t);
                }
            }
        });
        context.setPacketHandled(true);
    }
}