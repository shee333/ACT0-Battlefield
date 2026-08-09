package org.shee33.act0.battlefield.client;

import org.junit.jupiter.api.Test;
import org.shee33.act0.battlefield.network.BattlefieldRoomDto;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BattlefieldRoomBrowserAnimatorTest {

    /** 测试用房间。{@code max} 同时充当开局阈值,免得每个用例都要多传一个无关参数。 */
    private static BattlefieldRoomDto room(String key, boolean breakthrough, boolean running,
                                            int cur, int max, int t1, int t2, int tmax) {
        return new BattlefieldRoomDto(key, "战役 " + key, breakthrough, "解放峰", running,
                cur, max, max, false, "PAX ARMATA", "KATO 16", t1, t2, tmax, 0);
    }

    private static BattlefieldRoomDto membership(boolean viewerIn, boolean running, boolean breakthrough,
                                                  int cur, int max, int elapsedSeconds) {
        return new BattlefieldRoomDto("bf@overworld", "战役", breakthrough, "解放峰", running,
                cur, max, max, viewerIn, "PAX ARMATA", "KATO 16", 300, 280, 600, elapsedSeconds);
    }

    // ---------------- 已加入 / 退出：按钮派生 ----------------

    @Test
    void buttonLabelFlipsToLeaveWhenViewerIsAlreadyInTheRoom() {
        assertEquals("加 入", BattlefieldRoomBrowserAnimator.actionButtonLabel(false));
        assertEquals("退 出", BattlefieldRoomBrowserAnimator.actionButtonLabel(true));
    }

    @Test
    void leaveButtonIsBravoRedAndJoinButtonIsAlphaBlue() {
        assertEquals(0xFF4FA8FF, BattlefieldRoomBrowserAnimator.actionButtonColor(false), "加入沿用 ALPHA 蓝");
        assertEquals(0xFFD94A4A, BattlefieldRoomBrowserAnimator.actionButtonColor(true), "退出必须是 BRAVO 红");
    }

    @Test
    void bothButtonStatesAreVisuallyDistinctAndNeverGold() {
        int join = BattlefieldRoomBrowserAnimator.actionButtonColor(false);
        int leave = BattlefieldRoomBrowserAnimator.actionButtonColor(true);
        assertTrue(join != leave, "两态必须一眼分得出来");
        assertTrue(join != 0xFFFFD76A && leave != 0xFFFFD76A, "本仓库不继承 Arcade 的金色 #ffd76a");
    }

    @Test
    void joinedMarkerIsGreenAndDoesNotCollideWithExistingRowHighlights() {
        int marker = BattlefieldRoomBrowserAnimator.joinedMarkerColor();
        assertEquals(0xFF6EE27E, marker, "已加入标识用本仓库的成功绿");
        assertTrue(marker != BattlefieldRoomBrowserAnimator.actionButtonColor(false),
                "不能与展开蓝条/实时变动蓝色高亮撞色");
        assertTrue(marker != BattlefieldRoomBrowserAnimator.actionButtonColor(true), "不能与退出红撞色");
        assertTrue(marker != 0xFFFFFFFF, "不能与白色悬停底撞色");
        assertTrue(marker != 0xFFFFD76A, "不能是金色");
    }

    // ---------------- 已加入：行内提示文案 ----------------

    @Test
    void runningRowGetsJoinedPrefixOnlyWhenViewerIsIn() {
        assertEquals("运行中 · 1:05",
                BattlefieldRoomBrowserAnimator.rowTagText(membership(false, true, false, 20, 64, 65)));
        assertEquals("已加入 · 运行中 · 1:05",
                BattlefieldRoomBrowserAnimator.rowTagText(membership(true, true, false, 20, 64, 65)));
    }

    @Test
    void waitingRowGetsJoinedPrefixToo() {
        assertEquals("等待中 · 还差 4 人",
                BattlefieldRoomBrowserAnimator.rowTagText(membership(false, false, false, 4, 8, 0)));
        assertEquals("已加入 · 等待中 · 还差 4 人",
                BattlefieldRoomBrowserAnimator.rowTagText(membership(true, false, false, 4, 8, 0)));
    }

    @Test
    void joinedPrefixIsPresentForBothModes() {
        assertTrue(BattlefieldRoomBrowserAnimator.rowTagText(membership(true, true, true, 8, 32, 10))
                .startsWith("已加入 · "));
        assertTrue(BattlefieldRoomBrowserAnimator.rowTagText(membership(true, false, true, 8, 32, 0))
                .startsWith("已加入 · "));
    }

    // ---------------- 加入 / 退出意图不会混淆 ----------------

    @Test
    void joinClosesTheBrowserButLeaveKeepsItOpen() {
        var join = new BattlefieldRoomBrowserAnimator.RoomActionRequest(
                "bf@overworld", false, BattlefieldRoomBrowserAnimator.RoomAction.JOIN);
        var leave = new BattlefieldRoomBrowserAnimator.RoomActionRequest(
                "bf@overworld", false, BattlefieldRoomBrowserAnimator.RoomAction.LEAVE);
        assertTrue(join.closesBrowser(), "加入后玩家进战场,浏览器应关闭");
        assertFalse(leave.closesBrowser(), "退出后玩家通常还想继续浏览,不应关闭");
    }

    @Test
    void sameRoomWithDifferentIntentsAreNotEqual() {
        var join = new BattlefieldRoomBrowserAnimator.RoomActionRequest(
                "bf@overworld", true, BattlefieldRoomBrowserAnimator.RoomAction.JOIN);
        var leave = new BattlefieldRoomBrowserAnimator.RoomActionRequest(
                "bf@overworld", true, BattlefieldRoomBrowserAnimator.RoomAction.LEAVE);
        assertNotEquals(join, leave, "同一房间的加入与退出必须是两个可区分的意图");
        assertEquals(join.roomKey(), leave.roomKey());
        assertEquals(join.breakthrough(), leave.breakthrough());
    }

    @Test
    void breakthroughFlagSurvivesOnBothIntentsSoCommandRoutingStaysCorrect() {
        for (BattlefieldRoomBrowserAnimator.RoomAction a : BattlefieldRoomBrowserAnimator.RoomAction.values()) {
            assertTrue(new BattlefieldRoomBrowserAnimator.RoomActionRequest("bt@nether", true, a).breakthrough(),
                    "突破房的命令前缀依赖这个标记,不能在任一意图上丢失");
            assertFalse(new BattlefieldRoomBrowserAnimator.RoomActionRequest("bf@overworld", false, a).breakthrough());
        }
    }

    // ---------------- 滚动：可视行数 / 夹紧 / 可达性 ----------------

    @Test
    void visibleRowsIsListHeightDividedByRowHeight() {
        assertEquals(5, BattlefieldRoomBrowserAnimator.visibleRowsFor(152));
        assertEquals(4, BattlefieldRoomBrowserAnimator.visibleRowsFor(112));
        assertEquals(1, BattlefieldRoomBrowserAnimator.visibleRowsFor(28));
    }

    @Test
    void visibleRowsIsAtLeastOneEvenForDegenerateViewport() {
        assertEquals(1, BattlefieldRoomBrowserAnimator.visibleRowsFor(0));
        assertEquals(1, BattlefieldRoomBrowserAnimator.visibleRowsFor(-40));
    }

    @Test
    void accordionReservesThreeExtraRowsOnlyWhileExpanded() {
        assertEquals(0, BattlefieldRoomBrowserAnimator.accordionExtraRows(false));
        assertEquals(3, BattlefieldRoomBrowserAnimator.accordionExtraRows(true));
    }

    @Test
    void listShorterThanViewportCannotScroll() {
        assertEquals(0, BattlefieldRoomBrowserAnimator.maxScrollRow(3, 5, 0));
        assertEquals(0, BattlefieldRoomBrowserAnimator.maxScrollRow(5, 5, 0));
    }

    @Test
    void maxScrollLeavesExactlyOneFullViewportAtTheBottom() {
        assertEquals(7, BattlefieldRoomBrowserAnimator.maxScrollRow(12, 5, 0),
                "12 行、可视 5 行 → 最多滚到 7,此时窗口正好是 [7,12)");
    }

    @Test
    void emptyListHasNoScrollRange() {
        assertEquals(0, BattlefieldRoomBrowserAnimator.maxScrollRow(0, 5, 0));
        assertEquals(0, BattlefieldRoomBrowserAnimator.clampScrollRow(9, 0, 5, 0));
    }

    @Test
    void maxScrollNeverExceedsLastValidRowIndex() {
        // 视口比"手风琴预留行数"还矮的极端情况:上限不能越过 rows 的最后一个合法下标,
        // 否则 rows.get(scrollRow) 会抛 IndexOutOfBounds。
        for (int count = 1; count <= 12; count++) {
            for (int visible = 1; visible <= 3; visible++) {
                int max = BattlefieldRoomBrowserAnimator.maxScrollRow(count, visible, 3);
                assertTrue(max <= count - 1, "count=" + count + " visible=" + visible + " max=" + max);
                assertTrue(max >= 0);
            }
        }
    }

    @Test
    void clampPullsOutOfRangeScrollBackIntoBounds() {
        assertEquals(7, BattlefieldRoomBrowserAnimator.clampScrollRow(99, 12, 5, 0));
        assertEquals(0, BattlefieldRoomBrowserAnimator.clampScrollRow(-4, 12, 5, 0));
    }

    @Test
    void scrollingDownThenUpReturnsToTopAndStopsThere() {
        int s = 0;
        for (int i = 0; i < 40; i++) {
            s = BattlefieldRoomBrowserAnimator.scrollStep(s, -1, 12, 5, 0);
        }
        assertEquals(7, s, "一直向下滚应停在上限,不越界");
        for (int i = 0; i < 40; i++) {
            s = BattlefieldRoomBrowserAnimator.scrollStep(s, 1, 12, 5, 0);
        }
        assertEquals(0, s, "一直向上滚应停在 0,不出现负数");
    }

    @Test
    void scrollStepIsIdempotentAtBothBoundaries() {
        assertEquals(0, BattlefieldRoomBrowserAnimator.scrollStep(0, 1, 12, 5, 0));
        assertEquals(7, BattlefieldRoomBrowserAnimator.scrollStep(7, -1, 12, 5, 0));
    }

    @Test
    void everyRowIsReachableBySomeScrollPositionForTwelveRooms() {
        // 这条正是本次修的 bug 的直接反证:窗口 [scrollRow, scrollRow+visible) 的并集必须覆盖所有行,
        // 否则就存在"永久不可见、不可点"的房间。
        int count = 12;
        int visible = 5;
        boolean[] seen = new boolean[count];
        int max = BattlefieldRoomBrowserAnimator.maxScrollRow(count, visible, 0);
        for (int s = 0; s <= max; s++) {
            for (int i = s; i < Math.min(count, s + visible); i++) {
                seen[i] = true;
            }
        }
        for (int i = 0; i < count; i++) {
            assertTrue(seen[i], "第 " + i + " 行永远不可见 —— 滚动窗口没有覆盖它");
        }
    }

    @Test
    void everyRowIsReachableAcrossManyListAndViewportSizes() {
        for (int count = 0; count <= 25; count++) {
            for (int visible = 1; visible <= 8; visible++) {
                boolean[] seen = new boolean[count];
                int max = BattlefieldRoomBrowserAnimator.maxScrollRow(count, visible, 0);
                for (int s = 0; s <= max; s++) {
                    for (int i = s; i < Math.min(count, s + visible); i++) {
                        seen[i] = true;
                    }
                }
                for (int i = 0; i < count; i++) {
                    assertTrue(seen[i], "count=" + count + " visible=" + visible + " 第 " + i + " 行不可达");
                }
            }
        }
    }

    @Test
    void bottomWindowEndsExactlyAtLastRowWhenNothingIsExpanded() {
        int count = 12;
        int visible = 5;
        int max = BattlefieldRoomBrowserAnimator.maxScrollRow(count, visible, 0);
        assertEquals(count, Math.min(count, max + visible),
                "滚到底时窗口末端应正好落在最后一行,既不留空也不越界");
    }

    // ---------------- 滚动与逐行错峰索引的一致性 ----------------

    @Test
    void staggerSlotIsScreenRowNotAbsoluteIndex() {
        assertEquals(0, BattlefieldRoomBrowserAnimator.staggerSlot(7, 7),
                "滚动后首个可见行的错峰槽位必须是 0,否则入场/退场会凭空延迟");
        assertEquals(2, BattlefieldRoomBrowserAnimator.staggerSlot(9, 7));
    }

    @Test
    void staggerSlotClampsRowsScrolledAboveTheViewport() {
        assertEquals(0, BattlefieldRoomBrowserAnimator.staggerSlot(3, 7));
    }

    @Test
    void firstVisibleRowAlwaysStartsItsStaggerImmediately() {
        for (int scroll = 0; scroll <= 20; scroll++) {
            int slot = BattlefieldRoomBrowserAnimator.staggerSlot(scroll, scroll);
            assertEquals(0L, BattlefieldRoomBrowserAnimator.rowDelayMs(slot));
            assertEquals(0L, BattlefieldRoomBrowserAnimator.exitDelayMs(slot));
            assertEquals(0L, BattlefieldRoomBrowserAnimator.refreshExitDelayMs(slot));
            assertEquals(0L, BattlefieldRoomBrowserAnimator.flipDelayMs(slot));
        }
    }

    // ---------------- 错峰间隔（文档 §1.3 / §3.3 原值） ----------------

    @Test
    void chunkCascadeStaggersSeventyMsPerBlock() {
        assertEquals(0L, BattlefieldRoomBrowserAnimator.chunkDelayMs(0));
        assertEquals(70L, BattlefieldRoomBrowserAnimator.chunkDelayMs(1));
        assertEquals(280L, BattlefieldRoomBrowserAnimator.chunkDelayMs(4));
    }

    @Test
    void rowCascadeStaggersFiftyMsPerRow() {
        assertEquals(0L, BattlefieldRoomBrowserAnimator.rowDelayMs(0));
        assertEquals(50L, BattlefieldRoomBrowserAnimator.rowDelayMs(1));
        assertEquals(250L, BattlefieldRoomBrowserAnimator.rowDelayMs(5));
    }

    @Test
    void tabExitStaggersEighteenMsAndRefreshExitFifteenMs() {
        assertEquals(54L, BattlefieldRoomBrowserAnimator.exitDelayMs(3));
        assertEquals(45L, BattlefieldRoomBrowserAnimator.refreshExitDelayMs(3));
    }

    @Test
    void flipStaggersTwentyFiveMsPerRow() {
        assertEquals(0L, BattlefieldRoomBrowserAnimator.flipDelayMs(0));
        assertEquals(25L, BattlefieldRoomBrowserAnimator.flipDelayMs(1));
        assertEquals(125L, BattlefieldRoomBrowserAnimator.flipDelayMs(5));
    }

    @Test
    void staggerDelaysNeverGoNegative() {
        assertEquals(0L, BattlefieldRoomBrowserAnimator.chunkDelayMs(-1));
        assertEquals(0L, BattlefieldRoomBrowserAnimator.rowDelayMs(-1));
        assertEquals(0L, BattlefieldRoomBrowserAnimator.exitDelayMs(-1));
        assertEquals(0L, BattlefieldRoomBrowserAnimator.refreshExitDelayMs(-1));
        assertEquals(0L, BattlefieldRoomBrowserAnimator.flipDelayMs(-1));
    }

    // ---------------- FLIP 位移 ----------------

    @Test
    void flipOffsetIsOldPositionMinusNewPosition() {
        assertEquals(-56, BattlefieldRoomBrowserAnimator.flipOffset(28, 84));
        assertEquals(56, BattlefieldRoomBrowserAnimator.flipOffset(84, 28));
        assertEquals(0, BattlefieldRoomBrowserAnimator.flipOffset(28, 28));
    }

    // ---------------- 「还差 N 人开局」 ----------------

    /** 差额的分母是<b>自动开始人数</b>，不是人数上限——等待阶段要回答的是"还差几人开打"。 */
    @Test
    void waitingShortfallCountsDownToAutoStart() {
        assertEquals(6, BattlefieldRoomBrowserAnimator.waitingShortfall(2, 8));
        assertEquals(0, BattlefieldRoomBrowserAnimator.waitingShortfall(8, 8));
    }

    @Test
    void waitingShortfallClampsAtZeroPastThreshold() {
        assertEquals(0, BattlefieldRoomBrowserAnimator.waitingShortfall(12, 8),
                "人数已超过开局阈值(此时正等待开局tick)，不应出现负数文案");
    }

    // ---------------- 对峙条比例 = 票数/tmax × 50% ----------------

    @Test
    void ticketBarFractionIsHalfWidthAtFullTickets() {
        assertEquals(0.5f, BattlefieldRoomBrowserAnimator.ticketBarFraction(1000, 1000), 1e-6f);
    }

    @Test
    void ticketBarFractionIsProportional() {
        assertEquals(0.25f, BattlefieldRoomBrowserAnimator.ticketBarFraction(500, 1000), 1e-6f);
        assertEquals(0f, BattlefieldRoomBrowserAnimator.ticketBarFraction(0, 1000), 1e-6f);
    }

    @Test
    void ticketBarFractionSurvivesZeroMaxAndOverflow() {
        assertEquals(0f, BattlefieldRoomBrowserAnimator.ticketBarFraction(300, 0), 1e-6f);
        assertEquals(0.5f, BattlefieldRoomBrowserAnimator.ticketBarFraction(1200, 1000), 1e-6f);
    }

    // ---------------- 人数条填充率与三档配色 ----------------

    @Test
    void fillPctIsCurOverMax() {
        assertEquals(0.5f, BattlefieldRoomBrowserAnimator.fillPct(room("a", false, true, 4, 8, 0, 0, 1)), 1e-6f);
        assertEquals(0f, BattlefieldRoomBrowserAnimator.fillPct(room("a", false, true, 4, 0, 0, 0, 1)), 1e-6f);
    }

    @Test
    void playersFillColorHasThreeTiers() {
        int low = BattlefieldRoomBrowserAnimator.playersFillColor(0.5f);
        int mid = BattlefieldRoomBrowserAnimator.playersFillColor(0.9f);
        int full = BattlefieldRoomBrowserAnimator.playersFillColor(1f);
        assertEquals(0xFF4FA8FF, low, "<75% 走 ALPHA 蓝");
        assertEquals(0xFFFF8C00, mid, "≥75% 走本仓库橙黄(替代文档金色)");
        assertEquals(0xFFD94A4A, full, "满走 BRAVO 红");
    }

    @Test
    void playersFillColorNeverUsesArcadeGold() {
        for (int i = 0; i <= 10; i++) {
            assertTrue(BattlefieldRoomBrowserAnimator.playersFillColor(i / 10f) != 0xFFFFD76A,
                    "本仓库不继承 Arcade 的金色 #ffd76a");
        }
    }

    // ---------------- 滚轮方向 = 语义（增上滚 / 减下滚） ----------------

    @Test
    void rollDirectionIsUpOnIncreaseAndDownOnDecrease() {
        assertEquals(1, BattlefieldRoomBrowserAnimator.rollDirection(40, 41));
        assertEquals(-1, BattlefieldRoomBrowserAnimator.rollDirection(41, 40));
        assertEquals(1, BattlefieldRoomBrowserAnimator.rollDirection(40, 40));
    }

    // ---------------- 排序：仅人数，首点降序再点升序 ----------------

    @Test
    void descendingSortPutsFullestMatchFirst() {
        List<BattlefieldRoomDto> list = new ArrayList<>(List.of(
                room("a", false, true, 12, 64, 0, 0, 1),
                room("b", false, true, 58, 64, 0, 0, 1),
                room("c", false, true, 41, 64, 0, 0, 1)));
        list.sort((x, y) -> BattlefieldRoomBrowserAnimator.compareByPlayers(x, y, true));
        assertEquals(List.of("b", "c", "a"), list.stream().map(BattlefieldRoomDto::roomKey).toList());
    }

    @Test
    void ascendingSortIsTheExactReverse() {
        List<BattlefieldRoomDto> list = new ArrayList<>(List.of(
                room("a", false, true, 12, 64, 0, 0, 1),
                room("b", false, true, 58, 64, 0, 0, 1),
                room("c", false, true, 41, 64, 0, 0, 1)));
        list.sort((x, y) -> BattlefieldRoomBrowserAnimator.compareByPlayers(x, y, false));
        assertEquals(List.of("a", "c", "b"), list.stream().map(BattlefieldRoomDto::roomKey).toList());
    }

    // ---------------- 文案 ----------------

    @Test
    void modeTextCarriesScaleLikeConquestSixtyFour() {
        assertEquals("征服 64", BattlefieldRoomBrowserAnimator.modeText(room("a", false, true, 1, 64, 0, 0, 1)));
        assertEquals("突破 32", BattlefieldRoomBrowserAnimator.modeText(room("a", true, true, 1, 32, 0, 0, 1)));
    }

    @Test
    void playersTextHasNoQueueSuffixBecauseThisRepoHasNoQueue() {
        assertEquals("58/64", BattlefieldRoomBrowserAnimator.playersText(room("a", false, true, 58, 64, 0, 0, 1)));
    }

    @Test
    void spacedMapNameInsertsOneSpaceBetweenEveryCharacter() {
        assertEquals("解 放 峰", BattlefieldRoomBrowserAnimator.spacedMapName("解放峰"));
        assertEquals("A", BattlefieldRoomBrowserAnimator.spacedMapName("A"));
        assertEquals("", BattlefieldRoomBrowserAnimator.spacedMapName(""));
        assertEquals("", BattlefieldRoomBrowserAnimator.spacedMapName(null));
    }

    @Test
    void elapsedIsFormattedAsMinutesAndZeroPaddedSeconds() {
        assertEquals("0:00", BattlefieldRoomBrowserAnimator.formatElapsed(0));
        assertEquals("1:05", BattlefieldRoomBrowserAnimator.formatElapsed(65));
        assertEquals("12:34", BattlefieldRoomBrowserAnimator.formatElapsed(754));
        assertEquals("0:00", BattlefieldRoomBrowserAnimator.formatElapsed(-5));
    }

    // ---------------- 三段式加入转场时间轴（文档 §3.5） ----------------

    @Test
    void joinStageAdvancesThroughThreePhases() {
        assertEquals(0, BattlefieldRoomBrowserAnimator.joinStage(0));
        assertEquals(0, BattlefieldRoomBrowserAnimator.joinStage(849));
        assertEquals(1, BattlefieldRoomBrowserAnimator.joinStage(850));
        assertEquals(1, BattlefieldRoomBrowserAnimator.joinStage(1549));
        assertEquals(2, BattlefieldRoomBrowserAnimator.joinStage(1550));
        assertEquals(2, BattlefieldRoomBrowserAnimator.joinStage(BattlefieldRoomBrowserAnimator.joinTotalMs()));
    }

    @Test
    void joinBarHitsFortyTwoThenEightyEightThenHundred() {
        assertEquals(0f, BattlefieldRoomBrowserAnimator.joinBarPercent(0), 1e-4f);
        assertEquals(0f, BattlefieldRoomBrowserAnimator.joinBarPercent(249), 1e-4f);
        assertEquals(42f, BattlefieldRoomBrowserAnimator.joinBarPercent(850), 0.5f);
        assertEquals(88f, BattlefieldRoomBrowserAnimator.joinBarPercent(1550), 0.5f);
        assertEquals(100f, BattlefieldRoomBrowserAnimator.joinBarPercent(1750), 1e-4f);
        assertEquals(100f, BattlefieldRoomBrowserAnimator.joinBarPercent(2600), 1e-4f);
    }

    @Test
    void joinBarNeverGoesBackwards() {
        float prev = -1f;
        for (long t = 0; t <= BattlefieldRoomBrowserAnimator.joinTotalMs(); t += 10) {
            float p = BattlefieldRoomBrowserAnimator.joinBarPercent(t);
            assertTrue(p >= prev - 1e-4f, "进度条在 t=" + t + " 处倒退了: " + prev + " → " + p);
            prev = p;
        }
    }

    @Test
    void joinOverlayFadesInHoldsThenFadesOut() {
        assertEquals(0f, BattlefieldRoomBrowserAnimator.joinOverlayAlpha(0), 1e-4f);
        assertEquals(1f, BattlefieldRoomBrowserAnimator.joinOverlayAlpha(250), 1e-4f);
        assertEquals(1f, BattlefieldRoomBrowserAnimator.joinOverlayAlpha(2449), 1e-4f);
        assertTrue(BattlefieldRoomBrowserAnimator.joinOverlayAlpha(2600) < 1f);
        assertEquals(0f, BattlefieldRoomBrowserAnimator.joinOverlayAlpha(
                BattlefieldRoomBrowserAnimator.joinTotalMs()), 1e-4f);
    }

    @Test
    void joinOverlayHoldsSevenHundredMsBeforeFadeOut() {
        assertEquals(700L, 2450L - 1750L, "第三段结束到淡出之间应停留 700ms");
        assertEquals(1f, BattlefieldRoomBrowserAnimator.joinOverlayAlpha(1750), 1e-4f);
        assertEquals(1f, BattlefieldRoomBrowserAnimator.joinOverlayAlpha(2449), 1e-4f);
    }
}
