package org.shee33.act0.battlefield.client;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.NamedGuiOverlay;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.shee33.act0.battlefield.Act0Battlefield;

import java.util.Set;

/**
 * 对局期间隐藏被作战 HUD 取代的原版 HUD 元件，并把开火动作转发给准心扩散。
 *
 * <p>{@link CombatHudOverlay} 自绘了快捷栏、准心与血量，若原版同时还在画，屏幕上会出现两套
 * 血条、两个准心、以及底部中央一条与右下武器栏重复的快捷栏。饥饿/护甲/经验条一并隐藏是因为
 * 它们原本紧贴快捷栏排布，快捷栏一旦撤走这几条会散落在屏幕底部中央，反而更碍眼。
 *
 * <p>只在对局 HUD 显示时生效，退出对局立刻恢复原版 HUD。
 */
@Mod.EventBusSubscriber(modid = Act0Battlefield.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class VanillaHudSuppressor {

    private static final Set<NamedGuiOverlay> SUPPRESSED = Set.of(
            VanillaGuiOverlay.CROSSHAIR.type(),
            VanillaGuiOverlay.HOTBAR.type(),
            VanillaGuiOverlay.PLAYER_HEALTH.type(),
            VanillaGuiOverlay.FOOD_LEVEL.type(),
            VanillaGuiOverlay.ARMOR_LEVEL.type(),
            VanillaGuiOverlay.EXPERIENCE_BAR.type());

    private VanillaHudSuppressor() {
    }

    @SubscribeEvent
    public static void onRenderOverlayPre(RenderGuiOverlayEvent.Pre event) {
        if (!inMatch()) {
            return;
        }
        if (SUPPRESSED.contains(event.getOverlay())) {
            event.setCanceled(true);
        }
    }

    /** 开火即准心扩散（规格 §4.2）。用输入事件而非挥手动画，近战与远程一致。 */
    @SubscribeEvent
    public static void onAttack(InputEvent.InteractionKeyMappingTriggered event) {
        if (event.isAttack() && inMatch()) {
            CombatFeedbackAnimator.onFire(Tween.now());
        }
    }

    private static boolean inMatch() {
        return ClientBattleHud.isShown() || ClientBreakthroughHud.isShown();
    }
}
