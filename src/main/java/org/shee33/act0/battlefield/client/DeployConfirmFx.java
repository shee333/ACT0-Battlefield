package org.shee33.act0.battlefield.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

/**
 * 部署确认转场 —— 《部署界面动效规格文档》§3.7 的 Minecraft 移植:去掉网页 demo 的"地图缩放推进"
 * 段落(Minecraft 已有等效的 900ms 相机平滑过场,见 {@link ClientDeployPan}/{@link
 * DeployPanCameraHandler},不重复实现),只叠加规格文档描述的另外两个纯 2D HUD 层元素:全屏白闪 +
 * "正在部署"文字。
 *
 * <p><b>触发</b>:{@link #trigger()} 由 {@code BattlefieldDeployScreen} 在客户端确认部署的
 * 那一刻(与发送确认部署网络包同一帧)调用,因此与服务端发起的 900ms 相机过场同步起跑,不会有
 * 额外的可感知延迟。
 *
 * <p><b>与落地反馈衔接</b>:{@link #onLanded()} 由 {@code DeploySpawnFxPacket} 的处理器在触发
 * {@code ClientDeployFx.trigger}(黑幕淡出 + toast,既有逻辑,本类不改动它)的同一帧调用——两者
 * 共享同一个"落地那一刻"的时间基准,因此白闪淡出与黑幕淡出天然咬合,不需要额外猜测网络延迟。
 * {@link #FADE_OUT_MS} 比黑幕的 300ms 淡出更短,保证"白闪先退场、黑幕再进场"，不会出现两层
 * 强对比色叠在一起的花屏感。
 *
 * <p><b>兜底</b>:若因故(比如部署请求在到达服务端前失效,没有收到落地包)一直等不到
 * {@link #onLanded()}，{@link #MAX_WAIT_FOR_LANDING_MS} 之后自动视为"到点落地"退场，避免白闪
 * 永久卡屏。
 */
public final class DeployConfirmFx {

    /** 白闪层颜色 —— 规格文档 §2 给定的浅蓝白 #e8f2ff。 */
    private static final int FLASH_COLOR = 0xFFE8F2FF;
    /** "正在部署"文字颜色 —— 白闪背景下取深色保证对比度,近似规格文档 §3.7 给定的 #14181d。 */
    private static final int TEXT_COLOR = 0xFF14181D;

    /** 白闪淡入延迟:900ms 相机过场大约 2/3 处起,按比例换算自规格文档 §3.7 的"620/750"时间点。 */
    private static final long FLASH_IN_DELAY_MS = 600L;
    private static final long FLASH_IN_DURATION_MS = 400L;
    private static final float FLASH_MAX_ALPHA = 0.95f;
    /** "正在部署"延迟到白闪几近满值才显示,对应规格文档"白闪满,正在部署文字显示"。 */
    private static final long TEXT_DELAY_MS = FLASH_IN_DELAY_MS + FLASH_IN_DURATION_MS;
    private static final long TEXT_FADE_IN_MS = 120L;
    /** 落地后的退场时长,故意短于 {@code ClientDeployFx} 的 300ms 黑幕淡出,确保白闪先退场。 */
    private static final long FADE_OUT_MS = 220L;
    /** 等不到落地信号时的安全兜底(900ms 相机过场 + 充裕的网络延迟余量)。 */
    private static final long MAX_WAIT_FOR_LANDING_MS = 1600L;

    private static long triggeredAtMs = -1L;
    private static long landedAtMs = -1L;

    private DeployConfirmFx() {
    }

    /** 玩家确认部署那一刻调用:与发送确认部署网络包同一帧,和服务端 900ms 相机过场同步起跑。 */
    public static void trigger() {
        triggeredAtMs = Tween.now();
        landedAtMs = -1L;
    }

    /** 由 {@code DeploySpawnFxPacket} 处理器在触发 {@code ClientDeployFx.trigger} 的同一帧调用。 */
    public static void onLanded() {
        if (triggeredAtMs >= 0L && landedAtMs < 0L) {
            landedAtMs = Tween.now();
        }
    }

    /** 转场是否仍需要渲染(白闪或文字尚未完全淡出);顺带做超时后的自动状态清理。 */
    public static boolean isActive() {
        if (triggeredAtMs < 0L) {
            return false;
        }
        long now = Tween.now();
        long sinceLanded = effectiveSinceLandedMs(now);
        if (sinceLanded >= FADE_OUT_MS) {
            triggeredAtMs = -1L;
            landedAtMs = -1L;
            return false;
        }
        return true;
    }

    /** 渲染白闪层 + "正在部署"文字;由 HUD overlay 每帧调用,内部自行判断是否需要绘制。 */
    public static void render(GuiGraphics gg, Font font) {
        if (!isActive()) {
            return;
        }
        long now = Tween.now();
        long sinceTrigger = now - triggeredAtMs;
        long sinceLanded = effectiveSinceLandedMs(now);

        float flash = flashAlpha(sinceTrigger, sinceLanded);
        if (flash > 0.003f) {
            gg.fill(0, 0, gg.guiWidth(), gg.guiHeight(), withAlpha(FLASH_COLOR, flash));
        }
        float text = textAlpha(sinceTrigger, sinceLanded);
        if (text > 0.01f) {
            drawSpacedCentered(gg, font, "正在部署", gg.guiWidth() / 2, gg.guiHeight() / 2 - 4,
                    withAlpha(TEXT_COLOR, text), 1.6f, 6);
        }
    }

    /** {@code -1} 表示尚未落地;超过 {@link #MAX_WAIT_FOR_LANDING_MS} 未落地则视为在兜底时刻自动落地。 */
    private static long effectiveSinceLandedMs(long now) {
        if (landedAtMs >= 0L) {
            return now - landedAtMs;
        }
        long sinceTrigger = now - triggeredAtMs;
        return sinceTrigger >= MAX_WAIT_FOR_LANDING_MS ? sinceTrigger - MAX_WAIT_FOR_LANDING_MS : -1L;
    }

    // ===== 纯函数(不读时钟,供单测直接覆盖):§3.7 时间轴换算 =====

    /** 白闪 alpha(0~0.95)。{@code sinceLandedMs<0} 表示尚未落地,只按淡入曲线走。 */
    static float flashAlpha(long sinceTriggerMs, long sinceLandedMs) {
        return fadeCombine(sinceTriggerMs, sinceLandedMs, DeployConfirmFx::rawFlashAlpha);
    }

    /** "正在部署"文字 alpha(0~1)。规则与 {@link #flashAlpha} 同构,延迟到白闪几近满值才起。 */
    static float textAlpha(long sinceTriggerMs, long sinceLandedMs) {
        return fadeCombine(sinceTriggerMs, sinceLandedMs, DeployConfirmFx::rawTextAlpha);
    }

    /** 淡入曲线(未落地)与落地淡出叠加的共享计算:落地后从"落地那一刻的淡入值"平滑退场,不跳变。 */
    private static float fadeCombine(long sinceTriggerMs, long sinceLandedMs, java.util.function.LongToDoubleFunction rawFn) {
        if (sinceLandedMs < 0L) {
            return (float) rawFn.applyAsDouble(sinceTriggerMs);
        }
        if (sinceLandedMs >= FADE_OUT_MS) {
            return 0f;
        }
        float alphaAtLanding = (float) rawFn.applyAsDouble(sinceTriggerMs - sinceLandedMs);
        float outT = Tween.Ease.IN_CUBIC.apply(sinceLandedMs / (float) FADE_OUT_MS);
        return alphaAtLanding * (1f - outT);
    }

    private static double rawFlashAlpha(long elapsedMs) {
        if (elapsedMs < FLASH_IN_DELAY_MS) {
            return 0d;
        }
        float t = Math.min(1f, (elapsedMs - FLASH_IN_DELAY_MS) / (float) FLASH_IN_DURATION_MS);
        return Tween.Ease.OUT_CUBIC.apply(t) * FLASH_MAX_ALPHA;
    }

    private static double rawTextAlpha(long elapsedMs) {
        if (elapsedMs < TEXT_DELAY_MS) {
            return 0d;
        }
        float t = Math.min(1f, (elapsedMs - TEXT_DELAY_MS) / (float) TEXT_FADE_IN_MS);
        return Tween.Ease.OUT_CUBIC.apply(t);
    }

    /** 手动加宽字距居中绘制(规格文档"字距10"的视觉意图),{@code y} 为绘制基线(未叠加 scale)。 */
    private static void drawSpacedCentered(GuiGraphics gg, Font font, String text, int centerX, int y,
                                            int color, float scale, int spacingPx) {
        float totalW = 0f;
        for (int i = 0; i < text.length(); i++) {
            totalW += font.width(String.valueOf(text.charAt(i))) * scale;
            if (i < text.length() - 1) {
                totalW += spacingPx * scale;
            }
        }
        gg.pose().pushPose();
        gg.pose().translate(centerX - totalW / 2f, y, 0);
        gg.pose().scale(scale, scale, 1f);
        float cx = 0f;
        for (int i = 0; i < text.length(); i++) {
            String s = String.valueOf(text.charAt(i));
            gg.drawString(font, s, Math.round(cx), 0, color, false);
            cx += font.width(s) + spacingPx;
        }
        gg.pose().popPose();
    }

    private static int withAlpha(int argb, float alphaMul) {
        int baseA = (argb >>> 24) & 0xFF;
        int a = Math.round(baseA * Mth.clamp(alphaMul, 0f, 1f));
        return (argb & 0x00FFFFFF) | (a << 24);
    }
}
