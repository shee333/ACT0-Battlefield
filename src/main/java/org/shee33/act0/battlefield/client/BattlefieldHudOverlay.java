package org.shee33.act0.battlefield.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.shee33.act0.battlefield.Act0Battlefield;
import org.shee33.act0.battlefield.network.BattleHudDto;
import org.shee33.act0.battlefield.network.ControlPointHudDto;
import org.shee33.act0.battlefield.network.SquadMateHudDto;

import java.util.List;

/**
 * BF 风格大战场 HUD：顶部票数条 + 据点图标/进度条 + 左下小队队友信息。
 *
 * <p>不使用原版计分板侧边栏，因此不会出现右侧红色数字。颜色按“自己阵营=蓝、敌方=红、中立=灰”渲染，
 * 更贴近战地系列：玩家无论在红队还是蓝队，友军都显示为蓝色、敌军显示为红色。
 */
@Mod.EventBusSubscriber(modid = Act0Battlefield.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class BattlefieldHudOverlay {

    private static final int BLUE = 0xFF57C7FF;
    private static final int BLUE_DIM = 0x8857C7FF;
    private static final int RED = 0xFFE7654E;
    private static final int RED_DIM = 0x88E7654E;
    private static final int GREY = 0xFF9EA7AA;
    private static final int BG = 0x78000000;
    private static final int BG_DARK = 0xA0000000;
    private static final int WHITE = 0xFFE8F4F8;
    private static final int TEXT_DIM = 0xFFB0BEC5;
    private static final int GREEN = 0xFF8EEA5A;

    private static final ResourceLocation SCORE_BG = texture("scores/progress.png");
    private static final ResourceLocation SCORE_BLUE = texture("scores/progress_allies.png");
    private static final ResourceLocation SCORE_RED = texture("scores/progress_axis.png");
    private static final ResourceLocation POINT_FRIENDLY = texture("capturepoint/allies.png");
    private static final ResourceLocation POINT_ENEMY = texture("capturepoint/axis.png");
    private static final ResourceLocation POINT_NEUTRAL = texture("misc/capturepoint.png");
    private static final ResourceLocation POINT_OVERRUN = texture("misc/capturepoint_overrun.png");
    private static final ResourceLocation SQUAD_DOT = texture("compass/waypoint_pp_player.png");
    private static final ResourceLocation CAPTURE_BAR_BG = texture("capturepoint/progress.png");
    private static final ResourceLocation CAPTURE_BAR_BLUE = texture("capturepoint/progress_allies.png");
    private static final ResourceLocation CAPTURE_BAR_RED = texture("capturepoint/progress_axis.png");

    private BattlefieldHudOverlay() {
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        BattleHudDto hud = ClientBattleHud.hud();
        if (!ClientBattleHud.isShown() || hud == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) {
            return;
        }
        GuiGraphics gg = event.getGuiGraphics();
        Font font = mc.font;

        renderTopHud(gg, font, hud);
        renderCaptureFocus(gg, font, hud);
        renderSquadPanel(gg, font, hud.squad());
        renderKillFeed(gg, font, hud.myFaction());
    }

    private static void renderTopHud(GuiGraphics gg, Font font, BattleHudDto hud) {
        int screenW = gg.guiWidth();
        int center = screenW / 2;
        int top = 7;

        int max = Math.max(1, hud.maxTickets());
        int alphaColor = factionColor(1, hud.myFaction());
        int bravoColor = factionColor(2, hud.myFaction());

        // 左票数 + 左进度条（BlockFront 扁平条资产）
        String alphaText = "[ " + hud.alphaTickets() + " ]";
        int leftTextX = center - 154;
        gg.drawString(font, alphaText, leftTextX, top + 2, alphaColor, false);
        drawTexturedScoreBar(gg, center - 92, top + 4, hud.alphaTickets(), max, true,
            scoreTexture(1, hud.myFaction()));

        // 右票数 + 右进度条（BlockFront 扁平条资产）
        drawTexturedScoreBar(gg, center + 34, top + 4, hud.bravoTickets(), max, false,
            scoreTexture(2, hud.myFaction()));
        String bravoText = "[ " + hud.bravoTickets() + " ]";
        gg.drawString(font, bravoText, center + 104, top + 2, bravoColor, false);

        // 中央据点图标（A/B/C...）
        renderPointRow(gg, font, hud.points(), hud.myFaction(), center, top + 23);
    }

    private static void drawTexturedScoreBar(GuiGraphics gg, int x, int y, int value, int max,
                                             boolean fromRight, ResourceLocation fillTex) {
        int w = 58;
        int h = 9;
        gg.blit(SCORE_BG, x, y, 0, 0, w, h, w, h);
        int fill = Math.max(0, Math.min(w, Math.round(w * (value / (float) max))));
        if (fill <= 0) {
            return;
        }
        if (fromRight) {
            gg.enableScissor(x + w - fill, y, x + w, y + h);
            gg.blit(fillTex, x, y, 0, 0, w, h, w, h);
            gg.disableScissor();
        } else {
            gg.enableScissor(x, y, x + fill, y + h);
            gg.blit(fillTex, x, y, 0, 0, w, h, w, h);
            gg.disableScissor();
        }
    }

    private static void renderPointRow(GuiGraphics gg, Font font, List<ControlPointHudDto> points, int myFaction,
                                       int center, int y) {
        if (points.isEmpty()) {
            return;
        }
        int icon = 16;
        int gap = 8;
        int totalW = points.size() * icon + (points.size() - 1) * gap;
        int x = center - totalW / 2;
        for (ControlPointHudDto p : points) {
            int pressureColor = factionColor(p.pressure(), myFaction);

            // 据点图标使用授权导入的 BlockFront 扁平资产。
            gg.blit(pointTexture(p, myFaction), x, y, 0, 0, icon, icon, icon, icon);
            String label = p.name();
            if (label.length() > 1) {
                label = label.substring(0, 1);
            }
            gg.drawString(font, label, x + icon / 2 - font.width(label) / 2, y + 5, WHITE, true);

            // 据点进度：中立/被中和/反占都显示进度条，颜色代表当前推进方向。
            gg.fill(x, y + icon + 3, x + icon, y + icon + 5, 0x66000000);
            int fill = Math.max(0, Math.min(icon, Math.round(icon * (p.progress() / 100.0f))));
            if (fill > 0 && p.pressure() != 0) {
                gg.fill(x, y + icon + 3, x + fill, y + icon + 5, pressureColor);
            }
            x += icon + gap;
        }
    }

    private static void renderSquadPanel(GuiGraphics gg, Font font, List<SquadMateHudDto> squad) {
        if (squad.isEmpty()) {
            return;
        }
        int rows = Math.min(5, squad.size());
        int panelW = 122;
        int rowH = 16;
        int panelH = 18 + rows * rowH;
        int x = 8;
        int y = gg.guiHeight() - panelH - 32;

        gg.fill(x, y, x + panelW, y + panelH, BG);
        gg.fill(x, y, x + panelW, y + 1, BLUE_DIM);
        gg.drawString(font, "小队", x + 6, y + 5, BLUE, false);

        int cy = y + 18;
        for (int i = 0; i < rows; i++) {
            SquadMateHudDto mate = squad.get(i);
            int dot = mate.alive() ? GREEN : RED;
            if (mate.alive()) {
                gg.blit(SQUAD_DOT, x + 4, cy + 3, 0, 0, 7, 7, 7, 7);
            } else {
                gg.fill(x + 6, cy + 5, x + 9, cy + 8, dot);
            }
            String name = mate.self() ? "你" : mate.name();
            if (font.width(name) > 72) {
                while (name.length() > 1 && font.width(name + "…") > 72) {
                    name = name.substring(0, name.length() - 1);
                }
                name = name + "…";
            }
            gg.drawString(font, name, x + 14, cy + 3, mate.self() ? WHITE : TEXT_DIM, false);

            // 血量条
            int bx = x + panelW - 34;
            int by = cy + 5;
            gg.fill(bx, by, bx + 26, by + 4, 0x66000000);
            int fill = Math.max(0, Math.min(26, Math.round(26 * (mate.healthPct() / 100.0f))));
            gg.fill(bx, by, bx + fill, by + 4, mate.alive() ? GREEN : RED);
            cy += rowH;
        }
    }

    private static void renderKillFeed(GuiGraphics gg, Font font, int myFaction) {
        int xRight = gg.guiWidth() - 12;
        int y = 34;
        for (ClientKillFeed.Entry e : ClientKillFeed.entries()) {
            int killerColor = factionColor(e.killerFaction(), myFaction);
            int victimColor = factionColor(e.victimFaction(), myFaction);
            String mid = "  击杀  ";
            int w = font.width(e.killer()) + font.width(mid) + font.width(e.victim()) + 10;
            int x = xRight - w;
            gg.fill(x - 2, y - 2, xRight + 2, y + 10, 0x66000000);
            int cx = x + 3;
            gg.drawString(font, e.killer(), cx, y, killerColor, false);
            cx += font.width(e.killer());
            gg.drawString(font, mid, cx, y, TEXT_DIM, false);
            cx += font.width(mid);
            gg.drawString(font, e.victim(), cx, y, victimColor, false);
            y += 12;
        }
    }

    private static void renderCaptureFocus(GuiGraphics gg, Font font, BattleHudDto hud) {
        if (hud.focusState() == 0 || hud.focusName().isBlank()) {
            return;
        }
        int center = gg.guiWidth() / 2;
        int y = Math.max(54, (int) (gg.guiHeight() * 0.67f));
        int panelW = 154;
        int panelH = 34;
        int x = center - panelW / 2;
        int factionColor = hud.focusState() == 3 ? GREY : factionColor(hud.focusFaction(), hud.myFaction());

        gg.fill(x, y, x + panelW, y + panelH, 0x77000000);
        gg.fill(x, y, x + panelW, y + 1, factionColor);

        String title = focusTitle(hud.focusState()) + " " + hud.focusName();
        gg.drawString(font, title, center - font.width(title) / 2, y + 5, factionColor, false);

        int barX = center - 59;
        int barY = y + 20;
        drawCaptureProgressBar(gg, barX, barY, hud.focusProgress(),
                hud.focusState() == 3 ? null : captureBarTexture(hud.focusFaction(), hud.myFaction()));
    }

    private static void drawCaptureProgressBar(GuiGraphics gg, int x, int y, int progress, ResourceLocation fillTex) {
        int w = 118;
        int h = 8;
        gg.blit(CAPTURE_BAR_BG, x, y, 0, 0, w, h, w, h);
        int fill = Math.max(0, Math.min(w, Math.round(w * (progress / 100.0f))));
        if (fill > 0 && fillTex != null) {
            gg.enableScissor(x, y, x + fill, y + h);
            gg.blit(fillTex, x, y, 0, 0, w, h, w, h);
            gg.disableScissor();
        } else if (fill > 0) {
            gg.fill(x, y, x + fill, y + h, 0xAA9EA7AA);
        }
    }

    private static int factionColor(int faction, int mine) {
        if (faction == 0) {
            return GREY;
        }
        if (mine != 0) {
            return faction == mine ? BLUE : RED;
        }
        return faction == 1 ? BLUE : RED;
    }

    private static int alphaDim(int mine, int faction) {
        int color = factionColor(faction, mine);
        return color == BLUE ? BLUE_DIM : RED_DIM;
    }

    private static ResourceLocation scoreTexture(int faction, int mine) {
        return factionColor(faction, mine) == BLUE ? SCORE_BLUE : SCORE_RED;
    }

    private static ResourceLocation captureBarTexture(int faction, int mine) {
        return factionColor(faction, mine) == BLUE ? CAPTURE_BAR_BLUE : CAPTURE_BAR_RED;
    }

    private static String focusTitle(int state) {
        return switch (state) {
            case 1 -> "正在占领";
            case 2 -> "正在防守";
            case 3 -> "争夺中";
            default -> "";
        };
    }

    private static ResourceLocation pointTexture(ControlPointHudDto point, int mine) {
        if (point.owner() == 0) {
            return point.pressure() == 0 ? POINT_NEUTRAL : POINT_OVERRUN;
        }
        return factionColor(point.owner(), mine) == BLUE ? POINT_FRIENDLY : POINT_ENEMY;
    }

    @SuppressWarnings("removal")
    private static ResourceLocation texture(String path) {
        return new ResourceLocation(Act0Battlefield.MODID, "textures/gui/hud/" + path);
    }
}
