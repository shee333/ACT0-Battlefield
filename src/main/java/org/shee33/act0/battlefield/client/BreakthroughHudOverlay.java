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
 * 突破模式 HUD —— 占点 HUD 动效规格文档"设计 B：突破模式（分区域推进）"的 Minecraft 移植。
 *
 * <p>与 Conquest（{@code BattlefieldHudOverlay} + {@code CaptureFocusAnimator}）同一套技术路线
 * （见文档 §1 共享基础设施 + §7 移植对照），但本文件专属突破模式的据点行/区域标签/区域突破序列/
 * 终局横幅渲染，两套阶段机与渲染逻辑完全独立，互不依赖。
 *
 * <ul>
 *   <li>顶部票数条 + 阶段文案：{@link #renderTopBar}（未改动的既有逻辑）。</li>
 *   <li>区域标签(遮罩换字) + 区域进度条：{@link #renderSectorLabelAndPips}（§3.4/§3.3）。</li>
 *   <li>当前区域目标行(六边形小图标 + 距离 + 小进度环 + 入场/退场动效)：{@link #renderPointRow}（§3.1/§3.2/§3.3）。</li>
 *   <li>FLIP 下拉特写：{@link #renderCaptureFocus}，状态机在 {@link BreakthroughFocusAnimator}。</li>
 *   <li>区域突破横幅 + 战线扫过 + 终局横幅/碎片：{@link #renderSectorBreakBanner}/{@link #renderFrontSweep}/
 *       {@link #renderFinalBanner}，状态机在 {@link BreakthroughSectorAnimator}。</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = Act0Battlefield.MODID, value = Dist.CLIENT)
public final class BreakthroughHudOverlay {

    private static final int NEUTRAL_GREY = 0xFF8C9196;
    private static final int BG_DARK = 0xAA101418;
    private static final int WHITE = 0xFFEEEEEE;
    private static final int TEXT_DIM = 0xFFA0A8B0;
    /** 保留既有票数条使用的攻防阵营绝对色，仅用于顶部票数条/阶段文案，不用于新据点行/特写渡染
     * (那部分严格改用 {@link DocPalette} 的配色语义，见类文档)。 */
    private static final int ATTACKER_BLUE = 0xFF5787C7;
    private static final int DEFENDER_RED = 0xFFC75757;

    /** 据点行小图标直径(px)——与 Conquest ({@code BattlefieldHudOverlay#SMALL_DIAMETER}) 保持同一
     * 视觉比例基准,便于 FLIP 缩放起点 S0 的换算一致。 */
    private static final float SMALL_DIAMETER = 18f;
    /** FLIP 特写六边形直径上限(px)——同 Conquest {@code FOCUS_DIAMETER_MAX}。 */
    private static final float FOCUS_DIAMETER_MAX = 50f;

    private static final int TOP_BAR_Y = 7;
    private static final int TOP_BAR_H = 22;
    private static final int REGION_LABEL_Y = TOP_BAR_Y + TOP_BAR_H + 4;
    private static final int PIPS_Y = REGION_LABEL_Y + 10;
    private static final int POINT_ROW_Y = PIPS_Y + 16;

    private BreakthroughHudOverlay() {
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) {
            return;
        }
        BreakthroughHudDto hud = BreakthroughSectorAnimator.effectiveHud(ClientBreakthroughHud.hud());
        if (hud == null) {
            return;
        }
        GuiGraphics gg = event.getGuiGraphics();
        Font font = mc.font;
        long now = Tween.now();

        renderTopBar(gg, font, hud);
        renderSectorLabelAndPips(gg, font, hud, now);
        renderPointRow(gg, font, hud, now);
        renderCaptureFocus(gg, font, hud);
        renderSectorBreakBanner(gg, font, now);
        renderFrontSweep(gg, now);
        renderFinalBanner(gg, font, now);
        renderCapturePointBanner(gg, font, hud);
        BattlefieldHudOverlay.renderDeployConfirmFx(gg, font);
        BattlefieldHudOverlay.renderDeploySpawnFx(gg, font);
        BattlefieldHudOverlay.renderMatchStartFx(gg);
        BattlefieldHudOverlay.renderDownedSelfFeedback(gg, font);
        BattlefieldHudOverlay.renderBeingRevivedProgress(gg, font, hud.beingRevivedByName(), hud.beingRevivedProgress());
    }

    // ---- 顶部票数条 + 阶段文案(既有逻辑,未参与本次动效重构) ----

    private static void renderTopBar(GuiGraphics gg, Font font, BreakthroughHudDto hud) {
        int screenW = gg.guiWidth();
        int leftX = 8;
        String label = "进攻方";
        int labelW = font.width(label);
        gg.drawString(font, label, leftX, TOP_BAR_Y + 4, ATTACKER_BLUE, false);

        int barX = leftX + labelW + 8;
        int barW = 160;
        int barY = TOP_BAR_Y + 5;
        String ticketText = hud.attackerTickets() + "/" + hud.maxTickets();
        drawFlatProgressBar(gg, barX, barY, barW, 8, hud.attackerTickets(), hud.maxTickets(), ATTACKER_BLUE);
        gg.drawString(font, ticketText, barX + barW + 6, TOP_BAR_Y + 4, WHITE, false);

        if (hud.phase() == 0) {
            drawTextCentered(gg, font, "准备阶段", screenW / 2, TOP_BAR_Y + 4, WHITE);
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

    // ---- 区域标签(遮罩换字) + 区域进度条 pip(§3.3/§3.4) ----

    private static void renderSectorLabelAndPips(GuiGraphics gg, Font font, BreakthroughHudDto hud, long now) {
        int centerX = gg.guiWidth() / 2;
        String label = BreakthroughSectorAnimator.labelText(hud, now);
        float offsetFrac = BreakthroughSectorAnimator.labelOffsetFrac(now);
        int labelH = 9;
        gg.enableScissor(centerX - 90, REGION_LABEL_Y, centerX + 90, REGION_LABEL_Y + labelH);
        int ty = REGION_LABEL_Y + Math.round(offsetFrac * labelH);
        gg.drawString(font, label, centerX - font.width(label) / 2, ty, TEXT_DIM, false);
        gg.disableScissor();

        int totalSectors = hud.totalSectors();
        if (totalSectors <= 0) {
            return;
        }
        int pipW = 22;
        int pipH = 4;
        int gap = 8;
        int totalW = totalSectors * pipW + (totalSectors - 1) * gap;
        int x = centerX - totalW / 2;
        int breakingIdx = BreakthroughSectorAnimator.breakingPipIndex();
        for (int i = 0; i < totalSectors; i++) {
            int color;
            if (i < hud.currentSectorId()) {
                color = DocPalette.FRIEND;
            } else if (i == hud.currentSectorId()) {
                color = DocPalette.PROGRESS;
            } else {
                color = NEUTRAL_GREY;
            }
            float scaleY = i == breakingIdx ? BreakthroughSectorAnimator.pipBounceScaleY(now) : 1f;
            if (i == breakingIdx) {
                color = DocPalette.FRIEND;
            }
            int cx = x + pipW / 2;
            int cy = PIPS_Y + pipH / 2;
            gg.pose().pushPose();
            if (scaleY != 1f) {
                gg.pose().translate(cx, cy, 0);
                gg.pose().scale(1f, scaleY, 1f);
                gg.pose().translate(-cx, -cy, 0);
            }
            gg.fill(x, PIPS_Y, x + pipW, PIPS_Y + pipH, color);
            gg.pose().popPose();
            x += pipW + gap;
        }
    }

    // ---- 当前区域目标行:六边形小图标 + 距离 + 小进度环 + 入场/退场动效(§3.1/§3.2/§3.3) ----

    private static void renderPointRow(GuiGraphics gg, Font font, BreakthroughHudDto hud, long now) {
        Minecraft mc = Minecraft.getInstance();
        List<BreakthroughSectorAnimator.RowIcon> icons = BreakthroughSectorAnimator.isBreakSequenceActive()
                ? BreakthroughSectorAnimator.exitIcons(now)
                : BreakthroughSectorAnimator.currentRowIcons(hud, now);
        if (icons.isEmpty()) {
            return;
        }
        float hexR = SMALL_DIAMETER / 2f;
        int diameter = Math.round(SMALL_DIAMETER);
        int gap = 10;
        int totalW = icons.size() * diameter + (icons.size() - 1) * gap;
        int centerX = gg.guiWidth() / 2;
        int x = centerX - totalW / 2;
        int y = POINT_ROW_Y;
        int ghostId = BreakthroughFocusAnimator.ghostPointId();

        for (BreakthroughSectorAnimator.RowIcon icon : icons) {
            float cx = x + hexR;
            float cy = y + hexR + icon.offsetY();
            boolean isGhost = ghostId == icon.pointId();
            boolean dimOthers = ghostId != -1 && !isGhost;
            float alphaMul = icon.alpha() * (isGhost ? 0.18f : (dimOthers ? 0.45f : 1.0f));
            int ownerColor = icon.owner() == 1 ? DocPalette.FRIEND : DocPalette.ENEMY;

            float bounce = BreakthroughFocusAnimator.accountBounceScale(icon.pointId(), now);
            gg.pose().pushPose();
            if (bounce != 1f) {
                gg.pose().translate(cx, cy, 0);
                gg.pose().scale(bounce, bounce, 1f);
                gg.pose().translate(-cx, -cy, 0);
            }
            HudShapes.fillHex(gg, cx, cy, hexR, DocPalette.PANEL_BG, alphaMul);
            HudShapes.strokeHex(gg, cx, cy, hexR, 1.5f, ownerColor, alphaMul);
            String label = icon.name().isBlank() ? "?" : icon.name().substring(0, 1);
            gg.drawString(font, label, Math.round(cx - font.width(label) / 2f), Math.round(cy - 4f),
                    withAlpha(ownerColor, alphaMul), false);
            gg.pose().popPose();

            // 小进度环(§3.1:同一区域内相邻目标只在小图标行里正常显示状态/颜色/小进度环,不做特写放大)。
            float ringR = hexR + 3f;
            int ringColor;
            float frac;
            if (icon.owner() == 1) {
                ringColor = DocPalette.FRIEND;
                frac = 1f;
            } else if (icon.progress() > 0 && isRepelled(hud, icon.pointId())) {
                ringColor = DocPalette.ENEMY;
                frac = Math.max(0f, Math.min(1f, icon.progress() / 100f));
            } else {
                ringColor = DocPalette.PROGRESS;
                frac = Math.max(0f, Math.min(1f, icon.progress() / 100f));
            }
            HudShapes.ringTrack(gg, cx, cy, ringR, 1.5f, DocPalette.RING_TRACK, alphaMul);
            if (frac > 0f) {
                HudShapes.ringArc(gg, cx, cy, ringR, 1.5f, frac, ringColor, alphaMul);
            }

            if (mc.player != null) {
                double dist = Math.sqrt(mc.player.distanceToSqr(icon.x(), icon.y(), icon.z()));
                String distText = Math.round(dist) + "m";
                gg.drawString(font, distText, Math.round(cx - font.width(distText) / 2f),
                        Math.round(y - 9 + icon.offsetY()), withAlpha(TEXT_DIM, alphaMul), false);
            }

            BreakthroughFocusAnimator.reportSlot(icon.pointId(), cx, cy, diameter);
            x += diameter + gap;
        }
    }

    /** 该据点当前是否处于防守方压制方向(pressure==2),用于小图标行进度环临时转红("遭到反击")。 */
    private static boolean isRepelled(BreakthroughHudDto hud, int pointId) {
        for (BreakthroughPointDto p : hud.points()) {
            if (p.pointId() == pointId) {
                return p.pressure() == 2;
            }
        }
        return false;
    }

    // ---- FLIP 下拉特写(§1.3.2/§3.1) ----

    private static void renderCaptureFocus(GuiGraphics gg, Font font, BreakthroughHudDto hud) {
        float targetX = gg.guiWidth() / 2f;
        float targetY = Math.min(gg.guiHeight() * 0.34f, 140f);
        float focusDiameter = Math.min(FOCUS_DIAMETER_MAX, gg.guiHeight() * 0.23f);
        BreakthroughFocusAnimator.configureGeometry(targetX, targetY, SMALL_DIAMETER, focusDiameter);

        BreakthroughFocusAnimator.Snapshot snap = BreakthroughFocusAnimator.update(hud);
        if (snap == null) {
            return;
        }

        float cx = snap.x();
        float cy = snap.y();
        float r = focusDiameter / 2f * snap.scale();

        HudShapes.fillHex(gg, cx, cy, r, DocPalette.PANEL_BG, 1f);
        HudShapes.strokeHex(gg, cx, cy, r, 1.5f, withAlpha(0xFFFFFFFF, 0.3f), 1f);

        float ringR = r * 0.78f;
        float ringThickness = Math.max(2f, r * 0.09f);
        HudShapes.ringTrack(gg, cx, cy, ringR, ringThickness, DocPalette.RING_TRACK, 1f);
        HudShapes.ringArc(gg, cx, cy, ringR, ringThickness, snap.percent() / 100f, snap.ringColor(), 1f);

        String letter = snap.letter();
        float letterScale = Math.max(0.6f, r / 30f);
        gg.pose().pushPose();
        gg.pose().translate(cx - font.width(letter) * letterScale / 2f, cy - 4f * letterScale, 300);
        gg.pose().scale(letterScale, letterScale, 1f);
        gg.drawString(font, letter, 0, 0, snap.letterColor(), true);
        gg.pose().popPose();

        float subAlpha = snap.subordinateAlpha();
        if (subAlpha <= 0.01f) {
            return;
        }
        float textY = cy + r + 4f;
        String pctText = snap.percent() + "%";
        gg.drawString(font, pctText, Math.round(cx - font.width(pctText) / 2f), Math.round(textY), withAlpha(WHITE, subAlpha), false);
        textY += 10f;
        String statusText = snap.statusText();
        gg.drawString(font, statusText, Math.round(cx - font.width(statusText) / 2f), Math.round(textY),
                withAlpha(snap.textColor(), subAlpha), false);
    }

    // ---- 区域突破横幅(§3.3) ----

    private static void renderSectorBreakBanner(GuiGraphics gg, Font font, long now) {
        if (!BreakthroughSectorAnimator.bannerVisible(now)) {
            return;
        }
        int centerX = gg.guiWidth() / 2;
        int barY = Math.round(gg.guiHeight() * 0.40f);
        int barWpx = Math.min(140, Math.round(gg.guiWidth() * 0.35f));
        float barScaleX = BreakthroughSectorAnimator.bannerBarScaleX(now);
        if (barScaleX > 0.001f) {
            int w = Math.max(1, Math.round(barWpx * barScaleX));
            gg.fill(centerX - w / 2, barY, centerX + w / 2, barY + 2, WHITE);
        }

        int titleY = barY + 10;
        int titleH = 12;
        String title = BreakthroughSectorAnimator.bannerTitle();
        float titleOffset = BreakthroughSectorAnimator.bannerTitleOffsetFrac(now);
        gg.enableScissor(centerX - 160, titleY, centerX + 160, titleY + titleH);
        int ty = titleY + Math.round(titleOffset * titleH);
        drawScaledCentered(gg, font, title, centerX, ty, WHITE, 1.25f);
        gg.disableScissor();

        int subY = titleY + titleH + 4;
        int subH = 9;
        String sub = BreakthroughSectorAnimator.bannerSubtitle();
        float subOffset = BreakthroughSectorAnimator.bannerSubtitleOffsetFrac(now);
        gg.enableScissor(centerX - 160, subY, centerX + 160, subY + subH);
        int sy = subY + Math.round(subOffset * subH);
        gg.drawString(font, sub, centerX - font.width(sub) / 2, sy, DocPalette.FRIEND, false);
        gg.disableScissor();
    }

    /** 战线扫过(§3.3 招牌效果):发光竖线 + 身后蓝色浸染层。{@code GuiGraphics.fill} 画不出 CSS 渐变发光,
     * 用多层不同透明度的矩形从竖线中心向两侧递减来模拟发光(§3.3 技术提示)。 */
    private static void renderFrontSweep(GuiGraphics gg, long now) {
        float alpha = BreakthroughSectorAnimator.sweepAlpha(now);
        if (alpha <= 0.001f) {
            return;
        }
        float frac = BreakthroughSectorAnimator.sweepXFrac(now);
        int w = gg.guiWidth();
        int h = gg.guiHeight();
        int lineX = Math.round(w * frac);

        gg.fill(0, 0, Math.max(0, lineX), h, withAlpha(DocPalette.FRIEND, 0.05f * alpha));

        gg.fill(Math.max(0, lineX - 6), 0, Math.max(0, lineX - 3), h, withAlpha(DocPalette.FRIEND, 0.12f * alpha));
        gg.fill(Math.max(0, lineX - 3), 0, Math.max(0, lineX - 1), h, withAlpha(DocPalette.FRIEND, 0.35f * alpha));
        gg.fill(Math.max(0, lineX - 1), 0, Math.min(w, lineX + 1), h, withAlpha(DocPalette.FRIEND, 0.9f * alpha));
        gg.fill(Math.min(w, lineX + 1), 0, Math.min(w, lineX + 3), h, withAlpha(DocPalette.FRIEND, 0.35f * alpha));
        gg.fill(Math.min(w, lineX + 3), 0, Math.min(w, lineX + 6), h, withAlpha(DocPalette.FRIEND, 0.12f * alpha));
    }

    // ---- 终局:全线突破横幅 + 径向碎片爆开(§3.4) ----

    private static void renderFinalBanner(GuiGraphics gg, Font font, long now) {
        if (!BreakthroughSectorAnimator.isFinalActive()) {
            return;
        }
        int centerX = gg.guiWidth() / 2;
        int centerY = Math.round(gg.guiHeight() * 0.46f);
        float alpha = BreakthroughSectorAnimator.finalBannerAlpha(now);
        float scale = BreakthroughSectorAnimator.finalBannerScale(now);
        if (alpha > 0.001f) {
            gg.pose().pushPose();
            gg.pose().translate(centerX, centerY, 400);
            gg.pose().scale(scale, scale, 1f);
            String title = "全 线 突 破";
            drawScaledCentered(gg, font, title, 0, -10, withAlpha(DocPalette.FRIEND, alpha), 1.4f);
            String sub = "进攻方胜利";
            gg.drawString(font, sub, -font.width(sub) / 2, 6, withAlpha(TEXT_DIM, alpha), false);
            gg.pose().popPose();
        }
        for (float[] shard : BreakthroughSectorAnimator.shardOffsets(now)) {
            int sx = Math.round(centerX + shard[0]);
            int sy = Math.round(centerY + shard[1]);
            gg.fill(sx - 2, sy - 2, sx + 3, sy + 3, withAlpha(DocPalette.FRIEND, shard[2]));
        }
    }

    // ---- 据点状态边沿事件横幅(复用 BattlefieldHudOverlay 的共享渡染核心) ----

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

    // ---- 共享绘制工具 ----

    private static void drawFlatProgressBar(GuiGraphics gg, int x, int y, int w, int h, int value, int max, int color) {
        gg.fill(x, y, x + w, y + h, BG_DARK);
        int fill = Math.max(0, Math.min(w - 2, Math.round((w - 2) * (value / (float) Math.max(1, max)))));
        if (fill > 0) {
            gg.fill(x + 1, y + 1, x + 1 + fill, y + h - 1, color);
        }
    }

    private static void drawTextCentered(GuiGraphics gg, Font font, String text, int centerX, int y, int color) {
        gg.drawString(font, text, centerX - font.width(text) / 2, y, color, false);
    }

    private static void drawScaledCentered(GuiGraphics gg, Font font, String text, int centerX, int y, int color, float scale) {
        gg.pose().pushPose();
        gg.pose().translate(centerX, y, 0);
        gg.pose().scale(scale, scale, 1f);
        gg.drawString(font, text, -font.width(text) / 2, 0, color, false);
        gg.pose().popPose();
    }

    private static int withAlpha(int color, float alpha) {
        int a = Math.max(0, Math.min(255, Math.round(255 * alpha)));
        return (color & 0x00FFFFFF) | (a << 24);
    }
}
