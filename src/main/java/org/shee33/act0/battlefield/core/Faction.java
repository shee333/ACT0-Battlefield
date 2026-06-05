package org.shee33.act0.battlefield.core;

/**
 * 征服模式的两个参战阵营。MC-free 纯枚举。
 *
 * <p>据点的归属用 {@code Faction}（{@code null} 表示中立），票数池按阵营分别记录。
 */
public enum Faction {

    /** 甲方（进攻/红队）。 */
    ALPHA("甲方", "§c"),
    /** 乙方（防守/蓝队）。 */
    BRAVO("乙方", "§9");

    private final String displayName;
    private final String colorCode;

    Faction(String displayName, String colorCode) {
        this.displayName = displayName;
        this.colorCode = colorCode;
    }

    public String displayName() {
        return displayName;
    }

    /** 该阵营的 §-颜色码，供 HUD/聊天着色。 */
    public String colorCode() {
        return colorCode;
    }

    /** 着色后的显示名，如 {@code §c甲方}。 */
    public String coloredName() {
        return colorCode + displayName;
    }

    /** 对方阵营。 */
    public Faction opponent() {
        return this == ALPHA ? BRAVO : ALPHA;
    }
}
