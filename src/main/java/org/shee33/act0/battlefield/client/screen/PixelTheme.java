package org.shee33.act0.battlefield.client.screen;

import net.minecraft.client.gui.GuiGraphics;

/**
 * 大战场像素风主题：军事配色 + 程序化九宫格面板，<b>不依赖任何贴图</b>，避免缺图渲染失败。
 *
 * <p>沿用街机界面同款斜角面板风格，但偏冷峻军绿/钢灰；两阵营各有专属强调色（红队 / 蓝队）。
 */
public final class PixelTheme {

    /** 面板底色（半透明深墨）。 */
    public static final int PANEL_BG = 0xF00C0E12;
    /** 外边框（暗）。 */
    public static final int BORDER_DARK = 0xFF1A1E24;
    /** 内斜角高光（钢灰）。 */
    public static final int BEVEL_LIGHT = 0xFF4A525C;
    /** 内斜角阴影。 */
    public static final int BEVEL_SHADOW = 0xFF282C32;
    /** 主文本。 */
    public static final int TEXT = 0xFFE6E6E0;
    /** 次文本。 */
    public static final int TEXT_DIM = 0xFF8A8F88;

    /** 红队强调色。 */
    public static final int ALPHA_COLOR = 0xFFC0504D;
    /** 蓝队强调色。 */
    public static final int BRAVO_COLOR = 0xFF4A7AB5;

    private PixelTheme() {
    }

    /** 绘制一个像素风面板：外暗边 + 双层斜角 + 内填充。 */
    public static void panel(GuiGraphics gg, int x, int y, int w, int h) {
        int x2 = x + w;
        int y2 = y + h;
        gg.fill(x, y, x2, y2, BORDER_DARK);
        gg.fill(x + 1, y + 1, x2 - 1, y2 - 1, PANEL_BG);
        gg.fill(x + 1, y + 1, x2 - 1, y + 2, BEVEL_LIGHT);
        gg.fill(x + 1, y + 1, x + 2, y2 - 1, BEVEL_LIGHT);
        gg.fill(x + 1, y2 - 2, x2 - 1, y2 - 1, BEVEL_SHADOW);
        gg.fill(x2 - 2, y + 1, x2 - 1, y2 - 1, BEVEL_SHADOW);
    }

    /**
     * 绘制一个着色的阵营面板（可高亮：被选中时加亮边）。
     *
     * @param accent 阵营强调色
     * @param selected 是否为玩家当前所属阵营
     */
    public static void factionPanel(GuiGraphics gg, int x, int y, int w, int h, int accent, boolean selected, boolean hovered) {
        int x2 = x + w;
        int y2 = y + h;
        gg.fill(x, y, x2, y2, BORDER_DARK);
        // 顶部色条
        gg.fill(x + 1, y + 1, x2 - 1, y + 5, accent);
        // 主体（带一点阵营色调）
        int body = selected ? blend(PANEL_BG, accent, 0.28f)
                : (hovered ? blend(PANEL_BG, accent, 0.14f) : PANEL_BG);
        gg.fill(x + 1, y + 5, x2 - 1, y2 - 1, body);
        if (selected) {
            // 选中：四周亮边
            gg.fill(x, y, x2, y + 1, accent);
            gg.fill(x, y2 - 1, x2, y2, accent);
            gg.fill(x, y, x + 1, y2, accent);
            gg.fill(x2 - 1, y, x2, y2, accent);
        }
    }

    /** 程序化按钮底纹。 */
    public static void button(GuiGraphics gg, int x, int y, int w, int h, boolean hovered, boolean enabled) {
        int x2 = x + w;
        int y2 = y + h;
        gg.fill(x, y, x2, y2, BORDER_DARK);
        int face = !enabled ? 0xFF2A2E30 : (hovered ? BEVEL_LIGHT : 0xFF3A4048);
        gg.fill(x + 1, y + 1, x2 - 1, y2 - 1, face);
        gg.fill(x + 1, y + 1, x2 - 1, y + 2, blend(face, 0xFFFFFFFF, 0.18f));
        gg.fill(x + 1, y2 - 2, x2 - 1, y2 - 1, BEVEL_SHADOW);
    }

    /** 线性混合两个 ARGB 颜色（保留 a=ff）。 */
    public static int blend(int base, int over, float t) {
        int br = (base >> 16) & 0xFF, bg = (base >> 8) & 0xFF, bb = base & 0xFF;
        int or = (over >> 16) & 0xFF, og = (over >> 8) & 0xFF, ob = over & 0xFF;
        int r = (int) (br + (or - br) * t);
        int g = (int) (bg + (og - bg) * t);
        int b = (int) (bb + (ob - bb) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
