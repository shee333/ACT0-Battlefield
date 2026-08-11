package org.shee33.act0.battlefield.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChatMessageTextTest {

    /**
     * 回归用例：这就是"名字出现两遍"的成因。
     *
     * <p>装饰串里已经含有 {@code <Steve>}，若拿它当正文，最终会拼成
     * {@code §9Steve§f: <Steve> 你好}——一个染色名、一个原版无色名。
     */
    @Test
    void prefersRawTextSoDecorationNeverLeaksIntoTheBody() {
        assertEquals("你好", ChatMessageText.bodyOf("你好", "<Steve> 你好", "Steve"));
    }

    /**
     * 判别性用例：装饰格式不是原版 {@code <Name>} 时，只有"优先 rawText"救得回来。
     *
     * <p>上一条用例其实证明不了 rawText 优先——它的装饰串恰好是 {@code <Steve> 你好}，
     * 即便去掉优先逻辑，后面的前缀剥离也会顺手把它修对，于是那条断言对真正的回归完全失明
     * （首次写完时正是如此，靠变异测试才发现）。聊天格式化插件用的是各式各样的前缀，
     * 剥离逻辑只认原版一种，所以必须有一条剥离救不了的用例来钉死优先级。
     */
    @Test
    void prefersRawTextEvenWhenTheDecorationIsNotTheVanillaFormat() {
        assertEquals("你好", ChatMessageText.bodyOf("你好", "[红队] Steve » 你好", "Steve"));
        assertEquals("hi", ChatMessageText.bodyOf("hi", "Steve: hi", "Steve"));
        assertEquals("gg", ChatMessageText.bodyOf("gg", "§7[ALPHA] §fSteve §7>> §fgg", "Steve"));
    }

    @Test
    void fallsBackToDecoratedTextWhenRawIsMissing() {
        assertEquals("你好", ChatMessageText.bodyOf("", "<Steve> 你好", "Steve"));
        assertEquals("你好", ChatMessageText.bodyOf(null, "<Steve> 你好", "Steve"));
        assertEquals("你好", ChatMessageText.bodyOf("   ", "<Steve> 你好", "Steve"));
    }

    /** 回退路径也不能把装饰漏出去。 */
    @Test
    void fallbackStripsTheVanillaNamePrefix() {
        assertEquals("hello world", ChatMessageText.bodyOf(null, "<Alex> hello world", "Alex"));
        assertEquals("", ChatMessageText.bodyOf(null, "<Alex>", "Alex"));
        assertEquals("", ChatMessageText.bodyOf(null, "<Alex> ", "Alex"));
    }

    /** 没有装饰器的服务器上，装饰串本来就等于正文，不能被误剥。 */
    @Test
    void fallbackLeavesUndecoratedTextAlone() {
        assertEquals("hello", ChatMessageText.bodyOf(null, "hello", "Steve"));
    }

    /**
     * 玩家自己打出的尖括号必须原样保留——通用的"剥掉开头尖括号"会吃掉正文。
     */
    @Test
    void doesNotStripAngleBracketsThatArePartOfThePlayersOwnMessage() {
        assertEquals("<hi> there", ChatMessageText.bodyOf("<hi> there", "<Steve> <hi> there", "Steve"));
        assertEquals("<hi> there", ChatMessageText.bodyOf(null, "<hi> there", "Steve"));
        assertEquals("<Bob> hi", ChatMessageText.bodyOf(null, "<Bob> hi", "Steve"),
                "别人名字的尖括号不是本条消息的装饰，不能剥");
    }

    /** 只剥第一层：正文里再次出现同名尖括号是玩家打的内容。 */
    @Test
    void stripsOnlyTheLeadingPrefixOnce() {
        assertEquals("<Steve> hi", ChatMessageText.bodyOf(null, "<Steve> <Steve> hi", "Steve"));
    }

    @Test
    void handlesMissingSenderNameGracefully() {
        assertEquals("<Steve> hi", ChatMessageText.bodyOf(null, "<Steve> hi", ""));
        assertEquals("<Steve> hi", ChatMessageText.bodyOf(null, "<Steve> hi", null));
    }
}
