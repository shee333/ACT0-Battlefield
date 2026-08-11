package org.shee33.act0.battlefield.core;

/**
 * 从聊天事件里取出<b>不带任何装饰</b>的消息正文。
 *
 * <p>对局聊天要把原版的 {@code <Name> message} 换成按关系染色的 {@code Name: message}，因此
 * 必须拿到"玩家实际打的那串字"。取错来源会让名字出现两遍——一遍是我们染色的，一遍是混在正文
 * 里的原版装饰。
 *
 * <p>Forge 1.20.1 的 {@code ServerChatEvent} 有两个来源，语义完全不同：
 * <ul>
 *   <li>{@code getRawText()}：构造时直接传入的 {@code String}，即 {@code signedContent()}，
 *       就是玩家键入的原文，<b>永远不含装饰</b>；</li>
 *   <li>{@code getMessage()}：{@code decoratedContent()}，等于
 *       {@code unsignedContent != null ? unsignedContent : literal(signedContent())}。
 *       而 {@code unsignedContent} 正是 {@code ChatDecorator} 的产物——一旦服务端或其他模组
 *       装了把消息格式化成 {@code <Name> message} 的装饰器，它就会连名字一起带进来。</li>
 * </ul>
 *
 * <p>所以以 rawText 为准，仅在它为空时才回退到装饰串，并把回退串上可能存在的
 * {@code <Name> } 前缀剥掉。
 */
public final class ChatMessageText {

    private ChatMessageText() {
    }

    /**
     * @param rawText       {@code ServerChatEvent#getRawText()}，玩家键入的原文
     * @param decoratedText {@code ServerChatEvent#getMessage().getString()}，可能带装饰
     * @param senderName    发送者名，用于识别并剥离回退串上的 {@code <Name> } 前缀
     */
    public static String bodyOf(String rawText, String decoratedText, String senderName) {
        if (rawText != null && !rawText.isBlank()) {
            return rawText;
        }
        return stripNamePrefix(decoratedText == null ? "" : decoratedText, senderName);
    }

    /**
     * 剥掉开头的 {@code <发送者名> }。
     *
     * <p>只认"尖括号里恰好是发送者本人的名字"这一种形式，不做通用的尖括号剥离——玩家完全可能
     * 自己打出 {@code <hi> there} 这样的正文，通用剥离会把它的开头吃掉。
     */
    private static String stripNamePrefix(String text, String senderName) {
        if (senderName == null || senderName.isEmpty()) {
            return text;
        }
        String prefix = "<" + senderName + ">";
        if (!text.startsWith(prefix)) {
            return text;
        }
        String rest = text.substring(prefix.length());
        return rest.startsWith(" ") ? rest.substring(1) : rest;
    }
}
