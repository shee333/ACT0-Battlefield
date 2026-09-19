package org.shee33.act0.battlefield.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EffectInstance;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.shee33.act0.battlefield.Act0Battlefield;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;

/**
 * 离开作战区域时的画面去饱和（黑白）后处理。强度由 {@link ClientOutOfBounds} 的倒计时驱动。
 *
 * <p>与 {@code HudBlurEffect} 同一套骨架与纪律：在 {@link RenderGuiEvent.Pre} 处理主缓冲——此刻
 * 世界已渲染完、HUD 尚未绘制，因此<b>只有战场画面变灰、HUD 保持彩色</b>。这是刻意的：HUD 是玩家
 * 赖以作战的信息层（血量/弹药/据点/倒计时），氛围效果不该牺牲它的可读性。
 *
 * <p>后处理链是自定义资源（{@code shaders/post/grayscale.json}，内含一份内联 blit 用于写回主缓冲），
 * 通过 {@code Intensity} uniform 控制灰度深浅。{@code PostChain#passes} 在 1.20.1 是 private 且没有
 * 公开的遍历入口，因此用反射取该列表，再走公开的 {@code PostPass#getEffect()/EffectInstance#getUniform}
 * 设置 uniform（{@code PostPass#process} 每帧都会调用 {@code EffectInstance#apply()}，改动即时生效）。
 *
 * <p><b>任何一步失败都永久降级为不变灰</b>：后处理涉及帧缓冲与着色器编译，出问题的后果是整屏花屏或
 * 消失。一个氛围效果绝不该有能力拖垮战斗界面——宁可没有灰度。
 */
@Mod.EventBusSubscriber(modid = Act0Battlefield.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class OutOfBoundsGrayscale {

    /** 低于此强度完全不建链、不处理，避免常态下白白多花一次全屏后处理。 */
    private static final float MIN_INTENSITY = 0.004f;

    private static PostChain chain;
    private static int lastWidth;
    private static int lastHeight;
    private static boolean unavailable;
    /** 缓存的私有字段句柄；惰性初始化，避免每帧重复查找。 */
    private static Field passesField;
    /** 是否已尝试定位过字段（失败也记为已解析，避免每帧重复扫描）。 */
    private static boolean passesFieldResolved;

    private OutOfBoundsGrayscale() {
    }

    @SubscribeEvent
    public static void onRenderGuiPre(RenderGuiEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) {
            return;
        }
        float intensity = ClientOutOfBounds.intensity();
        if (intensity < MIN_INTENSITY) {
            return;
        }
        apply(mc, event.getPartialTick(), intensity);
    }

    private static void apply(Minecraft mc, float partialTick, float intensity) {
        if (unavailable) {
            return;
        }
        try {
            RenderTarget target = mc.getMainRenderTarget();
            if (chain == null || target.width != lastWidth || target.height != lastHeight) {
                rebuild(mc, target);
            }
            if (chain == null) {
                return;
            }
            setIntensity(intensity);
            // 与原版 GameRenderer 应用后处理时的状态序列一致：先关混合与深度、重置纹理矩阵，
            // 处理完再把主缓冲重新绑为写入目标，否则后续 HUD 会画进后处理的中间缓冲里。
            RenderSystem.disableBlend();
            RenderSystem.disableDepthTest();
            RenderSystem.resetTextureMatrix();
            chain.process(partialTick);
            mc.getMainRenderTarget().bindWrite(true);
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
        } catch (Throwable t) {
            unavailable = true;
            dispose();
            mc.getMainRenderTarget().bindWrite(true);
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
        }
    }

    /**
     * 把灰度深浅写进链里所有声明了 {@code Intensity} 的 pass。
     *
     * <p>{@code PostChain#passes} 是 private，这里反射取列表；取不到就抛异常给上层降级，
     * 不影响其它渲染路径。
     */
    private static void setIntensity(float intensity) throws ReflectiveOperationException {
        List<PostPass> passes = passes();
        if (passes == null) {
            return;
        }
        for (PostPass pass : passes) {
            EffectInstance effect = pass.getEffect();
            if (effect == null) {
                continue;
            }
            Uniform uniform = effect.getUniform("Intensity");
            if (uniform != null) {
                uniform.set(intensity);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<PostPass> passes() throws ReflectiveOperationException {
        if (!passesFieldResolved) {
            passesFieldResolved = true;
            passesField = findPassesField();
        }
        if (passesField == null) {
            return null;
        }
        return (List<PostPass>) passesField.get(chain);
    }

    /**
     * 按"元素类型是 {@link PostPass} 的 {@code List}"定位那个私有字段，<b>而不是按名字</b>。
     *
     * <p>生产环境里 MC 的字段名是 SRG（{@code f_xxxxx_}），按名字取会在正式包里失效（只在开发环境
     * 的官方映射下侥幸成功）；泛型签名在混淆/SRG 下依然保留，所以按类型定位是映射无关的。
     */
    private static Field findPassesField() {
        for (Field field : PostChain.class.getDeclaredFields()) {
            if (!List.class.isAssignableFrom(field.getType())) {
                continue;
            }
            Type generic = field.getGenericType();
            if (!(generic instanceof ParameterizedType parameterized)) {
                continue;
            }
            Type[] args = parameterized.getActualTypeArguments();
            if (args.length == 1 && args[0] == PostPass.class) {
                field.setAccessible(true);
                return field;
            }
        }
        return null;
    }

    private static void rebuild(Minecraft mc, RenderTarget target) throws Exception {
        dispose();
        chain = new PostChain(mc.getTextureManager(), mc.getResourceManager(), target,
                new ResourceLocation(Act0Battlefield.MODID, "shaders/post/grayscale.json"));
        chain.resize(target.width, target.height);
        lastWidth = target.width;
        lastHeight = target.height;
    }

    private static void dispose() {
        if (chain != null) {
            try {
                chain.close();
            } catch (Throwable ignored) {
                // 关闭失败没有补救手段，丢弃引用即可，不能让它阻断降级路径。
            }
            chain = null;
        }
    }
}