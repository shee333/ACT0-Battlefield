package org.shee33.act0.battlefield.client.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import org.shee33.act0.battlefield.client.BattlefieldDeployWorldOverlay;
import org.shee33.act0.battlefield.client.BattlefieldKeyMappings;
import org.shee33.act0.battlefield.client.ClientDeployStatus;
import org.shee33.act0.battlefield.client.ClientSquadSpectate;
import org.shee33.act0.battlefield.client.DeployConfirmFx;
import org.shee33.act0.battlefield.client.DeployClassBar;
import org.shee33.act0.battlefield.client.DeployMapPanel;
import org.shee33.act0.battlefield.client.DeployModeLabel;
import org.shee33.act0.battlefield.client.DeployPresetDropdown;
import org.shee33.act0.battlefield.network.BattlefieldNetwork;
import org.shee33.act0.battlefield.network.DeployActionPacket;
import org.shee33.act0.battlefield.network.DeployPointDto;
import org.shee33.act0.battlefield.network.DeploySquadMateDto;
import org.shee33.act0.battlefield.network.DeployStatusDto;

import java.util.List;

/**
 * 战地式无缝部署界面：不再渲染独立窗口，直接在上帝视角真实战场上叠加极简 HUD。
 *
 * <p>Wave2 重写:2D 缩略地图({@link DeployMapPanel})取代旧的"据点横排缩略条"，成为唯一的选点
 * 交互命中区域。{@link BattlefieldDeployWorldOverlay} 的 3D 世界标记保留作纯视觉辅助，其
 * {@code targets()}/{@code hoveredTarget()} 不再参与点击/悬停判定(见部署界面动效规格文档
 * Wave2 设计决策 3)。
 *
 * <p>Wave3 新增:底部武器更换上拉面板({@link DeployWeaponPanel})取代旧的右侧纵向配装文字列表
 * ({@code renderLoadoutPanel}，已删除，避免同一份配装信息在两处重复展示)，是本次"部署界面大改"
 * 里唯一涉及真实换装功能的一块——{@link DeployWeaponPanel#handleClick} 在 {@link #mouseClicked}
 * 里被赋予比地图选点更高的点击优先级(先命中武器栏/面板，未命中才落到 {@link DeployMapPanel})。
 *
 * <p>Wave4 新增:底部配装下拉({@link DeployPresetDropdown})取代旧的纯展示条，提供同兵种预设间的
 * 真实切换（点击 → 上弹该阵营该兵种的全预设列表 → 点选 → 服务端回推新的 DeployLoadoutDto）。
 */
public final class BattlefieldDeployScreen extends Screen {

    /** 顶部标题条高度。 */
    private static final int HEADER_H = 30;
    /** 底部提示条高度。 */
    private static final int HINT_H = 26;
    /** 右侧固定宽度栏(预览卡独占一栏)。 */
    private static final int SIDE_W = 208;
    private static final int MAP_MARGIN = 10;
    /** 武器栏与底部提示条之间的留白。 */
    private static final int WEAPON_BAR_MARGIN = 6;

    public BattlefieldDeployScreen() {
        super(Component.literal("部署"));
        DeployMapPanel.onOpened();
        DeployPresetDropdown.onOpened();
        DeployModeLabel.onOpened();
    }

    public void onDeployUpdated() {
        // 地图/标记/十字准星/预览卡每帧读取 DeployMapPanel 内部状态，无需额外状态。
    }

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        DeployStatusDto st = ClientDeployStatus.status();

        // 顶部信息条：保留最小状态信息，真实战场画面保持可见。
        gg.fill(0, 0, width, 28, 0x88000000);
        gg.fill(0, 28, width, 30, PixelTheme.ALPHA_COLOR);
        String title = "§b§l重新部署";
        gg.drawString(font, title, width / 2 - font.width(title) / 2, 8, 0xFFFFFFFF, false);
        int ready = st == null ? 0 : Math.max(0, st.readyInTicks());
        String timer = ready > 0 ? "§7可部署倒计时 §f" + ((ready + 19) / 20) + " 秒" : "§a可以部署";
        gg.drawString(font, timer, width / 2 - font.width(timer) / 2, 19, 0xFFFFFFFF, false);
        DeployModeLabel.render(gg, font, st, 8, 4);

        updateSquadSpectate(st);

        int barTopY = height - HINT_H - WEAPON_BAR_MARGIN - DeployPresetDropdown.barHeight();
        int classBarTopY = barTopY - DeployClassBar.barHeight();

        int mapX = MAP_MARGIN;
        int mapY = HEADER_H + 6;
        int mapW = Math.max(160, width - SIDE_W - mapX - 8);
        int mapH = Math.max(120, classBarTopY - mapY - 6);
        DeployMapPanel.render(gg, font, st, mapX, mapY, mapW, mapH, mouseX, mouseY);

        int sideX = width - SIDE_W;
        DeployMapPanel.renderCard(gg, font, sideX, mapY, SIDE_W - 8);

        // 底部提示条。
        String hint = ready > 0
            ? "§7点击/悬停地图选点，倒计时结束后部署 · R 刷新"
            : "§f点击地图标记或按 Enter 部署 · R 刷新";
        gg.fill(0, height - HINT_H, width, height, 0x88000000);
        gg.drawString(font, hint, width / 2 - font.width(hint) / 2, height - 18, 0xFFFFFFFF, false);

        renderSpectateFade(gg);

        DeployClassBar.render(gg, font, width, classBarTopY, mouseX, mouseY);
        // barTopY 是按钮顶部；render 用的是按钮底部（bottomY），所以传 barTopY + barHeight
        DeployPresetDropdown.render(gg, font, width, barTopY + DeployPresetDropdown.barHeight(), mouseX, mouseY);
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
            // 武器栏/上拉面板优先命中：命中槍位或可选项时直接消费，不落到地图选点逻辑；未命中
            // 任何交互区域时它会把已打开的面板顺手关闭（同规格文档"点空白"语义），再放行给地图。
            if (DeployClassBar.handleClick(mouseX, mouseY)) {
                return true;
            }
            if (DeployPresetDropdown.handleClick(mouseX, mouseY, button)) {
                return true;
            }
            DeployMapPanel.ClickOutcome outcome = DeployMapPanel.handleClick(mouseX, mouseY);
            if (outcome.selection() != null) {
                DeployStatusDto stBeforeClick = ClientDeployStatus.status();
                BattlefieldNetwork.CHANNEL.sendToServer(
                        new DeployActionPacket(outcome.selection().kind(), outcome.selection().targetId()));
                // 倒计时已结束时点击地图标记会让服务端立即确认部署(见 RedeployService#handleDeployAction
                // 的即时部署分支)，与发包同一帧触发白闪转场，跟随即开始的 900ms 相机过场同步起跑。
                maybeTriggerDeployConfirmFx(stBeforeClick);
                return true;
            }
            if (outcome.insideMap()) {
                // 点击了地图空白区域：DeployMapPanel 已在内部把选中视觉标记为"暂时隐藏"，这里
                // 只需要消费掉这次点击，不再落到 3D 世界叠加层(不参与命中，见类头注释)。
                return true;
            }
        }
return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollDelta) {
        if (DeployPresetDropdown.handleScroll(scrollDelta)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollDelta);
    }

@Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Escape 优先让配装下拉关闭弹层；弹层关闭后才走 ESC 默认行为（关闭整个 Screen）
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (DeployPresetDropdown.handleEscape()) {
                return true;
            }
        }
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
                maybeTriggerDeployConfirmFx(st);
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /**
     * 只有倒计时已经归零(服务端会把这次动作当场处理为立即部署)时才触发白闪转场——若倒计时未结束，
     * 这次动作在服务端只是重新确认选择，不会真的开始 900ms 相机过场，此时播白闪只会显得莫名其妙。
     */
    private static void maybeTriggerDeployConfirmFx(DeployStatusDto st) {
        if (st != null && st.active() && Math.max(0, st.readyInTicks()) <= 0) {
            DeployConfirmFx.trigger();
        }
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
        DeployMapPanel.onClosed();
        DeployPresetDropdown.onClosed();
        DeployModeLabel.onClosed();
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