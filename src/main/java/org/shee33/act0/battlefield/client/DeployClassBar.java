package org.shee33.act0.battlefield.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import org.shee33.act0.battlefield.core.SoldierClass;
import org.shee33.act0.battlefield.network.BattlefieldNetwork;
import org.shee33.act0.battlefield.network.DeployLoadoutDto;
import org.shee33.act0.battlefield.network.LoadoutSelectClassPacket;

import java.util.ArrayList;
import java.util.List;

/**
 * 部署界面的兵种选择条：四个兵种横排在武器条正上方，点击即切换。
 *
 * <p>切换后整套配装会跟着变——服务端按兵种各存一套选择，回包是完整的
 * {@link DeployLoadoutDto}，因此这里不做任何乐观更新，等回包重绘即可。武器条那边有乐观更新
 * 是因为它改的是单个槽位、能局部预判；兵种一换是五个槽位同时变，本地猜不出来。
 */
public final class DeployClassBar {

    private static final int CHIP_H = 18;
    private static final int CHIP_GAP = 6;
    private static final int CHIP_PAD = 12;
    private static final int BRIEF_ROW_H = 12;
    private static final int GAP_BELOW = 6;

    private static final int SELECTED_BG = 0xFF2A3038;
    private static final int SELECTED_LINE = DocPalette.PROGRESS;
    private static final int IDLE_BG = 0x8014181C;
    private static final int HOVER_BG = 0xB01E242A;
    private static final int TEXT = 0xFFE0E0E0;
    private static final int TEXT_DIM = 0xC08A8F88;

    private static final List<ChipRect> LAST_CHIPS = new ArrayList<>();

    private DeployClassBar() {
    }

    private record ChipRect(String classId, int x, int y, int w, int h) {
    }

    /** 本条整体占用的高度（含能力说明行），供部署界面把武器条往上顶。 */
    public static int barHeight() {
        return CHIP_H + BRIEF_ROW_H + GAP_BELOW;
    }

    public static void render(GuiGraphics gg, Font font, int screenW, int topY, int mouseX, int mouseY) {
        LAST_CHIPS.clear();
        DeployLoadoutDto loadout = ClientDeployLoadout.get();
        if (loadout == null) {
            return;
        }
        SoldierClass selected = SoldierClass.byIdOrDefault(loadout.selectedClassId());
        SoldierClass[] all = SoldierClass.values();

        int[] widths = new int[all.length];
        for (int i = 0; i < all.length; i++) {
            widths[i] = font.width(all[i].displayName()) + CHIP_PAD * 2;
        }
        int[] xs = DeployWeaponMath.layoutSlotX(widths, CHIP_GAP, screenW / 2);

        for (int i = 0; i < all.length; i++) {
            SoldierClass c = all[i];
            boolean isSelected = c == selected;
            boolean hovered = inRect(mouseX, mouseY, xs[i], topY, widths[i], CHIP_H);
            gg.fill(xs[i], topY, xs[i] + widths[i], topY + CHIP_H,
                    isSelected ? SELECTED_BG : (hovered ? HOVER_BG : IDLE_BG));
            if (isSelected) {
                gg.fill(xs[i], topY + CHIP_H - 2, xs[i] + widths[i], topY + CHIP_H, SELECTED_LINE);
            }
            gg.drawString(font, c.displayName(), xs[i] + CHIP_PAD, topY + 5,
                    isSelected ? TEXT : TEXT_DIM, false);
            LAST_CHIPS.add(new ChipRect(c.id(), xs[i], topY, widths[i], CHIP_H));
        }
    }

    /** @return 是否吃掉了这次点击 */
    public static boolean handleClick(double mouseX, double mouseY) {
        for (ChipRect chip : LAST_CHIPS) {
            if (inRect(mouseX, mouseY, chip.x, chip.y, chip.w, chip.h)) {
                BattlefieldNetwork.CHANNEL.sendToServer(
                        new LoadoutSelectClassPacket("", chip.classId, true));
                return true;
            }
        }
        return false;
    }

    private static boolean inRect(double mouseX, double mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }
}
