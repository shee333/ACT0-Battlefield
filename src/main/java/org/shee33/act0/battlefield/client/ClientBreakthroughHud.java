package org.shee33.act0.battlefield.client;

import org.shee33.act0.battlefield.network.BreakthroughHudDto;

import javax.annotation.Nullable;

/**
 * 客户端突破模式 HUD 状态缓存，供突破模式 overlay 每帧绘制。
 * {@code dto.show()=false} 时 HUD 隐藏。
 */
public final class ClientBreakthroughHud {

    @Nullable
    private static volatile BreakthroughHudDto hud;

    private ClientBreakthroughHud() {
    }

    public static void accept(BreakthroughHudDto dto) {
        hud = dto != null && dto.show() ? dto : null;
    }

    public static boolean isShown() {
        return hud != null;
    }

    @Nullable
    public static BreakthroughHudDto hud() {
        return hud;
    }

    public static void clear() {
        hud = null;
    }
}
