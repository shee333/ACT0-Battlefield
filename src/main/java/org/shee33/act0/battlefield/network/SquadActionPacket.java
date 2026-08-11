package org.shee33.act0.battlefield.network;

import com.mojang.logging.LogUtils;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.shee33.act0.battlefield.Act0Battlefield;
import org.slf4j.Logger;

import java.util.function.Supplier;

/**
 * C2S：暂停菜单里的小队操作。
 *
 * <p>服务端会完整复核权限与准入（{@code SquadJoinRules}），不信任客户端上报——否则任何人都能
 * 靠伪包挤进已锁定或满员的小队。
 */
public final class SquadActionPacket {

    /** 切换本队锁定（仅队长）。 */
    public static final int KIND_TOGGLE_LOCK = 0;
    /** 离开当前小队。 */
    public static final int KIND_LEAVE = 1;
    /** 加入指定小队。 */
    public static final int KIND_JOIN = 2;

    private static final Logger LOGGER = LogUtils.getLogger();

    private final int kind;
    private final int targetSquadId;

    public SquadActionPacket(int kind, int targetSquadId) {
        this.kind = kind;
        this.targetSquadId = targetSquadId;
    }

    public static void encode(SquadActionPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.kind);
        buf.writeVarInt(msg.targetSquadId);
    }

    public static SquadActionPacket decode(FriendlyByteBuf buf) {
        return new SquadActionPacket(buf.readVarInt(), buf.readVarInt());
    }

    public static void handle(SquadActionPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            try {
                if (!Act0Battlefield.manager().handleSquadAction(player, msg.kind, msg.targetSquadId)) {
                    Act0Battlefield.BREAKTHROUGH_MANAGER.handleSquadAction(player, msg.kind, msg.targetSquadId);
                }
            } catch (Throwable t) {
                LOGGER.warn("{}squad action failed: {}", Act0Battlefield.LOG_PREFIX, t.toString());
            }
        });
        context.setPacketHandled(true);
    }
}
