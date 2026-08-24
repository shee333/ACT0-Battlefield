package org.shee33.act0.battlefield.client.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import org.shee33.act0.battlefield.client.ClientLoadoutConfig;
import org.shee33.act0.battlefield.client.ClientNames;
import org.shee33.act0.battlefield.core.SoldierClass;
import org.shee33.act0.battlefield.core.arena.LoadoutPresetDef;
import org.shee33.act0.battlefield.core.arena.LoadoutSlot;
import org.shee33.act0.battlefield.network.BattlefieldNetwork;
import org.shee33.act0.battlefield.network.ClassPresetsDto;
import org.shee33.act0.battlefield.network.DeploySlotDto;
import org.shee33.act0.battlefield.network.FactionPresetsDto;
import org.shee33.act0.battlefield.network.LoadoutConfigDto;
import org.shee33.act0.battlefield.network.LoadoutPresetPreviewDto;
import org.shee33.act0.battlefield.network.LoadoutSelectClassPacket;
import org.shee33.act0.battlefield.network.LoadoutSelectPresetPacket;
import org.shee33.act0.battlefield.network.RequestLoadoutConfigPacket;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

import static org.shee33.act0.battlefield.client.screen.BattlefieldLoadoutLayout.inRect;
import static org.shee33.act0.battlefield.client.screen.BattlefieldLoadoutLayout.withAlpha;

/**
 * 对局<b>之外</b>的配装界面：地图 → 阵营（ALPHA/BRAVO）→ 兵种 → <b>管理员预设配装列表</b>。
 *
 * <p>玩家只做<b>选择与预览</b>：点某行即把该套设为该（阵营,兵种）的选中配装，内容全部由
 * 管理员在服务端用 {@code /aew1 loadout} 预设，本界面没有任何编辑入口。
 *
 * <p>本地只保留纯 UI 状态：当前阵营标签、展开的兵种。数据每帧重读服务端快照
 * {@link ClientLoadoutConfig}，界面永远等于服务端事实。
 */
public final class BattlefieldLoadoutScreen extends Screen {

    private static final int PAD = 16;
    private static final int TITLE_Y = 12;
    private static final int MAP_TAB_Y = 32;
    private static final int TAB_H = 12;
    private static final int MAP_RULE_Y = MAP_TAB_Y + TAB_H;

    private static final int ROW_H = 18;
    private static final int SECTION_GAP = 8;

    private static final int TAB_PAD_X = 6;
    private static final int TAB_GAP = 4;
    private static final int TAB_MAX_TEXT_W = 64;
    private static final int ARROW_W = 8;
    private static final int ARROW_GAP = 4;

    private static final long FADE_MS = 220L;

    private static final int HOVER_TINT = 0x18FFFFFF;
    private static final int RULE_COLOR = 0x663A3A3A;
    private static final int ACCENT_BAR_W = 2;

    private enum Kind { MAP, FACTION, CLASS, PRESET }

    private record Hit(int x, int y, int w, int h, Kind kind, int index, String id) {
    }

    private final long openedAtMs = System.currentTimeMillis();
    private final List<Hit> hits = new ArrayList<>();

    private int mapTabFirst;
    /** 当前查看的阵营（纯客户端标签，默认取快照里的 factionId）。 */
    private String factionTab = "ALPHA";

    public BattlefieldLoadoutScreen() {
        super(Component.literal("配装"));
    }

    @Override
    protected void init() {
        BattlefieldNetwork.CHANNEL.sendToServer(new RequestLoadoutConfigPacket(""));
    }

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        renderBackground(gg);
        hits.clear();

        long elapsed = System.currentTimeMillis() - openedAtMs;
        float chromeA = BattlefieldLoadoutLayout.fadeIn(elapsed, FADE_MS);

        int contentX = PAD;
        int contentW = Math.max(BattlefieldLoadoutLayout.MIN_LEFT_W, width - PAD * 2);
        PixelTheme.panel(gg, contentX - 8, TITLE_Y - 8, contentW + 16, height - TITLE_Y);

        gg.drawString(font, "配 装", contentX, TITLE_Y, withAlpha(PixelTheme.TEXT, chromeA), false);
        String hint = "选择配装 · 内容由管理员预设";
        gg.drawString(font, hint, contentX + contentW - font.width(hint), TITLE_Y,
                withAlpha(PixelTheme.TEXT_DIM, chromeA), false);

        LoadoutConfigDto dto = ClientLoadoutConfig.get();
        if (dto == null) {
            drawNotice(gg, contentX, contentW, "正在读取配装数据…", "");
            return;
        }
        if (dto.isEmpty()) {
            drawNotice(gg, contentX, contentW, "服务器尚未配置任何地图",
                    "管理员需先执行 /aew1 map <名称> 建图");
            return;
        }

        drawMapTabs(gg, dto, contentX, contentW, mouseX, mouseY, chromeA);
        FactionPresetsDto faction = factionOf(dto, factionTab);
        if (faction == null) {
            return;
        }
        drawFactionTabs(gg, dto, faction, contentX, mouseX, mouseY, chromeA);

        SoldierClass selectedClass = SoldierClass.byIdOrDefault(dto.selectedClassId());
        drawClassTabs(gg, dto, faction, selectedClass, contentX, contentW, mouseX, mouseY, chromeA);
        drawPresetList(gg, faction, selectedClass, contentX, contentW, mouseX, mouseY, chromeA);
    }

    private void drawNotice(GuiGraphics gg, int contentX, int contentW, String title, String detail) {
        int y = MAP_RULE_Y + 12;
        int h = detail.isEmpty() ? 28 : 40;
        gg.fill(contentX, y, contentX + contentW, y + h,
                withAlpha(PixelTheme.BEVEL_SHADOW, 0.6f));
        gg.fill(contentX, y, contentX + ACCENT_BAR_W, y + h, withAlpha(PixelTheme.ALPHA_COLOR, 1f));
        gg.drawString(font, title, contentX + ACCENT_BAR_W + 6, y + 8, PixelTheme.TEXT, false);
        if (!detail.isEmpty()) {
            gg.drawString(font, detail, contentX + ACCENT_BAR_W + 6, y + 22,
                    withAlpha(PixelTheme.TEXT_DIM, 1f), false);
        }
    }

    private void drawMapTabs(GuiGraphics gg, LoadoutConfigDto dto, int contentX, int contentW,
                             int mouseX, int mouseY, float alpha) {
        List<String> maps = dto.mapNames();
        int[] widths = tabWidths(maps);
        boolean overflow = BattlefieldLoadoutLayout.visibleTabCount(widths, TAB_GAP, contentW, 0) < maps.size();
        int gutter = overflow ? ARROW_W + ARROW_GAP : 0;
        int stripX = contentX + gutter;
        int stripW = contentW - gutter * 2;

        int selected = BattlefieldLoadoutLayout.indexOfOrFirst(maps, dto.mapName());
        mapTabFirst = BattlefieldLoadoutLayout.ensureTabVisible(mapTabFirst, selected, widths, TAB_GAP, stripW);
        int count = BattlefieldLoadoutLayout.visibleTabCount(widths, TAB_GAP, stripW, mapTabFirst);

        gg.fill(contentX, MAP_RULE_Y, contentX + contentW, MAP_RULE_Y + 1, withAlpha(RULE_COLOR, alpha));
        for (int i = mapTabFirst; i < Math.min(maps.size(), mapTabFirst + count); i++) {
            int x = BattlefieldLoadoutLayout.tabX(widths, TAB_GAP, stripX, mapTabFirst, i);
            drawTab(gg, maps.get(i), x, MAP_TAB_Y, widths[i], i == selected, mouseX, mouseY, alpha);
            hits.add(new Hit(x, MAP_TAB_Y - 2, widths[i], TAB_H + 2, Kind.MAP, i, maps.get(i)));
        }
        if (overflow) {
            drawArrow(gg, contentX, MAP_TAB_Y, false, mapTabFirst > 0, alpha);
            hits.add(new Hit(contentX, MAP_TAB_Y - 2, ARROW_W, TAB_H + 2, Kind.MAP, -1, ""));
            int rx = contentX + contentW - ARROW_W;
            boolean more = mapTabFirst < BattlefieldLoadoutLayout.maxTabScroll(widths, TAB_GAP, stripW);
            drawArrow(gg, rx, MAP_TAB_Y, true, more, alpha);
            hits.add(new Hit(rx, MAP_TAB_Y - 2, ARROW_W, TAB_H + 2, Kind.MAP, 1, ""));
        }
    }

    private void drawFactionTabs(GuiGraphics gg, LoadoutConfigDto dto, FactionPresetsDto current,
                                 int contentX, int mouseX, int mouseY, float alpha) {
        int y = MAP_RULE_Y + 6;
        int x = contentX;
        gg.drawString(font, "阵营", x, y, withAlpha(PixelTheme.TEXT_DIM, alpha), false);
        x += font.width("阵营") + 10;
        for (FactionPresetsDto f : dto.factions()) {
            String label = f.factionId().equals("ALPHA") ? "阵营 1" : "阵营 2";
            int w = Math.min(TAB_MAX_TEXT_W, font.width(label)) + TAB_PAD_X * 2;
            drawTab(gg, label, x, y, w, f == current, mouseX, mouseY, alpha);
            hits.add(new Hit(x, y - 2, w, TAB_H + 2, Kind.FACTION, 0, f.factionId()));
            x += w + TAB_GAP;
        }
    }

    private void drawClassTabs(GuiGraphics gg, LoadoutConfigDto dto, FactionPresetsDto faction,
                               SoldierClass selected, int contentX, int contentW,
                               int mouseX, int mouseY, float alpha) {
        int y = MAP_RULE_Y + 24;
        gg.fill(contentX, y + TAB_H, contentX + contentW, y + TAB_H + 1, withAlpha(RULE_COLOR, alpha));
        int x = contentX;
        for (SoldierClass c : SoldierClass.values()) {
            int w = Math.min(TAB_MAX_TEXT_W, font.width(c.displayName())) + TAB_PAD_X * 2;
            drawTab(gg, c.displayName(), x, y, w, c == selected, mouseX, mouseY, alpha);
            hits.add(new Hit(x, y - 2, w, TAB_H + 2, Kind.CLASS, c.ordinal(), c.id()));
            x += w + TAB_GAP;
        }
    }

    private void drawPresetList(GuiGraphics gg, FactionPresetsDto faction, SoldierClass soldierClass,
                                int contentX, int contentW, int mouseX, int mouseY, float alpha) {
        ClassPresetsDto cls = classOf(faction, soldierClass);
        int y = MAP_RULE_Y + 24 + TAB_H + 8;
        if (cls == null || cls.presets().isEmpty()) {
            gg.drawString(font, "该阵营该兵种还没有配装预设（管理员用 /aew1 loadout 配置）",
                    contentX, y + 4, withAlpha(PixelTheme.TEXT_DIM, alpha), false);
            return;
        }
        for (LoadoutPresetPreviewDto preset : cls.presets()) {
            boolean selected = preset.id().equals(cls.selectedPresetId());
            boolean hovered = !selected && inRect(mouseX, mouseY, contentX, y, contentW, ROW_H);
            if (selected) {
                gg.fill(contentX, y, contentX + contentW, y + ROW_H, withAlpha(
                        PixelTheme.blend(PixelTheme.PANEL_BG, PixelTheme.ALPHA_COLOR, 0.24f), alpha));
                gg.fill(contentX, y, contentX + ACCENT_BAR_W, y + ROW_H,
                        withAlpha(PixelTheme.ALPHA_COLOR, alpha));
            } else if (hovered) {
                gg.fill(contentX, y, contentX + contentW, y + ROW_H, withAlpha(HOVER_TINT, alpha));
            }
            int tx = contentX + ACCENT_BAR_W + 6;
            gg.drawString(font, (selected ? "▶ " : "  ") + presetName(preset),
                    tx, y + 4, withAlpha(selected ? PixelTheme.TEXT : PixelTheme.TEXT_DIM, alpha), false);
            int slotX = tx + 110;
            for (LoadoutSlot slot : LoadoutPresetDef.PRESET_SLOTS) {
                String itemName = presetItemName(preset, slot);
                gg.drawString(font, PixelTheme.fit(font, itemName, 96),
                        slotX, y + 4, withAlpha(PixelTheme.TEXT_DIM, alpha), false);
                slotX += 104;
            }
            hits.add(new Hit(contentX, y, contentW, ROW_H, Kind.PRESET, 0, preset.id()));
            y += ROW_H;
        }
        y += 6;
        gg.drawString(font, "点击某行即选用该配装（内容由管理员预设）",
                contentX, y, withAlpha(PixelTheme.TEXT_DIM, alpha * 0.8f), false);
    }

    private static String presetName(LoadoutPresetPreviewDto preset) {
        return preset.displayName().isBlank() ? "未命名配装" : preset.displayName();
    }

    private static String presetItemName(LoadoutPresetPreviewDto preset, LoadoutSlot slot) {
        for (DeploySlotDto dto : preset.slots()) {
            if (dto.slotIndex() == slot.hotbarIndex() && !dto.itemId().isEmpty()) {
                return slot.displayName() + " " + ClientNames.itemName(dto.itemId());
            }
        }
        return slot.displayName() + " 空";
    }

    private void drawTab(GuiGraphics gg, String label, int x, int y, int w, boolean selected,
                         int mouseX, int mouseY, float alpha) {
        boolean hovered = inRect(mouseX, mouseY, x, y - 2, w, TAB_H + 2);
        if (selected) {
            gg.fill(x, y - 2, x + w, y + TAB_H,
                    withAlpha(PixelTheme.blend(PixelTheme.PANEL_BG, PixelTheme.ALPHA_COLOR, 0.22f), alpha));
            gg.fill(x, y + TAB_H, x + w, y + TAB_H + 2, withAlpha(PixelTheme.ALPHA_COLOR, alpha));
        } else if (hovered) {
            gg.fill(x, y - 2, x + w, y + TAB_H, withAlpha(HOVER_TINT, alpha));
        }
        gg.drawString(font, PixelTheme.fit(font, label, w - TAB_PAD_X * 2), x + TAB_PAD_X, y + 2,
                withAlpha(selected ? PixelTheme.TEXT : PixelTheme.TEXT_DIM, alpha), false);
    }

    private static void drawArrow(GuiGraphics gg, int x, int y, boolean right, boolean enabled, float alpha) {
        int color = withAlpha(enabled ? PixelTheme.TEXT : PixelTheme.BEVEL_SHADOW, alpha);
        int base = x + (ARROW_W - 4) / 2;
        for (int i = 0; i < 4; i++) {
            int col = right ? base + i : base + 3 - i;
            gg.fill(col, y + 2 + i, col + 1, y + 9 - i, color);
        }
    }

    // ---- 输入 ----

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        LoadoutConfigDto dto = ClientLoadoutConfig.get();
        if (button != 0 || dto == null) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        for (Hit hit : hits) {
            if (inRect(mouseX, mouseY, hit.x(), hit.y(), hit.w(), hit.h())) {
                activate(hit, dto);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void activate(Hit hit, LoadoutConfigDto dto) {
        switch (hit.kind()) {
            case MAP -> {
                if (hit.index() < 0) {
                    mapTabFirst = Math.max(0, mapTabFirst + hit.index());
                } else {
                    BattlefieldNetwork.CHANNEL.sendToServer(new RequestLoadoutConfigPacket(hit.id()));
                }
            }
            case FACTION -> factionTab = hit.id();
            case CLASS -> BattlefieldNetwork.CHANNEL.sendToServer(
                    new LoadoutSelectClassPacket(dto.mapName(), hit.id(), false));
            case PRESET -> BattlefieldNetwork.CHANNEL.sendToServer(
                    new LoadoutSelectPresetPacket(dto.mapName(), factionTab,
                            SoldierClass.byIdOrDefault(dto.selectedClassId()).id(), hit.id()));
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        LoadoutConfigDto dto = ClientLoadoutConfig.get();
        if (dto != null && !dto.isEmpty()
                && (keyCode == GLFW.GLFW_KEY_LEFT || keyCode == GLFW.GLFW_KEY_RIGHT)) {
            SoldierClass[] all = SoldierClass.values();
            int cur = SoldierClass.byIdOrDefault(dto.selectedClassId()).ordinal();
            int next = BattlefieldLoadoutLayout.wrapIndex(
                    cur + (keyCode == GLFW.GLFW_KEY_RIGHT ? 1 : -1), all.length);
            BattlefieldNetwork.CHANNEL.sendToServer(
                    new LoadoutSelectClassPacket(dto.mapName(), all[next].id(), false));
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(null);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Nullable
    private static FactionPresetsDto factionOf(LoadoutConfigDto dto, String factionId) {
        for (FactionPresetsDto f : dto.factions()) {
            if (f.factionId().equals(factionId)) {
                return f;
            }
        }
        return dto.factions().isEmpty() ? null : dto.factions().get(0);
    }

    @Nullable
    private static ClassPresetsDto classOf(FactionPresetsDto faction, SoldierClass soldierClass) {
        for (ClassPresetsDto c : faction.classes()) {
            if (c.classId().equals(soldierClass.id())) {
                return c;
            }
        }
        return null;
    }

    private int[] tabWidths(List<String> labels) {
        int[] out = new int[labels.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = Math.min(TAB_MAX_TEXT_W, font.width(labels.get(i))) + TAB_PAD_X * 2;
        }
        return out;
    }
}
