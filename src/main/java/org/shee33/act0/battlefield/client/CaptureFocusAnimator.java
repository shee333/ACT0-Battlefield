package org.shee33.act0.battlefield.client;

import net.minecraft.util.Mth;
import org.shee33.act0.battlefield.network.BattleHudDto;
import org.shee33.act0.battlefield.network.CapturePointEventPacket;
import org.shee33.act0.battlefield.network.ControlPointHudDto;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 据点占领特写(FLIP 下拉放大)的每帧状态机 —— 对应规格文档 §1.3.2/§2.2/§2.3。
 *
 * <p>架构映射(与文档 demo 的关键差异):demo 用全局 {@code busy} 锁 + 点击触发单一动画序列;
 * 真实联机对局里每个客户端只关心"本地玩家当前站在哪个据点里"这一件事({@code focusState}/
 * {@code focusName} 本来就是单数概念),所以这里同一时刻最多一个特写会话,不存在 demo 那种
 * "任意据点排队播放"的并发问题 —— 其余据点始终只在 {@code renderPointRow} 的小图标里正常显示。
 *
 * <p>环形进度的驱动源是服务端已同步的真实进度({@link ControlPointHudDto#progress()}),经追赶插值
 * (0.15/帧,文档 §1.3.1)平滑显示;而下拉/归位/中立确认停留/环倒放/轮次脉冲这些"UI 转场"严格使用
 * 文档写死的毫秒数,与真实占领速度({@code ConquestRules},8~15s 量级,且随人数浮动)无关。
 *
 * <p>两轮制(文档 §2.3)判定:进入据点时若真实 owner 是敌方,记为 two-round 会话,round 1(中立化)
 * 的环走 {@code 100 - progress}(敌方控制力衰减到 0 即算完成一轮);一旦收到该据点的
 * {@link CapturePointEventPacket.Kind#LOST} 事件(复用现有事件包,首次进入中立化确认的边沿信号),
 * 播放"环倒放清零"过渡,切到 round 2,环改走 {@code progress} 直接爬升;若进入时已是中立/己方,
 * 直接单圈,不做两轮。
 */
final class CaptureFocusAnimator {

    private CaptureFocusAnimator() {
    }

    // ---- 文档写死的 UI 转场时长(§2.2/§2.3),与真实占领速度无关 ----
    private static final long DROP_IN_MS = 500L;
    static final long SUB_FADE_IN_MS = 250L;
    static final long SUB_FADE_IN_DELAY_MS = 300L;
    private static final long NEUTRAL_HOLD_MS = 600L;
    private static final long RING_REWIND_MS = 400L;
    private static final long ROUND_PULSE_MS = 180L;
    private static final long COMPLETE_HOLD_MS = 900L;
    private static final long RETREAT_MS = 450L;
    private static final long RETREAT_DELAY_MS = 120L;
    static final long SUB_FADE_OUT_MS = 200L;
    private static final long ACCOUNT_BOUNCE_MS = 380L;
    private static final float CHASE_FACTOR = 0.15f;
    private static final float CONTEST_PULSE_AMPLITUDE = 0.03f;
    private static final float CONTEST_PULSE_PERIOD_MS = 90f;

    private enum Phase { IDLE, DROP_IN, ACTIVE, ROUND_HOLD, ROUND_REWIND, ROUND_PULSE, COMPLETE_HOLD, RETREAT }

    private static Phase phase = Phase.IDLE;
    private static long phaseStartMs;
    private static int pointId = -1;
    private static boolean twoRoundMode;
    private static boolean secondRound;
    private static boolean completedFriendly;
    private static float shownProgress;
    private static long lastEventSeenMs = -1L;
    private static long contestStartMs = -1L;

    // 上一帧(非 RETREAT 阶段)计算出的配色/文案,供 beginRetreat() 冻结使用。
    private static int lastRingColor = DocPalette.PROGRESS;
    private static int lastTextColor = DocPalette.PROGRESS;
    private static int lastLetterColor = DocPalette.TEXT;
    private static String lastStatusText = "";
    private static String lastRoundText = "";

    private static int frozenRingColor;
    private static int frozenTextColor;
    private static int frozenLetterColor;
    private static String frozenStatusText = "";
    private static String frozenRoundText = "";
    private static int frozenPercent;

    private static float retreatFromX;
    private static float retreatFromY;
    private static float retreatFromScale;

    private static long accountBounceStartMs = -1L;
    private static int accountBouncePointId = -1;

    /** 据点小图标的屏幕几何(cx, cy, diameter),每帧由 {@code renderPointRow} 汇报。 */
    private static final Map<Integer, float[]> slots = new HashMap<>();

    private static float targetX;
    private static float targetY;
    private static float smallDiameter = 18f;
    private static float focusDiameter = 50f;

    /** 每帧由 {@code renderPointRow} 汇报据点小图标的屏幕中心与直径,供本类计算 FLIP 起止点。 */
    static void reportSlot(int id, float cx, float cy, float diameter) {
        slots.put(id, new float[]{cx, cy, diameter});
    }

    /** 断开服务器连接时兜底清空，防止下次连到另一个世界/服务器时播放上一局遗留的幽灵特写/回撤动画。 */
    static void reset() {
        phase = Phase.IDLE;
        pointId = -1;
        twoRoundMode = false;
        secondRound = false;
        completedFriendly = false;
        shownProgress = 0f;
        lastEventSeenMs = -1L;
        contestStartMs = -1L;
        accountBounceStartMs = -1L;
        accountBouncePointId = -1;
        slots.clear();
    }

    /** 由 {@code renderCaptureFocus} 每帧同步落点坐标与小图标/特写图的直径,随 GUI 分辨率变化。 */
    static void configureGeometry(float targetXpx, float targetYpx, float smallD, float focusD) {
        targetX = targetXpx;
        targetY = targetYpx;
        smallDiameter = smallD;
        focusDiameter = focusD;
    }

    /** 当前正在被特写(FLIP 放大或正在归位)的据点 id;其余据点小图标应压暗 45%,本图标应留 18% 虚影。 */
    static int ghostPointId() {
        return phase == Phase.IDLE ? -1 : pointId;
    }

    /** 380ms outBack 入账弹跳的当前缩放值;不在弹跳中的据点返回 1(即无缩放)。 */
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

    /** 主更新入口,{@code renderCaptureFocus} 每帧调用一次;无活跃会话时返回 {@code null}。 */
    static Snapshot update(BattleHudDto hud) {
        long now = Tween.now();
        int myFaction = hud.myFaction();
        boolean liveActive = hud.focusState() != 0 && !hud.focusName().isBlank();
        ControlPointHudDto live = liveActive ? findByName(hud.points(), hud.focusName()) : null;
        int liveId = live != null ? live.pointId() : -1;

        if (phase == Phase.IDLE) {
            if (live == null) {
                return null;
            }
            beginSession(live, myFaction, now);
        }

        boolean stillFocused = liveActive && liveId == pointId;
        boolean interruptible = phase == Phase.DROP_IN || phase == Phase.ACTIVE;
        if (!stillFocused && interruptible) {
            beginRetreat(now);
        }

        ControlPointHudDto current = live != null ? live : findById(hud.points(), pointId);
        advance(now, current, myFaction, hud.focusState());

        if (phase == Phase.IDLE) {
            return null;
        }
        return buildSnapshot(now, current, myFaction, hud.focusState());
    }

    private static void beginSession(ControlPointHudDto point, int myFaction, long now) {
        pointId = point.pointId();
        twoRoundMode = point.owner() != 0 && point.owner() != myFaction;
        secondRound = false;
        completedFriendly = false;
        shownProgress = computeRoundProgress(point, myFaction);
        ClientCapturePointEvent.Snapshot ev = ClientCapturePointEvent.latestEvent(pointId);
        lastEventSeenMs = ev != null ? ev.atMs() : -1L;
        contestStartMs = -1L;
        phase = Phase.DROP_IN;
        phaseStartMs = now;
    }

    private static void advance(long now, ControlPointHudDto current, int myFaction, int state) {
        switch (phase) {
            case DROP_IN -> {
                updateChase(current, myFaction, state, now);
                if (now - phaseStartMs >= DROP_IN_MS) {
                    phase = Phase.ACTIVE;
                    phaseStartMs = now;
                }
            }
            case ACTIVE -> {
                updateChase(current, myFaction, state, now);
                checkEdgeEvents(now, myFaction);
            }
            case ROUND_HOLD -> {
                if (now - phaseStartMs >= NEUTRAL_HOLD_MS) {
                    phase = Phase.ROUND_REWIND;
                    phaseStartMs = now;
                }
            }
            case ROUND_REWIND -> {
                float t = Mth.clamp((now - phaseStartMs) / (float) RING_REWIND_MS, 0f, 1f);
                shownProgress = 100f * (1f - Tween.Ease.OUT_CUBIC.apply(t));
                if (t >= 1f) {
                    secondRound = true;
                    shownProgress = 0f;
                    phase = Phase.ROUND_PULSE;
                    phaseStartMs = now;
                }
            }
            case ROUND_PULSE -> {
                if (now - phaseStartMs >= ROUND_PULSE_MS) {
                    phase = Phase.ACTIVE;
                    phaseStartMs = now;
                }
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

    private static void updateChase(ControlPointHudDto current, int myFaction, int state, long now) {
        if (current == null) {
            return;
        }
        if (state == 3) {
            if (contestStartMs < 0) {
                contestStartMs = now;
            }
            return; // 争夺中:进度暂停,不追赶。
        }
        contestStartMs = -1L;
        float real = computeRoundProgress(current, myFaction);
        shownProgress = Tween.chase(shownProgress, real, CHASE_FACTOR);
    }

    private static void checkEdgeEvents(long now, int myFaction) {
        ClientCapturePointEvent.Snapshot ev = ClientCapturePointEvent.latestEvent(pointId);
        if (ev == null || ev.atMs() <= lastEventSeenMs) {
            return;
        }
        lastEventSeenMs = ev.atMs();
        if (ev.kind() == CapturePointEventPacket.Kind.LOST && twoRoundMode && !secondRound) {
            shownProgress = 100f;
            phase = Phase.ROUND_HOLD;
            phaseStartMs = now;
        } else if (ev.kind() == CapturePointEventPacket.Kind.CAPTURED_NEW
                || ev.kind() == CapturePointEventPacket.Kind.CAPTURED_RECOVERED) {
            shownProgress = 100f;
            completedFriendly = true;
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
        frozenRoundText = lastRoundText;
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

    /** 敌方满控(round 1,中立化):环走"敌方控制力衰减";否则(中立/己方推进/单圈)环直接走真实进度。 */
    private static float computeRoundProgress(ControlPointHudDto p, int myFaction) {
        if (p.owner() != 0 && p.owner() != myFaction) {
            return 100f - p.progress();
        }
        return p.progress();
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

    private static ControlPointHudDto findByName(List<ControlPointHudDto> points, String name) {
        for (ControlPointHudDto p : points) {
            if (p.name().equals(name)) {
                return p;
            }
        }
        return null;
    }

    private static ControlPointHudDto findById(List<ControlPointHudDto> points, int id) {
        for (ControlPointHudDto p : points) {
            if (p.pointId() == id) {
                return p;
            }
        }
        return null;
    }

    private static void buildLiveColorsAndText(int myFaction, int state) {
        boolean contested = state == 3;
        boolean round1 = twoRoundMode && !secondRound;
        if (contested) {
            lastRingColor = DocPalette.ENEMY;
            lastTextColor = DocPalette.ENEMY;
            lastLetterColor = round1 ? DocPalette.ENEMY : DocPalette.TEXT;
            lastStatusText = "争夺中";
        } else {
            lastRingColor = DocPalette.PROGRESS;
            lastTextColor = DocPalette.PROGRESS;
            lastLetterColor = round1 ? DocPalette.ENEMY : DocPalette.TEXT;
            lastStatusText = round1 ? "正在中立化" : (state == 2 ? "正在防守" : "正在占领");
        }
        lastRoundText = twoRoundMode ? (secondRound ? "第 2 / 2 轮" : "第 1 / 2 轮") : "";
    }

    /** 归零/占满时的呼吸缩放(争夺脉冲,§1.4:{@code scale = 1 + 0.03*sin(经过毫秒/90)})。 */
    private static float contestPulseScale(long now) {
        if (contestStartMs < 0) {
            return 1f;
        }
        long elapsed = now - contestStartMs;
        return 1f + CONTEST_PULSE_AMPLITUDE * (float) Math.sin(elapsed / CONTEST_PULSE_PERIOD_MS);
    }

    /** 轮次指示器切换脉冲(§2.3:{@code 1+0.25*sin(vπ)},180ms)。 */
    private static float roundPulseScale(long now) {
        float t = Mth.clamp((now - phaseStartMs) / (float) ROUND_PULSE_MS, 0f, 1f);
        return 1f + 0.25f * (float) Math.sin(t * Math.PI);
    }

    private static Snapshot buildSnapshot(long now, ControlPointHudDto current, int myFaction, int state) {
        float[] xyScale = liveXYScale(now);
        int ringColor;
        int textColor;
        int letterColor;
        String statusText;
        String roundText;
        int percent;
        float subordinateAlpha;
        float pulseScale = 1f;

        switch (phase) {
            case DROP_IN -> {
                buildLiveColorsAndText(myFaction, state);
                ringColor = lastRingColor;
                textColor = lastTextColor;
                letterColor = lastLetterColor;
                statusText = lastStatusText;
                roundText = lastRoundText;
                percent = Math.round(shownProgress);
                long age = now - phaseStartMs;
                subordinateAlpha = Tween.Ease.OUT_CUBIC.apply(
                        Mth.clamp((age - SUB_FADE_IN_DELAY_MS) / (float) SUB_FADE_IN_MS, 0f, 1f));
            }
            case ACTIVE -> {
                buildLiveColorsAndText(myFaction, state);
                ringColor = lastRingColor;
                textColor = lastTextColor;
                letterColor = lastLetterColor;
                statusText = lastStatusText;
                roundText = lastRoundText;
                percent = Math.round(shownProgress);
                subordinateAlpha = 1f;
                if (state == 3) {
                    pulseScale = contestPulseScale(now);
                }
            }
            case ROUND_HOLD, ROUND_REWIND -> {
                ringColor = DocPalette.NEUTRAL;
                textColor = DocPalette.NEUTRAL;
                letterColor = DocPalette.TEXT;
                statusText = "已中立化";
                roundText = lastRoundText.isEmpty() ? "第 1 / 2 轮" : lastRoundText;
                percent = Math.round(shownProgress);
                subordinateAlpha = 1f;
                lastRingColor = ringColor;
                lastTextColor = textColor;
                lastLetterColor = letterColor;
                lastStatusText = statusText;
                lastRoundText = roundText;
            }
            case ROUND_PULSE -> {
                ringColor = DocPalette.PROGRESS;
                textColor = DocPalette.PROGRESS;
                letterColor = DocPalette.TEXT;
                statusText = "正在占领";
                roundText = "第 2 / 2 轮";
                percent = Math.round(shownProgress);
                subordinateAlpha = 1f;
                pulseScale = 1f; // 环本身不缩放;轮次文字单独用 roundPulseScale()
                lastRingColor = ringColor;
                lastTextColor = textColor;
                lastLetterColor = letterColor;
                lastStatusText = statusText;
                lastRoundText = roundText;
            }
            case COMPLETE_HOLD -> {
                ringColor = DocPalette.FRIEND;
                textColor = DocPalette.FRIEND;
                letterColor = DocPalette.FRIEND;
                statusText = "已占领";
                roundText = "";
                percent = 100;
                subordinateAlpha = 1f;
                lastRingColor = ringColor;
                lastTextColor = textColor;
                lastLetterColor = letterColor;
                lastStatusText = statusText;
                lastRoundText = roundText;
            }
            case RETREAT -> {
                ringColor = frozenRingColor;
                textColor = frozenTextColor;
                letterColor = frozenLetterColor;
                statusText = frozenStatusText;
                roundText = frozenRoundText;
                percent = frozenPercent;
                long age = now - phaseStartMs;
                subordinateAlpha = 1f - Tween.Ease.IN_CUBIC.apply(Mth.clamp(age / (float) SUB_FADE_OUT_MS, 0f, 1f));
            }
            default -> {
                ringColor = DocPalette.PROGRESS;
                textColor = DocPalette.PROGRESS;
                letterColor = DocPalette.TEXT;
                statusText = "";
                roundText = "";
                percent = 0;
                subordinateAlpha = 0f;
            }
        }

        float roundPulse = phase == Phase.ROUND_PULSE ? roundPulseScale(now) : 1f;
        String letter = current != null && !current.name().isBlank() ? current.name().substring(0, 1) : "";

        return new Snapshot(pointId, letter, xyScale[0], xyScale[1], xyScale[2] * pulseScale,
                ringColor, textColor, letterColor, statusText, roundText, percent,
                Mth.clamp(subordinateAlpha, 0f, 1f), roundPulse);
    }

    /** 供 {@code renderCaptureFocus} 直接消费的一帧渲染快照。 */
    record Snapshot(int pointId, String letter, float x, float y, float scale,
                     int ringColor, int textColor, int letterColor,
                     String statusText, String roundText, int percent,
                     float subordinateAlpha, float roundPulseScale) {
    }
}
