package org.shee33.act0.battlefield.client;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.shee33.act0.battlefield.network.DeploySquadMateDto;

import java.util.List;

/**
 * 死亡等待重生阶段的观战相机控制器。
 *
 * <p>默认保持全局俯瞰部署地图，相机完全交还玩家自由观察；只有玩家在部署列表里明确选中了一个
 * 具体的部署目标才离开俯瞰：
 * <ul>
 *   <li>选中队友——切换为该队友的第三人称越肩跟随（{@link #focus(int)}），玩家可用 V 键在当前
 *       可部署的队友之间循环切换（{@link #cycleNext}）。</li>
 *   <li>选中据点/基地——不接管相机位置，只在目标变化时做一次性转向，把视线对准目标附近
 *       （{@link #focusLocation}），随后立刻把朝向交还玩家自由环顾。</li>
 * </ul>
 * 未选中任何目标、或选中的目标失效（如队友已阵亡）时一律回到默认俯瞰。退出重生等待/部署界面时
 * 恢复原相机与原视角类型。
 */
public final class ClientSquadSpectate {

    private static int currentEntityId = -1;
    private static Entity previousCamera;
    private static CameraType previousCameraType;
    private static long transitionStartedMs = 0L;

    /** 上一次做过转向提示的据点/基地 key（如 {@code "point:3"}/{@code "base"}），避免重复打断。 */
    private static String locationFocusKey = "";

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
     * 手动循环切换到列表中的下一个队友（按传入列表顺序，越界回绕）。若当前尚未处于越肩视角
     * （例如刚从俯瞰进入，或选中的是据点/基地），优先跳到服务端提示的最近队友 {@code hintEntityId}，
     * 其次退回列表首位。返回新聚焦的队友，供调用方把部署选择同步为该队友，避免下一次状态刷新时
     * 因选择与相机不一致而被打回俯瞰；列表为空时返回 {@code null} 且不改变相机。
     */
    public static DeploySquadMateDto cycleNext(List<DeploySquadMateDto> mates, int hintEntityId) {
        if (mates.isEmpty()) {
            return null;
        }
        int index = -1;
        for (int i = 0; i < mates.size(); i++) {
            if (mates.get(i).entityId() == currentEntityId) {
                index = i;
                break;
            }
        }
        if (index == -1) {
            for (DeploySquadMateDto mate : mates) {
                if (mate.entityId() == hintEntityId) {
                    focus(hintEntityId);
                    return mate;
                }
            }
            DeploySquadMateDto first = mates.get(0);
            focus(first.entityId());
            return first;
        }
        DeploySquadMateDto next = mates.get((index + 1) % mates.size());
        focus(next.entityId());
        return next;
    }

    /** 恢复进入部署观战前的相机（仅越肩跟随状态；不影响据点/基地转向提示的去重记录）。 */
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

    /** 切换小队成员时的淡出淡入黑幕 alpha（0~180）。 */
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

    /**
     * 浏览阶段选中据点/基地（而非队友）时调用：不接管相机位置，仍保持自由俯瞰，只在目标发生
     * 变化（{@code key} 与上次不同）时把视线瞬间对准目标附近，随后立刻把朝向控制权交还玩家——
     * 不会持续接管、不会与玩家的自由环顾打架。同一目标重复调用（key 不变）不会重新打断玩家。
     */
    public static void focusLocation(String key, double x, double y, double z) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || key.equals(locationFocusKey)) {
            return;
        }
        locationFocusKey = key;
        Vec3 from = mc.player.getEyePosition();
        double dx = x - from.x;
        double dy = y - from.y;
        double dz = z - from.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) (-Math.toDegrees(Math.atan2(dy, horiz)));
        mc.player.setYRot(yaw);
        mc.player.setXRot(Mth.clamp(pitch, -90f, 90f));
    }

    /** 离开据点/基地聚焦状态（切到队友视角或彻底取消选中）时重置去重记录，便于下次重选同一目标时再次转向提示。 */
    public static void clearLocationFocus() {
        locationFocusKey = "";
    }
}
