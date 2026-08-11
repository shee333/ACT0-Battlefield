package org.shee33.act0.battlefield.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.shee33.act0.battlefield.Act0Battlefield;
import org.shee33.act0.battlefield.network.BattleHudDto;
import org.shee33.act0.battlefield.network.CapturePointEventPacket;
import org.shee33.act0.battlefield.network.ControlPointHudDto;
import org.shee33.act0.battlefield.network.DownedMateDto;

import java.util.List;

/**
 * BF 风格大战场 HUD：顶部票数条 + 据点图标/进度条（左下小队面板已移除，避免与顶部票数信息重复冗余）。
 *
 * <p>不使用原版计分板侧边栏，因此不会出现右侧红色数字。颜色按战地风格动态渲染：友军=蓝色，敌军=红色。
 */
@Mod.EventBusSubscriber(modid = Act0Battlefield.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class BattlefieldHudOverlay {

    // ---- 扁平化配色 ----
    private static final int BLUE = 0xFF4A90D9;
    private static final int RED = 0xFFD94A4A;
    private static final int RED_DIM = 0x88D94A4A;
    private static final int GREY = 0xFF8C9196;
    private static final int BG = 0x88101418;
    private static final int BG_DARK = 0xAA101418;
    private static final int WHITE = 0xFFEEEEEE;
    private static final int TEXT_DIM = 0xFFA0A8B0;
    private static final int GREEN = 0xFF66CC66;
    private static final int DANGER = 0xFFFF8C00;

    /** 据点行小图标直径(px)——见 {@link #renderPointRow} 方法文档的取值依据。 */
    private static final float SMALL_DIAMETER = 18f;
    /** FLIP 特写六边形直径上限(px)——见 {@link #renderCaptureFocus} 方法文档的取值依据。 */
    private static final float FOCUS_DIAMETER_MAX = 50f;

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

        renderTopHudGroup(gg, font, hud);
        renderKillFeed(gg, font, hud.myFaction());
        renderReviveProgress(gg, font, hud);
        renderDownedMates(gg, font, hud);
        renderDeployConfirmFx(gg, font);
        renderDeploySpawnFx(gg, font);
        renderMatchStartFx(gg);
        renderDownedSelfFeedback(gg, font);
        renderBeingRevivedProgress(gg, font, hud.beingRevivedByName(), hud.beingRevivedProgress());
    }

    /**
     * 部署确认转场：全屏白闪 + "正在部署"文字，与 {@code ClientDeployPan} 驱动的 900ms 相机平滑
     * 过场同步播放，退场时机与 {@link #renderDeploySpawnFx} 的黑幕淡出天然咬合(见
     * {@code DeployConfirmFx} 类文档)。渲染顺序故意排在 {@link #renderDeploySpawnFx} 之前，
     * 让黑幕(若同帧出现)绘制在白闪之上，视觉上"白闪先退场、黑幕再进场"。
     *
     * <p>模式无关：包内可见，供 {@code BreakthroughHudOverlay} 复用，参照
     * {@link #renderCapturePointBannerCore} 的共享方法模式。
     */
    static void renderDeployConfirmFx(GuiGraphics gg, Font font) {
        DeployConfirmFx.render(gg, font);
    }

    /**
     * 部署/重生传送落地反馈：全屏黑幕 ease-out 淡出 + 底部"已部署 · 据点名"提示。
     *
     * <p>模式无关：服务端向 Conquest / Breakthrough 玩家均会发送 {@code DeploySpawnFxPacket}，
     * 因此本方法为包内可见（非 private），供 {@code BreakthroughHudOverlay} 复用，
     * 参照 {@link #renderCapturePointBannerCore} 的共享方法模式。
     */
    static void renderDeploySpawnFx(GuiGraphics gg, Font font) {
        int fadeAlpha = ClientDeployFx.fadeAlpha();
        if (fadeAlpha > 0) {
            gg.fill(0, 0, gg.guiWidth(), gg.guiHeight(), fadeAlpha << 24);
        }
        float toast = ClientDeployFx.toastAlpha();
        if (toast <= 0f) {
            return;
        }
        String text = "已部署 · " + ClientDeployFx.label();
        int textW = font.width(text);
        int panelW = textW + 24;
        int panelH = 16;
        int centerX = gg.guiWidth() / 2;
        int x = centerX - panelW / 2;
        int y = gg.guiHeight() - 64;
        gg.fill(x, y, x + panelW, y + panelH, withAlpha(0xFF101418, toast * 0.72f));
        gg.fill(x, y, x + panelW, y + 1, withAlpha(WHITE, toast * 0.9f));
        gg.drawString(font, text, centerX - textW / 2, y + 5, withAlpha(WHITE, toast), false);
    }

    /**
     * 比赛开局全屏黑屏转场：淡入(变黑)→停留→淡出(恢复)，倒计时结束、COMBAT 阶段正式开始那一刻
     * 触发。与 {@link #renderDeploySpawnFx} 用的 {@code ClientDeployFx} 是两套独立状态，互不覆盖。
     *
     * <p>模式无关：包内可见，供 {@code BreakthroughHudOverlay} 复用，参照
     * {@link #renderCapturePointBannerCore} 的共享方法模式。
     */
    static void renderMatchStartFx(GuiGraphics gg) {
        int fadeAlpha = ClientMatchStartFx.fadeAlpha();
        if (fadeAlpha > 0) {
            gg.fill(0, 0, gg.guiWidth(), gg.guiHeight(), fadeAlpha << 24);
        }
    }

    /**
     * 自身倒地反馈：四角低调 vignette（静止不闪烁）+ 顶部常驻横幅 + 被救起后的短暂提示。
     *
     * <p>模式无关：包内可见，供 {@code BreakthroughHudOverlay} 复用，参照
     * {@link #renderCapturePointBannerCore} 的共享方法模式。
     */
    static void renderDownedSelfFeedback(GuiGraphics gg, Font font) {
        float vignette = ClientDownedFeedback.vignetteAlpha();
        if (vignette > 0f) {
            int w = gg.guiWidth();
            int h = gg.guiHeight();
            int cw = Math.min(96, w / 5);
            int ch = Math.min(64, h / 6);
            int color = withAlpha(DANGER, vignette * 0.35f);
            gg.fill(0, 0, cw, ch, color);
            gg.fill(w - cw, 0, w, ch, color);
            gg.fill(0, h - ch, cw, h, color);
            gg.fill(w - cw, h - ch, w, h, color);
        }

        if (ClientDownedFeedback.isDowned()) {
            String text = "倒地 · 等待救援";
            int textW = font.width(text);
            int panelW = textW + 24;
            int panelH = 16;
            int centerX = gg.guiWidth() / 2;
            int x = centerX - panelW / 2;
            int y = 120;
            gg.fill(x, y, x + panelW, y + panelH, withAlpha(0xFF101418, 0.72f));
            gg.fill(x, y, x + panelW, y + 1, DANGER);
            gg.drawString(font, text, centerX - textW / 2, y + 5, WHITE, false);
        }

        float revivedToast = ClientDownedFeedback.revivedToastAlpha();
        if (revivedToast > 0f) {
            String text = "已被 " + ClientDownedFeedback.reviverName() + " 救起";
            int textW = font.width(text);
            int panelW = textW + 24;
            int panelH = 16;
            int centerX = gg.guiWidth() / 2;
            int x = centerX - panelW / 2;
            int y = 120;
            gg.fill(x, y, x + panelW, y + panelH, withAlpha(0xFF101418, revivedToast * 0.72f));
            gg.fill(x, y, x + panelW, y + 1, withAlpha(GREEN, revivedToast * 0.9f));
            gg.drawString(font, text, centerX - textW / 2, y + 5, withAlpha(WHITE, revivedToast), false);
        }
    }

    /**
     * 被救援中(target侧)进度条：与 reviver 侧的 {@link #renderReviveProgress} 互为镜像，让倒地的
     * 一方也能看到同一场救援的实时进度。紧贴 {@link #renderDownedSelfFeedback} 的"倒地·等待救援"
     * 横幅下方（16px 间距，8px 网格对齐），同属"自身倒地"信息组，不与其重叠。
     *
     * <p>模式无关：包内可见，供 {@code BreakthroughHudOverlay} 复用，参照
     * {@link #renderCapturePointBannerCore} 的共享方法模式。
     */
    static void renderBeingRevivedProgress(GuiGraphics gg, Font font, String reviverName, int progress) {
        if (reviverName == null || reviverName.isBlank()) {
            return;
        }
        int centerX = gg.guiWidth() / 2;
        int y = 152;
        String text = "§b" + reviverName + " §f正在救援你 " + progress + "%";
        int textW = font.width(text);
        int barW = Math.max(textW + 20, 140);
        int barH = 16;
        int x = centerX - barW / 2;
        gg.fill(x, y, x + barW, y + barH, 0xCC000000);
        gg.fill(x + 1, y + 1, x + 1 + Math.round((barW - 2) * (progress / 100f)), y + barH - 1, 0xCC66CC66);
        gg.drawCenteredString(font, text, centerX, y + 4, 0xFFFFFFFF);
    }

    private static int withAlpha(int color, float alpha) {
        int a = Math.max(0, Math.min(255, Math.round(255 * alpha)));
        return (color & 0x00FFFFFF) | (a << 24);
    }

    /**
     * 顶部票数条 + 据点图标行 + 据点特写/横幅，作为一组整体随 TAB 呼出淡出、松开淡入。
     *
     * <p>它们与 TAB 战绩面板抢同一片屏幕区域。把 TAB 画在更上层并不能解决问题——两层信息叠在
     * 一起谁都读不清；让下层退场才是可读的解法。整组一起淡出而不是逐个处理，是因为票数与据点
     * 状态在 TAB 面板里本来就有（票数在标题下方直接显示），淡出期间没有信息丢失。
     *
     * <p>用 {@code setShaderColor} 的全局颜色调制统一乘 alpha，而不是把透明度逐个塞进几十处
     * 颜色常量。必须在恢复调制前 {@link GuiGraphics#flush()}：文字与图元是批处理的，若先恢复
     * 颜色再由外部触发 flush，这一组就会以原色画出，淡出完全失效。
     */
    private static void renderTopHudGroup(GuiGraphics gg, Font font, BattleHudDto hud) {
        float dim = ClientTabFocus.dim();
        if (dim >= 0.999f) {
            return;
        }
        boolean fading = dim > 0.001f;
        if (fading) {
            RenderSystem.enableBlend();
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f - dim);
        }
        renderTopHud(gg, font, hud);
        renderCaptureFocus(gg, font, hud);
        renderCapturePointBanner(gg, font, hud);
        if (fading) {
            gg.flush();
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        }
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
        renderPointRow(gg, font, hud.points(), hud.myFaction(), center, top + 28,
                hud.squadOrderPointId(), hud.squadOrderAttack());
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

    /**
     * 据点横排小图标 —— 按占点 HUD 动效规格文档 §2.1/§4 的六边形样式 + {@link DocPalette} 配色语义重绘。
     *
     * <p>直径 18px(circumradius 9px):落在任务要求的 14~20px 量级区间上段,在典型 GUI 缩放下仍能同时
     * 容纳字母 + 上方距离文字保持可读,又不会因过大挤占多据点横排的空间(N 越大越吃紧,文档 §2.1 本身也
     * 建议大数量时缩小图标)。与文档 demo 的 46px 保持同一比例基准({@link #FOCUS_DIAMETER} 采用同源
     * 缩放比),供 {@code CaptureFocusAnimator} 的 FLIP 特写换算缩放起点。
     *
     * <p>本地玩家当前站在里面的那个据点(若有 FLIP 特写正在播放)在此处只留 18% 虚影,其余据点压暗到
     * 45% —— 对应文档 §1.3.2;这是"每个客户端只关心自己的单一 focus"，不影响其他玩家各自据点的显示。
     */
    private static void renderPointRow(GuiGraphics gg, Font font, List<ControlPointHudDto> points, int myFaction,
                                       int center, int y, int orderPointId, boolean orderAttack) {
        if (points.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        long now = Tween.now();
        float hexR = SMALL_DIAMETER / 2f;
        int diameter = Math.round(SMALL_DIAMETER);
        int gap = 10;
        int totalW = points.size() * diameter + (points.size() - 1) * gap;
        int x = center - totalW / 2;
        int ghostId = CaptureFocusAnimator.ghostPointId();

        for (ControlPointHudDto p : points) {
            float cx = x + hexR;
            float cy = y + hexR;
            boolean isGhost = ghostId == p.pointId();
            boolean dimOthers = ghostId != -1 && !isGhost;
            float alphaMul = isGhost ? 0.18f : (dimOthers ? 0.45f : 1.0f);
            boolean ordered = p.pointId() == orderPointId;

            float bounce = CaptureFocusAnimator.accountBounceScale(p.pointId(), now);
            gg.pose().pushPose();
            if (bounce != 1f) {
                gg.pose().translate(cx, cy, 0);
                gg.pose().scale(bounce, bounce, 1f);
                gg.pose().translate(-cx, -cy, 0);
            }
            int ownerColor = DocPalette.relative(p.owner(), myFaction);
            HudShapes.fillHex(gg, cx, cy, hexR, DocPalette.PANEL_BG, alphaMul);
            HudShapes.strokeHex(gg, cx, cy, hexR, 1.5f, ownerColor, alphaMul);
            String label = p.name().isBlank() ? "?" : p.name().substring(0, 1);
            gg.drawString(font, label, Math.round(cx - font.width(label) / 2f), Math.round(cy - 4f),
                    withAlpha(ownerColor, alphaMul), false);
            gg.pose().popPose();

            if (mc.player != null) {
                double dist = Math.sqrt(mc.player.distanceToSqr(p.x(), p.y(), p.z()));
                String distText = Math.round(dist) + "m";
                gg.drawString(font, distText, Math.round(cx - font.width(distText) / 2f), y - 9,
                        withAlpha(TEXT_DIM, alphaMul), false);
            }

            if (ordered) {
                int orderColor = orderAttack ? GREEN : BLUE;
                String marker = "◆";
                gg.drawString(font, marker, Math.round(cx - font.width(marker) / 2f), y - 20,
                        withAlpha(orderColor, alphaMul), false);
            }

            int pressureColor = DocPalette.relative(p.pressure(), myFaction);
            int fillW = p.pressure() != 0
                    ? Math.max(0, Math.min(diameter, Math.round(diameter * (p.progress() / 100.0f))))
                    : 0;
            HudShapes.flatBar(gg, x, Math.round(cy + hexR + 3), diameter, 2, fillW, BG_DARK, pressureColor, alphaMul);

            CaptureFocusAnimator.reportSlot(p.pointId(), cx, cy, diameter);
            x += diameter + gap;
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

    /**
     * 据点占领特写(FLIP 下拉放大)—— 对应规格文档 §1.3.2/§2.2/§2.3,渲染逻辑全部交给
     * {@link CaptureFocusAnimator} 的每帧状态机,这里只负责按快照把六边形轮廓 + 进度环 + 字母/文字画出来。
     *
     * <p>落点固定在票数条/据点行下方、屏幕上半部(y ≈ 30% 屏高),刻意不采用文档 demo 的屏幕正中(52%)——
     * 那是没有准星的网页演示专用坐标,真实对局里绝对不能让特写盖住准星与中央战斗视野。特写直径按
     * {@link #FOCUS_DIAMETER_MAX} 与屏高的 23% 取更小值,确保在任何 GUI 缩放下都不超过屏幕高度的
     * 22~26%(任务给定的上限),小图标/特写的直径比即是 FLIP 缩放起点 S0。
     */
    private static void renderCaptureFocus(GuiGraphics gg, Font font, BattleHudDto hud) {
        float targetX = gg.guiWidth() / 2f;
        float targetY = Math.min(gg.guiHeight() * 0.30f, 118f);
        float focusDiameter = Math.min(FOCUS_DIAMETER_MAX, gg.guiHeight() * 0.23f);
        CaptureFocusAnimator.configureGeometry(targetX, targetY, SMALL_DIAMETER, focusDiameter);

        CaptureFocusAnimator.Snapshot snap = CaptureFocusAnimator.update(hud);
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
        if (!snap.roundText().isEmpty()) {
            String rt = snap.roundText();
            gg.pose().pushPose();
            gg.pose().translate(cx, textY, 0);
            gg.pose().scale(snap.roundPulseScale(), snap.roundPulseScale(), 1f);
            gg.drawString(font, rt, -font.width(rt) / 2f, 0, withAlpha(TEXT_DIM, subAlpha), false);
            gg.pose().popPose();
            textY += 10f;
        }
        String pctText = snap.percent() + "%";
        gg.drawString(font, pctText, Math.round(cx - font.width(pctText) / 2f), Math.round(textY), withAlpha(WHITE, subAlpha), false);
        textY += 10f;
        String statusText = snap.statusText();
        gg.drawString(font, statusText, Math.round(cx - font.width(statusText) / 2f), Math.round(textY),
                withAlpha(snap.textColor(), subAlpha), false);
    }

    /**
     * 据点状态边沿事件横幅：位于 {@link #renderCaptureFocus} 面板下方（该面板最深延伸到 y=108），
     * 220ms 滑入淡入 + 停留（按事件类型区分）+ 280ms 淡出，避免与 focus 面板重叠遮挡。
     */
    private static void renderCapturePointBanner(GuiGraphics gg, Font font, BattleHudDto hud) {
        ClientCapturePointEvent.Active active = ClientCapturePointEvent.poll();
        if (active == null) {
            return;
        }
        String text = pointNameFor(hud, active.pointId()) + " · " + capturePointVerb(active.kind());
        int color = capturePointBannerColor(active.kind(), active.factionCode(), hud.myFaction());
        renderCapturePointBannerCore(gg, font, active, text, color);
    }

    /**
     * 据点状态边沿事件横幅的共享渲染核心（动效/位置/配色基线），供 {@code BreakthroughHudOverlay}
     * 复用，避免为突破模式重新发明一套据点横幅渲染。
     */
    static void renderCapturePointBannerCore(GuiGraphics gg, Font font,
                                              ClientCapturePointEvent.Active active, String text, int color) {
        long age = active.age();
        long holdMs = active.holdMs();
        float in = easeOut(Math.min(1.0f, age / (float) ClientCapturePointEvent.BANNER_IN_MS));
        long inOutBoundary = ClientCapturePointEvent.BANNER_IN_MS + holdMs;
        float out = age < inOutBoundary
                ? 1.0f
                : 1.0f - Math.min(1.0f, (age - inOutBoundary) / (float) ClientCapturePointEvent.BANNER_OUT_MS);
        float alpha = Math.max(0.0f, Math.min(1.0f, in * out));
        if (alpha <= 0.0f) {
            return;
        }
        int aColor = withAlpha(color, alpha);

        int textW = font.width(text);
        int panelW = textW + 24;
        int panelH = 16;
        int centerX = gg.guiWidth() / 2;
        int x = centerX - panelW / 2;
        int baseY = 112; // 8px 网格；renderCaptureFocus 面板最深至 y=108，此处留 4px 间距
        int y = baseY + Math.round(4 * (1.0f - in)); // 220ms 内从下方 4px 上移到位

        gg.fill(x, y, x + panelW, y + panelH, withAlpha(0xFF101418, alpha * 0.72f));
        int borderH = active.kind() == CapturePointEventPacket.Kind.CAPTURED_RECOVERED ? 2 : 1;
        gg.fill(x, y, x + panelW, y + borderH, aColor);
        gg.drawString(font, text, centerX - textW / 2, y + 5, aColor, false);
    }

    static String capturePointVerb(CapturePointEventPacket.Kind kind) {
        return switch (kind) {
            case STARTED -> "争夺中";
            case CAPTURED_NEW -> "已占领";
            case CAPTURED_RECOVERED -> "已夺回";
            case LOST -> "已失守";
        };
    }

    private static int capturePointBannerColor(CapturePointEventPacket.Kind kind, int factionCode, int myFaction) {
        if (kind == CapturePointEventPacket.Kind.LOST && myFaction != 0) {
            if (factionCode == myFaction) {
                return DANGER;
            }
            if (factionCode != 0) {
                return GREEN;
            }
        }
        return factionColor(factionCode, myFaction);
    }

    private static String pointNameFor(BattleHudDto hud, int pointId) {
        for (ControlPointHudDto p : hud.points()) {
            if (p.pointId() == pointId) {
                return p.name();
            }
        }
        return "";
    }

    private static float easeOut(float t) {
        return 1.0f - (float) Math.pow(1.0f - t, 3.0f);
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

}
