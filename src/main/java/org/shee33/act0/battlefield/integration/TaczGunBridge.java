package org.shee33.act0.battlefield.integration;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Method;

/**
 * TaCZ（Timeless and Classics Zero）<b>弹药与换弹状态</b>的反射软依赖桥，供作战 HUD 的武器栏使用。
 *
 * <p>本模组不在编译期依赖 TaCZ。与 Arcade 的 {@code TaczBridge}（只管配件）刻意分开：那个桥在
 * 另一个仓库、且战地对 Arcade 也只是可选依赖，走"战地→反射 Arcade→反射 TaCZ"两跳会让弹药显示
 * 平白依赖 Arcade 是否在场。
 *
 * <p><b>逐方法降级</b>：每个反射目标单独解析，任何一个缺失只让对应功能回退，不会拖垮整个桥。
 * 这是因为 TaCZ 在 1.20.1 分支上 1.0.x 与 1.1.x 的 API 并不一致（例如 {@code useInventoryAmmo}
 * 只有 1.1.x 才有），而玩家服上跑的具体版本不受我们控制。
 *
 * <p>API 依据 TaCZ 仓库 {@code MCModderAnchor/TACZ} 分支 {@code 1.20.1}（tag 1.1.8-hotfix）核对。
 */
public final class TaczGunBridge {

    private static Class<?> iGunClass;
    private static Class<?> iAmmoClass;
    private static Class<?> iAmmoBoxClass;

    private static Method getIGunOrNull;
    private static Method gunGetCurrentAmmoCount;
    private static Method gunHasBulletInBarrel;
    private static Method gunUseDummyAmmo;
    private static Method gunGetDummyAmmoAmount;
    /** 仅 TaCZ 1.1.x 存在；1.0.x 下为 null，视为"不启用背包直读"。 */
    private static Method gunUseInventoryAmmo;

    private static Method ammoIsAmmoOfGun;
    private static Method boxIsAmmoBoxOfGun;
    private static Method boxGetAmmoCount;
    private static Method boxIsCreative;
    private static Method boxIsAllTypeCreative;

    private static Method gunGetGunId;
    private static Method timelessGetClientGunIndex;
    private static Method clientIndexGetGunData;
    private static Method gunDataGetBolt;
    private static Object openBoltConstant;

    private static Method operatorFromLivingEntity;
    private static Method operatorGetSynReloadState;
    private static Method reloadStateGetStateType;
    private static Method reloadStateGetCountDown;
    private static Method stateTypeIsReloading;

    // ---- TaCZ API 名。集中放置并由 TaczGunBridgeTest 钉死：这些字符串拼错不会报错，
    // 只会让对应功能静默失灵（开发中就差点把 getSynReloadState 写成 getSyncReloadState）。
    static final String CLASS_I_GUN = "com.tacz.guns.api.item.IGun";
    static final String CLASS_I_AMMO = "com.tacz.guns.api.item.IAmmo";
    static final String CLASS_I_AMMO_BOX = "com.tacz.guns.api.item.IAmmoBox";
    static final String CLASS_GUN_OPERATOR = "com.tacz.guns.api.entity.IGunOperator";
    static final String CLASS_RELOAD_STATE = "com.tacz.guns.api.entity.ReloadState";
    static final String CLASS_RELOAD_STATE_TYPE = "com.tacz.guns.api.entity.ReloadState$StateType";
    static final String M_GET_I_GUN_OR_NULL = "getIGunOrNull";
    static final String M_CURRENT_AMMO = "getCurrentAmmoCount";
    static final String M_HAS_BULLET_IN_BARREL = "hasBulletInBarrel";
    static final String M_USE_DUMMY_AMMO = "useDummyAmmo";
    static final String M_DUMMY_AMMO_AMOUNT = "getDummyAmmoAmount";
    static final String M_USE_INVENTORY_AMMO = "useInventoryAmmo";
    static final String M_IS_AMMO_OF_GUN = "isAmmoOfGun";
    static final String M_IS_AMMO_BOX_OF_GUN = "isAmmoBoxOfGun";
    static final String M_BOX_AMMO_COUNT = "getAmmoCount";
    static final String M_BOX_IS_CREATIVE = "isCreative";
    static final String M_BOX_IS_ALL_TYPE_CREATIVE = "isAllTypeCreative";
    static final String M_FROM_LIVING_ENTITY = "fromLivingEntity";
    static final String M_GET_SYN_RELOAD_STATE = "getSynReloadState";
    static final String M_GET_STATE_TYPE = "getStateType";
    static final String M_GET_COUNT_DOWN = "getCountDown";
    static final String M_IS_RELOADING = "isReloading";
    static final String CLASS_TIMELESS_API = "com.tacz.guns.api.TimelessAPI";
    static final String CLASS_CLIENT_GUN_INDEX = "com.tacz.guns.client.resource.index.ClientGunIndex";
    static final String CLASS_GUN_DATA = "com.tacz.guns.resource.pojo.data.gun.GunData";
    static final String CLASS_BOLT = "com.tacz.guns.resource.pojo.data.gun.Bolt";
    static final String M_GET_GUN_ID = "getGunId";
    static final String M_GET_CLIENT_GUN_INDEX = "getClientGunIndex";
    static final String M_GET_GUN_DATA = "getGunData";
    static final String M_GET_BOLT = "getBolt";
    static final String ENUM_OPEN_BOLT = "OPEN_BOLT";

    private static final boolean AVAILABLE;

    static {
        boolean gunOk = false;
        try {
            iGunClass = Class.forName(CLASS_I_GUN);
            getIGunOrNull = iGunClass.getMethod(M_GET_I_GUN_OR_NULL, ItemStack.class);
            gunGetCurrentAmmoCount = iGunClass.getMethod(M_CURRENT_AMMO, ItemStack.class);
            gunOk = true;
        } catch (Throwable ignored) {
            gunOk = false;
        }
        AVAILABLE = gunOk;

        gunHasBulletInBarrel = optional(iGunClass, M_HAS_BULLET_IN_BARREL, ItemStack.class);
        gunUseDummyAmmo = optional(iGunClass, M_USE_DUMMY_AMMO, ItemStack.class);
        gunGetDummyAmmoAmount = optional(iGunClass, M_DUMMY_AMMO_AMOUNT, ItemStack.class);
        gunUseInventoryAmmo = optional(iGunClass, M_USE_INVENTORY_AMMO, ItemStack.class);

        try {
            gunGetGunId = iGunClass == null ? null : iGunClass.getMethod(M_GET_GUN_ID, ItemStack.class);
            Class<?> timelessApi = Class.forName(CLASS_TIMELESS_API);
            timelessGetClientGunIndex = timelessApi.getMethod(M_GET_CLIENT_GUN_INDEX, ResourceLocation.class);
            clientIndexGetGunData = Class.forName(CLASS_CLIENT_GUN_INDEX).getMethod(M_GET_GUN_DATA);
            gunDataGetBolt = Class.forName(CLASS_GUN_DATA).getMethod(M_GET_BOLT);
            for (Object constant : Class.forName(CLASS_BOLT).getEnumConstants()) {
                if (ENUM_OPEN_BOLT.equals(((Enum<?>) constant).name())) {
                    openBoltConstant = constant;
                }
            }
        } catch (Throwable ignored) {
            openBoltConstant = null;
        }

        try {
            iAmmoClass = Class.forName(CLASS_I_AMMO);
            ammoIsAmmoOfGun = iAmmoClass.getMethod(M_IS_AMMO_OF_GUN, ItemStack.class, ItemStack.class);
        } catch (Throwable ignored) {
            iAmmoClass = null;
        }
        try {
            iAmmoBoxClass = Class.forName(CLASS_I_AMMO_BOX);
            boxIsAmmoBoxOfGun = iAmmoBoxClass.getMethod(M_IS_AMMO_BOX_OF_GUN, ItemStack.class, ItemStack.class);
            boxGetAmmoCount = iAmmoBoxClass.getMethod(M_BOX_AMMO_COUNT, ItemStack.class);
            boxIsCreative = optional(iAmmoBoxClass, M_BOX_IS_CREATIVE, ItemStack.class);
            boxIsAllTypeCreative = optional(iAmmoBoxClass, M_BOX_IS_ALL_TYPE_CREATIVE, ItemStack.class);
        } catch (Throwable ignored) {
            iAmmoBoxClass = null;
        }
        try {
            Class<?> operatorClass = Class.forName(CLASS_GUN_OPERATOR);
            operatorFromLivingEntity = operatorClass.getMethod(M_FROM_LIVING_ENTITY, LivingEntity.class);
            operatorGetSynReloadState = operatorClass.getMethod(M_GET_SYN_RELOAD_STATE);
            Class<?> reloadStateClass = Class.forName(CLASS_RELOAD_STATE);
            reloadStateGetStateType = reloadStateClass.getMethod(M_GET_STATE_TYPE);
            reloadStateGetCountDown = reloadStateClass.getMethod(M_GET_COUNT_DOWN);
            Class<?> stateTypeClass = Class.forName(CLASS_RELOAD_STATE_TYPE);
            stateTypeIsReloading = stateTypeClass.getMethod(M_IS_RELOADING);
        } catch (Throwable ignored) {
            operatorFromLivingEntity = null;
        }
    }

    private static Method optional(Class<?> owner, String name, Class<?>... params) {
        if (owner == null) {
            return null;
        }
        try {
            return owner.getMethod(name, params);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private TaczGunBridge() {
    }

    /** 运行时是否存在 TaCZ 枪械 API。 */
    public static boolean isAvailable() {
        return AVAILABLE;
    }

    public static boolean isGun(ItemStack stack) {
        return iGun(stack) != null;
    }

    private static Object iGun(ItemStack stack) {
        if (!AVAILABLE || stack == null || stack.isEmpty()) {
            return null;
        }
        try {
            return getIGunOrNull.invoke(null, stack);
        } catch (Throwable e) {
            return null;
        }
    }

    /** 弹匣内余弹；非枪或不可用返回 -1。 */
    public static int currentAmmo(ItemStack stack) {
        Object gun = iGun(stack);
        if (gun == null) {
            return -1;
        }
        try {
            Object v = gunGetCurrentAmmoCount.invoke(gun, stack);
            return v instanceof Integer i ? i : -1;
        } catch (Throwable e) {
            return -1;
        }
    }

    /**
     * 该枪是否为开膛待击（OPEN_BOLT）。开膛枪械没有"独立的膛内一发"，TaCZ 官方 HUD 因此
     * 不把 {@link #hasBulletInBarrel} 计入弹匣显示数。
     *
     * <p>取值链是 {@code IGun.getGunId → TimelessAPI.getClientGunIndex → ClientGunIndex
     * .getGunData → GunData.getBolt}，纯客户端资源索引。任何一环解析不到都返回 false，
     * 退回"按闭膛处理"——绝大多数枪械是闭膛，这是更接近正确的默认。
     */
    public static boolean isOpenBolt(ItemStack stack) {
        Object gun = iGun(stack);
        if (gun == null || gunGetGunId == null || timelessGetClientGunIndex == null
                || clientIndexGetGunData == null || gunDataGetBolt == null || openBoltConstant == null) {
            return false;
        }
        try {
            Object gunId = gunGetGunId.invoke(gun, stack);
            if (gunId == null) {
                return false;
            }
            Object optional = timelessGetClientGunIndex.invoke(null, gunId);
            if (!(optional instanceof java.util.Optional<?> opt) || opt.isEmpty()) {
                return false;
            }
            Object gunData = clientIndexGetGunData.invoke(opt.get());
            return gunData != null && openBoltConstant.equals(gunDataGetBolt.invoke(gunData));
        } catch (Throwable e) {
            return false;
        }
    }

    /** 是否有已上膛的一发（闭膛枪械 TaCZ 自己的 HUD 会把它计入弹匣数）。 */
    public static boolean hasBulletInBarrel(ItemStack stack) {
        Object gun = iGun(stack);
        if (gun == null || gunHasBulletInBarrel == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(gunHasBulletInBarrel.invoke(gun, stack));
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * 备弹数。TaCZ <b>没有</b>现成的备弹接口，其官方 HUD 也是遍历背包统计 {@code IAmmo} 与
     * {@code IAmmoBox}，这里照搬同一套判定：
     * <ol>
     *   <li>虚拟备弹模式（{@code useDummyAmmo}）→ 直接取 {@code getDummyAmmoAmount}；</li>
     *   <li>背包直读模式（1.1.x 的 {@code useInventoryAmmo}）→ TaCZ 官方 HUD 此时不显示备弹，
     *       返回 -1 让调用方省略；</li>
     *   <li>否则遍历背包：散装弹按堆叠数累加，弹药箱按其内含弹量累加，创造弹药箱直接 9999。</li>
     * </ol>
     *
     * @return 备弹数；-1 表示"不适用/不显示"
     */
    public static int reserveAmmo(Player player, ItemStack gunStack) {
        Object gun = iGun(gunStack);
        if (gun == null || player == null) {
            return -1;
        }
        try {
            if (gunUseDummyAmmo != null && Boolean.TRUE.equals(gunUseDummyAmmo.invoke(gun, gunStack))) {
                if (gunGetDummyAmmoAmount == null) {
                    return -1;
                }
                Object v = gunGetDummyAmmoAmount.invoke(gun, gunStack);
                return v instanceof Integer i ? i : -1;
            }
            if (gunUseInventoryAmmo != null
                    && Boolean.TRUE.equals(gunUseInventoryAmmo.invoke(gun, gunStack))) {
                return -1;
            }
        } catch (Throwable e) {
            return -1;
        }
        return countInventoryAmmo(player.getInventory(), gunStack);
    }

    private static int countInventoryAmmo(Inventory inventory, ItemStack gunStack) {
        int total = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack slot = inventory.getItem(i);
            if (slot.isEmpty()) {
                continue;
            }
            Object item = slot.getItem();
            try {
                if (iAmmoClass != null && iAmmoClass.isInstance(item)
                        && Boolean.TRUE.equals(ammoIsAmmoOfGun.invoke(item, gunStack, slot))) {
                    total += slot.getCount();
                    continue;
                }
                if (iAmmoBoxClass != null && iAmmoBoxClass.isInstance(item)
                        && Boolean.TRUE.equals(boxIsAmmoBoxOfGun.invoke(item, gunStack, slot))) {
                    if (isCreativeBox(item, slot)) {
                        return 9999;
                    }
                    Object v = boxGetAmmoCount.invoke(item, slot);
                    if (v instanceof Integer c) {
                        total += c;
                    }
                }
            } catch (Throwable ignored) {
                // 单个槽位判定失败不影响其余统计
            }
        }
        return total;
    }

    private static boolean isCreativeBox(Object item, ItemStack slot) throws Exception {
        if (boxIsAllTypeCreative != null && Boolean.TRUE.equals(boxIsAllTypeCreative.invoke(item, slot))) {
            return true;
        }
        return boxIsCreative != null && Boolean.TRUE.equals(boxIsCreative.invoke(item, slot));
    }

    /** 玩家当前是否正在换弹。 */
    public static boolean isReloading(LivingEntity entity) {
        Object stateType = reloadStateType(entity);
        if (stateType == null || stateTypeIsReloading == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(stateTypeIsReloading.invoke(stateType));
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * 换弹剩余毫秒数；未在换弹或不可用返回 -1。
     *
     * <p>TaCZ 只同步"剩余时间"，不给总时长——总时长藏在 {@code GunData.getReloadData()} 里，取它
     * 需要再穿 {@code TimelessAPI → ClientGunIndex → GunData} 三层反射。HUD 只需要一条进度条，
     * 由调用方记录本次换弹观测到的最大剩余值当分母即可，不值得为此多接三个反射目标。
     */
    public static long reloadCountDownMs(LivingEntity entity) {
        Object state = reloadState(entity);
        if (state == null || reloadStateGetCountDown == null) {
            return -1L;
        }
        try {
            Object v = reloadStateGetCountDown.invoke(state);
            return v instanceof Long l ? l : -1L;
        } catch (Throwable e) {
            return -1L;
        }
    }

    private static Object reloadState(LivingEntity entity) {
        if (entity == null || operatorFromLivingEntity == null || operatorGetSynReloadState == null) {
            return null;
        }
        try {
            Object operator = operatorFromLivingEntity.invoke(null, entity);
            return operator == null ? null : operatorGetSynReloadState.invoke(operator);
        } catch (Throwable e) {
            return null;
        }
    }

    private static Object reloadStateType(LivingEntity entity) {
        Object state = reloadState(entity);
        if (state == null || reloadStateGetStateType == null) {
            return null;
        }
        try {
            return reloadStateGetStateType.invoke(state);
        } catch (Throwable e) {
            return null;
        }
    }
}
