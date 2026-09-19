package org.shee33.act0.battlefield.network;

import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * HUD 用的单个据点快照。
 *
 * @param name         据点名（A/B/C 或自定义）
 * @param owner        归属：0=中立，1=ALPHA，2=BRAVO
 * @param pressure     当前进度倾向：0=无/中立，1=ALPHA 方向，2=BRAVO 方向
 * @param progress     占领/中和进度百分比（0~100）
 * @param x            世界坐标 X（据点中心）
 * @param y            世界坐标 Y（据点中心）
 * @param z            世界坐标 Z（据点中心）
 * @param markerScale  世界浮标缩放倍率
 * @param markerDistance 世界浮标最大渲染距离
 * @param pointId      据点编号
 * @param boundary     管理员圈画的不规则占领区顶点（世界坐标 {@code {x,y,z}}，按环绕顺序）；
 *                     空表示未圈画、只有方形半径。第一人称下的地面边界高亮按它绘制，
 *                     顶点自带高度，因此边界能贴合地形起伏而不是浮在一个水平面上。
 */
public record ControlPointHudDto(String name, int owner, int pressure, int progress, double x, double y, double z,
                                 double markerScale, int markerDistance, int pointId, List<double[]> boundary) {

    /** 解码时对顶点数的硬上限，防止坏包撑爆内存（与 DeployStatusDto 同源纪律）。 */
    private static final int MAX_BOUNDARY_VERTICES = 256;

    public ControlPointHudDto {
        boundary = boundary == null ? List.of() : List.copyOf(boundary);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(name);
        buf.writeVarInt(owner);
        buf.writeVarInt(pressure);
        buf.writeVarInt(progress);
        buf.writeDouble(x);
        buf.writeDouble(y);
        buf.writeDouble(z);
        buf.writeDouble(markerScale);
        buf.writeVarInt(markerDistance);
        buf.writeVarInt(pointId);
        buf.writeVarInt(boundary.size());
        for (double[] vertex : boundary) {
            buf.writeDouble(vertex[0]);
            buf.writeDouble(vertex[1]);
            buf.writeDouble(vertex[2]);
        }
    }

    public static ControlPointHudDto decode(FriendlyByteBuf buf) {
        String name = buf.readUtf();
        int owner = buf.readVarInt();
        int pressure = buf.readVarInt();
        int progress = buf.readVarInt();
        double x = buf.readDouble();
        double y = buf.readDouble();
        double z = buf.readDouble();
        double markerScale = buf.readDouble();
        int markerDistance = buf.readVarInt();
        int pointId = buf.readVarInt();
        int count = Math.max(0, Math.min(buf.readVarInt(), MAX_BOUNDARY_VERTICES));
        List<double[]> boundary = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            boundary.add(new double[]{buf.readDouble(), buf.readDouble(), buf.readDouble()});
        }
        return new ControlPointHudDto(name, owner, pressure, progress, x, y, z,
                markerScale, markerDistance, pointId, boundary);
    }
}