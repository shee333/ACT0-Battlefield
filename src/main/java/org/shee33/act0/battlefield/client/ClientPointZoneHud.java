package org.shee33.act0.battlefield.client;

/**
 * 据点地面边界高亮的开关（客户端），由 {@code SyncVanillaHudPacket} 下发。
 *
 * <p>与 {@code ClientVanillaHud} 同源：服务端权威、管理员用 {@code /aew1 hud pointzone} 切换。
 * 默认开——边界高亮是这一版的默认观感，关掉是给管理员做 A/B 对比用的。
 */
public final class ClientPointZoneHud {

    private static volatile boolean enabled = true;

    private ClientPointZoneHud() {
    }

    public static void setEnabled(boolean value) {
        ClientPointZoneHud.enabled = value;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /** 断线兜底：回到"默认开"，避免下一局沿用一个已经不存在的开关状态。 */
    public static void clear() {
        enabled = true;
    }
}
