package org.shee33.act0.battlefield.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 锁住阵营名的准入规则。
 *
 * <p>这些名称由建图者自由输入，之后会被直接拼进聊天、TAB 面板与对局浏览器的着色串里——
 * 校验是这条链路上唯一的关卡。
 */
class FactionNamesTest {

    @Test
    void acceptsOrdinaryNames() {
        assertEquals(FactionNames.Problem.OK, FactionNames.check("凡尔登守备军", "第七装甲师"));
    }

    @Test
    void rejectsBlank() {
        assertEquals(FactionNames.Problem.BLANK, FactionNames.checkName(null));
        assertEquals(FactionNames.Problem.BLANK, FactionNames.checkName(""));
        assertEquals(FactionNames.Problem.BLANK, FactionNames.checkName("   "));
    }

    @Test
    void rejectsOverlongName() {
        String justFits = "阵".repeat(FactionNames.MAX_LENGTH);
        assertEquals(FactionNames.Problem.OK, FactionNames.checkName(justFits));
        assertEquals(FactionNames.Problem.TOO_LONG, FactionNames.checkName(justFits + "阵"));
    }

    /** § 会覆盖阵营配色甚至用 §k 打乱整行，必须挡在存储之前。 */
    @Test
    void rejectsFormattingCodeInjection() {
        assertEquals(FactionNames.Problem.ILLEGAL_CHAR, FactionNames.checkName("§c红队"));
        assertEquals(FactionNames.Problem.ILLEGAL_CHAR, FactionNames.checkName("红§k队"));
    }

    @Test
    void rejectsControlCharacters() {
        assertEquals(FactionNames.Problem.ILLEGAL_CHAR, FactionNames.checkName("红\n队"));
        assertEquals(FactionNames.Problem.ILLEGAL_CHAR, FactionNames.checkName("红\u007f队"));
    }

    @Test
    void rejectsDuplicateIgnoringCaseAndPadding() {
        assertEquals(FactionNames.Problem.DUPLICATE, FactionNames.check("Alpha", "alpha"));
        assertEquals(FactionNames.Problem.DUPLICATE, FactionNames.check("红队", "  红队  "));
    }

    /** ALPHA 先报错，管理员一次只需要改一个地方。 */
    @Test
    void reportsAlphaProblemFirst() {
        assertEquals(FactionNames.Problem.BLANK, FactionNames.check("", "§c也非法"));
    }

    @Test
    void constructorRejectsInvalidInput() {
        assertThrows(IllegalArgumentException.class, () -> new FactionNames("", "蓝队"));
        assertThrows(IllegalArgumentException.class, () -> new FactionNames("红队", "红队"));
    }

    @Test
    void trimsStoredNames() {
        FactionNames names = new FactionNames("  红队  ", " 蓝队 ");
        assertEquals("红队", names.alpha());
        assertEquals("蓝队", names.bravo());
    }

    /** 0.2.7 及更早的存档没有这两个字段，读出来是空串，必须整对回落而不是抛异常。 */
    @Test
    void sanitizeFallsBackToLegacyForOldSaves() {
        assertEquals(FactionNames.LEGACY, FactionNames.sanitize("", ""));
        assertEquals(FactionNames.LEGACY, FactionNames.sanitize("只有一个", ""));
        assertEquals(FactionNames.LEGACY, FactionNames.sanitize("§c脏数据", "蓝队"));
    }

    @Test
    void sanitizeKeepsValidPair() {
        assertEquals(new FactionNames("红队", "蓝队"), FactionNames.sanitize("红队", "蓝队"));
    }

    @Test
    void resolvesPerFaction() {
        FactionNames names = new FactionNames("红队", "蓝队");
        assertEquals("红队", names.name(Faction.ALPHA));
        assertEquals("蓝队", names.name(Faction.BRAVO));
        assertEquals("§9红队", names.colored(Faction.ALPHA));
        assertEquals("§c蓝队", names.colored(Faction.BRAVO));
    }
}
