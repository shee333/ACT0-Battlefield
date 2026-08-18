package org.shee33.act0.battlefield.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 锁住地图名补全必须"补出来就能直接用"。
 *
 * <p>补全给出的串一旦不能被 Brigadier 解析回同一个地图名，玩家按 Tab 得到的就是一条报错命令，
 * 而报错信息不会提示"要加引号"。这里覆盖两种都真实出现过的情况：含冒号的维度 ID 兜底键，
 * 以及与同级子命令重名的地图。
 */
class ArenaSuggestionTest {

    @Test
    void plainNameNeedsNoQuotes() {
        assertEquals("Dust2", ArenaCommand.suggestable("Dust2"));
    }

    /** 未命名地图回落到维度 ID，冒号不在 Brigadier 无引号词的合法字符集内。 */
    @Test
    void dimensionIdIsQuoted() {
        assertEquals("\"minecraft:overworld\"", ArenaCommand.suggestable("minecraft:overworld"));
    }

    /** 与 {@code /aew1 arena list} 同名的地图必须带引号，否则永远被 list 子命令截走。 */
    @Test
    void nameCollidingWithSiblingLiteralIsQuoted() {
        assertEquals("\"list\"", ArenaCommand.suggestable("list"));
    }
}
