package org.shee33.act0.battlefield.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CombatHudMathTest {

    /** 规格 §5.2：>60 绿 / 25~60 黄 / ≤25 红。边界值(60/25)归属必须与文档一致。 */
    @Test
    void healthColorThresholds() {
        assertEquals(CombatHudMath.GREEN, CombatHudMath.healthColor(100));
        assertEquals(CombatHudMath.GREEN, CombatHudMath.healthColor(61));
        assertEquals(CombatHudMath.GOLD, CombatHudMath.healthColor(60), "60 属于黄档(>60 才是绿)");
        assertEquals(CombatHudMath.GOLD, CombatHudMath.healthColor(26));
        assertEquals(CombatHudMath.RED, CombatHudMath.healthColor(25), "25 属于红档(≤25)");
        assertEquals(CombatHudMath.RED, CombatHudMath.healthColor(0));
    }

    @Test
    void thresholdCrossingDetection() {
        assertTrue(CombatHudMath.crossedThreshold(61, 60), "绿→黄应触发脉冲");
        assertTrue(CombatHudMath.crossedThreshold(26, 25), "黄→红应触发脉冲");
        assertFalse(CombatHudMath.crossedThreshold(100, 70), "同在绿档不应触发");
        assertFalse(CombatHudMath.crossedThreshold(20, 5), "同在红档不应触发");
        assertTrue(CombatHudMath.crossedThreshold(20, 80), "治疗跨档同样触发");
    }

    @Test
    void criticalBoundary() {
        assertTrue(CombatHudMath.isCritical(25));
        assertFalse(CombatHudMath.isCritical(26));
    }

    /** 规格 §3.3：1=白 / 2~3=金 / ≥4=红。 */
    @Test
    void streakTierBoundaries() {
        assertEquals(0, CombatHudMath.streakTier(1));
        assertEquals(1, CombatHudMath.streakTier(2));
        assertEquals(1, CombatHudMath.streakTier(3));
        assertEquals(2, CombatHudMath.streakTier(4));
        assertEquals(2, CombatHudMath.streakTier(99));
        assertEquals(0xFFFFFFFF, CombatHudMath.tierColor(0));
        assertEquals(CombatHudMath.GOLD, CombatHudMath.tierColor(1));
        assertEquals(CombatHudMath.RED, CombatHudMath.tierColor(2));
    }

    /** 规格 §3.2：故障时长 220+tier×120、幅度 2+tier×1.5。 */
    @Test
    void glitchScalesWithTier() {
        assertEquals(220L, CombatHudMath.glitchDurationMs(0));
        assertEquals(340L, CombatHudMath.glitchDurationMs(1));
        assertEquals(460L, CombatHudMath.glitchDurationMs(2));
        assertEquals(2f, CombatHudMath.glitchAmplitude(0), 0.001f);
        assertEquals(3.5f, CombatHudMath.glitchAmplitude(1), 0.001f);
        assertEquals(5f, CombatHudMath.glitchAmplitude(2), 0.001f);
    }

    /** 规格 §3.3：得分 100+(streak−1)×25，且是累加而非重置。 */
    @Test
    void killScoreProgression() {
        assertEquals(100, CombatHudMath.killScore(1));
        assertEquals(125, CombatHudMath.killScore(2));
        assertEquals(150, CombatHudMath.killScore(3));
        assertEquals(175, CombatHudMath.killScore(4));

        int total = 0;
        for (int s = 1; s <= 4; s++) {
            total += CombatHudMath.killScore(s);
        }
        assertEquals(550, total, "四连杀累计应为 100+125+150+175");
    }

    /** 规格 §3.2：乱码逐字锁定，进度 1 必须完全还原原文。 */
    @Test
    void scrambleLocksProgressively() {
        String target = "Ghost-9";
        assertEquals(target, CombatHudMath.scramble(target, 1f, 7L));
        assertEquals(target.length(), CombatHudMath.scramble(target, 0f, 7L).length(),
                "乱码期间长度必须与原文一致,否则布局会抖");
        String half = CombatHudMath.scramble(target, 0.5f, 7L);
        assertEquals("Gho", half.substring(0, 3), "前一半应已锁定为原文");
        assertEquals(target.length(), half.length());
    }

    @Test
    void scrambleIsDeterministicForSameSeed() {
        assertEquals(CombatHudMath.scramble("Bravo22", 0.3f, 42L),
                CombatHudMath.scramble("Bravo22", 0.3f, 42L),
                "同帧同 seed 必须稳定,否则同一帧内多次绘制会闪烁");
    }

    @Test
    void scrambleHandlesEmpty() {
        assertEquals("", CombatHudMath.scramble("", 0.5f, 1L));
        assertEquals("", CombatHudMath.scramble(null, 0.5f, 1L));
    }

    /** 规格 §5.2：阈值脉冲峰值 ×1.9,两端回到 1。 */
    @Test
    void thresholdPulsePeaksAtMidpoint() {
        assertEquals(1f, CombatHudMath.thresholdPulseScale(0f), 0.001f);
        assertEquals(1.9f, CombatHudMath.thresholdPulseScale(0.5f), 0.001f);
        assertEquals(1f, CombatHudMath.thresholdPulseScale(1f), 0.001f);
    }

    @Test
    void criticalPulseStaysInRange() {
        for (long t = 0; t < 2000; t += 37) {
            float a = CombatHudMath.criticalPulseAlpha(t);
            assertTrue(a >= 0.29f && a <= 1.01f, "濒死脉冲越界: " + a);
        }
    }

    /** 只有濒死且未倒地时才有红晕底噪——倒地有自己的反馈,不叠加。 */
    @Test
    void vignetteBaseOnlyWhenCriticalAndNotDowned() {
        assertEquals(0f, CombatHudMath.vignetteBase(100, false), 0.001f);
        assertEquals(0f, CombatHudMath.vignetteBase(10, true), 0.001f);
        float v = CombatHudMath.vignetteBase(10, false);
        assertTrue(v >= 0.05f && v <= 0.19f, "濒死红晕底噪越界: " + v);
    }

    /** 规格 §4.1：主武器最宽、副武器次之、其余小件等宽。 */
    @Test
    void slotWidthsFollowLoadoutHierarchy() {
        assertEquals(40, CombatHudMath.slotWidth(0));
        assertEquals(32, CombatHudMath.slotWidth(1));
        assertEquals(24, CombatHudMath.slotWidth(2));
        assertEquals(24, CombatHudMath.slotWidth(5));
        assertTrue(CombatHudMath.slotWidth(0) > CombatHudMath.slotWidth(1));
        assertTrue(CombatHudMath.slotWidth(1) > CombatHudMath.slotWidth(2));
    }

    @Test
    void slotRowWidthMatchesSumPlusGaps() {
        int expected = 40 + 32 + 24 * 4 + CombatHudMath.SLOT_GAP * 5;
        assertEquals(expected, CombatHudMath.slotRowWidth());
    }

    /**
     * 布局防重叠：队友面板贴在小地图右侧，右侧是武器栏。MC 的 GUI 宽度随缩放差异极大，
     * 必须保证任何常见宽度下两者都不叠。
     */
    /** 复刻 HealthPanelRenderer 的真实取值链，返回面板右缘。 */
    private static int panelRightEdge(int guiWidth, int margin, int panelLeft, int barIndent, int preferredW) {
        int weaponLeft = CombatHudMath.weaponBarLeft(guiWidth, margin);
        int maxRight = Math.min(weaponLeft - CombatHudMath.COLLISION_PAD,
                CombatHudMath.squadPanelMaxRight(guiWidth, margin, panelLeft));
        maxRight = Math.max(panelLeft + CombatHudMath.SQUAD_BAR_MIN_W, maxRight);
        return panelLeft + barIndent + CombatHudMath.squadBarWidth(panelLeft, maxRight, barIndent, preferredW);
    }

    @Test
    void squadPanelNeverOverlapsWeaponBar() {
        int margin = 8;
        int barIndent = 13;
        int panelLeft = margin + 84 + 12;
        // 真实 guiWidth：1280/1920/2560 屏在 GUI scale 1~4 下的取值(含 1280@scale3=426)。
        for (int guiWidth : new int[]{426, 480, 640, 853, 960, 1280, 1920, 2560}) {
            int weaponLeft = CombatHudMath.weaponBarLeft(guiWidth, margin);
            int right = panelRightEdge(guiWidth, margin, panelLeft, barIndent, 84);
            assertTrue(right <= weaponLeft,
                    "guiWidth=" + guiWidth + " 时队友面板右缘 " + right + " 压到武器栏左缘 " + weaponLeft);
        }
    }

    /** 宽屏下队友面板必须真的拿到完整宽度,而不是被无谓地压缩。 */
    @Test
    void squadPanelGetsFullWidthOnWideScreens() {
        int margin = 8;
        int panelLeft = margin + 84 + 12;
        int maxRight = CombatHudMath.squadPanelMaxRight(640, margin, panelLeft);
        assertTrue(maxRight - panelLeft >= 84,
                "640 宽下应容得下完整自身血条(84px),实际只有 " + (maxRight - panelLeft));
    }

    /**
     * squadSize 可配到 16，队友面板自底向上生长。若不限制行数，16 人小队会把面板顶进
     * 规格明令禁止触碰的顶部票数/据点区。
     */
    @Test
    void mateRowsCappedToMinimapHeight() {
        int mateRowH = 13;
        int selfRowH = 17;
        int minimapH = 84;
        int cap = CombatHudMath.maxMateRows(minimapH, mateRowH, selfRowH);

        assertTrue(cap * mateRowH + selfRowH <= minimapH,
                "面板总高 " + (cap * mateRowH + selfRowH) + " 超出小地图高度 " + minimapH);
        assertTrue(cap >= 3, "默认 4 人小队的 3 名队友必须都显示得下，实际上限 " + cap);
    }

    @Test
    void mateRowsAlwaysLeavesAtLeastOne() {
        assertEquals(1, CombatHudMath.maxMateRows(10, 13, 17), "极端窄高度下也要保留 1 行队友");
        assertEquals(1, CombatHudMath.maxMateRows(0, 13, 17));
    }

    @Test
    void weaponBarLeftLeavesRoomForItself() {
        int total = CombatHudMath.slotRowWidth() + CombatHudMath.INFO_GAP + CombatHudMath.INFO_W;
        assertEquals(640 - 8 - total, CombatHudMath.weaponBarLeft(640, 8));
    }
}
