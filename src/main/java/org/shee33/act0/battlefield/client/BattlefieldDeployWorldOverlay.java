package org.shee33.act0.battlefield.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.shee33.act0.battlefield.Act0Battlefield;
import org.shee33.act0.battlefield.core.Polygon2D;
import org.shee33.act0.battlefield.network.DeployActionPacket;
import org.shee33.act0.battlefield.network.DeployPointDto;
import org.shee33.act0.battlefield.network.DeploySquadMateDto;
import org.shee33.act0.battlefield.network.DeployStatusDto;

import java.util.ArrayList;
import java.util.List;

/** 部署阶段世界空间标记：把基地/小队/据点直接贴到真实战场上，并提供屏幕点击命中。 */
@Mod.EventBusSubscriber(modid = Act0Battlefield.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class BattlefieldDeployWorldOverlay {
    private static final int BLUE = 0xFF57C7FF;
    private static final int RED = 0xFFE7654E;
    private static final int GREY = 0xFF9EA7AA;
    private static final int GREEN = 0xFF9DFF9D;
    private static final int WHITE = 0xFFE8F4F8;
    private static final int LIGHT = 0xF000F0;
    private static final int AREA_FLOOR_RGB = 0xFFE8C36A;
    private static final int AREA_WALL_RGB = 0xCCB6E3FF;

    /** 据点不规则区域：地面半透明填充 alpha 与边框 alpha（0-255）。 */
    private static final int ZONE_FILL_ALPHA = 46;
    private static final int ZONE_RIM_ALPHA = 210;
    /** 区域贴地抬升，避免与地面 z-fighting。 */
    private static final double ZONE_GROUND_LIFT = 0.03D;

    private static final ResourceLocation POINT_FRIENDLY = texture("capturepoint/allies.png");
    private static final ResourceLocation POINT_ENEMY = texture("capturepoint/axis.png");
    private static final ResourceLocation POINT_NEUTRAL = texture("misc/capturepoint.png");

    /** 超过此距离的标记既不绘制也不可点击——两者必须同一个判据，否则会出现看不见却能点中的目标。 */
    private static final double MARKER_CULL_DISTANCE = 600.0D;

    /**
     * 标记在 billboard 局部坐标里的视觉中心 y。
     *
     * <p>{@code renderMarker} 把图标画在局部 y 的 −32…−16、主标签画在 −16，而 {@code pose.scale} 的 y 分量
     * 为负，因此这些负值在屏幕上位于锚点<b>上方</b>。点击热区若直接投影锚点，就会落在看得见的图标下方约
     * 一个图标的距离——玩家瞄着图标点，判定却在图标脚下。取图标中心（无图标时取主标签中心）作为热区锚点。
     */
    private static final float MARKER_ICON_LOCAL_Y = -24.0F;
    private static final float MARKER_LABEL_LOCAL_Y = -12.0F;

    private static final List<DeployClickTarget> TARGETS = new ArrayList<>();
    private static Matrix4f projectionMatrix;
    private static Vec3 cameraPos = Vec3.ZERO;
    private static Vec3 cameraLook = new Vec3(0, 0, 1);
    private static Vec3 cameraRight = new Vec3(1, 0, 0);
    private static Vec3 cameraUp = new Vec3(0, 1, 0);
    private static DeployClickTarget hoveredTarget;

    private BattlefieldDeployWorldOverlay() {
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        DeployStatusDto status = ClientDeployStatus.status();
        if (status == null || !status.active()) {
            TARGETS.clear();
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) {
            TARGETS.clear();
            return;
        }
        captureProjection(event);
        TARGETS.clear();

        PoseStack pose = event.getPoseStack();
        Camera camera = event.getCamera();
        Font font = mc.font;
        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();

        if (status.canBase()) {
            addAndRender(pose, buffer, font, camera, status,
                    status.baseX(), status.baseY(), status.baseZ(), "H", "基地", true, BLUE,
                    DeployActionPacket.DeployKind.BASE, "", null);
        }
        if (status.canSquad() && status.squadMates().isEmpty()) {
            addAndRender(pose, buffer, font, camera, status,
                    status.squadX(), status.squadY(), status.squadZ(), "S", "小队", true, BLUE,
                    DeployActionPacket.DeployKind.SQUAD, "", null);
        }
        for (DeploySquadMateDto mate : status.squadMates()) {
            addAndRender(pose, buffer, font, camera, status,
                    mate.x(), mate.y(), mate.z(), "◆", mate.name(), mate.deployable(), mate.deployable() ? BLUE : RED,
                    DeployActionPacket.DeployKind.SQUAD, mate.id(), null);
        }
        // 据点不规则区域先画（在地面、标记之下）：边框 + 半透明填充。
        drawPointZones(pose, buffer, camera, status);
        for (DeployPointDto point : status.points()) {
            String label = point.name() == null || point.name().isBlank() ? "?" : point.name().substring(0, 1);
            int color = point.owner() == 0 ? GREY : (point.deployable() ? BLUE : RED);
            addAndRender(pose, buffer, font, camera, status,
                    point.x(), point.y(), point.z(), label, point.name(), point.deployable(), color,
                    DeployActionPacket.DeployKind.POINT, point.id(), pointTexture(point));
        }
        if (status.hasArea()) {
            if (status.areaBoundary().size() >= 3) {
                // 管理员圈画了不规则边界：按多边形画地面填充 + 边框（与据点区域同源画法）。
                // 此时不再画矩形框——多边形可能超出矩形 XZ，两者同画会互相误导。
                drawAreaPolygon(pose, buffer, camera, status.areaBoundary(), status.areaMinY());
            } else {
                drawAreaBox(pose, buffer, camera,
                        status.areaMinX(), status.areaMinY(), status.areaMinZ(),
                        status.areaMaxX(), status.areaMaxY(), status.areaMaxZ(),
                        AREA_FLOOR_RGB, AREA_WALL_RGB);
            }
            renderAreaLabel(pose, font, buffer, camera,
                    status.areaMinX(), status.areaMaxY(), status.areaMinZ(),
                    status.areaExplicit());
        }
        buffer.endBatch();
    }

    public static List<DeployClickTarget> targets() {
        return List.copyOf(TARGETS);
    }

    public static DeployClickTarget hoveredTarget() {
        return hoveredTarget;
    }

    public static void updateHover(double mouseX, double mouseY) {
        hoveredTarget = null;
        double best = Double.MAX_VALUE;
        for (DeployClickTarget target : TARGETS) {
            double dx = mouseX - target.x();
            double dy = mouseY - target.y();
            double dist = dx * dx + dy * dy;
            if (dist <= target.radius() * target.radius() && dist < best) {
                best = dist;
                hoveredTarget = target;
            }
        }
    }

    private static void addAndRender(PoseStack pose, MultiBufferSource.BufferSource buffer, Font font, Camera camera,
                                     DeployStatusDto status, double x, double y, double z,
                                     String icon, String name, boolean deployable, int color,
                                     DeployActionPacket.DeployKind kind, String targetId,
                                     ResourceLocation iconTexture) {
        Vec3 pos = new Vec3(x, y, z);
        double dist = pos.distanceTo(camera.getPosition());
        if (dist > MARKER_CULL_DISTANCE) {
            return;
        }
        float localY = iconTexture != null ? MARKER_ICON_LOCAL_Y : MARKER_LABEL_LOCAL_Y;
        Vec3 hotspot = pos.add(cameraUp.scale(-localY * markerScale(dist)));
        ScreenProjection projected = projectToScreen(Minecraft.getInstance(), hotspot);
        boolean selected = status.selectedKind().equals(kind.id())
                && (targetId == null || targetId.isBlank() || targetId.equals(status.selectedTarget()));
        boolean hovered = hoveredTarget != null && hoveredTarget.kind() == kind
                && safeId(hoveredTarget.targetId()).equals(safeId(targetId));
        if (projected != null && deployable) {
            TARGETS.add(new DeployClickTarget(projected.x(), projected.y(), selected || hovered ? 24 : 18, kind, targetId,
                    safe(name), deployable));
        }
        renderMarker(pose, buffer, font, camera, pos, icon, name, deployable, selected, hovered, color, iconTexture);
    }

    private static float markerScale(double dist) {
        return (float) Mth.clamp(dist * 0.0018D, 0.035D, 0.115D);
    }

    private static void renderMarker(PoseStack pose, MultiBufferSource buffer, Font font, Camera camera, Vec3 pos,
                                     String icon, String name, boolean deployable, boolean selected, boolean hovered, int color,
                                     ResourceLocation iconTexture) {
        Vec3 cam = camera.getPosition();
        double dx = pos.x - cam.x;
        double dy = pos.y - cam.y;
        double dz = pos.z - cam.z;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        float scale = markerScale(dist);
        int main = selected ? GREEN : (hovered && deployable ? WHITE : color);
        String line1 = (selected || hovered) ? "◆ " + safe(name) : safe(icon);
        String line2 = deployable ? (selected ? "已选择" : (hovered ? "点击确认" : Math.round(dist) + "m")) : "不可部署";

        pose.pushPose();
        pose.translate(dx, dy, dz);
        pose.mulPose(camera.rotation());
        pose.scale(-scale, -scale, scale);
        var mat = pose.last().pose();

        if (iconTexture != null) {
            drawIcon(mat, buffer, iconTexture, -8, -32, 16, 16, 220);
        }
        drawCentered(font, line1, 0, -16, main, mat, buffer, 0x77000000);
        drawCentered(font, line2, 0, -4, deployable ? WHITE : GREY, mat, buffer, 0x66000000);
        if (selected || hovered) {
            drawCentered(font, "▔▔▔", 0, 8, GREEN, mat, buffer, 0x00000000);
        }
        pose.popPose();
    }

    private static void drawCentered(Font font, String text, int x, int y, int color,
                                     Matrix4f matrix, MultiBufferSource buffer, int bg) {
        float px = x - font.width(text) / 2.0f;
        font.drawInBatch(text, px, y, color, false, matrix, buffer, Font.DisplayMode.SEE_THROUGH, bg, LIGHT);
    }

    /** 16x16 据点图标四边形——与 BattlefieldWorldPointOverlay.drawIcon 同技术路线。 */
    private static void drawIcon(Matrix4f matrix, MultiBufferSource buffer, ResourceLocation iconTexture,
                                 float x, float y, float w, float h, int alpha) {
        VertexConsumer vc = buffer.getBuffer(RenderType.text(iconTexture));
        int a = Math.max(0, Math.min(255, alpha));
        vc.vertex(matrix, x, y + h, 0.0f).color(255, 255, 255, a).uv(0.0f, 1.0f).uv2(LIGHT).endVertex();
        vc.vertex(matrix, x + w, y + h, 0.0f).color(255, 255, 255, a).uv(1.0f, 1.0f).uv2(LIGHT).endVertex();
        vc.vertex(matrix, x + w, y, 0.0f).color(255, 255, 255, a).uv(1.0f, 0.0f).uv2(LIGHT).endVertex();
        vc.vertex(matrix, x, y, 0.0f).color(255, 255, 255, a).uv(0.0f, 0.0f).uv2(LIGHT).endVertex();
    }

    /**
     * 据点标记图标选择：
     * owner==0 中立；deployable 即 owner==玩家阵营，取友方；其余敌方。
     * DeployPointDto 不带 pressure 字段，因此 POINT_OVERRUN 在此界面无法渲染。
     */
    private static ResourceLocation pointTexture(DeployPointDto point) {
        if (point.owner() == 0) {
            return POINT_NEUTRAL;
        }
        return point.deployable() ? POINT_FRIENDLY : POINT_ENEMY;
    }

    @SuppressWarnings("removal")
    private static ResourceLocation texture(String path) {
        return new ResourceLocation(Act0Battlefield.MODID, "textures/gui/hud/" + path);
    }

    private static String safe(String text) {
        return text == null || text.isBlank() ? "?" : text;
    }

    private static String safeId(String text) {
        return text == null ? "" : text;
    }

    private static void captureProjection(RenderLevelStageEvent event) {
        Camera camera = event.getCamera();
        projectionMatrix = new Matrix4f((Matrix4fc) event.getProjectionMatrix());
        cameraPos = camera.getPosition();
        cameraLook = toVec3(camera.getLookVector()).normalize();
        cameraRight = toVec3(camera.getLeftVector()).scale(-1.0).normalize();
        cameraUp = toVec3(camera.getUpVector()).normalize();
    }

    private static Vec3 toVec3(Vector3f value) {
        return new Vec3(value.x(), value.y(), value.z());
    }

    private static ScreenProjection projectToScreen(Minecraft mc, Vec3 pos) {
        if (mc.player == null || projectionMatrix == null) {
            return null;
        }
        Vec3 to = pos.subtract(cameraPos);
        double forward = to.dot(cameraLook);
        if (forward <= 0.01D) {
            return null;
        }
        float cameraX = (float) to.dot(cameraRight);
        float cameraY = (float) to.dot(cameraUp);
        float cameraZ = (float) -forward;
        Vector4f clip = new Vector4f(cameraX, cameraY, cameraZ, 1.0F).mul((Matrix4fc) projectionMatrix);
        if (Math.abs(clip.w()) <= 1.0E-6F) {
            return null;
        }
        double ndcX = clip.x() / clip.w();
        double ndcY = clip.y() / clip.w();
        double screenX = mc.getWindow().getGuiScaledWidth() * 0.5D * (1.0D + ndcX);
        double screenY = mc.getWindow().getGuiScaledHeight() * 0.5D * (1.0D - ndcY);
        if (screenX < -80 || screenY < -80 || screenX > mc.getWindow().getGuiScaledWidth() + 80
                || screenY > mc.getWindow().getGuiScaledHeight() + 80) {
            return null;
        }
        return new ScreenProjection(screenX, screenY);
    }

    public record DeployClickTarget(double x, double y, double radius,
                                    DeployActionPacket.DeployKind kind, String targetId,
                                    String label, boolean deployable) {
    }

    private record ScreenProjection(double x, double y) {
    }

    /** 绘制战斗区域 AABB：12 条边 + 角点强调。 */
    private static void drawAreaBox(PoseStack pose, MultiBufferSource.BufferSource buffer, Camera camera,
                                    double minX, double minY, double minZ,
                                    double maxX, double maxY, double maxZ,
                                    int floorColor, int wallColor) {
        Vec3 cam = camera.getPosition();
        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);
        Matrix4f matrix = pose.last().pose();
        pose.popPose();

        VertexConsumer consumer = buffer.getBuffer(RenderType.LINES);

        // 4 底边（地面轮廓）—— 强调色
        drawLine(consumer, matrix, minX, minY, minZ, maxX, minY, minZ, floorColor);
        drawLine(consumer, matrix, maxX, minY, minZ, maxX, minY, maxZ, floorColor);
        drawLine(consumer, matrix, maxX, minY, maxZ, minX, minY, maxZ, floorColor);
        drawLine(consumer, matrix, minX, minY, maxZ, minX, minY, minZ, floorColor);

        // 4 顶边 + 4 立柱 —— 较淡
        drawLine(consumer, matrix, minX, maxY, minZ, maxX, maxY, minZ, wallColor);
        drawLine(consumer, matrix, maxX, maxY, minZ, maxX, maxY, maxZ, wallColor);
        drawLine(consumer, matrix, maxX, maxY, maxZ, minX, maxY, maxZ, wallColor);
        drawLine(consumer, matrix, minX, maxY, maxZ, minX, maxY, minZ, wallColor);
        drawLine(consumer, matrix, minX, minY, minZ, minX, maxY, minZ, wallColor);
        drawLine(consumer, matrix, maxX, minY, minZ, maxX, maxY, minZ, wallColor);
        drawLine(consumer, matrix, maxX, minY, maxZ, maxX, maxY, maxZ, wallColor);
        drawLine(consumer, matrix, minX, minY, maxZ, minX, maxY, maxZ, wallColor);
    }

    /**
     * 绘制战斗区域的不规则多边形：地面半透明填充 + 逐边边框。
     *
     * <p>与 {@link #drawPointZones} 同源画法（耳切三角化发 TRIANGLES + LINES 描边），差别仅在原点：
     * 这里直接用世界坐标减相机位置，地面高度取矩形包围盒的 minY（多边形本身是 2D，无自身高度）。
     */
    private static void drawAreaPolygon(PoseStack pose, MultiBufferSource.BufferSource buffer, Camera camera,
                                        List<double[]> boundary, double groundY) {
        Polygon2D poly = Polygon2D.of(boundary);
        if (poly == null) {
            return;
        }
        Vec3 cam = camera.getPosition();
        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);
        Matrix4f matrix = pose.last().pose();
        pose.popPose();

        int rgb = AREA_FLOOR_RGB & 0xFFFFFF;
        int cr = (rgb >> 16) & 0xFF;
        int cg = (rgb >> 8) & 0xFF;
        int cb = rgb & 0xFF;

        VertexConsumer fill = buffer.getBuffer(PointZoneFill.TYPE);
        int[] tris = poly.triangulate();
        for (int idx : tris) {
            fill.vertex(matrix, (float) poly.x(idx), (float) groundY, (float) poly.z(idx))
                    .color(cr, cg, cb, ZONE_FILL_ALPHA).endVertex();
        }

        VertexConsumer line = buffer.getBuffer(RenderType.LINES);
        int rimArgb = (AREA_WALL_RGB & 0xFFFFFF) | (ZONE_RIM_ALPHA << 24);
        int n = poly.size();
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            drawLine(line, matrix, poly.x(i), groundY, poly.z(i), poly.x(j), groundY, poly.z(j), rimArgb);
        }
    }

    private static void drawLine(VertexConsumer consumer, Matrix4f matrix,
                                 double x1, double y1, double z1,
                                 double x2, double y2, double z2,
                                 int argb) {
        float a = ((argb >>> 24) & 0xFF) / 255.0f;
        float r = ((argb >>> 16) & 0xFF) / 255.0f;
        float g = ((argb >>> 8) & 0xFF) / 255.0f;
        float b = (argb & 0xFF) / 255.0f;
        // RenderType.LINES uses POSITION_COLOR_NORMAL; normal must be supplied for both vertices.
        // Pure debug overlay — normal direction has no visible effect on line rendering,
        // but missing it crashes strict vertex format validators (e.g. Xenon/Sodium).
        consumer.vertex(matrix, (float) x1, (float) y1, (float) z1).color(r, g, b, a).normal(0.0f, 1.0f, 0.0f).endVertex();
        consumer.vertex(matrix, (float) x2, (float) y2, (float) z2).color(r, g, b, a).normal(0.0f, 1.0f, 0.0f).endVertex();
    }

    /** 在区域一角绘制"战斗区域"标签：显式录入绿色，推导黄色。 */
    private static void renderAreaLabel(PoseStack pose, Font font, MultiBufferSource.BufferSource buffer,
                                        Camera camera, double x, double y, double z, boolean explicit) {
        Vec3 cam = camera.getPosition();
        double dx = x - cam.x;
        double dy = y - cam.y;
        double dz = z - cam.z;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist > 800.0D) {
            return;
        }
        float scale = (float) Mth.clamp(dist * 0.0020D, 0.05D, 0.15D);
        String label = explicit ? "战斗区域" : "战斗区域 (推导)";
        int color = explicit ? 0xFF9DFF9D : 0xFFFFD37A;

        pose.pushPose();
        pose.translate(dx, dy, dz);
        pose.mulPose(camera.rotation());
        pose.scale(-scale, -scale, scale);
        Matrix4f matrix = pose.last().pose();
        font.drawInBatch(label, -font.width(label) / 2.0f, 0f, color, false,
                matrix, buffer, Font.DisplayMode.SEE_THROUGH, 0x88000000, LIGHT);
        pose.popPose();
    }

    /**
     * 画据点的多边形区域：地面半透明填充 + 逐边边框。
     *
     * <p>只画管理员圈画过多边形（≥3 顶点）的据点；未圈画的据点仍用方形半径判定，但本层
     * 不额外画区域（方形范围由标记本身表达，避免与既有视觉重复）。
     */
    private static void drawPointZones(PoseStack pose, MultiBufferSource.BufferSource buffer,
                                       Camera camera, DeployStatusDto status) {
        Vec3 cam = camera.getPosition();
        VertexConsumer fill = buffer.getBuffer(PointZoneFill.TYPE);
        VertexConsumer line = buffer.getBuffer(RenderType.LINES);
        for (DeployPointDto point : status.points()) {
            List<double[]> boundary = point.boundary();
            if (boundary.size() < 3) {
                continue;
            }
            Polygon2D poly = Polygon2D.of(boundary);
            if (poly == null) {
                continue;
            }
            int rgb = point.owner() == 0 ? (GREY & 0xFFFFFF)
                    : (point.deployable() ? (BLUE & 0xFFFFFF) : (RED & 0xFFFFFF));
            int cr = (rgb >> 16) & 0xFF;
            int cg = (rgb >> 8) & 0xFF;
            int cb = rgb & 0xFF;

            // 相机相对平移，避免远离原点时 float 精度抖动。
            pose.pushPose();
            pose.translate(point.x() - cam.x, point.y() + ZONE_GROUND_LIFT - cam.y, point.z() - cam.z);
            Matrix4f matrix = pose.last().pose();

            // 填充：耳切三角化后发 TRIANGLES。
            int[] tris = poly.triangulate();
            for (int i = 0; i < tris.length; i++) {
                double[] v = boundary.get(tris[i]);
                fill.vertex(matrix, (float) (v[0] - point.x()), 0f, (float) (v[1] - point.z()))
                        .color(cr, cg, cb, ZONE_FILL_ALPHA).endVertex();
            }
            // 边框：逐边发 LINES（必须补 normal，严格顶点校验器会崩）。
            int n = boundary.size();
            for (int i = 0; i < n; i++) {
                double[] a = boundary.get(i);
                double[] b = boundary.get((i + 1) % n);
                line.vertex(matrix, (float) (a[0] - point.x()), 0f, (float) (a[1] - point.z()))
                        .color(cr, cg, cb, ZONE_RIM_ALPHA).normal(0f, 1f, 0f).endVertex();
                line.vertex(matrix, (float) (b[0] - point.x()), 0f, (float) (b[1] - point.z()))
                        .color(cr, cg, cb, ZONE_RIM_ALPHA).normal(0f, 1f, 0f).endVertex();
            }
            pose.popPose();
        }
    }

    /**
     * 据点多边形区域的地面半透明填充 {@link RenderType}。
     *
     * <p>状态选择与 {@code DeployableGroundOverlay.GroundDisc} 同源：深度测试开（被地形正确遮挡）
     * + 深度写关（填充与边框不抢深度）+ 关背面剔除。嵌套类的惰性初始化把 {@code RenderType.create}
     * 推迟到首次渲染，避开 mod CONSTRUCT 阶段的静态初始化。
     */
    private static final class PointZoneFill extends RenderType {

        private static final RenderType TYPE = RenderType.create(
                Act0Battlefield.MODID + ":point_zone_fill",
                DefaultVertexFormat.POSITION_COLOR,
                VertexFormat.Mode.TRIANGLES,
                1536,
                false,
                false,
                RenderType.CompositeState.builder()
                        .setShaderState(POSITION_COLOR_SHADER)
                        .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                        .setDepthTestState(LEQUAL_DEPTH_TEST)
                        .setWriteMaskState(COLOR_WRITE)
                        .setCullState(NO_CULL)
                        .createCompositeState(false));

        private PointZoneFill() {
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
