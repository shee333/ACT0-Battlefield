package org.shee33.act0.battlefield.client;

/**
 * 底部武器更换上拉面板(《部署界面动效规格文档》§3.6)的纯数学部分 —— 与 {@link DeployMapMath}
 * 同样的拆分动机:错峰延迟计算、面板锚定边界保护、"选中项是否等于当前项"判断都不依赖
 * {@link net.minecraft.client.gui.GuiGraphics}/{@link Tween} 之外的任何渲染状态,方便脱离
 * Minecraft 客户端渠道单独单测。
 */
final class DeployWeaponMath {

    private DeployWeaponMath() {
    }

    /** §3.6"点击槽位打开":选项自下而上错峰升起,每项间隔 20ms。 */
    static long openRowDelayMs(int index) {
        return Math.max(0, index) * 20L;
    }

    /** §3.6"关闭":选项逐项下沉淡出,错峰 10ms。 */
    static long closeRowDelayMs(int index) {
        return Math.max(0, index) * 10L;
    }

    /**
     * §3.6"面板锚定在该槽位正上方(左缘对齐,右边界保护 min(x, 舞台宽−200))":
     * 面板左缘默认与槽位左缘对齐,但若这样会导致面板右边界超出舞台,则整体左移,
     * 且不允许移出舞台左侧(0 下限保护,应对舞台本身比面板还窄的极端情况)。
     */
    static int clampPanelX(int slotX, int panelWidth, int stageWidth) {
        int maxX = Math.max(0, stageWidth - panelWidth);
        return Math.max(0, Math.min(slotX, maxX));
    }

    /** §3.6"选中当前项则只闪不换":判断本次选中的物品是否与当前已装备项相同。 */
    static boolean isSameItem(String pickedItem, String currentItem) {
        return pickedItem != null && pickedItem.equals(currentItem);
    }

    /**
     * 底部槽位栏整组水平居中布局(§3.6"整组水平居中"/§2 布局表"底部武器栏"):
     * 给定每个槽位的宽度与槽位间距,返回各槽位左缘相对屏幕的 X 坐标,使整组围绕
     * {@code centerX} 居中对齐。
     */
    static int[] layoutSlotX(int[] widths, int gap, int centerX) {
        int[] xs = new int[widths.length];
        if (widths.length == 0) {
            return xs;
        }
        int total = 0;
        for (int w : widths) {
            total += w;
        }
        total += gap * (widths.length - 1);
        int cursor = centerX - total / 2;
        for (int i = 0; i < widths.length; i++) {
            xs[i] = cursor;
            cursor += widths[i] + gap;
        }
        return xs;
    }
}
