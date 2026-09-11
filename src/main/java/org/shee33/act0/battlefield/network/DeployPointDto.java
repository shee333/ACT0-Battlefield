package org.shee33.act0.battlefield.network;

import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * 部署地图上的一个据点点位。
 *
 * <p>{@code boundary} 是管理员圈画的不规则占领区域顶点（世界 XZ，按环绕顺序）；空列表表示
 * 未圈画、沿用方形半径判定。3D 部署界面据此投影绘制区域边框与填充。
 */
public record DeployPointDto(String id, String name, int owner, boolean deployable,
                             double x, double y, double z, List<double[]> boundary) {

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(id);
        buf.writeUtf(name);
        buf.writeVarInt(owner);
        buf.writeBoolean(deployable);
        buf.writeDouble(x);
        buf.writeDouble(y);
        buf.writeDouble(z);
        buf.writeVarInt(boundary.size());
        for (double[] vertex : boundary) {
            buf.writeDouble(vertex[0]);
            buf.writeDouble(vertex[1]);
        }
    }

    public static DeployPointDto decode(FriendlyByteBuf buf) {
        String id = buf.readUtf();
        String name = buf.readUtf();
        int owner = buf.readVarInt();
        boolean deployable = buf.readBoolean();
        double x = buf.readDouble();
        double y = buf.readDouble();
        double z = buf.readDouble();
        int count = buf.readVarInt();
        List<double[]> boundary = new ArrayList<>(Math.max(0, count));
        for (int i = 0; i < count; i++) {
            boundary.add(new double[]{buf.readDouble(), buf.readDouble()});
        }
        return new DeployPointDto(id, name, owner, deployable, x, y, z, List.copyOf(boundary));
    }
}
