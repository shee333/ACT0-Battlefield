package org.shee33.act0.battlefield.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.shee33.act0.battlefield.Act0Battlefield;
import org.shee33.act0.battlefield.network.BattleHudDto;
import org.shee33.act0.battlefield.network.BreakthroughHudDto;
import org.shee33.act0.battlefield.integration.TaczGunBridge;
import org.shee33.act0.battlefield.network.SquadMateHudDto;

import java.util.List;

/**
 * 第一人称作战 HUD —— 《作战HUD动效规格文档》的三大系统：①中屏击杀提示、②右下武器/装备栏、
 * ③小队与自身血量，外加准心/命中/受击反馈。
 *
 * <p><b>范围红线</b>：规格开篇明令本文档<b>不包含</b>屏幕顶部区域（票数进度条、据点状态图标），
 * 严禁改动或在其位置渲染。本类因此完全不碰 y &lt; 140 的中上部，顶部仍由
 * {@code BattlefieldHudOverlay} / {@code BreakthroughHudOverlay} 各自负责。
 *
 * <p>征服与突破共用同一套作战 HUD：两种模式的 HUD DTO 结构不同，这里只取双方都有的
 * {@code squad} 列表，因此一份实现即可覆盖两个模式（与 {@code BattlefieldMinimapOverlay}
 * 归一化两套 DTO 的做法同源）。
 */
@Mod.EventBusSubscriber(modid = Act0Battlefield.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class CombatHudOverlay {

    /** 小地图占左下角，队友面板紧随其右。 */
    private static final int MINIMAP_SIZE = 84;
    private static final int MARGIN = 8;
    private static final int PANEL_GAP = 12;

    private static boolean wasShown;

    private CombatHudOverlay() {
    }

    /** 供小地图复用，保证两者对左下角的几何认知一致。 */
    static int minimapSize() {
        return MINIMAP_SIZE;
    }

    static int margin() {
        return MARGIN;
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.options.hideGui) {
            return;
        }
        List<SquadMateHudDto> squad = activeSquad();
        if (squad == null) {
            if (wasShown) {
                onHudHidden();
            }
            return;
        }
        long now = Tween.now();
        if (!wasShown) {
            wasShown = true;
            WeaponBarAnimator.playIntro(now);
            HealthPanelAnimator.playIntro(now);
        }

        int selfHpPct = selfHealthPct(player);
        trackSelfDamage(selfHpPct, now);
        trackGunFire(player, now);
        CombatFeedbackAnimator.pollHitFeedback(now);
        KillPromptAnimator.poll(player.getGameProfile().getName(), now);

        GuiGraphics gg = event.getGuiGraphics();
        Font font = mc.font;
        boolean downed = ClientDownedFeedback.isDowned();

        float vignette = CombatFeedbackAnimator.vignetteAlpha(now, selfHpPct, downed);
        if (vignette > 0.005f) {
            HudShapes.edgeVignette(gg, gg.guiWidth(), gg.guiHeight(), 0xFFC81E1E, vignette, 0.55f);
        }

        // 准心刻意画在抖动之外：规格 demo 抖的是整个舞台，但真实对局里抖动准心会直接干扰
        // 瞄准，玩家会分不清是自己手抖还是 HUD 在抖。受击反馈的表达交给红晕与面板抖动即可。
        renderCrosshair(gg, now);

        float[] shake = CombatFeedbackAnimator.shakeOffset(now);
        gg.pose().pushPose();
        gg.pose().translate(shake[0], shake[1], 0f);

        KillPromptRenderer.render(gg, font, now);

        int bottomY = gg.guiHeight() - MARGIN;
        int weaponLeft = WeaponBarRenderer.render(gg, font, player, gg.guiWidth() - MARGIN, bottomY, now);

        int panelLeft = MARGIN + MINIMAP_SIZE + PANEL_GAP;
        int panelMaxRight = Math.min(weaponLeft - CombatHudMath.COLLISION_PAD,
                CombatHudMath.squadPanelMaxRight(gg.guiWidth(), MARGIN, panelLeft));
        HealthPanelRenderer.render(gg, font, squad, selfHpPct, panelLeft, bottomY,
                Math.max(panelLeft + CombatHudMath.SQUAD_BAR_MIN_W, panelMaxRight), now);

        gg.pose().popPose();
    }

    /** 两种模式取各自 HUD 的 squad 列表；都没显示时返回 null。 */
    private static List<SquadMateHudDto> activeSquad() {
        BattleHudDto conquest = ClientBattleHud.hud();
        if (ClientBattleHud.isShown() && conquest != null) {
            return conquest.squad();
        }
        BreakthroughHudDto bt = ClientBreakthroughHud.hud();
        if (ClientBreakthroughHud.isShown() && bt != null && bt.show()) {
            return bt.squad();
        }
        return null;
    }

    private static void onHudHidden() {
        wasShown = false;
        // 不清零会让下次进场时把"上局最后血量→本局满血"当成一次掉血，凭空抖一下屏。
        lastSelfHpPct = -1;
        lastMagCount = -1;
        ClientGunStatus.clear();
        KillPromptAnimator.clear();
        WeaponBarAnimator.clear();
        HealthPanelAnimator.clear();
        CombatFeedbackAnimator.clear();
    }

    private static int lastSelfHpPct = -1;
    private static int lastMagCount = -1;

    /**
     * 开火检测：以 TaCZ 弹匣数下降为准，而不是原版攻击键。TaCZ 的射击走它自己的输入链，
     * 攻击键映射不保证触发；弹匣少一发则是"确实打出去了一发"的确凿信号，且连发武器每发
     * 都会各触发一次准心扩散，与规格 §4.2「开火 → 准心扩散」的语义一致。
     */
    private static void trackGunFire(LocalPlayer player, long now) {
        int mag = TaczGunBridge.currentAmmo(player.getMainHandItem());
        if (mag >= 0 && lastMagCount > mag) {
            CombatFeedbackAnimator.onFire(now);
        }
        lastMagCount = mag;
    }

    private static void trackSelfDamage(int hpPct, long now) {
        if (lastSelfHpPct >= 0 && hpPct < lastSelfHpPct) {
            CombatFeedbackAnimator.onSelfHurt(now);
        }
        lastSelfHpPct = hpPct;
    }

    private static int selfHealthPct(LocalPlayer player) {
        float max = Math.max(1f, player.getMaxHealth());
        return Math.max(0, Math.min(100, Math.round(player.getHealth() / max * 100f)));
    }

    /** 规格 §2：4 短线 + 中点，开火时扩散；命中时四角 X 标记闪现。 */
    private static void renderCrosshair(GuiGraphics gg, long now) {
        int cx = gg.guiWidth() / 2;
        int cy = gg.guiHeight() / 2;
        int spread = Math.round(CombatFeedbackAnimator.spread(now));
        int len = 7;
        int white = 0xE6FFFFFF;

        gg.fill(cx - 1, cy - spread - len, cx + 1, cy - spread, white);
        gg.fill(cx - 1, cy + spread, cx + 1, cy + spread + len, white);
        gg.fill(cx - spread - len, cy - 1, cx - spread, cy + 1, white);
        gg.fill(cx + spread, cy - 1, cx + spread + len, cy + 1, white);
        gg.fill(cx - 1, cy - 1, cx + 1, cy + 1, white);

        float hm = CombatFeedbackAnimator.hitmarkAlpha(now);
        if (hm <= 0.01f) {
            return;
        }
        float s = CombatFeedbackAnimator.hitmarkScale(now);
        int color = CombatFeedbackAnimator.hitmarkIsKill() ? CombatHudMath.RED : 0xFFFFFFFF;
        int a = Math.round(255 * hm);
        int argb = (color & 0x00FFFFFF) | (a << 24);
        gg.pose().pushPose();
        gg.pose().translate(cx, cy, 0);
        gg.pose().scale(s, s, 1f);
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sy = -1; sy <= 1; sy += 2) {
                for (int i = 0; i < 5; i++) {
                    gg.fill(sx * (4 + i), sy * (4 + i), sx * (4 + i) + 1, sy * (4 + i) + 1, argb);
                }
            }
        }
        gg.pose().popPose();
    }
}
