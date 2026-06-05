package org.shee33.act0.battlefield.client;

import java.util.List;

/**
 * 客户端 HUD 状态持有者：缓存服务端下发的大战场 HUD 标题与行文本，供 {@link BattlefieldHudOverlay} 自绘。
 *
 * <p>仅在客户端调用。自绘 HUD <b>不显示任何数字序号</b>，信息都包含在行文本内。
 */
public final class ClientHud {

    private static volatile boolean show = false;
    private static volatile String title = "";
    private static volatile List<String> lines = List.of();

    private ClientHud() {
    }

    public static void accept(boolean show, String title, List<String> lines) {
        ClientHud.show = show;
        ClientHud.title = title != null ? title : "";
        ClientHud.lines = lines != null ? lines : List.of();
    }

    public static boolean isShown() {
        return show;
    }

    public static String title() {
        return title;
    }

    public static List<String> lines() {
        return lines;
    }
}
