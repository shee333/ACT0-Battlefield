package org.shee33.act0.battlefield.client.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.shee33.act0.battlefield.client.ClientBattlefieldStatus;
import org.shee33.act0.battlefield.network.ActionPacket;
import org.shee33.act0.battlefield.network.BattlefieldNetwork;
import org.shee33.act0.battlefield.network.BattlefieldStatusDto;

/**
 * 大战场加入界面：像素风双阵营面板（北大西洋公约 / 无邦军团），点击选边；展示双方人数与（开局后）票数、
 * 据点控制数。底部为开局/停止（管理员）/关闭按钮。
 *
 * <p>玩家向界面，遵循极简原则：只显示中文核心信息（阵营名、人数、票数、据点 x/总数），不出现指令提示。
 * 状态变化时由 {@link ClientBattlefieldStatus} 调用 {@link #onStatusUpdated()} 就地刷新。
 */
public final class BattlefieldJoinScreen extends Screen {

    private static final int PANEL_W = 300;
    private static final int PANEL_H = 180;

    private int left;
    private int top;

    // 交互区域
    private int alphaX, bravoX, factionY, factionW, factionH;
    private int btnY, btnH;

    public BattlefieldJoinScreen() {
        super(Component.literal("大战场"));
    }

    @Override
    protected void init() {
        this.left = (this.width - PANEL_W) / 2;
        this.top = (this.height - PANEL_H) / 2;
    }

    /** 服务端状态刷新后由客户端缓存回调，无需重建控件（纯自绘）。 */
    public void onStatusUpdated() {
        // 纯自绘，render 每帧读取最新缓存，无需额外处理。
    }

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(gg);
        BattlefieldStatusDto st = ClientBattlefieldStatus.status();

        PixelTheme.panel(gg, left, top, PANEL_W, PANEL_H);

        // 标题
        String title = "§c§l大 战 场";
        gg.drawString(this.font, title, left + (PANEL_W - this.font.width(title)) / 2, top + 8, 0xFFFFFFFF, false);
        String sub = st != null && st.active() ? "§7对局进行中" : "§7集结中 · 选择你的阵营";
        gg.drawString(this.font, sub, left + (PANEL_W - this.font.width(sub)) / 2, top + 20, 0xFFAAAAAA, false);

        // 双阵营面板
        factionW = (PANEL_W - 30) / 2;
        factionH = 110;
        factionY = top + 34;
        alphaX = left + 10;
        bravoX = left + PANEL_W - 10 - factionW;

        int myF = st != null ? st.myFaction() : 0;
        renderFaction(gg, st, true, alphaX, mouseX, mouseY, myF == 1);
        renderFaction(gg, st, false, bravoX, mouseX, mouseY, myF == 2);

        // 底部按钮
        btnH = 18;
        btnY = top + PANEL_H - btnH - 8;
        renderBottomButtons(gg, st, mouseX, mouseY);
    }

    private void renderFaction(GuiGraphics gg, BattlefieldStatusDto st, boolean alpha,
                               int x, int mouseX, int mouseY, boolean selected) {
        int accent = alpha ? PixelTheme.ALPHA_COLOR : PixelTheme.BRAVO_COLOR;
        boolean hovered = inRect(mouseX, mouseY, x, factionY, factionW, factionH);
        PixelTheme.factionPanel(gg, x, factionY, factionW, factionH, accent, selected, hovered);

        String name = alpha ? "§9北大西洋公约" : "§c无邦军团";
        int cx = x + factionW / 2;
        gg.drawString(this.font, name, cx - this.font.width(name) / 2, factionY + 10, 0xFFFFFFFF, false);

        int count = st == null ? 0 : (alpha ? st.alphaCount() : st.bravoCount());
        String people = "§f" + count + " §7人";
        gg.drawString(this.font, people, cx - this.font.width(people) / 2, factionY + 30, 0xFFFFFFFF, false);

        if (st != null && st.active()) {
            int tickets = alpha ? st.alphaTickets() : st.bravoTickets();
            int pts = alpha ? st.alphaPoints() : st.bravoPoints();
            String tline = "§7票数 §f" + tickets;
            String pline = "§7据点 §f" + pts + "§7/" + st.totalPoints();
            gg.drawString(this.font, tline, cx - this.font.width(tline) / 2, factionY + 52, 0xFFFFFFFF, false);
            gg.drawString(this.font, pline, cx - this.font.width(pline) / 2, factionY + 64, 0xFFFFFFFF, false);
        } else {
            String hint = selected ? "§a✔ 已选择" : "§8点击加入";
            gg.drawString(this.font, hint, cx - this.font.width(hint) / 2, factionY + 56, 0xFFFFFFFF, false);
        }

        if (selected) {
            String tag = "§a● 你的阵营";
            gg.drawString(this.font, tag, cx - this.font.width(tag) / 2, factionY + factionH - 16, 0xFFFFFFFF, false);
        }
    }

    private void renderBottomButtons(GuiGraphics gg, BattlefieldStatusDto st, int mouseX, int mouseY) {
        boolean active = st != null && st.active();
        boolean canManage = st != null && st.canManage();
        boolean joined = st != null && st.myFaction() != 0;

        int gap = 6;
        int n = (canManage ? 1 : 0) + (joined && !active ? 1 : 0) + 1; // 管理键 + 退出 + 关闭
        int totalW = PANEL_W - 20;
        int bw = (totalW - gap * (n - 1)) / n;
        int x = left + 10;

        if (canManage) {
            boolean hov = inRect(mouseX, mouseY, x, btnY, bw, btnH);
            PixelTheme.button(gg, x, btnY, bw, btnH, hov, true);
            String label = active ? "§c停止对局" : "§a开始对局";
            gg.drawString(this.font, label, x + (bw - this.font.width(label)) / 2, btnY + 5, 0xFFFFFFFF, false);
            manageBtnX = x;
            manageBtnW = bw;
            x += bw + gap;
        } else {
            manageBtnW = 0;
        }

        if (joined && !active) {
            boolean hov = inRect(mouseX, mouseY, x, btnY, bw, btnH);
            PixelTheme.button(gg, x, btnY, bw, btnH, hov, true);
            String label = "§7退出";
            gg.drawString(this.font, label, x + (bw - this.font.width(label)) / 2, btnY + 5, 0xFFFFFFFF, false);
            leaveBtnX = x;
            leaveBtnW = bw;
            x += bw + gap;
        } else {
            leaveBtnW = 0;
        }

        boolean hov = inRect(mouseX, mouseY, x, btnY, bw, btnH);
        PixelTheme.button(gg, x, btnY, bw, btnH, hov, true);
        String label = "§7关闭";
        gg.drawString(this.font, label, x + (bw - this.font.width(label)) / 2, btnY + 5, 0xFFFFFFFF, false);
        closeBtnX = x;
        closeBtnW = bw;
    }

    private int manageBtnX, manageBtnW;
    private int leaveBtnX, leaveBtnW;
    private int closeBtnX, closeBtnW;

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            BattlefieldStatusDto st = ClientBattlefieldStatus.status();
            boolean active = st != null && st.active();

            // 阵营面板点击（仅未开局可选）
            if (!active) {
                if (inRect((int) mouseX, (int) mouseY, alphaX, factionY, factionW, factionH)) {
                    send(ActionPacket.Action.JOIN_ALPHA);
                    return true;
                }
                if (inRect((int) mouseX, (int) mouseY, bravoX, factionY, factionW, factionH)) {
                    send(ActionPacket.Action.JOIN_BRAVO);
                    return true;
                }
            }
            // 管理按钮
            if (manageBtnW > 0 && inRect((int) mouseX, (int) mouseY, manageBtnX, btnY, manageBtnW, btnH)) {
                send(active ? ActionPacket.Action.STOP : ActionPacket.Action.START);
                return true;
            }
            // 退出
            if (leaveBtnW > 0 && inRect((int) mouseX, (int) mouseY, leaveBtnX, btnY, leaveBtnW, btnH)) {
                send(ActionPacket.Action.LEAVE);
                return true;
            }
            // 关闭
            if (closeBtnW > 0 && inRect((int) mouseX, (int) mouseY, closeBtnX, btnY, closeBtnW, btnH)) {
                this.onClose();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void send(ActionPacket.Action action) {
        BattlefieldNetwork.CHANNEL.sendToServer(new ActionPacket(action));
    }

    private static boolean inRect(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
