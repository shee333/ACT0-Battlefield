package org.shee33.act0.battlefield.client;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.shee33.act0.battlefield.network.DeploySquadMateDto;

import java.util.List;

/**
 * 死亡等待重生阶段的小队越肩观战控制器。
 *
 * <p>玩家死亡进入重生等待后，默认自动锁定离玩家最近的存活小队成员并切换为第三人称后方视角，形成
 * 类似战地系列的越肩观战；玩家可按键循环切换到下一个存活队友。若小队没有存活成员，则保持原版自由
 * 飞行（不接管相机）。切换目标时记录淡入淡出时间，UI 叠加黑幕做转场。退出重生等待/部署界面时恢复
 * 原相机与原视角类型。
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

    /**
     * 每帧驱动自动跟随：只要当前跟随目标仍存活（存在于 {@code mates} 中）就保持不变；否则重新选取
     * （优先服务端给出的最近队友提示 {@code hintEntityId}，其次退回列表首个）。列表为空（无存活队友）
     * 时清空跟随，回到原版自由飞行。
     */
    public static void updateSpectate(List<DeploySquadMateDto> mates, int hintEntityId) {
        if (mates.isEmpty()) {
            clear();
            return;
        }
        for (DeploySquadMateDto mate : mates) {
            if (mate.entityId() == currentEntityId) {
                return;
            }
        }
        for (DeploySquadMateDto mate : mates) {
            if (mate.entityId() == hintEntityId) {
                focus(hintEntityId);
                return;
            }
        }
        focus(mates.get(0).entityId());
    }

    /** 手动循环切换到列表中的下一个存活队友（按小队列表顺序，越界回绕）。 */
    public static void cycleNext(List<DeploySquadMateDto> mates) {
        if (mates.isEmpty()) {
            return;
        }
        int index = -1;
        for (int i = 0; i < mates.size(); i++) {
            if (mates.get(i).entityId() == currentEntityId) {
                index = i;
                break;
            }
        }
        int next = (index + 1) % mates.size();
        focus(mates.get(next).entityId());
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
