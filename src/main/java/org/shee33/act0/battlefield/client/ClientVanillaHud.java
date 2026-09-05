package org.shee33.act0.battlefield.client;

/**
 * 客户端 HUD 模式缓存：本图对局是否用原版快捷栏替代自绘武器栏。
 *
 * <p>由 {@code SyncVanillaHudPacket} 驱动（服务端权威，管理员 {@code /aew1 hud vanilla}
 * 切换后广播）。默认 {@code false} 走自绘武器栏；为 {@code true} 时 HUD 改放行并平移
 * 原版快捷栏到右下角，解决部分模组物品在自绘栏里的紫黑显示问题。
 */
public final class ClientVanillaHud {

    private static volatile boolean vanillaHud;

    private ClientVanillaHud() {
    }

    public static void setVanillaHud(boolean vanillaHud) {
        ClientVanillaHud.vanillaHud = vanillaHud;
    }

    /** 当前地图的对局 HUD 是否处于原版快捷栏模式。 */
    public static boolean isVanillaHud() {
        return vanillaHud;
    }
}
