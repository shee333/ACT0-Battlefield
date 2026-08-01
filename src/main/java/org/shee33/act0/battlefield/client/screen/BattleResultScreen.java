package org.shee33.act0.battlefield.client.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.shee33.act0.battlefield.network.BattleResultDto;
import org.shee33.act0.battlefield.network.TabEntryDto;

import java.util.List;

/**
 * FlatTheme (BF2042 style) battle result screen.
 *
 * <p>Dark overlay panel with faction-color accents, two-column stats layout,
 * and clean typography on an 8px grid. No bevels, no gradients, no PixelTheme.
 */
public final class BattleResultScreen extends Screen {

    // --- FlatTheme BF2042 palette ---
    /** Panel background: #0A0A0A at ~80% alpha. */
    private static final int PANEL_FILL = 0xCC0A0A0A;
    /** Panel border: #3A3A3A at 40% alpha. */
    private static final int PANEL_BORDER = 0x663A3A3A;
    /** Section divider: #3A3A3A at 30% alpha. */
    private static final int DIVIDER = 0x4D3A3A3A;

    /** ALPHA faction blue: #5787C7. */
    private static final int ALPHA_COLOR = 0xFF5787C7;
    /** BRAVO faction red: #C75757. */
    private static final int BRAVO_COLOR = 0xFFC75757;

    /** Primary body text: light grey. */
    private static final int TEXT_PRIMARY = 0xFFE0E0E0;
    /** Secondary / dim text. */
    private static final int TEXT_SECONDARY = 0xFF909090;
    /** Header / white text. */
    private static final int TEXT_HEADER = 0xFFFFFFFF;
    /** Award accent: orange. */
    private static final int TEXT_AWARD = 0xFFFF8C00;

    // --- Layout (8px grid aligned) ---
    private static final int PANEL_W = 360;
    private static final int PANEL_H = 280;
    private static final int PAD = 16; // panel padding
    private static final int COL_W = (PANEL_W - PAD * 2 - 16) / 2; // 156px per column
    private static final int COL_RIGHT_X_OFFSET = COL_W + 16; // offset from left edge

    private final BattleResultDto result;
    private int left;
    private int top;

    public BattleResultScreen(final BattleResultDto result) {
        super(Component.literal("战报"));
        this.result = result;
    }

    @Override
    protected void init() {
        left = (width - PANEL_W) / 2;
        top = (height - PANEL_H) / 2;
        // Close button centered at bottom
        addRenderableWidget(Button.builder(Component.literal("关闭"), b -> onClose())
                .bounds(left + PANEL_W / 2 - 40, top + PANEL_H - 32, 80, 20).build());
    }

    // ============================================================
    // Main render
    // ============================================================

    @Override
    public void render(final GuiGraphics gg, final int mouseX, final int mouseY,
                       final float partialTick) {
        renderBackground(gg);
        drawPanel(gg);
        drawTitle(gg);
        drawTickets(gg);
        drawDivider(gg, top + 72);
        drawColumns(gg);
        super.render(gg, mouseX, mouseY, partialTick);
    }

    // ============================================================
    // Panel
    // ============================================================

    /** Flat panel: solid fill + 1px border, no bevels. */
    private void drawPanel(final GuiGraphics gg) {
        int x2 = left + PANEL_W;
        int y2 = top + PANEL_H;
        gg.fill(left, top, x2, y2, PANEL_FILL);
        // 1px border on all four edges
        gg.fill(left, top, x2, top + 1, PANEL_BORDER);
        gg.fill(left, y2 - 1, x2, y2, PANEL_BORDER);
        gg.fill(left, top, left + 1, y2, PANEL_BORDER);
        gg.fill(x2 - 1, top, x2, y2, PANEL_BORDER);
    }

    // ============================================================
    // Title section
    // ============================================================

    private void drawTitle(final GuiGraphics gg) {
        int cx = left + PANEL_W / 2;

        // "战报" label
        String label = "战 报";
        gg.drawString(font, label, cx - font.width(label) / 2, top + 16,
                TEXT_SECONDARY, false);

        // Victory / Defeat / Draw
        String outcome;
        int outcomeColor;
        int winnerFaction = result.winnerFaction();
        if (winnerFaction == 1) {
            outcome = result.myFaction() == 1 ? "胜利" : "失败";
            outcomeColor = result.myFaction() == 1 ? ALPHA_COLOR : BRAVO_COLOR;
        } else if (winnerFaction == 2) {
            outcome = result.myFaction() == 2 ? "胜利" : "失败";
            outcomeColor = result.myFaction() == 2 ? BRAVO_COLOR : ALPHA_COLOR;
        } else {
            outcome = "平局";
            outcomeColor = TEXT_SECONDARY;
        }
        gg.drawString(font, outcome, cx - font.width(outcome) / 2, top + 32,
                outcomeColor, false);

        // 2px accent bar below title
        int barColor = winnerFaction == 1 ? ALPHA_COLOR
                : winnerFaction == 2 ? BRAVO_COLOR : TEXT_SECONDARY;
        int barW = 80;
        gg.fill(cx - barW / 2, top + 48, cx + barW / 2, top + 50, barColor);
    }

    // ============================================================
    // Tickets banner
    // ============================================================

    private void drawTickets(final GuiGraphics gg) {
        int y = top + 56;
        int cx = left + PANEL_W / 2;

        // Faction names + score on one line
        String alphaName = "北大西洋公约";
        String bravoName = "无邦军团";
        String score = result.alphaTickets() + " - " + result.bravoTickets();

        gg.drawString(font, alphaName, left + PAD, y, ALPHA_COLOR, false);
        gg.drawString(font, score, cx - font.width(score) / 2, y, TEXT_HEADER, false);
        gg.drawString(font, bravoName, left + PANEL_W - PAD - font.width(bravoName), y,
                BRAVO_COLOR, false);

        // Ticket ratio bar (4px tall)
        int total = result.alphaTickets() + result.bravoTickets();
        if (total > 0) {
            int barY = y + 14;
            int barW = PANEL_W - PAD * 2;
            int alphaW = Math.max(4, (int) ((float) result.alphaTickets() / total * barW));
            int bravoW = barW - alphaW;

            gg.fill(left + PAD, barY, left + PAD + alphaW, barY + 4, ALPHA_COLOR);
            gg.fill(left + PAD + alphaW, barY, left + PAD + barW, barY + 4, BRAVO_COLOR);
        }
    }

    // ============================================================
    // Two-column content
    // ============================================================

    private void drawColumns(final GuiGraphics gg) {
        int colTop = top + 80;
        int lx = left + PAD;
        int rx = lx + COL_RIGHT_X_OFFSET;

        drawPersonalColumn(gg, lx, colTop);
        drawAwardsColumn(gg, rx, colTop);
    }

    // --- Left: personal stats + leaderboard ---

    private void drawPersonalColumn(final GuiGraphics gg, final int x, int y) {
        // Section header
        gg.drawString(font, "个人战绩", x, y, TEXT_HEADER, false);
        y += 16;

        // K / D / K/D ratio
        gg.drawString(font, "击杀  " + result.myKills(), x, y, TEXT_PRIMARY, false);
        gg.drawString(font, "阵亡  " + result.myDeaths(), x, y + 14, TEXT_PRIMARY, false);

        String kd = result.myDeaths() > 0
                ? String.format("K/D  %.1f", (float) result.myKills() / result.myDeaths())
                : "K/D  " + result.myKills();
        gg.drawString(font, kd, x, y + 28, TEXT_AWARD, false);

        y += 48;
        drawDivider(gg, y);
        y += 8;

        // Leaderboard header
        gg.drawString(font, "击杀榜", x, y, TEXT_HEADER, false);
        y += 16;
        // Column labels
        gg.drawString(font, "K", x + COL_W - 48, y, TEXT_SECONDARY, false);
        gg.drawString(font, "D", x + COL_W - 24, y, TEXT_SECONDARY, false);
        y += 12;

        List<TabEntryDto> entries = result.leaderboard();
        int maxRows = Math.min(7, entries.size());
        for (int i = 0; i < maxRows; i++) {
            TabEntryDto e = entries.get(i);
            int nameColor = e.faction() == 1 ? ALPHA_COLOR : BRAVO_COLOR;

            // Rank
            gg.drawString(font, (i + 1) + ".", x, y, TEXT_SECONDARY, false);
            // Name (trimmed to column width)
            String name = trim(e.name(), COL_W - 72);
            gg.drawString(font, name, x + 20, y, nameColor, false);
            // Kills
            String k = Integer.toString(e.kills());
            gg.drawString(font, k, x + COL_W - 48 - font.width(k), y, TEXT_PRIMARY, false);
            // Deaths
            String d = Integer.toString(e.deaths());
            gg.drawString(font, d, x + COL_W - 24 - font.width(d), y, TEXT_PRIMARY, false);

            y += 13;
        }
    }

    // --- Right: awards ---

    private void drawAwardsColumn(final GuiGraphics gg, final int x, int y) {
        gg.drawString(font, "最佳表现", x, y, TEXT_HEADER, false);
        y += 20;

        // Match duration (both modes)
        gg.drawString(font, "对局时长", x, y, TEXT_AWARD, false);
        gg.drawString(font, formatDuration(result.matchSeconds()), x, y + 14, TEXT_PRIMARY, false);
        y += 28;

        // Sector advance (Breakthrough only; totalSectors == 0 for Conquest)
        if (result.totalSectors() > 0) {
            gg.drawString(font, "推进扇区", x, y, TEXT_AWARD, false);
            gg.drawString(font, result.sectorsCaptured() + " / " + result.totalSectors(), x, y + 14,
                    TEXT_PRIMARY, false);
            y += 28;
        }

        // Top capturer
        if (!result.topCapturer().isBlank()) {
            gg.drawString(font, "占点王", x, y, TEXT_AWARD, false);
            String name = trim(result.topCapturer(), COL_W);
            gg.drawString(font, name, x, y + 14, TEXT_PRIMARY, false);
            gg.drawString(font, result.topCapturerTime() + " 秒", x, y + 28,
                    TEXT_SECONDARY, false);
            y += 48;
        }

        // Best squad
        if (!result.bestSquad().isBlank()) {
            gg.drawString(font, "最佳小队", x, y, TEXT_AWARD, false);
            String name = trim(result.bestSquad(), COL_W);
            gg.drawString(font, name, x, y + 14, TEXT_PRIMARY, false);
            gg.drawString(font, result.bestSquadKills() + " 击杀", x, y + 28,
                    TEXT_SECONDARY, false);
        }
    }

    // ============================================================
    // Helpers
    // ============================================================

    private void drawDivider(final GuiGraphics gg, final int y) {
        gg.fill(left + PAD, y, left + PANEL_W - PAD, y + 1, DIVIDER);
    }

    /** Format seconds as m:ss for the match-duration display. */
    private static String formatDuration(final int totalSeconds) {
        int s = Math.max(0, totalSeconds);
        return (s / 60) + ":" + String.format("%02d", s % 60);
    }

    /** Trim text to fit within maxW pixels, appending "…" if truncated. */
    private String trim(final String text, final int maxW) {
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
