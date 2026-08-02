package org.shee33.act0.battlefield.client;

import net.minecraft.util.Mth;

/**
 * 部署确认"过场相机"的客户端插值状态。
 *
 * <p>服务端（{@code RedeployService.beginDeployPan()}）只在过场开始时通过 {@code DeployPanPacket}
 * 下发一次起止位姿 + 总时长，不逐 tick 重发。此处按渲染帧的真实经过时间（{@link Tween#now()}，不绑
 * server tick）用 {@link Tween.Ease#OUT_CUBIC} 曲线（与服务端 {@code tickDeployPan()} 的
 * {@code easeOutCubic} 同一条曲线，保证手感一致）每帧重新算出当前应显示的相机位姿，交给
 * {@link DeployPanCameraHandler} 通过 {@code ViewportEvent.ComputeCameraAngles} 覆盖渲染相机。
 *
 * <p>这样插值频率就跟渲染帧率（通常远高于 20Hz）一致，不再受服务端 tick 频率钳制——这是本次"进一步提升
 * 流畅度"改造的核心：视觉呈现完全交给客户端自己算，服务端只需要告诉客户端"这次过场的起点、终点、开始
 * 时间、总时长"这一组数据。
 */
public final class ClientDeployPan {

    private static boolean active;
    private static long startAtMs;
    private static long durationMs = 1L;

    private static double startX;
    private static double startY;
    private static double startZ;
    private static float startYaw;
    private static float startPitch;
    private static double endX;
    private static double endY;
    private static double endZ;
    private static float endYaw;
    private static float endPitch;

    private ClientDeployPan() {
    }

    /** 由 {@code DeployPanPacket} 在过场开始时调用一次；之后每帧靠 {@link #currentPose()} 自行插值。 */
    public static void begin(double startX, double startY, double startZ, float startYaw, float startPitch,
                              double endX, double endY, double endZ, float endYaw, float endPitch,
                              int durationTicks) {
        ClientDeployPan.startX = startX;
        ClientDeployPan.startY = startY;
        ClientDeployPan.startZ = startZ;
        ClientDeployPan.startYaw = startYaw;
        ClientDeployPan.startPitch = startPitch;
        ClientDeployPan.endX = endX;
        ClientDeployPan.endY = endY;
        ClientDeployPan.endZ = endZ;
        ClientDeployPan.endYaw = endYaw;
        ClientDeployPan.endPitch = endPitch;
        ClientDeployPan.durationMs = Math.max(1L, durationTicks * 50L);
        ClientDeployPan.startAtMs = Tween.now();
        active = true;
    }

    /** 过场是否仍在进行中；超过总时长后自动失效，渲染恢复跟随真实实体位置。 */
    public static boolean isActive() {
        if (!active) {
            return false;
        }
        if (Tween.now() - startAtMs >= durationMs) {
            active = false;
            return false;
        }
        return true;
    }

    /** 当前应显示的过场相机位姿（实体坐标口径，未加眼高）；仅在 {@link #isActive()} 为 true 时调用有意义。 */
    static Pose currentPose() {
        float t = Tween.Ease.OUT_CUBIC.apply(Mth.clamp((Tween.now() - startAtMs) / (float) durationMs, 0f, 1f));
        double x = Mth.lerp(t, startX, endX);
        double y = Mth.lerp(t, startY, endY);
        double z = Mth.lerp(t, startZ, endZ);
        float yaw = startYaw + Mth.wrapDegrees(endYaw - startYaw) * t;
        float pitch = Mth.lerp(t, startPitch, endPitch);
        return new Pose(x, y, z, yaw, pitch);
    }

    /** 一帧的插值相机位姿快照（实体坐标口径；第一人称眼位置 = y + 玩家眼高，由调用方叠加）。 */
    record Pose(double x, double y, double z, float yaw, float pitch) {
    }
}
