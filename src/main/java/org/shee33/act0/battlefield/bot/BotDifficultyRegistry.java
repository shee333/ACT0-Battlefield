package org.shee33.act0.battlefield.bot;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * 五档难度的<b>生效</b>瞄准参数。MC-free，可单测。与 {@code AttachmentRegistry} 平行。
 *
 * <p><b>为什么要有这一层，而不是直接读 {@link AimModel.Difficulty#defaults()}。</b>
 * 这 60 个数（五档 × 12 参数）是需要反复调手感的东西——硬编码意味着每改一个数字都要重编译重启，
 * 调参循环长到没人愿意认真调。本注册表持有从 {@code config/act0_arcade/bot/difficulty.json}
 * 读入的生效值，配合 {@code /arcade bot reload} 可在局内即时生效。
 *
 * <p><b>回退语义</b>：任一档缺失或非法时回退到该档的内置默认值，而非整体拒绝加载。
 * 管理员改坏一个数字不应导致 bot 完全不可用。
 */
public final class BotDifficultyRegistry {

    private final Map<AimModel.Difficulty, AimModel> models = new EnumMap<>(AimModel.Difficulty.class);

    /** 新注册表以全部内置默认值起步，因此未加载配置时也立即可用。 */
    public BotDifficultyRegistry() {
        resetToDefaults();
    }

    /** 某档当前生效的参数。 */
    public AimModel get(AimModel.Difficulty difficulty) {
        Objects.requireNonNull(difficulty, "difficulty");
        return models.getOrDefault(difficulty, difficulty.defaults());
    }

    /** 覆盖某档参数。 */
    public void put(AimModel.Difficulty difficulty, AimModel model) {
        Objects.requireNonNull(difficulty, "difficulty");
        Objects.requireNonNull(model, "model");
        models.put(difficulty, model);
    }

    /** 把某档恢复为内置默认值。 */
    public void resetToDefault(AimModel.Difficulty difficulty) {
        Objects.requireNonNull(difficulty, "difficulty");
        models.put(difficulty, difficulty.defaults());
    }

    /** 全部档位恢复为内置默认值。 */
    public void resetToDefaults() {
        for (AimModel.Difficulty difficulty : AimModel.Difficulty.values()) {
            models.put(difficulty, difficulty.defaults());
        }
    }

    /** 某档是否已被配置覆盖（与内置默认值不同）。 */
    public boolean isOverridden(AimModel.Difficulty difficulty) {
        return !get(difficulty).equals(difficulty.defaults());
    }

    /**
     * 检查"更高难度必须更强"这条设计不变量，返回违反项的可读描述；全部满足时返回空列表。
     *
     * <p><b>刻意只报告而不拒绝。</b>该不变量在内置默认值上由单测强制保证，但管理员可能出于
     * 特殊玩法（如"高难度只是反应更快、枪法反而更差"）故意打破它。拒绝加载会把配置文件变成
     * 一件对抗管理员的东西；报告则既提醒手误、又不阻断有意为之。
     *
     * <p><b>只检查主阶梯。</b>不在
     * {@link AimModel.Difficulty#onEscalationLadder() 递增阶梯}上的档位（当前是写实档）被整体跳过
     * ——它以更窄视野与更短记忆换取更强枪法，与相邻档位在设计上本就不可比。把它纳入检查会稳定
     * 产出一组"违反项"，而这组噪音会让这份自检失去意义：真正的手误将淹没在预期的报告里。
     */
    public java.util.List<String> monotonicityViolations() {
        java.util.List<AimModel.Difficulty> ladder = new java.util.ArrayList<>();
        for (AimModel.Difficulty tier : AimModel.Difficulty.values()) {
            if (tier.onEscalationLadder()) {
                ladder.add(tier);
            }
        }
        java.util.List<String> violations = new java.util.ArrayList<>();
        for (int i = 1; i < ladder.size(); i++) {
            AimModel lo = get(ladder.get(i - 1));
            AimModel hi = get(ladder.get(i));
            String pair = ladder.get(i - 1) + "→" + ladder.get(i);
            check(violations, pair, "反应应更快", hi.reactionTicks() < lo.reactionTicks());
            check(violations, pair, "转向应更快", hi.turnRateDegPerTick() > lo.turnRateDegPerTick());
            check(violations, pair, "初始误差应更小", hi.errorInitialDegrees() < lo.errorInitialDegrees());
            check(violations, pair, "收敛误差应更小", hi.errorSettledDegrees() < lo.errorSettledDegrees());
            check(violations, pair, "收敛应更快", hi.errorConvergeTicks() < lo.errorConvergeTicks());
            check(violations, pair, "后坐力应更小", hi.errorPerShotDegrees() < lo.errorPerShotDegrees());
            check(violations, pair, "回落应更快", hi.errorRecoveryPerTick() > lo.errorRecoveryPerTick());
            check(violations, pair, "视野应更广", hi.fovHalfAngleDegrees() > lo.fovHalfAngleDegrees());
            check(violations, pair, "点射停顿应更短", hi.burstPauseTicks() < lo.burstPauseTicks());
            check(violations, pair, "目标记忆应更久", hi.reacquireTicks() > lo.reacquireTicks());
        }
        return violations;
    }

    private static void check(java.util.List<String> out, String pair, String rule, boolean ok) {
        if (!ok) {
            out.add(pair + " " + rule);
        }
    }
}
