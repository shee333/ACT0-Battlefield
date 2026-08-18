package org.shee33.act0.battlefield.client.screen;

import java.util.ArrayList;
import java.util.List;

/**
 * 配装界面（三级标签：地图 / 兵种 / 槽位分组）的纯算术：标签滚动窗口、槽位分组、选项分页、命中矩形、
 * 淡入曲线与透明度合成。
 *
 * <p><b>为什么单独成类、且刻意不引用任何 Minecraft 类型</b>：客户端 GUI 在无显示环境里没有任何
 * 视觉验证手段，而这一块恰恰是"算错了也不会崩、只会静默错位"的高危区——越界的标签下标、
 * 翻页后停在空白页、命中矩形与绘制矩形差一个像素。把它们全部挤进一个裸 JVM 就能跑的类，
 * 是这些逻辑唯一能被真正验证的途径。
 */
final class BattlefieldLoadoutLayout {

    /** 左栏（槽位列表）最小宽度：容得下最长槽位名加一个短装备名。 */
    static final int MIN_LEFT_W = 88;

    /** 右栏（可选项列表）最小宽度。 */
    static final int MIN_RIGHT_W = 96;

    private BattlefieldLoadoutLayout() {
    }

    // =====================================================================
    // 下标
    // =====================================================================

    /** 夹紧到 {@code [0, size)}；{@code size <= 0} 时返回 {@code -1} 表示"没有合法下标"。 */
    static int clampIndex(int desired, int size) {
        if (size <= 0) {
            return -1;
        }
        return Math.max(0, Math.min(desired, size - 1));
    }

    /** 循环取模，供左右方向键在兵种标签之间绕圈；负数也能正确回绕到末尾。 */
    static int wrapIndex(int desired, int size) {
        if (size <= 0) {
            return -1;
        }
        return ((desired % size) + size) % size;
    }

    /**
     * 在列表里定位一个字符串。
     *
     * <p>找不到时回落到 {@code 0} 而不是 {@code -1}：服务端下发的"当前地图/当前兵种"理论上一定在
     * 列表里，但一旦目录在两个包之间被改过就会对不上，此时高亮第一项远好于整条标签栏一个都不亮。
     */
    static int indexOfOrFirst(List<String> items, String value) {
        if (items == null || items.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).equals(value)) {
                return i;
            }
        }
        return 0;
    }

    // =====================================================================
    // 槽位分组（三级标签的"组"）
    // =====================================================================

    /** 快捷栏 0/1/2 是武器（主/副/近战），3/4 是道具——与 {@code LoadoutSlot} 的索引约定一致。 */
    static boolean isWeaponSlot(int slotIndex) {
        return slotIndex >= 0 && slotIndex <= 2;
    }

    /**
     * 挑出属于某一组的槽位在<b>原列表中的下标</b>（而不是槽位索引本身）。
     *
     * <p>返回下标是为了让调用方能直接 {@code slots.get(pos)} 取回完整 DTO：服务端下发的槽位顺序
     * 未必连续、也未必按索引排序（地图没配某个槽位时它整个不出现），按下标走就不必再反查一次。
     */
    static List<Integer> groupMembers(List<Integer> slotIndices, boolean weapons) {
        List<Integer> out = new ArrayList<>();
        if (slotIndices == null) {
            return out;
        }
        for (int i = 0; i < slotIndices.size(); i++) {
            if (isWeaponSlot(slotIndices.get(i)) == weapons) {
                out.add(i);
            }
        }
        return out;
    }

    /** 按快捷栏索引在槽位列表里定位；不存在返回 {@code -1}。 */
    static int positionOfSlot(List<Integer> slotIndices, int hotbarIndex) {
        if (slotIndices == null) {
            return -1;
        }
        for (int i = 0; i < slotIndices.size(); i++) {
            if (slotIndices.get(i) == hotbarIndex) {
                return i;
            }
        }
        return -1;
    }

    // =====================================================================
    // 选项分页
    // =====================================================================

    /** 一页能放几行；至少 1，否则 {@link #pageCount} 会除零。 */
    static int rowsPerPage(int areaH, int rowH) {
        if (rowH <= 0) {
            return 1;
        }
        return Math.max(1, areaH / rowH);
    }

    /** 总页数；空列表也算 1 页（那一页画"没有可选装备"，而不是让分页器消失）。 */
    static int pageCount(int total, int perPage) {
        int per = Math.max(1, perPage);
        if (total <= 0) {
            return 1;
        }
        return (total + per - 1) / per;
    }

    static int clampPage(int page, int total, int perPage) {
        return Math.max(0, Math.min(page, pageCount(total, perPage) - 1));
    }

    static int pageStart(int page, int total, int perPage) {
        int per = Math.max(1, perPage);
        return clampPage(page, total, per) * per;
    }

    static int pageEnd(int page, int total, int perPage) {
        int per = Math.max(1, perPage);
        return Math.min(Math.max(0, total), pageStart(page, total, per) + per);
    }

    /** 某一项落在第几页，供"切换槽位后自动翻到当前装备所在页"使用。 */
    static int pageOf(int index, int perPage) {
        if (index < 0) {
            return 0;
        }
        return index / Math.max(1, perPage);
    }

    /**
     * 滚轮翻页。{@code delta > 0} 是向上滚 = 上一页，与本仓库其它列表的滚动方向保持一致。
     * 结果已夹紧，因此到边界时会原样返回当前页，调用方据此判断"滚不动"。
     */
    static int stepPage(int page, double delta, int total, int perPage) {
        if (delta == 0) {
            return clampPage(page, total, perPage);
        }
        return clampPage(page + (delta > 0 ? -1 : 1), total, perPage);
    }

    // =====================================================================
    // 标签滚动窗口（地图数量不受代码控制，必须能横向翻页）
    // =====================================================================

    /**
     * 从第 {@code first} 个标签起，{@code avail} 像素内能完整放下几个。
     *
     * <p>下限为 1：单个标签本身就比整条可用宽度还宽时（超长中文地图名），宁可把它画出去被裁掉，
     * 也不能返回 0 —— 那会让整条标签栏空白，玩家彻底没有可点的东西。
     */
    static int visibleTabCount(int[] widths, int gap, int avail, int first) {
        if (widths == null || widths.length == 0) {
            return 0;
        }
        int f = clampIndex(first, widths.length);
        int used = 0;
        int n = 0;
        for (int i = f; i < widths.length; i++) {
            int need = n == 0 ? widths[i] : used + gap + widths[i];
            if (need > avail && n > 0) {
                break;
            }
            used = need;
            n++;
        }
        return Math.max(1, n);
    }

    /** 横向滚动上限：再往后滚就会在右侧留出永远填不满的空白。 */
    static int maxTabScroll(int[] widths, int gap, int avail) {
        if (widths == null || widths.length == 0) {
            return 0;
        }
        for (int f = 0; f < widths.length; f++) {
            if (f + visibleTabCount(widths, gap, avail, f) >= widths.length) {
                return f;
            }
        }
        return widths.length - 1;
    }

    static int clampTabScroll(int first, int[] widths, int gap, int avail) {
        return Math.max(0, Math.min(first, maxTabScroll(widths, gap, avail)));
    }

    /**
     * 把 {@code index} 滚进可视窗口。服务端切图后当前地图可能落在窗口之外，
     * 不做这一步玩家会看到"选中的标签一个都没亮"。
     */
    static int ensureTabVisible(int first, int index, int[] widths, int gap, int avail) {
        int f = clampTabScroll(first, widths, gap, avail);
        if (widths == null || widths.length == 0 || index < 0) {
            return f;
        }
        if (index < f) {
            return clampTabScroll(index, widths, gap, avail);
        }
        while (index >= f + visibleTabCount(widths, gap, avail, f) && f < widths.length - 1) {
            f++;
        }
        return clampTabScroll(f, widths, gap, avail);
    }

    /** 第 {@code i} 个标签在屏幕上的左边界（窗口首项为 {@code first}）。 */
    static int tabX(int[] widths, int gap, int startX, int first, int i) {
        int x = startX;
        if (widths == null) {
            return x;
        }
        for (int k = Math.max(0, first); k < i && k < widths.length; k++) {
            x += widths[k] + gap;
        }
        return x;
    }

    // =====================================================================
    // 两栏分割与命中
    // =====================================================================

    /**
     * 左栏宽度：内容区的 42%，但两栏各有下限。
     *
     * <p>极窄的 guiScale 下宁可对半分也不让任意一栏塌成 0 —— 塌成 0 的那一栏会把它内部所有
     * {@code fit} 调用的可用宽度算成负数，文字全变成两个点。
     */
    static int splitLeftWidth(int totalW, int gap) {
        int usable = Math.max(0, totalW - gap);
        if (usable < MIN_LEFT_W + MIN_RIGHT_W) {
            return usable / 2;
        }
        int left = Math.max(MIN_LEFT_W, usable * 42 / 100);
        return Math.min(left, usable - MIN_RIGHT_W);
    }

    /**
     * 半开区间命中：右/下边界归下一个矩形。
     *
     * <p>闭区间会让相邻两行在交界那一行像素上同时命中，而命中列表是"先到先得"的，
     * 表现为"点这一行有时选中上一行"——只在特定 guiScale 下复现，极难定位。
     */
    static boolean inRect(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    // =====================================================================
    // 动效（150–300ms fade + ease-out，AGENTS.md 允许的唯一一类）
    // =====================================================================

    /** 归一化进度的 ease-out cubic；{@code durationMs <= 0} 视为已播完。 */
    static float fadeIn(long elapsedMs, long durationMs) {
        if (durationMs <= 0L) {
            return 1f;
        }
        float t = Math.max(0f, Math.min(1f, elapsedMs / (float) durationMs));
        float inv = 1f - t;
        return 1f - inv * inv * inv;
    }

    /** 按比例缩放 ARGB 的 alpha 通道，保留 RGB。 */
    static int withAlpha(int argb, float alpha) {
        int base = (argb >>> 24) & 0xFF;
        int a = Math.round(base * Math.max(0f, Math.min(1f, alpha)));
        return (a << 24) | (argb & 0x00FFFFFF);
    }
}
