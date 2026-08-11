package org.shee33.act0.battlefield.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.shee33.act0.battlefield.Act0Battlefield;

/**
 * 呼出 TAB 时对背景做高斯模糊。
 *
 * <p>直接复用原版的 {@code shaders/post/blur.json}——它本身就是一条标准的两趟可分离高斯
 * （先横后纵），且最终写回 {@code minecraft:main}，正好是"把已经画完的世界就地模糊掉"。
 * 用原版资源意味着零新增着色器文件，也不存在自写着色器编译不过的风险。
 *
 * <p>时机选在 {@link RenderGuiEvent.Pre}：此刻世界已经渲染进主缓冲，而所有 HUD 尚未绘制，
 * 因此模糊只作用于背景，HUD 与 TAB 面板保持锐利。
 *
 * <p><b>任何一步失败都永久降级为不模糊</b>。后处理链涉及帧缓冲与着色器状态，出问题的后果是
 * 整个 HUD 花屏或消失；一个锦上添花的背景效果绝不该有能力拖垮战斗界面，所以这里宁可不模糊。
 */
@Mod.EventBusSubscriber(modid = Act0Battlefield.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class HudBlurEffect {

    private static PostChain chain;
    private static int lastWidth;
    private static int lastHeight;
    private static boolean unavailable;

    private HudBlurEffect() {
    }

    @SubscribeEvent
    public static void onRenderGuiPre(RenderGuiEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        boolean held = mc.player != null && !mc.options.hideGui
                && ClientBattleTab.isShown() && ClientBattleTab.tab() != null
                && mc.options.keyPlayerList.isDown();
        ClientTabFocus.update(held);
        if (ClientTabFocus.dim() > 0.02f) {
            apply(mc, event.getPartialTick());
        }
    }

    private static void apply(Minecraft mc, float partialTick) {
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

    private static void rebuild(Minecraft mc, RenderTarget target) throws Exception {
        dispose();
        chain = new PostChain(mc.getTextureManager(), mc.getResourceManager(), target,
                new ResourceLocation("minecraft", "shaders/post/blur.json"));
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
