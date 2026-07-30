package org.shee33.act0.battlefield.core;

/**
 * 突破模式的可配置规则。不可变，通过 Builder 构建。
 *
 * @param startingTickets   进攻方初始票数
 * @param ticketsPerSector  占领一个区域后补充的票数
 * @param captureSeconds    单个据点占领所需秒数
 * @param maxCaptureBoost   同时占领的最大人数加成
 */
public final class BreakthroughRules {
    private final int startingTickets;
    private final int ticketsPerSector;
    private final double captureSeconds;
    private final int maxCaptureBoost;

    private BreakthroughRules(Builder builder) {
        this.startingTickets = builder.startingTickets;
        this.ticketsPerSector = builder.ticketsPerSector;
        this.captureSeconds = builder.captureSeconds;
        this.maxCaptureBoost = builder.maxCaptureBoost;
    }

    public int startingTickets() { return startingTickets; }
    public int ticketsPerSector() { return ticketsPerSector; }
    public double captureSeconds() { return captureSeconds; }
    public int maxCaptureBoost() { return maxCaptureBoost; }

    /** 计算一次 tick 中据点占领的进度增量。 */
    public double captureStep(int playersInZone, double deltaSeconds) {
        if (playersInZone <= 0) return 0.0;
        int capped = Math.min(playersInZone, maxCaptureBoost);
        return (capped * deltaSeconds) / captureSeconds;
    }

    /** BF6 风格默认值：攻击方 300 票，每区域 +50，占领 15 秒，最多 4 人加速。 */
    public static BreakthroughRules standard() {
        return new Builder().build();
    }

    public static final class Builder {
        private int startingTickets = 300;
        private int ticketsPerSector = 50;
        private double captureSeconds = 15.0;
        private int maxCaptureBoost = 4;

        public Builder startingTickets(int v) { this.startingTickets = v; return this; }
        public Builder ticketsPerSector(int v) { this.ticketsPerSector = v; return this; }
        public Builder captureSeconds(double v) { this.captureSeconds = v; return this; }
        public Builder maxCaptureBoost(int v) { this.maxCaptureBoost = v; return this; }

        public BreakthroughRules build() {
            if (startingTickets <= 0) throw new IllegalArgumentException("startingTickets must be > 0");
            if (ticketsPerSector < 0) throw new IllegalArgumentException("ticketsPerSector must be >= 0");
            if (captureSeconds <= 0) throw new IllegalArgumentException("captureSeconds must be > 0");
            if (maxCaptureBoost <= 0) throw new IllegalArgumentException("maxCaptureBoost must be > 0");
            return new BreakthroughRules(this);
        }
    }
}
