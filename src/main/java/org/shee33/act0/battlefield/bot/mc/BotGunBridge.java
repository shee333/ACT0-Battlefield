package org.shee33.act0.battlefield.bot.mc;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Method;
import java.util.function.Supplier;

/**
 * 让 AI 士兵通过 TaCZ 自身的开火管线射击的<b>反射软依赖</b>桥。
 *
 * <p><b>为什么必须走 TaCZ 而不是自建 raycast。</b>本项目自己不拥有任何枪械表现——
 * 枪声、枪口火光、曳光弹、抛壳、后坐力动画、弹着点效果、换弹动画，全部来自 TaCZ；
 * {@code network/} 包里没有任何战斗反馈包。若 bot 走自建弹道，它开枪会<b>完全静默</b>：
 * 玩家只看到血条掉、屏幕变红，完全无法判断子弹来自何处。而"被打时知道从哪被打"在战地／COD
 * 里不是装饰，是核心玩法本身。走 TaCZ 则这一整套与真人开枪完全同源，且伤害、爆头、距离衰减
 * 共用同一份数值，不产生"bot 一套、玩家一套"的平衡双源。
 *
 * <p><b>为什么假玩家能驱动它。</b>{@code IGunOperator.fromLivingEntity} 的实现是一次
 * {@code checkcast}——TaCZ 用 {@code common.LivingEntityMixin} 让 {@code LivingEntity} 本身实现
 * 该接口。{@link BotPlayer} 继承自 {@code ServerPlayer}，因此天然满足强转。
 *
 * <p><b>为什么用 2 参数的 {@code shoot} 重载。</b>TaCZ 有一项网络时延校验：
 * {@code alpha = now - baseTimestamp - timestamp} 必须落在窗口内，否则返回 {@code NETWORK_FAIL}。
 * 2 参数重载内部自行取 {@code System.currentTimeMillis() - baseTimestamp} 作为时间戳，
 * 使 {@code alpha} 恒为 0，校验必过——无需我们伪造任何客户端时间戳。
 *
 * <p>反射约定沿用 {@link org.shee33.act0.arcade.integration.TaczBridge}：静态解析并缓存、
 * 全部方法空安全、任何反射异常一律吞掉并返回保守值，绝不向上抛出。
 *
 * <p>所有方法必须在服务器主线程调用。
 */
public final class BotGunBridge {

    /** {@code shoot} 返回值：TaCZ 不可用或反射失败时的占位。 */
    public static final String RESULT_UNAVAILABLE = "TACZ_UNAVAILABLE";

    /** {@code shoot} 成功时 TaCZ 返回的枚举常量名。 */
    public static final String RESULT_SUCCESS = "SUCCESS";

    private static final boolean AVAILABLE;

    private static Class<?> gunItemInterface;
    private static Method fromLivingEntity;
    private static Method initialData;
    private static Method draw;
    private static Method shoot;
    private static Method getSynShootCoolDown;
    private static Method getSynReloadState;
    private static Method getSynIsBolting;
    private static Method bolt;
    private static Method reload;
    private static Method aim;
    private static Method reloadStateGetStateType;
    private static Method stateTypeIsReloading;

    static {
        boolean ok = false;
        try {
            Class<?> operator = Class.forName("com.tacz.guns.api.entity.IGunOperator");
            Class<?> reloadState = Class.forName("com.tacz.guns.api.entity.ReloadState");
            Class<?> stateType = Class.forName("com.tacz.guns.api.entity.ReloadState$StateType");
            gunItemInterface = Class.forName("com.tacz.guns.api.item.IGun");

            fromLivingEntity = operator.getMethod("fromLivingEntity",
                    net.minecraft.world.entity.LivingEntity.class);
            initialData = operator.getMethod("initialData");
            draw = operator.getMethod("draw", Supplier.class);
            shoot = operator.getMethod("shoot", Supplier.class, Supplier.class);
            getSynShootCoolDown = operator.getMethod("getSynShootCoolDown");
            getSynReloadState = operator.getMethod("getSynReloadState");
            getSynIsBolting = operator.getMethod("getSynIsBolting");
            bolt = operator.getMethod("bolt");
            reload = operator.getMethod("reload");
            aim = operator.getMethod("aim", boolean.class);

            reloadStateGetStateType = reloadState.getMethod("getStateType");
            stateTypeIsReloading = stateType.getMethod("isReloading");
            ok = true;
        } catch (Throwable ignored) {
            ok = false;
        }
        AVAILABLE = ok;
    }

    private BotGunBridge() {
    }

    /** 运行时是否存在 TaCZ 开火 API。不可用时上层应禁用 bot 开火而非报错。 */
    public static boolean isAvailable() {
        return AVAILABLE;
    }

    /**
     * 让 bot"持枪就绪"。
     *
     * <p>必须在开火前调用一次，否则 {@code shoot} 会返回 {@code NOT_DRAW}——
     * TaCZ 需要知道当前持有哪把枪。传入的是取主手物品的 supplier 而非快照，
     * 使换枪后无需重新登记。
     */
    public static boolean drawMainHand(ServerPlayer bot) {
        Object operator = operatorOf(bot);
        // 主手不是枪时必须在 initialData 之前退出。initialData 会把 TaCZ 的 currentGunItem 设成
        // 一个恒非空的"取主手物品"supplier，此后 TaCZ 再也无法报出 NOT_DRAW——空手的 bot 就此
        // 从"没持枪"这个可自愈、可复现的状态，掉进只在首次开火报一行 NOT_GUN、随后被去重永久
        // 静音的死角。如实失败才能让上层把"这个 bot 根本没拿到枪"讲出来。
        if (operator == null || !isGunInMainHand(bot)) {
            return false;
        }
        try {
            initialData.invoke(operator);
            Supplier<ItemStack> mainHand = bot::getMainHandItem;
            draw.invoke(operator, mainHand);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /** 主手当前是否握着一把 TaCZ 枪械。 */
    public static boolean isGunInMainHand(ServerPlayer bot) {
        if (!AVAILABLE || bot == null) {
            return false;
        }
        ItemStack stack = bot.getMainHandItem();
        return !stack.isEmpty() && gunItemInterface.isInstance(stack.getItem());
    }

    /**
     * 按给定朝向开一枪，返回 TaCZ 的结果枚举名。
     *
     * <p>结果名直接透出而不折叠成布尔，因为 {@code COOL_DOWN} / {@code NO_AMMO} /
     * {@code IS_RELOADING} / {@code NOT_DRAW} / {@code NETWORK_FAIL} 对调用方是完全不同的语义
     * ——节奏控制与调试都依赖区分它们。
     *
     * @param pitch 俯仰角（负值为抬头）
     * @param yaw   偏航角
     * @return TaCZ {@code ShootResult} 的常量名；不可用时为 {@link #RESULT_UNAVAILABLE}
     */
    public static String shoot(ServerPlayer bot, float pitch, float yaw) {
        Object operator = operatorOf(bot);
        if (operator == null) {
            return RESULT_UNAVAILABLE;
        }
        try {
            Supplier<Float> pitchSupplier = () -> pitch;
            Supplier<Float> yawSupplier = () -> yaw;
            Object result = shoot.invoke(operator, pitchSupplier, yawSupplier);
            return result instanceof Enum<?> e ? e.name() : String.valueOf(result);
        } catch (Throwable e) {
            return RESULT_UNAVAILABLE;
        }
    }

    /** 剩余射击冷却（毫秒）；不可用或异常时返回 {@code 0}（视为可射击，由 TaCZ 自身再兜底）。 */
    public static long shootCoolDownMillis(ServerPlayer bot) {
        return longOf(bot, getSynShootCoolDown);
    }

    /** 是否正在换弹。换弹期间不应扣扳机，且这正是留给玩家的战术窗口。 */
    public static boolean isReloading(ServerPlayer bot) {
        Object operator = operatorOf(bot);
        if (operator == null) {
            return false;
        }
        try {
            Object state = getSynReloadState.invoke(operator);
            if (state == null) {
                return false;
            }
            Object type = reloadStateGetStateType.invoke(state);
            return type != null && (boolean) stateTypeIsReloading.invoke(type);
        } catch (Throwable e) {
            return false;
        }
    }

    /** 是否正在上膛（拉栓枪械每发之间的动作）。 */
    public static boolean isBolting(ServerPlayer bot) {
        Object operator = operatorOf(bot);
        if (operator == null) {
            return false;
        }
        try {
            return (boolean) getSynIsBolting.invoke(operator);
        } catch (Throwable e) {
            return false;
        }
    }

    /** 触发上膛。 */
    public static void bolt(ServerPlayer bot) {
        invokeVoid(bot, bolt);
    }

    /** 触发换弹。 */
    public static void reload(ServerPlayer bot) {
        invokeVoid(bot, reload);
    }

    /** 开镜 / 收镜。影响 TaCZ 的散布与移动速度，是 bot"认真交火"与"行军"的姿态区分。 */
    public static void aim(ServerPlayer bot, boolean aiming) {
        Object operator = operatorOf(bot);
        if (operator == null) {
            return;
        }
        try {
            aim.invoke(operator, aiming);
        } catch (Throwable ignored) {
            // 姿态失败不影响开火，静默降级。
        }
    }

    // ---- 内部 ----

    private static Object operatorOf(ServerPlayer bot) {
        if (!AVAILABLE || bot == null) {
            return null;
        }
        try {
            return fromLivingEntity.invoke(null, bot);
        } catch (Throwable e) {
            return null;
        }
    }

    private static long longOf(ServerPlayer bot, Method method) {
        Object operator = operatorOf(bot);
        if (operator == null) {
            return 0L;
        }
        try {
            return (long) method.invoke(operator);
        } catch (Throwable e) {
            return 0L;
        }
    }

    private static void invokeVoid(ServerPlayer bot, Method method) {
        Object operator = operatorOf(bot);
        if (operator == null) {
            return;
        }
        try {
            method.invoke(operator);
        } catch (Throwable ignored) {
            // 与 TaczBridge 一致：集成层异常不得影响主玩法。
        }
    }
}
