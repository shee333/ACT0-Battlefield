package org.shee33.act0.battlefield.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import org.shee33.act0.battlefield.client.ClientPointZoneHud;
import org.shee33.act0.battlefield.client.ClientVanillaHud;

import java.util.function.Supplier;

/**
 * S→C：本图的 HUD 显示选项（服务端权威，管理员用 {@code /aew1 hud} 切换）。
 *
 * <p>承载两项：{@code vanillaHud}（是否用原版快捷栏替代自绘武器栏）与 {@code pointZone}
 * （第一人称下的据点地面边界高亮，默认开）。玩家进入对局时也会收到一次当前值，保证新进场的
 * 人不会沿用自己的旧状态。
 */
public final class SyncVanillaHudPacket {

    private final boolean vanillaHud;
    private final boolean pointZone;

    public SyncVanillaHudPacket(boolean vanillaHud, boolean pointZone) {
        this.vanillaHud = vanillaHud;
        this.pointZone = pointZone;
    }

    public static void encode(SyncVanillaHudPacket msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.vanillaHud);
        buf.writeBoolean(msg.pointZone);
    }

    public static SyncVanillaHudPacket decode(FriendlyByteBuf buf) {
        return new SyncVanillaHudPacket(buf.readBoolean(), buf.readBoolean());
    }

    public static void handle(SyncVanillaHudPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            ClientVanillaHud.setVanillaHud(msg.vanillaHud);
            ClientPointZoneHud.setEnabled(msg.pointZone);
        }));
        context.setPacketHandled(true);
    }
}