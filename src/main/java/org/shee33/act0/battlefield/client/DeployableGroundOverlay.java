package org.shee33.act0.battlefield.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.shee33.act0.battlefield.Act0Battlefield;
import org.shee33.act0.battlefield.core.SupplyRules;
import org.shee33.act0.battlefield.deployable.DeployableKind;
import org.shee33.act0.battlefield.network.DeployableDto;

import java.util.List;

/**
 * 部署型补给物的地面范围圆：在每个已部署弹药箱 / 医疗箱脚下画一个半透明平面圆盘。
 *
 * <p>这个圆<b>不是装饰</b>，它就是 {@link SupplyRules#RADIUS} 的真实判定边界——站进去就补给，
 * 站出去什么都没有。因此一切设计决策都服从"边界可读"这一个目标：
 * <ul>
 *   <li>圆的外沿严格等于 {@code RADIUS}，不做任何美观性的放大缩小；</li>
 *   <li>内部填充刻意压到很低的不透明度（约 0.21），不能糊住脚下的地形和敌人；</li>
 *   <li>但边缘单独画一条更实的窄环（约 0.52），否则低不透明度的圆在杂色地表上根本找不到边；</li>
 *   <li>除到期淡出以外<b>没有任何动效</b>。呼吸 / 缩放 / 旋转会让玩家无法判断自己是否踩线，
 *       对一个"精确范围指示器"来说那是功能性缺陷，不是风格问题。</li>
 * </ul>
 *
 * <p>数据取自 {@link ClientDeployables} 的快照（由 {@code SyncDeployablesPacket} 以 HUD 频率
 * 约每 10 tick 推送）。服务端只把补给物下发给同阵营玩家，所以这里不需要再做可见性过滤。
 *
 * <p>渲染走 {@link RenderLevelStageEvent}，与 {@link BattlefieldWorldPointOverlay} 同一套路：
 * 同一个 {@code AFTER_PARTICLES} 阶段、同样的"相机相对平移"以规避大坐标下的 float 精度损失。
 */
@Mod.EventBusSubscriber(modid = Act0Battlefield.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class DeployableGroundOverlay {

    /** 圆周分段数。r=3 时 64 段的边长约 0.29 格，肉眼已看不出多边形棱角。 */
    private static final int SEGMENTS = 64;

    /** 边缘窄环宽度（格）：向内收，外沿保持在 {@code RADIUS} 上，不把生效范围画大。 */
    private static final double RIM_WIDTH = 0.12D;

    /** 离地抬升（格）：躲开与地表方块共面导致的 z-fighting 闪烁。 */
    private static final double GROUND_LIFT = 0.02D;

    /** 到期淡出时长（tick）：最后 2 秒开始变淡，给玩家"该换个补给点了"的预告。 */
    private static final int FADE_TICKS = 40;

    /** 填充不透明度（0-255）。约 0.21——够看见范围，又不遮挡脚下战场信息。 */
    private static final int FILL_ALPHA = 54;

    /** 边缘不透明度（0-255）。约 0.52——只比填充实一档，用于勾出边界而非抢注意力。 */
    private static final int RIM_ALPHA = 132;

    /** 弹药箱：淡琥珀橙。与部署界面战斗区域地面线同色系，保持模组内的"边界"视觉一致。 */
    private static final int AMMO_RGB = 0xE8C36A;

    /** 医疗箱：淡绿。取现有强调绿的低饱和版本，避免在草地上过于跳脱。 */
    private static final int MEDIC_RGB = 0x8FD9A0;

    /** 渲染距离上限（格）。超出这个距离圆只剩几个像素，画它只是白烧顶点。 */
    private static final double MAX_RENDER_DISTANCE = 96.0D;

    private static final double MILLIS_PER_TICK = 50.0D;

    /**
     * 上一次观察到的快照引用与其首次出现的时刻，仅用于把淡出补平。
     *
     * <p>{@code remainingTicks} 每约 10 tick 才刷新一次，直接拿它算 alpha 会让 40 tick 的淡出
     * 变成 4 级台阶式跳变——那看起来像掉帧而不是淡出。这里用"快照到手后又过了多久"把中间补上。
     * 两个字段都不带初始化器（默认 null / 0），因此本类的静态初始化里没有任何可执行代码——
     * {@code @Mod.EventBusSubscriber} 类会在 mod CONSTRUCT 阶段就被加载，那时候几乎什么都还没
     * 就绪，在这里做任何解析都可能拿到 null 并让客户端直接起不来（见 EventSubscriberStaticInitTest）。
     */
    private static List<DeployableDto> lastSnapshot;
    private static long lastSnapshotMillis;

    private DeployableGroundOverlay() {
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        List<DeployableDto> deployables = ClientDeployables.get();
        if (deployables.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) {
            return;
        }

        double sinceSnapshot = trackSnapshot(deployables);
        Vec3 cam = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();
        VertexConsumer consumer = buffer.getBuffer(GroundDisc.TYPE);

        for (DeployableDto deployable : deployables) {
            double dx = deployable.x() - cam.x;
            double dy = deployable.y() - cam.y;
            double dz = deployable.z() - cam.z;
            if (dx * dx + dy * dy + dz * dz > MAX_RENDER_DISTANCE * MAX_RENDER_DISTANCE) {
                continue;
            }
            float fade = fadeFactor(deployable.remainingTicks(), sinceSnapshot);
            if (fade <= 0.0F) {
                continue;
            }
            int rgb = DeployableKind.byId(deployable.kind()) == DeployableKind.MEDIC ? MEDIC_RGB : AMMO_RGB;

            // 逐个补给物把原点搬到它脚下，圆盘顶点用局部坐标发出：远离出生点几万格时，
            // 直接送世界坐标会因 float 精度不足让圆抖动。
            pose.pushPose();
            pose.translate(dx, dy + GROUND_LIFT, dz);
            drawDisc(consumer, pose.last().pose(), rgb, fade);
            pose.popPose();
        }
        buffer.endBatch(GroundDisc.TYPE);
    }

    /**
     * 画一个圆盘：内部填充 + 外沿窄环，两者不重叠。
     *
     * <p>刻意不让环压在填充上：半透明叠加会得到第三种不可控的亮度，而扁平化视觉要求每一块色域
     * 的不透明度都是设计定死的值。
     */
    private static void drawDisc(VertexConsumer consumer, Matrix4f matrix, int rgb, float fade) {
        double outer = SupplyRules.RADIUS;
        double inner = Math.max(0.0D, outer - RIM_WIDTH);
        int fillAlpha = scaledAlpha(FILL_ALPHA, fade);
        int rimAlpha = scaledAlpha(RIM_ALPHA, fade);
        double step = (Math.PI * 2.0D) / SEGMENTS;

        for (int i = 0; i < SEGMENTS; i++) {
            double a0 = i * step;
            double a1 = (i + 1) * step;
            double c0 = Math.cos(a0);
            double s0 = Math.sin(a0);
            double c1 = Math.cos(a1);
            double s1 = Math.sin(a1);

            // 填充：以圆心为顶点的三角扇（用 TRIANGLES 逐个发，避免依赖图元的扇形状态）。
            if (inner > 0.0D) {
                vertex(consumer, matrix, 0.0D, 0.0D, rgb, fillAlpha);
                vertex(consumer, matrix, c0 * inner, s0 * inner, rgb, fillAlpha);
                vertex(consumer, matrix, c1 * inner, s1 * inner, rgb, fillAlpha);
            }

            // 外沿窄环：每段两个三角形拼成的梯形。
            vertex(consumer, matrix, c0 * inner, s0 * inner, rgb, rimAlpha);
            vertex(consumer, matrix, c0 * outer, s0 * outer, rgb, rimAlpha);
            vertex(consumer, matrix, c1 * outer, s1 * outer, rgb, rimAlpha);
            vertex(consumer, matrix, c0 * inner, s0 * inner, rgb, rimAlpha);
            vertex(consumer, matrix, c1 * outer, s1 * outer, rgb, rimAlpha);
            vertex(consumer, matrix, c1 * inner, s1 * inner, rgb, rimAlpha);
        }
    }

    /** 圆盘是水平面，所以只有 x/z 两个自由度，y 恒为 0（抬升已经做在 PoseStack 上）。 */
    private static void vertex(VertexConsumer consumer, Matrix4f matrix, double x, double z, int rgb, int alpha) {
        consumer.vertex(matrix, (float) x, 0.0F, (float) z)
                .color((rgb >>> 16) & 0xFF, (rgb >>> 8) & 0xFF, rgb & 0xFF, alpha)
                .endVertex();
    }

    private static int scaledAlpha(int base, float fade) {
        int alpha = Math.round(base * fade);
        return Math.max(0, Math.min(255, alpha));
    }

    /**
     * 剩余不足 {@link #FADE_TICKS} 时线性淡出，其余时间保持满不透明度。
     *
     * @param sinceSnapshotTicks 快照到手后经过的 tick 数，用于把服务端的粗粒度倒计时补平
     */
    private static float fadeFactor(int remainingTicks, double sinceSnapshotTicks) {
        double left = remainingTicks - sinceSnapshotTicks;
        if (left <= 0.0D) {
            return 0.0F;
        }
        return (float) Math.min(1.0D, left / FADE_TICKS);
    }

    /**
     * 记录快照的到手时刻并返回其已存在的 tick 数。
     *
     * <p>用引用比较判断"是不是新包"：{@code ClientDeployables#accept} 每次都 {@code List.copyOf}
     * 出一个新实例，所以引用变了就等于收到了新的一份倒计时。用挂钟时间而非 tick 计数，是因为
     * 渲染发生在 tick 之间，拿 tick 计数会得到同样的台阶。
     */
    private static double trackSnapshot(List<DeployableDto> snapshot) {
        long now = System.currentTimeMillis();
        if (snapshot != lastSnapshot) {
            lastSnapshot = snapshot;
            lastSnapshotMillis = now;
            return 0.0D;
        }
        return Math.max(0.0D, (now - lastSnapshotMillis) / MILLIS_PER_TICK);
    }

    /**
     * 圆盘专用的半透明 {@link RenderType} 持有者。
     *
     * <p>为什么要单独开一个嵌套类：
     * <ul>
     *   <li>{@code RenderStateShard} 里的状态常量是 {@code protected}，只有 {@link RenderType}
     *       的子类能取到，所以必须 extends；</li>
     *   <li>更关键的是，{@code RenderType.create} 是<b>可执行</b>的静态初始化。放在外层那个带
     *       {@code @Mod.EventBusSubscriber} 的类里，它会在 mod CONSTRUCT 阶段被迫求值；放在这个
     *       嵌套类里，JVM 的惰性类初始化保证它推迟到第一次真正渲染时才跑。</li>
     * </ul>
     *
     * <p>状态选择：深度测试开 + 深度写关。开测试，圆才会被前方的墙体、山体正确遮挡，玩家看到的
     * 才是"绕过墙走进去"而不是透视；关写入，同一个圆的填充与外环、以及多个重叠的圆之间就不会
     * 互相抢深度导致边缘撕裂。关闭背面剔除，从箱子下方（地下室、坡底）抬头也能看到范围。
     * 不加 {@code VIEW_OFFSET_Z_LAYERING}：那会把几何体朝相机整体缩放一点，虽然能防 z-fighting，
     * 但也让半径不再是精确的 3.0；离地 {@value DeployableGroundOverlay#GROUND_LIFT} 格已经够用，
     * 精确性优先。
     */
    private static final class GroundDisc extends RenderType {

        /** 一个圆盘的顶点上限 ×16 字节（POSITION_COLOR 每顶点 16 字节），减少缓冲扩容。 */
        private static final int BUFFER_BYTES = SEGMENTS * 9 * 16;

        private static final RenderType TYPE = RenderType.create(
                Act0Battlefield.MODID + ":deployable_ground_disc",
                DefaultVertexFormat.POSITION_COLOR,
                VertexFormat.Mode.TRIANGLES,
                BUFFER_BYTES,
                false,
                false,
                RenderType.CompositeState.builder()
                        .setShaderState(POSITION_COLOR_SHADER)
                        .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                        .setDepthTestState(LEQUAL_DEPTH_TEST)
                        .setWriteMaskState(COLOR_WRITE)
                        .setCullState(NO_CULL)
                        .createCompositeState(false));

        private GroundDisc() {
            super("", DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.TRIANGLES, 0, false, false,
                    () -> {
                    },
                    () -> {
                    });
            // 本类只为借到 RenderStateShard 的 protected 常量而继承 RenderType，从不实例化。
            throw new AssertionError();
        }
    }
}
