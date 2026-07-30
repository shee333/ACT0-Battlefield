package org.shee33.act0.battlefield.core;

/**
 * 一场对局所处的阶段。MC-free 纯枚举。
 *
 * <p>四个阶段刻画一局征服的完整时间轴：
 * <ul>
 *   <li>{@link #COUNTDOWN}：开赛倒计时，玩家已入场但据点争夺与票数流失尚未开始。</li>
 *   <li>{@link #LIVE}：比赛中，据点争夺与票数流失正常进行。</li>
 *   <li>{@link #POST_MATCH}：已分出胜负，结算画面展示中，逻辑冻结。</li>
 *   <li>{@link #ENDED}：本局完全结束，数据落盘，进入可清理状态。</li>
 * </ul>
 */
public enum MatchPhase {

    /** 开赛倒计时，玩家已入场但据点争夺与票数流失尚未开始。 */
    COUNTDOWN,
    /** 比赛中，据点争夺与票数流失正常进行。 */
    LIVE,
    /** 已分出胜负，结算画面展示中，逻辑冻结。 */
    POST_MATCH,
    /** 本局完全结束，数据落盘，进入可清理状态。 */
    ENDED;

    /** 是否处于开赛前阶段（COUNTDOWN）。 */
    public boolean isPreMatch() {
        return this == COUNTDOWN;
    }

    /** 是否处于正式比赛阶段（LIVE）。 */
    public boolean isLive() {
        return this == LIVE;
    }

    /** 是否已进入结束阶段（POST_MATCH 或 ENDED），此时据点推进与票数流失应停止。 */
    public boolean isFinished() {
        return this == POST_MATCH || this == ENDED;
    }
}