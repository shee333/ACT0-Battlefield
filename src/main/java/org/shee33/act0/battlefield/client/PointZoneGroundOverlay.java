package org.shee33.act0.battlefield.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
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
import org.shee33.act0.battlefield.core.Polygon2D;
import org.shee33.act0.battlefield.network.BattleHudDto;
import org.shee33.act0.battlefield.network.ControlPointHudDto;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 第一人称下的据点地面边界高亮（部署期的多边形边界在实战中的延续）。
 *
 * <p><b>近距聚焦</b>：只在玩家逼近某一个据点时，为<b>那一个</b>据点画出地面边界——走近淡入、离开淡出。
 * 不做"全场据点常驻显示"：第一人称下多个多边形同时糊在地面上既噪音大又基本看不见（FP 视角看地面的
 * 面积很小，且填充被地形遮挡），而"我正要进哪个圈"才是真需要边界感的时刻。这与 {@code CaptureFocusAnimator}
 * "每个客户端只关心自己所在的那一个据点"的哲学一致，也符合"减法优先"。
 *
 * <p><b>视觉语言与部署期同源</b>：同款淡填充 + 锐利描边 + 同款阵营色，保证"部署时看到的圈"和
 * "打起来看到的圈"是同一个东西。填充仍走 {@code BattlefieldDeployWorldOverlay.PointZoneFill} 的
 * RenderType（深度测试开→被地形正确遮挡，深度写关→不挡后面的东西），因此不穿墙。
 *
 * <p><b>顶点高度贴合</b>：边界顶点自带 Y（服务端下发世界坐标），三角面按顶点高度倾斜，因此边界能贴合
 * 地形起伏，而不是浮在一个水平面上。顶点之间的大坑仍会穿插（三角面是线性插值），可接受。
 *
 * <p><b>平视可见性</b>：地面填充在平视时几乎不可见，因此在每个顶点画一根低位短竖线（不是立柱/幕布——
 * 那会遮挡战斗视野），让玩家平视也能定位边界走向。
 */
@Mod.EventBusSubscriber(modid = Act0Battlefield.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class PointZoneGroundOverlay {

    /** 与部署期同源的阵营色（同一个圈在两处必须看起来是一回事）。 */
    private static final int BLUE = 0x57C7FF;
    private static final int RED = 0xE7654E;
    private static final int GREY = 0x9EA7AA;
    /** 正在被占领/中和时环与文字转黄，与 {@code DocPalette.PROGRESS} 同值同语义。 */
    private static final int CONTEST = 0xFFD76A;

    private static final int FILL_ALPHA = 46;
    private static final int RIM_ALPHA = 210;
    /** 顶点竖线：明显比描边淡，避免"一圈栏杆"的观感。 */
    private static final int TICK_ALPHA = 110;

    /** 近距聚焦半径（水平，到多边形包围盒的间距）；0 表示玩家已在包围盒内。 */
    private static final double FOCUS_RANGE = 40.0D;
    /** 抬升，防与地面 z-fighting（与部署期同量级）。 */
    private static final float GROUND_LIFT = 0.05f;
    /** 顶点低位竖线高度（格）；刻意低，只为平视时能看出边界在哪。 */
    private static final float TICK_HEIGHT = 0.8f;

    private static final float FADE_IN_MS = 220f;
    private static final float FADE_OUT_MS = 300f;
    /** 争夺呼吸周期（毫秒）：只做透明度，不旋转不缩放。 */
    private static final long CONTEST_PERIOD_MS = 1200L;

    /** 当前应显示的据点（-1 = 不显示）。 */
    private static int focusId = -1;
    private static float alpha;
    private static long transitionStartMs;
    private static float transitionFrom;

    /** 三角化缓存：按据点缓存，仅在收到新的 HUD 快照（DTO 实例变化）时重切。 */
    private static final Map<Integer, Cached> CACHE = new HashMap<>();

    private PointZoneGroundOverlay() {
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) {
            return;
        }
        long now = System.currentTimeMillis();
        BattleHudDto hud = ClientBattleHud.hud();
        if (!ClientPointZoneHud.isEnabled() || !ClientBattleHud.isShown() || hud == null || hud.points().isEmpty()) {
            focusId = -1;
            updateAlpha(now);
            return;
        }

        double px = mc.player.getX();
        double pz = mc.player.getZ();
        ControlPointHudDto focused = null;
        double best = Double.MAX_VALUE;
        for (ControlPointHudDto point : hud.points()) {
            double gap = gapToZone(point, px, pz);
            if (gap <= FOCUS_RANGE && gap < best) {
                best = gap;
                focused = point;
            }
        }

        focusId = focused != null ? focused.pointId() : -1;
        float shown = updateAlpha(now);
        if (focused == null || shown <= 0.01f) {
            return;
        }
        Cached cached = cached(focused);
        if (cached == null) {
            return;
        }

        int rgb = zoneColor(focused, hud.myFaction());
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();
        draw(pose, buffer, event.getCamera(), focused, cached, rgb, shown, now);
        buffer.endBatch();
    }

    /** 玩家到该据点多边形包围盒的水平间距；未圈画边界（<3 顶点）返回 {@code MAX_VALUE}（不画）。 */
    private static double gapToZone(ControlPointHudDto point, double px, double pz) {
        List<double[]> boundary = point.boundary();
        if (boundary.size() < 3) {
            return Double.MAX_VALUE;
        }
        double minX = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE;
        double maxZ = -Double.MAX_VALUE;
        for (double[] vertex : boundary) {
            minX = Math.min(minX, vertex[0]);
            maxX = Math.max(maxX, vertex[0]);
            minZ = Math.min(minZ, vertex[2]);
            maxZ = Math.max(maxZ, vertex[2]);
        }
        double dx = Math.max(0.0, Math.max(minX - px, px - maxX));
        double dz = Math.max(0.0, Math.max(minZ - pz, pz - maxZ));
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** 每帧推进淡入淡出；返回当前可见度 [0,1]。焦点切换时从当前值平滑过渡，不做硬切。 */
    private static float updateAlpha(long now) {
        float target = focusId >= 0 ? 1f : 0f;
        float current = alpha;
        if (Math.abs(target - current) > 0.0001f && transitionStartMs == 0L) {
            transitionFrom = current;
            transitionStartMs = now;
        }
        if (transitionStartMs == 0L) {
            alpha = target;
            return alpha;
        }
        boolean fadingIn = target > transitionFrom;
        float duration = fadingIn ? FADE_IN_MS : FADE_OUT_MS;
        float t = Math.min(1f, (now - transitionStartMs) / duration);
        float eased = 1f - (float) Math.pow(1f - t, 3f);
        alpha = transitionFrom + (target - transitionFrom) * eased;
        if (t >= 1f) {
            alpha = target;
            transitionStartMs = 0L;
            transitionFrom = target;
        }
        return alpha;
    }

    private static Cached cached(ControlPointHudDto point) {
        Cached existing = CACHE.get(point.pointId());
        if (existing != null && existing.source() == point) {
            return existing;
        }
        List<double[]> xz = new ArrayList<>(point.boundary().size());
        for (double[] vertex : point.boundary()) {
            xz.add(new double[]{vertex[0], vertex[2]});
        }
        Polygon2D polygon = Polygon2D.of(xz);
        if (polygon == null) {
            return null;
        }
        Cached fresh = new Cached(point, polygon.triangulate());
        CACHE.put(point.pointId(), fresh);
        return fresh;
    }

    private static int zoneColor(ControlPointHudDto point, int myFaction) {
        if (point.pressure() != 0) {
            return CONTEST;
        }
        return factionColor(point.owner(), myFaction);
    }

    private static int factionColor(int faction, int mine) {
        if (faction == 0) {
            return GREY;
        }
        if (mine != 0) {
            return faction == mine ? BLUE : RED;
        }
        return faction == 1 ? BLUE : RED;
    }

    private static void draw(PoseStack pose, MultiBufferSource.BufferSource buffer, Camera camera,
                             ControlPointHudDto point, Cached cached, int rgb, float visibility, long now) {
        Vec3 cam = camera.getPosition();
        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);
        Matrix4f matrix = pose.last().pose();
        pose.popPose();

        int cr = (rgb >> 16) & 0xFF;
        int cg = (rgb >> 8) & 0xFF;
        int cb = rgb & 0xFF;
        int fillAlpha = Math.round(FILL_ALPHA * visibility);
        int rimAlpha = Math.round(rimAlpha(point, now) * visibility);
        int tickAlpha = Math.round(TICK_ALPHA * visibility);

        List<double[]> boundary = point.boundary();
        VertexConsumer fill = buffer.getBuffer(BattlefieldDeployWorldOverlay.PointZoneFill.TYPE);
        for (int index : cached.triangles()) {
            double[] vertex = boundary.get(index);
            fill.vertex(matrix, (float) vertex[0], (float) vertex[1] + GROUND_LIFT, (float) vertex[2])
                    .color(cr, cg, cb, fillAlpha).endVertex();
        }

        VertexConsumer line = buffer.getBuffer(RenderType.LINES);
        int n = boundary.size();
        for (int i = 0; i < n; i++) {
            double[] a = boundary.get(i);
            double[] b = boundary.get((i + 1) % n);
            drawLine(line, matrix, a[0], a[1] + GROUND_LIFT, a[2], b[0], b[1] + GROUND_LIFT, b[2], cr, cg, cb, rimAlpha);
            drawLine(line, matrix, a[0], a[1] + GROUND_LIFT, a[2],
                    a[0], a[1] + GROUND_LIFT + TICK_HEIGHT, a[2], cr, cg, cb, tickAlpha);
        }
    }

    /**
     * 描边透明度：正在被占领/中和（{@code pressure != 0}）时做 {@link #CONTEST_PERIOD_MS} 周期的
     * 透明度呼吸，强化"这里正在发生变化"。
     *
     * <p>说明：真正的"双方同场争夺"判定只在玩家身处据点内时由 {@code focusState} 给出，覆盖不到
     * "走近但没进圈"的情形；这里用"有推进方向"作为客户端可见的等价信号。
     */
    private static float rimAlpha(ControlPointHudDto point, long now) {
        if (point.pressure() == 0) {
            return RIM_ALPHA;
        }
        float phase = (now % CONTEST_PERIOD_MS) / (float) CONTEST_PERIOD_MS;
        return RIM_ALPHA * (0.7f + 0.3f * (float) Math.sin(phase * Math.PI * 2.0));
    }

    private static void drawLine(VertexConsumer consumer, Matrix4f matrix,
                                 double x1, double y1, double z1, double x2, double y2, double z2,
                                 int r, int g, int b, int a) {
        // RenderType.LINES 用 POSITION_COLOR_NORMAL：两个顶点都必须补 normal，否则严格顶点校验器会崩。
        consumer.vertex(matrix, (float) x1, (float) y1, (float) z1).color(r, g, b, a).normal(0f, 1f, 0f).endVertex();
        consumer.vertex(matrix, (float) x2, (float) y2, (float) z2).color(r, g, b, a).normal(0f, 1f, 0f).endVertex();
    }

    /** 断线兜底：清掉焦点与缓存，防止下一局残留一个幽灵边界。 */
    static void reset() {
        focusId = -1;
        alpha = 0f;
        transitionFrom = 0f;
        transitionStartMs = 0L;
        CACHE.clear();
    }

    private record Cached(ControlPointHudDto source, int[] triangles) {
    }
}
