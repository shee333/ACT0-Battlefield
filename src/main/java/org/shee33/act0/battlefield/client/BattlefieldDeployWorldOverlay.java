package org.shee33.act0.battlefield.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
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
        for (DeployPointDto point : status.points()) {
            String label = point.name() == null || point.name().isBlank() ? "?" : point.name().substring(0, 1);
            int color = point.owner() == 0 ? GREY : (point.deployable() ? BLUE : RED);
            addAndRender(pose, buffer, font, camera, status,
                    point.x(), point.y(), point.z(), label, point.name(), point.deployable(), color,
                    DeployActionPacket.DeployKind.POINT, point.id(), pointTexture(point));
        }
        if (status.hasArea()) {
            drawAreaBox(pose, buffer, camera,
                    status.areaMinX(), status.areaMinY(), status.areaMinZ(),
                    status.areaMaxX(), status.areaMaxY(), status.areaMaxZ(),
                    AREA_FLOOR_RGB, AREA_WALL_RGB);
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
}
