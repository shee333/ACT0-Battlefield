package org.shee33.act0.battlefield.network;

import net.minecraft.network.FriendlyByteBuf;

/**
 * HUD 用的单个据点快照。
 *
 * @param name         据点名（A/B/C 或自定义）
 * @param owner        归属：0=中立，1=红队，2=蓝队
 * @param pressure     当前进度倾向：0=无/中立，1=红队方向，2=蓝队方向
 * @param progress     占领/中和进度百分比（0~100）
 * @param x            世界坐标 X（据点中心）
 * @param y            世界坐标 Y（据点中心）
 * @param z            世界坐标 Z（据点中心）
 */
public record ControlPointHudDto(String name, int owner, int pressure, int progress, double x, double y, double z) {

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(name);
        buf.writeVarInt(owner);
        buf.writeVarInt(pressure);
        buf.writeVarInt(progress);
        buf.writeDouble(x);
        buf.writeDouble(y);
        buf.writeDouble(z);
    }

    public static ControlPointHudDto decode(FriendlyByteBuf buf) {
        return new ControlPointHudDto(buf.readUtf(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                buf.readDouble(), buf.readDouble(), buf.readDouble());
    }
}
