package org.shee33.act0.battlefield.client;

/**
 * 占点 HUD 动效规格文档 §1.2 的配色语义 —— 严格执行的十六进制值,专属于据点占领特写/小图标
 * 这套动效,不与 {@code BattlefieldHudOverlay} 其它地方(票数条、击杀反馈等)已有的阵营色数值
 * 混用。语义上是"友方/敌方相对色",复用 {@link #relative(int, int)} 的判断逻辑,等价于文件里
 * 原有 {@code factionColor(faction, mine)} 这类"相对我方阵营染色"辅助方法的模式。
 */
final class DocPalette {

    private DocPalette() {
    }

    /** 黄 #ffd76a —— 一切进行中:进度环、"正在占领/正在中立化"状态文字。 */
    static final int PROGRESS = 0xFFFFD76A;
    /** 蓝 #4fa8ff —— 已占领/我方归属。 */
    static final int FRIEND = 0xFF4FA8FF;
    /** 红 #ff6a5e —— 敌方;争夺/反击干扰期间环与文字短暂转红。 */
    static final int ENEMY = 0xFFFF6A5E;
    /** 灰白 #c9ced4 —— 中立据点、两轮之间的过渡态。 */
    static final int NEUTRAL = 0xFFC9CED4;
    /** 正文 #e8edf2 —— 百分比数字 / 特写字母与标题(非敌方轮次时字母走这个色而非纯白,更贴近文档基调)。 */
    static final int TEXT = 0xFFE8EDF2;
    /** 舞台底板 rgba(10,14,18,0.72),用作特写六边形的暗色填充。 */
    static final int PANEL_BG = 0xB80A0E12;
    /** 环背板 rgba(255,255,255,0.14)。 */
    static final int RING_TRACK = 0x24FFFFFF;

    /**
     * 给定一个阵营代码(0=中立,1/2=阵营)相对 {@code myFaction} 的语义色。这是"相对色"而非绝对
     * 阵营色:同一个 code 在不同 viewer 眼里可能是蓝也可能是红,与文件里已有的
     * {@code BattlefieldHudOverlay#factionColor} 判断逻辑一致,只是数值换成本文档指定的调色板。
     */
    static int relative(int code, int myFaction) {
        if (code == 0) {
            return NEUTRAL;
        }
        if (myFaction == 0) {
            return code == 1 ? FRIEND : ENEMY;
        }
        return code == myFaction ? FRIEND : ENEMY;
    }
}
