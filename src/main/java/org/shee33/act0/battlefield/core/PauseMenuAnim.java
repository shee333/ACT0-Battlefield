package org.shee33.act0.battlefield.core;

/**
 * 暂停菜单动效的纯数学层，对应《战地暂停菜单动效规格文档》§3.1–§3.2。
 *
 * <p>做成 MC-free 是因为这些是最容易写错、又最难在游戏里验证的部分：开场是 5 项各自延迟
 * 140+i×55ms 的级联，关闭是 22ms 错峰反向，长按确认要 800ms 填满、中途松手按<b>当前宽度</b>
 * 180ms 回退。这些时序靠手动开关菜单去比对是不可能测准的，放在 {@code core/} 就能用 JUnit
 * 把每个边界钉死。街机与战地两套暂停菜单共用本类，避免同一套时序被实现两遍还对不上。
 */
public final class PauseMenuAnim {

    /** 遮罩淡入时长。 */
    public static final int OVERLAY_IN_MS = 220;
    /** 状态标签延迟与时长。 */
    public static final int TAG_DELAY_MS = 80;
    public static final int TAG_IN_MS = 280;
    /** 菜单项级联起始延迟与项间错峰。 */
    public static final int ITEM_DELAY_MS = 140;
    public static final int ITEM_STAGGER_MS = 55;
    public static final int ITEM_IN_MS = 260;
    /** 右侧状态区延迟与时长。 */
    public static final int PANEL_DELAY_MS = 260;
    public static final int PANEL_IN_MS = 300;
    /** 金色指示器淡入延迟与时长。 */
    public static final int INDICATOR_DELAY_MS = 400;
    public static final int INDICATOR_IN_MS = 200;

    /** 关闭时菜单项错峰与时长。 */
    public static final int CLOSE_ITEM_STAGGER_MS = 22;
    public static final int CLOSE_ITEM_MS = 150;
    public static final int CLOSE_FADE_MS = 180;
    public static final int CLOSE_OVERLAY_DELAY_MS = 120;
    public static final int CLOSE_OVERLAY_MS = 220;

    /** 菜单项入场横向位移（px，负值=从左滑入）。 */
    public static final int ITEM_SLIDE_PX = -24;
    /** 关闭时菜单项左移量（px）。 */
    public static final int CLOSE_ITEM_SLIDE_PX = -14;
    /** 状态标签入场纵向位移（px，负值=从上滑入）。 */
    public static final int TAG_SLIDE_PX = -10;
    /** 右侧状态区入场横向位移（px，正值=从右滑入）。 */
    public static final int PANEL_SLIDE_PX = 20;

    /** 焦点指示器滑动时长。 */
    public static final int INDICATOR_SLIDE_MS = 220;
    /** 指示器滑动中的纵向拉伸峰值增量（×1.6 即 +0.6）。 */
    public static final float INDICATOR_STRETCH = 0.6F;

    /** 长按确认填满所需时长。 */
    public static final int HOLD_CONFIRM_MS = 800;
    /** 松手/移出后填充回退时长。 */
    public static final int HOLD_RELEASE_MS = 180;

    /** 子页面滑入/滑出时长。 */
    public static final int SUB_IN_MS = 300;
    public static final int SUB_OUT_MS = 240;
    /** 子页面打开时右侧状态区让位：压暗到该不透明度并左移。 */
    public static final float SUB_PANEL_DIM = 0.25F;
    public static final int SUB_PANEL_SHIFT_PX = -14;
    /** 子页面内容行错峰与时长。 */
    public static final int SUB_ROW_STAGGER_MS = 45;
    public static final int SUB_ROW_IN_MS = 240;

    /** Toast 淡入/停留/淡出。 */
    public static final int TOAST_IN_MS = 220;
    public static final int TOAST_HOLD_MS = 1400;
    public static final int TOAST_OUT_MS = 300;

    private PauseMenuAnim() {
    }

    // ---- 缓动（与规格文档 E{} 一致） ----

    public static float outCubic(float t) {
        float c = clamp01(t);
        float inv = 1f - c;
        return 1f - inv * inv * inv;
    }

    public static float inCubic(float t) {
        float c = clamp01(t);
        return c * c * c;
    }

    public static float outExpo(float t) {
        float c = clamp01(t);
        return c >= 1f ? 1f : 1f - (float) Math.pow(2.0, -10.0 * c);
    }

    public static float outBack(float t) {
        float c = clamp01(t);
        float k = 1.70158F;
        float d = k + 1f;
        float p = c - 1f;
        return 1f + d * p * p * p + k * p * p;
    }

    public static float clamp01(float t) {
        return t < 0f ? 0f : Math.min(1f, t);
    }

    /**
     * 带延迟的补间进度：{@code elapsed} 未到 {@code delay} 前恒为 0。
     *
     * <p>级联就是靠这个实现的——每一项用同样的时长、不同的延迟，于是内容陆续到达而不是整体
     * 一起跳出。延迟未到必须返回 0（而不是负数或直接开始），否则第一帧会出现闪现。
     */
    public static float progress(long elapsedMs, int delayMs, int durationMs) {
        if (durationMs <= 0) {
            return elapsedMs >= delayMs ? 1f : 0f;
        }
        long local = elapsedMs - delayMs;
        if (local <= 0L) {
            return 0f;
        }
        return local >= durationMs ? 1f : (float) local / durationMs;
    }

    /** 第 {@code index} 个菜单项的开场进度（含 140+i×55 级联延迟，outCubic）。 */
    public static float itemOpenProgress(long elapsedMs, int index) {
        return outCubic(progress(elapsedMs, ITEM_DELAY_MS + index * ITEM_STAGGER_MS, ITEM_IN_MS));
    }

    /** 第 {@code index} 个菜单项的关闭进度（22ms 错峰，inCubic）。 */
    public static float itemCloseProgress(long elapsedMs, int index) {
        return inCubic(progress(elapsedMs, index * CLOSE_ITEM_STAGGER_MS, CLOSE_ITEM_MS));
    }

    /** 整个关闭序列总时长：最后一项走完与遮罩走完取较晚者。 */
    public static int closeTotalMs(int itemCount) {
        int items = Math.max(0, itemCount - 1) * CLOSE_ITEM_STAGGER_MS + CLOSE_ITEM_MS;
        int overlay = CLOSE_OVERLAY_DELAY_MS + CLOSE_OVERLAY_MS;
        return Math.max(items, overlay);
    }

    /**
     * 指示器滑动中的纵向拉伸倍率：{@code 1 + 0.6·sin(vπ)}。
     *
     * <p>用 sin 包络而非线性，是为了让拉伸在滑动中途达到峰值、两端恰好回到 1——两端不回到 1
     * 的话指示器停下时会是被拉长的，看起来像没画对。
     */
    public static float indicatorStretch(float slideProgress) {
        return 1f + INDICATOR_STRETCH * (float) Math.sin(clamp01(slideProgress) * Math.PI);
    }

    /**
     * 长按确认的填充比例。
     *
     * @param heldMs        按住已经过的毫秒数
     * @param releasedAt    松手时的填充比例；{@code < 0} 表示仍在按住
     * @param sinceRelease  松手后经过的毫秒数
     * @return 0..1 的填充宽度比例；按住满 {@link #HOLD_CONFIRM_MS} 返回 1（调用方据此执行）
     */
    public static float holdFill(long heldMs, float releasedAt, long sinceRelease) {
        if (releasedAt < 0f) {
            return clamp01((float) heldMs / HOLD_CONFIRM_MS);
        }
        // 从松手当刻的宽度回退，而不是从 1 回退：按到 10% 就松手却看到满条缩回去，
        // 会让玩家以为自己差点触发了危险操作。
        float back = clamp01((float) sinceRelease / HOLD_RELEASE_MS);
        return releasedAt * (1f - outCubic(back));
    }

    /** 长按是否已达成（填满即执行）。 */
    public static boolean holdCompleted(long heldMs) {
        return heldMs >= HOLD_CONFIRM_MS;
    }

    /** Toast 不透明度：淡入 → 停留 → 淡出。 */
    public static float toastAlpha(long elapsedMs) {
        if (elapsedMs < 0L) {
            return 0f;
        }
        if (elapsedMs < TOAST_IN_MS) {
            return outCubic((float) elapsedMs / TOAST_IN_MS);
        }
        long afterHold = elapsedMs - TOAST_IN_MS - TOAST_HOLD_MS;
        if (afterHold <= 0L) {
            return 1f;
        }
        return afterHold >= TOAST_OUT_MS ? 0f : 1f - inCubic((float) afterHold / TOAST_OUT_MS);
    }

    /**
     * 遮罩三档横向渐变的某一列不透明度：0% 处 0.92 → 45% 处 0.82 → 100% 处 0.55。
     *
     * <p>左重右轻是"游戏没停"这条第一原则的载体——右侧战场必须保持可见。
     */
    public static float overlayAlphaAt(float xRatio) {
        float x = clamp01(xRatio);
        if (x <= 0.45f) {
            return 0.92f + (0.82f - 0.92f) * (x / 0.45f);
        }
        return 0.82f + (0.55f - 0.82f) * ((x - 0.45f) / 0.55f);
    }
}
