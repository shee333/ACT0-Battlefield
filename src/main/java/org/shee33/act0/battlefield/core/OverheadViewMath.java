package org.shee33.act0.battlefield.core;

/**
 * 正俯视（pitch = +90）相机的朝向与屏幕轴向之间的换算。
 *
 * <p>存在的理由：部署界面同时呈现<b>两个</b>地图——玩家被传送到战场上方俯视真实地形，同时叠加
 * 一张 2D 缩略图面板。两者的方位约定必须一致，否则缩略图上的据点和玩家眼前看到的据点对不上。
 * 这个对应关系此前只存在于两处彼此不知情的代码里（面板按"北上东右"投影，俯瞰相机却用 yaw=0），
 * 而 yaw=0 在 Minecraft 里是<b>朝南</b>，于是两者恰好差 180°。
 *
 * <p>Minecraft 的 yaw 约定（水平朝向单位向量）：
 * <pre>
 *   yaw=0   → (0, 0, +1)  南
 *   yaw=90  → (-1, 0, 0)  西
 *   yaw=180 → (0, 0, -1)  北
 *   yaw=270 → (+1, 0, 0)  东
 * </pre>
 */
public final class OverheadViewMath {

    /**
     * 与"北上东右"缩略图约定一致所需的俯瞰相机 yaw。
     *
     * <p>朝北（yaw=180）时正俯视的屏幕上方是北、右方是东，与 {@code DeployMapMath#project} 的
     * 投影约定（世界 X 增大向右、世界 Z 增大向下）完全吻合。
     */
    public static final float NORTH_UP_YAW = 180.0F;

    /** 正俯视的 pitch：+90 为正对下方。 */
    public static final float STRAIGHT_DOWN_PITCH = 90.0F;

    private OverheadViewMath() {
    }

    /**
     * 正俯视时，屏幕<b>上方</b>对应的世界水平方向单位向量 {@code [x, z]}。
     *
     * <p>俯视到极限时视线沿 -Y，屏幕上方退化为相机的水平朝向本身。
     */
    public static double[] screenUpDirection(float yawDeg) {
        double yaw = Math.toRadians(yawDeg);
        return new double[]{-Math.sin(yaw), Math.cos(yaw)};
    }

    /**
     * 正俯视时，屏幕<b>右方</b>对应的世界水平方向单位向量 {@code [x, z]}。
     *
     * <p>右向量是朝向绕 -Y 轴旋转 90°：面朝南时右手指西，面朝北时右手指东。
     */
    public static double[] screenRightDirection(float yawDeg) {
        double yaw = Math.toRadians(yawDeg);
        return new double[]{-Math.cos(yaw), -Math.sin(yaw)};
    }

    /**
     * 该 yaw 下的正俯视画面是否满足"北上东右"——即是否与 2D 缩略图面板的投影约定一致。
     */
    public static boolean matchesNorthUpEastRight(float yawDeg) {
        double[] up = screenUpDirection(yawDeg);
        double[] right = screenRightDirection(yawDeg);
        return up[1] < -0.999D && Math.abs(up[0]) < 1.0e-6D
                && right[0] > 0.999D && Math.abs(right[1]) < 1.0e-6D;
    }
}
