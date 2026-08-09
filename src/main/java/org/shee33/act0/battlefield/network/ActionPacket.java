package org.shee33.act0.battlefield.network;

import com.mojang.logging.LogUtils;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.shee33.act0.battlefield.Act0Battlefield;
import org.slf4j.Logger;

import java.util.function.Supplier;

/**
 * C→S：加入界面的玩家操作。{@link Action#JOIN} 加入（阵营由服务端随机分配）、
 * {@link Action#LEAVE} 退出候选名单、{@link Action#START}/{@link Action#STOP} 开局/停止（仅 OP）、
 * {@link Action#OPEN} 请求服务端打开 GUI、{@link Action#OPEN_LOADOUT} 请求打开 Arcade 配装、
 * {@link Action#REFRESH} 请求刷新。处理后回推最新状态。
 */
public final class ActionPacket {

    private static final Logger LOGGER = LogUtils.getLogger();

    public enum Action {
        /**
         * 加入候选名单/对局。阵营由服务端随机分配——玩家不再自行选边，
         * 见 {@code ConquestManager#assignRandomFaction}。
         */
        JOIN, LEAVE, START, STOP, OPEN, OPEN_LOADOUT, REFRESH
    }

    private final Action action;

    public ActionPacket(Action action) {
        this.action = action;
    }

    public static void encode(ActionPacket msg, FriendlyByteBuf buf) {
        buf.writeEnum(msg.action);
    }

    public static ActionPacket decode(FriendlyByteBuf buf) {
        int ordinal = buf.readVarInt();
        Action[] actions = Action.values();
        Action action = (ordinal >= 0 && ordinal < actions.length) ? actions[ordinal] : Action.REFRESH;
        return new ActionPacket(action);
    }

    public static void handle(ActionPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) {
                try {
                    Act0Battlefield.manager().handleAction(player, msg.action);
                } catch (Throwable t) {
                    LOGGER.warn("ActionPacket handler failed for {}", player.getGameProfile().getName(), t);
                }
            }
        });
        context.setPacketHandled(true);
    }

}
