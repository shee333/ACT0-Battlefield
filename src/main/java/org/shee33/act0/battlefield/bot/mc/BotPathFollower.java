package org.shee33.act0.battlefield.bot.mc;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.Vec3;
import org.shee33.act0.battlefield.bot.PathCursor;

import javax.annotation.Nullable;

/**
 * 沿路径走到一个坐标的<b>机制</b>层：持有寻路器与路径游标，只回答"这一 tick 往哪迈"。
 *
 * <p>与 {@link BotLocomotion} 的分工是机制与策略：本类不关心为什么要去那里
 * （追敌、巡逻、还是手动航点），{@link BotLocomotion} 不关心怎么绕开障碍。
 * 拆开的直接原因是后者已同时承担交火机动、行军、巡逻、扫视四类决策，
 * 再叠上寻路细节会超出单文件的可读上限。
 */
final class BotPathFollower {

    /**
     * 行进时每 tick 最大转向角（度）。
     *
     * <p>与瞄准的转向速率刻意分开：那是交火时枪口追踪目标的速率、属难度参数；
     * 这是行军时身体的转向速率，与强弱无关，四档共用一个值即可。
     */
    private static final float MOVE_TURN_RATE = 12.0F;

    /** 抵达判定半径（格）。小于玩家碰撞箱宽度会导致到点后反复微调抖动。 */
    private static final double ARRIVE_RADIUS = 0.6D;

    /**
     * 路径重规划间隔（tick）。
     *
     * <p>2 秒重算一次：原版 {@code shouldRecomputePath} 极少触发，而追击活动目标时
     * 目标每时每刻都在移动。间隔再短会让寻路开销显著上升，而 {@link PathCursor} 的前瞻
     * 与卡死恢复已能吸收这个粒度内的偏差。
     */
    private static final int PATH_REPLAN_INTERVAL_TICKS = 40;

    /**
     * 判定卡死的无进展 tick 数。
     *
     * <p>1.5 秒：足够长以容纳绕过障碍时的正常贴墙滑行与起跳，又足够短以免玩家看着 bot 顶墙发呆。
     */
    private static final int STUCK_TICKS = 30;

    /** 一 tick 路径跟随的结果。 */
    enum Outcome {
        /** 正在沿路径前进。 */
        MOVING,
        /** 已抵达目标点。 */
        ARRIVED,
        /** 无路可走或卡死后正在重规划。 */
        BLOCKED
    }

    private final BotPlayer bot;

    @Nullable
    private BotNavigator navigator;

    @Nullable
    private PathCursor cursor;

    BotPathFollower(BotPlayer bot) {
        this.bot = bot;
    }

    /** 丢弃当前路径，下次跟随会重新规划（目标变更时调用）。 */
    void reset() {
        cursor = null;
    }

    /**
     * 沿路径走向目标点一 tick。
     *
     * <p>三件事必须一起做，缺一个都会让 bot 看起来是坏的：周期重规划（原版的
     * {@code shouldRecomputePath} 极少触发，追活动目标时路径立刻过期）、卡死恢复
     * （撞在几何缝隙里只会继续顶墙）、以及无路可走时如实上报而非死抱目标。
     *
     * @param aimOwnsFacing 交火中，朝向由瞄准独占，位移只能走侧移分解
     * @param sprint        是否允许疾跑；交火中必须为 {@code false}（TaCZ 疾跑拒绝开火）
     */
    Outcome follow(MinecraftServer server, Vec3 destination, boolean aimOwnsFacing, boolean sprint) {
        boolean due = cursor == null
                || (server.getTickCount() + bot.getId()) % PATH_REPLAN_INTERVAL_TICKS == 0;
        if (due) {
            PathCursor fresh = navigator().computePath(BlockPos.containing(destination));
            if (fresh == null) {
                return Outcome.BLOCKED;
            }
            cursor = fresh;
        }
        if (cursor.advance(bot.getX(), bot.getY(), bot.getZ())) {
            return Outcome.ARRIVED;
        }
        if (cursor.ticksWithoutProgress() > STUCK_TICKS) {
            cursor.stepBack();
            cursor = null;
            return Outcome.BLOCKED;
        }
        if (aimOwnsFacing) {
            BotMovementDriver.driveRelative(bot,
                    cursor.targetX() - bot.getX(), cursor.targetZ() - bot.getZ(), sprint);
        } else {
            BotMovementDriver.driveTo(bot, cursor.targetX(), cursor.targetY(), cursor.targetZ(),
                    MOVE_TURN_RATE, ARRIVE_RADIUS, sprint);
        }
        return Outcome.MOVING;
    }

    private BotNavigator navigator() {
        if (navigator == null) {
            navigator = new BotNavigator(bot);
        }
        return navigator;
    }

    /** bot 撤走时释放导航资源，避免影子 Mob 随之泄漏。 */
    void release() {
        cursor = null;
        if (navigator != null) {
            navigator.release();
            navigator = null;
        }
    }
}
