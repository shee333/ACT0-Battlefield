package org.shee33.act0.battlefield.integration;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对着<b>真实 TaCZ jar</b> 验证 {@link TaczGunBridge} 的每一个反射目标都能解析。
 *
 * <p>这是这套集成唯一的硬证据。反射用错类名/方法名/参数类型不会抛异常，只会让 HUD 对应
 * 功能永远返回默认值——弹药栏空着、换弹条不动，日志里一个字都没有。{@code TaczGunBridgeTest}
 * 里的名称常量断言只能防止"改动"，防不了"一开始就写错"；只有把真 jar 挂上去做一次实际解析
 * 才能证明名字确实对得上。
 *
 * <p>jar 由 {@code build.gradle} 条件挂载（存在才挂）。不存在时本测试整体跳过，
 * 因此在没有该 jar 的机器上克隆本仓库依然能跑通全部测试。
 */
class TaczGunBridgeRealJarTest {

    /**
     * 与 {@link TaczGunBridge} 内部<b>静态解析</b>的反射目标字段一一对应。
     *
     * <p>刻意不含 {@code commonIndexGetGunData}：对 {@code CommonGunIndex} 做方法解析要链接
     * TaCZ 自带的 luaj，而本仓库只挂了 TaCZ 主 jar，没有它的第三方依赖。该目标因此改为调用时
     * 惰性解析，无法在这里验证。
     */
    private static final String[] REFLECTION_FIELDS = {
            "getIGunOrNull",
            "gunGetCurrentAmmoCount",
            "gunHasBulletInBarrel",
            "gunUseDummyAmmo",
            "gunGetDummyAmmoAmount",
            "gunUseInventoryAmmo",
            "ammoIsAmmoOfGun",
            "boxIsAmmoBoxOfGun",
            "boxGetAmmoCount",
            "boxIsCreative",
            "boxIsAllTypeCreative",
            "operatorFromLivingEntity",
            "operatorGetSynReloadState",
            "reloadStateGetStateType",
            "reloadStateGetCountDown",
            "stateTypeIsReloading",
            "gunGetGunId",
            "gunSetDummyAmmoAmount",
            "timelessGetCommonGunIndex",
            "timelessGetClientGunIndex",
            "builderCreate",
            "builderSetId",
            "builderSetFireMode",
            "builderSetAmmoCount",
            "builderSetAmmoInBarrel",
            "builderSetHeatData",
            "builderBuild",
            "gunDataGetFireModeSet",
            "gunDataGetAmmoAmount",
            "gunDataHasHeatData",
            "clientIndexGetGunData",
            "gunDataGetBolt",
            "openBoltConstant",
    };

    @Test
    void everyReflectionTargetResolvesAgainstRealJar() throws Exception {
        Assumptions.assumeTrue(TaczGunBridge.isAvailable(),
                "测试 classpath 上没有 TaCZ jar，跳过真实解析验证");

        for (String name : REFLECTION_FIELDS) {
            Field field = TaczGunBridge.class.getDeclaredField(name);
            field.setAccessible(true);
            assertNotNull(field.get(null),
                    "反射目标未能解析: " + name + " —— 类名/方法名/参数类型与实际 TaCZ 不符");
        }
    }

    /** 桥识别到 TaCZ 后，空输入仍必须走安全路径而不是因为"可用"就放松防御。 */
    @Test
    void staysNullSafeEvenWhenTaczPresent() {
        Assumptions.assumeTrue(TaczGunBridge.isAvailable(), "无 TaCZ jar，跳过");
        assertTrue(TaczGunBridge.currentAmmo(null) < 0);
        assertTrue(TaczGunBridge.reserveAmmo(null, null) < 0);
        assertTrue(TaczGunBridge.reloadCountDownMs(null) < 0);
    }
}
