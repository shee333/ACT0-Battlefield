package org.shee33.act0.battlefield.bot;

import org.junit.jupiter.api.Test;
import org.shee33.act0.battlefield.bot.ShootOutcome.Action;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 开火结果分类（MC-free）单元测试。
 *
 * <p>本类同时充当 TaCZ {@code ShootResult} 的<b>清单</b>：{@link #ALL_TACZ_RESULTS} 是对着
 * tacz-1.20.1-1.1.8-hotfix.jar 逐个核对出来的全部 16 项。升级 TaCZ 时先比对这份清单，
 * 新增项必须在此显式归类——这是防止"新结果悄悄变成什么都不做、bot 又一次静默不开枪"的闸门。
 */
class ShootOutcomeTest {

    private static final List<String> ALL_TACZ_RESULTS = List.of(
            "SUCCESS", "UNKNOWN_FAIL", "COOL_DOWN", "NO_AMMO", "NOT_DRAW", "NOT_GUN",
            "ID_NOT_EXIST", "NEED_BOLT", "IS_RELOADING", "IS_DRAWING", "IS_BOLTING",
            "IS_MELEE", "IS_SPRINTING", "NETWORK_FAIL", "FORGE_EVENT_CANCEL", "OVERHEATED");

    /**
     * 16 项里恰好这 5 项该告警——分区一旦被改动（无论是漏报还是误报）本断言即失败。
     *
     * <p>刻意断言整份分区而非逐项重述映射：后者只是把实现抄一遍，改坏了两边一起改，测不出东西。
     */
    @Test
    void exactlyFiveTaczResultsAreFaults() {
        List<String> faults = ALL_TACZ_RESULTS.stream()
                .filter(result -> ShootOutcome.actionFor(result) == Action.REPORT)
                .toList();
        assertEquals(
                List.of("UNKNOWN_FAIL", "NOT_GUN", "ID_NOT_EXIST", "NETWORK_FAIL", "FORGE_EVENT_CANCEL"),
                faults);
    }

    // ---------------- 需要就地自愈的结果 ----------------

    @Test
    void emptyMagazineTriggersReload() {
        assertEquals(Action.RELOAD, ShootOutcome.actionFor("NO_AMMO"));
    }

    @Test
    void manualActionGunTriggersBolt() {
        assertEquals(Action.BOLT, ShootOutcome.actionFor("NEED_BOLT"));
    }

    @Test
    void notDrawTriggersDraw() {
        assertEquals(Action.DRAW, ShootOutcome.actionFor("NOT_DRAW"));
    }

    // ---------------- 会自行消失的瞬态：必须静默 ----------------

    @Test
    void transientStatesAreSilent() {
        // 每一项都会在数十 tick 内自行消失；报警只会淹没真故障那一行
        for (String state : List.of("COOL_DOWN", "IS_RELOADING", "IS_DRAWING",
                "IS_BOLTING", "IS_MELEE", "IS_SPRINTING", "OVERHEATED")) {
            assertEquals(Action.NONE, ShootOutcome.actionFor(state), state);
        }
    }

    @Test
    void sprintingIsSilentBecauseAimingClearsItByItself() {
        // TaCZ 在检测到开镜时自行清疾跑，AI 每个交火 tick 都先开镜——在此反手写疾跑状态
        // 只会与位移层抢方向盘
        assertEquals(Action.NONE, ShootOutcome.actionFor("IS_SPRINTING"));
    }

    @Test
    void successNeedsNoFollowUp() {
        assertEquals(Action.NONE, ShootOutcome.actionFor(ShootOutcome.SUCCESS));
    }

    // ---------------- 重试不会好转的故障：必须报出来 ----------------

    @Test
    void missingWeaponIsReportedNotSwallowed() {
        // 正是本次"bot 一枪不发"的现场：配装没发到枪，主手是空气
        assertEquals(Action.REPORT, ShootOutcome.actionFor("NOT_GUN"));
    }

    @Test
    void configurationFaultsAreReported() {
        for (String fault : List.of("UNKNOWN_FAIL", "ID_NOT_EXIST", "FORGE_EVENT_CANCEL",
                "TACZ_UNAVAILABLE")) {
            assertEquals(Action.REPORT, ShootOutcome.actionFor(fault), fault);
        }
    }

    @Test
    void networkFailIsAFaultBecauseTheAiPathCannotProduceIt() {
        // AI 走 2 参数 shoot 重载，时延校验量恒为 0；它一旦出现就说明 TaCZ 的实现变了
        assertEquals(Action.REPORT, ShootOutcome.actionFor("NETWORK_FAIL"));
    }

    // ---------------- 未知输入 ----------------

    @Test
    void unknownResultIsReportedRatherThanSilentlyIgnored() {
        // TaCZ 升级后新增的结果不该悄悄退化成"什么都不做"
        assertEquals(Action.REPORT, ShootOutcome.actionFor("SOME_FUTURE_TACZ_RESULT"));
    }

    @Test
    void nullResultIsReported() {
        assertEquals(Action.REPORT, ShootOutcome.actionFor(null));
    }

}
