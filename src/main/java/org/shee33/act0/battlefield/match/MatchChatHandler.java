package org.shee33.act0.battlefield.match;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.shee33.act0.battlefield.core.ChatMessageText;
import org.shee33.act0.battlefield.core.Faction;

import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 大战场对局内聊天格式化：把征服/突破参战玩家发送的消息从原版 {@code <Name> message}
 * 改为极简 {@code Name: message}，名字按"观察者与发送者的关系"单独染色
 * （敌方红 §c / 同小队绿 §a / 同阵营异小队蓝 §9），消息正文与冒号统一为白色 §f。
 *
 * <p>只处理正在参与 Conquest 或 Breakthrough 对局的玩家发出的消息；不在任何对局中的
 * 普通服务器聊天不取消事件，维持原版行为不变。
 *
 * <p>由于同一条消息对不同观察者需要显示不同颜色的名字，这里取消 {@link ServerChatEvent}
 * 的默认广播，改为遍历该对局所有参战玩家，逐个构造专属 Component 并 {@code sendSystemMessage}。
 *
 * <p>注册到 Forge 事件总线（{@code MinecraftForge.EVENT_BUS.register(new MatchChatHandler(...))}）。
 */
public final class MatchChatHandler {

    private final ConquestManager conquestManager;
    private final BreakthroughManager breakthroughManager;

    public MatchChatHandler(ConquestManager conquestManager, BreakthroughManager breakthroughManager) {
        this.conquestManager = conquestManager;
        this.breakthroughManager = breakthroughManager;
    }

    @SubscribeEvent
    public void onServerChat(ServerChatEvent event) {
        ServerPlayer sender = event.getPlayer();
        UUID senderId = sender.getUUID();
        String messageText = messageText(event, sender.getGameProfile().getName());

        ConquestMatch conquest = conquestManager.activeContaining(senderId);
        if (conquest != null) {
            event.setCanceled(true);
            broadcast(sender, messageText, conquest::contains, conquest::factionOf, conquest::isSameSquad);
            return;
        }
        BreakthroughMatch breakthrough = breakthroughManager.activeContaining(senderId);
        if (breakthrough != null) {
            event.setCanceled(true);
            broadcast(sender, messageText, breakthrough::contains, breakthrough::factionOf,
                    breakthrough::isSameSquad);
        }
    }

    /**
     * 提取玩家实际打的消息正文。
     *
     * <p>以 {@link ServerChatEvent#getRawText()} 为准。此前这里用的是
     * {@link ServerChatEvent#getMessage()}，理由写的是"rawText 来自
     * {@code ForgeHooks.getRawText(Component)}，只在裸 LiteralContents 时才有值"——
     * 那个说法在 Forge 1.20.1（47.4.10 核对过字节码）上是错的：{@code rawText} 就是构造
     * {@code ServerChatEvent} 时直接传入的 {@code String}，即 {@code signedContent()}，
     * 永远是玩家键入的原文。
     *
     * <p>而 {@code getMessage()} 是 {@code decoratedContent()}，等于
     * {@code unsignedContent != null ? unsignedContent : literal(signedContent())}；
     * {@code unsignedContent} 正是 {@code ChatDecorator} 的产物，装了聊天格式化的服务端
     * 会把 {@code <Name> } 一起塞进来。拿它当正文，就会拼出"一个染色名 + 一个原版无色名"
     * 的重复效果——这正是被反馈的 bug。
     *
     * <p>判定与剥离规则见 {@link ChatMessageText}（MC-free，带单测）。
     */
    private static String messageText(ServerChatEvent event, String senderName) {
        return ChatMessageText.bodyOf(event.getRawText(), event.getMessage().getString(), senderName);
    }

    private void broadcast(ServerPlayer sender, String messageText, Predicate<UUID> isParticipant,
                            Function<UUID, Faction> factionOf, BiPredicate<UUID, UUID> isSameSquad) {
        MinecraftServer server = sender.getServer();
        if (server == null) {
            return;
        }
        UUID senderId = sender.getUUID();
        Faction senderFaction = factionOf.apply(senderId);
        String senderName = sender.getGameProfile().getName();
        for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
            UUID viewerId = viewer.getUUID();
            if (!isParticipant.test(viewerId)) {
                continue;
            }
            String color = relationColor(senderFaction, factionOf.apply(viewerId), senderId, viewerId, isSameSquad);
            viewer.sendSystemMessage(Component.literal(color + senderName + "§f: " + messageText));
        }
    }

    private static String relationColor(Faction senderFaction, Faction viewerFaction, UUID senderId, UUID viewerId,
                                         BiPredicate<UUID, UUID> isSameSquad) {
        if (senderFaction == null || viewerFaction == null || senderFaction != viewerFaction) {
            return "§c";
        }
        return isSameSquad.test(senderId, viewerId) ? "§a" : "§9";
    }
}
