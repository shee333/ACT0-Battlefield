package org.shee33.act0.battlefield.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.shee33.act0.battlefield.Act0Battlefield;
import org.shee33.act0.battlefield.network.BreakthroughHudDto;
import org.shee33.act0.battlefield.network.BreakthroughPointDto;

import java.util.List;

/**
 * BF6-style Breakthrough mode HUD overlay.
 *
 * <p>Layout (top to bottom):
 * <ol>
 *   <li>Top bar — attacker ticket progress on the left, sector info on the right,
 *       phase/result overlay in center.</li>
 *   <li>Point row — horizontal card list of capture points with name and progress bar
 *       (blue for attackers, red for defenders, grey for locked).</li>
 * </ol>
 *
 * <p>FlatTheme spec:
 * <ul>
 *   <li>Background: #101418 at ~70% alpha</li>
 *   <li>Faction: attackers #5787C7, defenders #C75757</li>
 *   <li>8px grid, 1px borders, max 2px rounded corners</li>
 *   <li>No gradients, no bounce/scale/spin animations</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = Act0Battlefield.MODID, value = Dist.CLIENT)
public final class BreakthroughHudOverlay {

    // ---- FlatTheme BF6 colors ----
    /** Attacker faction blue (#5787C7). */
    private static final int ATTACKER_BLUE = 0xFF5787C7;
    /** Defender faction red (#C75757). */
    private static final int DEFENDER_RED = 0xFFC75757;
    /** Neutral / locked grey. */
    private static final int NEUTRAL_GREY = 0xFF8C9196;
    /** Panel background (#101418 at 70% alpha). */
    private static final int BG = 0xB2101418;
    /** Darker background for bar backings. */
    private static final int BG_DARK = 0xAA101418;
    /** White text for primary info. */
    private static final int WHITE = 0xFFEEEEEE;
    /** Dim text for secondary info. */
    private static final int TEXT_DIM = 0xFFA0A8B0;

    // ---- layout constants (8px grid) ----
    private static final int TOP_BAR_Y = 7;
    private static final int TOP_BAR_H = 22;
    private static final int POINT_ROW_Y = TOP_BAR_Y + TOP_BAR_H + 8;
    private static final int POINT_ITEM_W = 80;
    private static final int POINT_ITEM_H = 28;
    private static final int POINT_GAP = 8;
    private static final int POINT_BAR_H = 6;

    private BreakthroughHudOverlay() {
    }

    // ---- event subscriber ----

    /**
     * Main render entry point. Delegates to sub-renderers after validating
     * HUD visibility and client state.
     */
    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) {
            return;
        }
        BreakthroughHudDto hud = ClientBreakthroughHud.hud();
        if (hud == null || !hud.show()) {
            return;
        }
        GuiGraphics gg = event.getGuiGraphics();
        Font font = mc.font;

        renderTopBar(gg, font, hud);
        renderPoints(gg, font, hud);
        renderCapturePointBanner(gg, font, hud);
        BattlefieldHudOverlay.renderDeploySpawnFx(gg, font);
        BattlefieldHudOverlay.renderDownedSelfFeedback(gg, font);
    }

    // ---- capture point banner (reuses BattlefieldHudOverlay's shared render core) ----

    /**
     * 据点状态边沿事件横幅：复用 {@link BattlefieldHudOverlay#renderCapturePointBannerCore}
     * 的动效/位置/配色基线，仅替换文本与配色来源（攻防阵营色而非"我方/敌方"相对色，
     * 因为突破模式 ALPHA 恒为进攻方、BRAVO 恒为防守方，无需按 viewer 阵营做相对着色）。
     *
     * <p>不含小地图脉冲反馈：突破模式没有世界坐标小地图 overlay（{@code BattlefieldMinimapOverlay}
     * 专属 Conquest），补建一套小地图属独立工程，超出本次移植范围。
     */
    private static void renderCapturePointBanner(GuiGraphics gg, Font font, BreakthroughHudDto hud) {
        ClientCapturePointEvent.Active active = ClientCapturePointEvent.poll();
        if (active == null) {
            return;
        }
        String text = pointNameFor(hud, active.pointId()) + " · " + BattlefieldHudOverlay.capturePointVerb(active.kind());
        int color = bannerFactionColor(active.factionCode());
        BattlefieldHudOverlay.renderCapturePointBannerCore(gg, font, active, text, color);
    }

    private static String pointNameFor(BreakthroughHudDto hud, int pointId) {
        for (BreakthroughPointDto p : hud.points()) {
            if (p.pointId() == pointId) {
                return p.name();
            }
        }
        return "";
    }

    private static int bannerFactionColor(int factionCode) {
        if (factionCode == 1) {
            return ATTACKER_BLUE;
        }
        if (factionCode == 2) {
            return DEFENDER_RED;
        }
        return NEUTRAL_GREY;
    }

    // ---- top bar: attacker tickets + sector info ----

    /**
     * Renders the top info bar.
     * <ul>
     *   <li>Left: "进攻方" label + flat progress bar + "178/300" ticket text</li>
     *   <li>Right: "Sector 2/4" sector indicator</li>
     *   <li>Center: phase overlay (countdown / result)</li>
     * </ul>
     */
    private static void renderTopBar(GuiGraphics gg, Font font, BreakthroughHudDto hud) {
        int screenW = gg.guiWidth();

        // --- left section: attacker tickets ---
        int leftX = 8;
        String label = "进攻方";
        int labelW = font.width(label);
        gg.drawString(font, label, leftX, TOP_BAR_Y + 4, ATTACKER_BLUE, false);

        int barX = leftX + labelW + 8;
        int barW = 160;
        int barY = TOP_BAR_Y + 5;
        String ticketText = hud.attackerTickets() + "/" + hud.maxTickets();

        drawFlatProgressBar(gg, barX, barY, barW, 8,
                hud.attackerTickets(), hud.maxTickets(), ATTACKER_BLUE);

        int textX = barX + barW + 6;
        gg.drawString(font, ticketText, textX, TOP_BAR_Y + 4, WHITE, false);

        // --- right section: sector indicator ---
        String sectorText;
        if (hud.totalSectors() > 0) {
            sectorText = "Sector " + hud.currentSectorId() + "/" + hud.totalSectors();
        } else {
            sectorText = "Sector —";
        }
        int sectorW = font.width(sectorText);
        gg.drawString(font, sectorText, screenW - sectorW - 8, TOP_BAR_Y + 4, TEXT_DIM, false);

        // --- center: phase / winner overlay ---
        if (hud.phase() == 0) {
            String countdown = "准备阶段";
            drawTextCentered(gg, font, countdown, screenW / 2, TOP_BAR_Y + 4, WHITE);
        } else if (hud.phase() == 2) {
            String result;
            int resultColor;
            if (hud.winner() == 1) {
                result = "进攻方胜利";
                resultColor = ATTACKER_BLUE;
            } else if (hud.winner() == 2) {
                result = "防守方胜利";
                resultColor = DEFENDER_RED;
            } else {
                result = "平局";
                resultColor = TEXT_DIM;
            }
            drawTextCentered(gg, font, result, screenW / 2, TOP_BAR_Y + 4, resultColor);
        }
    }

    // ---- point row: capture point cards ----

    /**
     * Renders capture points in a centered horizontal row.
     * Each point is rendered as a small card with name and progress bar.
     */
    private static void renderPoints(GuiGraphics gg, Font font, BreakthroughHudDto hud) {
        List<BreakthroughPointDto> points = hud.points();
        if (points.isEmpty()) {
            return;
        }
        int screenW = gg.guiWidth();
        int totalW = points.size() * POINT_ITEM_W + (points.size() - 1) * POINT_GAP;
        int x = screenW / 2 - totalW / 2;
        int y = POINT_ROW_Y;

        for (BreakthroughPointDto p : points) {
            renderSinglePoint(gg, font, p, x, y);
            x += POINT_ITEM_W + POINT_GAP;
        }
    }

    /**
     * Renders a single capture point card.
     * <ul>
     *   <li>Active point: faction-colored name, top accent border, progress bar</li>
     *   <li>Locked point: grey name, empty bar, no accent</li>
     * </ul>
     */
    private static void renderSinglePoint(GuiGraphics gg, Font font,
                                          BreakthroughPointDto p, int x, int y) {
        // background card
        gg.fill(x, y, x + POINT_ITEM_W, y + POINT_ITEM_H, BG);

        if (p.locked()) {
            // locked: neutral name, empty bar
            gg.drawString(font, "[" + p.name() + "]", x + 4, y + 3, NEUTRAL_GREY, false);
            gg.drawString(font, "LOCKED", x + 4, y + 12, NEUTRAL_GREY, false);
        } else {
            // determine faction color from owner id
            int fillColor;
            if (p.owner() == 1) {
                fillColor = ATTACKER_BLUE;
            } else if (p.owner() == 2) {
                fillColor = DEFENDER_RED;
            } else {
                fillColor = NEUTRAL_GREY;
            }
            // point name
            gg.drawString(font, p.name(), x + 4, y + 3, fillColor, false);
            // top accent border (1px faction color)
            gg.fill(x, y, x + POINT_ITEM_W, y + 1, fillColor);
            // progress bar
            drawFlatProgressBar(gg, x + 2, y + 15, POINT_ITEM_W - 4, POINT_BAR_H,
                    p.progress(), 100, fillColor);
        }
    }

    // ---- shared drawing helpers ----

    /**
     * Draws a flat progress bar: dark background + solid-color fill.
     * The fill width is proportional to {@code value / max} with a 1px inset
     * on each side.
     */
    private static void drawFlatProgressBar(GuiGraphics gg, int x, int y,
                                            int w, int h, int value, int max, int color) {
        gg.fill(x, y, x + w, y + h, BG_DARK);
        int fill = Math.max(0, Math.min(w - 2,
                Math.round((w - 2) * (value / (float) Math.max(1, max)))));
        if (fill > 0) {
            gg.fill(x + 1, y + 1, x + 1 + fill, y + h - 1, color);
        }
    }

    /**
     * Draws a string centered horizontally at the given Y coordinate.
     */
    private static void drawTextCentered(GuiGraphics gg, Font font, String text,
                                         int centerX, int y, int color) {
        gg.drawString(font, text, centerX - font.width(text) / 2, y, color, false);
    }
}
