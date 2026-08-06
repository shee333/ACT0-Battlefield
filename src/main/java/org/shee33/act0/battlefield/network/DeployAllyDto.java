package org.shee33.act0.battlefield.network;

import net.minecraft.network.FriendlyByteBuf;

/**
 * 部署地图上的一个同阵营(非小队)存活玩家标记。
 *
 * <p>纯展示用途，不可交互——见《部署界面动效规格文档》3.3 节选点交互表格："同阵营玩家"一行
 * "可交互"列为"否"（与"小队成员"一行的"是"相对），因此本 DTO 不携带 {@code deployable} 字段。
 */
public record DeployAllyDto(String id, String name, int entityId, double x, double y, double z) {

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(id);
        buf.writeUtf(name);
        buf.writeVarInt(entityId);
        buf.writeDouble(x);
        buf.writeDouble(y);
        buf.writeDouble(z);
    }

    public static DeployAllyDto decode(FriendlyByteBuf buf) {
        return new DeployAllyDto(buf.readUtf(), buf.readUtf(), buf.readVarInt(),
                buf.readDouble(), buf.readDouble(), buf.readDouble());
    }
}
