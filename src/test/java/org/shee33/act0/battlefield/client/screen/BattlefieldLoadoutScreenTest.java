package org.shee33.act0.battlefield.client.screen;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BattlefieldLoadoutScreen} 的可验证部分——即被抽到 {@link BattlefieldLoadoutLayout} 的
 * 全部纯算术。绘制本身在无显示环境里没有验证手段，这里覆盖的是"算错了也不会崩、只会静默错位"的那一层。
 */
class BattlefieldLoadoutScreenTest {

    // ---------------- 下标夹紧与回绕 ----------------

    @Test
    void clampIndexKeepsValueInsideList() {
        assertEquals(0, BattlefieldLoadoutLayout.clampIndex(-3, 4));
        assertEquals(3, BattlefieldLoadoutLayout.clampIndex(9, 4));
        assertEquals(2, BattlefieldLoadoutLayout.clampIndex(2, 4));
    }

    @Test
    void clampIndexReportsNoLegalIndexForEmptyList() {
        assertEquals(-1, BattlefieldLoadoutLayout.clampIndex(0, 0),
                "空列表必须能被调用方分辨出来，而不是伪装成下标 0");
    }

    @Test
    void wrapIndexCyclesInBothDirections() {
        assertEquals(3, BattlefieldLoadoutLayout.wrapIndex(-1, 4), "左方向键从第一个绕到最后一个");
        assertEquals(0, BattlefieldLoadoutLayout.wrapIndex(4, 4), "右方向键从最后一个绕回第一个");
        assertEquals(1, BattlefieldLoadoutLayout.wrapIndex(-7, 4));
    }

    @Test
    void wrapIndexOnEmptyListIsNotAnIndex() {
        assertEquals(-1, BattlefieldLoadoutLayout.wrapIndex(1, 0));
    }

    @Test
    void indexOfFallsBackToFirstEntryWhenServerNameIsUnknown() {
        List<String> maps = List.of("解放峰", "钢铁走廊");
        assertEquals(1, BattlefieldLoadoutLayout.indexOfOrFirst(maps, "钢铁走廊"));
        assertEquals(0, BattlefieldLoadoutLayout.indexOfOrFirst(maps, "已删除的图"),
                "对不上时高亮第一项，好过整条标签栏一个都不亮");
        assertEquals(-1, BattlefieldLoadoutLayout.indexOfOrFirst(List.of(), "任意"));
    }

    // ---------------- 槽位分组（三级标签的「组」） ----------------

    @Test
    void hotbarZeroToTwoAreWeaponsAndThreeFourAreGadgets() {
        assertTrue(BattlefieldLoadoutLayout.isWeaponSlot(0), "主武器");
        assertTrue(BattlefieldLoadoutLayout.isWeaponSlot(1), "副武器");
        assertTrue(BattlefieldLoadoutLayout.isWeaponSlot(2), "近战");
        assertFalse(BattlefieldLoadoutLayout.isWeaponSlot(3), "道具1");
        assertFalse(BattlefieldLoadoutLayout.isWeaponSlot(4), "道具2");
    }

    @Test
    void negativeOrOutOfRangeSlotIsNeverTreatedAsWeapon() {
        assertFalse(BattlefieldLoadoutLayout.isWeaponSlot(-1));
        assertFalse(BattlefieldLoadoutLayout.isWeaponSlot(9));
    }

    @Test
    void groupMembersSplitsFullFiveSlotLoadoutIntoTwoGroups() {
        List<Integer> slots = List.of(0, 1, 2, 3, 4);
        assertEquals(List.of(0, 1, 2), BattlefieldLoadoutLayout.groupMembers(slots, true));
        assertEquals(List.of(3, 4), BattlefieldLoadoutLayout.groupMembers(slots, false));
    }

    @Test
    void groupMembersReturnsListPositionsNotSlotIndices() {
        // 地图只配了主武器/近战/道具2 —— 槽位号有洞，位置号没有。
        List<Integer> slots = List.of(0, 2, 4);
        assertEquals(List.of(0, 1), BattlefieldLoadoutLayout.groupMembers(slots, true));
        assertEquals(List.of(2), BattlefieldLoadoutLayout.groupMembers(slots, false));
    }

    @Test
    void groupMembersToleratesEmptyAndNullSlotLists() {
        assertTrue(BattlefieldLoadoutLayout.groupMembers(List.of(), true).isEmpty());
        assertTrue(BattlefieldLoadoutLayout.groupMembers(null, false).isEmpty());
    }

    @Test
    void positionOfSlotLocatesByHotbarIndex() {
        List<Integer> slots = List.of(0, 2, 4);
        assertEquals(1, BattlefieldLoadoutLayout.positionOfSlot(slots, 2));
        assertEquals(-1, BattlefieldLoadoutLayout.positionOfSlot(slots, 3),
                "换图后消失的槽位必须报告不存在，调用方才知道要回落");
        assertEquals(-1, BattlefieldLoadoutLayout.positionOfSlot(null, 0));
    }

    // ---------------- 选项分页 ----------------

    @Test
    void rowsPerPageIsAtLeastOneEvenInAbsurdlySmallAreas() {
        assertEquals(6, BattlefieldLoadoutLayout.rowsPerPage(100, 16));
        assertEquals(1, BattlefieldLoadoutLayout.rowsPerPage(10, 16));
        assertEquals(1, BattlefieldLoadoutLayout.rowsPerPage(100, 0), "行高为 0 也不能除零");
    }

    @Test
    void emptyOptionListStillCountsAsOnePage() {
        assertEquals(1, BattlefieldLoadoutLayout.pageCount(0, 5),
                "空列表要有一页承载「该槽位没有可选装备」，分页器不能凭空消失");
    }

    @Test
    void pageCountRoundsUp() {
        assertEquals(1, BattlefieldLoadoutLayout.pageCount(5, 5));
        assertEquals(2, BattlefieldLoadoutLayout.pageCount(6, 5));
        assertEquals(13, BattlefieldLoadoutLayout.pageCount(64, 5));
    }

    @Test
    void clampPageNeverLandsOnABlankPage() {
        assertEquals(1, BattlefieldLoadoutLayout.clampPage(9, 6, 5));
        assertEquals(0, BattlefieldLoadoutLayout.clampPage(-4, 6, 5));
        assertEquals(0, BattlefieldLoadoutLayout.clampPage(3, 0, 5));
    }

    @Test
    void pageWindowCoversExactlyTheRemainingOptions() {
        assertEquals(0, BattlefieldLoadoutLayout.pageStart(0, 6, 5));
        assertEquals(5, BattlefieldLoadoutLayout.pageEnd(0, 6, 5));
        assertEquals(5, BattlefieldLoadoutLayout.pageStart(1, 6, 5));
        assertEquals(6, BattlefieldLoadoutLayout.pageEnd(1, 6, 5), "末页只画剩下的一项，不越界");
    }

    @Test
    void pageWindowOfAnOverflowedPageIsSnappedBackToTheLastPage() {
        assertEquals(5, BattlefieldLoadoutLayout.pageStart(99, 6, 5));
        assertEquals(6, BattlefieldLoadoutLayout.pageEnd(99, 6, 5));
    }

    @Test
    void pageWindowIsEmptyWhenThereAreNoOptions() {
        assertEquals(0, BattlefieldLoadoutLayout.pageStart(0, 0, 5));
        assertEquals(0, BattlefieldLoadoutLayout.pageEnd(0, 0, 5));
    }

    @Test
    void pageOfLocatesTheCurrentlyEquippedOption() {
        assertEquals(0, BattlefieldLoadoutLayout.pageOf(4, 5));
        assertEquals(1, BattlefieldLoadoutLayout.pageOf(5, 5));
        assertEquals(0, BattlefieldLoadoutLayout.pageOf(-3, 5));
    }

    @Test
    void scrollingUpGoesToThePreviousPage() {
        assertEquals(0, BattlefieldLoadoutLayout.stepPage(1, 1.0, 6, 5));
        assertEquals(1, BattlefieldLoadoutLayout.stepPage(0, -1.0, 6, 5));
    }

    @Test
    void scrollingAtTheEdgeReturnsTheSamePage() {
        assertEquals(0, BattlefieldLoadoutLayout.stepPage(0, 1.0, 6, 5));
        assertEquals(1, BattlefieldLoadoutLayout.stepPage(1, -1.0, 6, 5));
        assertEquals(1, BattlefieldLoadoutLayout.stepPage(1, 0.0, 6, 5));
    }

    // ---------------- 地图标签的横向滚动窗口 ----------------

    private static final int[] TABS = {20, 20, 20};

    @Test
    void visibleTabCountStopsBeforeOverflowing() {
        assertEquals(2, BattlefieldLoadoutLayout.visibleTabCount(TABS, 4, 64, 0));
        assertEquals(3, BattlefieldLoadoutLayout.visibleTabCount(TABS, 4, 68, 0));
    }

    @Test
    void aSingleOversizedTabIsStillShown() {
        assertEquals(1, BattlefieldLoadoutLayout.visibleTabCount(new int[]{200}, 4, 40, 0),
                "超长中文地图名宁可被裁掉右半边，也不能让整条标签栏空白");
    }

    @Test
    void visibleTabCountOfNoTabsIsZero() {
        assertEquals(0, BattlefieldLoadoutLayout.visibleTabCount(new int[0], 4, 64, 0));
        assertEquals(0, BattlefieldLoadoutLayout.visibleTabCount(null, 4, 64, 0));
    }

    @Test
    void maxTabScrollLeavesNoUnfillableGapOnTheRight() {
        assertEquals(1, BattlefieldLoadoutLayout.maxTabScroll(TABS, 4, 64));
        assertEquals(0, BattlefieldLoadoutLayout.maxTabScroll(TABS, 4, 68), "全部放得下就不该能滚");
        assertEquals(0, BattlefieldLoadoutLayout.maxTabScroll(new int[0], 4, 64));
    }

    @Test
    void clampTabScrollRejectsBothUnderAndOverScroll() {
        assertEquals(0, BattlefieldLoadoutLayout.clampTabScroll(-5, TABS, 4, 64));
        assertEquals(1, BattlefieldLoadoutLayout.clampTabScroll(9, TABS, 4, 64));
    }

    @Test
    void ensureTabVisibleScrollsRightUntilTheSelectedTabEntersTheWindow() {
        assertEquals(1, BattlefieldLoadoutLayout.ensureTabVisible(0, 2, TABS, 4, 64));
    }

    @Test
    void ensureTabVisibleScrollsLeftWhenTheSelectionIsBehindTheWindow() {
        assertEquals(0, BattlefieldLoadoutLayout.ensureTabVisible(1, 0, TABS, 4, 64));
    }

    @Test
    void ensureTabVisibleIsAFixedPointWhenTheSelectionAlreadyShows() {
        assertEquals(0, BattlefieldLoadoutLayout.ensureTabVisible(0, 1, TABS, 4, 64));
        assertEquals(0, BattlefieldLoadoutLayout.ensureTabVisible(0, -1, TABS, 4, 64));
    }

    @Test
    void tabXAccumulatesWidthsAndGapsFromTheWindowStart() {
        assertEquals(100, BattlefieldLoadoutLayout.tabX(TABS, 4, 100, 0, 0));
        assertEquals(124, BattlefieldLoadoutLayout.tabX(TABS, 4, 100, 0, 1));
        assertEquals(148, BattlefieldLoadoutLayout.tabX(TABS, 4, 100, 0, 2));
        assertEquals(100, BattlefieldLoadoutLayout.tabX(TABS, 4, 100, 1, 1),
                "滚动后窗口首项必须回到左边界，否则整条标签栏会整体右移");
    }

    // ---------------- 两栏分割 ----------------

    @Test
    void bodySplitsIntoAboutFortyPercentSlotsAndSixtyPercentOptions() {
        assertEquals(164, BattlefieldLoadoutLayout.splitLeftWidth(400, 8));
    }

    @Test
    void neitherColumnEverCollapsesToZero() {
        int left = BattlefieldLoadoutLayout.splitLeftWidth(100, 8);
        assertTrue(left > 0, "左栏塌成 0 会让所有 fit 的可用宽度变成负数");
        assertTrue(left < 100 - 8, "右栏同样不能被挤没");
    }

    @Test
    void widePanelStillHonoursTheRightColumnMinimum() {
        int total = BattlefieldLoadoutLayout.MIN_LEFT_W + BattlefieldLoadoutLayout.MIN_RIGHT_W + 8;
        int left = BattlefieldLoadoutLayout.splitLeftWidth(total, 8);
        assertEquals(BattlefieldLoadoutLayout.MIN_LEFT_W, left);
    }

    // ---------------- 命中 ----------------

    @Test
    void inRectIsHalfOpenSoAdjacentRowsNeverBothMatch() {
        assertTrue(BattlefieldLoadoutLayout.inRect(10, 20, 10, 20, 30, 16));
        assertFalse(BattlefieldLoadoutLayout.inRect(40, 20, 10, 20, 30, 16), "右边界属于下一个矩形");
        assertFalse(BattlefieldLoadoutLayout.inRect(10, 36, 10, 20, 30, 16), "下边界属于下一行");
        assertFalse(BattlefieldLoadoutLayout.inRect(9, 20, 10, 20, 30, 16));
    }

    @Test
    void adjacentRowRectsPartitionTheColumnWithoutOverlapOrGap() {
        int top = 10;
        int rowH = 16;
        for (int y = top; y < top + rowH * 3; y++) {
            int matches = 0;
            for (int row = 0; row < 3; row++) {
                if (BattlefieldLoadoutLayout.inRect(20, y, 12, top + row * rowH, 40, rowH)) {
                    matches++;
                }
            }
            assertEquals(1, matches, "y=" + y + " 必须恰好命中一行");
        }
    }

    // ---------------- 淡入与透明度合成 ----------------

    @Test
    void fadeInRunsFromZeroToOneWithEaseOut() {
        assertEquals(0f, BattlefieldLoadoutLayout.fadeIn(0, 220), 1e-6f);
        assertEquals(1f, BattlefieldLoadoutLayout.fadeIn(220, 220), 1e-6f);
        assertEquals(0.875f, BattlefieldLoadoutLayout.fadeIn(110, 220), 1e-6f);
    }

    @Test
    void fadeInClampsBeforeTheStartAndAfterTheEnd() {
        assertEquals(0f, BattlefieldLoadoutLayout.fadeIn(-80, 220), 1e-6f,
                "错峰延迟期的负经过时间必须停在 0，不能倒放");
        assertEquals(1f, BattlefieldLoadoutLayout.fadeIn(10_000, 220), 1e-6f);
        assertEquals(1f, BattlefieldLoadoutLayout.fadeIn(0, 0), 1e-6f);
    }

    @Test
    void fadeInIsMonotonic() {
        float prev = -1f;
        for (long t = 0; t <= 220; t += 10) {
            float v = BattlefieldLoadoutLayout.fadeIn(t, 220);
            assertTrue(v >= prev, "淡入不允许回退，t=" + t);
            prev = v;
        }
    }

    @Test
    void withAlphaScalesOnlyTheAlphaChannel() {
        assertEquals(0x80112233, BattlefieldLoadoutLayout.withAlpha(0xFF112233, 0.5f));
        assertEquals(0x00112233, BattlefieldLoadoutLayout.withAlpha(0xFF112233, 0f));
        assertEquals(0xFF112233, BattlefieldLoadoutLayout.withAlpha(0xFF112233, 1f));
    }

    @Test
    void withAlphaIsRelativeToTheSourceAlphaSoTranslucentTintsStayTranslucent() {
        assertEquals(0x40112233, BattlefieldLoadoutLayout.withAlpha(0x80112233, 0.5f));
        assertEquals(0x80112233, BattlefieldLoadoutLayout.withAlpha(0x80112233, 2f),
                "越界的比例必须夹紧，否则 alpha 会溢出到 RGB 里");
    }
}
