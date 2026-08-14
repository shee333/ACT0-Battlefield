package org.shee33.act0.battlefield.client;

import net.minecraft.util.Mth;

/**
 * 部署 2D 缩略地图的纯数学部分 —— 参照《部署界面动效规格文档》第2节(布局比例)与
 * §3.4(十字准星边缘渐隐),不依赖 {@link GuiGraphics}/{@link Tween} 之外的
 * 任何渐染状态,方便脱离 Minecraft classpath 之外的纯逻辑单独单测。
 *
 * <p><b>标记位置必须与真实世界坐标严格一一对应</b>：规格文档 §3.2 的"全局漂移循环"
 * （每个标记按 sin/cos 持续偏移 ±1.6px）已移除——那套动效是给装饰性元素设计的氛围效果，
 * 套用在据点/队友这类<b>需要精确定位</b>的标记上会让它们永远对不准实际位置，
 * 且点击命中判定跟着晃动的标记走，玩家实际选到的目标与看到的位置不符。
 *
 * <p>投影约定:世界 X 对应屏幕横轴(增大向右/东),世界 Z 对应屏幕纵轴(增大向下/南),即<b>恒为北朝上</b>。
 * 战斗区域 AABB 的 {@code min}/{@code max} 天然满足 min≤max,因此无需额外翻转 Z 轴符号。
 *
 * <p><b>方位基准与战斗小地图不同,这是刻意的。</b>{@code BattlefieldMinimapOverlay} 默认走旋转模式
 * (前方永远朝上,见 {@code BattlefieldConfig.MINIMAP_NORTH_UP} 默认 {@code false}),本图则固定北朝上
 * ——部署时要的是稳定可点的目标,地图跟着镜头转会让人点不准。代价是两者相差玩家当时的偏航角,玩家在
 * 战斗中建立的空间感无法直接套用到这张图上,因此本图<b>必须自带方位参照物</b>
 * ({@link #facingDirection} 驱动的自身朝向锥与正北标记);拿掉它们,这张图就会"看着对不上实际地图"。
 */
final class DeployMapMath {

    private DeployMapMath() {
    }

    /** 区域跨度下限(格),避免退化区域(单点/单线)导致除零。 */
    private static final double MIN_SPAN = 1.0e-3;

    /**
     * 规格文档 §1 "任何宿主比例下不变形":给定战斗区域 AABB 与宿主绘制框(box),
     * 用 {@code Math.min(scaleX, scaleY)} 等比缩放并居中,返回实际绘制矩形
     * {@code [drawX, drawY, drawW, drawH]}(letterbox,类似 SVG {@code preserveAspectRatio="xMidYMid meet"})。
     */
    static float[] fittedRect(double areaMinX, double areaMinZ, double areaMaxX, double areaMaxZ,
                               float boxX, float boxY, float boxW, float boxH) {
        double spanX = Math.max(MIN_SPAN, areaMaxX - areaMinX);
        double spanZ = Math.max(MIN_SPAN, areaMaxZ - areaMinZ);
        float scale = (float) Math.min(boxW / spanX, boxH / spanZ);
        if (!(scale > 0f) || Float.isInfinite(scale)) {
            scale = 0f;
        }
        float drawW = (float) (spanX * scale);
        float drawH = (float) (spanZ * scale);
        float drawX = boxX + (boxW - drawW) / 2f;
        float drawY = boxY + (boxH - drawH) / 2f;
        return new float[]{drawX, drawY, drawW, drawH};
    }

    /**
     * 世界 XZ → 缩略图屏幕 XY。内部会先按 {@link #fittedRect} 对给定的 box 做等比适配,
     * 因此传入"整块地图外框"或"已经适配过的内框"两种 box 都能得到一致结果(幂等)。
     */
    static float[] project(double worldX, double worldZ,
                            double areaMinX, double areaMinZ, double areaMaxX, double areaMaxZ,
                            float boxX, float boxY, float boxW, float boxH) {
        double spanX = Math.max(MIN_SPAN, areaMaxX - areaMinX);
        double spanZ = Math.max(MIN_SPAN, areaMaxZ - areaMinZ);
        float scale = (float) Math.min(boxW / spanX, boxH / spanZ);
        if (!(scale > 0f) || Float.isInfinite(scale)) {
            scale = 0f;
        }
        float drawW = (float) (spanX * scale);
        float drawH = (float) (spanZ * scale);
        float originX = boxX + (boxW - drawW) / 2f;
        float originY = boxY + (boxH - drawH) / 2f;
        float sx = originX + (float) ((worldX - areaMinX) * scale);
        float sy = originY + (float) ((worldZ - areaMinZ) * scale);
        return new float[]{sx, sy};
    }

    /**
     * 纯函数(P0修复):给定投影坐标与letterbox后的内框rect,判断是否落在范围内——战斗区域
     * AABB之外的标记(比如队友跑到最外圈据点范围之外)投影后可能落在地图矩形rect之外,甚至
     * 落在地图外框box范围内的其他UI区域(预览卡/标题条/武器栏)上,这些越界标记不能参与
     * 点击命中判定,否则点击那些区域可能意外命中一个"漏"出来的标记从而触发部署。
     */
    static boolean insideRect(float px, float py, float rx, float ry, float rw, float rh) {
        return px >= rx && px <= rx + rw && py >= ry && py <= ry + rh;
    }

    /**
     * 玩家偏航角 → 本图上朝向标记的旋转角(度,可直接喂给 {@code Axis.ZP.rotationDegrees}),
     * 约定标记在局部坐标里朝上(−y)绘制。返回值归一化到 {@code [0,360)}。
     *
     * <p>换算是 {@code yaw + 180}:Minecraft 的 yaw 以正南为 0 且顺时针增长(90=西、180=北、270=东),
     * 而本图北朝上——正北 yaw=180 必须得到 0°(不旋转,标记朝上)。屏幕 y 轴向下,{@code Axis.ZP} 的正向
     * 旋转在屏幕上表现为顺时针,恰好与 yaw 的增长方向一致,因此只需平移 180° 而无需再取负。
     */
    static float facingScreenDegrees(float yawDegrees) {
        float deg = (yawDegrees + 180.0F) % 360.0F;
        return deg < 0.0F ? deg + 360.0F : deg;
    }

    /** 选中脉冲相位:{@code p = 0.5 + 0.5*sin(t/280)},周期约 1.76s,恒在 [0,1] 内。 */
    static float pulsePhase(long nowMs) {
        return 0.5f + 0.5f * (float) Math.sin(nowMs / 280.0);
    }

    /**
     * §3.4 十字准星"向边缘淡出"渐变的三档 stop 近似(0→0, 0.5→0.65, 1→0),
     * {@code t} 为沿线段的局部位置比例([0,1])。用于分段绘制模拟 SVG linearGradient
     * (Minecraft 没有原生渐变图元,{@code DeployMapPanel} 按此值分段调用 {@code GuiGraphics.fill})。
     */
    static float edgeFadeAlpha(float t) {
        float c = Mth.clamp(t, 0f, 1f);
        if (c <= 0.5f) {
            return 0.65f * (c / 0.5f);
        }
        return 0.65f * (1f - (c - 0.5f) / 0.5f);
    }
}
