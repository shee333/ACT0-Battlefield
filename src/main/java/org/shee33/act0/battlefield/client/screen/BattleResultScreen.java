package org.shee33.act0.battlefield.client.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.shee33.act0.battlefield.network.BattleResultDto;
import org.shee33.act0.battlefield.network.TabEntryDto;

import java.util.List;

/**
 * 对局结束战报界面：展示胜方、双方剩余票数、个人 K/D 与全场击杀榜。
 */
public final class BattleResultScreen extends Screen {

    private static final int PANEL_W = 360;
    private static final int PANEL_H = 280;

    private final BattleResultDto result;
    private int left;
    private int top;

    public BattleResultScreen(BattleResultDto result) {
        super(Component.literal("战报"));
        this.result = result;
    }

    @Override
    protected void init() {
        left = (width - PANEL_W) / 2;
        top = (height - PANEL_H) / 2;
        addRenderableWidget(Button.builder(Component.literal("关闭"), b -> onClose())
                .bounds(left + PANEL_W / 2 - 42, top + PANEL_H - 28, 84, 20).build());
    }

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        renderBackground(gg);
        PixelTheme.panel(gg, left, top, PANEL_W, PANEL_H);

        String title = winnerTitle();
        gg.drawString(font, title, left + (PANEL_W - font.width(title)) / 2, top + 12, 0xFFFFFFFF, false);

        String ticketLine = "§9北大西洋公约 " + result.alphaTickets() + " §7- §c" + result.bravoTickets() + " 无邦军团";
        gg.drawString(font, ticketLine, left + (PANEL_W - font.width(ticketLine)) / 2, top + 30, 0xFFFFFFFF, false);

        String mine = "§f你的战绩  §a" + result.myKills() + "§7 / §c" + result.myDeaths();
        gg.drawString(font, mine, left + 18, top + 48, 0xFFFFFFFF, false);

        if (!result.topCapturer().isBlank()) {
            String cap = "§7占点王  §e" + result.topCapturer() + " §7" + result.topCapturerTime() + "秒";
            gg.drawString(font, cap, left + 18, top + 64, 0xFFFFFFFF, false);
        }
        if (!result.bestSquad().isBlank()) {
            String sq = "§7最佳  §e" + result.bestSquad() + " §7" + result.bestSquadKills() + "杀";
            gg.drawString(font, sq, left + 18, top + 80, 0xFFFFFFFF, false);
        }

        int tblY = result.topCapturer().isBlank() ? 84 : (result.bestSquad().isBlank() ? 84 : 100);
        gg.fill(left + 14, top + tblY, left + PANEL_W - 14, top + tblY + 1, PixelTheme.BEVEL_SHADOW);
        gg.drawString(font, "击杀榜", left + 18, top + tblY + 10, PixelTheme.TEXT, false);
        gg.drawString(font, "K", left + PANEL_W - 72, top + tblY + 10, PixelTheme.TEXT_DIM, false);
        gg.drawString(font, "D", left + PANEL_W - 46, top + tblY + 10, PixelTheme.TEXT_DIM, false);

        List<TabEntryDto> entries = result.leaderboard();
        int rows = Math.min(8, entries.size());
        int y = top + tblY + 26;
        for (int i = 0; i < rows; i++) {
            TabEntryDto e = entries.get(i);
            int color = e.faction() == 1 ? 0xFFE7654E : 0xFF57C7FF;
            String rank = (i + 1) + ".";
            gg.drawString(font, rank, left + 18, y, PixelTheme.TEXT_DIM, false);
            String name = trim(e.name(), 170);
            gg.drawString(font, name, left + 42, y, color, false);
            gg.drawString(font, Integer.toString(e.kills()), left + PANEL_W - 72, y, PixelTheme.TEXT, false);
            gg.drawString(font, Integer.toString(e.deaths()), left + PANEL_W - 46, y, PixelTheme.TEXT, false);
            y += 13;
        }

        super.render(gg, mouseX, mouseY, partialTick);
    }

    private String winnerTitle() {
        if (result.winnerFaction() == 1) {
            return result.myFaction() == 1 ? "§a§l胜利" : "§c§l失败";
        }
        if (result.winnerFaction() == 2) {
            return result.myFaction() == 2 ? "§a§l胜利" : "§c§l失败";
        }
        return "§7§l平局";
    }

    private String trim(String text, int maxW) {
        if (font.width(text) <= maxW) {
            return text;
        }
        String out = text;
        while (out.length() > 1 && font.width(out + "…") > maxW) {
            out = out.substring(0, out.length() - 1);
        }
        return out + "…";
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
