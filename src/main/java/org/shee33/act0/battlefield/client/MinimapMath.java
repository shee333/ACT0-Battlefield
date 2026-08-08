package org.shee33.act0.battlefield.client;

/**
 * 小地图的纯数学与配色规则，不依赖任何 Minecraft 类型，可直接 JUnit 单测。
 *
 * <p>小地图采用<b>北朝上固定朝向</b>而非"整图跟随视角旋转"：旋转整张地图会让据点名标签
 * 跟着倾斜到难以辨认，且 {@code GuiGraphics.enableScissor} 的裁剪区域不跟随 PoseStack
 * 变换（见 AGENTS.md 菜单动效规范），旋转后裁剪会错位。因此改为地图固定、玩家箭头旋转，
 * 方向信息同样完整，实现代价却低得多。
 */
public final class MinimapMath {

    // BF2042 扁平配色
    public static final int BG = 0x99101418;
    public static final int BORDER = 0xFF3A3A3A;
    public static final int PLAYER = 0xFFFFFFFF;
    public static final int BLUE = 0xFF4A90D9;
    public static final int RED = 0xFFD94A4A;
    public static final int GREY = 0xFF8C9196;
    public static final int YELLOW = 0xFFFF8C00;
    public static final int LABEL_BG = 0xAA000000;
    /** 存活队友点位。 */
    public static final int SQUAD_ALIVE = 0xFF66CC66;
    /** 倒地队友点位——复用调色板里的警告橙，与"需要救援"的语义一致。 */
    public static final int SQUAD_DOWNED = 0xFFFF8C00;

    private MinimapMath() {
    }

    /**
     * 把 Minecraft 的玩家 yaw 换算成小地图箭头在屏幕上的顺时针旋转角度。
     *
     * <p>MC 的 yaw：0=+Z(南)、90=-X(西)、180=-Z(北)、270=+X(东)。小地图 -Z 朝上，箭头基础
     * 形状指向正上方（即北）。于是"玩家面朝北(yaw=180)"时箭头不该旋转，面东(yaw=270)时应
     * 顺时针转 90°，面南(yaw=0)转 180°，面西(yaw=90)转 270°——即 {@code 角度 = yaw + 180}。
     *
     * @param yaw 玩家 yaw，可为任意实数（MC 不保证归一化到 0~360）
     * @return 归一化到 [0, 360) 的屏幕顺时针旋转角
     */
    public static float screenAngleFor(float yaw) {
        float angle = (yaw + 180f) % 360f;
        return angle < 0f ? angle + 360f : angle;
    }

    /** 世界 X 差值 → 小地图屏幕 X 偏移（+X 在屏幕上向右）。 */
    public static int offsetX(double worldDx, double scale) {
        return (int) Math.round(worldDx * scale);
    }

    /** 世界 Z 差值 → 小地图屏幕 Y 偏移（-Z 为北，在屏幕上向上，故取反）。 */
    public static int offsetY(double worldDz, double scale) {
        return -(int) Math.round(worldDz * scale);
    }

    /** 标记是否落在小地图可视区内（留出边距，避免压在边框上）。 */
    public static boolean withinBounds(int sx, int sy, int mapX, int mapY, int size, int inset) {
        int min = mapX + inset;
        int maxX = mapX + size - inset;
        int minY = mapY + inset;
        int maxY = mapY + size - inset;
        return sx >= min && sx <= maxX && sy >= minY && sy <= maxY;
    }

    /**
     * 征服模式据点配色：颜色相对查看者阵营（自己的据点永远是蓝色）。
     *
     * @param owner     归属 0=中立 1=ALPHA 2=BRAVO
     * @param pressure  争夺压力，非 0 表示正在被争夺
     * @param myFaction 查看者阵营，0 表示无阵营（观战）
     */
    public static int conquestPointColor(int owner, int pressure, int myFaction) {
        if (pressure != 0) {
            return YELLOW;
        }
        if (owner == 0) {
            return GREY;
        }
        if (myFaction != 0) {
            return owner == myFaction ? BLUE : RED;
        }
        return owner == 1 ? BLUE : RED;
    }

    /**
     * 突破模式据点配色：颜色语义是<b>绝对</b>的，不随查看者阵营翻转——攻方(ALPHA)已拿下的
     * 点恒为蓝、守方(BRAVO)仍持有的点恒为红。这与 {@code BreakthroughHudDto} 的既有约定
     * 一致（该 DTO 刻意不含 focusFaction 字段，正是因为突破模式无阵营相对性）。
     */
    public static int breakthroughPointColor(int owner, int pressure) {
        if (pressure != 0) {
            return YELLOW;
        }
        if (owner == 0) {
            return GREY;
        }
        return owner == 1 ? BLUE : RED;
    }

    /** 队友点位配色：倒地优先于存活判定，因为倒地玩家仍算 alive。 */
    public static int squadMateColor(boolean downed) {
        return downed ? SQUAD_DOWNED : SQUAD_ALIVE;
    }

    /** 把颜色的 alpha 通道整体乘以 {@code alpha}（0~1），用于据点事件的一次性光晕衰减。 */
    public static int withAlpha(int color, float alpha) {
        int a = Math.max(0, Math.min(255, Math.round(255 * alpha)));
        return (color & 0x00FFFFFF) | (a << 24);
    }
}
