package org.shee33.act0.battlefield.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import org.shee33.act0.battlefield.client.ClientMinimapEvents;

import java.util.function.Supplier;

/**
 * S2C：受击来源<b>方位角</b>，驱动小地图内圈的受击方向弧。
 *
 * <p>刻意只下发方位角、<b>不下发敌人坐标</b>——"小地图不显示敌人位置"是既有架构决策，方位
 * 只够玩家知道"威胁来自哪个方向"，无法反推出对方在哪。
 *
 * <p>方位以正北为 0、顺时针为正（与 {@code MinimapMath.worldBearing} 同一约定），单位弧度。
 */
public final class DamageDirectionPacket {

    private final float bearingRad;

    public DamageDirectionPacket(float bearingRad) {
        this.bearingRad = bearingRad;
    }

    public static void encode(DamageDirectionPacket msg, FriendlyByteBuf buf) {
        buf.writeFloat(msg.bearingRad);
    }

    public static DamageDirectionPacket decode(FriendlyByteBuf buf) {
        return new DamageDirectionPacket(buf.readFloat());
    }

    public static void handle(DamageDirectionPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientMinimapEvents.onDamageFrom(msg.bearingRad)));
        context.setPacketHandled(true);
    }
}
