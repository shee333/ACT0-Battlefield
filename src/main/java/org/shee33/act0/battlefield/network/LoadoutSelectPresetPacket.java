package org.shee33.act0.battlefield.network;

import com.mojang.logging.LogUtils;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.shee33.act0.battlefield.loadout.LoadoutConfigService;
import org.slf4j.Logger;

import java.util.function.Supplier;

/**
 * C2S：配装界面点击 4×4 网格的某一格——选定该兵种并把该格设为激活配装。
 *
 * <p>一次点击同时完成「切兵种 + 切激活套」，服务端回发整屏快照。
 */
public final class LoadoutSelectPresetPacket {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final String mapName;
    private final String classId;
    private final int presetIndex;

    public LoadoutSelectPresetPacket(String mapName, String classId, int presetIndex) {
        this.mapName = mapName != null ? mapName : "";
        this.classId = classId != null ? classId : "";
        this.presetIndex = presetIndex;
    }

    public static void encode(LoadoutSelectPresetPacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.mapName);
        buf.writeUtf(msg.classId);
        buf.writeVarInt(msg.presetIndex);
    }

    public static LoadoutSelectPresetPacket decode(FriendlyByteBuf buf) {
        return new LoadoutSelectPresetPacket(buf.readUtf(), buf.readUtf(), buf.readVarInt());
    }

    public static void handle(LoadoutSelectPresetPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) {
                try {
                    LoadoutConfigService.selectPreset(player, msg.mapName, msg.classId, msg.presetIndex);
                } catch (Throwable t) {
                    LOGGER.warn("LoadoutSelectPresetPacket handler failed for {}",
                            player.getGameProfile().getName(), t);
                }
            }
        });
        context.setPacketHandled(true);
    }
}
