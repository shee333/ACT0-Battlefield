package org.shee33.act0.battlefield.client;

/**
 * 小地图事件的客户端入口。
 *
 * <p>网络包<b>绝不能</b>直接引用 {@link MinimapAnimator} —— 后者是 {@code client} 包内的
 * package-private 类，而网络包在 {@code network} 包。此外网络包类会在专用服务端被加载，
 * 一旦编译期引用到任何客户端类就会 {@code NoClassDefFoundError} 拒绝加载整个模组
 * （本仓库曾因此崩过一次服务端）。因此包侧只经 {@code DistExecutor} 调用本类的公开静态方法。
 */
public final class ClientMinimapEvents {

    private ClientMinimapEvents() {
    }

    /** 收到受击方向（世界方位角，弧度）。 */
    public static void onDamageFrom(float bearingRad) {
        MinimapAnimator.onDamageFrom(bearingRad, Tween.now());
    }

    /** 收到队友的战术标记。 */
    public static void onPing(double x, double z) {
        MinimapAnimator.addPing(x, z, Tween.now());
    }

    /** 部署落地时播放小地图开场雷达扫描。 */
    public static void playIntro() {
        MinimapAnimator.playIntro(Tween.now());
    }

    /** 退出对局/断线时清空插值与事件状态。 */
    public static void clear() {
        MinimapAnimator.clear();
    }
}
