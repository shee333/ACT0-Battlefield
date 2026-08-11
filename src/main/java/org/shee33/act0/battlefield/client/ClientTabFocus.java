package org.shee33.act0.battlefield.client;

import org.shee33.act0.battlefield.core.HoldFade;

/**
 * TAB 战绩面板的"聚焦"状态：按住 TAB 时让顶部据点/占领 HUD 让位，松开后复原。
 *
 * <p>存在的理由是层叠冲突——TAB 面板与顶部据点图标、占领进度、据点特写横幅在屏幕上占同一片
 * 区域。单纯把 TAB 画在最上层只会变成两层信息糊在一起；让下层在 TAB 呼出时退场才是可读的做法。
 *
 * <p>{@link #dim()} 返回 0（HUD 完全显示）到 1（HUD 完全退场）。
 */
public final class ClientTabFocus {

    private static boolean held;
    private static float fromValue;
    private static long changedAtMs;

    private ClientTabFocus() {
    }

    /** 每帧在 GUI 渲染最前端调用一次，保证同一帧内模糊与 HUD 淡出读到一致的状态。 */
    public static void update(boolean nowHeld) {
        if (nowHeld == held) {
            return;
        }
        fromValue = dim();
        held = nowHeld;
        changedAtMs = System.currentTimeMillis();
    }

    /** 0 = 顶部 HUD 完全显示，1 = 完全退场。 */
    public static float dim() {
        if (changedAtMs == 0L) {
            return 0f;
        }
        return HoldFade.eased(fromValue, held ? 1f : 0f,
                System.currentTimeMillis() - changedAtMs, HoldFade.DURATION_MS);
    }

    public static void reset() {
        held = false;
        fromValue = 0f;
        changedAtMs = 0L;
    }
}
