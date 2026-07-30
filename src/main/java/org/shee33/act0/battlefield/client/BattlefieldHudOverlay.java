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
import org.shee33.act0.battlefield.network.DownedMateDto;
import org.shee33.act0.battlefield.network.SquadMateHudDto;

import java.util.List;

/**
 * BF 风格大战场 HUD：顶部票数条 + 据点图标/进度条 + 左下小队队友信息。
 *
 * <p>不使用原版计分板侧边栏，因此不会出现右侧红色数字。颜色按战地风格动态渲染：友军=蓝色，敌军=红色。
 */
@Mod.EventBusSubscriber(modid = Act0Battlefield.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class BattlefieldHudOverlay {

    // ---- 扁平化配色 ----
    private static final int BLUE = 0xFF4A90D9;
    private static final int BLUE_DIM = 0x884A90D9;
    private static final int RED = 0xFFD94A4A;
    private static final int RED_DIM = 0x88D94A4A;
    private static final int GREY = 0xFF8C9196;
    private static final int BG = 0x88101418;
    private static final int BG_DARK = 0xAA101418;
    private static final int WHITE = 0xFFEEEEEE;
    private static final int TEXT_DIM = 0xFFA0A8B0;
    private static final int GREEN = 0xFF66CC66;

    private static final ResourceLocation POINT_FRIENDLY = new ResourceLocation(Act0Battlefield.MODID, "textures/gui/hud/capturepoint/allies.png");
    private static final ResourceLocation POINT_ENEMY = new ResourceLocation(Act0Battlefield.MODID, "textures/gui/hud/capturepoint/axis.png");
    private static final ResourceLocation POINT_NEUTRAL = new ResourceLocation(Act0Battlefield.MODID, "textures/gui/hud/misc/capturepoint.png");
    private static final ResourceLocation POINT_OVERRUN = new ResourceLocation(Act0Battlefield.MODID, "textures/gui/hud/misc/capturepoint_overrun.png");
    private static final ResourceLocation SQUAD_DOT = new ResourceLocation(Act0Battlefield.MODID, "textures/gui/hud/compass/waypoint_pp_player.png");
    private static final ResourceLocation CAPTURE_BAR_BLUE = new ResourceLocation(Act0Battlefield.MODID, "textures/gui/hud/capturepoint/progress_allies.png");
    private static final ResourceLocation CAPTURE_BAR_RED = new ResourceLocation(Act0Battlefield.MODID, "textures/gui/hud/capturepoint/progress_axis.png");

    private static String focusName = "";
    private static int focusState;
    private static int focusProgress;
    private static int focusFaction;
    private static long focusStartMs;
    private static long focusLastSeenMs;

    private BattlefieldHudOverlay() {
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) {
            return;
        }
        GuiGraphics gg = event.getGuiGraphics();
        Font font = mc.font;

        BattleHudDto hud = ClientBattleHud.hud();
        if (!ClientBattleHud.isShown() || hud == null) {
            return;
        }

        renderTopHud(gg, font, hud);
        renderCaptureFocus(gg, font, hud);
        renderSquadPanel(gg, font, hud.squad());
        renderKillFeed(gg, font, hud.myFaction());
        renderReviveProgress(gg, font, hud);
        renderDownedMates(gg, font, hud);
    }

    private static void renderHitFeedback(GuiGraphics gg, Font font) {
        if (!ClientHitFeedback.active()) {
            return;
        }
        long age = System.currentTimeMillis() - ClientHitFeedback.startedMs();
        float lifeMs = ClientHitFeedback.isKill() ? 880.0f : 650.0f;
        float t = Math.max(0f, Math.min(1f, age / lifeMs));
        float fade = 1.0f - t;

        int centerX = gg.guiWidth() / 2;
        int centerY = gg.guiHeight() / 2;
        int x = centerX - 42;
        int y = centerY + 20;

        boolean kill = ClientHitFeedback.isKill();
        String marker = kill ? "KILL +100" : "HIT";
        int main = withAlpha(kill ? RED : BLUE, fade);

        gg.pose().pushPose();
        gg.pose().translate(x, y, 420);
        gg.drawString(font, marker, 0, 0, main, false);

        int barW = Math.max(18, font.width(marker) + 6);
        int bar = Math.max(1, Math.round(barW * fade));
        gg.fill(0, 11, bar, 12, main);
        gg.pose().popPose();
    }

    private static int withAlpha(int color, float alpha) {
        int a = Math.max(0, Math.min(255, Math.round(255 * alpha)));
        return (color & 0x00FFFFFF) | (a << 24);
    }

    private static void renderTopHud(GuiGraphics gg, Font font, BattleHudDto hud) {
        int screenW = gg.guiWidth();
        int center = screenW / 2;
        int top = 7;

        int max = Math.max(1, hud.maxTickets());
        int alphaColor = factionColor(1, hud.myFaction());
        int bravoColor = factionColor(2, hud.myFaction());

        // 左票数 + 扁平进度条
        String alphaText = String.valueOf(hud.alphaTickets());
        int leftTextX = center - 140;
        drawScaledText(gg, font, alphaText, leftTextX, top + 1, alphaColor, 1.1f);
        drawFlatScoreBar(gg, center - 118, top + 4, hud.alphaTickets(), max, alphaColor);

        // 右票数 + 扁平进度条
        drawFlatScoreBar(gg, center + 44, top + 4, hud.bravoTickets(), max, bravoColor);
        String bravoText = String.valueOf(hud.bravoTickets());
        drawScaledText(gg, font, bravoText, center + 124, top + 1, bravoColor, 1.1f);

        // 中央据点图标（A/B/C...）
        renderPointRow(gg, font, hud.points(), hud.myFaction(), center, top + 28, activeFocusName(hud),
                hud.squadOrderPointId(), hud.squadOrderAttack());
        renderStreakCounter(gg, font, hud.streak());
    }

    private static void drawScaledText(GuiGraphics gg, Font font, String text, int x, int y, int color, float scale) {
        gg.pose().pushPose();
        gg.pose().translate(x, y, 0);
        gg.pose().scale(scale, scale, 1.0f);
        gg.drawString(font, text, 0, 0, color, false);
        gg.pose().popPose();
    }

    /** 扁平化票数进度条：背景深色 + 纯色填充。 */
    private static void drawFlatScoreBar(GuiGraphics gg, int x, int y, int value, int max, int color) {
        int w = 74;
        int h = 10;
        gg.fill(x, y, x + w, y + h, BG_DARK);
        int fill = Math.max(0, Math.min(w - 2, Math.round((w - 2) * (value / (float) Math.max(1, max)))));
        if (fill > 0) {
            gg.fill(x + 1, y + 1, x + 1 + fill, y + h - 1, color);
        }
    }

    private static void renderPointRow(GuiGraphics gg, Font font, List<ControlPointHudDto> points, int myFaction,
                                       int center, int y, String activeFocus, int orderPointId, boolean orderAttack) {
        if (points.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        int icon = 16;
        int gap = 8;
        int totalW = points.size() * icon + (points.size() - 1) * gap;
        int x = center - totalW / 2;
        for (ControlPointHudDto p : points) {
            int pressureColor = factionColor(p.pressure(), myFaction);
            boolean focused = activeFocus != null && !activeFocus.isBlank() && activeFocus.equals(p.name());
            boolean ordered = p.pointId() == orderPointId;
            int drawIcon = focused ? 22 : icon;
            int dx = focused ? x - 3 : x;
            int dy = focused ? y - 3 : y;

            ResourceLocation ptTex;
            if (p.owner() == 0) {
                ptTex = p.pressure() == 0 ? POINT_NEUTRAL : POINT_OVERRUN;
            } else {
                ptTex = factionColor(p.owner(), myFaction) == BLUE ? POINT_FRIENDLY : POINT_ENEMY;
            }
            gg.blit(ptTex, dx, dy, 0, 0, drawIcon, drawIcon, icon, icon);
            String label = p.name();
            if (label.length() > 1) {
                label = label.substring(0, 1);
            }
            gg.drawString(font, label, x + icon / 2 - font.width(label) / 2, y + 5, WHITE, true);
            if (mc.player != null) {
                double dist = Math.sqrt(mc.player.distanceToSqr(p.x(), p.y(), p.z()));
                String distText = Math.round(dist) + "m";
                gg.drawString(font, distText, x + icon / 2 - font.width(distText) / 2, y - 7, TEXT_DIM, false);
            }
            if (focused) {
                gg.fill(x - 2, y + icon + 7, x + icon + 2, y + icon + 9, pressureColor);
            }

            if (ordered) {
                int orderColor = orderAttack ? GREEN : BLUE;
                String marker = "◆";
                gg.drawString(font, marker, x + icon / 2 - font.width(marker) / 2, y - 18, orderColor, false);
            }

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
            if (mate.isSquadLeader()) {
                name = "★ " + name;
            }
            if (mate.downed()) {
                name = "§4[倒地] " + name;
            }
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
            String weapon = e.weapon() != null && !e.weapon().isBlank() ? " §7[" + e.weapon() + "]" : "";
            String mid = " 击杀 ";
            int w = font.width(e.killer()) + font.width(weapon) + font.width(mid) + font.width(e.victim()) + 10;
            int x = xRight - w;
            gg.fill(x - 2, y - 2, xRight + 2, y + 10, 0x66000000);
            int cx = x + 3;
            gg.drawString(font, e.killer(), cx, y, killerColor, false);
            cx += font.width(e.killer());
            gg.drawString(font, weapon, cx, y, TEXT_DIM, false);
            cx += font.width(weapon);
            gg.drawString(font, mid, cx, y, TEXT_DIM, false);
            cx += font.width(mid);
            gg.drawString(font, e.victim(), cx, y, victimColor, false);
            y += 12;
        }
    }

    private static void renderCaptureFocus(GuiGraphics gg, Font font, BattleHudDto hud) {
        updateFocusState(hud);
        long now = System.currentTimeMillis();
        boolean hasLiveFocus = hud.focusState() != 0 && !hud.focusName().isBlank();
        long age = now - focusStartMs;
        long sinceSeen = now - focusLastSeenMs;
        if (!hasLiveFocus && sinceSeen > 280L) {
            return;
        }

        float in = easeOut(Math.min(1.0f, age / 220.0f));
        float out = hasLiveFocus ? 1.0f : (1.0f - Math.min(1.0f, sinceSeen / 280.0f));
        float alpha = Math.max(0.0f, Math.min(1.0f, in * out));
        int center = gg.guiWidth() / 2;
        int topRowY = 35;
        int focusY = 60;
        int y = Math.round(topRowY + (focusY - topRowY) * in);
        int panelW = 166;
        int panelH = 48;
        int x = center - panelW / 2;
        int factionColor = focusState == 3 ? GREY : factionColor(focusFaction, hud.myFaction());
        int aColor = withAlpha(factionColor, alpha);

        gg.fill(x, y, x + panelW, y + panelH, withAlpha(0xFF101418, alpha * 0.72f));
        gg.fill(x, y, x + panelW, y + 2, aColor);

        String label = focusName.length() > 1 ? focusName.substring(0, 1) : focusName;
        gg.pose().pushPose();
        gg.pose().translate(center - 5, y + 6 - 4.0f * in, 300);
        gg.drawString(font, label, 0, 0, aColor, true);
        gg.pose().popPose();

        String title = focusTitle(focusState) + " " + focusName;
        gg.drawString(font, title, center - font.width(title) / 2, y + 24, aColor, false);

        int barX = center - 59;
        int barY = y + 37;
        ResourceLocation capTex = focusState == 3 ? null :
                (factionColor(focusFaction, hud.myFaction()) == BLUE ? CAPTURE_BAR_BLUE : CAPTURE_BAR_RED);
        drawCaptureProgressBar(gg, barX, barY, focusProgress, capTex);
    }

    private static void updateFocusState(BattleHudDto hud) {
        long now = System.currentTimeMillis();
        if (hud.focusState() == 0 || hud.focusName().isBlank()) {
            return;
        }
        if (!hud.focusName().equals(focusName) || focusState == 0) {
            focusStartMs = now;
        }
        focusName = hud.focusName();
        focusState = hud.focusState();
        focusProgress = hud.focusProgress();
        focusFaction = hud.focusFaction();
        focusLastSeenMs = now;
    }

    private static String activeFocusName(BattleHudDto hud) {
        if (hud.focusState() != 0 && !hud.focusName().isBlank()) {
            return hud.focusName();
        }
        return System.currentTimeMillis() - focusLastSeenMs <= 280L ? focusName : "";
    }

    private static float easeOut(float t) {
        return 1.0f - (float) Math.pow(1.0f - t, 3.0f);
    }

    /** 扁平化据点占领进度条。 */
    private static void drawCaptureProgressBar(GuiGraphics gg, int x, int y, int progress, ResourceLocation fillTex) {
        int w = 118;
        int h = 6;
        gg.fill(x, y, x + w, y + h, BG_DARK);
        int fill = Math.max(0, Math.min(w - 2, Math.round((w - 2) * (progress / 100.0f))));
        if (fill > 0) {
            int color = fillTex == CAPTURE_BAR_BLUE ? BLUE : (fillTex == CAPTURE_BAR_RED ? RED : GREY);
            gg.fill(x + 1, y + 1, x + 1 + fill, y + h - 1, color);
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

    private static String focusTitle(int state) {
        return switch (state) {
            case 1 -> "正在占领";
            case 2 -> "正在防守";
            case 3 -> "争夺中";
            default -> "";
        };
    }

    /** 救援进度条：正在救援倒地队友时显示。 */
    private static void renderReviveProgress(GuiGraphics gg, Font font, BattleHudDto hud) {
        if (hud.revivingName().isBlank()) {
            return;
        }
        int centerX = gg.guiWidth() / 2;
        int y = gg.guiHeight() - 80;
        String text = "§a救援 " + hud.revivingName() + " §f" + hud.revivingProgress() + "%";
        int textW = font.width(text);
        int barW = Math.max(textW + 20, 140);
        int barH = 16;
        int x = centerX - barW / 2;
        gg.fill(x, y, x + barW, y + barH, 0xCC000000);
        gg.fill(x + 1, y + 1, x + 1 + Math.round((barW - 2) * (hud.revivingProgress() / 100f)), y + barH - 1, 0xCC4A90D9);
        gg.drawCenteredString(font, text, centerX, y + 4, 0xFFFFFFFF);
    }

    /** 倒地队友列表：显示距离和剩余时间。 */
    private static void renderDownedMates(GuiGraphics gg, Font font, BattleHudDto hud) {
        if (hud.downedMates().isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        int x = 8;
        int y = gg.guiHeight() - 48 - hud.downedMates().size() * 16;
        gg.fill(x - 2, y - 2, x + 160, y + hud.downedMates().size() * 16 + 2, 0x99000000);
        gg.fill(x - 2, y - 2, x + 160, y - 1, RED);
        for (DownedMateDto d : hud.downedMates()) {
            double dist = Math.sqrt(mc.player.distanceToSqr(d.x(), d.y(), d.z()));
            String text = "§4✚ " + d.name() + " §7" + Math.round(dist) + "m §c" + d.remainingSeconds() + "s";
            gg.drawString(font, text, x + 4, y + 2, 0xFFFFFFFF, false);
            y += 16;
        }
    }

    /** 连杀计数器：准星下方白色数字。 */
    private static void renderStreakCounter(GuiGraphics gg, Font font, int streak) {
        if (streak < 2) {
            return;
        }
        int cx = gg.guiWidth() / 2;
        int cy = gg.guiHeight() / 2;
        String text = String.valueOf(streak);
        gg.drawString(font, text, cx - font.width(text) / 2, cy + 28, 0xFFFFFFFF, false);
    }
}
