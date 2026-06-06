package org.shee33.act0.battlefield.client;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

/**
 * 部署界面的小队越肩观战控制器。
 *
 * <p>当玩家在重新部署地图中选中某个具体小队成员时，客户端把相机切到该成员并切换为第三人称后方，形成
 * 类似战地5的越肩观战；切换目标时记录淡入淡出时间，UI 叠加黑幕做转场。退出部署界面时恢复原相机与
 * 原视角类型。
 */
public final class ClientSquadSpectate {

    private static int currentEntityId = -1;
    private static Entity previousCamera;
    private static CameraType previousCameraType;
    private static long transitionStartedMs = 0L;

    private ClientSquadSpectate() {
    }

    /** 切换到指定小队成员实体的越肩视角。 */
    public static void focus(int entityId) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        Entity target = mc.level.getEntity(entityId);
        if (target == null) {
            clear();
            return;
        }
        if (currentEntityId == entityId && mc.getCameraEntity() == target) {
            return;
        }
        if (currentEntityId == -1) {
            previousCamera = mc.getCameraEntity();
            previousCameraType = mc.options.getCameraType();
        }
        currentEntityId = entityId;
        transitionStartedMs = System.currentTimeMillis();
        mc.setCameraEntity(target);
        mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
    }

    /** 恢复进入部署观战前的相机。 */
    public static void clear() {
        Minecraft mc = Minecraft.getInstance();
        if (currentEntityId == -1) {
            return;
        }
        if (previousCamera != null) {
            mc.setCameraEntity(previousCamera);
        } else if (mc.player != null) {
            mc.setCameraEntity(mc.player);
        }
        if (previousCameraType != null) {
            mc.options.setCameraType(previousCameraType);
        }
        currentEntityId = -1;
        previousCamera = null;
        previousCameraType = null;
        transitionStartedMs = 0L;
    }

    /** 切换淡出淡入黑幕 alpha（0~180）。 */
    public static int fadeAlpha() {
        if (transitionStartedMs <= 0L) {
            return 0;
        }
        long dt = System.currentTimeMillis() - transitionStartedMs;
        if (dt >= 700L) {
            return 0;
        }
        // 前 250ms 逐渐变黑，后 450ms 淡回。
        if (dt < 250L) {
            return (int) (180 * (dt / 250.0));
        }
        return (int) (180 * (1.0 - ((dt - 250L) / 450.0)));
    }
}
