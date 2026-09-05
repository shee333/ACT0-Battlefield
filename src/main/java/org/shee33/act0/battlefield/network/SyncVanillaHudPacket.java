package org.shee33.act0.battlefield.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import org.shee33.act0.battlefield.client.ClientVanillaHud;

import java.util.function.Supplier;

/**
 * S→C：本图对局 HUD 是否用原版快捷栏（放右下角）替代自绘武器栏。
 *
 * <p>管理员用 {@code /aew1 hud vanilla true|false} 切换，本包把当前值广播给玩家；
 * 玩家进入对局时也会收到一次当前值，保证新进场的人不会沿用自己的旧状态。
 */
public final class SyncVanillaHudPacket {

    private final boolean vanillaHud;

    public SyncVanillaHudPacket(boolean vanillaHud) {
        this.vanillaHud = vanillaHud;
    }

    public static void encode(SyncVanillaHudPacket msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.vanillaHud);
    }

    public static SyncVanillaHudPacket decode(FriendlyByteBuf buf) {
        return new SyncVanillaHudPacket(buf.readBoolean());
    }

    public static void handle(SyncVanillaHudPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientVanillaHud.setVanillaHud(msg.vanillaHud)));
        context.setPacketHandled(true);
    }
}
