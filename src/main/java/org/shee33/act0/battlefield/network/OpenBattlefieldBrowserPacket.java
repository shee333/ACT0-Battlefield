package org.shee33.act0.battlefield.network;

import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import org.shee33.act0.battlefield.client.screen.BattlefieldRoomBrowserScreen;

import java.util.function.Supplier;

/**
 * S→C：告知客户端打开对局浏览器（无载荷）。由战地终端物品右键 / {@code /battlefield ui} 等
 * 服务端触发点发出——这些入口本身运行在服务端，无法直接调用客户端的 {@code Minecraft.setScreen}，
 * 需要这一跳网络包。已经打开浏览器时不重新构造（避免重播开场级联动效）。
 */
public final class OpenBattlefieldBrowserPacket {

    public OpenBattlefieldBrowserPacket() {
    }

    public static void encode(OpenBattlefieldBrowserPacket msg, FriendlyByteBuf buf) {
    }

    public static OpenBattlefieldBrowserPacket decode(FriendlyByteBuf buf) {
        return new OpenBattlefieldBrowserPacket();
    }

    public static void handle(OpenBattlefieldBrowserPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            Minecraft mc = Minecraft.getInstance();
            if (!(mc.screen instanceof BattlefieldRoomBrowserScreen)) {
                mc.setScreen(new BattlefieldRoomBrowserScreen());
            }
        }));
        context.setPacketHandled(true);
    }
}
