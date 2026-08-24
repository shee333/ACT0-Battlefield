package org.shee33.act0.battlefield.client.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import org.shee33.act0.battlefield.client.ClientLoadoutConfig;
import org.shee33.act0.battlefield.client.ClientNames;
import org.shee33.act0.battlefield.core.SoldierClass;
import org.shee33.act0.battlefield.core.arena.ClassLoadouts;
import org.shee33.act0.battlefield.core.arena.LoadoutSlot;
import org.shee33.act0.battlefield.network.BattlefieldNetwork;
import org.shee33.act0.battlefield.network.ClassLoadoutDto;
import org.shee33.act0.battlefield.network.DeployOptionDto;
import org.shee33.act0.battlefield.network.DeploySlotOptionsDto;
import org.shee33.act0.battlefield.network.LoadoutConfigDto;
import org.shee33.act0.battlefield.network.LoadoutEditPacket;
import org.shee33.act0.battlefield.network.LoadoutPresetDto;
import org.shee33.act0.battlefield.network.LoadoutRenamePacket;
import org.shee33.act0.battlefield.network.LoadoutSelectClassPacket;
import org.shee33.act0.battlefield.network.LoadoutSelectPresetPacket;
import org.shee33.act0.battlefield.network.RequestLoadoutConfigPacket;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

import static org.shee33.act0.battlefield.client.screen.BattlefieldLoadoutLayout.inRect;
import static org.shee33.act0.battlefield.client.screen.BattlefieldLoadoutLayout.withAlpha;

/**
 * 对局<b>之外</b>也能用的配装界面：地图标签 + <b>4×4 配装网格</b>（4 兵种 × 4 套可命名配装）+ 槽位编辑器。
 *
 * <p>入口是全局化之后的 ESC 暂停菜单里的「配装」按钮，因此本类<b>不得假设存在任何对局状态</b>——
 * 地图列表、网格数据、军械库全部来自服务端推送的 {@link ClientLoadoutConfig} 快照。
 *
 * <p><b>为什么每帧重读快照而不缓存一份可变副本</b>：写操作（换图 / 点格 / 换装备 / 改名）在服务端
 * 一律以"整屏快照回发"作为回执，界面永远等于服务端事实。
 *
 * <p><b>网格语义</b>：列 = 兵种（突击/侦查/支援/工程），行 = 该兵种下的 4 套配装。格子显示
 * 「配装名（未命名回退『配装 N』）+ 主武器名」。点击某格 = 选定该兵种并把该格设为激活套，
 * 激活套决定部署/出生时发什么装。网格下方是改名输入框。
 *
 * <p>本地只保留纯 UI 状态：展开的是哪个槽位、选项列表翻到第几页、改名框与选中格的同步锚点。
 */
public final class BattlefieldLoadoutScreen extends Screen {

    // ---- 8px 网格纵向节奏 ----
    private static final int PAD = 16;
    private static final int TITLE_Y = 12;
    private static final int MAP_TAB_Y = 32;
    private static final int TAB_H = 12;
    private static final int MAP_RULE_Y = MAP_TAB_Y + TAB_H;

    private static final int GRID_Y = 52;
    private static final int GRID_HEADER_H = 10;
    private static final int GRID_ROW_H = 20;
    private static final int GRID_ROW_GAP = 2;
    private static final int GRID_COL_GAP = 6;
    private static final int GRID_CELL_W = 46;
    private static final int GRID_W = 4 * GRID_CELL_W + 3 * GRID_COL_GAP;
    private static final int GRID_H = GRID_HEADER_H + 4 * GRID_ROW_H + 3 * GRID_ROW_GAP;

    private static final int RENAME_Y = GRID_Y + GRID_H + 6;
    private static final int RENAME_H = 12;
    private static final int RENAME_LABEL_W = 40;

    private static final int TAB_PAD_X = 6;
    private static final int TAB_GAP = 4;
    /** 单个标签的文字上限：超长中文地图名不允许一个人吃掉整条标签栏。 */
    private static final int TAB_MAX_TEXT_W = 64;
    private static final int ARROW_W = 8;
    private static final int ARROW_GAP = 4;

    private static final int ROW_H = 16;
    private static final int GROUP_HEADER_H = 12;
    private static final int GROUP_GAP = 8;
    private static final int COL_GAP = 8;
    private static final int PAGER_H = 12;
    private static final int ROW_PAD_X = 6;
    private static final int ACCENT_BAR_W = 2;

    private static final long FADE_MS = 220L;
    /** 正文相对顶部框架的错峰，制造"陆续到达"而不是整屏一起亮。 */
    private static final long BODY_DELAY_MS = 80L;

    private static final int HOVER_TINT = 0x18FFFFFF;
    private static final int RULE_COLOR = 0x663A3A3A;

    /** 一次点击可能触发的动作类型。 */
    private enum Kind {
        MAP,
        /** 配装网格的某一格：携带 classId + presetIndex。 */
        PRESET,
        SLOT,
        OPTION,
        PAGE
    }

    private record Hit(int x, int y, int w, int h, Kind kind, int index, String id) {
    }

    private final long openedAtMs = System.currentTimeMillis();
    private final List<Hit> hits = new ArrayList<>();

    private int mapTabFirst;
    private int slotHotbarIndex = LoadoutSlot.PRIMARY.hotbarIndex();
    private int optionPage;
    /** 上一帧渲染的槽位，用来识别"玩家刚换了槽位"这一刻并把页码跳到当前装备所在页。 */
    private int pagedSlotIndex = -1;

    private int optionRegionX;
    private int optionRegionY;
    private int optionRegionW;
    private int optionRegionH;
    private int optionTotal;
    private int optionPerPage = 1;

    /** 改名输入框；值与选中格（classId#presetIndex）绑定。 */
    private EditBox renameBox;
    /** 上一帧同步过改名框内容的选中格锚点，变了才重置输入框内容。 */
    private String renameSyncedKey = "";

    public BattlefieldLoadoutScreen() {
        super(Component.literal("配装"));
    }

    /** 空串 = 「随便给我第一张图」，客户端此时还不知道服务端有哪些地图。 */
    @Override
    protected void init() {
        BattlefieldNetwork.CHANNEL.sendToServer(new RequestLoadoutConfigPacket(""));
        int gridX = PAD;
        renameBox = new EditBox(font, gridX + RENAME_LABEL_W, RENAME_Y, GRID_W - RENAME_LABEL_W - 4, RENAME_H,
                Component.literal("配装名"));
        renameBox.setMaxLength(16);
        renameBox.setResponder(this::onRenameTyped);
        addRenderableWidget(renameBox);
    }

    // =====================================================================
    // 渲染
    // =====================================================================

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        renderBackground(gg);
        hits.clear();

        long elapsed = System.currentTimeMillis() - openedAtMs;
        float chromeA = BattlefieldLoadoutLayout.fadeIn(elapsed, FADE_MS);
        float bodyA = BattlefieldLoadoutLayout.fadeIn(elapsed - BODY_DELAY_MS, FADE_MS);

        int contentX = PAD;
        int contentW = Math.max(BattlefieldLoadoutLayout.MIN_LEFT_W, width - PAD * 2);
        PixelTheme.panel(gg, contentX - 8, TITLE_Y - 8, contentW + 16, height - TITLE_Y);

        drawHeader(gg, contentX, contentW, chromeA);

        LoadoutConfigDto dto = ClientLoadoutConfig.get();
        if (dto == null) {
            drawNotice(gg, contentX, contentW, "正在读取配装数据…", "", bodyA);
            return;
        }
        if (dto.isEmpty()) {
            drawNotice(gg, contentX, contentW, "服务器尚未配置任何地图",
                    "管理员需先执行 /aew1 map <名称> 建图", bodyA);
            return;
        }

        drawMapTabs(gg, dto, contentX, contentW, mouseX, mouseY, chromeA);

        // 左栏：4×4 配装网格 + 改名框
        drawPresetGrid(gg, dto, contentX, mouseX, mouseY, bodyA);
        syncRenameBox(dto);
        renameBox.render(gg, mouseX, mouseY, partialTick);
        gg.drawString(font, "改名", contentX, RENAME_Y + 2,
                withAlpha(PixelTheme.TEXT_DIM, bodyA), false);

        // 右栏：选中兵种激活套的槽位编辑器（左：槽位行；右：可选项）
        SoldierClass soldier = SoldierClass.byIdOrDefault(dto.selectedClassId());
        ClassLoadoutDto cls = classOf(dto, soldier);
        if (cls == null || cls.slotOptions().isEmpty()) {
            drawNotice(gg, contentX + GRID_W + 12, contentW - GRID_W - 12, "本地图尚未配置军械库",
                    "该兵种在「" + dto.mapName() + "」上没有可用槽位", bodyA);
            return;
        }
        drawBody(gg, cls.slotOptions(), contentX + GRID_W + 12,
                contentW - GRID_W - 12 - COL_GAP, mouseX, mouseY, bodyA);
    }

    private void drawHeader(GuiGraphics gg, int contentX, int contentW, float alpha) {
        gg.drawString(font, "配 装", contentX, TITLE_Y,
                withAlpha(PixelTheme.TEXT, alpha), false);
        String hint = "ESC 返回";
        gg.drawString(font, hint, contentX + contentW - font.width(hint), TITLE_Y,
                withAlpha(PixelTheme.TEXT_DIM, alpha), false);
    }

    private void drawNotice(GuiGraphics gg, int contentX, int contentW, String title, String detail, float alpha) {
        int y = GRID_Y;
        int h = detail.isEmpty() ? 28 : 40;
        gg.fill(contentX, y, contentX + contentW, y + h,
                withAlpha(PixelTheme.BEVEL_SHADOW, alpha * 0.6f));
        gg.fill(contentX, y, contentX + ACCENT_BAR_W, y + h,
                withAlpha(PixelTheme.ALPHA_COLOR, alpha));
        int textW = contentW - ROW_PAD_X * 2 - ACCENT_BAR_W;
        gg.drawString(font, PixelTheme.fit(font, title, textW), contentX + ACCENT_BAR_W + ROW_PAD_X, y + 8,
                withAlpha(PixelTheme.TEXT, alpha), false);
        if (!detail.isEmpty()) {
            gg.drawString(font, PixelTheme.fit(font, detail, textW), contentX + ACCENT_BAR_W + ROW_PAD_X, y + 22,
                    withAlpha(PixelTheme.TEXT_DIM, alpha), false);
        }
    }

    // ---- 一级：地图 ----

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

        gg.fill(contentX, MAP_RULE_Y, contentX + contentW, MAP_RULE_Y + 1,
                withAlpha(RULE_COLOR, alpha));
        for (int i = mapTabFirst; i < Math.min(maps.size(), mapTabFirst + count); i++) {
            int x = BattlefieldLoadoutLayout.tabX(widths, TAB_GAP, stripX, mapTabFirst, i);
            drawTab(gg, maps.get(i), x, MAP_TAB_Y, widths[i], i == selected, mouseX, mouseY, alpha);
            hits.add(new Hit(x, MAP_TAB_Y - 2, widths[i], TAB_H + 2, Kind.MAP, i, maps.get(i)));
        }
        if (overflow) {
            drawArrow(gg, contentX, MAP_TAB_Y, false, mapTabFirst > 0, alpha);
            hits.add(new Hit(contentX, MAP_TAB_Y - 2, ARROW_W, TAB_H + 2, Kind.PAGE, -1, ""));
            int rx = contentX + contentW - ARROW_W;
            boolean more = mapTabFirst < BattlefieldLoadoutLayout.maxTabScroll(widths, TAB_GAP, stripW);
            drawArrow(gg, rx, MAP_TAB_Y, true, more, alpha);
            hits.add(new Hit(rx, MAP_TAB_Y - 2, ARROW_W, TAB_H + 2, Kind.PAGE, 1, ""));
        }
    }

    // ---- 二级：4×4 配装网格 ----

    /**
     * 4 列（兵种）× 4 行（配装）网格。格内两行：配装名（未命名回退「配装 N」）+ 主武器名。
     * 当前选中格 = 选中兵种 + 其激活套，用强调底标出。
     */
    private void drawPresetGrid(GuiGraphics gg, LoadoutConfigDto dto, int gridX,
                                int mouseX, int mouseY, float alpha) {
        SoldierClass[] classes = SoldierClass.values();
        SoldierClass selected = SoldierClass.byIdOrDefault(dto.selectedClassId());

        // 表头：4 个兵种名
        for (int c = 0; c < classes.length; c++) {
            int x = gridX + c * (GRID_CELL_W + GRID_COL_GAP);
            String label = classes[c].displayName();
            gg.drawString(font, label, x + (GRID_CELL_W - font.width(label)) / 2, GRID_Y,
                    withAlpha(classes[c] == selected ? PixelTheme.TEXT : PixelTheme.TEXT_DIM, alpha), false);
        }
        gg.fill(gridX, GRID_Y + GRID_HEADER_H - 1, gridX + GRID_W, GRID_Y + GRID_HEADER_H,
                withAlpha(RULE_COLOR, alpha));

        // 4 行 × 4 列格子
        for (int row = 0; row < ClassLoadouts.PRESET_COUNT; row++) {
            int y = GRID_Y + GRID_HEADER_H + 2 + row * (GRID_ROW_H + GRID_ROW_GAP);
            for (int c = 0; c < classes.length; c++) {
                SoldierClass soldierClass = classes[c];
                ClassLoadoutDto cls = classOf(dto, soldierClass);
                boolean isSelected = soldierClass == selected && row == (cls != null ? cls.activeIndex() : 0);
                int x = gridX + c * (GRID_CELL_W + GRID_COL_GAP);
                drawPresetCell(gg, dto, cls, row, x, y, isSelected, mouseX, mouseY, alpha);
                hits.add(new Hit(x, y, GRID_CELL_W, GRID_ROW_H, Kind.PRESET, row, soldierClass.id()));
            }
        }
    }

    private void drawPresetCell(GuiGraphics gg, LoadoutConfigDto dto, @Nullable ClassLoadoutDto cls,
                                int presetIndex, int x, int y, boolean selected,
                                int mouseX, int mouseY, float alpha) {
        boolean hovered = !selected && inRect(mouseX, mouseY, x, y, GRID_CELL_W, GRID_ROW_H);
        if (selected) {
            gg.fill(x, y, x + GRID_CELL_W, y + GRID_ROW_H, withAlpha(
                    PixelTheme.blend(PixelTheme.PANEL_BG, PixelTheme.ALPHA_COLOR, 0.24f), alpha));
            gg.fill(x, y, x + ACCENT_BAR_W, y + GRID_ROW_H,
                    withAlpha(PixelTheme.ALPHA_COLOR, alpha));
        } else if (hovered) {
            gg.fill(x, y, x + GRID_CELL_W, y + GRID_ROW_H, withAlpha(HOVER_TINT, alpha));
        }

        LoadoutPresetDto preset = cls != null && presetIndex < cls.presets().size()
                ? cls.presets().get(presetIndex) : null;
        String name = preset != null && !preset.name().isBlank()
                ? preset.name() : "配装 " + (presetIndex + 1);
        int nameColor = selected ? PixelTheme.TEXT : PixelTheme.TEXT_DIM;
        gg.drawString(font, PixelTheme.fit(font, name, GRID_CELL_W - ROW_PAD_X * 2),
                x + ROW_PAD_X, y + 1, withAlpha(nameColor, alpha), false);

        String weapon = primaryWeaponName(cls, preset);
        if (!weapon.isEmpty()) {
            gg.drawString(font, PixelTheme.fit(font, weapon, GRID_CELL_W - ROW_PAD_X * 2),
                    x + ROW_PAD_X, y + 11, withAlpha(PixelTheme.TEXT_DIM, alpha * 0.9f), false);
        }
    }

    /** 从某套配装 + 该兵种槽位可选项里取出主武器的显示名；无主武器返回空串。 */
    private static String primaryWeaponName(@Nullable ClassLoadoutDto cls, @Nullable LoadoutPresetDto preset) {
        if (cls == null || preset == null) {
            return "";
        }
        String primaryId = null;
        for (var pick : preset.picks()) {
            if (pick.slotIndex() == LoadoutSlot.PRIMARY.hotbarIndex()) {
                primaryId = pick.itemId();
                break;
            }
        }
        if (primaryId == null) {
            return "";
        }
        for (DeploySlotOptionsDto slot : cls.slotOptions()) {
            if (slot.slotIndex() == LoadoutSlot.PRIMARY.hotbarIndex()) {
                return ClientNames.resolve(slot.displayNameOf(primaryId));
            }
        }
        return "";
    }

    /** 改名框内容与选中格绑定：选中格变了才重置输入框，避免打字时被快照回发打断。 */
    private void syncRenameBox(LoadoutConfigDto dto) {
        SoldierClass selected = SoldierClass.byIdOrDefault(dto.selectedClassId());
        ClassLoadoutDto cls = classOf(dto, selected);
        int presetIndex = cls != null ? cls.activeIndex() : 0;
        String key = selected.id() + "#" + presetIndex;
        if (!renameSyncedKey.equals(key)) {
            renameSyncedKey = key;
            String name = cls != null && presetIndex < cls.presets().size()
                    ? cls.presets().get(presetIndex).name() : "";
            renameBox.setValue(name);
        }
    }

    /** 输入框内容变化时无操作（内容由 Enter 统一提交），保持纯 UI 状态最小化。 */
    private void onRenameTyped(String text) {
    }

    // ---- 三级：槽位编辑器（右栏） ----

    private void drawBody(GuiGraphics gg, List<DeploySlotOptionsDto> slots, int contentX, int contentW,
                          int mouseX, int mouseY, float alpha) {
        List<Integer> indices = new ArrayList<>(slots.size());
        for (DeploySlotOptionsDto s : slots) {
            indices.add(s.slotIndex());
        }
        int pos = BattlefieldLoadoutLayout.positionOfSlot(indices, slotHotbarIndex);
        if (pos < 0) {
            pos = 0;
            slotHotbarIndex = slots.get(0).slotIndex();
        }

        int leftW = BattlefieldLoadoutLayout.splitLeftWidth(contentW, COL_GAP);
        int rightX = contentX + leftW + COL_GAP;
        int rightW = contentX + contentW - rightX;

        int y = GRID_Y;
        y = drawGroup(gg, "武 器", BattlefieldLoadoutLayout.groupMembers(indices, true), slots,
                contentX, y, leftW, mouseX, mouseY, alpha);
        drawGroup(gg, "道具与装备", BattlefieldLoadoutLayout.groupMembers(indices, false), slots,
                contentX, y + GROUP_GAP, leftW, mouseX, mouseY, alpha);

        drawOptions(gg, slots.get(pos), rightX, rightW, mouseX, mouseY, alpha);
    }

    private int drawGroup(GuiGraphics gg, String title, List<Integer> members,
                          List<DeploySlotOptionsDto> slots, int x, int y, int w,
                          int mouseX, int mouseY, float alpha) {
        gg.drawString(font, PixelTheme.fit(font, title, w), x, y,
                withAlpha(PixelTheme.TEXT_DIM, alpha), false);
        gg.fill(x, y + GROUP_HEADER_H - 3, x + w, y + GROUP_HEADER_H - 2,
                withAlpha(RULE_COLOR, alpha));
        int rowY = y + GROUP_HEADER_H;
        if (members.isEmpty()) {
            gg.drawString(font, PixelTheme.fit(font, "本组无可用槽位", w), x + ROW_PAD_X, rowY + 4,
                    withAlpha(PixelTheme.TEXT_DIM, alpha * 0.7f), false);
            return rowY + ROW_H;
        }
        for (int p : members) {
            DeploySlotOptionsDto slot = slots.get(p);
            drawSlotRow(gg, slot, x, rowY, w, mouseX, mouseY, alpha);
            hits.add(new Hit(x, rowY, w, ROW_H, Kind.SLOT, slot.slotIndex(), ""));
            rowY += ROW_H;
        }
        return rowY;
    }

    private void drawSlotRow(GuiGraphics gg, DeploySlotOptionsDto slot, int x, int y, int w,
                             int mouseX, int mouseY, float alpha) {
        boolean selected = slot.slotIndex() == slotHotbarIndex;
        boolean hovered = inRect(mouseX, mouseY, x, y, w, ROW_H);
        if (selected) {
            gg.fill(x, y, x + w, y + ROW_H, withAlpha(
                    PixelTheme.blend(PixelTheme.PANEL_BG, PixelTheme.ALPHA_COLOR, 0.24f), alpha));
            gg.fill(x, y, x + ACCENT_BAR_W, y + ROW_H,
                    withAlpha(PixelTheme.ALPHA_COLOR, alpha));
        } else if (hovered) {
            gg.fill(x, y, x + w, y + ROW_H, withAlpha(HOVER_TINT, alpha));
        }
        int inner = w - ACCENT_BAR_W - ROW_PAD_X * 2;
        int nameW = Math.max(8, inner * 40 / 100);
        int itemW = Math.max(8, inner - nameW - 4);
        int tx = x + ACCENT_BAR_W + ROW_PAD_X;
        gg.drawString(font, PixelTheme.fit(font, slot.slotName(), nameW), tx, y + 4,
                withAlpha(PixelTheme.TEXT_DIM, alpha), false);
        String item = PixelTheme.fit(font, ClientNames.resolve(slot.currentDisplayName()), itemW);
        gg.drawString(font, item, x + w - ROW_PAD_X - font.width(item), y + 4,
                withAlpha(selected ? PixelTheme.TEXT : PixelTheme.TEXT_DIM, alpha), false);
    }

    private void drawOptions(GuiGraphics gg, DeploySlotOptionsDto slot, int x, int w,
                             int mouseX, int mouseY, float alpha) {
        int top = GRID_Y;
        int bottom = height - PAD;
        gg.drawString(font, PixelTheme.fit(font, slot.slotName() + " · 可选装备", w), x, top,
                withAlpha(PixelTheme.TEXT_DIM, alpha), false);
        gg.fill(x, top + GROUP_HEADER_H - 3, x + w, top + GROUP_HEADER_H - 2,
                withAlpha(RULE_COLOR, alpha));

        int listY = top + GROUP_HEADER_H;
        int listH = Math.max(ROW_H, bottom - PAGER_H - listY);
        optionRegionX = x;
        optionRegionY = listY;
        optionRegionW = w;
        optionRegionH = listH;

        List<DeployOptionDto> options = slot.options();
        optionTotal = options.size();
        int perPage = BattlefieldLoadoutLayout.rowsPerPage(listH, ROW_H);
        optionPerPage = perPage;
        if (options.isEmpty()) {
            gg.drawString(font, PixelTheme.fit(font, "该槽位没有可选装备", w), x + ROW_PAD_X, listY + 4,
                    withAlpha(PixelTheme.TEXT_DIM, alpha * 0.7f), false);
            return;
        }
        if (slot.slotIndex() != pagedSlotIndex) {
            pagedSlotIndex = slot.slotIndex();
            optionPage = BattlefieldLoadoutLayout.pageOf(BattlefieldLoadoutLayout.indexOfOrFirst(
                    slot.availableItemNames(), slot.currentItemName()), perPage);
        }
        optionPage = BattlefieldLoadoutLayout.clampPage(optionPage, options.size(), perPage);
        int from = BattlefieldLoadoutLayout.pageStart(optionPage, options.size(), perPage);
        int to = BattlefieldLoadoutLayout.pageEnd(optionPage, options.size(), perPage);
        int rowY = listY;
        for (int i = from; i < to; i++) {
            DeployOptionDto option = options.get(i);
            boolean current = option.id().equals(slot.currentItemName());
            drawOptionRow(gg, option, current, x, rowY, w, mouseX, mouseY, alpha);
            hits.add(new Hit(x, rowY, w, ROW_H, Kind.OPTION, slot.slotIndex(), option.id()));
            rowY += ROW_H;
        }
        drawPager(gg, options.size(), perPage, x, bottom - PAGER_H, w, alpha);
    }

    private void drawOptionRow(GuiGraphics gg, DeployOptionDto option, boolean current,
                               int x, int y, int w, int mouseX, int mouseY, float alpha) {
        boolean hovered = inRect(mouseX, mouseY, x, y, w, ROW_H);
        if (current) {
            gg.fill(x, y, x + w, y + ROW_H, withAlpha(
                    PixelTheme.blend(PixelTheme.PANEL_BG, PixelTheme.ALPHA_COLOR, 0.18f), alpha));
        } else if (hovered) {
            gg.fill(x, y, x + w, y + ROW_H, withAlpha(HOVER_TINT, alpha));
        }
        int marker = withAlpha(
                current ? PixelTheme.ALPHA_COLOR : PixelTheme.BEVEL_LIGHT, alpha);
        gg.fill(x + ROW_PAD_X, y + 7, x + ROW_PAD_X + 3, y + 10, marker);
        int textX = x + ROW_PAD_X + 8;
        gg.drawString(font, PixelTheme.fit(font, ClientNames.resolve(option.displayName()), x + w - ROW_PAD_X - textX), textX, y + 4,
                withAlpha(current ? PixelTheme.TEXT : PixelTheme.TEXT_DIM, alpha), false);
    }

    private void drawPager(GuiGraphics gg, int total, int perPage, int x, int y, int w, float alpha) {
        int pages = BattlefieldLoadoutLayout.pageCount(total, perPage);
        if (pages <= 1) {
            return;
        }
        drawArrow(gg, x, y, false, optionPage > 0, alpha);
        hits.add(new Hit(x, y, ARROW_W, PAGER_H, Kind.PAGE, -2, ""));
        int rx = x + w - ARROW_W;
        drawArrow(gg, rx, y, true, optionPage < pages - 1, alpha);
        hits.add(new Hit(rx, y, ARROW_W, PAGER_H, Kind.PAGE, 2, ""));
        String label = (optionPage + 1) + " / " + pages;
        gg.drawString(font, label, x + (w - font.width(label)) / 2, y + 2,
                withAlpha(PixelTheme.TEXT_DIM, alpha), false);
    }

    // ---- 通用小件 ----

    private void drawTab(GuiGraphics gg, String label, int x, int y, int w, boolean selected,
                         int mouseX, int mouseY, float alpha) {
        boolean hovered = inRect(mouseX, mouseY, x, y - 2, w, TAB_H + 2);
        if (selected) {
            gg.fill(x, y - 2, x + w, y + TAB_H,
                    withAlpha(
                            PixelTheme.blend(PixelTheme.PANEL_BG, PixelTheme.ALPHA_COLOR, 0.22f), alpha));
            gg.fill(x, y + TAB_H, x + w, y + TAB_H + 2,
                    withAlpha(PixelTheme.ALPHA_COLOR, alpha));
        } else if (hovered) {
            gg.fill(x, y - 2, x + w, y + TAB_H, withAlpha(HOVER_TINT, alpha));
        }
        int color = selected ? PixelTheme.TEXT : PixelTheme.TEXT_DIM;
        gg.drawString(font, PixelTheme.fit(font, label, w - TAB_PAD_X * 2), x + TAB_PAD_X, y + 2,
                withAlpha(color, alpha), false);
    }

    private static void drawArrow(GuiGraphics gg, int x, int y, boolean right, boolean enabled, float alpha) {
        int color = withAlpha(
                enabled ? PixelTheme.TEXT : PixelTheme.BEVEL_SHADOW, alpha);
        int base = x + (ARROW_W - 4) / 2;
        for (int i = 0; i < 4; i++) {
            int col = right ? base + i : base + 3 - i;
            gg.fill(col, y + 2 + i, col + 1, y + 9 - i, color);
        }
    }

    // =====================================================================
    // 输入
    // =====================================================================

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
                pagedSlotIndex = -1;
                BattlefieldNetwork.CHANNEL.sendToServer(new RequestLoadoutConfigPacket(hit.id()));
            }
            // 点击网格一格：选定兵种 + 设为激活套。
            case PRESET -> {
                pagedSlotIndex = -1;
                slotHotbarIndex = LoadoutSlot.PRIMARY.hotbarIndex();
                optionPage = 0;
                BattlefieldNetwork.CHANNEL.sendToServer(
                        new LoadoutSelectPresetPacket(dto.mapName(), hit.id(), hit.index()));
            }
            case SLOT -> slotHotbarIndex = hit.index();
            case OPTION -> {
                ClassLoadoutDto cls = classOf(dto, SoldierClass.byIdOrDefault(dto.selectedClassId()));
                BattlefieldNetwork.CHANNEL.sendToServer(new LoadoutEditPacket(
                        dto.mapName(), SoldierClass.byIdOrDefault(dto.selectedClassId()).id(),
                        cls != null ? cls.activeIndex() : 0, hit.index(), hit.id()));
            }
            // |1| 是地图标签横向滚动，|2| 是选项分页；两者共用一个 Kind，靠幅度区分。
            case PAGE -> {
                if (Math.abs(hit.index()) == 1) {
                    mapTabFirst = Math.max(0, mapTabFirst + hit.index());
                } else {
                    optionPage = BattlefieldLoadoutLayout.clampPage(
                            optionPage + hit.index() / 2, optionTotal, optionPerPage);
                }
            }
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (inRect(mouseX, mouseY,
                optionRegionX, optionRegionY, optionRegionW, optionRegionH)) {
            int next = BattlefieldLoadoutLayout.stepPage(optionPage, delta, optionTotal, optionPerPage);
            if (next != optionPage) {
                optionPage = next;
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    /** Enter 提交改名；左右方向键在兵种之间绕圈（保留原快捷路径）。 */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        LoadoutConfigDto dto = ClientLoadoutConfig.get();
        if (dto == null || dto.isEmpty()) {
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            commitRename(dto);
            return true;
        }
        if (renameBox.isFocused()) {
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        if (keyCode == GLFW.GLFW_KEY_LEFT || keyCode == GLFW.GLFW_KEY_RIGHT) {
            SoldierClass[] all = SoldierClass.values();
            int cur = SoldierClass.byIdOrDefault(dto.selectedClassId()).ordinal();
            int next = BattlefieldLoadoutLayout.wrapIndex(
                    cur + (keyCode == GLFW.GLFW_KEY_RIGHT ? 1 : -1), all.length);
            optionPage = 0;
            BattlefieldNetwork.CHANNEL.sendToServer(
                    new LoadoutSelectClassPacket(dto.mapName(), all[next].id(), false));
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** 提交改名框内容：只有确实改了才发包。 */
    private void commitRename(LoadoutConfigDto dto) {
        ClassLoadoutDto cls = classOf(dto, SoldierClass.byIdOrDefault(dto.selectedClassId()));
        if (cls == null) {
            return;
        }
        String newName = renameBox.getValue() == null ? "" : renameBox.getValue().trim();
        String oldName = cls.presets().get(cls.activeIndex()).name();
        if (!newName.equals(oldName == null ? "" : oldName)) {
            BattlefieldNetwork.CHANNEL.sendToServer(new LoadoutRenamePacket(
                    dto.mapName(), cls.classId(), cls.activeIndex(), newName));
        }
        renameBox.setFocused(false);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (renameBox.isFocused()) {
            return super.charTyped(codePoint, modifiers);
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(null);
    }

    /** 多人对局不会因为打开界面而暂停，世界必须继续推进——与 {@code BattlefieldPauseScreen} 同理。 */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Nullable
    private static ClassLoadoutDto classOf(LoadoutConfigDto dto, SoldierClass soldier) {
        for (ClassLoadoutDto c : dto.classes()) {
            if (soldier.id().equals(c.classId())) {
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
