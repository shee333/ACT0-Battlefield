package org.shee33.act0.battlefield.core;

/**
 * 小队规模上限的 MC-free 副本。
 *
 * <p>{@code SquadManager} 位于 MC 依赖层，{@code core/} 的纯规则不能引用它，否则规则就没法
 * 脱离 Minecraft classpath 单测。由 {@code SquadManagerLimitsTest} 断言两处数值一致，防止
 * 其中一侧被改动后悄悄失同步。
 */
public final class SquadManagerLimits {

    /** 小队人数硬上限，须与 {@code SquadManager.MAX_SQUAD_SIZE} 一致。 */
    public static final int MAX_SQUAD_SIZE = 4;

    private SquadManagerLimits() {
    }
}
