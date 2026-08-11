package org.shee33.act0.battlefield.core;

/**
 * 玩家主动切换小队的准入规则，对应《战地暂停菜单动效规格文档》§3.4「加入其他小队」。
 *
 * <p>规格文档把锁定/离开/加入描述为"对现有小队系统的调用"，但本仓库的 {@code SquadManager}
 * 原先只有自动分队与补位，并不存在玩家主动换队这回事。这里先把准入判据独立成 MC-free 规则，
 * 再由服务端调用——把"能不能加入"和"怎么搬人"分开，前者才可能被单测覆盖。
 *
 * <p>返回失败原因而不是布尔值：客户端要按原因显示「已满」/「已锁定」两种不同的灰置按钮，
 * 只给 true/false 就没法区分。
 */
public final class SquadJoinRules {

    public enum Result {
        OK,
        /** 已经在该小队里。 */
        ALREADY_IN,
        /** 目标小队满员。 */
        FULL,
        /** 目标小队已锁定。 */
        LOCKED,
        /** 跨阵营。 */
        WRONG_FACTION,
        /** 目标小队不存在。 */
        NO_SUCH_SQUAD
    }

    private SquadJoinRules() {
    }

    /**
     * @param currentSquadId 申请人当前小队号；0 表示未加入
     * @param targetSquadId  目标小队号；0/负数视为不存在
     * @param targetSize     目标小队现有人数
     * @param targetLocked   目标小队是否已锁定
     * @param sameFaction    目标小队是否与申请人同阵营
     */
    public static Result canJoin(int currentSquadId, int targetSquadId, int targetSize,
                                 boolean targetLocked, boolean sameFaction) {
        if (targetSquadId <= 0) {
            return Result.NO_SUCH_SQUAD;
        }
        if (currentSquadId == targetSquadId) {
            return Result.ALREADY_IN;
        }
        if (!sameFaction) {
            return Result.WRONG_FACTION;
        }
        // 锁定优先于满员：一个既锁定又满员的小队，玩家更需要知道的是"队长锁了"，
        // 因为满员会随人员流动自然缓解，锁定不会。
        if (targetLocked) {
            return Result.LOCKED;
        }
        return targetSize >= SquadManagerLimits.MAX_SQUAD_SIZE ? Result.FULL : Result.OK;
    }

    /** 仅队长可以切换本队的锁定状态。 */
    public static boolean canToggleLock(boolean isSquadLeader, int currentSquadId) {
        return isSquadLeader && currentSquadId > 0;
    }

    /** 未加入任何小队时无法离队。 */
    public static boolean canLeave(int currentSquadId) {
        return currentSquadId > 0;
    }
}
