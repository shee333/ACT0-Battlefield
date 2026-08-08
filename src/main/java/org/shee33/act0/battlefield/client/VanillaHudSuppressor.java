package org.shee33.act0.battlefield.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.NamedGuiOverlay;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.shee33.act0.battlefield.Act0Battlefield;
import org.shee33.act0.battlefield.integration.TaczGunBridge;

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
        if (SUPPRESSED.contains(event.getOverlay()) || isTaczAmmoHud(event.getOverlay())) {
            event.setCanceled(true);
        }
    }

    /**
     * 只屏蔽 TaCZ 自己的<b>弹药 HUD</b>（我们的武器栏已经显示同样的信息，两份会打架）。
     *
     * <p>刻意按 id 精确匹配而不是"整个 tacz 命名空间一刀切"：TaCZ 还注册了瞄准镜等覆盖层，
     * 那些屏蔽掉会直接破坏开镜观感。宁可漏屏蔽（最多多出一处重复读数）也不能误伤。
     */
    private static boolean isTaczAmmoHud(NamedGuiOverlay overlay) {
        if (overlay == null || overlay.id() == null) {
            return false;
        }
        return "tacz".equals(overlay.id().getNamespace()) && overlay.id().getPath().contains("hud");
    }

    /**
     * 近战挥击的准心扩散。TaCZ 枪械的开火不走这条路径，由 {@code CombatHudOverlay} 按弹匣
     * 数下降检测——这里若不排除枪械，开镜点射会被扩散两次。
     */
    @SubscribeEvent
    public static void onAttack(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isAttack() || !inMatch()) {
            return;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null && TaczGunBridge.isGun(player.getMainHandItem())) {
            return;
        }
        CombatFeedbackAnimator.onFire(Tween.now());
    }

    private static boolean inMatch() {
        return ClientBattleHud.isShown() || ClientBreakthroughHud.isShown();
    }
}
