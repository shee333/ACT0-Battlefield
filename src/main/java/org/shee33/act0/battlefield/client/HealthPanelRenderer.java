package org.shee33.act0.battlefield.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import org.shee33.act0.battlefield.network.SquadMateHudDto;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 小队与自身血量面板渲染 —— 《作战HUD动效规格文档》§5。
 *
 * <p>按用户要求置于小地图右侧（规格原位是左下角，小地图现在占了那里）。队友行在上、自身行
 * 在下且更大，与规格的层级一致。
 */
final class HealthPanelRenderer {

    private static final int ROW_H = 13;
    private static final int SELF_ROW_H = 17;
    private static final int ICON = 9;
    private static final int SELF_ICON = 11;
    private static final int MATE_BAR_H = 2;
    private static final int SELF_BAR_H = 4;
    private static final int BAR_INDENT = 13;
    private static final int MATE_BAR_W = 56;
    private static final int SELF_BAR_W = 84;

    private HealthPanelRenderer() {
    }

    /** @return 面板实际占用的最右像素。 */
    static int render(GuiGraphics gg, Font font, List<SquadMateHudDto> squad,
                      int selfHpPct, int leftX, int bottomY, int maxRightX, long now) {
        List<SquadMateHudDto> mates = new ArrayList<>();
        SquadMateHudDto self = null;
        for (SquadMateHudDto m : squad) {
            if (m.self()) {
                self = m;
            } else {
                mates.add(m);
            }
        }

        int mateBarW = CombatHudMath.squadBarWidth(leftX, maxRightX, BAR_INDENT, MATE_BAR_W);
        int selfBarW = CombatHudMath.squadBarWidth(leftX, maxRightX, BAR_INDENT, SELF_BAR_W);

        Set<String> present = new HashSet<>();
        int totalH = mates.size() * ROW_H + (self != null ? SELF_ROW_H : 0);
        int y = bottomY - totalH;

        int row = 0;
        for (SquadMateHudDto m : mates) {
            present.add(m.name());
            HealthPanelAnimator.MemberState st =
                    HealthPanelAnimator.feed(m.name(), m.healthPct(), m.downed(), now);
            drawRow(gg, font, m, st, leftX, y, mateBarW, ICON, MATE_BAR_H, false, now, row);
            y += ROW_H;
            row++;
        }
        if (self != null) {
            present.add(self.name());
            // 自身血量用<b>本地</b>数值：服务端 HUD 快照按 tick 下发，用它做掉血动效会慢半拍，
            // 而自身受击反馈恰恰是最需要即时的。队友只能用服务端值。
            HealthPanelAnimator.MemberState st =
                    HealthPanelAnimator.feed(self.name(), selfHpPct, self.downed(), now);
            drawRow(gg, font, self, st, leftX, y, selfBarW, SELF_ICON, SELF_BAR_H, true, now, row);
        }
        HealthPanelAnimator.retainOnly(present);
        return leftX + BAR_INDENT + Math.max(mateBarW, selfBarW);
    }

    private static void drawRow(GuiGraphics gg, Font font, SquadMateHudDto m,
                                HealthPanelAnimator.MemberState st, int x, int y,
                                int barW, int iconSize, int barH, boolean self, long now, int rowIndex) {
        float intro = HealthPanelAnimator.rowIntroProgress(rowIndex, now);
        if (intro <= 0.01f) {
            return;
        }
        gg.pose().pushPose();
        gg.pose().translate(-14f * (1f - intro), 0f, 0f);

        // 阵亡（流血耗尽或直接死亡）：整行压暗至 0.38，与倒地的"还能救"形成明确区分。
        boolean dead = !m.alive() && !m.downed();
        if (dead) {
            intro *= 0.38f;
        }
        int rowH = self ? SELF_ROW_H : ROW_H;
        float hurt = HealthPanelAnimator.hurtFlashAlpha(st, now);
        float revive = HealthPanelAnimator.reviveFlashAlpha(st, now);
        if (hurt > 0.001f) {
            gg.fill(x - 2, y - 1, x + BAR_INDENT + barW + 2, y + rowH - 2, withAlpha(CombatHudMath.RED, hurt * intro));
        }
        if (revive > 0.001f) {
            gg.fill(x - 2, y - 1, x + BAR_INDENT + barW + 2, y + rowH - 2, withAlpha(CombatHudMath.GREEN, revive * intro));
        }

        boolean downed = m.downed();
        float iconPulse = downed ? CombatHudMath.downedIconPulse(now) : 1f;
        int iconColor = downed ? CombatHudMath.RED : CombatHudMath.GREEN;
        String glyph = downed ? "✚" : (m.isSquadLeader() ? "★" : "●");
        float iconProgress = HealthPanelAnimator.downedIconProgress(st, now);
        gg.pose().pushPose();
        float icx = x + iconSize / 2f;
        float icy = y + iconSize / 2f;
        gg.pose().translate(icx, icy, 0);
        if (downed && iconProgress < 1f) {
            gg.pose().mulPose(com.mojang.math.Axis.ZP.rotationDegrees(90f * (1f - iconProgress)));
        }
        gg.pose().translate(-icx, -icy, 0);
        gg.drawString(font, glyph, Math.round(icx - font.width(glyph) / 2f), Math.round(icy - 4f),
                withAlpha(iconColor, intro * iconPulse), false);
        gg.pose().popPose();

        String name = self ? "你" : m.name();
        int nameColor = downed ? 0xFFE8EDF2 : CombatHudMath.TEXT;
        float nameAlpha = (downed ? 0.45f : (self ? 0.95f : 0.85f)) * intro;
        gg.drawString(font, name, x + BAR_INDENT, y + 1, withAlpha(nameColor, nameAlpha), false);

        int textRight = x + BAR_INDENT + font.width(name) + 4;
        if (self) {
            // 用补间中的填充值而非目标值：数字随血条一起平滑下滚，对应规格的"血量数字滚轮"。
            String hpText = String.valueOf(Math.round(HealthPanelAnimator.fillPct(st, now)));
            gg.drawString(font, hpText, textRight, y + 1,
                    withAlpha(CombatHudMath.healthColor(st.shownPct), intro), false);
            textRight += font.width(hpText) + 4;
        }
        if (downed) {
            String tag = "倒地";
            gg.drawString(font, tag, textRight, y + 1,
                    withAlpha(CombatHudMath.RED, intro * CombatHudMath.downedTagPulse(now)), false);
        } else if (dead) {
            // 「阵亡」常亮不闪：已经没有救援窗口了，闪烁只会制造无谓的紧迫感。
            gg.drawString(font, "阵亡", textRight, y + 1, withAlpha(CombatHudMath.RED, intro), false);
        }

        drawBar(gg, st, m, x + BAR_INDENT, y + (self ? 11 : 9), barW, barH, dead, intro, now);
        gg.pose().popPose();
    }

    private static void drawBar(GuiGraphics gg, HealthPanelAnimator.MemberState st, SquadMateHudDto m,
                                int x, int y, int w, int h, boolean dead, float intro, long now) {
        if (dead) {
            gg.fill(x, y, x + w, y + h, withAlpha(CombatHudMath.BAR_TRACK, intro));
            return;
        }
        boolean downed = m.downed();
        float pulse = HealthPanelAnimator.thresholdPulse(st, now);
        gg.pose().pushPose();
        if (pulse != 1f) {
            float cy = y + h / 2f;
            gg.pose().translate(0, cy, 0);
            gg.pose().scale(1f, pulse, 1f);
            gg.pose().translate(0, -cy, 0);
        }

        gg.fill(x, y, x + w, y + h, withAlpha(CombatHudMath.BAR_TRACK, intro));

        float pct;
        int color;
        float fillAlpha = intro;
        if (downed) {
            // 倒地：血条转红并从满格线性流血，本身就是救援紧迫度读数。
            pct = HealthPanelAnimator.bleedRemaining(st, now) * 100f;
            color = CombatHudMath.RED;
        } else {
            pct = HealthPanelAnimator.fillPct(st, now);
            color = CombatHudMath.healthColor(st.shownPct);
            if (CombatHudMath.isCritical(st.shownPct)) {
                fillAlpha *= CombatHudMath.criticalPulseAlpha(now);
            }
        }
        int fillW = Math.round(w * Math.max(0f, Math.min(100f, pct)) / 100f);
        if (fillW > 0) {
            gg.fill(x, y, x + fillW, y + h, withAlpha(color, fillAlpha));
        }

        if (!downed) {
            float ghost = HealthPanelAnimator.ghostPct(st, now);
            if (ghost > 0.01f) {
                int gw = Math.round(w * ghost / 100f);
                gg.fill(x + fillW, y, Math.min(x + w, x + fillW + gw), y + h,
                        withAlpha(CombatHudMath.GHOST, intro));
            }
        }
        gg.pose().popPose();
    }

    private static int withAlpha(int color, float alpha) {
        int a = Math.max(0, Math.min(255, Math.round(255 * Math.max(0f, Math.min(1f, alpha)))));
        return (color & 0x00FFFFFF) | (a << 24);
    }
}
