package org.shee33.act0.battlefield.network;

import net.minecraft.network.FriendlyByteBuf;

/**
 * 突破模式 HUD 单个突破点快照。
 *
 * @param pointId     据点 ID，用于据点状态边沿事件（CapturePointEventPacket）反查名称
 * @param name        据点显示名
 * @param owner       归属：0=中立, 1=ALPHA(攻击方), 2=BRAVO(防守方)
 * @param pressure    压制进度（0~100）
 * @param progress    占领/压制进度（0~100）
 * @param locked      是否已锁定（不可被占领，例如已沦陷的据点或最终目标之外的目标）
 * @param sectorIndex 所属区域索引（0 起）—— 占点 HUD 动效规格文档 §3.1/§3.2：客户端凭此字段筛选出
 *                    "当前区域的目标行"（{@code sectorIndex == hud.currentSectorId()}），
 *                    而不必依赖 {@code core.Sector} 这类服务端专属数据类型。
 * @param x           据点世界坐标 X（据点中心），供客户端计算距离显示
 * @param y           据点世界坐标 Y
 * @param z           据点世界坐标 Z
 */
public record BreakthroughPointDto(int pointId, String name, int owner, int pressure, int progress, boolean locked,
                                    int sectorIndex, double x, double y, double z) {

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(pointId);
        buf.writeUtf(name);
        buf.writeVarInt(owner);
        buf.writeVarInt(pressure);
        buf.writeVarInt(progress);
        buf.writeBoolean(locked);
        buf.writeVarInt(sectorIndex);
        buf.writeDouble(x);
        buf.writeDouble(y);
        buf.writeDouble(z);
    }

    public static BreakthroughPointDto decode(FriendlyByteBuf buf) {
        return new BreakthroughPointDto(
                buf.readVarInt(),
                buf.readUtf(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readBoolean(),
                buf.readVarInt(),
                buf.readDouble(),
                buf.readDouble(),
                buf.readDouble());
    }
}
