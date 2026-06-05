package org.shee33.act0.battlefield.client.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.shee33.act0.battlefield.client.ClientDeployStatus;
import org.shee33.act0.battlefield.network.BattlefieldNetwork;
import org.shee33.act0.battlefield.network.DeployActionPacket;
import org.shee33.act0.battlefield.network.DeployStatusDto;

/**
 * 死亡后的部署界面：选择小队/据点/基地部署。
 *
 * <p>玩家不需要输入命令；死亡后服务端自动打开此界面。倒计时结束后点击可用部署点即可复活。
 */
public final class BattlefieldDeployScreen extends Screen {

    private static final int PANEL_W = 260;
    private static final int PANEL_H = 148;

    private int left;
    private int top;
    private int squadX, pointX, baseX, optionY, optionW, optionH;

    public BattlefieldDeployScreen() {
        super(Component.literal("部署"));
    }

    @Override
    protected void init() {
        left = (width - PANEL_W) / 2;
        top = (height - PANEL_H) / 2;
    }

    public void onDeployUpdated() {
        // 纯自绘，每帧读缓存即可。
    }

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        renderBackground(gg);
        DeployStatusDto st = ClientDeployStatus.status();
        PixelTheme.panel(gg, left, top, PANEL_W, PANEL_H);

        String title = "§c§l重新部署";
        gg.drawString(font, title, left + (PANEL_W - font.width(title)) / 2, top + 10, 0xFFFFFFFF, false);

        int ready = st == null ? 0 : Math.max(0, st.readyInTicks());
        String timer = ready > 0 ? "§7可部署倒计时 §f" + ((ready + 19) / 20) + " 秒" : "§a可以部署";
        gg.drawString(font, timer, left + (PANEL_W - font.width(timer)) / 2, top + 26, 0xFFFFFFFF, false);

        optionW = 72;
        optionH = 54;
        optionY = top + 48;
        int gap = 10;
        squadX = left + 13;
        pointX = squadX + optionW + gap;
        baseX = pointX + optionW + gap;

        renderOption(gg, mouseX, mouseY, squadX, "小队", "队友身边", st != null && st.canSquad(), isSelected(st, "squad"));
        renderOption(gg, mouseX, mouseY, pointX, "据点", "前进出生", st != null && st.canPoint(), isSelected(st, "point"));
        renderOption(gg, mouseX, mouseY, baseX, "基地", "安全部署", st != null && st.canBase(), isSelected(st, "base"));

        String hint = ready > 0 ? "§8选择出生点，倒计时结束后部署" : "§7点击可用出生点部署";
        gg.drawString(font, hint, left + (PANEL_W - font.width(hint)) / 2, top + PANEL_H - 22, 0xFFFFFFFF, false);
    }

    private void renderOption(GuiGraphics gg, int mouseX, int mouseY, int x, String title, String sub,
                              boolean enabled, boolean selected) {
        boolean hovered = enabled && inRect(mouseX, mouseY, x, optionY, optionW, optionH);
        int accent = selected ? PixelTheme.BRAVO_COLOR : (enabled ? PixelTheme.BEVEL_LIGHT : PixelTheme.TEXT_DIM);
        PixelTheme.button(gg, x, optionY, optionW, optionH, hovered || selected, enabled);
        gg.fill(x + 1, optionY + 1, x + optionW - 1, optionY + 4, accent);
        String t = (selected ? "§a" : (enabled ? "§f" : "§8")) + title;
        String s = enabled ? "§7" + sub : "§8不可用";
        gg.drawString(font, t, x + (optionW - font.width(t)) / 2, optionY + 17, 0xFFFFFFFF, false);
        gg.drawString(font, s, x + (optionW - font.width(s)) / 2, optionY + 31, 0xFFFFFFFF, false);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            DeployStatusDto st = ClientDeployStatus.status();
            if (st != null) {
                if (st.canSquad() && inRect((int) mouseX, (int) mouseY, squadX, optionY, optionW, optionH)) {
                    send(DeployActionPacket.DeployKind.SQUAD);
                    return true;
                }
                if (st.canPoint() && inRect((int) mouseX, (int) mouseY, pointX, optionY, optionW, optionH)) {
                    send(DeployActionPacket.DeployKind.POINT);
                    return true;
                }
                if (st.canBase() && inRect((int) mouseX, (int) mouseY, baseX, optionY, optionW, optionH)) {
                    send(DeployActionPacket.DeployKind.BASE);
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private static boolean isSelected(DeployStatusDto st, String kind) {
        return st != null && kind.equals(st.selectedKind());
    }

    private static void send(DeployActionPacket.DeployKind kind) {
        BattlefieldNetwork.CHANNEL.sendToServer(new DeployActionPacket(kind));
    }

    private static boolean inRect(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
