package org.shee33.act0.battlefield.bot.mc;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import org.shee33.act0.battlefield.bot.PathCursor;

import javax.annotation.Nullable;
import java.util.Objects;

/**
 * 单个 AI 士兵的导航器：用影子 Mob 复用原版寻路，把结果交给 {@link PathCursor} 跟随。
 *
 * <p>影子 Mob 与导航实例按需惰性创建，并在 bot 换维度时重建——{@code GroundPathNavigation}
 * 绑定了创建时的 {@code Level}，跨维度复用会算出另一个世界里的路径。
 */
public final class BotNavigator {

    /**
     * 节点抵达判定半径（格）。
     *
     * <p>原版路径节点位于方块中心，而玩家碰撞箱宽 0.6 格。取 0.8 使 bot 无需精确压到中心即可
     * 推进到下一节点——要求过严会让它在每个节点前反复微调，观感是一路抽搐。
     */
    public static final double NODE_ARRIVE_RADIUS = 0.8D;

    private final BotPlayer bot;

    @Nullable
    private ShadowMob shadow;

    @Nullable
    private GroundPathNavigation navigation;

    @Nullable
    private ServerLevel boundLevel;

    public BotNavigator(BotPlayer bot) {
        this.bot = Objects.requireNonNull(bot, "bot");
    }

    /**
     * 计算一条通往目标方块的路径，返回可跟随的节点游标；无路可走返回 {@code null}。
     *
     * <p>算路前把影子 Mob 同步到 bot 当前位置——路径必须从 bot 所在处起算，
     * 否则第一段会把 bot 往影子 Mob 上次停留的地方拽。
     */
    @Nullable
    public PathCursor computePath(BlockPos goal) {
        GroundPathNavigation nav = navigation();
        ShadowMob mob = this.shadow;
        if (nav == null || mob == null) {
            return null;
        }
        mob.moveTo(bot.getX(), bot.getY(), bot.getZ(), bot.getYRot(), bot.getXRot());
        // 必须手动置为"在地面上"：GroundPathNavigation.canUpdatePath() 以 mob.onGround() 为门控，
        // 而影子 Mob 从不被 tick，该标志永远不会自行变真——不置则 createPath 一律静默返回 null，
        // bot 表现为收到指令后完全不动。
        mob.setOnGround(true);

        Path path = nav.createPath(goal, 1);
        if (path == null || path.getNodeCount() == 0) {
            return null;
        }
        int count = path.getNodeCount();
        double[] xs = new double[count];
        double[] ys = new double[count];
        double[] zs = new double[count];
        for (int i = 0; i < count; i++) {
            Node node = path.getNode(i);
            // 用节点中心而非角点：原版节点坐标是方块整数坐标，直接当目标会让 bot 贴着方块边缘走。
            xs[i] = node.x + 0.5D;
            ys[i] = node.y;
            zs[i] = node.z + 0.5D;
        }
        return new PathCursor(xs, ys, zs, NODE_ARRIVE_RADIUS, path.canReach());
    }

    /** 释放影子 Mob（bot 撤走时调用），避免其随 bot 一起泄漏。 */
    public void release() {
        shadow = null;
        navigation = null;
        boundLevel = null;
    }

    @Nullable
    private GroundPathNavigation navigation() {
        ServerLevel level = bot.serverLevel();
        if (navigation != null && boundLevel == level) {
            return navigation;
        }
        shadow = new ShadowMob(level);
        navigation = new GroundPathNavigation(shadow, level);
        navigation.setCanOpenDoors(true);
        navigation.setCanPassDoors(true);
        boundLevel = level;
        return navigation;
    }
}
