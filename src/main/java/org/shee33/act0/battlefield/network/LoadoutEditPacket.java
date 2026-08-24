package org.shee33.act0.battlefield.network;

import com.mojang.logging.LogUtils;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.shee33.act0.battlefield.loadout.LoadoutConfigService;
import org.slf4j.Logger;

import java.util.function.Supplier;

/**
 * C2S：配装界面改动某个兵种某套配装里的一个槽位。
 *
 * <p>与 {@link DeploySlotOverridePacket} 的区别：那个走对局，地图取自当前对局、且只改激活套；
 * 这个显式带地图、兵种与配装序号，因此在没有任何对局时也能用——这正是把 ESC 菜单做成全局的意义。
 *
 * <p>服务端一律重新校验该物品在该图该槽位的目录里，通过与否都回发整屏快照。
 */
public final class LoadoutEditPacket {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final String mapName;
    private final String classId;
    private final int presetIndex;
    private final int slotIndex;
    private final String itemId;

    public LoadoutEditPacket(String mapName, String classId, int presetIndex, int slotIndex, String itemId) {
        this.mapName = mapName != null ? mapName : "";
        this.classId = classId != null ? classId : "";
        this.presetIndex = presetIndex;
        this.slotIndex = slotIndex;
        this.itemId = itemId != null ? itemId : "";
    }

    public static void encode(LoadoutEditPacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.mapName);
        buf.writeUtf(msg.classId);
        buf.writeVarInt(msg.presetIndex);
        buf.writeVarInt(msg.slotIndex);
        buf.writeUtf(msg.itemId);
    }

    public static LoadoutEditPacket decode(FriendlyByteBuf buf) {
        return new LoadoutEditPacket(buf.readUtf(), buf.readUtf(), buf.readVarInt(), buf.readVarInt(), buf.readUtf());
    }

    public static void handle(LoadoutEditPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) {
                try {
                    LoadoutConfigService.edit(player, msg.mapName, msg.classId, msg.presetIndex,
                            msg.slotIndex, msg.itemId);
                } catch (Throwable t) {
                    LOGGER.warn("LoadoutEditPacket handler failed for {}",
                            player.getGameProfile().getName(), t);
                }
            }
        });
        context.setPacketHandled(true);
    }
}
