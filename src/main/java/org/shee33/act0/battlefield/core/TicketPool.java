package org.shee33.act0.battlefield.core;

/**
 * 双方票数池与流失逻辑。MC-free，可单测。
 *
 * <p>票数随时间按"据点多数"流失：控制据点多的一方使对方持续掉票，差距越大掉得越快；另外可选每名玩家
 * 阵亡扣本方票数。任一方票数归零即落败。
 */
public final class TicketPool {

    private double alpha;
    private double bravo;

    public TicketPool(double startingTickets) {
        this.alpha = startingTickets;
        this.bravo = startingTickets;
    }

    public double tickets(Faction faction) {
        return faction == Faction.ALPHA ? alpha : bravo;
    }

    /** 取整后的票数，供 HUD 显示。 */
    public int displayTickets(Faction faction) {
        return (int) Math.ceil(Math.max(0.0, tickets(faction)));
    }

    /**
     * 按当前据点控制数流失票数。
     *
    * @param alphaPoints  红队控制的据点数
    * @param bravoPoints  蓝队控制的据点数
     * @param rules        规则（流失速率）
     * @param deltaSeconds 经过的秒数
     */
    public void bleed(int alphaPoints, int bravoPoints, ConquestRules rules, double deltaSeconds) {
        if (deltaSeconds <= 0) {
            return;
        }
        int diff = alphaPoints - bravoPoints;
        if (diff == 0) {
            return;
        }
        double amount = Math.abs(diff) * rules.bleedPerPointPerSecond() * deltaSeconds;
        if (diff > 0) {
            bravo = Math.max(0.0, bravo - amount);
        } else {
            alpha = Math.max(0.0, alpha - amount);
        }
    }

    /** 一名玩家阵亡：按规则扣其所属阵营票数。 */
    public void onDeath(Faction faction, ConquestRules rules) {
        double cost = rules.ticketPerDeath();
        if (cost <= 0) {
            return;
        }
        if (faction == Faction.ALPHA) {
            alpha = Math.max(0.0, alpha - cost);
        } else {
            bravo = Math.max(0.0, bravo - cost);
        }
    }

    /** 是否已有一方票数归零。 */
    public boolean hasLoser() {
        return alpha <= 0.0 || bravo <= 0.0;
    }

    /**
    * 当前胜者：某方票数归零则对方胜；若同刻双方都归零，按剩余票数（都为 0 则红队，极端边界）判，
     * 一般不会发生。返回 {@code null} 表示尚未分出胜负。
     */
    public Faction winner() {
        boolean aDead = alpha <= 0.0;
        boolean bDead = bravo <= 0.0;
        if (!aDead && !bDead) {
            return null;
        }
        if (aDead && bDead) {
            return alpha >= bravo ? Faction.ALPHA : Faction.BRAVO;
        }
        return aDead ? Faction.BRAVO : Faction.ALPHA;
    }
}
