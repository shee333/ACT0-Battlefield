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
        if (SUPPRESSED.contains(event.getOverlay()) || isReplacedTaczOverlay(event.getOverlay())) {
            event.setCanceled(true);
        }
    }

    /**
     * 被作战 HUD 取代的 TaCZ 覆盖层，按<b>精确 id</b> 屏蔽。
     *
     * <p>id 取自 TaCZ 1.1.8-hotfix 的 {@code ClientSetupEvent.onRegisterGuiOverlays}，
     * 命名空间即其 modId {@code tacz}。它一共注册四个覆盖层，这里只屏蔽与我们重复的两个：
     * <ul>
     *   <li>{@code tac_gun_hud_overlay} —— 弹药/弹匣读数，已由武器栏取代</li>
     *   <li>{@code tac_kill_amount_overlay} —— 击杀计数，已由击杀提示取代</li>
     * </ul>
     * 保留 {@code tac_heat_bar}（枪管过热，我们没有对应显示）与
     * {@code tac_interact_key_overlay}（交互按键提示，不重复）。
     *
     * <p>刻意不按"整个 tacz 命名空间一刀切"，也不按关键字模糊匹配：前者会连过热条和交互
     * 提示一起误伤，后者正是之前的写法——{@code contains("hud")} 只碰巧命中弹药层，漏掉了
     * 击杀计数，导致它和我们的击杀提示同时显示。
     */
    private static final Set<String> SUPPRESSED_TACZ_OVERLAYS =
            Set.of("tac_gun_hud_overlay", "tac_kill_amount_overlay");

    private static boolean isReplacedTaczOverlay(NamedGuiOverlay overlay) {
        if (overlay == null || overlay.id() == null) {
            return false;
        }
        return "tacz".equals(overlay.id().getNamespace())
                && SUPPRESSED_TACZ_OVERLAYS.contains(overlay.id().getPath());
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
