package org.shee33.act0.battlefield.client.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.shee33.act0.battlefield.client.ClientDeployStatus;
import org.shee33.act0.battlefield.client.ClientSquadSpectate;
import org.shee33.act0.battlefield.network.BattlefieldNetwork;
import org.shee33.act0.battlefield.network.DeployActionPacket;
import org.shee33.act0.battlefield.network.DeployPointDto;
import org.shee33.act0.battlefield.network.DeploySquadMateDto;
import org.shee33.act0.battlefield.network.DeployStatusDto;

import java.util.ArrayList;
import java.util.List;

/**
 * 死亡后的俯视战术部署界面：中央地图点选己方据点，右侧选择小队/基地。
 *
 * <p>玩家不需要输入命令；死亡后服务端自动打开。倒计时结束前点击只记录选择，倒计时结束后点击可用点部署。
 */
public final class BattlefieldDeployScreen extends Screen {

    private static final int PANEL_W = 430;
    private static final int PANEL_H = 254;
    private static final int MAP_W = 270;
    private static final int MAP_H = 180;

    private final List<ClickTarget> targets = new ArrayList<>();

    private int left;
    private int top;
    private int mapX;
    private int mapY;
    private int sideX;
    private int squadY;
    private int baseY;
    private int cardW;
    private int cardH;

    public BattlefieldDeployScreen() {
        super(Component.literal("部署"));
    }

    @Override
    protected void init() {
        left = (width - PANEL_W) / 2;
        top = (height - PANEL_H) / 2;
        mapX = left + 14;
        mapY = top + 46;
        sideX = mapX + MAP_W + 14;
        squadY = mapY + 10;
        baseY = squadY + 68;
        cardW = PANEL_W - (sideX - left) - 14;
        cardH = 56;
    }

    public void onDeployUpdated() {
        // 纯自绘，每帧读缓存即可。
    }

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        renderBackground(gg);
        DeployStatusDto st = ClientDeployStatus.status();
        targets.clear();

        PixelTheme.panel(gg, left, top, PANEL_W, PANEL_H);

        String title = "§c§l重新部署";
        gg.drawString(font, title, left + (PANEL_W - font.width(title)) / 2, top + 9, 0xFFFFFFFF, false);
        int ready = st == null ? 0 : Math.max(0, st.readyInTicks());
        String timer = ready > 0 ? "§7可部署倒计时 §f" + ((ready + 19) / 20) + " 秒" : "§a可以部署";
        gg.drawString(font, timer, left + (PANEL_W - font.width(timer)) / 2, top + 24, 0xFFFFFFFF, false);

        renderMap(gg, mouseX, mouseY, st);
        renderSideCards(gg, mouseX, mouseY, st);
        updateSquadSpectate(st);

        String hint = ready > 0 ? "§8选择部署点，倒计时结束后部署" : "§7点击地图或右侧部署点重返战场";
        gg.drawString(font, hint, left + (PANEL_W - font.width(hint)) / 2, top + PANEL_H - 22, 0xFFFFFFFF, false);

        renderSpectateFade(gg);
    }

    /** 根据当前选中的具体小队成员，切换到越肩观战相机。 */
    private void updateSquadSpectate(DeployStatusDto st) {
        if (st == null || !"squad".equals(st.selectedKind()) || st.selectedTarget().isBlank()) {
            ClientSquadSpectate.clear();
            return;
        }
        for (DeploySquadMateDto mate : st.squadMates()) {
            if (mate.deployable() && mate.id().equals(st.selectedTarget())) {
                ClientSquadSpectate.focus(mate.entityId());
                return;
            }
        }
        ClientSquadSpectate.clear();
    }

    /** 渲染小队成员切换时的淡出淡入黑幕。 */
    private void renderSpectateFade(GuiGraphics gg) {
        int alpha = ClientSquadSpectate.fadeAlpha();
        if (alpha <= 0) {
            return;
        }
        gg.fill(0, 0, width, height, (alpha << 24));
    }

    private void renderMap(GuiGraphics gg, int mouseX, int mouseY, DeployStatusDto st) {
        gg.fill(mapX, mapY, mapX + MAP_W, mapY + MAP_H, 0xAA05080A);
        gg.fill(mapX, mapY, mapX + MAP_W, mapY + 1, PixelTheme.BEVEL_LIGHT);
        gg.fill(mapX, mapY + MAP_H - 1, mapX + MAP_W, mapY + MAP_H, PixelTheme.BEVEL_SHADOW);
        gg.drawString(font, "战术部署图", mapX + 8, mapY + 7, PixelTheme.TEXT_DIM, false);

        if (st == null || st.points().isEmpty()) {
            String empty = "等待据点数据";
            gg.drawString(font, empty, mapX + MAP_W / 2 - font.width(empty) / 2, mapY + MAP_H / 2, PixelTheme.TEXT_DIM, false);
            return;
        }

        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        if (st.canBase()) {
            minX = Math.min(minX, st.baseX()); maxX = Math.max(maxX, st.baseX());
            minZ = Math.min(minZ, st.baseZ()); maxZ = Math.max(maxZ, st.baseZ());
        }
        if (st.canSquad()) {
            minX = Math.min(minX, st.squadX()); maxX = Math.max(maxX, st.squadX());
            minZ = Math.min(minZ, st.squadZ()); maxZ = Math.max(maxZ, st.squadZ());
        }
        for (DeploySquadMateDto mate : st.squadMates()) {
            minX = Math.min(minX, mate.x());
            maxX = Math.max(maxX, mate.x());
            minZ = Math.min(minZ, mate.z());
            maxZ = Math.max(maxZ, mate.z());
        }
        for (DeployPointDto p : st.points()) {
            minX = Math.min(minX, p.x());
            maxX = Math.max(maxX, p.x());
            minZ = Math.min(minZ, p.z());
            maxZ = Math.max(maxZ, p.z());
        }
        if (minX == Double.MAX_VALUE) {
            minX = -1; maxX = 1; minZ = -1; maxZ = 1;
        }
        double pad = 24;
        minX -= pad; maxX += pad; minZ -= pad; maxZ += pad;
        double spanX = Math.max(1, maxX - minX);
        double spanZ = Math.max(1, maxZ - minZ);

        // 网格线：给地图一点战术感
        for (int i = 1; i < 4; i++) {
            int gx = mapX + i * MAP_W / 4;
            int gy = mapY + i * MAP_H / 4;
            gg.fill(gx, mapY + 20, gx + 1, mapY + MAP_H - 8, 0x223A4A54);
            gg.fill(mapX + 8, gy, mapX + MAP_W - 8, gy + 1, 0x223A4A54);
        }

        if (st.canBase()) {
            int bx = mapX + 14 + (int) Math.round(((st.baseX() - minX) / spanX) * (MAP_W - 28));
            int by = mapY + 24 + (int) Math.round(((st.baseZ() - minZ) / spanZ) * (MAP_H - 42));
            boolean selected = "base".equals(st.selectedKind());
            renderBaseIcon(gg, bx, by, selected);
            targets.add(new ClickTarget(bx - 12, by - 12, 24, 24, DeployActionPacket.DeployKind.BASE, ""));
        }
        if (st.canSquad()) {
            int qx = mapX + 14 + (int) Math.round(((st.squadX() - minX) / spanX) * (MAP_W - 28));
            int qy = mapY + 24 + (int) Math.round(((st.squadZ() - minZ) / spanZ) * (MAP_H - 42));
            boolean selected = "squad".equals(st.selectedKind());
            renderSquadIcon(gg, qx, qy, selected);
            targets.add(new ClickTarget(qx - 12, qy - 12, 24, 24, DeployActionPacket.DeployKind.SQUAD, ""));
        }

        for (DeploySquadMateDto mate : st.squadMates()) {
            int sx = mapX + 14 + (int) Math.round(((mate.x() - minX) / spanX) * (MAP_W - 28));
            int sy = mapY + 24 + (int) Math.round(((mate.z() - minZ) / spanZ) * (MAP_H - 42));
            boolean selected = "squad".equals(st.selectedKind()) && mate.id().equals(st.selectedTarget());
            boolean hovered = mate.deployable() && distanceSq(mouseX, mouseY, sx, sy) <= 100;
            renderSquadMateIcon(gg, sx, sy, mate.deployable(), selected || hovered, mate.name());
            if (mate.deployable()) {
                targets.add(new ClickTarget(sx - 10, sy - 10, 20, 20, DeployActionPacket.DeployKind.SQUAD, mate.id()));
            }
        }

        for (DeployPointDto p : st.points()) {
            int sx = mapX + 14 + (int) Math.round(((p.x() - minX) / spanX) * (MAP_W - 28));
            int sy = mapY + 24 + (int) Math.round(((p.z() - minZ) / spanZ) * (MAP_H - 42));
            boolean deployable = p.deployable();
            boolean friendly = deployable;
            boolean selected = "point".equals(st.selectedKind()) && p.id().equals(st.selectedTarget());
            boolean hovered = deployable && distanceSq(mouseX, mouseY, sx, sy) <= 100;
            int color = p.owner() == 0 ? 0xFF9EA7AA : (friendly ? 0xFF57C7FF : 0xFFE7654E);
            if (!deployable) {
                color = (color & 0x00FFFFFF) | 0x99000000;
            }
            renderPointIcon(gg, sx, sy, color, hovered || (selected && deployable), p.name());
            if (deployable) {
                targets.add(new ClickTarget(sx - 10, sy - 10, 20, 20, DeployActionPacket.DeployKind.POINT, p.id()));
            }
        }
    }

    private void renderBaseIcon(GuiGraphics gg, int cx, int cy, boolean selected) {
        int color = selected ? 0xFF9DFF9D : 0xFF57C7FF;
        gg.fill(cx - 8, cy - 8, cx + 9, cy + 9, 0xAA000000);
        gg.fill(cx - 6, cy - 6, cx + 7, cy + 7, color);
        gg.fill(cx - 3, cy - 3, cx + 4, cy + 4, 0xAA000000);
        gg.drawString(font, "H", cx - font.width("H") / 2, cy - 4, 0xFFFFFFFF, true);
    }

    private void renderSquadIcon(GuiGraphics gg, int cx, int cy, boolean selected) {
        int color = selected ? 0xFF9DFF9D : 0xFF57C7FF;
        gg.fill(cx - 7, cy - 7, cx + 8, cy + 8, 0xAA000000);
        gg.fill(cx - 1, cy - 8, cx + 2, cy + 9, color);
        gg.fill(cx - 8, cy - 1, cx + 9, cy + 2, color);
        gg.drawString(font, "S", cx - font.width("S") / 2, cy - 4, 0xFFFFFFFF, true);
    }

    private void renderSquadMateIcon(GuiGraphics gg, int cx, int cy, boolean deployable, boolean selected, String name) {
        int color = !deployable ? 0x999EA7AA : (selected ? 0xFF9DFF9D : 0xFF57C7FF);
        gg.fill(cx - 5, cy - 5, cx + 6, cy + 6, 0xAA000000);
        gg.fill(cx - 3, cy - 3, cx + 4, cy + 4, color);
        String mark = "◆";
        gg.drawString(font, mark, cx - font.width(mark) / 2, cy - 4, 0xFFFFFFFF, true);
        if (selected && name != null && !name.isBlank()) {
            String label = trim(name, 52);
            gg.drawString(font, label, cx - font.width(label) / 2, cy + 8, 0xFFFFFFFF, true);
        }
    }

    private String trim(String text, int maxW) {
        if (font.width(text) <= maxW) {
            return text;
        }
        String out = text;
        while (out.length() > 1 && font.width(out + "…") > maxW) {
            out = out.substring(0, out.length() - 1);
        }
        return out + "…";
    }

    private void renderPointIcon(GuiGraphics gg, int cx, int cy, int color, boolean selected, String label) {
        int s = selected ? 9 : 7;
        gg.fill(cx - 1, cy - s, cx + 1, cy + s, color);
        gg.fill(cx - s, cy - 1, cx + s, cy + 1, color);
        gg.fill(cx - 5, cy - 5, cx + 6, cy + 6, 0xAA000000);
        String l = label == null || label.isEmpty() ? "?" : label.substring(0, 1);
        gg.drawString(font, l, cx - font.width(l) / 2, cy - 4, 0xFFFFFFFF, true);
    }

    private void renderSideCards(GuiGraphics gg, int mouseX, int mouseY, DeployStatusDto st) {
        renderCard(gg, mouseX, mouseY, sideX, squadY, cardW, cardH,
                "小队", "队友身边", st != null && st.canSquad(), st != null && "squad".equals(st.selectedKind()),
                DeployActionPacket.DeployKind.SQUAD);
        renderCard(gg, mouseX, mouseY, sideX, baseY, cardW, cardH,
                "基地", "安全部署", st != null && st.canBase(), st != null && "base".equals(st.selectedKind()),
                DeployActionPacket.DeployKind.BASE);
    }

    private void renderCard(GuiGraphics gg, int mouseX, int mouseY, int x, int y, int w, int h,
                            String title, String sub, boolean enabled, boolean selected, DeployActionPacket.DeployKind kind) {
        boolean hovered = enabled && inRect(mouseX, mouseY, x, y, w, h);
        int accent = selected ? PixelTheme.BRAVO_COLOR : (enabled ? PixelTheme.BEVEL_LIGHT : PixelTheme.TEXT_DIM);
        PixelTheme.button(gg, x, y, w, h, hovered || selected, enabled);
        gg.fill(x + 1, y + 1, x + w - 1, y + 4, accent);
        String t = (selected ? "§a" : (enabled ? "§f" : "§8")) + title;
        String s = enabled ? "§7" + sub : "§8不可用";
        gg.drawString(font, t, x + (w - font.width(t)) / 2, y + 17, 0xFFFFFFFF, false);
        gg.drawString(font, s, x + (w - font.width(s)) / 2, y + 31, 0xFFFFFFFF, false);
        if (enabled) {
            targets.add(new ClickTarget(x, y, w, h, kind, ""));
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            for (ClickTarget t : targets) {
                if (inRect((int) mouseX, (int) mouseY, t.x(), t.y(), t.w(), t.h())) {
                    send(t.kind(), t.targetId());
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void removed() {
        ClientSquadSpectate.clear();
        super.removed();
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    private static void send(DeployActionPacket.DeployKind kind, String targetId) {
        BattlefieldNetwork.CHANNEL.sendToServer(new DeployActionPacket(kind, targetId));
    }

    private static boolean inRect(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private static int distanceSq(int mx, int my, int x, int y) {
        int dx = mx - x;
        int dy = my - y;
        return dx * dx + dy * dy;
    }

    private record ClickTarget(int x, int y, int w, int h, DeployActionPacket.DeployKind kind, String targetId) {
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
