package org.shee33.act0.battlefield.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import org.shee33.act0.battlefield.network.BattlefieldNetwork;
import org.shee33.act0.battlefield.network.DeployLoadoutDto;
import org.shee33.act0.battlefield.network.DeployOptionDto;
import org.shee33.act0.battlefield.network.DeploySlotOptionsDto;
import org.shee33.act0.battlefield.network.DeploySlotOverridePacket;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 部署界面底部武器更换上拉面板 —— 《部署界面动效规格文档》§3.6 的完整实现，是 Wave3 唯一涉及
 * "真实游戏功能"的一块（其余部分都是纯视觉/动效）：点击可选项会真正发送
 * {@link DeploySlotOverridePacket} 让服务端换装，本类只负责 UI 呈现 + 乐观更新，合法性把关
 * 始终在服务端（{@code RedeployService#handleSlotOverride}，Wave1 已实现，本波不改动）。
 *
 * <p><b>数据来源</b>:槽位列表/可选项完全由 {@link ClientDeployLoadout#get()} 下发的
 * {@link DeployLoadoutDto#slots()} 驱动，不硬编码槍/装备/投掷物这类固定槽位类型 ——
 * 与 Wave2 {@link DeployMapPanel} 同样的"服务端权威数据驱动渲染"哲学。
 *
 * <p><b>乐观更新</b>:点击可选项后立即用 {@link DeployLoadoutDto#withOverrides(Map)} 算出
 * 乐观后的新状态用于本地渲染（见 {@link #effectiveLoadout()}），不等待网络往返；一旦
 * {@link ClientDeployLoadout} 收到新的服务端回包(对象引用必然与发起请求时不同，见
 * {@link SyncDeployLoadoutPacket#decode} 每次都反序列化出全新实例)，对应的乐观覆盖立即失效
 * 让位给服务端权威数据——无论服务端最终接受还是拒绝了这次覆盖，下一次回包永远是唯一真相来源。
 *
 * <p><b>架构</b>:与 {@link DeployMapPanel} 同款"静态状态机,每帧被动推进"范式,补间全部走
 * {@link Tween.Anim}/{@link Tween.Ease}。纯数学(错峰延迟/锚定边界保护/同项判断/居中布局)
 * 拆到 {@link DeployWeaponMath} 方便脱离 Minecraft classpath 单测。
 */
public final class DeployWeaponPanel {

    private DeployWeaponPanel() {
    }

    // =====================================================================
    // 布局常量(§3.6:占位剪影块 30px 高 + 名称行 14px 高 + 间距)
    // =====================================================================

    private static final int SLOT_H = 30;
    private static final int NAME_ROW_H = 14;
    private static final int ROW_GAP = 3;
    private static final int SLOT_GAP = 8;
    private static final int MIN_SLOT_W = 44;
    private static final int MAX_SLOT_W = 92;

    private static final int PANEL_MIN_W = 120;
    private static final int PANEL_MAX_W = 220;
    private static final int PANEL_TITLE_H = 16;
    private static final int PANEL_ROW_H = 18;
    private static final int PANEL_GAP_ABOVE_BAR = 6;

    private static final int GOLD = DocPalette.PROGRESS;
    private static final int NAME_COLOR = 0xFFE0E0E0;
    private static final int DIM_TEXT = 0xC0C9CED4;

    /** 底部槽位栏总高度(占位块+间距+名称行),供 Screen 布局时预留空间。 */
    public static int barHeight() {
        return SLOT_H + ROW_GAP + NAME_ROW_H;
    }

    // =====================================================================
    // 打开的面板状态
    // =====================================================================

    private static int openSlotIndex = -1;
    private static List<DeployOptionDto> openOptions = List.of();
    private static String openSlotLabel = "";
    private static String openCurrentItemName = "";
    private static boolean panelClosing = false;
    private static long scheduledCloseAtMs = -1L;

    private static final int MAX_PANEL_ROWS = 24;
    private static final Tween.Anim PANEL_FADE = new Tween.Anim();
    private static final Tween.Anim[] ROW_IN = newAnimArray(MAX_PANEL_ROWS);
    private static final Tween.Anim[] ROW_OUT = newAnimArray(MAX_PANEL_ROWS);

    private static Tween.Anim[] newAnimArray(int n) {
        Tween.Anim[] arr = new Tween.Anim[n];
        for (int i = 0; i < n; i++) {
            arr[i] = new Tween.Anim();
        }
        return arr;
    }

    // ---- 选定反馈(§3.6"选定新武器"):行金闪 + 槽位名称上拉换字 + 占位块金色脉冲 ----
    private static int pickedSlotIndex = -1;
    private static int pickedRowIndex = -1;
    private static final Tween.Anim PICK_FLASH = new Tween.Anim();

    private static int nameSwapSlotIndex = -1;
    private static String nameSwapOldName = "";
    private static String nameSwapNewName = "";
    private static final Tween.Anim NAME_OUT = new Tween.Anim();
    private static final Tween.Anim NAME_IN = new Tween.Anim();

    private static int pulseSlotIndex = -1;
    private static final Tween.Anim PULSE = new Tween.Anim();

    // ---- 乐观更新:槽位序号 → (待确认物品名, 发起请求时的原始快照) ----
    private record PendingOverride(String itemName, DeployLoadoutDto requestedOnRaw) {
    }

    private static final Map<Integer, PendingOverride> pendingOverrides = new HashMap<>();

    // ---- 本帧点击命中缓存(下一帧 handleClick 复用,同 DeployMapPanel 的 lastTargets 模式) ----
    private record SlotRect(int slotIndex, int x, int y, int w, int h, DeploySlotOptionsDto slotDto) {
    }

    private record OptionRect(int rowIndex, String itemName, int x, int y, int w, int h) {
    }

    private static final List<SlotRect> lastSlotRects = new ArrayList<>();
    private static final List<OptionRect> lastOptionRects = new ArrayList<>();

    // =====================================================================
    // 生命周期
    // =====================================================================

    /** 部署界面每次打开时调用一次:清空所有残留状态,避免跨会话串场。 */
    public static void onOpened() {
        resetPanelState();
        pendingOverrides.clear();
        pickedSlotIndex = -1;
        pulseSlotIndex = -1;
        nameSwapSlotIndex = -1;
        PICK_FLASH.reset();
        PULSE.reset();
        NAME_OUT.reset();
        NAME_IN.reset();
        lastSlotRects.clear();
        lastOptionRects.clear();
    }

    /** 部署界面关闭时调用:清空点击命中缓存,避免残留旧坐标误命中。 */
    public static void onClosed() {
        resetPanelState();
        lastSlotRects.clear();
        lastOptionRects.clear();
    }

    private static void resetPanelState() {
        openSlotIndex = -1;
        openOptions = List.of();
        openSlotLabel = "";
        openCurrentItemName = "";
        panelClosing = false;
        scheduledCloseAtMs = -1L;
        PANEL_FADE.reset();
        for (Tween.Anim a : ROW_IN) {
            a.reset();
        }
        for (Tween.Anim a : ROW_OUT) {
            a.reset();
        }
        lastOptionRects.clear();
    }

    // =====================================================================
    // 乐观更新
    // =====================================================================

    /**
     * 原始快照叠加尚未被服务端回包结算的乐观覆盖。一旦 {@link ClientDeployLoadout} 出现新的
     * DTO 实例(不论内容是否变化,{@code decode} 每次都会反序列化出全新对象),说明服务端已经
     * 对这次请求做出了回应(接受或拒绝),对应的乐观覆盖立即失效——让位给这份新回包,不会永久
     * 停留在错误的乐观状态。
     */
    private static DeployLoadoutDto effectiveLoadout() {
        DeployLoadoutDto raw = ClientDeployLoadout.get();
        if (raw == null) {
            return null;
        }
        pendingOverrides.entrySet().removeIf(e -> e.getValue().requestedOnRaw() != raw);
        if (pendingOverrides.isEmpty()) {
            return raw;
        }
        Map<Integer, String> overrides = new HashMap<>();
        for (Map.Entry<Integer, PendingOverride> e : pendingOverrides.entrySet()) {
            overrides.put(e.getKey(), e.getValue().itemName());
        }
        return raw.withOverrides(overrides);
    }

    // =====================================================================
    // 渲染:底部槽位栏 + 上拉面板
    // =====================================================================

    /**
     * @param barTopY 槽位占位块顶部的屏幕 Y 坐标(名称行紧跟其下方,总高见 {@link #barHeight()})。
     */
    public static void render(GuiGraphics gg, Font font, int screenW, int screenH, int barTopY,
                               int mouseX, int mouseY) {
        long now = Tween.now();

        if (scheduledCloseAtMs >= 0L && now >= scheduledCloseAtMs) {
            closePanel(now);
            scheduledCloseAtMs = -1L;
        }
        if (panelClosing && PANEL_FADE.isDone(now)) {
            resetPanelState();
        }

        lastSlotRects.clear();

        DeployLoadoutDto loadout = effectiveLoadout();
        if (loadout == null || loadout.slots().isEmpty()) {
            lastOptionRects.clear();
            return;
        }

        List<DeploySlotOptionsDto> slots = loadout.slots();
        int n = slots.size();
        int[] widths = new int[n];
        for (int i = 0; i < n; i++) {
            int textW = font.width(slots.get(i).currentDisplayName());
            widths[i] = Mth.clamp(textW + 16, MIN_SLOT_W, MAX_SLOT_W);
        }
        int[] xs = DeployWeaponMath.layoutSlotX(widths, SLOT_GAP, screenW / 2);

        int openSlotScreenX = -1;
        int openSlotScreenW = 0;
        for (int i = 0; i < n; i++) {
            DeploySlotOptionsDto slot = slots.get(i);
            int sx = xs[i];
            int sw = widths[i];
            boolean isOpen = slot.slotIndex() == openSlotIndex;
            boolean hovered = inRect(mouseX, mouseY, sx, barTopY, sw, SLOT_H + ROW_GAP + NAME_ROW_H);

            renderSlot(gg, font, sx, barTopY, sw, slot, isOpen, hovered, now);

            lastSlotRects.add(new SlotRect(slot.slotIndex(), sx, barTopY, sw,
                    SLOT_H + ROW_GAP + NAME_ROW_H, slot));
            if (isOpen) {
                openSlotScreenX = sx;
                openSlotScreenW = sw;
            }
        }

        if (openSlotIndex >= 0 && openSlotScreenX >= 0) {
            renderPanel(gg, font, screenW, openSlotScreenX, openSlotScreenW, barTopY, mouseX, mouseY, now);
        } else {
            lastOptionRects.clear();
        }
    }

    /** 单个槽位:占位剪影块(带描边+内部物品占位色块)+ 名称行(换字动效在此播放)。 */
    private static void renderSlot(GuiGraphics gg, Font font, int sx, int sy, int sw,
                                    DeploySlotOptionsDto slot, boolean isOpen, boolean hovered, long now) {
        // 深色背板 + 轻微白色叠加(§3.6 占位剪影块基调)。
        gg.fill(sx, sy, sx + sw, sy + SLOT_H, 0xAA101418);
        gg.fill(sx, sy, sx + sw, sy + SLOT_H, withAlpha(0xFFFFFFFF, 0.07f));

        // §3.6"槽位悬停:外框描边 0.1→0.35(即时)";点击打开后描边转金。
        int borderColor = isOpen ? withAlpha(GOLD, 1f) : withAlpha(0xFFFFFFFF, hovered ? 0.35f : 0.1f);
        drawBorder(gg, sx, sy, sw, SLOT_H, borderColor);

        // 内部物品占位色块(left 18%/top 32%/width 64%/height 36%),留待渲染实际物品图标。
        int swX = sx + Math.round(sw * 0.18f);
        int swY = sy + Math.round(SLOT_H * 0.32f);
        int swW = Math.round(sw * 0.64f);
        int swH = Math.round(SLOT_H * 0.36f);
        gg.fill(swX, swY, swX + swW, swY + swH, withAlpha(0xFFFFFFFF, 0.14f));
        if (pulseSlotIndex == slot.slotIndex() && !PULSE.isDone(now)) {
            float decay = 1f - PULSE.easedT(now);
            gg.fill(swX, swY, swX + swW, swY + swH, withAlpha(GOLD, 0.2f * decay));
        } else if (pulseSlotIndex == slot.slotIndex()) {
            pulseSlotIndex = -1;
        }

        renderNameRow(gg, font, sx, sy + SLOT_H + ROW_GAP, sw, slot, now);
    }

    /** 名称行:外层 scissor 遮罩 + 上拉换字动效(§3.6"选定新武器":旧名上滑出/新名下滑入)。 */
    private static void renderNameRow(GuiGraphics gg, Font font, int sx, int nameY, int sw,
                                       DeploySlotOptionsDto slot, long now) {
        gg.enableScissor(sx, nameY, sx + sw, nameY + NAME_ROW_H);
        if (nameSwapSlotIndex == slot.slotIndex() && !NAME_IN.isDone(now)) {
            if (!NAME_OUT.isDone(now)) {
                float t = NAME_OUT.easedT(now);
                int ty = nameY - Math.round(NAME_ROW_H * 1.10f * t);
                drawCenteredName(gg, font, sx, ty, sw, nameSwapOldName);
            } else {
                float t = NAME_IN.easedT(now);
                int ty = nameY + Math.round(NAME_ROW_H * 1.10f * (1f - t));
                drawCenteredName(gg, font, sx, ty, sw, nameSwapNewName);
            }
        } else {
            if (nameSwapSlotIndex == slot.slotIndex()) {
                nameSwapSlotIndex = -1;
            }
            drawCenteredName(gg, font, sx, nameY, sw, slot.currentDisplayName());
        }
        gg.disableScissor();
    }

    private static void drawCenteredName(GuiGraphics gg, Font font, int sx, int y, int sw, String text) {
        String shown = font.width(text) > sw - 4 ? font.plainSubstrByWidth(text, sw - 4) : text;
        int tx = sx + Math.max(0, (sw - font.width(shown)) / 2);
        gg.drawString(font, shown, tx, y + 2, NAME_COLOR, false);
    }

    /** 上拉面板:锚定被点槽位正上方,选项自下而上错峰升起(§3.6)。 */
    private static void renderPanel(GuiGraphics gg, Font font, int screenW, int slotX, int slotW,
                                     int barTopY, int mouseX, int mouseY, long now) {
        lastOptionRects.clear();
        float opacity = panelOpacity(now);
        if (opacity <= 0.01f) {
            return;
        }

        int panelW = PANEL_MIN_W;
        for (DeployOptionDto opt : openOptions) {
            panelW = Math.max(panelW, font.width(opt.displayName()) + 54);
        }
        panelW = Math.min(panelW, PANEL_MAX_W);

        int rows = Math.min(openOptions.size(), MAX_PANEL_ROWS);
        int panelH = PANEL_TITLE_H + rows * PANEL_ROW_H;
        int panelBottom = barTopY - PANEL_GAP_ABOVE_BAR;
        int panelTop = panelBottom - panelH;
        int panelX = DeployWeaponMath.clampPanelX(slotX, panelW, screenW);

        gg.fill(panelX, panelTop, panelX + panelW, panelTop + PANEL_TITLE_H, withAlpha(0xFFFFFFFF, 0.08f * opacity));
        String title = "更换 " + openSlotLabel;
        gg.drawString(font, title, panelX + 8, panelTop + 4, withAlpha(0xFFE0E0E0, 0.75f * opacity), false);

        int contentTop = panelTop + PANEL_TITLE_H;
        gg.fill(panelX, contentTop, panelX + panelW, panelTop + panelH, withAlpha(0xE6101418, opacity));
        int border = withAlpha(0xFFFFFFFF, 0.1f * opacity);
        gg.fill(panelX, panelTop, panelX + panelW, panelTop + 1, border);
        gg.fill(panelX, panelTop + panelH - 1, panelX + panelW, panelTop + panelH, border);
        gg.fill(panelX, panelTop, panelX + 1, panelTop + panelH, border);
        gg.fill(panelX + panelW - 1, panelTop, panelX + panelW, panelTop + panelH, border);

        for (int i = 0; i < rows; i++) {
            float[] rowFx = rowOpacityAndOffset(i, now);
            float rowOpacity = rowFx[0] * opacity;
            if (rowOpacity <= 0.01f) {
                continue;
            }
            int rowY = contentTop + i * PANEL_ROW_H + Math.round(rowFx[1]);
            renderOptionRow(gg, font, panelX, rowY, panelW, i, rowOpacity, mouseX, mouseY, now);
            if (!panelClosing) {
                lastOptionRects.add(new OptionRect(i, openOptions.get(i).id(),
                        panelX, rowY, panelW, PANEL_ROW_H));
            }
        }
    }

    private static void renderOptionRow(GuiGraphics gg, Font font, int panelX, int rowY, int panelW,
                                         int i, float rowOpacity, int mouseX, int mouseY, long now) {
        DeployOptionDto option = openOptions.get(i);
        boolean isCurrent = DeployWeaponMath.isSameItem(option.id(), openCurrentItemName);
        boolean hovered = !panelClosing && inRect(mouseX, mouseY, panelX, rowY, panelW, PANEL_ROW_H);
        if (hovered) {
            gg.fill(panelX, rowY, panelX + panelW, rowY + PANEL_ROW_H, withAlpha(0xFFFFFFFF, 0.07f * rowOpacity));
        }

        int swX = panelX + 10;
        int swY = rowY + (PANEL_ROW_H - 16) / 2;
        gg.fill(swX, swY, swX + 28, swY + 16, withAlpha(0xFFFFFFFF, (isCurrent ? 0.25f : 0.1f) * rowOpacity));

        int textColor = isCurrent ? GOLD : DIM_TEXT;
        gg.drawString(font, option.displayName(), swX + 34, rowY + 5,
                withAlpha(textColor, rowOpacity), false);

        if (pickedSlotIndex == openSlotIndex && pickedRowIndex == i && !PICK_FLASH.isDone(now)) {
            float decay = 1f - PICK_FLASH.easedT(now);
            gg.fill(panelX, rowY, panelX + panelW, rowY + PANEL_ROW_H, withAlpha(GOLD, 0.25f * decay * rowOpacity));
        }
    }

    /** §3.6:{@code opacity=[面板整体, 行内 offsetY(px)]}——开启走 outCubic 升起,关闭走 inCubic 下沉。 */
    private static float[] rowOpacityAndOffset(int i, long now) {
        if (panelClosing) {
            float t = ROW_OUT[i].easedT(now);
            return new float[]{1f - t, 10f * t};
        }
        float t = ROW_IN[i].easedT(now);
        return new float[]{t, 16f * (1f - t)};
    }

    private static float panelOpacity(long now) {
        if (openSlotIndex < 0) {
            return 0f;
        }
        if (panelClosing) {
            return 1f - PANEL_FADE.easedT(now);
        }
        return PANEL_FADE.easedT(now);
    }

    // =====================================================================
    // 点击命中(§3.6:打开/切换/关闭/选定)
    // =====================================================================

    /**
     * @return {@code true} 表示点击命中了槽位或可选项(已被本类消费,Screen 不应再转发给
     * {@code DeployMapPanel});{@code false} 表示未命中——若此时面板仍处于打开状态,会作为
     * "点空白关闭"的副作用先行关闭面板,再让调用方继续走地图的空白点击/取消逻辑。
     */
    public static boolean handleClick(double mouseX, double mouseY) {
        long now = Tween.now();

        if (openSlotIndex >= 0 && !panelClosing) {
            for (OptionRect r : lastOptionRects) {
                if (inRect(mouseX, mouseY, r.x(), r.y(), r.w(), r.h())) {
                    pick(openSlotIndex, r.rowIndex(), r.itemName(), now);
                    return true;
                }
            }
        }

        for (SlotRect r : lastSlotRects) {
            if (inRect(mouseX, mouseY, r.x(), r.y(), r.w(), r.h())) {
                if (r.slotIndex() == openSlotIndex && !panelClosing) {
                    closePanel(now);
                } else {
                    openSlot(r.slotIndex(), r.slotDto(), now);
                }
                return true;
            }
        }

        if (openSlotIndex >= 0) {
            closePanel(now);
        }
        return false;
    }

    private static void openSlot(int slotIndex, DeploySlotOptionsDto slotDto, long now) {
        openSlotIndex = slotIndex;
        openOptions = slotDto.options();
        openSlotLabel = slotDto.slotName();
        openCurrentItemName = slotDto.currentItemName();
        panelClosing = false;
        scheduledCloseAtMs = -1L;

        PANEL_FADE.start(now, 150L, Tween.Ease.OUT_CUBIC);
        int rows = Math.min(openOptions.size(), MAX_PANEL_ROWS);
        for (int i = 0; i < rows; i++) {
            ROW_OUT[i].reset();
            ROW_IN[i].start(now, 240L, Tween.Ease.OUT_CUBIC, DeployWeaponMath.openRowDelayMs(i));
        }
    }

    private static void closePanel(long now) {
        if (openSlotIndex < 0 || panelClosing) {
            return;
        }
        panelClosing = true;
        int rows = Math.min(openOptions.size(), MAX_PANEL_ROWS);
        for (int i = 0; i < rows; i++) {
            ROW_OUT[i].start(now, 150L, Tween.Ease.IN_CUBIC, DeployWeaponMath.closeRowDelayMs(i));
        }
        PANEL_FADE.start(now, 180L, Tween.Ease.IN_CUBIC, DeployWeaponMath.closeRowDelayMs(rows));
    }

    /**
     * §3.6"选定新武器":先发包真正触发换装,再播视觉反馈;选中当前项则只闪不换
     * (对应"相当于什么都没变"),否则播放槍位名称上拉换字 + 占位块金色脉冲,并把关闭面板
     * 延迟到"旧名称上滑出"播完(140ms)才执行,与规格文档的时序一致。
     */
    private static void pick(int slotIndex, int rowIndex, String itemName, long now) {
        DeployLoadoutDto raw = ClientDeployLoadout.get();
        if (raw == null) {
            return;
        }

        BattlefieldNetwork.CHANNEL.sendToServer(new DeploySlotOverridePacket(slotIndex, itemName));

        boolean same = DeployWeaponMath.isSameItem(itemName, openCurrentItemName);
        if (!same) {
            pendingOverrides.put(slotIndex, new PendingOverride(itemName, raw));
        }

        pickedSlotIndex = slotIndex;
        pickedRowIndex = rowIndex;
        PICK_FLASH.start(now, 200L, Tween.Ease.OUT_CUBIC);

        if (!same) {
            nameSwapSlotIndex = slotIndex;
            nameSwapOldName = displayNameOf(openCurrentItemName);
            nameSwapNewName = displayNameOf(itemName);
            NAME_OUT.start(now, 140L, Tween.Ease.IN_CUBIC);
            NAME_IN.start(now, 200L, Tween.Ease.OUT_CUBIC, 140L);

            pulseSlotIndex = slotIndex;
            PULSE.start(now, 300L, Tween.Ease.OUT_CUBIC);

            openCurrentItemName = itemName;
            scheduledCloseAtMs = now + 140L;
        } else {
            closePanel(now);
        }
    }

    // =====================================================================
    // 小工具
    // =====================================================================

    private static boolean inRect(double mouseX, double mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    private static void drawBorder(GuiGraphics gg, int x, int y, int w, int h, int color) {
        gg.fill(x, y, x + w, y + 1, color);
        gg.fill(x, y + h - 1, x + w, y + h, color);
        gg.fill(x, y, x + 1, y + h, color);
        gg.fill(x + w - 1, y, x + w, y + h, color);
    }

    /**
     * 在当前打开的槽位可选项里查 ID 对应的显示名。
     *
     * <p>换字动效需要"旧名 → 新名"两段文字，而手里只有 ID：服务端下发的显示名挂在可选项上，
     * 从这里查是唯一不必把显示名一路透传进点击回调的做法。
     */
    private static String displayNameOf(String id) {
        for (DeployOptionDto option : openOptions) {
            if (option.id().equals(id)) {
                return option.displayName();
            }
        }
        return DeployOptionDto.fallbackName(id);
    }

    private static int withAlpha(int argb, float alphaMul) {
        int baseA = (argb >>> 24) & 0xFF;
        int a = Math.round(baseA * Mth.clamp(alphaMul, 0f, 1f));
        return (argb & 0x00FFFFFF) | (a << 24);
    }
}
