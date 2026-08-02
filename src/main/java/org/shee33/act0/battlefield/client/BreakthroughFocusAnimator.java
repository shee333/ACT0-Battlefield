package org.shee33.act0.battlefield.client;

import net.minecraft.util.Mth;
import org.shee33.act0.battlefield.network.BreakthroughHudDto;
import org.shee33.act0.battlefield.network.BreakthroughPointDto;
import org.shee33.act0.battlefield.network.CapturePointEventPacket;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 突破模式据点占领特写(FLIP 下拉放大)的每帧状态机 —— {@link CaptureFocusAnimator} 的突破模式版本。
 *
 * <p>与 Conquest 版本的核心差异(占点 HUD 动效规格文档 §3.1)：突破模式单圈制、无中立化，
 * 因此阶段机去掉了 {@code ROUND_HOLD}/{@code ROUND_REWIND}/{@code ROUND_PULSE}，
 * 直接 {@code DROP_IN → ACTIVE → COMPLETE_HOLD → RETREAT}；配色语义也是绝对的
 * （见 {@link BreakthroughHudDto} 的类文档），不需要 {@code myFaction} 相对判断。
 */
final class BreakthroughFocusAnimator {

    private BreakthroughFocusAnimator() {
    }

    private static final long DROP_IN_MS = 500L;
    static final long SUB_FADE_IN_MS = 250L;
    static final long SUB_FADE_IN_DELAY_MS = 300L;
    private static final long COMPLETE_HOLD_MS = 900L;
    private static final long RETREAT_MS = 450L;
    private static final long RETREAT_DELAY_MS = 120L;
    static final long SUB_FADE_OUT_MS = 200L;
    private static final long ACCOUNT_BOUNCE_MS = 380L;
    private static final float CHASE_FACTOR = 0.15f;
    private static final float CONTEST_PULSE_AMPLITUDE = 0.03f;
    private static final float CONTEST_PULSE_PERIOD_MS = 90f;

    private enum Phase { IDLE, DROP_IN, ACTIVE, COMPLETE_HOLD, RETREAT }

    private static Phase phase = Phase.IDLE;
    private static long phaseStartMs;
    private static int pointId = -1;
    private static boolean completedFriendly;
    private static float shownProgress;
    private static long lastEventSeenMs = -1L;
    private static long contestStartMs = -1L;

    private static int lastRingColor = DocPalette.PROGRESS;
    private static int lastTextColor = DocPalette.PROGRESS;
    private static int lastLetterColor = DocPalette.ENEMY;
    private static String lastStatusText = "";

    private static int frozenRingColor;
    private static int frozenTextColor;
    private static int frozenLetterColor;
    private static String frozenStatusText = "";
    private static int frozenPercent;

    private static float retreatFromX;
    private static float retreatFromY;
    private static float retreatFromScale;

    private static long accountBounceStartMs = -1L;
    private static int accountBouncePointId = -1;

    private static final Map<Integer, float[]> slots = new HashMap<>();

    private static float targetX;
    private static float targetY;
    private static float smallDiameter = 18f;
    private static float focusDiameter = 50f;

    /** 每帧由据点行汇报小图标的屏幕中心与直径,供本类计算 FLIP 起止点。 */
    static void reportSlot(int id, float cx, float cy, float diameter) {
        slots.put(id, new float[]{cx, cy, diameter});
    }

    /** 由 overlay 每帧同步落点坐标与小图标/特写图直径,随 GUI 分辨率变化。 */
    static void configureGeometry(float targetXpx, float targetYpx, float smallD, float focusD) {
        targetX = targetXpx;
        targetY = targetYpx;
        smallDiameter = smallD;
        focusDiameter = focusD;
    }

    /** 当前正在被特写的据点 id;其余据点应压暗 45%,本图标应留 18% 虚影。 */
    static int ghostPointId() {
        return phase == Phase.IDLE ? -1 : pointId;
    }

    /** 380ms outBack 入账弹跳的当前缩放值;不在弹跳中的据点返回 1。 */
    static float accountBounceScale(int id, long now) {
        if (id != accountBouncePointId || accountBounceStartMs < 0) {
            return 1f;
        }
        long age = now - accountBounceStartMs;
        if (age >= ACCOUNT_BOUNCE_MS) {
            accountBouncePointId = -1;
            return 1f;
        }
        float t = Mth.clamp(age / (float) ACCOUNT_BOUNCE_MS, 0f, 1f);
        return 0.7f + 0.3f * Tween.Ease.OUT_BACK.apply(t);
    }

    /** 主更新入口,overlay 每帧调用一次;无活跃会话时返回 {@code null}。 */
    static Snapshot update(BreakthroughHudDto hud) {
        long now = Tween.now();
        boolean liveActive = hud.focusState() != 0 && !hud.focusName().isBlank();
        BreakthroughPointDto live = liveActive ? findByName(hud.points(), hud.focusName()) : null;
        int liveId = live != null ? live.pointId() : -1;

        if (phase == Phase.IDLE) {
            if (live == null) {
                return null;
            }
            beginSession(live, now);
        }

        boolean stillFocused = liveActive && liveId == pointId;
        boolean interruptible = phase == Phase.DROP_IN || phase == Phase.ACTIVE;
        if (!stillFocused && interruptible) {
            beginRetreat(now);
        }

        BreakthroughPointDto current = live != null ? live : findById(hud.points(), pointId);
        int state = stillFocused ? hud.focusState() : lastKnownState;
        float realProgress = stillFocused ? hud.focusProgress() : shownProgress;
        lastKnownState = state;
        advance(now, state, realProgress);

        if (phase == Phase.IDLE) {
            return null;
        }
        return buildSnapshot(now, current, state);
    }

    private static int lastKnownState = 0;

    private static void beginSession(BreakthroughPointDto point, long now) {
        pointId = point.pointId();
        completedFriendly = false;
        shownProgress = 0f;
        ClientCapturePointEvent.Snapshot ev = ClientCapturePointEvent.latestEvent(pointId);
        lastEventSeenMs = ev != null ? ev.atMs() : -1L;
        contestStartMs = -1L;
        phase = Phase.DROP_IN;
        phaseStartMs = now;
    }

    private static void advance(long now, int state, float realProgress) {
        switch (phase) {
            case DROP_IN -> {
                updateChase(state, realProgress, now);
                if (now - phaseStartMs >= DROP_IN_MS) {
                    phase = Phase.ACTIVE;
                    phaseStartMs = now;
                }
            }
            case ACTIVE -> {
                updateChase(state, realProgress, now);
                checkEdgeEvents(now);
            }
            case COMPLETE_HOLD -> {
                if (now - phaseStartMs >= COMPLETE_HOLD_MS) {
                    beginRetreat(now);
                }
            }
            case RETREAT -> {
                if (now - phaseStartMs >= RETREAT_DELAY_MS + RETREAT_MS) {
                    finishSession(now);
                }
            }
            default -> {
            }
        }
    }

    /** 追赶插值(§1.3.1):驱动源是服务端已同步的真实进度({@code BreakthroughHudDto#focusProgress}),
     * 争夺中("遭到反击")时进度暂停,不追赶。 */
    private static void updateChase(int state, float realProgress, long now) {
        if (state == 3) {
            if (contestStartMs < 0) {
                contestStartMs = now;
            }
            return;
        }
        contestStartMs = -1L;
        shownProgress = Tween.chase(shownProgress, realProgress, CHASE_FACTOR);
    }

    private static void checkEdgeEvents(long now) {
        ClientCapturePointEvent.Snapshot ev = ClientCapturePointEvent.latestEvent(pointId);
        if (ev == null || ev.atMs() <= lastEventSeenMs) {
            return;
        }
        lastEventSeenMs = ev.atMs();
        if (ev.kind() == CapturePointEventPacket.Kind.CAPTURED_NEW
                || ev.kind() == CapturePointEventPacket.Kind.CAPTURED_RECOVERED) {
            shownProgress = 100f;
            completedFriendly = ev.factionCode() == 1;
            phase = Phase.COMPLETE_HOLD;
            phaseStartMs = now;
        }
    }

    private static void beginRetreat(long now) {
        float[] pos = liveXYScale(now);
        retreatFromX = pos[0];
        retreatFromY = pos[1];
        retreatFromScale = pos[2];
        frozenRingColor = lastRingColor;
        frozenTextColor = lastTextColor;
        frozenLetterColor = lastLetterColor;
        frozenStatusText = lastStatusText;
        frozenPercent = Math.round(shownProgress);
        phase = Phase.RETREAT;
        phaseStartMs = now;
    }

    private static void finishSession(long now) {
        if (completedFriendly) {
            accountBouncePointId = pointId;
            accountBounceStartMs = now;
        }
        phase = Phase.IDLE;
        pointId = -1;
    }

    private static float[] liveXYScale(long now) {
        float[] slot = slots.getOrDefault(pointId, new float[]{targetX, targetY, smallDiameter});
        float x0 = slot[0];
        float y0 = slot[1];
        float s0 = smallDiameter / focusDiameter;
        return switch (phase) {
            case DROP_IN -> {
                float t = Tween.Ease.OUT_EXPO.apply(Mth.clamp((now - phaseStartMs) / (float) DROP_IN_MS, 0f, 1f));
                yield new float[]{Mth.lerp(t, x0, targetX), Mth.lerp(t, y0, targetY), Mth.lerp(t, s0, 1f)};
            }
            case RETREAT -> {
                float t = Tween.Ease.IN_OUT_CUBIC.apply(
                        Mth.clamp((now - phaseStartMs - RETREAT_DELAY_MS) / (float) RETREAT_MS, 0f, 1f));
                yield new float[]{Mth.lerp(t, retreatFromX, x0), Mth.lerp(t, retreatFromY, y0), Mth.lerp(t, retreatFromScale, s0)};
            }
            default -> new float[]{targetX, targetY, 1f};
        };
    }

    private static BreakthroughPointDto findByName(List<BreakthroughPointDto> points, String name) {
        for (BreakthroughPointDto p : points) {
            if (p.name().equals(name)) {
                return p;
            }
        }
        return null;
    }

    private static BreakthroughPointDto findById(List<BreakthroughPointDto> points, int id) {
        for (BreakthroughPointDto p : points) {
            if (p.pointId() == id) {
                return p;
            }
        }
        return null;
    }

    /** 争夺/反击脉冲(§1.4):{@code scale = 1 + 0.03*sin(经过毫秒/90)}。 */
    private static float contestPulseScale(long now) {
        if (contestStartMs < 0) {
            return 1f;
        }
        long elapsed = now - contestStartMs;
        return 1f + CONTEST_PULSE_AMPLITUDE * (float) Math.sin(elapsed / CONTEST_PULSE_PERIOD_MS);
    }

    private static Snapshot buildSnapshot(long now, BreakthroughPointDto current, int state) {
        float[] xyScale = liveXYScale(now);
        int ringColor;
        int textColor;
        int letterColor;
        String statusText;
        int percent;
        float subordinateAlpha;
        float pulseScale = 1f;

        switch (phase) {
            case DROP_IN, ACTIVE -> {
                boolean contested = state == 3;
                if (contested) {
                    ringColor = DocPalette.ENEMY;
                    textColor = DocPalette.ENEMY;
                    statusText = "遭到反击";
                } else {
                    ringColor = DocPalette.PROGRESS;
                    textColor = DocPalette.PROGRESS;
                    statusText = "正在占领";
                }
                letterColor = DocPalette.ENEMY; // 未占领完成前,字母始终保持敌方红(§3.1:初始均为敌方)。
                percent = Math.round(shownProgress);
                lastRingColor = ringColor;
                lastTextColor = textColor;
                lastLetterColor = letterColor;
                lastStatusText = statusText;
                if (phase == Phase.DROP_IN) {
                    long age = now - phaseStartMs;
                    subordinateAlpha = Tween.Ease.OUT_CUBIC.apply(
                            Mth.clamp((age - SUB_FADE_IN_DELAY_MS) / (float) SUB_FADE_IN_MS, 0f, 1f));
                } else {
                    subordinateAlpha = 1f;
                    if (contested) {
                        pulseScale = contestPulseScale(now);
                    }
                }
            }
            case COMPLETE_HOLD -> {
                boolean friendly = completedFriendly;
                ringColor = friendly ? DocPalette.FRIEND : DocPalette.ENEMY;
                textColor = ringColor;
                letterColor = ringColor;
                statusText = friendly ? "已占领" : "已失守";
                percent = 100;
                subordinateAlpha = 1f;
                lastRingColor = ringColor;
                lastTextColor = textColor;
                lastLetterColor = letterColor;
                lastStatusText = statusText;
            }
            case RETREAT -> {
                ringColor = frozenRingColor;
                textColor = frozenTextColor;
                letterColor = frozenLetterColor;
                statusText = frozenStatusText;
                percent = frozenPercent;
                long age = now - phaseStartMs;
                subordinateAlpha = 1f - Tween.Ease.IN_CUBIC.apply(Mth.clamp(age / (float) SUB_FADE_OUT_MS, 0f, 1f));
            }
            default -> {
                ringColor = DocPalette.PROGRESS;
                textColor = DocPalette.PROGRESS;
                letterColor = DocPalette.ENEMY;
                statusText = "";
                percent = 0;
                subordinateAlpha = 0f;
            }
        }

        String letter = current != null && !current.name().isBlank() ? current.name().substring(0, 1) : "";
        return new Snapshot(pointId, letter, xyScale[0], xyScale[1], xyScale[2] * pulseScale,
                ringColor, textColor, letterColor, statusText, percent,
                Mth.clamp(subordinateAlpha, 0f, 1f));
    }

    /** 供 overlay 直接消费的一帧渲染快照。 */
    record Snapshot(int pointId, String letter, float x, float y, float scale,
                     int ringColor, int textColor, int letterColor,
                     String statusText, int percent, float subordinateAlpha) {
    }
}
