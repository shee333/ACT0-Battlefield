package org.shee33.act0.battlefield.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * 武器/装备栏渲染 —— 《作战HUD动效规格文档》§4。
 *
 * <p>与规格 demo 的差异（据实记录，非偷工减料）：
 * <ul>
 *   <li>demo 的槽内是灰色剪影占位块，文档原话"留待渲染实际物品图标"——这里直接渲染
 *       {@link ItemStack} 真实图标，未选中槽用半透明黑罩压暗来还原"剪影亮度 0.18→0.60"的层级。</li>
 *   <li>弹药位与换弹进度条接的是 <b>TaCZ 真实数据</b>（见 {@link ClientGunStatus}）：枪械显示
 *       {@code 弹匣/备弹}、换弹时进度条走真实剩余时间。非 TaCZ 物品按原版语义回退
 *       （可堆叠 {@code ×N}、有耐久显示剩余耐久、其余 {@code ∞}），与规格"枪 cur/res、
 *       近战 ∞、道具 ×N"的信息层级一一对应。</li>
 * </ul>
 */
final class WeaponBarRenderer {

    private static final int KEY_ROW_H = 9;
    private static final int ICON = 16;
    // 宽度取自 CombatHudMath：防重叠计算用的是那份常量，这里若另立一套，两者一旦不一致
    // 碰撞保护就会算错边界（本次开发中已经因此踩过一次）。
    private static final int INFO_GAP = CombatHudMath.INFO_GAP;
    private static final int INFO_W = CombatHudMath.INFO_W;

    private WeaponBarRenderer() {
    }

    /** @return 本次绘制占用的最左像素，供上层做与左侧面板的碰撞保护。 */
    static int render(GuiGraphics gg, Font font, LocalPlayer player, int rightX, int bottomY, long now) {
        int rowW = CombatHudMath.slotRowWidth();
        int totalW = rowW + INFO_GAP + INFO_W;
        int left = rightX - totalW;
        int slotsLeft = left;
        int baseline = bottomY - KEY_ROW_H;

        syncSelection(player, now);

        float underlineTargetLeft = slotsLeft;
        float underlineTargetWidth = 0f;
        int x = slotsLeft;
        for (int i = 0; i < CombatHudMath.SLOT_COUNT; i++) {
            float raise = WeaponBarAnimator.raise(i, now);
            float intro = WeaponBarAnimator.introSlotProgress(i, now);
            int w = CombatHudMath.slotWidth(i);
            float aw = w * (1f + (CombatHudMath.SLOT_ACTIVE_W_MUL - 1f) * raise);
            float ah = CombatHudMath.SLOT_H + (CombatHudMath.SLOT_H_ACTIVE - CombatHudMath.SLOT_H) * raise;
            // 以底边为锚向上生长：y 随高度反向移动，底边恒定在 baseline。
            float top = baseline - ah + 10f * (1f - intro);
            float alpha = intro;
            if (alpha > 0.01f) {
                drawSlot(gg, font, player, i, x, top, aw, ah, raise, alpha, now);
            }
            if (i == WeaponBarAnimator.selected()) {
                underlineTargetLeft = x;
                underlineTargetWidth = aw;
            }
            x += w + CombatHudMath.SLOT_GAP;
        }

        drawUnderline(gg, underlineTargetLeft, underlineTargetWidth, baseline, now);
        drawInfoBlock(gg, font, rightX, baseline, now);
        return left;
    }

    /**
     * 配装只占用快捷栏 0~5，但玩家仍可滚到 6~8。此时<b>不</b>把选中态钳到第 5 格——那会高亮
     * 一把玩家并没拿在手里的武器；改为所有槽位都不升起、下划线宽度归零，信息块照常显示真正
     * 手持的物品，如实反映"当前手持不在配装栏内"。
     */
    private static void syncSelection(LocalPlayer player, long now) {
        int sel = player.getInventory().selected;
        ItemStack stack = player.getInventory().getItem(sel);
        String name = stack.isEmpty() ? "空槽位" : stack.getHoverName().getString();
        WeaponBarAnimator.select(sel, name, ClientGunStatus.ammoText(player, stack), now);
    }

    private static void drawSlot(GuiGraphics gg, Font font, LocalPlayer player, int index,
                                 float x, float y, float w, float h, float raise, float alpha, long now) {
        float bgAlpha = (0.5f + 0.32f * raise) * alpha;
        int bg = (Math.round(16 + 16 * raise) << 16) | (Math.round(21 + 18 * raise) << 8) | Math.round(27 + 20 * raise);
        HudShapes.fillSkewedRect(gg, x, y, w, h, CombatHudMath.SKEW_DEG, 0xFF000000 | bg, bgAlpha);

        ItemStack stack = player.getInventory().getItem(index);
        if (!stack.isEmpty()) {
            int ix = Math.round(x + (w - ICON) / 2f);
            int iy = Math.round(y + (h - ICON) / 2f);
            gg.renderItem(stack, ix, iy);
            // 未选中槽压暗，对应规格"剪影亮度 0.18→0.60"的三级层级；选中槽不压暗即最亮。
            float dim = (1f - raise) * 0.55f * alpha;
            if (dim > 0.01f) {
                HudShapes.fillSkewedRect(gg, x, y, w, h, CombatHudMath.SKEW_DEG, 0xFF0A0E12, dim);
            }
        }

        String key = String.valueOf(index + 1);
        int keyColor = raise > 0.5f ? CombatHudMath.GOLD : 0xFFE8EDF2;
        float keyAlpha = raise > 0.5f ? alpha : alpha * 0.45f;
        gg.drawString(font, key, Math.round(x + w / 2f - font.width(key) / 2f),
                Math.round(y + h + 2), withAlpha(keyColor, keyAlpha), false);
    }

    private static void drawUnderline(GuiGraphics gg, float targetLeft, float targetWidth, int baseline, long now) {
        float[] u = WeaponBarAnimator.underline(targetLeft, targetWidth, now);
        float w = u[1] * WeaponBarAnimator.introUnderlineFactor(now);
        if (w <= 0.5f) {
            return;
        }
        HudShapes.fillSkewedRect(gg, u[0], baseline + 1f, w, 2f, CombatHudMath.SKEW_DEG, CombatHudMath.GOLD, 1f);
    }

    private static void drawInfoBlock(GuiGraphics gg, Font font, int rightX, int baseline, long now) {
        int nameY = baseline - 34;
        int lineH = 9;
        // GuiGraphics.enableScissor 的裁剪矩形不跟随 PoseStack 变换，而这里正处在受击抖动的
        // translate 之下。若不把抖动补偿进裁剪坐标，受击那 200ms 内文字会被裁歪、缺一块。
        float[] shake = CombatFeedbackAnimator.shakeOffset(now);
        int sx = Math.round(shake[0]);
        int sy = Math.round(shake[1]);
        // 遮罩换字：裁剪出一行高的窗口，旧名上出、新名下入。
        gg.enableScissor(rightX - INFO_W - 4 + sx, nameY + sy, rightX + sx, nameY + lineH + 1 + sy);
        String name = WeaponBarAnimator.weaponName();
        float off = WeaponBarAnimator.nameOffsetRatio(now) * lineH;
        gg.drawString(font, name, rightX - font.width(name), Math.round(nameY + off),
                withAlpha(CombatHudMath.TEXT, 0.85f), false);
        gg.disableScissor();

        int ammoY = baseline - 22;
        int ammoH = 12;
        gg.enableScissor(rightX - INFO_W - 4 + sx, ammoY + sy, rightX + sx, ammoY + ammoH + sy);
        float roll = WeaponBarAnimator.ammoRoll(now);
        int dir = WeaponBarAnimator.ammoDir();
        String mag = WeaponBarAnimator.ammoText();
        String old = WeaponBarAnimator.oldAmmoText();
        String reserve = WeaponBarAnimator.reserveText();
        // 开火只滚弹匣数字：备弹部分静止绘制在右端，弹匣滚轮只占据它左侧的宽度。
        // 滚动幅度比整串滚动时减半（6px），数字更替更克制。
        int reserveW = Math.round(font.width(reserve) * 1.6f);
        float amp = ammoH * 0.5f;
        if (roll < 1f && !old.isEmpty()) {
            drawScaled(gg, font, old, rightX - reserveW, ammoY - dir * amp * roll, 1.6f, 0xFFFFFFFF, 1f);
        }
        drawScaled(gg, font, mag, rightX - reserveW, ammoY + dir * amp * (1f - roll), 1.6f, 0xFFFFFFFF, 1f);
        if (!reserve.isEmpty()) {
            drawScaled(gg, font, reserve, rightX, ammoY, 1.6f, 0xFFFFFFFF, 1f);
        }
        gg.disableScissor();

        float cooldown = ClientGunStatus.progress();
        int barY = baseline - 6;
        gg.fill(rightX - INFO_W, barY, rightX, barY + 1, 0x1FFFFFFF);
        if (cooldown > 0f) {
            gg.fill(rightX - INFO_W, barY, rightX - INFO_W + Math.round(INFO_W * cooldown), barY + 2,
                    CombatHudMath.GOLD);
        }
    }

    private static void drawScaled(GuiGraphics gg, Font font, String text, int rightX, float y,
                                   float scale, int color, float alpha) {
        gg.pose().pushPose();
        gg.pose().translate(rightX - font.width(text) * scale, y, 0);
        gg.pose().scale(scale, scale, 1f);
        gg.drawString(font, text, 0, 0, withAlpha(color, alpha), false);
        gg.pose().popPose();
    }

    private static int withAlpha(int color, float alpha) {
        int a = Math.max(0, Math.min(255, Math.round(255 * alpha)));
        return (color & 0x00FFFFFF) | (a << 24);
    }
}
