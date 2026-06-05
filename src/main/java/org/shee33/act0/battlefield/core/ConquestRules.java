package org.shee33.act0.battlefield.core;

import java.util.Objects;

/**
 * 征服规则参数：一组不可变数值，刻画一场征服对局的票数、占点速度与流失节奏。MC-free，可单测。
 *
 * <ul>
 *   <li>{@link #startingTickets}：每个阵营的起始票数。</li>
 *   <li>{@link #captureSeconds}：单人把一个据点从中立占满到己方所需秒数（人多更快，有上限）。</li>
 *   <li>{@link #maxCaptureBoost}：计入占点加速的最大人数（防止人海无限提速）。</li>
 *   <li>{@link #bleedPerPointPerSecond}：领先方每多控制 1 个据点，敌方每秒流失的票数。</li>
 *   <li>{@link #ticketPerDeath}：每名玩家阵亡使本方损失的票数（0 表示死亡不扣票）。</li>
 * </ul>
 */
public final class ConquestRules {

    private final double startingTickets;
    private final double captureSeconds;
    private final int maxCaptureBoost;
    private final double bleedPerPointPerSecond;
    private final double ticketPerDeath;

    private ConquestRules(Builder b) {
        if (b.startingTickets <= 0) {
            throw new IllegalArgumentException("startingTickets must be > 0");
        }
        if (b.captureSeconds <= 0) {
            throw new IllegalArgumentException("captureSeconds must be > 0");
        }
        this.startingTickets = b.startingTickets;
        this.captureSeconds = b.captureSeconds;
        this.maxCaptureBoost = Math.max(1, b.maxCaptureBoost);
        this.bleedPerPointPerSecond = Math.max(0, b.bleedPerPointPerSecond);
        this.ticketPerDeath = Math.max(0, b.ticketPerDeath);
    }

    /** 默认征服规则：300 票、单人 15 秒占点、最多 4 人加速、每多 1 点每秒流失 1 票、每死扣 1 票。 */
    public static ConquestRules standard() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public double startingTickets() {
        return startingTickets;
    }

    public double captureSeconds() {
        return captureSeconds;
    }

    public int maxCaptureBoost() {
        return maxCaptureBoost;
    }

    public double bleedPerPointPerSecond() {
        return bleedPerPointPerSecond;
    }

    public double ticketPerDeath() {
        return ticketPerDeath;
    }

    /**
     * 给定区域内某阵营人数，返回这段时间内该阵营推进的占点进度增量（0~1 为中立到占满的比例）。
     *
     * @param playersInZone 该阵营在据点区域内的人数（&gt;0）
     * @param deltaSeconds  经过的秒数
     */
    public double captureStep(int playersInZone, double deltaSeconds) {
        int effective = Math.min(Math.max(0, playersInZone), maxCaptureBoost);
        if (effective <= 0 || deltaSeconds <= 0) {
            return 0.0;
        }
        return (deltaSeconds / captureSeconds) * effective;
    }

    public static final class Builder {
        private double startingTickets = 300;
        private double captureSeconds = 15;
        private int maxCaptureBoost = 4;
        private double bleedPerPointPerSecond = 1.0;
        private double ticketPerDeath = 1.0;

        public Builder startingTickets(double v) {
            this.startingTickets = v;
            return this;
        }

        public Builder captureSeconds(double v) {
            this.captureSeconds = v;
            return this;
        }

        public Builder maxCaptureBoost(int v) {
            this.maxCaptureBoost = v;
            return this;
        }

        public Builder bleedPerPointPerSecond(double v) {
            this.bleedPerPointPerSecond = v;
            return this;
        }

        public Builder ticketPerDeath(double v) {
            this.ticketPerDeath = v;
            return this;
        }

        public ConquestRules build() {
            return new ConquestRules(this);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ConquestRules that)) {
            return false;
        }
        return Double.compare(that.startingTickets, startingTickets) == 0
                && Double.compare(that.captureSeconds, captureSeconds) == 0
                && maxCaptureBoost == that.maxCaptureBoost
                && Double.compare(that.bleedPerPointPerSecond, bleedPerPointPerSecond) == 0
                && Double.compare(that.ticketPerDeath, ticketPerDeath) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(startingTickets, captureSeconds, maxCaptureBoost,
                bleedPerPointPerSecond, ticketPerDeath);
    }
}
