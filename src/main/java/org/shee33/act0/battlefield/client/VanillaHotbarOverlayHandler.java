package org.shee33.act0.battlefield.client;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.NamedGuiOverlay;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.shee33.act0.battlefield.Act0Battlefield;

/**
 * vanilla 快捷栏模式的唯一定位器：把原版 HOTBAR/ITEM_NAME 两个覆盖层<b>整体平移</b>到右下角。
 *
 * <p>思路是"照搬原版、只改位置"：不屏蔽、不重绘、不自己调 {@code renderHotbar}——只在
 * {@code RenderGuiOverlayEvent.Pre} 里 {@code pushPose + translate}，让 Forge 随后调用的原版
 * 覆盖层渲染代码（物品图标、选中框、物品名计时等）原样执行、只是画在平移后的坐标上；
 * 同一覆盖层的 {@code Post} 里 {@code popPose} 还原，不污染下一个覆盖层。
 *
 * <p>原版 HOTBAR 的绘制锚点是 {@code screenWidth/2}（屏幕底边居中）。右缘目标 = 屏幕右缘
 * 留白 {@code RIGHT_MARGIN}。原版快捷栏总宽 182px，故整条右移量：
 * {@code dx = (screenWidth - RIGHT_MARGIN - 182) - (screenWidth/2 - 91)}
 * {@code    = screenWidth/2 - RIGHT_MARGIN - 91}。
 *
 * <p>只在对局 HUD 开启 vanilla 模式（{@link ClientVanillaHud#isVanillaHud()}）时生效，
 * 由 {@code /aew1 hud vanilla true} 驱动。若同一帧 HOTBAR 被其他订阅者 cancel（Pre 返回后
 * Forge 跳过 render 与 post），我们的 popPose 不会执行——但那属于非本 mod 主导的异常场景，
 * 且 GuiGraphics 的 pose 每帧由渲染循环重建，最多残留一帧后自愈。
 */
@Mod.EventBusSubscriber(modid = Act0Battlefield.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class VanillaHotbarOverlayHandler {

    /** 快捷栏右缘距屏幕右缘的留白，与其他 HUD 面板的 MARGIN 一致。 */
    private static final int RIGHT_MARGIN = 8;

    /** 原版快捷栏背景总宽 182px（9 格 × 20 + 边框），见 Gui.renderHotbar。 */
    private static final int HOTBAR_W = 182;

    private VanillaHotbarOverlayHandler() {
    }

    @SubscribeEvent
    public static void onOverlayPre(RenderGuiOverlayEvent.Pre event) {
        if (ClientVanillaHud.isVanillaHud() && inMatch() && isHotbarFamily(event.getOverlay())) {
            float dx = event.getWindow().getGuiScaledWidth() / 2f - RIGHT_MARGIN - HOTBAR_W / 2f;
            event.getGuiGraphics().pose().pushPose();
            event.getGuiGraphics().pose().translate(dx, 0f, 0f);
        }
    }

    @SubscribeEvent
    public static void onOverlayPost(RenderGuiOverlayEvent.Post event) {
        if (ClientVanillaHud.isVanillaHud() && inMatch() && isHotbarFamily(event.getOverlay())) {
            event.getGuiGraphics().pose().popPose();
        }
    }

    private static boolean isHotbarFamily(NamedGuiOverlay overlay) {
        if (overlay == null) {
            return false;
        }
        for (VanillaGuiOverlay vanilla : new VanillaGuiOverlay[]{
                VanillaGuiOverlay.HOTBAR, VanillaGuiOverlay.ITEM_NAME}) {
            if (vanilla.type() == overlay) {
                return true;
            }
        }
        return false;
    }

    private static boolean inMatch() {
        return ClientBattleHud.isShown() || ClientBreakthroughHud.isShown();
    }
}
