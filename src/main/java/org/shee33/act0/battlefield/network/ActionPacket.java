package org.shee33.act0.battlefield.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.shee33.act0.battlefield.Act0Battlefield;
import org.shee33.act0.battlefield.core.Faction;

import java.util.function.Supplier;

/**
 * C→S：加入界面的玩家操作。{@link Action#JOIN_ALPHA}/{@link Action#JOIN_BRAVO} 选边、
 * {@link Action#LEAVE} 退出候选名单、{@link Action#START}/{@link Action#STOP} 开局/停止（仅 OP）、
 * {@link Action#OPEN} 请求服务端打开 GUI、{@link Action#OPEN_LOADOUT} 请求打开 Arcade 配装、
 * {@link Action#REFRESH} 请求刷新。处理后回推最新状态。
 */
public final class ActionPacket {

    public enum Action {
        JOIN_ALPHA, JOIN_BRAVO, LEAVE, START, STOP, OPEN, OPEN_LOADOUT, REFRESH
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
                Act0Battlefield.manager().handleAction(player, msg.action);
            }
        });
        context.setPacketHandled(true);
    }

    /** 把 JOIN_ALPHA/JOIN_BRAVO 映射为阵营；非加入动作返回 {@code null}。 */
    public static Faction factionOf(Action action) {
        if (action == Action.JOIN_ALPHA) {
            return Faction.ALPHA;
        }
        if (action == Action.JOIN_BRAVO) {
            return Faction.BRAVO;
        }
        return null;
    }
}
