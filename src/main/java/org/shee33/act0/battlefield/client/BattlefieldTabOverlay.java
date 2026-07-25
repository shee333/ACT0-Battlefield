package org.shee33.act0.battlefield.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.shee33.act0.battlefield.Act0Battlefield;
import org.shee33.act0.battlefield.network.BattleTabDto;
import org.shee33.act0.battlefield.network.TabEntryDto;

import java.util.List;

/**
 * 自定义 TAB 战绩面板：替代原版玩家列表，展示双方阵营、票数、K/D、延迟与部署状态。
 */
@Mod.EventBusSubscriber(modid = Act0Battlefield.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class BattlefieldTabOverlay {

    // ---- 扁平化配色 ----
    private static final int BLUE = 0xFF4A90D9;
    private static final int RED = 0xFFD94A4A;
    private static final int WHITE = 0xFFEEEEEE;
    private static final int DIM = 0xFFA0A8B0;
    private static final int BG = 0xCC0A0C10;
    private static final int PANEL = 0xBB141820;

    private BattlefieldTabOverlay() {
    }

    @SubscribeEvent
    public static void onRenderOverlayPre(RenderGuiOverlayEvent.Pre event) {
        if (ClientBattleTab.isShown() && event.getOverlay() == VanillaGuiOverlay.PLAYER_LIST.type()) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        BattleTabDto tab = ClientBattleTab.tab();
        Minecraft mc = Minecraft.getInstance();
        if (!ClientBattleTab.isShown() || tab == null || mc.player == null || !mc.options.keyPlayerList.isDown()) {
            return;
        }
        GuiGraphics gg = event.getGuiGraphics();
        Font font = mc.font;

        int w = Math.min(520, gg.guiWidth() - 40);
        int x = (gg.guiWidth() - w) / 2;
        int y = 52; // 下移避免遮挡据点和进度条
        int colGap = 8;
        int colW = (w - colGap) / 2;
        int rows = Math.max(tab.alpha().size(), tab.bravo().size());
        int h = 40 + Math.max(6, rows) * 13 + 16;

        gg.fill(x - 6, y - 6, x + w + 6, y + h + 6, BG);
        String title = "大战场";
        gg.drawString(font, title, x + w / 2 - font.width(title) / 2, y, WHITE, false);
        String tickets = "§b" + tab.alphaTickets() + " §7- §c" + tab.bravoTickets();
        gg.drawString(font, tickets, x + w / 2 - font.width(tickets) / 2, y + 12, WHITE, false);

        renderTeam(gg, font, x, y + 30, colW, "北大西洋公约", BLUE, tab.alpha(), tab.myFaction() == 1);
        renderTeam(gg, font, x + colW + colGap, y + 30, colW, "无邦军团", RED, tab.bravo(), tab.myFaction() == 2);
    }

    private static void renderTeam(GuiGraphics gg, Font font, int x, int y, int w, String name, int color,
                                   List<TabEntryDto> entries, boolean mine) {
        int h = 22 + Math.max(6, entries.size()) * 13;
        gg.fill(x, y, x + w, y + h, PANEL);
        gg.fill(x, y, x + w, y + 2, color);
        String header = (mine ? "● " : "") + name + "  " + entries.size() + "人";
        gg.drawString(font, header, x + 6, y + 7, color, false);
        gg.drawString(font, "K", x + w - 68, y + 7, DIM, false);
        gg.drawString(font, "D", x + w - 48, y + 7, DIM, false);
        gg.drawString(font, "ms", x + w - 30, y + 7, DIM, false);

        int cy = y + 22;
        for (TabEntryDto e : entries) {
            String sq = e.squad() > 0 ? "§7" + e.squad() + " " : "";
            String n = trim(font, sq + e.name(), w - 94);
            int nameColor = e.state() == 0 ? WHITE : DIM;
            String state = e.state() == 3 ? " §4倒地" : (e.state() == 1 ? " §8部署" : (e.state() == 2 ? " §8离线" : ""));
            gg.drawString(font, n + state, x + 6, cy + 2, nameColor, false);
            gg.drawString(font, Integer.toString(e.kills()), x + w - 68, cy + 2, WHITE, false);
            gg.drawString(font, Integer.toString(e.deaths()), x + w - 48, cy + 2, WHITE, false);
            gg.drawString(font, e.ping() < 0 ? "-" : Integer.toString(e.ping()), x + w - 30, cy + 2, DIM, false);
            cy += 13;
        }
    }

    private static String trim(Font font, String s, int maxW) {
        if (font.width(s) <= maxW) {
            return s;
        }
        String out = s;
        while (out.length() > 1 && font.width(out + "…") > maxW) {
            out = out.substring(0, out.length() - 1);
        }
        return out + "…";
    }
}
