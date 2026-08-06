package org.shee33.act0.battlefield.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeployWeaponMathTest {

    // ---------------- openRowDelayMs / closeRowDelayMs ----------------

    @Test
    void openRowDelayIsFortyFiveMsPerIndex() {
        assertEquals(0L, DeployWeaponMath.openRowDelayMs(0));
        assertEquals(45L, DeployWeaponMath.openRowDelayMs(1));
        assertEquals(225L, DeployWeaponMath.openRowDelayMs(5));
    }

    @Test
    void closeRowDelayIsTwentyMsPerIndex() {
        assertEquals(0L, DeployWeaponMath.closeRowDelayMs(0));
        assertEquals(20L, DeployWeaponMath.closeRowDelayMs(1));
        assertEquals(100L, DeployWeaponMath.closeRowDelayMs(5));
    }

    @Test
    void rowDelaysNeverGoNegativeForNegativeIndex() {
        assertEquals(0L, DeployWeaponMath.openRowDelayMs(-1));
        assertEquals(0L, DeployWeaponMath.closeRowDelayMs(-1));
    }

    // ---------------- clampPanelX ----------------

    @Test
    void panelAlignsToSlotLeftWhenItFits() {
        int x = DeployWeaponMath.clampPanelX(50, 160, 800);
        assertEquals(50, x, "面板宽度在舞台内完全放得下时,左缘应与槽位左缘对齐");
    }

    @Test
    void panelShiftsLeftWhenItWouldOverflowRightEdge() {
        // 舞台宽 400,面板宽 160,槽位在 x=350 → 350+160=510 超出舞台,应整体左移到 400-160=240。
        int x = DeployWeaponMath.clampPanelX(350, 160, 400);
        assertEquals(240, x);
    }

    @Test
    void panelNeverGoesNegativeWhenStageIsNarrowerThanPanel() {
        // 极端情况:舞台本身比面板还窄,不应产生负坐标。
        int x = DeployWeaponMath.clampPanelX(10, 300, 200);
        assertEquals(0, x);
    }

    // ---------------- isSameItem ----------------

    @Test
    void sameItemNamesAreEqual() {
        assertTrue(DeployWeaponMath.isSameItem("m4a1", "m4a1"));
    }

    @Test
    void differentItemNamesAreNotEqual() {
        assertFalse(DeployWeaponMath.isSameItem("m4a1", "ak74"));
    }

    @Test
    void nullPickedItemIsNeverSameAsCurrent() {
        assertFalse(DeployWeaponMath.isSameItem(null, "m4a1"));
    }

    // ---------------- layoutSlotX ----------------

    @Test
    void emptyWidthsProducesEmptyLayout() {
        int[] xs = DeployWeaponMath.layoutSlotX(new int[0], 8, 400);
        assertEquals(0, xs.length);
    }

    @Test
    void singleSlotIsCenteredOnCenterX() {
        int[] xs = DeployWeaponMath.layoutSlotX(new int[]{60}, 8, 400);
        assertEquals(370, xs[0], "单槽位左缘应为 centerX - width/2");
    }

    @Test
    void multipleSlotsAreGroupedAndCenteredWithGaps() {
        // 三个槽位宽 60/40/40,gap=8 → 总宽 60+8+40+8+40=156,组左缘 = 400-78=322。
        int[] xs = DeployWeaponMath.layoutSlotX(new int[]{60, 40, 40}, 8, 400);
        assertEquals(322, xs[0]);
        assertEquals(322 + 60 + 8, xs[1]);
        assertEquals(322 + 60 + 8 + 40 + 8, xs[2]);
    }

    @Test
    void slotsNeverOverlapGivenPositiveGap() {
        int[] widths = {50, 30, 70, 44};
        int gap = 8;
        int[] xs = DeployWeaponMath.layoutSlotX(widths, gap, 500);
        for (int i = 0; i < xs.length - 1; i++) {
            int rightEdge = xs[i] + widths[i];
            assertTrue(rightEdge + gap <= xs[i + 1] + 1, "相邻槽位之间至少应保留 gap 间距,不应重叠");
        }
    }
}
