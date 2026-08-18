package org.shee33.act0.battlefield.client;

import org.shee33.act0.battlefield.command.Aew1Command;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.OptionsScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import org.shee33.act0.battlefield.core.PauseMenuAnim;
import org.shee33.act0.battlefield.network.BattlefieldNetwork;
import org.shee33.act0.battlefield.network.SquadActionPacket;

import javax.annotation.Nullable;

/**
 * 战地模式对局内的 ESC 暂停菜单（《战地暂停菜单动效规格文档》完整实现）。
 *
 * <p>由 {@link BattlefieldPauseMenuHook} 在原版 {@code PauseScreen} 打开时替换进来。
 * 本类刻意保持很薄：一切时间轴与像素在 {@link BattlefieldPauseAnimator}，这里只做生命周期、
 * 输入转发与命令/数据包下发——与本仓库既有的 {@code BattlefieldRoomBrowserScreen +
 * BattlefieldRoomBrowserAnimator} 分工完全一致。
 *
 * <p>{@link #isPauseScreen()} 返回 {@code false}：多人对局本来就不会因为 ESC 暂停，世界必须继续
 * 渲染与推进。这不是可选项，而是规格文档第一条设计原则（"游戏没停"是第一信息）的技术前提——
 * 返回 {@code true} 会让单人测试环境里的世界冻结，整套"票数在菜单里继续掉"的设计当场失效。
 */
public final class BattlefieldPauseScreen extends Screen {

    /** 危险操作的反馈延时：Toast 淡入完再多留 240ms，否则玩家永远看不到自己触发了什么。 */
    private static final int DEFER_MS = PauseMenuAnim.TOAST_IN_MS + 240;

    private final BattlefieldPauseAnimator animator = new BattlefieldPauseAnimator(Tween.now());

    /** 待延迟执行的危险动作与其执行时刻；{@code null} 表示无。 */
    @Nullable
    private BattlefieldPauseAnimator.Item deferred;
    private long deferredAtMs;

    /** Enter 长按状态：键盘与鼠标共用同一套长按确认，只是触发源不同。 */
    private boolean enterHeld;

    public BattlefieldPauseScreen() {
        super(Component.literal("暂停"));
    }

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        long now = Tween.now();
        animator.render(gg, font, width, height, mouseX, mouseY, now);

        BattlefieldPauseAnimator.Item item = animator.pollPendingItem();
        if (item != null) {
            dispatch(item, now);
        }
        if (deferred != null && now >= deferredAtMs) {
            BattlefieldPauseAnimator.Item run = deferred;
            deferred = null;
            execute(run);
            return;
        }
        if (animator.isClosing() && now >= animator.closeDoneAt()) {
            Minecraft mc = Minecraft.getInstance();
            mc.setScreen(null);
        }
    }

    /**
     * 对局结束/被踢出时兜底关闭：菜单里的票数与小队卡片全靠 {@link ClientBattleHud} 驱动，
     * 对局一没了就会画出上一局的残影。
     */
    @Override
    public void tick() {
        if (deferred == null && !animator.isClosing() && !isInBattlefield()) {
            Minecraft.getInstance().setScreen(null);
        }
    }

    // ============================================================
    // 动作分发
    // ============================================================

    private void dispatch(BattlefieldPauseAnimator.Item item, long now) {
        switch (item) {
            case RESUME -> animator.beginClose(now);
            case SETTINGS -> {
                Minecraft mc = Minecraft.getInstance();
                mc.setScreen(new OptionsScreen(this, mc.options));
            }
            // 长按确认项：Toast 已由动画层在填满那一刻弹出，这里只安排延迟执行。
            case LEAVE_MATCH, QUIT_GAME -> {
                deferred = item;
                deferredAtMs = now + DEFER_MS;
            }
            case SQUAD -> {
                // 子面板由动画层内部处理，不会走到这里。
            }
        }
    }

    private void execute(BattlefieldPauseAnimator.Item item) {
        Minecraft mc = Minecraft.getInstance();
        if (item == BattlefieldPauseAnimator.Item.LEAVE_MATCH) {
            LocalPlayer player = mc.player;
            if (player != null) {
                player.connection.sendCommand(Aew1Command.CMD_LEAVE);
            }
            mc.setScreen(null);
            return;
        }
        // 退出游戏：先干净地离开服务器，再退出进程（等价于原版"断开 + 退出"两步）。
        ClientLevel level = mc.level;
        if (level != null) {
            level.disconnect();
        }
        mc.clearLevel();
        mc.stop();
    }

    private void fireSquadAction(PauseSquadPanel.Request request, long now) {
        BattlefieldNetwork.CHANNEL.sendToServer(
                new SquadActionPacket(request.kind(), request.targetSquadId()));
        animator.toast(request.toast(), now);
    }

    // ============================================================
    // 输入
    // ============================================================

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        animator.onMouseMoved(mouseX, mouseY, Tween.now());
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0 || animator.isClosing()) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        long now = Tween.now();
        if (animator.insideSub(mouseX, width)) {
            PauseSquadPanel.Request request = animator.clickSub(mouseX, mouseY, now);
            if (request != null) {
                fireSquadAction(request, now);
            }
            return true;
        }
        int index = animator.itemAt(mouseX, mouseY);
        if (index < 0) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        animator.setFocus(index, now);
        BattlefieldPauseAnimator.Item item = animator.itemOf(index);
        if (item.hold) {
            animator.beginHold(index, now);
        } else {
            animator.activate(item, now);
        }
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && animator.isHolding()) {
            animator.cancelHold(Tween.now());
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    /** 按住不动地拖过界也算移出（规格文档 §3.2：移出即取消）。 */
    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button == 0 && animator.isHolding() && animator.itemAt(mouseX, mouseY) != animator.holdingIndex()) {
            animator.cancelHold(Tween.now());
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        long now = Tween.now();
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            // ESC 开↔关；子页面开着时先只收子页面，返回零成本。
            if (!animator.collapseSub(now)) {
                animator.beginClose(now);
            }
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_W) {
            animator.setFocus(animator.focusIndex() - 1, now);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_DOWN || keyCode == GLFW.GLFW_KEY_S) {
            animator.setFocus(animator.focusIndex() + 1, now);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER
                || keyCode == GLFW.GLFW_KEY_SPACE) {
            BattlefieldPauseAnimator.Item item = animator.focusedItem();
            if (item.hold) {
                // GLFW 的按键重复也会走 keyPressed，重复触发会把长按起点不断推后。
                if (!enterHeld) {
                    enterHeld = true;
                    animator.beginHold(animator.focusIndex(), now);
                }
            } else {
                animator.activate(item, now);
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER
                || keyCode == GLFW.GLFW_KEY_SPACE) {
            enterHeld = false;
            animator.cancelHold(Tween.now());
            return true;
        }
        return super.keyReleased(keyCode, scanCode, modifiers);
    }

    /** ESC 自己接管（要播反向序列），不能让父类直接 {@code onClose}。 */
    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** 与被替换掉的 {@code BattlefieldPauseMenuExitButton} 完全一致的在局判定，原样保留。 */
    static boolean isInBattlefield() {
        if (ClientBattleHud.isShown()) {
            return true;
        }
        var deploy = ClientDeployStatus.status();
        return deploy != null && deploy.active();
    }
}
