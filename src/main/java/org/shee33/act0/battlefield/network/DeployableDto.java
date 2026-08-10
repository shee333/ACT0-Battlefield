package org.shee33.act0.battlefield.network;

import net.minecraft.network.FriendlyByteBuf;

/**
 * 一个已部署补给物的客户端视图：类型、世界坐标与剩余时长。
 *
 * <p>只下发给同阵营玩家（见 {@code ConquestMatch#broadcastDeployables}）——地面提示圆同时也是
 * 一个"这里有人在补给"的战术信息，敌方不应看到。
 */
public record DeployableDto(int kind, double x, double y, double z, int remainingTicks) {

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(kind);
        buf.writeDouble(x);
        buf.writeDouble(y);
        buf.writeDouble(z);
        buf.writeVarInt(remainingTicks);
    }

    public static DeployableDto decode(FriendlyByteBuf buf) {
        return new DeployableDto(buf.readVarInt(), buf.readDouble(), buf.readDouble(), buf.readDouble(),
                buf.readVarInt());
    }
}
