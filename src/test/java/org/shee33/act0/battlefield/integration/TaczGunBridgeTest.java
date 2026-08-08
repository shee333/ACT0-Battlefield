package org.shee33.act0.battlefield.integration;

import org.junit.jupiter.api.Test;

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
 * <p><b>二、TaCZ 缺席时优雅降级。</b>测试 classpath 上没有 TaCZ，跑的正是玩家没装 TaCZ 的
 * 分支：桥必须整体不可用、取值返回约定默认值，且绝不抛异常——它在每帧 HUD 渲染路径上。
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

    @Test
    void unavailableWithoutTacz() {
        assertFalse(TaczGunBridge.isAvailable(), "测试 classpath 无 TaCZ，桥应报告不可用");
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
            assertFalse(TaczGunBridge.hasBulletInBarrel(null));
            assertEquals(-1, TaczGunBridge.reserveAmmo(null, null), "无枪时备弹约定返回 -1");
            assertFalse(TaczGunBridge.isReloading(null));
            assertEquals(-1L, TaczGunBridge.reloadCountDownMs(null), "非换弹状态约定返回 -1");
        });
    }
}
