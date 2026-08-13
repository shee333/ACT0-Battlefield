package org.shee33.act0.battlefield.bot.mc;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.shee33.act0.battlefield.bot.AimModel;
import org.shee33.act0.battlefield.bot.Steering;
import org.shee33.act0.battlefield.bot.TargetScoring;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * AI 士兵的感知层：枚举附近敌人、做视野与视线筛选，交由 {@link TargetScoring} 选出交火目标。
 *
 * <p>职责边界：本类只负责"看得见谁"（MC 相关），"打谁"的策略在 MC-free 的 {@link TargetScoring} 里。
 * 这样选择策略可以用单测覆盖各种战场态势，不必开服务器摆人。
 *
 * <p><b>敌我判定由调用方以谓词注入</b>，不在此处硬编码。目前调试指令传入的是"除自己以外的所有玩家"
 * （即混战），日后接入对局时换成 {@code ArcadeMatch} 的阵营判定即可，本类无需改动。
 */
public final class BotPerception {

    /**
     * 默认搜索半径（格）。
     *
     * <p>取 64 格：明显大于街机竞技场的典型交火距离（10~40 格），使 bot 不会因为目标稍远就"失明"；
     * 又不至于跨越整张地图去锁定根本打不着的人。真正的距离取舍由 {@link TargetScoring} 的得分衰减完成。
     */
    public static final double DEFAULT_SEARCH_RADIUS = 64.0D;

    private BotPerception() {
    }

    /**
     * 眼到目标点之间是否无方块遮挡。
     *
     * <p>只判方块不判实体：队友挡住射线时依然算"看得见"，与真人行为一致
     * （友伤由 {@code ArcadeMatch.shouldCancelDamage} 另行裁决）。
     */
    public static boolean hasClearLineOfSight(Entity viewer, Vec3 from, Vec3 to) {
        HitResult hit = viewer.level().clip(new ClipContext(
                from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, viewer));
        return hit.getType() == HitResult.Type.MISS;
    }

    /**
     * 选出当前应交火的目标；无合适目标返回 {@code null}。
     *
     * @param bot           发起感知的 bot
     * @param model         其难度参数（决定视野锥角）
     * @param isEnemy       敌我判定谓词
     * @param currentTarget 当前交火目标，用于施加黏滞；可为 {@code null}
     * @param searchRadius  搜索半径（格）
     */
    @Nullable
    public static ServerPlayer findTarget(BotPlayer bot,
                                          AimModel model,
                                          Predicate<ServerPlayer> isEnemy,
                                          @Nullable Entity currentTarget,
                                          double searchRadius) {
        return findTarget(bot, model, isEnemy, currentTarget, searchRadius, id -> 0.0F);
    }

    /**
     * 带额外权重的目标选择：{@code bonusOf} 为每个候选给出一个与黏滞同量纲的加权，
     * 当前用于小队集火（见 {@link org.shee33.act0.battlefield.bot.SquadTactics#focusFireBonus}）。
     *
     * <p>权重作用在打分而非筛选上：被队友集火的人更值得打，但<b>不因此变得可以穿墙打</b>
     * ——视线与视野锥仍是硬门槛，加权只在多个都打得到的目标之间排序。
     */
    @Nullable
    public static ServerPlayer findTarget(BotPlayer bot,
                                          AimModel model,
                                          Predicate<ServerPlayer> isEnemy,
                                          @Nullable Entity currentTarget,
                                          double searchRadius,
                                          java.util.function.Function<UUID, Float> bonusOf) {
        Vec3 eye = bot.getEyePosition();
        double radiusSq = searchRadius * searchRadius;
        UUID currentId = currentTarget != null ? currentTarget.getUUID() : null;

        List<TargetScoring.Candidate> candidates = new ArrayList<>();
        Map<UUID, ServerPlayer> byId = new HashMap<>();

        // 遍历玩家列表而非做 AABB 实体查询：参战者一律是玩家（含 bot），
        // 而玩家数量极小，逐个算距离比构造包围盒查询更省。
        for (ServerPlayer other : bot.serverLevel().players()) {
            if (other == bot || !other.isAlive() || other.isSpectator() || !isEnemy.test(other)) {
                continue;
            }
            Vec3 aimPoint = BotWeaponController.aimPointOf(other);
            double dx = aimPoint.x - eye.x;
            double dy = aimPoint.y - eye.y;
            double dz = aimPoint.z - eye.z;
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq > radiusSq) {
                continue;
            }
            // 用头部朝向而非身体朝向：行进中 bot 会左右扫视（见 MarchTactics），
            // 察觉范围应跟着它实际看的方向走，否则扫视对发现敌人毫无作用。
            float angle = Steering.angleBetween(bot.getYHeadRot(), Steering.yawToward(dx, dz));
            // 视线检测有 raycast 成本，故放在视野锥筛选之后——锥外的人无论看不看得见都不会被选中。
            boolean inFov = model.withinFov(angle);
            boolean los = inFov && hasClearLineOfSight(bot, eye, aimPoint);

            UUID id = other.getUUID();
            byId.put(id, other);
            Float bonus = bonusOf.apply(id);
            candidates.add(new TargetScoring.Candidate(
                    id, Math.sqrt(distSq), angle, los, id.equals(currentId),
                    bonus == null ? 0.0F : bonus));
        }

        Optional<TargetScoring.Candidate> chosen = TargetScoring.select(candidates, model);
        return chosen.map(c -> byId.get(c.id())).orElse(null);
    }

    /** 以默认搜索半径选目标。 */
    @Nullable
    public static ServerPlayer findTarget(BotPlayer bot,
                                          AimModel model,
                                          Predicate<ServerPlayer> isEnemy,
                                          @Nullable Entity currentTarget) {
        return findTarget(bot, model, isEnemy, currentTarget, DEFAULT_SEARCH_RADIUS);
    }

    /**
     * 最近的敌人，<b>忽略视野锥与视线遮挡</b>。
     *
     * <p>这是<b>移动</b>目标，不是交火目标。士兵在看见敌人之前就该朝交战方向推进；
     * 若连移动也要求视线，bot 在没看到人时就彻底停摆，表现为满场站桩——这正是
     * 交火目标（{@link #findTarget}）与行军目标必须分开取的原因。开枪仍由 findTarget
     * 的视线判定把关，故不会出现隔墙射击。
     *
     * <p>刻意不做 raycast：本方法每个扫描周期对每个 bot 都要跑，而"往哪个方向推进"
     * 这个粒度的决策不需要精确到能否看见。
     */
    @Nullable
    public static ServerPlayer findNearestHostile(BotPlayer bot,
                                                 Predicate<ServerPlayer> isEnemy,
                                                 double searchRadius) {
        double radiusSq = searchRadius * searchRadius;
        ServerPlayer nearest = null;
        double nearestSq = Double.MAX_VALUE;
        for (ServerPlayer other : bot.serverLevel().players()) {
            if (other == bot || !other.isAlive() || other.isSpectator() || !isEnemy.test(other)) {
                continue;
            }
            double distSq = other.distanceToSqr(bot);
            if (distSq <= radiusSq && distSq < nearestSq) {
                nearestSq = distSq;
                nearest = other;
            }
        }
        return nearest;
    }
}
