package org.shee33.act0.battlefield.client.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.shee33.act0.battlefield.client.BattlefieldDeployWorldOverlay;
import org.shee33.act0.battlefield.client.ClientDeployStatus;
import org.shee33.act0.battlefield.client.ClientSquadSpectate;
import org.shee33.act0.battlefield.network.BattlefieldNetwork;
import org.shee33.act0.battlefield.network.DeployActionPacket;
import org.shee33.act0.battlefield.network.DeployPointDto;
import org.shee33.act0.battlefield.network.DeploySquadMateDto;
import org.shee33.act0.battlefield.network.DeployStatusDto;

/**
 * 战地式无缝部署界面：不再渲染独立窗口，直接在上帝视角真实战场上叠加极简 HUD。
 *
 * <p>部署点由 {@link BattlefieldDeployWorldOverlay} 作为世界空间标记绘制；本界面只负责顶部状态、底部提示与点击命中。
 */
public final class BattlefieldDeployScreen extends Screen {

    public BattlefieldDeployScreen() {
        super(Component.literal("部署"));
    }

    public void onDeployUpdated() {
        // 世界标记与 HUD 每帧读取缓存，无需额外状态。
    }

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        DeployStatusDto st = ClientDeployStatus.status();
        BattlefieldDeployWorldOverlay.updateHover(mouseX, mouseY);

        // 顶部信息条：保留最小状态信息，真实战场画面保持可见。
        gg.fill(0, 0, width, 28, 0x88000000);
        gg.fill(0, 28, width, 30, PixelTheme.ALPHA_COLOR);
        String title = "§b§l重新部署";
        gg.drawString(font, title, width / 2 - font.width(title) / 2, 8, 0xFFFFFFFF, false);
        int ready = st == null ? 0 : Math.max(0, st.readyInTicks());
        String timer = ready > 0 ? "§7可部署倒计时 §f" + ((ready + 19) / 20) + " 秒" : "§a可以部署";
        gg.drawString(font, timer, width / 2 - font.width(timer) / 2, 19, 0xFFFFFFFF, false);
        renderPointStrip(gg, st);

        updateSquadSpectate(st);

        // 底部提示条：不做旧部署面板/小地图。
        String hint = ready > 0 ? "§7选择部署点，倒计时结束后部署" : "§f点击战场标记重返战场";
        gg.fill(0, height - 26, width, height, 0x88000000);
        gg.drawString(font, hint, width / 2 - font.width(hint) / 2, height - 18, 0xFFFFFFFF, false);
        renderSelectedTarget(gg, st);

        renderHoverTip(gg, mouseX, mouseY, ready);

        renderSpectateFade(gg);
    }

    private void renderPointStrip(GuiGraphics gg, DeployStatusDto st) {
        if (st == null || st.points().isEmpty()) {
            return;
        }
        int itemW = 36;
        int gap = 8;
        int totalW = st.points().size() * itemW + (st.points().size() - 1) * gap;
        int x = width / 2 - totalW / 2;
        int y = 35;
        for (DeployPointDto point : st.points()) {
            boolean selected = "point".equals(st.selectedKind()) && point.id().equals(st.selectedTarget());
            int color = point.owner() == 0 ? 0xFF9EA7AA : (point.deployable() ? PixelTheme.ALPHA_COLOR : PixelTheme.BRAVO_COLOR);
            gg.fill(x, y, x + itemW, y + 15, 0x88000000);
            gg.fill(x, y + 14, x + itemW, y + 15, selected ? 0xFF9DFF9D : color);
            String label = shortPointName(point.name());
            gg.drawCenteredString(font, label, x + itemW / 2, y + 4, selected ? 0xFF9DFF9D : 0xFFFFFFFF);
            x += itemW + gap;
        }
    }

    private void renderSelectedTarget(GuiGraphics gg, DeployStatusDto st) {
        if (st == null || st.selectedKind().isBlank()) {
            return;
        }
        String label = selectedLabel(st);
        if (label.isBlank()) {
            return;
        }
        int w = font.width(label) + 18;
        int x = width / 2 - w / 2;
        int y = height - 48;
        gg.fill(x, y, x + w, y + 17, 0x99000000);
        gg.fill(x, y + 16, x + w, y + 17, PixelTheme.ALPHA_COLOR);
        gg.drawCenteredString(font, label, width / 2, y + 5, 0xFFFFFFFF);
    }

    private String selectedLabel(DeployStatusDto st) {
        if ("base".equals(st.selectedKind())) {
            return "已选择：基地";
        }
        if ("point".equals(st.selectedKind())) {
            for (DeployPointDto point : st.points()) {
                if (point.id().equals(st.selectedTarget())) {
                    return "已选择：据点 " + shortPointName(point.name());
                }
            }
        }
        if ("squad".equals(st.selectedKind())) {
            for (DeploySquadMateDto mate : st.squadMates()) {
                if (mate.id().equals(st.selectedTarget())) {
                    return "已选择：队友 " + mate.name();
                }
            }
            return "已选择：小队";
        }
        return "";
    }

    private static String shortPointName(String name) {
        if (name == null || name.isBlank()) {
            return "?";
        }
        return name.length() > 1 ? name.substring(0, 1) : name;
    }

    private void renderHoverTip(GuiGraphics gg, int mouseX, int mouseY, int ready) {
        BattlefieldDeployWorldOverlay.DeployClickTarget target = BattlefieldDeployWorldOverlay.hoveredTarget();
        if (target == null) {
            return;
        }
        String text = ready > 0 ? "§7预选 §f" + target.label() : "§a部署 §f" + target.label();
        int w = font.width(text) + 12;
        int x = Math.min(width - w - 6, mouseX + 12);
        int y = Math.max(34, mouseY - 18);
        gg.fill(x, y, x + w, y + 15, 0xBB000000);
        gg.fill(x, y + 14, x + w, y + 15, PixelTheme.ALPHA_COLOR);
        gg.drawString(font, text, x + 6, y + 4, 0xFFFFFFFF, false);
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
        if (alpha > 0) {
            gg.fill(0, 0, width, height, (alpha << 24));
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            for (BattlefieldDeployWorldOverlay.DeployClickTarget target : BattlefieldDeployWorldOverlay.targets()) {
                double dx = mouseX - target.x();
                double dy = mouseY - target.y();
                if (dx * dx + dy * dy <= target.radius() * target.radius()) {
                    BattlefieldNetwork.CHANNEL.sendToServer(new DeployActionPacket(target.kind(), target.targetId()));
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

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
