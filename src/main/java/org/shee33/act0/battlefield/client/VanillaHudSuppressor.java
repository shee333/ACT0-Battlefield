package org.shee33.act0.battlefield.client;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.NamedGuiOverlay;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.shee33.act0.battlefield.Act0Battlefield;

import java.util.EnumSet;
import java.util.Set;

/**
 * 对局期间隐藏被作战 HUD 取代的原版 HUD 元件，并把开火动作转发给准心扩散。
 *
* <p>{@link CombatHudOverlay} 自绘了快捷栏与血量，若原版同时还在画，屏幕上会出现两套血条、
* 以及底部中央一条与右下武器栏重复的快捷栏。<b>准星不在屏蔽之列</b>——自绘准星已按需求移除，
* 交还原版/TaCZ 渲染。饥饿/护甲/经验条一并隐藏是因为
* 它们原本紧贴快捷栏排布，快捷栏一旦撤走这几条会散落在屏幕底部中央，反而更碍眼。
 *
 * <p>ITEM_NAME（选中物品名）原版画在快捷栏上方居中，随快捷栏一并屏蔽。
 * vanilla 快捷栏模式（{@code /aew1 hud vanilla true}）下快捷栏与物品名由
 * {@link CombatHudOverlay} 用原版 {@code Gui#renderHotbar} 重画到右下角，此处仍屏蔽原版居中版本。
 *
* <p>只在对局 HUD 显示时生效，退出对局立刻恢复原版 HUD。
*/
@Mod.EventBusSubscriber(modid = Act0Battlefield.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class VanillaHudSuppressor {

    /**
     * 这里存的是<b>枚举常量本身</b>，绝不能在静态初始化里就调 {@code .type()}。
     *
     * <p>带 {@code @Mod.EventBusSubscriber} 的类会在 mod CONSTRUCT 阶段就被加载以注册事件方法，
     * 而原版覆盖层的 {@link NamedGuiOverlay} 实例要等到 Forge 触发 {@code RegisterGuiOverlaysEvent}
     * 才被赋值——此刻 {@code VanillaGuiOverlay.XXX.type()} 全是 null。{@code Set.of} 不接受 null
     * 元素，会直接抛 NPE 让整个 mod 构造失败、客户端起不来。改为在事件期再解析。
     */
    private static final Set<VanillaGuiOverlay> SUPPRESSED = EnumSet.of(
            VanillaGuiOverlay.HOTBAR,
            VanillaGuiOverlay.ITEM_NAME,
            VanillaGuiOverlay.PLAYER_HEALTH,
            VanillaGuiOverlay.FOOD_LEVEL,
            VanillaGuiOverlay.ARMOR_LEVEL,
            VanillaGuiOverlay.EXPERIENCE_BAR);

    private VanillaHudSuppressor() {
    }

    @SubscribeEvent
    public static void onRenderOverlayPre(RenderGuiOverlayEvent.Pre event) {
        if (!inMatch()) {
            return;
        }
        if (isSuppressedVanilla(event.getOverlay()) || isReplacedTaczOverlay(event.getOverlay())) {
            event.setCanceled(true);
        }
    }

    /** 事件期解析 {@code .type()}：此时覆盖层已注册完毕，不会再是 null。 */
    private static boolean isSuppressedVanilla(NamedGuiOverlay overlay) {
        if (overlay == null) {
            return false;
        }
        for (VanillaGuiOverlay vanilla : SUPPRESSED) {
            if (vanilla.type() == overlay) {
                return true;
            }
        }
        return false;
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
        if (!"tacz".equals(overlay.id().getNamespace())) {
            return false;
        }
        String path = overlay.id().getPath();
        // 原版快捷栏模式下弹药读数交还 TaCZ 自带 HUD，不再由自绘武器栏取代。
        if ("tac_gun_hud_overlay".equals(path) && ClientVanillaHud.isVanillaHud()) {
            return false;
        }
        return SUPPRESSED_TACZ_OVERLAYS.contains(path);
    }

    private static boolean inMatch() {
        return ClientBattleHud.isShown() || ClientBreakthroughHud.isShown();
    }
}
