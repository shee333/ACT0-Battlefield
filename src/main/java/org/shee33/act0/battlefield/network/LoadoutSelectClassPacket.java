package org.shee33.act0.battlefield.network;

import com.mojang.logging.LogUtils;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.shee33.act0.battlefield.Act0Battlefield;
import org.shee33.act0.battlefield.loadout.LoadoutConfigService;
import org.slf4j.Logger;

import java.util.function.Supplier;

/**
 * C2S：切换某张地图上的兵种。部署界面与配装界面共用。
 *
 * <p>{@code fromDeployScreen} 决定回包形态：部署界面要的是 {@link DeployLoadoutDto}（它只认
 * 当前对局那张图），配装界面要的是整屏 {@link LoadoutConfigDto}。两者写入的是同一份存档，
 * 只是读回来的视图不同，因此没有理由拆成两个包。
 */
public final class LoadoutSelectClassPacket {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final String mapName;
    private final String classId;
    private final boolean fromDeployScreen;

    public LoadoutSelectClassPacket(String mapName, String classId, boolean fromDeployScreen) {
        this.mapName = mapName != null ? mapName : "";
        this.classId = classId != null ? classId : "";
        this.fromDeployScreen = fromDeployScreen;
    }

    public static void encode(LoadoutSelectClassPacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.mapName);
        buf.writeUtf(msg.classId);
        buf.writeBoolean(msg.fromDeployScreen);
    }

    public static LoadoutSelectClassPacket decode(FriendlyByteBuf buf) {
        return new LoadoutSelectClassPacket(buf.readUtf(), buf.readUtf(), buf.readBoolean());
    }

    public static void handle(LoadoutSelectClassPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            try {
                if (msg.fromDeployScreen) {
                    Act0Battlefield.manager().handleDeployClassChange(player, msg.classId);
                    Act0Battlefield.BREAKTHROUGH_MANAGER.handleDeployClassChange(player, msg.classId);
                } else {
                    LoadoutConfigService.selectClass(player, msg.mapName, msg.classId);
                }
            } catch (Throwable t) {
                LOGGER.warn("LoadoutSelectClassPacket handler failed for {}",
                        player.getGameProfile().getName(), t);
            }
        });
        context.setPacketHandled(true);
    }
}
