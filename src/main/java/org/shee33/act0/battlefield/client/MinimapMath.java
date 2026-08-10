package org.shee33.act0.battlefield.client;

/**
 * 小地图的纯数学与配色规则，不依赖任何 Minecraft 类型，可直接 JUnit 单测。
 *
 * <p>默认<b>旋转模式</b>：整图随视角旋转、前方永远朝上；北朝上作为配置项保留。此前之所以
 * 只做北朝上，是因为两条顾虑，现已各有解法：
 * <ul>
 *   <li>标签倾斜 → 标记分两层，"位置组"随世界旋转、"字形组"每帧叠加 {@code rotate(-mapRot)}
 *       反向旋转，文字图标恒屏幕正立；</li>
 *   <li>{@code enableScissor} 裁剪区不跟随 PoseStack → 裁剪矩形始终取屏幕固定的面板区域，
 *       旋转只发生在 scissor <b>内部</b>的 PoseStack 上，裁剪因此永远正确。</li>
 * </ul>
 * 方位类元素统一按 {@link #screenBearing} 换算，两种模式零特判。
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

    /**
     * 可视半径（格）。
     *
     * <p>此前用的是"1 像素 = 2 格"，但那与"半径 50 格"在 84px 面板上并不自洽：42px 半宽
     * ×2 等于 84 格而非 50。这里以<b>半径语义</b>为准，像素/格由 {@link #pixelsPerBlock}
     * 从面板尺寸反算，面板尺寸变化时半径恒定。
     */
    public static final double VIEW_R = 50.0;

    /** 边缘渐隐带宽度（格）。 */
    public static final double FADE_R = 6.0;

    private MinimapMath() {
    }

    /** 像素/格：{@code size / (2·VIEW_R)}。 */
    public static double pixelsPerBlock(int panelSize) {
        return panelSize / (2.0 * VIEW_R);
    }

    /**
     * 最短弧角度平滑的单步增量（度）。
     *
     * <p>直接对角度做线性插值会在 ±180° 边界上绕远路打转（例如 350°→10° 会逆着转 340°）。
     * 先把差值归一化到 (−180, 180] 再按比例前进，转向永远走短边。
     *
     * @param current 当前角度（度，任意范围）
     * @param target  目标角度（度，任意范围）
     * @param factor  每帧前进比例
     * @return 应加到 {@code current} 上的增量
     */
    public static float shortestArcStep(float current, float target, float factor) {
        float delta = ((target - current + 540f) % 360f) - 180f;
        return delta * factor;
    }

    /** 追赶插值单步：{@code shown += (real - shown) × factor}。 */
    public static double chase(double shown, double real, double factor) {
        return shown + (real - shown) * factor;
    }

    /**
     * 边缘软化透明度：距离进入 {@link #FADE_R} 带内开始线性渐隐，超出可视半径返回 0。
     *
     * <p>替代原先的硬切。"超界不画、不 clamp 贴边"的决策保留——渐隐到 0 即不可见。
     */
    public static float edgeFade(double dist, double viewR, double fadeR) {
        if (dist >= viewR) {
            return 0f;
        }
        if (fadeR <= 0) {
            return 1f;
        }
        return (float) Math.min(1.0, Math.max(0.0, (viewR - dist) / fadeR));
    }

    /**
     * 世界方位角 → 屏幕方位角（弧度）。旋转模式与北朝上模式共用此换算，只是后者
     * {@code mapRotRad} 恒为 0，因此方位类元素（边缘指示、受击弧、罗盘）无需为模式写特判。
     */
    public static double screenBearing(double worldBearingRad, double mapRotRad) {
        return worldBearingRad + mapRotRad;
    }

    /**
     * 世界方位角（弧度）：+Z 为南、−Z 为北，返回值以正北为 0、顺时针增长，与
     * {@link #screenBearing} 及极坐标落点换算配套。
     */
    public static double worldBearing(double dx, double dz) {
        return Math.atan2(dx, -dz);
    }

    /**
     * 旋转模式下的投影：世界坐标 → 面板内屏幕坐标。
     *
     * <p>变换链为 {@code Rotate(mapRot) · (worldPos − playerPos) · S + center}。北朝上模式
     * 传 {@code mapRotRad = 0} 即退化为纯平移缩放。
     *
     * @return 长度 2 的数组 {@code [x, y]}，已含面板中心偏移
     */
    public static double[] project(double worldX, double worldZ, double playerX, double playerZ,
                                   double mapRotRad, double pixelsPerBlock, double centerX, double centerY) {
        double dx = (worldX - playerX) * pixelsPerBlock;
        double dz = (worldZ - playerZ) * pixelsPerBlock;
        double cos = Math.cos(mapRotRad);
        double sin = Math.sin(mapRotRad);
        return new double[]{
                centerX + dx * cos - dz * sin,
                centerY + dx * sin + dz * cos,
        };
    }

    /**
     * 屏幕角（度）→ 地图旋转角（度）。旋转模式要让"前方朝上"，因此整图反向转玩家朝向。
     *
     * @param yaw MC 的玩家 yaw
     */
    public static float mapRotationFor(float yaw, boolean northUp) {
        return northUp ? 0f : -screenAngleFor(yaw);
    }

    /** 极坐标落点：以面板中心为原点、按屏幕方位角取半径 {@code r} 处的点。 */
    public static double[] polar(double bearingRad, double r, double centerX, double centerY) {
        return new double[]{
                centerX + Math.sin(bearingRad) * r,
                centerY - Math.cos(bearingRad) * r,
        };
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
