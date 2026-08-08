package org.shee33.act0.battlefield.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import org.shee33.act0.battlefield.network.DeployActionPacket;
import org.shee33.act0.battlefield.network.DeployAllyDto;
import org.shee33.act0.battlefield.network.DeployPointDto;
import org.shee33.act0.battlefield.network.DeploySquadMateDto;
import org.shee33.act0.battlefield.network.DeployStatusDto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 部署界面 2D 缩略地图 —— 参照《部署界面动效规格文档》第2/3节,在 {@link GuiGraphics} 2D 约束下
 * 复现地图选点系统:矩形区域(替代规格文档的领土多边形,见 Wave2 设计决策 1)描边入场、
 * 标记体系(同阵营/小队/据点/基地)、悬停/选中/取消交互、十字准星(生长/滑动/淡出)、
 * 右侧预览卡(首现/切换)。
 *
 * <p><b>架构</b>:与 {@link CaptureFocusAnimator} 同款"静态状态机,每帧被动推进"范式——单人
 * 客户端同一时刻只有一个部署界面会话,不存在多实例并发问题。{@link
 * org.shee33.act0.battlefield.client.screen.BattlefieldDeployScreen} 只负责布局(把矩形区域交给
 * 本类)与鼠标/键盘输入转发,选点视觉/动效全部由本类承载,方便 Wave3(武器面板)/Wave4(部署
 * 转场)在此基础上继续挂靠而不用重新理解一套新状态机。
 *
 * <p><b>选中态的数据来源</b>:选中是服务端权威状态({@link DeployStatusDto#selectedKind()}/
 * {@link DeployStatusDto#selectedTarget()})——点击/键盘都是"发包请求服务端切换选中",而不是
 * 本地直接切换。这保证键盘 V 键循环队友时地图上的选中态与鼠标点击选中天然同源、自动同步,
 * 不需要额外的"键盘选中→通知地图"桥接代码。"点击空白取消"({@link #handleClick})不发包
 * (动作枚举里没有"清除选择"这个语义,且不允许本波改动 Wave1 数据层之外的服务端逻辑),只是把
 * {@link #dismissed} 置真,让本类暂时不渲染选中视觉;一旦服务端选择真的变化(新的
 * selectedKind/selectedTarget 到达,例如玩家换选了别的目标),{@link #dismissed} 自动复位。
 *
 * <p><b>命中范围</b>:{@link #lastTargets} 只在 {@link #render} 时用"这一帧刚画出来的屏幕坐标"
 * 重建,{@link #handleClick} 只认这份列表——3D 世界空间的 {@code BattlefieldDeployWorldOverlay}
 * 保留作纯视觉辅助,不再参与点击命中判定(设计决策 3)。
 */
public final class DeployMapPanel {

    private DeployMapPanel() {
    }

    /** 小队绿 #6ee27e —— 规格文档 §2.2 给定,{@link DocPalette} 未收录同色相,本类补充。 */
    static final int SQUAD_COLOR = 0xFF6EE27E;

    private static final float ALLY_R = 2.2f;
    private static final float SQUAD_R = 3.2f;
    private static final float POINT_R = 8.0f;
    private static final float BASE_R = 7.5f;

    // ---- 开场编排:同阵营 → 小队 → 据点/基地 三波依次弹出 ----
    // 区域描边+填色动效已按需求移除，因此标记不再等待"边框画完"(原先要等到 1180ms 才起第一波，
    // 那个延迟的唯一理由就是让位给边框演出)。现在面板一出现就立刻开始出标记。
    private static final long MARKERS_BASE_MS = 60L;
    private static final long ALLY_STEP_MS = 40L;
    private static final long SQUAD_BASE_MS = MARKERS_BASE_MS + 160L;
    private static final long SQUAD_STEP_MS = 90L;
    private static final long POINT_BASE_MS = SQUAD_BASE_MS + 160L;
    private static final long POP_DURATION_MS = 340L;

    private static long openedAtMs = -1L;

    // ---- 悬停(§3.3,P1-3修复):每个标记独立一份补间状态,160ms outCubic,对称补间 ----
    // 原实现是全局唯一的"进入中"/"退出中"槖位,当两个标记同时落在悬停判定半径内时(压缩过的
    // 缩略图上队友标记扎堆是很常见的场景)每一帧会互相抢占同一份共享tween,导致悬停效果永远
    // 不出现;改为per-marker独立tween后各标记互不干扰。key是标记的稳定标识符(如"squad:<uuid>")。
    private static final Map<String, HoverState> hoverStates = new LinkedHashMap<>();

    /** 单个标记的悬停过渡状态:{@code hovering}记录当前是否处于悬停中,anim是本次过渡的补间。 */
    private static final class HoverState {
        boolean hovering;
        final Tween.Anim anim = new Tween.Anim();
    }

    // ---- 选中(服务端权威,见类注释) ----
    private static String selectedKey = "";
    private static boolean dismissed = false;

    // ---- 十字准星(§3.4):生长/滑动/淡出三态 ----
    private static boolean xhSession = false;
    private static boolean xhGrowMode = true;
    private static boolean xhFading = false;
    private static float xhX;
    private static float xhY;
    private static float xhFromX;
    private static float xhFromY;
    private static float xhToX;
    private static float xhToY;
    private static int xhColor = DocPalette.FRIEND;
    private static final Tween.Anim XH_GROW = new Tween.Anim();
    private static final Tween.Anim XH_SLIDE = new Tween.Anim();
    private static final Tween.Anim XH_FADE = new Tween.Anim();

    // ---- 预览卡(§3.5):首现 outCubic / 切换 dim→swap→brighten / 取消 inCubic ----
    private static boolean cardVisible = false;
    private static boolean cardFadingOut = false;
    private static boolean cardSwapPending = false;
    private static String cardTitle = "";
    private static String cardDesc1 = "";
    private static String cardDesc2 = "";
    private static int cardColor = DocPalette.FRIEND;
    private static String pendingTitle = "";
    private static String pendingDesc1 = "";
    private static String pendingDesc2 = "";
    private static int pendingColor = DocPalette.FRIEND;
    private static final Tween.Anim CARD_IN = new Tween.Anim();
    private static final Tween.Anim CARD_DIM = new Tween.Anim();
    private static final Tween.Anim CARD_BRIGHTEN = new Tween.Anim();
    private static final Tween.Anim CARD_OUT = new Tween.Anim();

    // ---- 点击命中(本帧渲染时缓存,供 handleClick 复用;3D 世界叠加层不再参与命中) ----
    private static final List<ClickTarget> lastTargets = new ArrayList<>();
    private static float lastMapX;
    private static float lastMapY;
    private static float lastMapW;
    private static float lastMapH;

    record ClickTarget(DeployActionPacket.DeployKind kind, String targetId, float cx, float cy, float r) {
    }

    /** 供 {@code BattlefieldDeployScreen#mouseClicked} 消费:命中了某个可部署标记时需要发的包。 */
    public record Selection(DeployActionPacket.DeployKind kind, String targetId) {
    }

    /** {@code insideMap}=true 时 Screen 应当消费这次点击(即便未命中任何标记,也是"点空白取消")。 */
    public record ClickOutcome(boolean insideMap, Selection selection) {
    }

    private record TargetInfo(float worldX, float worldY, float worldZ,
                               String title, String desc1, String desc2, int color) {
    }

    // =====================================================================
    // 生命周期
    // =====================================================================

    /** 部署界面每次打开时调用一次:重置所有状态并让开场动效从头播放。 */
    public static void onOpened() {
        openedAtMs = Tween.now();
        hoverStates.clear();
        selectedKey = "";
        dismissed = false;
        xhSession = false;
        xhFading = false;
        cardVisible = false;
        cardFadingOut = false;
        cardSwapPending = false;
        lastTargets.clear();
    }

    /** 部署界面关闭时调用:清空点击命中缓存,避免残留旧坐标误命中。 */
    public static void onClosed() {
        lastTargets.clear();
    }

    // =====================================================================
    // 渲染
    // =====================================================================

    /**
     * 渲染整块缩略地图(矩形区域+标记+十字准星),不含右侧预览卡(见 {@link #renderCard})。
     * {@code mapX/mapY/mapW/mapH} 是 Screen 布局决定的外框,内部按 §1 letterbox 规则等比适配。
     */
    public static void render(GuiGraphics gg, Font font, DeployStatusDto st,
                        int mapX, int mapY, int mapW, int mapH,
                        int mouseX, int mouseY) {
        if (openedAtMs < 0L) {
            onOpened();
        }
        long now = Tween.now();
        lastMapX = mapX;
        lastMapY = mapY;
        lastMapW = mapW;
        lastMapH = mapH;
        lastTargets.clear();

        gg.fill(mapX, mapY, mapX + mapW, mapY + mapH, 0x66060708);

        if (st == null || !st.hasArea()) {
            // 尚无战斗区域数据(比如刚打开的第一帧):只画空面板背板,数据到位后下一帧自然补上。
            return;
        }

        float[] rect = DeployMapMath.fittedRect(st.areaMinX(), st.areaMinZ(), st.areaMaxX(), st.areaMaxZ(),
                mapX, mapY, mapW, mapH);
        float rx = rect[0];
        float ry = rect[1];
        float rw = rect[2];
        float rh = rect[3];

        String key = selectionKey(st);
        if (!key.equals(selectedKey)) {
            selectedKey = key;
            dismissed = false;
        }
        boolean showSelection = !selectedKey.isEmpty() && !dismissed;
        TargetInfo selInfo = showSelection ? findTargetInfo(st, st.selectedKind(), st.selectedTarget()) : null;

        if (selInfo != null) {
            float[] sp = DeployMapMath.project(selInfo.worldX(), selInfo.worldZ(),
                    st.areaMinX(), st.areaMinZ(), st.areaMaxX(), st.areaMaxZ(), rx, ry, rw, rh);
            crosshairShow(now, sp[0], sp[1], selInfo.color());
            cardShow(now, selInfo.title(), selInfo.desc1(), selInfo.desc2(), selInfo.color());
        } else {
            crosshairHide(now);
            cardHide(now);
        }

        // 十字准星在标记之下(规格 SVG 里 xhair 组在 mks 组之前),先画。
        renderCrosshair(gg, now, rx, ry, rw, rh);

        // P0 修复:标记(含悬浮标签)裁剪到地图外框box内,防止越界标记的视觉溢出到右侧预览卡/
        // 顶部标题条/底部武器栏等其他UI区域。用外框box而非letterbox后的内框rect,给
        // drawLabelAbove 悬浮标签留出足够的裁剪空间不被裁掉。
        gg.enableScissor(mapX, mapY, mapX + mapW, mapY + mapH);
        renderAllies(gg, st, now, rx, ry, rw, rh);
        renderSquadMates(gg, font, st, now, rx, ry, rw, rh, mouseX, mouseY);
        renderPoints(gg, font, st, now, rx, ry, rw, rh, mouseX, mouseY);
        renderBase(gg, font, st, now, rx, ry, rw, rh, mouseX, mouseY);
        gg.disableScissor();
    }

    /** 渲染右侧预览卡(§3.5),独立于地图矩形之外的另一块屏幕区域,由 Screen 决定位置。 */
    public static void renderCard(GuiGraphics gg, Font font, int cardX, int cardY, int cardW) {
        if (!cardVisible) {
            return;
        }
        long now = Tween.now();
        float[] os = cardOpacityAndSlide(now);
        float opacity = os[0];
        float translateX = os[1];
        if (opacity <= 0.01f) {
            return;
        }
        int x = cardX + Math.round(translateX);
        int titleH = 14;
        gg.fill(x, cardY, x + cardW, cardY + titleH, withAlpha(cardColor, opacity));
        gg.drawString(font, cardTitle, x + 6, cardY + 4, withAlpha(0xFF0F1216, opacity), false);
        int bodyY = cardY + titleH;
        int bodyH = 40;
        gg.fill(x, bodyY, x + cardW, bodyY + bodyH, withAlpha(0xE6101418, opacity));
        gg.drawString(font, cardDesc1, x + 6, bodyY + 8, withAlpha(0xFFC9CED4, opacity), false);
        gg.drawString(font, cardDesc2, x + 6, bodyY + 20, withAlpha(0xFFC9CED4, opacity), false);
    }

    // =====================================================================
    // 点击命中
    // =====================================================================

    /** 鼠标左键点击处理:命中可部署标记 → 返回待发送的 Selection;命中空白 → 本地取消选中视觉。 */
    public static ClickOutcome handleClick(double mouseX, double mouseY) {
        for (ClickTarget t : lastTargets) {
            float dx = (float) mouseX - t.cx();
            float dy = (float) mouseY - t.cy();
            if (dx * dx + dy * dy <= t.r() * t.r()) {
                return new ClickOutcome(true, new Selection(t.kind(), t.targetId()));
            }
        }
        boolean inside = mouseX >= lastMapX && mouseX <= lastMapX + lastMapW
                && mouseY >= lastMapY && mouseY <= lastMapY + lastMapH;
        if (inside) {
            dismissed = true;
        }
        return new ClickOutcome(inside, null);
    }

    // =====================================================================
    // 标记:同阵营(不可交互)
    // =====================================================================

    private static void renderAllies(GuiGraphics gg, DeployStatusDto st, long now, float rx, float ry, float rw, float rh) {
        List<DeployAllyDto> allies = st.allies();
        for (int i = 0; i < allies.size(); i++) {
            DeployAllyDto a = allies.get(i);
            float pop = popScale(now, MARKERS_BASE_MS + i * ALLY_STEP_MS);
            if (pop <= 0f) {
                continue;
            }
            float[] p = DeployMapMath.project(a.x(), a.z(), st.areaMinX(), st.areaMinZ(), st.areaMaxX(), st.areaMaxZ(), rx, ry, rw, rh);
            float cx = p[0];
            float cy = p[1];
            float r = ALLY_R * pop;
            gg.fill((int) (cx - r), (int) (cy - r), (int) (cx + r), (int) (cy + r), withAlpha(DocPalette.FRIEND, 0.85f));
        }
    }

    // =====================================================================
    // 标记:小队成员(可交互)
    // =====================================================================

    private static void renderSquadMates(GuiGraphics gg, Font font, DeployStatusDto st, long now,
                                          float rx, float ry, float rw, float rh, int mouseX, int mouseY) {
        List<DeploySquadMateDto> mates = st.squadMates();
        for (int i = 0; i < mates.size(); i++) {
            DeploySquadMateDto m = mates.get(i);
            float pop = popScale(now, SQUAD_BASE_MS + i * SQUAD_STEP_MS);
            if (pop <= 0f) {
                continue;
            }
            String key = "squad:" + m.id();
            float[] p = DeployMapMath.project(m.x(), m.z(), st.areaMinX(), st.areaMinZ(), st.areaMaxX(), st.areaMaxZ(), rx, ry, rw, rh);
            float cx = p[0];
            float cy = p[1];
            boolean selected = m.deployable() && key.equals(selectedKey) && !dismissed;
            boolean hoveredNow = m.deployable() && !selected && isNear(mouseX, mouseY, cx, cy, 10f);
            float hoverV = selected ? 1f : (m.deployable() ? updateHover(key, hoveredNow, now) : 0f);
            float scale = pop * (1f + 0.2f * hoverV);
            int color = m.deployable() ? SQUAD_COLOR : DocPalette.ENEMY;
            float alphaMul = m.deployable() ? 1f : 0.5f;
            float r = SQUAD_R * scale;

            gg.fill((int) (cx - r), (int) (cy - r), (int) (cx + r), (int) (cy + r), withAlpha(color, alphaMul));
            float ir = 1.1f * scale;
            gg.fill((int) (cx - ir), (int) (cy - ir), (int) (cx + ir), (int) (cy + ir), withAlpha(0xFF0F1216, alphaMul));

            if (selected) {
                renderPulseRing(gg, now, cx, cy, color);
            } else if (hoverV > 0.02f) {
                renderHoverRing(gg, cx, cy, 6.5f, hoverV, color);
            }
            if (m.deployable() && (hoverV > 0.02f || selected)) {
                drawLabelAbove(gg, font, cx, cy - 12f, "小队 · " + safe(m.name()), Math.max(hoverV, selected ? 1f : 0f));
            }
            if (m.deployable() && DeployMapMath.insideRect(cx, cy, rx, ry, rw, rh)) {
                // P0 修复:越界(落在letterbox内框rect之外)的标记不参与点击命中。
                lastTargets.add(new ClickTarget(DeployActionPacket.DeployKind.SQUAD, m.id(), cx, cy, Math.max(8f, r + 4f)));
            }
        }
    }

    // =====================================================================
    // 标记:据点(可交互,仅己方占领可部署)
    // =====================================================================

    private static void renderPoints(GuiGraphics gg, Font font, DeployStatusDto st, long now,
                                      float rx, float ry, float rw, float rh, int mouseX, int mouseY) {
        List<DeployPointDto> points = st.points();
        for (DeployPointDto pt : points) {
            float pop = popScale(now, POINT_BASE_MS);
            if (pop <= 0f) {
                continue;
            }
            String key = "point:" + pt.id();
            float[] p = DeployMapMath.project(pt.x(), pt.z(), st.areaMinX(), st.areaMinZ(), st.areaMaxX(), st.areaMaxZ(), rx, ry, rw, rh);
            float cx = p[0];
            float cy = p[1];
            boolean interactive = pt.deployable();
            boolean selected = interactive && key.equals(selectedKey) && !dismissed;
            boolean hoveredNow = interactive && !selected && isNear(mouseX, mouseY, cx, cy, POINT_R + 2f);
            float hoverV = selected ? 1f : (interactive ? updateHover(key, hoveredNow, now) : 0f);
            float scale = pop * (1f + 0.2f * hoverV);
            int color = pt.deployable() ? DocPalette.FRIEND : (pt.owner() == 0 ? DocPalette.NEUTRAL : DocPalette.ENEMY);
            float alphaMul = interactive ? 1f : 0.55f;
            float r = POINT_R * scale;

            if (selected) {
                HudShapes.fillHex(gg, cx, cy, r, color, 1f);
            } else {
                HudShapes.fillHex(gg, cx, cy, r, 0xFF0A0E12, 0.85f * alphaMul);
            }
            HudShapes.strokeHex(gg, cx, cy, r, 1.5f, color, alphaMul);
            if (!interactive) {
                // 不可部署:一条斜穿短线传达"锁定/不可点",避免和可部署据点混淆。
                gg.fill((int) (cx - r * 0.55f), (int) (cy - 0.5f), (int) (cx + r * 0.55f), (int) (cy + 0.5f),
                        withAlpha(color, 0.55f));
            }
            drawCenteredSmall(gg, font, shortLetter(pt.name()), cx, cy, selected ? 0xFF0F1216 : color);

            if (selected) {
                renderPulseRing(gg, now, cx, cy, color);
            } else if (hoverV > 0.02f) {
                renderHoverRing(gg, cx, cy, r + 3f, hoverV, color);
            }
            if (interactive && (hoverV > 0.02f || selected)) {
                drawLabelAbove(gg, font, cx, cy - r - 4f, "目标 · " + safe(pt.name()), Math.max(hoverV, selected ? 1f : 0f));
            }
            if (interactive && DeployMapMath.insideRect(cx, cy, rx, ry, rw, rh)) {
                // P0 修复:同上,越界标记不参与点击命中。
                lastTargets.add(new ClickTarget(DeployActionPacket.DeployKind.POINT, pt.id(), cx, cy, r + 4f));
            }
        }
    }

    // =====================================================================
    // 标记:己方基地(可交互,45°旋转正方形)
    // =====================================================================

    private static void renderBase(GuiGraphics gg, Font font, DeployStatusDto st, long now,
                                    float rx, float ry, float rw, float rh, int mouseX, int mouseY) {
        if (!st.canBase()) {
            return;
        }
        float pop = popScale(now, POINT_BASE_MS);
        if (pop <= 0f) {
            return;
        }
        String key = "base:";
        float[] p = DeployMapMath.project(st.baseX(), st.baseZ(), st.areaMinX(), st.areaMinZ(), st.areaMaxX(), st.areaMaxZ(), rx, ry, rw, rh);
        float cx = p[0];
        float cy = p[1];
        boolean selected = key.equals(selectedKey) && !dismissed;
        boolean hoveredNow = !selected && isNear(mouseX, mouseY, cx, cy, BASE_R + 3f);
        float hoverV = selected ? 1f : updateHover(key, hoveredNow, now);
        float scale = pop * (1f + 0.2f * hoverV);
        float r = BASE_R * scale;
        int color = DocPalette.FRIEND;

        PoseStack pose = gg.pose();
        pose.pushPose();
        pose.translate(cx, cy, 0f);
        pose.mulPose(Axis.ZP.rotationDegrees(45f));
        int fill = selected ? withAlpha(color, 1f) : withAlpha(0xFF0A0E12, 0.85f);
        gg.fill((int) -r, (int) -r, (int) r, (int) r, fill);
        int border = withAlpha(color, 1f);
        gg.fill((int) -r, (int) -r, (int) r, (int) -r + 1, border);
        gg.fill((int) -r, (int) r - 1, (int) r, (int) r, border);
        gg.fill((int) -r, (int) -r, (int) -r + 1, (int) r, border);
        gg.fill((int) r - 1, (int) -r, (int) r, (int) r, border);
        pose.popPose();

        drawCenteredSmall(gg, font, "HQ", cx, cy, selected ? 0xFF0F1216 : color);
        if (selected) {
            renderPulseRing(gg, now, cx, cy, color);
        } else if (hoverV > 0.02f) {
            renderHoverRing(gg, cx, cy, r + 3f, hoverV, color);
        }
        if (hoverV > 0.02f || selected) {
            drawLabelAbove(gg, font, cx, cy - r - 4f, "友方总部", Math.max(hoverV, selected ? 1f : 0f));
        }
        if (DeployMapMath.insideRect(cx, cy, rx, ry, rw, rh)) {
            // P0 修复:同上,越界标记不参与点击命中。
            lastTargets.add(new ClickTarget(DeployActionPacket.DeployKind.BASE, "", cx, cy, r + 4f));
        }
    }

    // =====================================================================
    // 悬停/漂移/脉冲 —— 通用小工具
    // =====================================================================

    private static boolean isNear(int mouseX, int mouseY, float cx, float cy, float r) {
        float dx = mouseX - cx;
        float dy = mouseY - cy;
        return dx * dx + dy * dy <= r * r;
    }

    /** 三波错峰弹出:{@code scale 0→1},outBack,{@code delayMs} 由调用方按类别/序号算好传入。 */
    private static float popScale(long now, long delayMs) {
        if (openedAtMs < 0L) {
            return 0f;
        }
        float raw = (now - openedAtMs - delayMs) / (float) POP_DURATION_MS;
        return Math.max(0f, Tween.Ease.OUT_BACK.apply(raw));
    }

    /**
     * §3.3 悬停/移出对称补间(160ms outCubic,P1-3修复后per-marker独立):每个标记(key)持有
     * 自己的一份 {@link HoverState},与已选中的标记互斥(调用方对选中标记直接短路传 1,
     * 不会走到这里)。
     */
    private static float updateHover(String key, boolean hoveredNow, long now) {
        HoverState hs = hoverStates.computeIfAbsent(key, k -> new HoverState());
        if (hoveredNow != hs.hovering) {
            hs.hovering = hoveredNow;
            hs.anim.start(now, 160L, Tween.Ease.OUT_CUBIC);
        }
        float t = hs.anim.easedT(now);
        return hs.hovering ? t : 1f - t;
    }

    private static void renderHoverRing(GuiGraphics gg, float cx, float cy, float r, float alphaMul, int color) {
        int c = withAlpha(color, 0.7f * alphaMul);
        int x1 = (int) (cx - r);
        int x2 = (int) (cx + r);
        int y1 = (int) (cy - r);
        int y2 = (int) (cy + r);
        gg.fill(x1, y1, x2, y1 + 1, c);
        gg.fill(x1, y2 - 1, x2, y2, c);
        gg.fill(x1, y1, x1 + 1, y2, c);
        gg.fill(x2 - 1, y1, x2, y2, c);
    }

    /** §3.2 选中呼吸脉冲环:{@code r=12+4p, opacity=0.7-0.5p}(按小标记尺寸缩小到 r 基准 6/3)。 */
    private static void renderPulseRing(GuiGraphics gg, long now, float cx, float cy, int color) {
        float phase = DeployMapMath.pulsePhase(now);
        float r = 6f + 3f * phase;
        float alpha = 0.7f - 0.5f * phase;
        int c = withAlpha(color, alpha);
        int x1 = (int) (cx - r);
        int x2 = (int) (cx + r);
        int y1 = (int) (cy - r);
        int y2 = (int) (cy + r);
        gg.fill(x1, y1, x2, y1 + 1, c);
        gg.fill(x1, y2 - 1, x2, y2, c);
        gg.fill(x1, y1, x1 + 1, y2, c);
        gg.fill(x2 - 1, y1, x2, y2, c);
    }

    private static void drawLabelAbove(GuiGraphics gg, Font font, float cx, float bottomY, String text, float alpha) {
        if (alpha <= 0.02f) {
            return;
        }
        int w = font.width(text);
        int x = (int) (cx - w / 2f);
        int y = (int) bottomY - font.lineHeight;
        gg.fill(x - 2, y - 1, x + w + 2, y + font.lineHeight, withAlpha(0xAA000000, alpha));
        gg.drawString(font, text, x, y, withAlpha(0xFFFFFFFF, alpha), false);
    }

    private static void drawCenteredSmall(GuiGraphics gg, Font font, String text, float cx, float cy, int color) {
        int w = font.width(text);
        gg.drawString(font, text, (int) (cx - w / 2f), (int) (cy - font.lineHeight / 2f), color, false);
    }

    private static String shortLetter(String name) {
        if (name == null || name.isBlank()) {
            return "?";
        }
        return name.length() > 1 ? name.substring(0, 1) : name;
    }

    private static String safe(String s) {
        return s == null || s.isBlank() ? "?" : s;
    }

    private static int withAlpha(int argb, float alphaMul) {
        int baseA = (argb >>> 24) & 0xFF;
        int a = Math.round(baseA * Mth.clamp(alphaMul, 0f, 1f));
        return (argb & 0x00FFFFFF) | (a << 24);
    }

    // =====================================================================
    // 选中信息解析
    // =====================================================================

    private static String selectionKey(DeployStatusDto st) {
        if (st == null || st.selectedKind() == null || st.selectedKind().isBlank()) {
            return "";
        }
        return st.selectedKind() + ":" + st.selectedTarget();
    }

    private static TargetInfo findTargetInfo(DeployStatusDto st, String kind, String targetId) {
        if ("point".equals(kind)) {
            for (DeployPointDto p : st.points()) {
                if (p.id().equals(targetId)) {
                    int color = p.deployable() ? DocPalette.FRIEND : (p.owner() == 0 ? DocPalette.NEUTRAL : DocPalette.ENEMY);
                    return new TargetInfo((float) p.x(), (float) p.y(), (float) p.z(),
                            "目标 · " + safe(p.name()), "据点部署点",
                            p.deployable() ? "可部署" : "尚未占领,不可部署", color);
                }
            }
            return null;
        }
        if ("squad".equals(kind)) {
            for (DeploySquadMateDto m : st.squadMates()) {
                if (m.id().equals(targetId)) {
                    return new TargetInfo((float) m.x(), (float) m.y(), (float) m.z(),
                            "小队成员 · " + safe(m.name()), "跟随小队部署", "周边可能有交火", SQUAD_COLOR);
                }
            }
            return null;
        }
        if ("base".equals(kind)) {
            if (!st.canBase()) {
                return null;
            }
            return new TargetInfo((float) st.baseX(), (float) st.baseY(), (float) st.baseZ(),
                    "友方总部", "安全区域", "距前线较远", DocPalette.FRIEND);
        }
        return null;
    }

    // =====================================================================
    // 十字准星状态机(§3.4)
    // =====================================================================

    private static void crosshairShow(long now, float targetX, float targetY, int color) {
        xhColor = color;
        if (!xhSession) {
            xhSession = true;
            xhFading = false;
            xhGrowMode = true;
            xhX = targetX;
            xhY = targetY;
            xhToX = targetX;
            xhToY = targetY;
            XH_GROW.start(now, 320L, Tween.Ease.OUT_CUBIC);
            return;
        }
        if (Math.abs(xhToX - targetX) > 0.01f || Math.abs(xhToY - targetY) > 0.01f) {
            xhFading = false;
            xhGrowMode = false;
            xhFromX = xhX;
            xhFromY = xhY;
            xhToX = targetX;
            xhToY = targetY;
            XH_SLIDE.start(now, 260L, Tween.Ease.OUT_CUBIC);
        }
    }

    private static void crosshairHide(long now) {
        if (!xhSession || xhFading) {
            return;
        }
        xhFading = true;
        XH_FADE.start(now, 200L, Tween.Ease.IN_CUBIC);
    }

    private static void updateCrosshairPosition(long now) {
        if (xhGrowMode) {
            xhX = xhToX;
            xhY = xhToY;
        } else {
            float t = XH_SLIDE.easedT(now);
            xhX = Mth.lerp(t, xhFromX, xhToX);
            xhY = Mth.lerp(t, xhFromY, xhToY);
        }
    }

    private static void renderCrosshair(GuiGraphics gg, long now, float rx, float ry, float rw, float rh) {
        if (!xhSession) {
            return;
        }
        updateCrosshairPosition(now);

        float fadeMul = 1f;
        if (xhFading) {
            fadeMul = 1f - XH_FADE.easedT(now);
            if (XH_FADE.isDone(now)) {
                xhSession = false;
                return;
            }
        }
        float v = xhGrowMode ? XH_GROW.easedT(now) : 1f;
        float overall = v * fadeMul;
        if (overall <= 0.005f) {
            return;
        }

        float x1 = xhX - (xhX - rx) * v;
        float x2 = xhX + ((rx + rw) - xhX) * v;
        float y1 = xhY - (xhY - ry) * v;
        float y2 = xhY + ((ry + rh) - xhY) * v;

        int segs = 14;
        for (int i = 0; i < segs; i++) {
            float t0 = i / (float) segs;
            float t1 = (i + 1) / (float) segs;
            float mid = (t0 + t1) * 0.5f;
            float a = DeployMapMath.edgeFadeAlpha(mid) * overall;
            if (a > 0.003f) {
                float sx0 = Mth.lerp(t0, x1, x2);
                float sx1 = Mth.lerp(t1, x1, x2);
                gg.fill((int) sx0, (int) xhY, (int) Math.ceil(sx1), (int) xhY + 1, withAlpha(xhColor, a));
            }
        }
        for (int i = 0; i < segs; i++) {
            float t0 = i / (float) segs;
            float t1 = (i + 1) / (float) segs;
            float mid = (t0 + t1) * 0.5f;
            float a = DeployMapMath.edgeFadeAlpha(mid) * overall;
            if (a > 0.003f) {
                float sy0 = Mth.lerp(t0, y1, y2);
                float sy1 = Mth.lerp(t1, y1, y2);
                gg.fill((int) xhX, (int) sy0, (int) xhX + 1, (int) Math.ceil(sy1), withAlpha(xhColor, a));
            }
        }
    }

    // =====================================================================
    // 预览卡状态机(§3.5)
    // =====================================================================

    private static void cardShow(long now, String title, String desc1, String desc2, int color) {
        if (!cardVisible) {
            cardVisible = true;
            cardFadingOut = false;
            cardSwapPending = false;
            cardTitle = title;
            cardDesc1 = desc1;
            cardDesc2 = desc2;
            cardColor = color;
            CARD_IN.start(now, 300L, Tween.Ease.OUT_CUBIC);
            return;
        }
        if (title.equals(cardTitle) && desc1.equals(cardDesc1) && desc2.equals(cardDesc2)) {
            return;
        }
        cardFadingOut = false;
        pendingTitle = title;
        pendingDesc1 = desc1;
        pendingDesc2 = desc2;
        pendingColor = color;
        cardSwapPending = true;
        CARD_DIM.start(now, 120L, Tween.Ease.IN_CUBIC);
    }

    private static void cardHide(long now) {
        if (!cardVisible || cardFadingOut) {
            return;
        }
        cardFadingOut = true;
        CARD_OUT.start(now, 220L, Tween.Ease.IN_CUBIC);
    }

    /** 返回 {@code [opacity, translateXpx]},见 §3.5 首现/切换/取消三段公式。 */
    private static float[] cardOpacityAndSlide(long now) {
        if (cardFadingOut) {
            float t = CARD_OUT.easedT(now);
            if (CARD_OUT.isDone(now)) {
                cardVisible = false;
            }
            return new float[]{1f - t, 24f * t};
        }
        if (cardSwapPending && CARD_DIM.isDone(now)) {
            cardTitle = pendingTitle;
            cardDesc1 = pendingDesc1;
            cardDesc2 = pendingDesc2;
            cardColor = pendingColor;
            cardSwapPending = false;
            CARD_BRIGHTEN.start(now, 150L, Tween.Ease.OUT_CUBIC);
        }
        if (CARD_BRIGHTEN.isRunning() && !CARD_BRIGHTEN.isDone(now)) {
            return new float[]{0.4f + 0.6f * CARD_BRIGHTEN.easedT(now), 0f};
        }
        if (cardSwapPending) {
            return new float[]{1f - 0.6f * CARD_DIM.easedT(now), 0f};
        }
        if (CARD_IN.isRunning() && !CARD_IN.isDone(now)) {
            float t = CARD_IN.easedT(now);
            return new float[]{t, 24f * (1f - t)};
        }
        return new float[]{1f, 0f};
    }
}
