package org.shee33.act0.battlefield.client;

/**
 * 武器/装备栏状态机 —— 《作战HUD动效规格文档》§4。
 *
 * <p>选中反馈是<b>升高 + 提亮</b>而不是整体缩放（规格 §4.2）：高度 17→25、宽度 ×1.12、底色与
 * 图标亮度同步抬升，三个量由同一个进度值驱动，绘制时以底边为锚向上生长。
 *
 * <p>唯一的选中指示物是金色下划线滑块。它在滑动过程中<b>每帧重新测量目标</b>——目标卡片自己
 * 也正在变宽，若只在切换瞬间测一次，滑块会先滑到旧宽度再突兀跳一下。
 */
final class WeaponBarAnimator {

    private static final long RAISE_MS = 280L;
    private static final long RAISE_DELAY_MS = 50L;
    private static final long LOWER_MS = 180L;
    private static final long UNDERLINE_MS = 280L;
    private static final long NAME_OUT_MS = 130L;
    private static final long NAME_IN_MS = 190L;
    private static final long ROLL_MS = 190L;
    private static final long INTRO_SLOT_MS = 280L;
    private static final long INTRO_STAGGER_MS = 60L;
    private static final long INTRO_UNDERLINE_MS = 300L;

    private static int selected = -1;
    private static int previous = -1;
    private static long switchStartMs;
    private static int switchDir = 1;

    private static float underlineLeft;
    private static float underlineWidth;
    private static float underlineFromLeft;
    private static float underlineFromWidth;

    private static String weaponName = "";
    private static String pendingName = "";
    private static long nameStartMs;

private static String ammoText = "";
    private static String oldAmmoText = "";
    private static String reserveText = "";
private static long ammoRollStartMs;

    private static long introStartMs;

    private WeaponBarAnimator() {
    }

    /** 进入对局/重新显示 HUD 时播放开场（规格 §4.2「开场」）。 */
    static void playIntro(long now) {
        introStartMs = now;
        underlineWidth = 0f;
        underlineFromWidth = 0f;
    }

    static void clear() {
        selected = -1;
        previous = -1;
        introStartMs = 0L;
        underlineLeft = 0f;
        underlineWidth = 0f;
        weaponName = "";
        pendingName = "";
ammoText = "";
        oldAmmoText = "";
        reserveText = "";
    }

    static boolean introPlayed() {
        return introStartMs > 0L;
    }

    /** 槽位入场进度（自下 10px 错峰浮入）。 */
    static float introSlotProgress(int index, long now) {
        if (introStartMs <= 0L) {
            return 1f;
        }
        long delay = 400L + index * INTRO_STAGGER_MS;
        float t = clamp01((now - introStartMs - delay) / (float) INTRO_SLOT_MS);
        return Tween.Ease.OUT_CUBIC.apply(t);
    }

    /** 开场时下划线从 0 宽展开的系数。 */
    static float introUnderlineFactor(long now) {
        if (introStartMs <= 0L) {
            return 1f;
        }
        long delay = 750L + 120L;
        if (now - introStartMs < delay) {
            return 0f;
        }
        return Tween.Ease.OUT_EXPO.apply(clamp01((now - introStartMs - delay) / (float) INTRO_UNDERLINE_MS));
    }

    /** 切换选中槽。{@code slot} 为快捷栏序号。 */
    static void select(int slot, String name, String ammo, long now) {
        if (selected == slot) {
            updateAmmo(ammo, now);
            updateName(name, now);
            return;
        }
        switchDir = selected < 0 || slot > selected ? 1 : -1;
        previous = selected;
        selected = slot;
        switchStartMs = now;
        underlineFromLeft = underlineLeft;
        underlineFromWidth = underlineWidth;
        updateName(name, now);
        rollAmmo(ammo, now);
    }

    private static void updateName(String name, long now) {
        String safe = name == null ? "" : name;
        if (safe.equals(weaponName) || safe.equals(pendingName)) {
            return;
        }
        // 首次显示没有"旧名"可推出去，直接落位；否则第一帧会演一段把新名字向上抽走的空动画。
        if (weaponName.isEmpty()) {
            weaponName = safe;
            pendingName = safe;
            nameStartMs = now - NAME_OUT_MS - NAME_IN_MS;
            return;
        }
        pendingName = safe;
        nameStartMs = now;
    }

    /** 同一把武器上的弹药变化（开火/补给）。弹匣数字变化时滚动，备弹变化时静默更新。 */
    static void updateAmmo(String ammo, long now) {
        String safe = ammo == null ? "" : ammo;
        String mag = magazinePart(safe);
        String reserve = reservePart(safe);
        if (mag.equals(ammoText) && reserve.equals(reserveText)) {
            return;
        }
        if (mag.equals(ammoText)) {
            // 弹匣没变（拾取弹药/换弹完成只动备弹）：备弹数字原地刷新，不滚动。
            reserveText = reserve;
            return;
        }
        switchDir = compareAmmo(safe, ammoText);
        rollAmmo(safe, now);
    }

    private static void rollAmmo(String ammo, long now) {
        String safe = ammo == null ? "" : ammo;
        String mag = magazinePart(safe);
        String reserve = reservePart(safe);
        if (mag.equals(ammoText)) {
            return;
        }
        oldAmmoText = ammoText;
        ammoText = mag;
        reserveText = reserve;
        ammoRollStartMs = now;
    }

    /** {@code "30 / 90"} 的弹匣部分 {@code "30"}；无分隔符（非枪械文本）时整串视为弹匣。 */
    private static String magazinePart(String text) {
        int sep = text.indexOf(" / ");
        return sep < 0 ? text : text.substring(0, sep);
    }

    /** {@code "30 / 90"} 的备弹部分 {@code " / 90"}（含前缀分隔符，便于渲染时与弹匣右对齐拼接）；无分隔符返回空串。 */
    private static String reservePart(String text) {
        int sep = text.indexOf(" / ");
        return sep < 0 ? "" : text.substring(sep);
    }

    /** 数值减少向下滚(−1)、增加向上滚(+1)；非数值内容一律向上。 */
    private static int compareAmmo(String next, String prev) {
        Integer a = leadingInt(next);
        Integer b = leadingInt(prev);
        if (a == null || b == null) {
            return 1;
        }
        return a < b ? -1 : 1;
    }

    private static Integer leadingInt(String s) {
        if (s == null) {
            return null;
        }
        int i = 0;
        while (i < s.length() && !Character.isDigit(s.charAt(i))) {
            i++;
        }
        int start = i;
        while (i < s.length() && Character.isDigit(s.charAt(i))) {
            i++;
        }
        if (start == i) {
            return null;
        }
        try {
            return Integer.parseInt(s.substring(start, i));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static int selected() {
        return selected;
    }

    /**
     * 槽位的"手持程度" 0..1。新槽 outBack 升起（带 50ms 延迟，制造到手确认的回弹），
     * 旧槽 outCubic 落回。
     */
    static float raise(int slot, long now) {
        if (slot == selected) {
            float t = clamp01((now - switchStartMs - RAISE_DELAY_MS) / (float) RAISE_MS);
            return Math.max(0f, Tween.Ease.OUT_BACK.apply(t));
        }
        if (slot == previous) {
            float t = clamp01((now - switchStartMs) / (float) LOWER_MS);
            return 1f - Tween.Ease.OUT_CUBIC.apply(t);
        }
        return 0f;
    }

    /** 每帧喂入实测的目标位置，返回滑块当前应画的 [left, width]。 */
    static float[] underline(float targetLeft, float targetWidth, long now) {
        float t = Tween.Ease.OUT_CUBIC.apply(clamp01((now - switchStartMs) / (float) UNDERLINE_MS));
        underlineLeft = underlineFromLeft + (targetLeft - underlineFromLeft) * t;
        underlineWidth = underlineFromWidth + (targetWidth - underlineFromWidth) * t;
        return new float[]{underlineLeft, underlineWidth};
    }

    /** 武器名遮罩换字的纵向偏移比例（-1..1，单位为行高）。 */
    static float nameOffsetRatio(long now) {
        if (pendingName.isEmpty()) {
            return 0f;
        }
        long age = now - nameStartMs;
        if (age < NAME_OUT_MS) {
            return -1.1f * Tween.Ease.IN_CUBIC.apply(age / (float) NAME_OUT_MS);
        }
        if (weaponName.equals(pendingName)) {
            float t = clamp01((age - NAME_OUT_MS) / (float) NAME_IN_MS);
            return 1.1f * (1f - Tween.Ease.OUT_CUBIC.apply(t));
        }
        weaponName = pendingName;
        return 1.1f;
    }

    static String weaponName() {
        return weaponName.isEmpty() ? pendingName : weaponName;
    }

    static String ammoText() {
        return ammoText;
    }

    static String oldAmmoText() {
        return oldAmmoText;
    }

    /** 弹药滚轮进度；≥1 表示滚动结束、不需要再画旧值。 */
    static float ammoRoll(long now) {
        return Tween.Ease.OUT_CUBIC.apply(clamp01((now - ammoRollStartMs) / (float) ROLL_MS));
    }

static int ammoDir() {
return switchDir;
    }

    /** 备弹部分（含 " / " 前缀，如 " / 90"），静止不滚动。 */
    static String reserveText() {
        return reserveText;
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : Math.min(v, 1f);
    }
}
