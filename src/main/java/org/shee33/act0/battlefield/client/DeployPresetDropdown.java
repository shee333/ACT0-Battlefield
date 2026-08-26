package org.shee33.act0.battlefield.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import org.shee33.act0.battlefield.network.BattlefieldNetwork;
import org.shee33.act0.battlefield.network.ClassPresetsDto;
import org.shee33.act0.battlefield.network.DeployLoadoutDto;
import org.shee33.act0.battlefield.network.FactionPresetsDto;
import org.shee33.act0.battlefield.network.LoadoutConfigDto;
import org.shee33.act0.battlefield.network.LoadoutPresetPreviewDto;
import org.shee33.act0.battlefield.network.LoadoutSelectPresetPacket;

import java.util.Collections;
import java.util.List;

/**
 * 部署界面底部的<b>配装下拉</b>：取代旧的纯展示条，提供真正的下拉切换。
 *
 * <p>关闭态是一个居中的胶囊按钮（"当前配装名 ▼"）；点击后向上弹出一个面板，按顺序列出
 * 服务端为当前阵营当前兵种配置的全部预设，点击其中一项会发 {@link LoadoutSelectPresetPacket}，
 * 服务端处理后会回一份新的 {@link DeployLoadoutDto}，本组件随即刷新按钮上的名字。
 *
 * <p>数据来源：{@link ClientLoadoutConfig}（部署入口由 RedeployService 同步推一份完整
 * {@link LoadoutConfigDto} 过来，下拉才能正确列出该阵营该兵种的预设）。玩家阵营走
 * {@link ClientBattleHud#hud()} 的 {@code myFaction}（1=ALPHA / 2=BRAVO），跟服务端同步
 * 这条线共用同一份事实，避免出现"下拉里是 B 队预设、但配装服务认为玩家是 A 队"的错位。
 *
 * <p>动效：开/关各走一段 alpha 过渡（线性，约 150ms），用 {@link System#currentTimeMillis()}
 * 与 MC tick 解耦；弹层高度跟随 alpha 收缩，避免关闭到一半时高度突变。
 */
public final class DeployPresetDropdown {

    /** 按钮总高度（关闭态占位）。Screen 布局按它给下面预留位置。 */
    private static final int BTN_H = 22;
    private static final int ITEM_H = 22;
    private static final int MAX_POPUP_H = 168;          // 约 7 行，再多滚动
    private static final int SCROLLBAR_W = 4;
    private static final int HORIZ_PAD = 12;
    private static final int CHEVRON_GAP = 6;
    private static final int BORDER_THICKNESS = 1;

    private static final int BG_BTN = 0xCC0A0E12;
    private static final int BG_POPUP = 0xEE0A0E12;
    private static final int BORDER_BTN = 0x33FFFFFF;
    private static final int BORDER_POPUP = 0x55FFFFFF;
    private static final int HOVER_BG = 0x33FFFFFF;
    private static final int SELECTED_BG = 0x554FA8FF;
    private static final int TEXT_MAIN = 0xFFE8EDF2;
    private static final int TEXT_DIM = 0xFF8A9099;
    private static final int TEXT_SELECTED = 0xFFFFD76A; // DocPalette.PROGRESS

    private static final long FADE_MS = 150L;

    private static boolean open;
    private static long animStartedAt;       // 0 = 当前 alpha 已稳定；>0 表示正在过渡
    private static boolean animOpening;
    private static float alpha;

    private static int scrollOffset;

    private static int lastBtnX, lastBtnY, lastBtnW;

    private DeployPresetDropdown() {
    }

    /** 按钮高度，供 Screen 给下方预留位置（与 {@link DeployPresetBar#barHeight()} 等价）。 */
    public static int barHeight() {
        return BTN_H;
    }

    public static void onOpened() {
        open = false;
        animStartedAt = 0;
        animOpening = false;
        alpha = 0f;
        scrollOffset = 0;
    }

    public static void onClosed() {
        open = false;
        animStartedAt = 0;
        alpha = 0f;
        scrollOffset = 0;
    }

    /** true 表示本次点击被本控件吃掉（不应该再落到地图选点逻辑）。 */
    public static boolean handleClick(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return false;
        }
        if (open) {
            if (inRect(mouseX, mouseY, lastBtnX, lastBtnY, lastBtnW, BTN_H)) {
                toggleOpen();
                return true;
            }
            int popupH = currentPopupHeight();
            int px = lastBtnX;
            int py = lastBtnY - popupH - 2;
            if (inRect(mouseX, mouseY, px, py, lastBtnW, popupH)) {
                int idx = pickIndex((int) mouseY - py);
                List<LoadoutPresetPreviewDto> presets = currentPresets();
                if (idx >= 0 && idx < presets.size()) {
                    selectPreset(presets.get(idx));
                }
                toggleOpen();
                return true;
            }
            open = false;
            animStartedAt = System.currentTimeMillis();
            animOpening = false;
            return true;
        }
        if (inRect(mouseX, mouseY, lastBtnX, lastBtnY, lastBtnW, BTN_H)) {
            toggleOpen();
            return true;
        }
        return false;
    }

/** 滚轮滚动：在弹层打开时滚动预设列表。 */
    public static boolean handleScroll(double scrollDelta) {
        if (!open || scrollDelta == 0) {
            return false;
        }
        List<LoadoutPresetPreviewDto> presets = currentPresets();
        int rows = presets.size();
        int popupH = currentPopupHeight();
        int visibleItems = popupH / ITEM_H;
        if (rows <= visibleItems) {
            return true;
        }
        int dir = scrollDelta > 0 ? -1 : 1;
        int newOffset = Math.max(0, Math.min(rows - visibleItems, scrollOffset + dir));
        if (newOffset != scrollOffset) {
            scrollOffset = newOffset;
        }
        return true;
    }

    /** Escape 键关闭弹层（Screen 在 keyPressed 阶段调用）。 */
public static boolean handleEscape() {
        if (open) {
            open = false;
            animStartedAt = System.currentTimeMillis();
            animOpening = false;
            return true;
        }
        return false;
    }

    public static void render(GuiGraphics gg, Font font, int screenW, int bottomY,
                              int mouseX, int mouseY) {
        advanceAnim();

        DeployLoadoutDto loadout = ClientDeployLoadout.get();
        boolean hasSelection = loadout != null && !loadout.presetId().isEmpty();
        String name = hasSelection && !loadout.presetName().isBlank()
                ? loadout.presetName()
                : "未命名配装";

        int textW = font.width(name);
        int chevronW = font.width("▼");
        int btnW = HORIZ_PAD + textW + CHEVRON_GAP + chevronW + HORIZ_PAD;
        int btnX = screenW / 2 - btnW / 2;
        // 按钮相对 bottomY 居中：bottomY 是 Screen 给的"按钮底部"，向上偏移 BTN_H
        int btnY = bottomY - BTN_H;
        lastBtnX = btnX;
        lastBtnY = btnY;
        lastBtnW = btnW;

        List<LoadoutPresetPreviewDto> presets = currentPresets();
        // 弹层高度跟随 alpha 收缩，避免关闭到一半时高度突变
        int popupH = (int) Math.round(currentPopupHeight() * Math.max(0.35f, alpha));

        if (alpha > 0.01f && !presets.isEmpty()) {
            int popX = btnX;
            int popY = btnY - popupH - 2;
            int popW = btnW;
            int a = (int) (alpha * 255);
            int bg = (a << 24) | (BG_POPUP & 0x00FFFFFF);
            int border = ((int) (alpha * 0x55) << 24) | (BORDER_POPUP & 0x00FFFFFF);
            gg.fill(popX, popY, popX + popW, popY + popupH, bg);
            gg.fill(popX, popY, popX + popW, popY + BORDER_THICKNESS, border);
            gg.fill(popX, popY + popupH - BORDER_THICKNESS, popX + popW, popY + popupH, border);
            gg.fill(popX, popY, popX + BORDER_THICKNESS, popY + popupH, border);
            gg.fill(popX + popW - BORDER_THICKNESS, popY, popX + popW, popY + popupH, border);

            int visibleItems = popupH / ITEM_H;
            int endIdx = Math.min(presets.size(), scrollOffset + visibleItems);
            String selectedId = hasSelection ? loadout.presetId() : "";
            for (int i = scrollOffset; i < endIdx; i++) {
                LoadoutPresetPreviewDto p = presets.get(i);
                int rowY = popY + (i - scrollOffset) * ITEM_H;
                boolean selected = p.id().equals(selectedId);
                boolean hovered = !selected
                        && inRect(mouseX, mouseY, popX, rowY, popW, ITEM_H);
                if (selected) {
                    gg.fill(popX + 1, rowY, popX + popW - 1, rowY + ITEM_H,
                            ((int) (alpha * 0x55) << 24) | (SELECTED_BG & 0x00FFFFFF));
                } else if (hovered) {
                    gg.fill(popX + 1, rowY, popX + popW - 1, rowY + ITEM_H,
                            ((int) (alpha * 0x33) << 24) | (HOVER_BG & 0x00FFFFFF));
                }
                String label = p.displayName().isBlank() ? "未命名配装" : p.displayName();
                int fg = selected ? TEXT_SELECTED : TEXT_MAIN;
                gg.drawString(font, label,
                        popX + HORIZ_PAD, rowY + (ITEM_H - 8) / 2 + 1,
                        (a << 24) | (fg & 0x00FFFFFF), false);
            }

            if (presets.size() > visibleItems) {
                int trackX = popX + popW - SCROLLBAR_W - 1;
                int trackY = popY + 1;
                int trackH = popupH - 2;
                gg.fill(trackX, trackY, trackX + SCROLLBAR_W, trackY + trackH,
                        ((int) (alpha * 0x33) << 24) | (BORDER_POPUP & 0x00FFFFFF));
                int thumbH = Math.max(8, trackH * visibleItems / presets.size());
                int thumbY = trackY + (int) ((long) scrollOffset * (trackH - thumbH)
                        / Math.max(1, presets.size() - visibleItems));
                gg.fill(trackX, thumbY, trackX + SCROLLBAR_W, thumbY + thumbH,
                        ((int) (alpha * 0x88) << 24) | 0x00FFFFFF);
            }
        }

        int btnAlpha = Math.max(0xB0, (int) (alpha * 0xCC + 0x33));
        int btnBg = (Math.min(0xFF, btnAlpha) << 24) | (BG_BTN & 0x00FFFFFF);
        int btnBorder = ((int) ((0.4f + alpha * 0.6f) * 0x44) << 24)
                | (BORDER_BTN & 0x00FFFFFF);
        gg.fill(btnX, btnY, btnX + btnW, btnY + BTN_H, btnBg);
        gg.fill(btnX, btnY, btnX + btnW, btnY + BORDER_THICKNESS, btnBorder);
        gg.fill(btnX, btnY + BTN_H - BORDER_THICKNESS, btnX + btnW, btnY + BTN_H, btnBorder);
        gg.fill(btnX, btnY, btnX + BORDER_THICKNESS, btnY + BTN_H, btnBorder);
        gg.fill(btnX + btnW - BORDER_THICKNESS, btnY, btnX + btnW, btnY + BTN_H, btnBorder);
        int textColor = hasSelection ? TEXT_MAIN : TEXT_DIM;
        float textA = Math.min(1f, 0.6f + alpha * 0.4f);
        gg.drawString(font, name, btnX + HORIZ_PAD, btnY + (BTN_H - 8) / 2 + 1,
                ((int) (textA * 255) << 24) | (textColor & 0x00FFFFFF), false);
        int chevronColor = open ? TEXT_SELECTED : TEXT_DIM;
        gg.drawString(font, "▼", btnX + btnW - HORIZ_PAD - chevronW,
                btnY + (BTN_H - 8) / 2 + 1,
                ((int) (textA * 255) << 24) | (chevronColor & 0x00FFFFFF), false);
    }

    private static void advanceAnim() {
        long now = System.currentTimeMillis();
        if (animStartedAt == 0) {
            alpha = open ? 1f : 0f;
            return;
        }
        long elapsed = now - animStartedAt;
        float t = Math.min(1f, elapsed / (float) FADE_MS);
        alpha = animOpening ? t : (1f - t);
        if (t >= 1f) {
            animStartedAt = 0;
            alpha = open ? 1f : 0f;
        }
    }

    private static void toggleOpen() {
        open = !open;
        animStartedAt = System.currentTimeMillis();
        animOpening = open;
        if (open) {
            scrollOffset = 0;
        }
    }

    private static int currentPopupHeight() {
        int rows = currentPresets().size();
        if (rows == 0) {
            return 0;
        }
        return Math.min(MAX_POPUP_H, rows * ITEM_H);
    }

    private static int pickIndex(int relY) {
        int row = relY / ITEM_H;
        int idx = scrollOffset + row;
        List<LoadoutPresetPreviewDto> presets = currentPresets();
        return idx >= 0 && idx < presets.size() ? idx : -1;
    }

    /**
     * 取当前 (玩家阵营, 当前选中兵种) 的全部预设。
     * 玩家阵营来自 BattleHudDto.myFaction（部署时仍在对局内，事实与服务端同源）；
     * 兵种取自 ClientDeployLoadout（最近一次服务器推送）。
     */
    private static List<LoadoutPresetPreviewDto> currentPresets() {
        LoadoutConfigDto config = ClientLoadoutConfig.get();
        DeployLoadoutDto loadout = ClientDeployLoadout.get();
        if (config == null || loadout == null) {
            return Collections.emptyList();
        }
        String factionId = playerFactionId();
        if (factionId == null) {
            return Collections.emptyList();
        }
        String classId = loadout.classId();
        for (FactionPresetsDto fp : config.factions()) {
            if (!fp.factionId().equals(factionId)) {
                continue;
            }
            for (ClassPresetsDto cp : fp.classes()) {
                if (cp.classId().equals(classId)) {
                    return cp.presets();
                }
            }
        }
        return Collections.emptyList();
    }

    private static String playerFactionId() {
        var hud = ClientBattleHud.hud();
        if (hud == null) {
            return null;
        }
        int myFaction = hud.myFaction();
        if (myFaction == 1) {
            return "ALPHA";
        }
        if (myFaction == 2) {
            return "BRAVO";
        }
        return null;
    }

    private static void selectPreset(LoadoutPresetPreviewDto preset) {
        DeployLoadoutDto loadout = ClientDeployLoadout.get();
        String factionId = playerFactionId();
        if (loadout == null || factionId == null) {
            return;
        }
        // mapName 留空：服务端 LoadoutConfigService.selectPreset 会用 ArenaKey.resolve 回填
        BattlefieldNetwork.CHANNEL.sendToServer(new LoadoutSelectPresetPacket(
                "", factionId, loadout.classId(), preset.id()));
    }

    private static boolean inRect(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
}