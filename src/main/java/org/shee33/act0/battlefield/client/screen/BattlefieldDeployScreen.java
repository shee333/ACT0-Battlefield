package org.shee33.act0.battlefield.client.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import org.shee33.act0.battlefield.client.BattlefieldDeployWorldOverlay;
import org.shee33.act0.battlefield.client.BattlefieldKeyMappings;
import org.shee33.act0.battlefield.client.ClientDeployLoadout;
import org.shee33.act0.battlefield.client.ClientDeployStatus;
import org.shee33.act0.battlefield.client.ClientSquadSpectate;
import org.shee33.act0.battlefield.network.BattlefieldNetwork;
import org.shee33.act0.battlefield.network.DeployActionPacket;
import org.shee33.act0.battlefield.network.DeployLoadoutDto;
import org.shee33.act0.battlefield.network.DeployPointDto;
import org.shee33.act0.battlefield.network.DeploySlotOptionsDto;
import org.shee33.act0.battlefield.network.DeploySquadMateDto;
import org.shee33.act0.battlefield.network.DeployStatusDto;

import java.util.List;

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
        String hint = ready > 0
            ? "§7选择部署点，倒计时结束后部署 · R 刷新"
            : "§f点击战场标记或按 Enter 部署 · R 刷新";
        gg.fill(0, height - 26, width, height, 0x88000000);
        gg.drawString(font, hint, width / 2 - font.width(hint) / 2, height - 18, 0xFFFFFFFF, false);
        renderSelectedTarget(gg, st);

        renderHoverTip(gg, mouseX, mouseY, ready);

        renderSpectateFade(gg);

        renderLoadoutPanel(gg);
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

    /**
     * 浏览阶段的观战相机：默认保持全局俯瞰（相机完全交还玩家自由观察），只有玩家在部署列表里
     * 明确选中了某个具体部署目标才离开俯瞰——选中队友切到越肩跟随；选中据点/基地则保持俯瞰，
     * 只做一次性转向把视线对准目标附近。未选中任何目标、或选中的目标已失效，一律回落俯瞰。
     */
    private void updateSquadSpectate(DeployStatusDto st) {
        if (st == null || st.selectedKind().isBlank()) {
            ClientSquadSpectate.clear();
            ClientSquadSpectate.clearLocationFocus();
            return;
        }
        if ("squad".equals(st.selectedKind()) && !st.selectedTarget().isBlank()) {
            ClientSquadSpectate.clearLocationFocus();
            for (DeploySquadMateDto mate : st.squadMates()) {
                if (mate.id().equals(st.selectedTarget())) {
                    ClientSquadSpectate.focus(mate.entityId());
                    return;
                }
            }
            // 选中的队友这一帧已经不在存活列表里（刚阵亡）：服务端很快会把选择重置为未选中，
            // 这一帧先退回俯瞰，避免相机悬空跟着一个不存在的实体。
            ClientSquadSpectate.clear();
            return;
        }
        ClientSquadSpectate.clear();
        focusSelectedLocation(st);
    }

    /** 选中据点/基地时的一次性转向提示（详见 {@link ClientSquadSpectate#focusLocation}）。 */
    private void focusSelectedLocation(DeployStatusDto st) {
        if ("point".equals(st.selectedKind())) {
            for (DeployPointDto point : st.points()) {
                if (point.id().equals(st.selectedTarget())) {
                    ClientSquadSpectate.focusLocation("point:" + point.id(), point.x(), point.y(), point.z());
                    return;
                }
            }
            ClientSquadSpectate.clearLocationFocus();
            return;
        }
        if ("base".equals(st.selectedKind())) {
            ClientSquadSpectate.focusLocation("base", st.baseX(), st.baseY(), st.baseZ());
            return;
        }
        ClientSquadSpectate.clearLocationFocus();
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
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (BattlefieldKeyMappings.SPECTATE_NEXT.matches(keyCode, scanCode)) {
            DeployStatusDto current = ClientDeployStatus.status();
            if (current != null) {
                // 仅在可部署的队友之间循环，确保新聚焦的目标一定能被服务端接受为部署选择
                // （避免选中的目标当场就被 deployStatus() 判定为无效而弹回俯瞰）。
                List<DeploySquadMateDto> deployable = current.squadMates().stream()
                        .filter(DeploySquadMateDto::deployable)
                        .toList();
                DeploySquadMateDto focused = ClientSquadSpectate.cycleNext(deployable, current.spectateEntityId());
                if (focused != null) {
                    // 若当前选中的是据点/基地（而非队友），V 键顺带把部署选择切成该队友，
                    // 让相机与实际部署目标保持一致——这比"按了 V 却看着不算数的画面"更符合直觉。
                    BattlefieldNetwork.CHANNEL.sendToServer(
                            new DeployActionPacket(DeployActionPacket.DeployKind.SQUAD, focused.id()));
                }
            }
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_R) {
            BattlefieldNetwork.CHANNEL.sendToServer(new DeployActionPacket(DeployActionPacket.DeployKind.REFRESH));
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER || keyCode == GLFW.GLFW_KEY_SPACE) {
            DeployStatusDto st = ClientDeployStatus.status();
            DeployActionPacket.DeployKind kind = selectedKind(st);
            if (kind != null) {
                BattlefieldNetwork.CHANNEL.sendToServer(new DeployActionPacket(kind, st.selectedTarget()));
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private static DeployActionPacket.DeployKind selectedKind(DeployStatusDto st) {
        if (st == null || !st.active() || st.selectedKind().isBlank()) {
            return null;
        }
        return switch (st.selectedKind()) {
            case "base" -> DeployActionPacket.DeployKind.BASE;
            case "point" -> DeployActionPacket.DeployKind.POINT;
            case "squad" -> DeployActionPacket.DeployKind.SQUAD;
            default -> null;
        };
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

    /**
     * BF5-style vertical loadout panel on the right side.
     * Anchored between the top header bar (y=30) and the bottom hint bar (y=height-26).
     */
    private void renderLoadoutPanel(GuiGraphics gg) {
        DeployLoadoutDto loadout = ClientDeployLoadout.get();
        if (loadout == null || loadout.slots().isEmpty()) {
            return;
        }

        final int panelX = width - 208;
        final int panelY = 35;
        final int panelW = 200;
        final int maxBottom = height - 26;
        final int accent = PixelTheme.ALPHA_COLOR;

        int rows = Math.min(loadout.slots().size(), 10);
        int contentH = 32 + rows * 14;
        int panelH = Math.min(contentH, maxBottom - panelY);

        gg.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xB3101418);

        int border = 0x663A3A3A;
        gg.fill(panelX, panelY, panelX + panelW, panelY + 1, border);
        gg.fill(panelX, panelY + panelH - 1, panelX + panelW, panelY + panelH, border);
        gg.fill(panelX, panelY, panelX + 1, panelY + panelH, border);
        gg.fill(panelX + panelW - 1, panelY, panelX + panelW, panelY + panelH, border);

        gg.fill(panelX + 1, panelY + 1, panelX + panelW - 1, panelY + 3, accent);

        int tx = panelX + 8;
        String header = "配装 · " + (loadout.className().isEmpty() ? "-" : loadout.className());
        gg.drawString(font, header, tx, panelY + 6, 0xFFB0B0B0, false);

        gg.fill(panelX + 6, panelY + 20, panelX + panelW - 6, panelY + 21, 0x333A3A3A);

        int lineH = 14;
        int maxTextW = panelW - 16;
        for (int i = 0; i < rows; i++) {
            int sy = panelY + 24 + i * lineH;
            if (sy + lineH > panelY + panelH - 4) break;

            DeploySlotOptionsDto slotDto = loadout.slots().get(i);
            String slot = slotDto.slotName();
            String item = slotDto.currentItemName();
            String text = slot + ": " + item;
            if (font.width(text) > maxTextW) {
                text = font.plainSubstrByWidth(text, maxTextW);
            }
            gg.drawString(font, text, tx, sy, 0xFFE0E0E0, false);
        }
    }
}
