package org.shee33.act0.battlefield.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 击杀提示渲染 —— 《作战HUD动效规格文档》§3。
 *
 * <p>位于准心左下方（39% / 57%），刻意不进入准心区域。整体透明度封顶 0.88。
 *
 * <p>RGB 分离故障按规格 §8 的移植对照实现：同一段文字以红/青两色各偏移 ±amp 各画一遍，
 * 再叠本体，配合逐帧随机的横向抖动。故障只在弹出瞬间释放，窗口过后完全静止。
 */
final class KillPromptRenderer {

    private KillPromptRenderer() {
    }

    static void render(GuiGraphics gg, Font font, long now) {
        if (!KillPromptAnimator.visible()) {
            return;
        }
        float alpha = KillPromptAnimator.alpha(now);
        if (alpha <= 0.02f) {
            return;
        }

        int cx = Math.round(gg.guiWidth() * 0.39f);
        int cy = Math.round(gg.guiHeight() * 0.57f) - Math.round(KillPromptAnimator.exitLiftPx(now));
        int tier = KillPromptAnimator.tier();
        int color = CombatHudMath.tierColor(tier);

        float amp = 0f;
        float jitter = 0f;
        if (KillPromptAnimator.glitchActive(now)) {
            amp = CombatHudMath.glitchAmplitude(tier);
            // 45% 概率逐帧闪：不是每帧都抖，才有"信号不稳"的观感而非匀速震动。
            long frame = now / 16L;
            boolean flash = Math.floorMod(frame * 2654435761L, 100L) < 45L;
            if (!flash) {
                amp = 0f;
            } else {
                jitter = ((Math.floorMod(frame * 40503L, 200L) / 100f) - 1f) * amp;
            }
        }

        float scale = KillPromptAnimator.scale(now);
        gg.pose().pushPose();
        gg.pose().translate(cx + jitter, cy, 0);
        gg.pose().scale(scale, scale, 1f);

        int y = 0;
        if (KillPromptAnimator.streak() >= 2) {
            String streakText = "连杀 ×" + KillPromptAnimator.streak();
            float ts = KillPromptAnimator.streakTagScale(now);
            gg.pose().pushPose();
            gg.pose().scale(ts, ts, 1f);
            drawCentered(gg, font, streakText, 0, Math.round(y / Math.max(0.01f, ts)), color, alpha, 0f);
            gg.pose().popPose();
            y += 11;
        }

        String head = "☠ " + KillPromptAnimator.displayScore(now);
        drawCentered(gg, font, head, 0, y, color, alpha, amp);
        y += 12;

        String name = KillPromptAnimator.displayName(now, now / 40L);
        drawCentered(gg, font, name, 0, y, 0xFFFF8A80, alpha, amp);
        y += 11;

        // 规格的「远射 N 米」标签需要受害者坐标，而 KillFeedPacket 只带名字与阵营，
        // 客户端无从得知击杀距离。不伪造一个数字，直接省掉这个 chip。
        StringBuilder tags = new StringBuilder();
        tags.append("击杀");
        if (KillPromptAnimator.streak() >= 3) {
            tags.append("  压制敌人");
        }
        drawCentered(gg, font, tags.toString(), 0, y, 0xFFFFFFFF, alpha * 0.85f, 0f);

        gg.pose().popPose();
    }

    /** {@code amp>0} 时先画红/青偏移残影再叠本体，形成 RGB 分离。 */
    private static void drawCentered(GuiGraphics gg, Font font, String text, int cx, int y,
                                     int color, float alpha, float amp) {
        int half = font.width(text) / 2;
        if (amp > 0f) {
            gg.drawString(font, text, cx - half + Math.round(amp), y, withAlpha(0xFFFF3C5A, alpha * 0.75f), false);
            gg.drawString(font, text, cx - half - Math.round(amp), y, withAlpha(0xFF3CDCFF, alpha * 0.75f), false);
        }
        gg.drawString(font, text, cx - half, y, withAlpha(color, alpha), false);
    }

    private static int withAlpha(int color, float alpha) {
        int a = Math.max(0, Math.min(255, Math.round(255 * Math.max(0f, Math.min(1f, alpha)))));
        return (color & 0x00FFFFFF) | (a << 24);
    }
}
