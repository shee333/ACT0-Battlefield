package org.shee33.act0.battlefield.client;

import org.shee33.act0.battlefield.network.BattleHudDto;

import javax.annotation.Nullable;

/**
 * 客户端 BF 风格大战场 HUD 状态缓存，供 {@link BattlefieldHudOverlay} 每帧绘制。
 */
public final class ClientBattleHud {

    private static volatile boolean shown = false;
    @Nullable
    private static volatile BattleHudDto hud;

    private ClientBattleHud() {
    }

    public static void accept(boolean show, BattleHudDto dto) {
        shown = show;
        hud = show ? dto : null;
    }

    public static boolean isShown() {
        return shown && hud != null;
    }

    @Nullable
    public static BattleHudDto hud() {
        return hud;
    }
}
