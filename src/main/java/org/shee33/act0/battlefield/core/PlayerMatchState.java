package org.shee33.act0.battlefield.core;

/**
 * 玩家在一场对局中所处的状态机。MC-free 纯枚举。
 *
 * <p>五种状态刻画玩家在一局内的全部生命周期：
 * <ul>
 *   <li>{@link #ACTIVE}：正常在场上，可战斗、可占点、可被作为重生目标。</li>
 *   <li>{@link #DOWNED}：倒地待救，15 秒内未救起则死亡。</li>
 *   <li>{@link #DEPLOYING}：处于重生 / 部署流程中（选点界面或传送途中），尚未进入 ACTIVE。</li>
 *   <li>{@link #OFFLINE}：掉线 / 暂时离开，保留阵营与票数，但暂不可控。</li>
 *   <li>{@link #LEFT}：主动退场或被踢出，已脱离本局。</li>
 * </ul>
 */
public enum PlayerMatchState {

    /** 正常在场上，可战斗、可占点、可被作为重生目标。 */
    ACTIVE,
    /** 倒地待救，15 秒内未救起则死亡。 */
    DOWNED,
    /** 重生 / 部署流程中（选点界面或传送途中）。 */
    DEPLOYING,
    /** 掉线或暂时离开，保留阵营与票数。 */
    OFFLINE,
    /** 已离开本局（主动退出或被踢出）。 */
    LEFT;

    /** 是否可以参与据点争夺。倒地、部署中、离线、离场者均不可占点。 */
    public boolean canCapture() {
        return this == ACTIVE;
    }

    /** 是否可作为队友的重生目标。仅 ACTIVE 玩家可被部署到。 */
    public boolean canBeSpawnTarget() {
        return this == ACTIVE;
    }

    /** 是否具备完整战斗能力（开火、交互、占点等）。 */
    public boolean isCombatReady() {
        return this == ACTIVE;
    }
}