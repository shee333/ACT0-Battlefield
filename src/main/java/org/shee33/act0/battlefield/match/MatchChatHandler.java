package org.shee33.act0.battlefield.match;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
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
        String messageText = messageText(event);

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
     * <p>不用 {@link ServerChatEvent#getRawText()}：其值来自 Forge 内部
     * {@code ForgeHooks.getRawText(Component)}，该方法仅在消息内容是裸的
     * {@code LiteralContents}（单层纯文本）时才返回文本，一旦装饰后的 Component
     * 被包成别的结构（比如其他同样监听聊天装饰链路的模组把正文放进了 sibling，
     * 或未来 MC/Forge 版本改变了装饰产物的结构），就会静默退化为空字符串——
     * 这与用户反馈的"看不到消息正文"完全吻合。
     *
     * <p>改用 {@link ServerChatEvent#getMessage()}（即将被发送给客户端的最终
     * Component，取消事件后不会真正发出）配合 {@link Component#getString()}：
     * 后者会递归访问 contents 以及全部 siblings，无论正文以什么结构挂在
     * Component 树上都能拿到完整文本，比 rawText 的单层 literal 判断更稳健。
     */
    private static String messageText(ServerChatEvent event) {
        return event.getMessage().getString();
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
