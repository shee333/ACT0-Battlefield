package org.shee33.act0.battlefield.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import org.shee33.act0.battlefield.core.arena.LoadoutPresetDef;
import org.shee33.act0.battlefield.core.arena.LoadoutSlot;
import org.shee33.act0.battlefield.network.DeployLoadoutDto;
import org.shee33.act0.battlefield.network.DeploySlotDto;

import java.util.ArrayList;
import java.util.List;
/**
 * 部署界面底部的<b>配装预览条</b>（取代旧的可编辑武器更换面板）：只读展示玩家当前兵种
 * 所选那套配装的名称与四个槽位，不提供任何点击编辑。
 *
 * <p>玩家只选不编——配装内容由管理员在服务端预设，这里纯展示。
 */
public final class DeployPresetBar {

    private static final int BAR_H = 18;
    private static final int GOLD = DocPalette.PROGRESS;
    private static final int TEXT = 0xFFE8EDF2;
    private static final int DIM = 0xFF8A9099;

    private DeployPresetBar() {
    }

    /** 底部预览条总高度，供 Screen 布局预留空间。 */
    public static int barHeight() {
        return BAR_H;
    }

    public static void onOpened() {
    }

    public static void onClosed() {
    }

    /** 纯展示，不吃任何点击。 */
    public static boolean handleClick(double mouseX, double mouseY) {
        return false;
    }

    public static void render(GuiGraphics gg, Font font, int screenW, int bottomY, int mouseX, int mouseY) {
        DeployLoadoutDto loadout = ClientDeployLoadout.get();
        if (loadout == null || loadout.presetId().isEmpty()) {
            String hint = "该兵种未配置配装（管理员用 /aew1 loadout 预设）";
            gg.drawString(font, hint, screenW / 2 - font.width(hint) / 2, bottomY + 5,
                    DIM, false);
            return;
        }
        String name = loadout.presetName().isBlank() ? "未命名配装" : loadout.presetName();
        List<String> parts = new ArrayList<>();
        parts.add(name);
        for (LoadoutSlot slot : LoadoutPresetDef.PRESET_SLOTS) {
            parts.add(slot.displayName() + " " + slotName(loadout, slot));
        }
        int total = 0;
        for (String p : parts) {
            total += font.width(p) + 12;
        }
        total -= 12;
        int x = screenW / 2 - total / 2;
        int y = bottomY + 5;
        for (int i = 0; i < parts.size(); i++) {
            gg.drawString(font, parts.get(i), x, y, i == 0 ? GOLD : TEXT, false);
            x += font.width(parts.get(i)) + 12;
        }
    }

    private static String slotName(DeployLoadoutDto loadout, LoadoutSlot slot) {
        for (DeploySlotDto dto : loadout.slots()) {
            if (dto.slotIndex() == slot.hotbarIndex() && !dto.itemId().isEmpty()) {
                String display = ClientNames.itemName(dto.itemId());
                return dto.ammo() > 0 ? display + "§8(×" + dto.ammo() + ")" : display;
            }
        }
        return "空槽位";
    }
}
