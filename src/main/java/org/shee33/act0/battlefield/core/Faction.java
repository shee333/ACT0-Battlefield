package org.shee33.act0.battlefield.core;

/**
 * 征服模式的两个参战阵营。MC-free 纯枚举。
 *
 * <p>据点的归属用 {@code Faction}（{@code null} 表示中立），票数池按阵营分别记录。
 *
 * <p>刻意<b>不带</b>显示名称：名称随地图配置，见 {@link FactionNames}。枚举只承载全局恒定的
 * "哪一方"与"什么颜色"，取名称必须先拿到地图上下文。
 */
public enum Faction {

    /** 一号阵营（征服模式随机分配；突破模式为进攻方）。 */
    ALPHA("§9"),
    /** 二号阵营（征服模式随机分配；突破模式为防守方）。 */
    BRAVO("§c");

    private final String colorCode;

    Faction(String colorCode) {
        this.colorCode = colorCode;
    }

    /** 该阵营的 §-颜色码，供 HUD/聊天着色。全局恒定，不随地图变化。 */
    public String colorCode() {
        return colorCode;
    }

    /** 对方阵营。 */
    public Faction opponent() {
        return this == ALPHA ? BRAVO : ALPHA;
    }
}
