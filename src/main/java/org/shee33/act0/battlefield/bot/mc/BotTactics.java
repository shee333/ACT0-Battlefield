package org.shee33.act0.battlefield.bot.mc;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.shee33.act0.battlefield.bot.CombatStance;
import org.shee33.act0.battlefield.bot.RetreatPolicy;
import org.shee33.act0.battlefield.bot.SquadTactics;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * 单个 AI 士兵的战术状态：交火姿态、低血脱离迟滞、小队角色与散开位移。
 *
 * <p>与街机版相比这里<b>没有模式分支</b>——大战场本轮只接征服，交火距离直接用
 * {@link CombatStance} 的通用值。等接突破模式时再引入按模式重建的那一层。
 *
 * <p>两个状态机（姿态翻向节奏、撤退迟滞）都带内部状态，因此随 bot 实例长期持有，
 * 只在复活时复位——见 {@link #onRespawn()}。
 */
final class BotTactics {

    /**
     * 征服模式的绕侧倾向，喂给 {@link SquadTactics#roleFor}／{@link SquadTactics#flankOffsetDegrees}。
     *
     * <p>取 1.0 表示"完全启用绕侧"。大战场本轮只有征服一种模式，所以这里是个定值；街机版对应的是
     * {@code ModeTactics.flankBias()} 那种按模式取的值。接突破模式时，这个常量就是替换成"按模式
     * 取值"的锚点——别把它当死代码删掉。
     */
    private static final float CONQUEST_FLANK_BIAS = 1.0F;

    private final BotPlayer bot;
    private final CombatStance stance = new CombatStance();
    private final RetreatPolicy retreat = new RetreatPolicy();

    BotTactics(BotPlayer bot) {
        this.bot = bot;
    }

    /** 推进撤退状态机；不在对局中时复位，避免对局外的血量把它锁在脱离态。 */
    void tick(@Nullable BotMatchContext context) {
        if (context == null) {
            retreat.reset();
            return;
        }
        retreat.tick(context.healthFraction());
    }

    CombatStance stance() {
        return stance;
    }

    boolean breakingOff() {
        return retreat.shouldBreakOff();
    }

    void onRespawn() {
        retreat.reset();
    }

    /** 本 bot 在小队里的角色；无队友时恒为压制——独自绕侧只是把侧身送人。 */
    SquadTactics.Role role(@Nullable BotMatchContext context) {
        if (context == null || context.squadMates().isEmpty()) {
            return SquadTactics.Role.SUPPRESS;
        }
        return SquadTactics.roleFor(context.squadRank(), CONQUEST_FLANK_BIAS);
    }

    /**
     * 把"自己→目标"的推进方向按绕侧角旋转；压制角色原样返回。
     *
     * @return 长度 2 的数组 {@code {dx, dz}}，模长与输入一致
     */
    double[] approachDirection(@Nullable BotMatchContext context, double dx, double dz) {
        if (context == null || context.squadMates().isEmpty()) {
            return new double[]{dx, dz};
        }
        float offset = SquadTactics.flankOffsetDegrees(context.squadRank(), CONQUEST_FLANK_BIAS);
        return offset == 0.0F ? new double[]{dx, dz} : SquadTactics.rotateApproach(dx, dz, offset);
    }

    /**
     * 小队散开的排斥位移：队友过近时给出一个远离最近队友的水平向量。
     *
     * <p>只算在场存活的队友——倒地或待部署的队友仍在名册里，把他们算进间距会让活人绕着尸体走位。
     */
    Vec3 separation(@Nullable BotMatchContext context) {
        if (context == null) {
            return Vec3.ZERO;
        }
        ServerPlayer nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (UUID mate : context.squadMates()) {
            ServerPlayer other = bot.serverLevel().getServer().getPlayerList().getPlayer(mate);
            if (other == null || !other.isAlive() || other.isSpectator()
                    || context.match().isDowned(mate)) {
                continue;
            }
            double dx = bot.getX() - other.getX();
            double dz = bot.getZ() - other.getZ();
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = other;
            }
        }
        if (nearest == null) {
            return Vec3.ZERO;
        }
        double strength = SquadTactics.separationStrength(nearestDist);
        if (strength <= 0.0D) {
            return Vec3.ZERO;
        }
        double dx = bot.getX() - nearest.getX();
        double dz = bot.getZ() - nearest.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0e-4D) {
            // 完全重合时方向未定义，用实体 id 造一个稳定的错开方向，避免两人朝同一侧一起挪。
            double angle = (bot.getId() % 8) * (Math.PI / 4.0D);
            return new Vec3(Math.cos(angle), 0.0D, Math.sin(angle))
                    .scale(SquadTactics.SPACING_MIN_BLOCKS * strength);
        }
        return new Vec3(dx / len, 0.0D, dz / len)
                .scale(SquadTactics.SPACING_MIN_BLOCKS * strength);
    }
}
