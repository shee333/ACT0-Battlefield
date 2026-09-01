package org.shee33.act0.battlefield.integration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 锁住 TaCZ 反射桥的两条契约。
 *
 * <p><b>一、API 名拼写。</b>反射用错名字不会抛异常，只会让对应功能永远返回默认值——弹药栏
 * 空着、换弹条不动，日志里一个字都没有。所以把每个目标名钉在这里，改动必须是刻意的。
 * 名称依据 TaCZ 仓库 {@code MCModderAnchor/TACZ} 分支 {@code 1.20.1}（tag 1.1.8-hotfix）核对。
 *
 * <p><b>二、空输入安全。</b>桥在每帧 HUD 渲染路径上被调用，一次抛出就是满屏报错。
 *
 * <p>对着真实 TaCZ jar 的解析验证在 {@code TaczGunBridgeRealJarTest}——本类不依赖 TaCZ
 * 是否在 classpath 上，两边断言互不冲突。
 */
class TaczGunBridgeTest {

    @Test
    void taczClassNamesMatchUpstream() {
        assertEquals("com.tacz.guns.api.item.IGun", TaczGunBridge.CLASS_I_GUN);
        assertEquals("com.tacz.guns.api.item.IAmmo", TaczGunBridge.CLASS_I_AMMO);
        assertEquals("com.tacz.guns.api.item.IAmmoBox", TaczGunBridge.CLASS_I_AMMO_BOX);
        assertEquals("com.tacz.guns.api.entity.IGunOperator", TaczGunBridge.CLASS_GUN_OPERATOR);
        assertEquals("com.tacz.guns.api.entity.ReloadState", TaczGunBridge.CLASS_RELOAD_STATE);
        assertEquals("com.tacz.guns.api.entity.ReloadState$StateType", TaczGunBridge.CLASS_RELOAD_STATE_TYPE,
                "嵌套枚举的二进制名必须用 $ 分隔，写成 . 会解析失败");
    }

    @Test
    void gunMethodNamesMatchUpstream() {
        assertEquals("getIGunOrNull", TaczGunBridge.M_GET_I_GUN_OR_NULL);
        assertEquals("getCurrentAmmoCount", TaczGunBridge.M_CURRENT_AMMO);
        assertEquals("hasBulletInBarrel", TaczGunBridge.M_HAS_BULLET_IN_BARREL);
        assertEquals("useDummyAmmo", TaczGunBridge.M_USE_DUMMY_AMMO);
        assertEquals("getDummyAmmoAmount", TaczGunBridge.M_DUMMY_AMMO_AMOUNT);
        assertEquals("useInventoryAmmo", TaczGunBridge.M_USE_INVENTORY_AMMO);
    }

    /** 按 ID 造枪与写虚拟备弹这条链路：拼错同样不报错，只会让玩家出生时空手。 */
    @Test
    void loadoutMethodNamesMatchUpstream() {
        assertEquals("getGunId", TaczGunBridge.M_GET_GUN_ID);
        assertEquals("setDummyAmmoAmount", TaczGunBridge.M_SET_DUMMY_AMMO_AMOUNT,
                "TaCZ 的写入方法是 setDummyAmmoAmount，读取才叫 getDummyAmmoAmount");
        assertEquals("getCommonGunIndex", TaczGunBridge.M_GET_COMMON_GUN_INDEX,
                "服务端要用 Common 索引，Client 索引在专用服务端上永远是空的");
    }

    /**
     * 造枪走 TaCZ 自己的 Builder。这些名字拼错的后果不是发不出枪，而是发出一把
     * 弹匣为 0、射击模式 UNKNOWN 的"哑枪"——物品非空，调用方的失败告警不会触发。
     */
    @Test
    void gunBuilderNamesMatchUpstream() {
        assertEquals("com.tacz.guns.api.item.builder.GunItemBuilder", TaczGunBridge.CLASS_GUN_ITEM_BUILDER);
        assertEquals("com.tacz.guns.resource.index.CommonGunIndex", TaczGunBridge.CLASS_COMMON_GUN_INDEX);
        assertEquals("com.tacz.guns.api.item.gun.FireMode", TaczGunBridge.CLASS_FIRE_MODE);
        assertEquals("create", TaczGunBridge.M_BUILDER_CREATE);
        assertEquals("setId", TaczGunBridge.M_BUILDER_SET_ID);
        assertEquals("setFireMode", TaczGunBridge.M_BUILDER_SET_FIRE_MODE);
        assertEquals("setAmmoCount", TaczGunBridge.M_BUILDER_SET_AMMO_COUNT);
        assertEquals("setAmmoInBarrel", TaczGunBridge.M_BUILDER_SET_AMMO_IN_BARREL);
        assertEquals("setHeatData", TaczGunBridge.M_BUILDER_SET_HEAT_DATA);
        assertEquals("build", TaczGunBridge.M_BUILDER_BUILD,
                "必须是 build（未加载的枪械 ID 会返回 EMPTY），不是跳过校验的 forceBuild");
        assertEquals("getFireModeSet", TaczGunBridge.M_GET_FIRE_MODE_SET);
        assertEquals("getAmmoAmount", TaczGunBridge.M_GET_AMMO_AMOUNT);
        assertEquals("hasHeatData", TaczGunBridge.M_HAS_HEAT_DATA);
        assertEquals("getName", TaczGunBridge.M_GET_NAME,
                "枪名来自 ClientGunIndex.getName()；物品 hover 名是共用的 item.tacz.modern_kinetic_gun");
    }

    @Test
    void ammoMethodNamesMatchUpstream() {
        assertEquals("isAmmoOfGun", TaczGunBridge.M_IS_AMMO_OF_GUN);
        assertEquals("isAmmoBoxOfGun", TaczGunBridge.M_IS_AMMO_BOX_OF_GUN);
        assertEquals("getAmmoCount", TaczGunBridge.M_BOX_AMMO_COUNT);
        assertEquals("isCreative", TaczGunBridge.M_BOX_IS_CREATIVE);
        assertEquals("isAllTypeCreative", TaczGunBridge.M_BOX_IS_ALL_TYPE_CREATIVE);
    }

    @Test
    void reloadMethodNamesMatchUpstream() {
        assertEquals("fromLivingEntity", TaczGunBridge.M_FROM_LIVING_ENTITY);
        assertEquals("getSynReloadState", TaczGunBridge.M_GET_SYN_RELOAD_STATE,
                "TaCZ 的拼写是 getSynReloadState(Syn)，不是 getSyncReloadState");
        assertEquals("getStateType", TaczGunBridge.M_GET_STATE_TYPE);
        assertEquals("getCountDown", TaczGunBridge.M_GET_COUNT_DOWN);
        assertEquals("isReloading", TaczGunBridge.M_IS_RELOADING);
    }

    /**
     * null 入参来自"玩家尚未加载/手持为空"的真实时序。此处不用 {@code ItemStack.EMPTY}：
     * 触碰它会拉起 MC 注册表初始化，纯 JUnit 环境需要完整 Bootstrap（还得先设游戏版本），
     * 而空判分支 {@code stack == null || stack.isEmpty()} 用 null 已能覆盖。
     */
    @Test
    void nullInputsDegradeSafely() {
        assertDoesNotThrow(() -> {
            assertFalse(TaczGunBridge.isGun(null));
            assertEquals(-1, TaczGunBridge.currentAmmo(null), "无枪时弹匣数约定返回 -1");
            assertFalse(TaczGunBridge.isReloading(null));
            assertEquals(-1L, TaczGunBridge.reloadCountDownMs(null), "非换弹状态约定返回 -1");
        });
    }

    @Test
    void dynamicGunNbtKeysMatchUpstream() {
        // 配件快照剥离的动态状态键：来自 TaCZ 1.1.8 GunItemDataAccessor 的 NBT 常量名。
        // 拼错不会报错，只会让快照把弹药/热量状态也带进配装——出生枪不再满状态。
        assertArrayEquals(new String[]{
                        "GunCurrentAmmoCount", "HasBulletInBarrel", "HeatAmount", "OverHeated",
                        "DummyAmmo", "MaxDummyAmmo", "GunLevelExp"},
                TaczGunBridge.GUN_DYNAMIC_NBT_KEYS);
    }
}
