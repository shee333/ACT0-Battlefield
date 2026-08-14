package org.shee33.act0.battlefield.bot;

import java.util.Set;

/**
 * 把 TaCZ 的开火结果翻译成 AI 该做的下一步。MC-free 纯函数，可单测。
 *
 * <p><b>为什么必须穷尽分类，而不是"认识几个、其余报错"。</b>先前的实现只认 4 个结果，其余一律
 * 走告警分支，而告警按"与上次相同就不再报"去重——于是一个持续存在的真故障（如 bot 根本没拿到枪）
 * 只在开局留下一行日志便永久沉默，同时任何一个正常的瞬态（冷却、换弹、抽枪、疾跑）都可能抢占
     * 那个唯一的去重槽位，把真故障那行顶掉。
 *
 * <p>常量取自 {@code com.tacz.guns.api.entity.ShootResult}（对着 1.1.8-hotfix 的真实 jar 核对，
 * 共 16 项），外加本模组在 TaCZ 缺席时自造的 {@code TACZ_UNAVAILABLE}。用字符串而非枚举，是因为
 * TaCZ 是反射软依赖，编译期拿不到它的类型——这也是本类能留在 MC-free 层被单测的原因。
 */
public final class ShootOutcome {

    /** 收到某个开火结果后该做的事。 */
    public enum Action {
        /** 什么都不做：正常的瞬态，下一 tick 自行消失。 */
        NONE,
        /** 触发换弹。 */
        RELOAD,
        /** 触发上膛。 */
        BOLT,
        /** 补一次持枪就绪。 */
        DRAW,
        /** 记一条告警：配置或集成出了问题，重试多少次都不会好。 */
        REPORT
    }

    /** TaCZ 成功开火时返回的常量名。 */
    public static final String SUCCESS = "SUCCESS";

    /**
     * 会自行消失的瞬态，一律静默。
     *
     * <p>其中 {@code IS_SPRINTING} 尤其不能"就地修正"：TaCZ 自己会在检测到开镜时把疾跑清掉
     * （{@code LivingEntityAim.tickSprint}），而 AI 每个交火 tick 都先调一次开镜，所以它至多
     * 持续到 {@code GunData.getSprintTime()} 秒。若在此处反手去写疾跑状态，只会和位移层
     * 抢方向盘，把行军速度一并压死。
     *
     * <p>{@code NETWORK_FAIL} 刻意<b>不</b>在此列：AI 走的是 2 参数 {@code shoot} 重载，其时延
     * 校验量恒为 0、必然通过（见 {@code BotGunBridge} 的类文档），因此它一旦出现就意味着 TaCZ
     * 的实现变了，属于要人看一眼的故障而非网络抖动。
     */
    private static final Set<String> TRANSIENT = Set.of(
            "COOL_DOWN", "IS_RELOADING", "IS_DRAWING", "IS_BOLTING",
            "IS_MELEE", "IS_SPRINTING", "OVERHEATED");

    private ShootOutcome() {
    }

    /**
     * 给定 TaCZ 的开火结果名，返回该做的事。
     *
     * <p>识别不了的结果按故障处理而非静默：TaCZ 升级后新增的结果不该悄悄变成"什么都不做"，
     * 那会让 bot 再次陷入一枪不发却毫无线索的状态。
     */
    public static Action actionFor(String result) {
        if (result == null) {
            return Action.REPORT;
        }
        return switch (result) {
            case SUCCESS -> Action.NONE;
            case "NO_AMMO" -> Action.RELOAD;
            case "NEED_BOLT" -> Action.BOLT;
            case "NOT_DRAW" -> Action.DRAW;
            default -> TRANSIENT.contains(result) ? Action.NONE : Action.REPORT;
        };
    }
}
