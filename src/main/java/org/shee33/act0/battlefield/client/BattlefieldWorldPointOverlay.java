package org.shee33.act0.battlefield.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.shee33.act0.battlefield.Act0Battlefield;
import org.shee33.act0.battlefield.network.BattleHudDto;
import org.shee33.act0.battlefield.network.ControlPointHudDto;

/**
 * 世界空间据点浮标：在 3D 视角中把 A/B/C 据点绘制为面向镜头的悬浮标志。
 *
 * <p>数据复用 {@link ClientBattleHud} 中的 HUD 快照；服务端每 0.5 秒同步据点坐标、归属与进度。
 * 颜色规则与战中 HUD 一致：自己阵营=蓝，敌方=红，中立=灰。
 */
@Mod.EventBusSubscriber(modid = Act0Battlefield.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class BattlefieldWorldPointOverlay {

    private static final int BLUE = 0xFF57C7FF;
    private static final int RED = 0xFFE7654E;
    private static final int GREY = 0xFF9EA7AA;
    private static final int WHITE = 0xFFE8F4F8;
    private static final int TEXT_DIM = 0xFFB0BEC5;
    private static final int LIGHT = 0xF000F0;
    private static final double MAX_DISTANCE = 220.0;

    private static final ResourceLocation POINT_FRIENDLY = texture("capturepoint/allies.png");
    private static final ResourceLocation POINT_ENEMY = texture("capturepoint/axis.png");
    private static final ResourceLocation POINT_NEUTRAL = texture("misc/capturepoint.png");
    private static final ResourceLocation POINT_OVERRUN = texture("misc/capturepoint_overrun.png");

    private BattlefieldWorldPointOverlay() {
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        BattleHudDto hud = ClientBattleHud.hud();
        if (!ClientBattleHud.isShown() || hud == null || hud.points().isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) {
            return;
        }

        PoseStack pose = event.getPoseStack();
        Camera camera = event.getCamera();
        Vec3 cam = camera.getPosition();
        Font font = mc.font;
        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();

        for (ControlPointHudDto point : hud.points()) {
            double dx = point.x() - cam.x;
            double dy = point.y() - cam.y;
            double dz = point.z() - cam.z;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist > MAX_DISTANCE) {
                continue;
            }
            renderPoint(pose, buffer, font, camera, point, hud.myFaction(), dx, dy, dz, dist);
        }
        buffer.endBatch();
    }

    private static void renderPoint(PoseStack pose, MultiBufferSource buffer, Font font, Camera camera,
                                    ControlPointHudDto point, int myFaction,
                                    double dx, double dy, double dz, double distance) {
        int ownerColor = factionColor(point.owner(), myFaction);
        int pressureColor = factionColor(point.pressure(), myFaction);
        int mainColor = point.pressure() != 0 ? pressureColor : ownerColor;

        String name = point.name();
        if (name.length() > 1) {
            name = name.substring(0, 1);
        }
        String line2 = ((int) Math.round(distance)) + "m";
        String line3 = progressText(point.progress(), point.pressure() != 0);

        // 随距离略放大，避免远处过小；近处保持克制。
        float scale = (float) Math.max(0.024, Math.min(0.075, distance * 0.00145));

        pose.pushPose();
        pose.translate(dx, dy, dz);
        pose.mulPose(camera.rotation());
        pose.scale(-scale, -scale, scale);

        Matrix4f mat = pose.last().pose();
        drawIcon(mat, buffer, pointTexture(point, myFaction), -8, -24, 16, 16, 220);
        drawCentered(font, name, 0, -19, WHITE, mat, buffer, 0x66000000);
        drawCentered(font, line2, 0, -6, TEXT_DIM, mat, buffer, 0x44000000);
        if (point.progress() > 0 && point.pressure() != 0) {
            drawCentered(font, line3, 0, 5, mainColor, mat, buffer, 0x44000000);
        }
        pose.popPose();
    }

    private static void drawCentered(Font font, String text, int x, int y, int color,
                                     Matrix4f matrix, MultiBufferSource buffer, int bg) {
        float px = x - font.width(text) / 2.0f;
        font.drawInBatch(text, px, y, color, false, matrix, buffer, Font.DisplayMode.SEE_THROUGH, bg, LIGHT);
    }

    private static void drawIcon(Matrix4f matrix, MultiBufferSource buffer, ResourceLocation texture,
                                 float x, float y, float w, float h, int alpha) {
        VertexConsumer vc = buffer.getBuffer(RenderType.text(texture));
        int a = Math.max(0, Math.min(255, alpha));
        vc.vertex(matrix, x, y + h, 0.0f).color(255, 255, 255, a).uv(0.0f, 1.0f).uv2(LIGHT).endVertex();
        vc.vertex(matrix, x + w, y + h, 0.0f).color(255, 255, 255, a).uv(1.0f, 1.0f).uv2(LIGHT).endVertex();
        vc.vertex(matrix, x + w, y, 0.0f).color(255, 255, 255, a).uv(1.0f, 0.0f).uv2(LIGHT).endVertex();
        vc.vertex(matrix, x, y, 0.0f).color(255, 255, 255, a).uv(0.0f, 0.0f).uv2(LIGHT).endVertex();
    }

    private static String progressText(int progress, boolean active) {
        int bars = Math.max(0, Math.min(8, Math.round(progress / 12.5f)));
        StringBuilder sb = new StringBuilder(active ? "占领 " : "");
        for (int i = 0; i < 8; i++) {
            sb.append(i < bars ? '▰' : '▱');
        }
        return sb.toString();
    }

    private static int factionColor(int faction, int mine) {
        if (faction == 0) {
            return GREY;
        }
        return faction == 1 ? RED : BLUE;
    }

    private static ResourceLocation pointTexture(ControlPointHudDto point, int mine) {
        if (point.owner() == 0) {
            return point.pressure() == 0 ? POINT_NEUTRAL : POINT_OVERRUN;
        }
        return factionColor(point.owner(), mine) == BLUE ? POINT_FRIENDLY : POINT_ENEMY;
    }

    @SuppressWarnings("removal")
    private static ResourceLocation texture(String path) {
        return new ResourceLocation(Act0Battlefield.MODID, "textures/gui/hud/" + path);
    }
}
