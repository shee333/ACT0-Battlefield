package org.shee33.act0.battlefield.bot.mc;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * 只用来算路径的隐藏 Mob，<b>永不进入世界</b>。
 *
 * <p><b>为什么需要它。</b>原版 {@code PathNavigation} 与 {@code GroundPathNavigation} 的构造器
 * 硬性要求一个 {@code Mob}，而 AI 士兵是 {@code ServerPlayer}。但寻路过程只读取该 Mob 的坐标与
 * 属性——<b>它无需被 tick，也无需加入世界</b>。于是每个 bot 养一个影子 Mob 专门算路径，
 * 算完由假玩家自己走节点。这样我们复用了 {@code WalkNodeEvaluator} 里多年积累的边界处理
 * （跳跃、翻栅栏、开门、活板门、树叶、铁轨、细雪、岩浆），而不必重写一遍。
 *
 * <p><b>三条必须遵守的约束</b>（否则会从"隐藏的计算器"退化成"世界里多出来的怪"）：
 * <ul>
 *   <li>绝不调用 {@code level.addFreshEntity}——它不是实体，是计算器；</li>
 *   <li>{@link #registerGoals()} 置空，不装任何 AI；</li>
 *   <li>{@code setNoAi(true)}，即便被误加入世界也不会自行行动。</li>
 * </ul>
 *
 * <p><b>为什么用 {@code EntityType.ZOMBIE} 作构造令牌。</b>{@code Mob} 的构造器必须要一个已注册的
 * {@code EntityType}（用于取碰撞箱尺寸等），而我们不该为一个纯计算对象新注册实体类型。僵尸的
 * 碰撞箱 0.6×1.95 与玩家的 0.6×1.8 同宽、同样需要 2 格净空，对寻路结果无实质差异；
 * 而由于永不入世界，"它是个僵尸"这件事对任何系统都不可见。
 */
final class ShadowMob extends PathfinderMob {

    /**
     * 寻路预算的驱动值（格）。
     *
     * <p>原版把节点预算算作 {@code floor(FOLLOW_RANGE × 16)}，并用同一属性决定搜索区域半径与
     * 最大路径距离。默认 16 格对街机竞技场偏小；提到 48 格得到 768 个节点与 48 格路径上限，
     * 足以横穿整张竞技场。再往上加会让 {@code PathNavigationRegion} 预取的区块快照迅速变大，
     * 属于可调旋钮而非硬限。
     */
    static final double NAV_RANGE = 48.0D;

    ShadowMob(ServerLevel level) {
        super(EntityType.ZOMBIE, level);
        setNoAi(true);
        AttributeInstance followRange = getAttribute(Attributes.FOLLOW_RANGE);
        if (followRange != null) {
            followRange.setBaseValue(NAV_RANGE);
        }
    }

    @Override
    protected void registerGoals() {
        // 刻意空实现：本对象只为满足寻路 API 的类型要求而存在，不应有任何行为。
    }
}
