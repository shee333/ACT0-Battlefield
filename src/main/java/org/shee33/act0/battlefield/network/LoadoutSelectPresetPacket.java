package org.shee33.act0.battlefield.network;

import com.mojang.logging.LogUtils;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.shee33.act0.battlefield.loadout.LoadoutConfigService;
import org.slf4j.Logger;

import java.util.function.Supplier;

/**
 * C2S：玩家在配装界面为某阵营某兵种选中一套配装（只选不编）。
 *
 * <p>服务端校验该配装 id 确实存在于该图该阵营该兵种下，通过则记录选择并回发整屏快照。
 */
public final class LoadoutSelectPresetPacket {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final String mapName;
    private final String factionId;
    private final String classId;
    private final String presetId;

    public LoadoutSelectPresetPacket(String mapName, String factionId, String classId, String presetId) {
        this.mapName = mapName != null ? mapName : "";
        this.factionId = factionId != null ? factionId : "";
        this.classId = classId != null ? classId : "";
        this.presetId = presetId != null ? presetId : "";
    }

    public static void encode(LoadoutSelectPresetPacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.mapName);
        buf.writeUtf(msg.factionId);
        buf.writeUtf(msg.classId);
        buf.writeUtf(msg.presetId);
    }

    public static LoadoutSelectPresetPacket decode(FriendlyByteBuf buf) {
        return new LoadoutSelectPresetPacket(buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readUtf());
    }

    public static void handle(LoadoutSelectPresetPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) {
                try {
                    LoadoutConfigService.selectPreset(player, msg.mapName, msg.factionId,
                            msg.classId, msg.presetId);
                } catch (Throwable t) {
                    LOGGER.warn("LoadoutSelectPresetPacket handler failed for {}",
                            player.getGameProfile().getName(), t);
                }
            }
        });
        context.setPacketHandled(true);
    }
}
