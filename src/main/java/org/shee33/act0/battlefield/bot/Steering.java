package org.shee33.act0.battlefield.bot;

/**
 * AI 的朝向解算与限速转向数学。MC-free 纯函数，可单测。
 *
 * <p><b>为什么单独成类而不是写进移动驱动里</b>：限速转向是后续瞄准模型的核心——COD 系的
 * bot 难度几乎全部由"转向速率 + 瞄准误差 + 反应延迟"这三个数值制造，而非决策更聪明。
 * 把转向抽出来，移动时的"转身走向目标"与交火时的"抬枪瞄准敌人"复用同一套限速逻辑，
 * 保证 bot 的转身手感在行走与射击之间是连续的，不会出现"走路慢慢转、开枪瞬间甩头"的出戏感。
 *
 * <p>角度全部采用 Minecraft 的偏航约定：{@code yaw=0} 朝向 {@code +Z}，
 * {@code yaw=-90} 朝向 {@code +X}；俯仰 {@code pitch<0} 为抬头。
 */
public final class Steering {

    private Steering() {
    }

    /**
     * 把任意角度规约到 {@code [-180, 180)} 区间。
     *
     * <p>转向必须先规约再比较，否则 {@code 179° → -179°} 这种跨越 ±180 边界的转向会被误算成
     * 转 358°（绕远路），表现为 bot 原地怪异地转一大圈。
     */
    public static float wrapDegrees(float degrees) {
        float d = degrees % 360.0F;
        if (d >= 180.0F) {
            d -= 360.0F;
        }
        if (d < -180.0F) {
            d += 360.0F;
        }
        return d;
    }

    /**
     * 朝向水平目标所需的偏航角。
     *
     * @param dx 目标相对自身的 X 位移
     * @param dz 目标相对自身的 Z 位移
     * @return Minecraft 约定下的偏航角，已规约到 {@code [-180, 180)}
     */
    public static float yawToward(double dx, double dz) {
        return wrapDegrees((float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F);
    }

    /**
     * 朝向目标所需的俯仰角。
     *
     * @param dy              目标相对自身视线高度的 Y 位移
     * @param horizontalDist  水平距离；{@code 0} 时视为正下/正上方
     * @return 俯仰角，负值为抬头；已钳制到 {@code [-90, 90]}
     */
    public static float pitchToward(double dy, double horizontalDist) {
        if (horizontalDist <= 0.0D) {
            return dy > 0.0D ? -90.0F : (dy < 0.0D ? 90.0F : 0.0F);
        }
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontalDist));
        return Math.max(-90.0F, Math.min(90.0F, pitch));
    }

    /**
     * 以每次最多 {@code maxDelta} 度的速率把当前角度转向目标角度，沿最短弧线转动。
     *
     * <p>这是 bot 不像机器人的关键：真人转身有角速度上限，瞬间对准会让玩家感到"开挂"。
     *
     * @param current  当前角度
     * @param target   目标角度
     * @param maxDelta 单次最大转动量（度）；{@code <= 0} 表示不转动
     * @return 转动后的角度，已规约到 {@code [-180, 180)}
     */
    public static float turnToward(float current, float target, float maxDelta) {
        if (maxDelta <= 0.0F) {
            return wrapDegrees(current);
        }
        float diff = wrapDegrees(target - current);
        float step = Math.max(-maxDelta, Math.min(maxDelta, diff));
        return wrapDegrees(current + step);
    }

    /**
     * 当前朝向与目标朝向的最短夹角绝对值（度），用于判断"是否已大致对准"。
     *
     * <p>移动时用它决定何时可以开始前进（背对目标就往前冲会绕远路），
     * 交火时用它决定何时允许扣扳机（没对准就开枪是纯粹的浪费与噪音）。
     */
    public static float angleBetween(float from, float to) {
        return Math.abs(wrapDegrees(to - from));
    }

    /**
     * 原版物理认得的移动意图：前进量与侧移量，各自已归一到 {@code [-1, 1]}。
     *
     * @param forward 前进量，正值向身体正面
     * @param strafe  侧移量，与原版 {@code xxa} 同号
     */
    public record MoveInput(float forward, float strafe) {

        /** 静止。 */
        public static final MoveInput ZERO = new MoveInput(0.0F, 0.0F);
    }

    /**
     * 把世界坐标系下的期望移动方向分解为前进量与侧移量。
     *
     * <p><b>这是"边瞄边动"成立的唯一前提。</b>原版把移动方向绑在身体朝向上
     * （{@code zza} 沿正面、{@code xxa} 沿侧向），因此只要还用"转身朝向移动目标"来走路，
     * 交火时枪口就必须跟着走，得到的是朝着航点开枪的错误画面。反过来，把朝向交给瞄准、
     * 再把期望位移投影到当前身体朝向的正交基上，bot 就能一边把枪压在敌人身上一边横向移动
     * ——这正是战地系列步兵交火的基本形态。
     *
     * <p>推导：原版 {@code getInputVector} 给出的世界速度是
     * {@code vx = xxa·cosθ − zza·sinθ}、{@code vz = zza·cosθ + xxa·sinθ}，
     * 即以 θ 为角的旋转作用在 {@code (xxa, zza)} 上。取其逆（旋转 −θ）便得下式。
     *
     * @param dirX     期望位移的世界 X 分量，无需预先归一化
     * @param dirZ     期望位移的世界 Z 分量，无需预先归一化
     * @param bodyYaw  当前身体偏航角（度）
     * @return 分解后的移动意图；方向为零向量时返回 {@link MoveInput#ZERO}
     */
    public static MoveInput moveInput(double dirX, double dirZ, float bodyYaw) {
        double length = Math.sqrt(dirX * dirX + dirZ * dirZ);
        if (length <= 1.0E-6D) {
            return MoveInput.ZERO;
        }
        double nx = dirX / length;
        double nz = dirZ / length;
        double rad = Math.toRadians(bodyYaw);
        double sin = Math.sin(rad);
        double cos = Math.cos(rad);
        float strafe = (float) (cos * nx + sin * nz);
        float forward = (float) (-sin * nx + cos * nz);
        return new MoveInput(forward, strafe);
    }
}
