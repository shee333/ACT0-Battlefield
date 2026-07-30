package org.shee33.act0.battlefield.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.shee33.act0.battlefield.Act0Battlefield;
import org.shee33.act0.battlefield.network.BattleHudDto;
import org.shee33.act0.battlefield.network.ControlPointHudDto;

/**
 * BF 风格小地图：底部右下角 100x100 面板，显示玩家位置、据点菱形标记。
 *
 * <p>使用 {@link RenderGuiEvent.Post} 每帧绘制。世界坐标以玩家为中心投影到
 * 小地图坐标（1 像素 = 2 格，半径 50 格）。颜色规则与战中 HUD 一致。
 *
 * <p><b>已知限制</b>：{@link org.shee33.act0.battlefield.network.SquadMateHudDto}
 * 当前不含世界坐标，因此小队队友 pips 暂不可用。后续需扩展 DTO 加入坐标字段。
 */
@Mod.EventBusSubscriber(modid = Act0Battlefield.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class BattlefieldMinimapOverlay {

    private static final int SIZE = 100;
    private static final int MARGIN_RIGHT = 8;
    private static final int MARGIN_BOTTOM = 8;
    private static final double SCALE = 0.5; // 1 px = 2 blocks → 50-block radius

    // BF2042 flat palette
    private static final int BG = 0x99101418;       // #101418 @ 60%
    private static final int BORDER = 0xFF3A3A3A;   // #3A3A3A
    private static final int PLAYER = 0xFFFFFFFF;    // white
    private static final int BLUE = 0xFF4A90D9;      // ALPHA
    private static final int RED = 0xFFD94A4A;       // BRAVO
    private static final int GREY = 0xFF8C9196;      // neutral
    private static final int YELLOW = 0xFFFF8C00;    // contested
    private static final int LABEL_BG = 0xAA000000;  // label backdrop

    private BattlefieldMinimapOverlay() {
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || mc.options.hideGui) {
            return;
        }
        BattleHudDto hud = ClientBattleHud.hud();
        if (!ClientBattleHud.isShown() || hud == null) {
            return;
        }

        GuiGraphics gg = event.getGuiGraphics();
        int mapX = gg.guiWidth() - SIZE - MARGIN_RIGHT;
        int mapY = gg.guiHeight() - SIZE - MARGIN_BOTTOM;

        renderMinimap(gg, mc.font, mapX, mapY, player, hud);
    }

    private static void renderMinimap(GuiGraphics gg, Font font, int mapX, int mapY,
                                       Player player, BattleHudDto hud) {
        // Background fill
        gg.fill(mapX, mapY, mapX + SIZE, mapY + SIZE, BG);

        // 1px border
        gg.fill(mapX, mapY, mapX + SIZE, mapY + 1, BORDER);             // top
        gg.fill(mapX, mapY + SIZE - 1, mapX + SIZE, mapY + SIZE, BORDER); // bottom
        gg.fill(mapX, mapY, mapX + 1, mapY + SIZE, BORDER);             // left
        gg.fill(mapX + SIZE - 1, mapY, mapX + SIZE, mapY + SIZE, BORDER); // right

        int cx = mapX + SIZE / 2;
        int cz = mapY + SIZE / 2;
        double px = player.getX();
        double pz = player.getZ();

        // Control point diamonds
        for (ControlPointHudDto point : hud.points()) {
            double dx = point.x() - px;
            double dz = point.z() - pz;
            int sx = cx + (int) Math.round(dx * SCALE);
            int sy = cz - (int) Math.round(dz * SCALE); // -Z = north = up

            // Clamp to visible area with small margin so diamonds don't sit on border
            int min = mapX + 3;
            int max = mapX + SIZE - 3;
            if (sx < min || sx > max || sy < min || sy > max) {
                continue;
            }

            int color = pointColor(point.owner(), point.pressure(), hud.myFaction());
            drawDiamond(gg, sx, sy, color);

            // Label (first char of point name)
            String label = point.name().length() > 1
                    ? point.name().substring(0, 1) : point.name();
            int labelW = font.width(label);
            int labelH = font.lineHeight;
            int labelX = sx + 4;
            int labelY = sy - labelH / 2;
            gg.fill(labelX - 1, labelY - 1, labelX + labelW + 1, labelY + labelH + 1, LABEL_BG);
            gg.drawString(font, label, labelX, labelY, 0xFFFFFFFF, false);
        }

        // Player center arrow — simple triangle pointing north
        drawPlayerArrow(gg, cx, cz);

        // NOTE: Squad mate pips are omitted because SquadMateHudDto lacks world
        // coordinates. When the DTO is extended with x/z fields, add rendering here
        // using GREEN (0xFF66CC66) dots.
    }

    /** 4-pixel diamond at (cx, cy). */
    private static void drawDiamond(GuiGraphics gg, int cx, int cy, int color) {
        gg.fill(cx - 2, cy, cx + 3, cy + 1, color);
        gg.fill(cx - 1, cy - 1, cx + 2, cy + 2, color);
        gg.fill(cx, cy - 2, cx + 1, cy + 3, color);
    }

    /** Small upward-pointing triangle at (cx, cz). */
    private static void drawPlayerArrow(GuiGraphics gg, int cx, int cz) {
        // Body
        gg.fill(cx - 1, cz - 3, cx + 2, cz + 3, PLAYER);
        // Head
        gg.fill(cx - 2, cz - 5, cx + 3, cz - 3, PLAYER);
        // Head fill (connects body to head)
        gg.fill(cx - 1, cz - 4, cx + 2, cz - 3, PLAYER);
    }

    private static int pointColor(int owner, int pressure, int myFaction) {
        if (pressure != 0) {
            return YELLOW; // contested / overrun
        }
        if (owner == 0) {
            return GREY; // neutral
        }
        if (myFaction != 0) {
            return owner == myFaction ? BLUE : RED;
        }
        return owner == 1 ? BLUE : RED;
    }
}
