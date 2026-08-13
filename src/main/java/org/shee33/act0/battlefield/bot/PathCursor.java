package org.shee33.act0.battlefield.bot;

/**
 * 路径跟随游标：在一串节点上推进，并判定卡死。MC-free 纯逻辑，可单测。
 *
 * <p>刻意不持有任何 MC 类型（节点以三个 {@code double[]} 传入），因为"该瞄哪个节点、什么时候
 * 算卡住"是路径跟随里最需要反复调整的部分，抽成纯逻辑后可以直接用单测覆盖各种走位，
 * 不必开服务器跑图。
 *
 * <p>承载两件事，各自对应一种典型的"AI 味"：
 * <ul>
 *   <li><b>前瞻跳过</b>——若后面的节点已经在抵达半径内，直接跳到它之后。原版路径在转角处会
 *       密集堆节点，逐个精确踩点会让 bot 在拐角处走出锯齿。</li>
 *   <li><b>卡死计数</b>——距离当前节点不再缩短即累计。撞在几何缝隙里的 bot 必须能被察觉，
 *       否则它会永远顶着墙走。</li>
 * </ul>
 */
public final class PathCursor {

    /**
     * 判定"有进展"所需的最小距离缩短量（格²，比较的是平方距离）。
     *
     * <p>取一个很小的正值而非 0：浮点抖动与贴墙滑行都会让距离产生微不足道的变化，
     * 用 0 作阈值会把"其实卡住了"误判为仍在前进。
     */
    public static final double PROGRESS_EPSILON = 1.0e-4D;

    /** 允许把节点视为"已抵达"的垂直容差（格）。跨一级台阶时不应因高度差而拒绝推进。 */
    public static final double VERTICAL_TOLERANCE = 1.5D;

    private final double[] xs;
    private final double[] ys;
    private final double[] zs;
    private final double arriveRadius;
    private final boolean reachesGoal;

    private int index;
    private int ticksWithoutProgress;
    private double lastDistanceSq = Double.MAX_VALUE;

    /**
     * @param xs           各节点 X（节点中心）
     * @param ys           各节点 Y
     * @param zs           各节点 Z
     * @param arriveRadius 节点抵达判定半径（水平，格）
     * @param reachesGoal  原版是否判定该路径能真正抵达目标（否则为"尽力靠近"的部分路径）
     */
    public PathCursor(double[] xs, double[] ys, double[] zs, double arriveRadius, boolean reachesGoal) {
        if (xs.length != ys.length || ys.length != zs.length) {
            throw new IllegalArgumentException("节点三轴数组长度必须一致");
        }
        if (arriveRadius <= 0.0D) {
            throw new IllegalArgumentException("arriveRadius must be > 0, got " + arriveRadius);
        }
        this.xs = xs.clone();
        this.ys = ys.clone();
        this.zs = zs.clone();
        this.arriveRadius = arriveRadius;
        this.reachesGoal = reachesGoal;
    }

    /**
     * 按当前位置推进游标，并更新卡死计数。
     *
     * @return 是否已走完全部节点
     */
    public boolean advance(double x, double y, double z) {
        if (isFinished()) {
            return true;
        }
        // 前瞻：从最远的已抵达节点之后继续，跳过转角处密集堆叠的中间节点。
        int reached = -1;
        for (int i = index; i < xs.length; i++) {
            if (withinArrival(i, x, y, z)) {
                reached = i;
            }
        }
        if (reached >= 0) {
            index = reached + 1;
            resetProgress();
            return isFinished();
        }

        double distSq = horizontalDistanceSq(index, x, z);
        if (distSq < lastDistanceSq - PROGRESS_EPSILON) {
            lastDistanceSq = distSq;
            ticksWithoutProgress = 0;
        } else {
            ticksWithoutProgress++;
        }
        return false;
    }

    /** 距离当前节点已连续多少 tick 没有缩短。 */
    public int ticksWithoutProgress() {
        return ticksWithoutProgress;
    }

    /** 清空进展记录（重规划路径或主动跳节点后调用）。 */
    public void resetProgress() {
        ticksWithoutProgress = 0;
        lastDistanceSq = Double.MAX_VALUE;
    }

    /** 强制退回上一个节点，用于卡死恢复。 */
    public void stepBack() {
        if (index > 0) {
            index--;
        }
        resetProgress();
    }

    public boolean isFinished() {
        return index >= xs.length;
    }

    /** 当前应走向的节点 X；已走完时返回最后一个节点。 */
    public double targetX() {
        return xs[clampedIndex()];
    }

    public double targetY() {
        return ys[clampedIndex()];
    }

    public double targetZ() {
        return zs[clampedIndex()];
    }

    public int index() {
        return index;
    }

    public int nodeCount() {
        return xs.length;
    }

    /** 原版是否判定该路径能真正抵达目标；{@code false} 表示这是"尽力靠近"的部分路径。 */
    public boolean reachesGoal() {
        return reachesGoal;
    }

    private int clampedIndex() {
        return Math.min(index, xs.length - 1);
    }

    private boolean withinArrival(int i, double x, double y, double z) {
        return Math.abs(ys[i] - y) <= VERTICAL_TOLERANCE
                && horizontalDistanceSq(i, x, z) <= arriveRadius * arriveRadius;
    }

    private double horizontalDistanceSq(int i, double x, double z) {
        double dx = xs[i] - x;
        double dz = zs[i] - z;
        return dx * dx + dz * dz;
    }
}
