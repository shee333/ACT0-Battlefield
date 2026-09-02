package org.shee33.act0.battlefield.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import org.shee33.act0.battlefield.network.DeployStatusDto;

/**
 * 部署界面左上角"动态模式标签"—— 《部署界面动效规格文档》§3.8 的 Minecraft 移植。
 *
 * <p>本 mod 一场对局从始至终只对应单一模式(Conquest/Breakthrough 二选一)与单一地图/世界,中途
 * 不存在"地图轮换"或"模式切换"(Wave4 调查结论,见 {@code RedeployService} 里
 * {@code matchModeName} 字段与地图名取值处的注释)——因此规格文档 §3.8 描述的"遮罩换字"运行时
 * 切换动效在这里永远不会真正触发。本类只在 {@link #onOpened()} 那一刻复用同一套"遮罩位移"手法
 * 播放一次性入场动效(模式名先入,地图名错峰 80ms 跟进),不实现一套永远用不到的"切换"逻辑。
 */
public final class DeployModeLabel {

    private static final long MODE_DURATION_MS = 280L;
    private static final long MAP_DELAY_MS = 80L;
    private static final long MAP_DURATION_MS = 280L;
    private static final int MASK_WIDTH = 140;

    private static final int MODE_COLOR = 0xFFFFFFFF;
    private static final int MAP_COLOR = 0x99E8EDF2;

    private static long openedAtMs = -1L;

    private DeployModeLabel() {
    }

    /** 部署界面每次打开时调用一次:入场动效从头播放。 */
    public static void onOpened() {
        openedAtMs = Tween.now();
    }

    /** 部署界面关闭时调用:重置状态,避免下次打开复用到旧的补间起点。 */
    public static void onClosed() {
        openedAtMs = -1L;
    }

    /** 渲染左上角两行文字,{@code x,y} 为模式名行的左上角。 */
    public static void render(GuiGraphics gg, Font font, DeployStatusDto st, int x, int y) {
        if (st == null) {
            return;
        }
        String mode = st.modeName();
        if (mode.isBlank()) {
            return;
        }
        long now = Tween.now();
        int lineH = font.lineHeight + 2;

        float modeT = openedAtMs < 0L ? 1f : slideInProgress(now, openedAtMs, 0L, MODE_DURATION_MS);
        drawMaskedLine(gg, font, mode, x, y, lineH, modeT, MODE_COLOR);

        String map = st.mapName();
        if (!map.isBlank()) {
            float mapT = openedAtMs < 0L ? 1f : slideInProgress(now, openedAtMs, MAP_DELAY_MS, MAP_DURATION_MS);
            drawMaskedLine(gg, font, map, x, y + lineH, lineH, mapT, MAP_COLOR);
        }
    }

    /**
     * 纯函数(不读时钟,供单测直接覆盖):§3.8"下滑入(280ms outExpo)"的一次性入场进度([0,1])，
     * {@code delayMs} 供地图名错峰跟进。
     */
    static float slideInProgress(long nowMs, long openedAtMs, long delayMs, long durationMs) {
        float raw = (nowMs - openedAtMs - delayMs) / (float) durationMs;
        return Tween.Ease.OUT_EXPO.apply(Mth.clamp(raw, 0f, 1f));
    }

    /** 遮罩内位移入场:{@code t=0} 时文字整行隐于遮罩底部之外,{@code t=1} 时归位。 */
    private static void drawMaskedLine(GuiGraphics gg, Font font, String text, int x, int y, int lineH,
                                        float t, int color) {
        if (t <= 0f) {
            return;
        }
        gg.enableScissor(x, y, x + MASK_WIDTH, y + lineH);
        int ty = y + Math.round(lineH * (1f - t));
        gg.drawString(font, text, x, ty, withAlpha(color, t), false);
        gg.disableScissor();
    }

    private static int withAlpha(int argb, float alphaMul) {
        int baseA = (argb >>> 24) & 0xFF;
        int a = Math.round(baseA * Mth.clamp(alphaMul, 0f, 1f));
        return (argb & 0x00FFFFFF) | (a << 24);
    }
}
