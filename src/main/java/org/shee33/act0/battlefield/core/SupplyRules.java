package org.shee33.act0.battlefield.core;

/**
 * 部署型补给物（弹药箱 / 医疗箱）的纯规则层：范围判定、存活期、补弹量、医疗三阶段状态机。
 *
 * <p>刻意做成 MC-free：这些是最容易写错又最难在游戏里复现的部分——治疗要"延迟 1.5 秒后在 3 秒内
 * 回满、中途受伤即中止"，涉及三个时间边界和一个受伤检测，靠进服务器手动站桩挨打去验证既慢又不
 * 可靠。放在 {@code core/} 就能用 JUnit 把每个边界钉死。
 */
public final class SupplyRules {

    /** 补给范围（格）。地面提示圆的半径与此一致，玩家看到的圆就是真实生效范围。 */
    public static final double RADIUS = 3.0D;

    /** 部署物存活时长（tick）：30 秒。 */
    public static final int LIFETIME_TICKS = 600;

    /** 弹药箱单次补给量（发）。 */
    public static final int AMMO_GRANT = 60;

    /** 医疗箱起效延迟（tick）：1.5 秒。 */
    public static final int MEDIC_DELAY_TICKS = 30;

    /** 医疗箱回血时长（tick）：3 秒内回满。 */
    public static final int MEDIC_HEAL_TICKS = 60;

    /** 医疗箱对同一玩家的重触发间隔（tick）：10 秒。 */
    public static final int MEDIC_RETRIGGER_TICKS = 200;

    /** 医疗针救援提速倍率。 */
    public static final int SYRINGE_SPEED_MULTIPLIER = 3;

    /** 医疗针完成一次救援后的冷却（tick）：2 秒。 */
    public static final int SYRINGE_COOLDOWN_TICKS = 40;

    private SupplyRules() {
    }

    /**
     * 是否处于补给范围内。
     *
     * <p>水平方向按圆判定、垂直方向单独限制在同样的距离内，而不是简单的球形距离：地面提示圆是
     * 一个<b>平面圆</b>，玩家据此判断"我站进去了没有"。若用球形判定，站在箱子正上方 2.9 格的
     * 屋顶上（水平距离 0）也会被补给，但那个位置在圆外，玩家会觉得莫名其妙；反过来站在圆边缘
     * 却因为高了半格被判出界同样难以理解。
     */
    public static boolean inRange(double dx, double dy, double dz, double radius) {
        return dx * dx + dz * dz <= radius * radius && Math.abs(dy) <= radius;
    }

    /** 部署物是否已到期。 */
    public static boolean expired(long now, long deployTick, int lifetimeTicks) {
        return now - deployTick >= lifetimeTicks;
    }

    /** 剩余存活 tick，最小为 0。 */
    public static int remainingTicks(long now, long deployTick, int lifetimeTicks) {
        long left = deployTick + lifetimeTicks - now;
        if (left < 0) {
            return 0;
        }
        return left > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) left;
    }

    /**
     * 补给后的备弹量：在当前基础上增加 {@code grant} 发，但不超过该枪配装记录的容量上限。
     *
     * <p>是"增加 60 发"而非"补满"：补满会让弹药箱变成无限弹药点，一个工程兵就能让整队永远不缺
     * 弹，据点争夺的补给压力直接消失。上限取配装记录的 {@code cap}，避免绕过配装设定的携弹量。
     *
     * @param cap 配装记录的备弹上限；{@code <= 0} 表示无记录，此时不设上限
     */
    public static int refilledAmmo(int current, int cap, int grant) {
        int base = Math.max(0, current);
        int granted = base + Math.max(0, grant);
        if (cap > 0) {
            // 已经超过上限（例如管理员手动给弹）时不倒扣，只是不再增加。
            return Math.min(granted, Math.max(cap, base));
        }
        return granted;
    }

    /** 医疗箱治疗阶段。 */
    public enum HealPhase {
        /** 已触发但仍在 1.5 秒延迟内，尚未开始回血。 */
        DELAY,
        /** 正在回血。 */
        HEALING,
        /** 本次治疗已完成。 */
        DONE
    }

    /** 按治疗已进行的 tick 数判断当前阶段。 */
    public static HealPhase healPhase(long elapsedTicks, int delayTicks, int healTicks) {
        if (elapsedTicks < delayTicks) {
            return HealPhase.DELAY;
        }
        return elapsedTicks < (long) delayTicks + healTicks ? HealPhase.HEALING : HealPhase.DONE;
    }

    /**
     * 治疗进度 0..1：延迟阶段恒为 0，回血阶段线性推进，完成后为 1。
     *
     * <p>调用方按 {@code from + (max - from) * progress} 设定血量即可得到"3 秒内平滑回满"，
     * 且天然幂等——任意一 tick 掉帧或漏算都不会累积误差，因为进度只由经过时间决定。
     */
    public static double healProgress(long elapsedTicks, int delayTicks, int healTicks) {
        if (elapsedTicks < delayTicks) {
            return 0.0D;
        }
        if (healTicks <= 0) {
            return 1.0D;
        }
        double p = (double) (elapsedTicks - delayTicks) / healTicks;
        return p < 0.0D ? 0.0D : Math.min(1.0D, p);
    }

    /** 由治疗进度推出本 tick 应有的血量。 */
    public static float healthAt(float healFrom, float maxHealth, double progress) {
        if (progress <= 0.0D) {
            return healFrom;
        }
        if (progress >= 1.0D) {
            return maxHealth;
        }
        return (float) (healFrom + (maxHealth - healFrom) * progress);
    }

    /**
     * 是否应判定为"治疗中受到伤害"。
     *
     * <p>比较实际血量与上一 tick 我们主动设定的血量：治疗期间血量由本系统独占写入，任何低于
     * 预期的差值都只能来自外部伤害。用 {@code epsilon} 吸收浮点误差，避免把 {@code setHealth}
     * 的舍入误差当成挨了一枪而误中止治疗。
     */
    public static boolean damagedDuringHeal(float actualHealth, float expectedHealth, float epsilon) {
        return actualHealth < expectedHealth - epsilon;
    }

    /** 医疗箱对某玩家是否仍在重触发冷却中。 */
    public static boolean onRetriggerCooldown(long now, long lastTriggerTick, int retriggerTicks) {
        return now - lastTriggerTick < retriggerTicks;
    }

    /** 按是否手持医疗针换算本次救援所需 tick 数。 */
    public static int reviveDuration(int baseDurationTicks, boolean withSyringe) {
        if (!withSyringe) {
            return Math.max(1, baseDurationTicks);
        }
        return Math.max(1, baseDurationTicks / SYRINGE_SPEED_MULTIPLIER);
    }
}
