package org.shee33.act0.battlefield.network;

import com.mojang.logging.LogUtils;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.shee33.act0.battlefield.Act0Battlefield;
import org.slf4j.Logger;

import java.util.function.Supplier;

/**
 * C2S：部署界面底部武器更换面板（《部署界面动效规格文档》3.6 节）提交一次槽位覆盖选择。
 *
 * <p>这是"这一命"临时换装——只影响本次重生落地时应用的装备，不改变 Arcade 里保存的配装本身
 * （见 {@code RedeployService} 的 {@code loadoutOverrides} 会话状态）。服务端收到后会重新校验
 * 该物品确实在该槽位的已解锁可选项内（防止伪造包塞入未解锁物品），通过后写入会话状态并重新
 * 下发一次 {@link DeployLoadoutDto} 让客户端 UI 立即反映新选择。
 *
 * <p>玩家可能同时只在 Conquest 或 Breakthrough 一种模式对局中，具体路由到哪个模式由
 * {@link #handle} 里同时尝试两个管理器决定（与 {@code DownedActionPacket}/
 * {@code ReviveHeartbeatPacket} 同款写法，各管理器内部会自行判断玩家是否在其管理的对局中）。
 */
public final class DeploySlotOverridePacket {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final int slotIndex;
    private final String itemName;

    public DeploySlotOverridePacket(int slotIndex, String itemName) {
        this.slotIndex = slotIndex;
        this.itemName = itemName != null ? itemName : "";
    }

    public static void encode(DeploySlotOverridePacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.slotIndex);
        buf.writeUtf(msg.itemName);
    }

    public static DeploySlotOverridePacket decode(FriendlyByteBuf buf) {
        return new DeploySlotOverridePacket(buf.readVarInt(), buf.readUtf());
    }

    public static void handle(DeploySlotOverridePacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) {
                try {
                    Act0Battlefield.manager().handleDeploySlotOverride(player, msg.slotIndex, msg.itemName);
                    Act0Battlefield.BREAKTHROUGH_MANAGER.handleDeploySlotOverride(player, msg.slotIndex, msg.itemName);
                } catch (Throwable t) {
                    LOGGER.warn("DeploySlotOverridePacket handler failed for {}", player.getGameProfile().getName(), t);
                }
            }
        });
        context.setPacketHandled(true);
    }
}
