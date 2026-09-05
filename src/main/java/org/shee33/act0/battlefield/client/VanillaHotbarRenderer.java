package org.shee33.act0.battlefield.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.level.GameType;
import net.minecraftforge.client.gui.overlay.ForgeGui;

/**
 * vanilla 快捷栏模式：把 MC <b>原版快捷栏本体</b>平移到右下角重画。
 *
 * <p>背景：管理员用 {@code /aew1 hud vanilla true} 切换。某些模组物品在我们自绘武器栏里显示为
 * 紫黑（BEWLR/资源缺失问题），此模式交还原版 {@code Gui#renderHotbar} 渲染——原版对物品的绘制
 * 路径与游戏内一致，天然规避紫黑。
 *
 * <p>原版 HOTBAR/ITEM_NAME 覆盖层由 {@link VanillaHudSuppressor} 保持屏蔽（它们写死画在底部
 * 居中），本类在 {@code RenderGuiEvent.Post} 里用 {@code pushPose + translate} 包住
 * {@code renderHotbar}/{@code renderSelectedItemName} 重画到右下角。renderHotbar 内部坐标全部
 * 取自 {@code Gui.screenWidth/screenHeight}（绝对居中锚点），叠加 pose 位移后整条随平移；
 * 该字段每帧由 ForgeGui.render 开头刷新为当前 GUI 尺寸，Post 阶段读到的即本帧值。
 *
 * <p>只平移 X 不平移 Y：原版快捷栏本就贴屏幕底（y = screenHeight-22），右下角只是横向右移。
 * 渲染前按原版 overlay 惯例设置混合/深度状态（HOTBAR lambda 亦如此做）。
 */
final class VanillaHotbarRenderer {

    /** 快捷栏右缘距屏幕右缘的留白，与其他 HUD 面板的 MARGIN 一致。 */
    private static final int RIGHT_MARGIN = 8;

    /** 原版快捷栏背景总宽 182px（9 格 × 20 + 边框），见 Gui.renderHotbar 常量。 */
    private static final int HOTBAR_W = 182;

    private VanillaHotbarRenderer() {
    }

    /** 是否处于 vanilla 快捷栏模式（服务端 {@code /aew1 hud vanilla} 驱动的客户端缓存）。 */
    static boolean active() {
        return ClientVanillaHud.isVanillaHud();
    }

    /**
     * 原版快捷栏占用的横向宽度提示（含副手在右侧时的额外 30px），供上层碰撞保护使用。
     */
    static int hotbarWidthHint(LocalPlayer player) {
        boolean offhandRight = player != null
                && player.getMainArm() == HumanoidArm.LEFT
                && !player.getOffhandItem().isEmpty();
        return HOTBAR_W + (offhandRight ? 30 : 0);
    }

    /**
     * 在 RenderGuiEvent.Post 里把原版快捷栏画到右下角。
     *
     * @return 原版快捷栏最左像素（供上层做与左侧血量面板的碰撞保护）；未绘制返回屏幕宽。
     */
    static int render(GuiGraphics gg, float partialTick, LocalPlayer player) {
        Minecraft mc = Minecraft.getInstance();
        int screenWidth = gg.guiWidth();
        if (mc.player == null || mc.options.hideGui || mc.gameMode == null) {
            return screenWidth;
        }
        // 观战模式的原版 hotbar 走 SpectatorGui 的独立绘制，不是 renderHotbar；对局内玩家是
        // 正常视角才会走到这里，spectator 直接跳过（部署界面有自己的 UI）。
        if (mc.gameMode.getPlayerMode() == GameType.SPECTATOR) {
            return screenWidth;
        }

        // 原版 HOTBAR 横向居中锚点是 screenWidth/2、宽 182。要把右缘挪到 screenWidth - RIGHT_MARGIN，
        // 整条右移量 = (右缘目标) - (当前右缘) = (screenWidth - RIGHT_MARGIN) - (screenWidth/2 + 91)。
        float dx = (float) screenWidth - RIGHT_MARGIN - (screenWidth / 2f + HOTBAR_W / 2f);

        ForgeGui gui = (ForgeGui) mc.gui;
        // 与 vanilla overlay lambda 相同的渲染状态前置（HOTBAR/ITEM_NAME 都这么调）。
        gui.setupOverlayRenderState(true, false);

        gg.pose().pushPose();
        gg.pose().translate(dx, 0f, 0f);
        gui.renderHotbar(partialTick, gg);
        // 选中物品名原版画在 hotbar 上方，同样随平移。无参版在名字未变化时会跳过重绘（原版逻辑）。
        gui.renderSelectedItemName(gg);
        gg.pose().popPose();

        return Math.round(screenWidth - RIGHT_MARGIN - hotbarWidthHint(player));
    }
}