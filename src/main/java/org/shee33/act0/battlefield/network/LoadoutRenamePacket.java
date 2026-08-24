package org.shee33.act0.battlefield.network;

import com.mojang.logging.LogUtils;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.shee33.act0.battlefield.loadout.LoadoutConfigService;
import org.slf4j.Logger;

import java.util.function.Supplier;

/**
 * C2S：给某个兵种的某套配装改名；空串恢复默认名。
 */
public final class LoadoutRenamePacket {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final String mapName;
    private final String classId;
    private final int presetIndex;
    private final String name;

    public LoadoutRenamePacket(String mapName, String classId, int presetIndex, String name) {
        this.mapName = mapName != null ? mapName : "";
        this.classId = classId != null ? classId : "";
        this.presetIndex = presetIndex;
        this.name = name != null ? name : "";
    }

    public static void encode(LoadoutRenamePacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.mapName);
        buf.writeUtf(msg.classId);
        buf.writeVarInt(msg.presetIndex);
        buf.writeUtf(msg.name);
    }

    public static LoadoutRenamePacket decode(FriendlyByteBuf buf) {
        return new LoadoutRenamePacket(buf.readUtf(), buf.readUtf(), buf.readVarInt(), buf.readUtf());
    }

    public static void handle(LoadoutRenamePacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) {
                try {
                    LoadoutConfigService.rename(player, msg.mapName, msg.classId, msg.presetIndex, msg.name);
                } catch (Throwable t) {
                    LOGGER.warn("LoadoutRenamePacket handler failed for {}",
                            player.getGameProfile().getName(), t);
                }
            }
        });
        context.setPacketHandled(true);
    }
}
