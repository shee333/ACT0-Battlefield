package org.shee33.act0.battlefield.bot.mc;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 在 tab 列表里给 AI 士兵加 {@code [BOT]} 标记。
 *
 * <p><b>刻意走 Forge 的 {@link PlayerEvent.TabListNameFormat} 而非记分板队伍的 prefix/suffix。</b>
 * 记分板队伍会同时改写战场上的悬浮名牌——那会让"这是假人"持续出现在玩家视野里，破坏沉浸感，
 * 而 {@code ArcadeMatch} 已经用队伍机制管理名牌配色，插进去还会互相干扰。本事件只影响 tab 列表，
 * 恰好实现"战斗中沉浸、信息界面诚实"。
 */
public final class BotTabListMarker {

    public static final BotTabListMarker INSTANCE = new BotTabListMarker();

    private BotTabListMarker() {
    }

    @SubscribeEvent
    public void onTabListNameFormat(PlayerEvent.TabListNameFormat event) {
        if (!(event.getEntity() instanceof BotPlayer bot)) {
            return;
        }
        Component current = event.getDisplayName();
        Component base = current != null
                ? current
                : Component.literal(bot.getGameProfile().getName());
        event.setDisplayName(Component.empty()
                .append(base)
                .append(Component.literal(" [BOT]").withStyle(ChatFormatting.DARK_GRAY)));
    }
}
