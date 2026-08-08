package org.shee33.act0.battlefield.client;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * 击杀提示状态机 —— 《作战HUD动效规格文档》§3。
 *
 * <p>触发源是 {@link ClientKillFeed}：击杀广播里带杀手名，本地玩家名与之相等即为"我的击杀"。
 * 规格 §8 明确连杀窗口由客户端本地判定，所以这里不读服务端的 {@code BattleHudDto.streak()}
 * ——那是"整局累计连杀"，语义与本提示要的"4 秒滚动窗口"不同。
 *
 * <p>只保存状态与进度计算，绘制在 {@link CombatHudOverlay}。
 */
final class KillPromptAnimator {

    private static final long POP_MS = 170L;
    private static final long REFRESH_MS = 120L;
    private static final long EXIT_MS = 280L;
    private static final long SCRAMBLE_MS = 220L;
    private static final long SCORE_MS = 360L;
    private static final long STREAK_TAG_MS = 300L;

    private static int streak;
    private static int total;
    private static int shownScoreFrom;
    private static int shownScore;
    private static long lastKillMs;
    private static long popStartMs;
    private static long scoreStartMs;
    private static long streakTagStartMs;
    private static long exitStartMs = -1L;
    private static boolean firstPop;
    private static String victim = "";
    private static long lastConsumedKillMs;

    private KillPromptAnimator() {
    }

    /** 断线/退出对局时清空，避免下一局残留旧连杀。 */
    static void clear() {
        streak = 0;
        total = 0;
        shownScore = 0;
        shownScoreFrom = 0;
        lastKillMs = 0L;
        popStartMs = 0L;
        exitStartMs = -1L;
        victim = "";
        // 刻意不重置 lastConsumedKillMs：kill feed 条目有 5 秒 TTL，清零会让退出对局时仍未
        // 过期的旧击杀在下次进场时被重新消费，凭空弹出一次假提示。
    }

    /**
     * 每帧轮询击杀源。{@code killMs} 是本次击杀的发生时刻（取 kill feed 条目的过期时间反推，
     * 保证同一条击杀只被消费一次）。
     */
    static void poll(@Nullable String localName, long now) {
        if (localName == null || localName.isBlank()) {
            return;
        }
        // 一次爆头连带、或一梭子同时放倒两人时，两条击杀会在同一帧一起到达。ClientKillFeed 是
        // 新的在前，若顺着取第一条就 break，较早那条会因为 lastConsumedKillMs 直接跳到最新值而
        // 被永久跳过，连杀少算。这里反向遍历（旧→新）把本帧所有新击杀依次消费掉。
        List<ClientKillFeed.Entry> fresh = new ArrayList<>();
        for (ClientKillFeed.Entry e : ClientKillFeed.entries()) {
            if (localName.equals(e.killer()) && e.expiresAt() > lastConsumedKillMs) {
                fresh.add(e);
            }
        }
        for (int i = fresh.size() - 1; i >= 0; i--) {
            ClientKillFeed.Entry e = fresh.get(i);
            lastConsumedKillMs = Math.max(lastConsumedKillMs, e.expiresAt());
            onKill(e.victim(), now);
        }
        if (exitStartMs < 0L && lastKillMs > 0L && now - lastKillMs >= CombatHudMath.KILL_PROMPT_HOLD_MS) {
            exitStartMs = now;
        }
        if (exitStartMs >= 0L && now - exitStartMs >= EXIT_MS) {
            streak = 0;
            total = 0;
            shownScore = 0;
            shownScoreFrom = 0;
            lastKillMs = 0L;
            exitStartMs = -1L;
        }
    }

    private static void onKill(String victimName, long now) {
        boolean withinWindow = lastKillMs > 0L && now - lastKillMs < CombatHudMath.STREAK_WINDOW_MS;
        if (withinWindow) {
            streak++;
        } else {
            streak = 1;
            total = 0;
            shownScore = 0;
        }
        // 已在显示中时不重弹，只做透明度回满 + 内容更新 + 故障爆发（规格 §3.2「连击刷新」）。
        firstPop = exitStartMs >= 0L || lastKillMs == 0L;
        lastKillMs = now;
        exitStartMs = -1L;
        total += CombatHudMath.killScore(streak);
        victim = victimName == null ? "" : victimName;
        popStartMs = now;
        // 分数是"追赶到累计值"，新击杀打断旧动画但从当前显示值继续追，不回退重弹。
        shownScoreFrom = shownScore;
        scoreStartMs = now;
        if (streak >= 2) {
            streakTagStartMs = now;
        }
    }

    static boolean visible() {
        return lastKillMs > 0L || exitStartMs >= 0L;
    }

    static int streak() {
        return streak;
    }

    static int tier() {
        return CombatHudMath.streakTier(streak);
    }

    static String victim() {
        return victim;
    }

    /** 规格 §3.2：整体透明度，封顶 0.88。 */
    static float alpha(long now) {
        if (exitStartMs >= 0L) {
            float t = clamp01((now - exitStartMs) / (float) EXIT_MS);
            return CombatHudMath.KILL_PROMPT_MAX_ALPHA * (1f - Tween.Ease.IN_CUBIC.apply(t));
        }
        float t = clamp01((now - popStartMs) / (float) (firstPop ? POP_MS : REFRESH_MS));
        float eased = Tween.Ease.OUT_CUBIC.apply(t);
        if (firstPop) {
            return CombatHudMath.KILL_PROMPT_MAX_ALPHA * eased;
        }
        return 0.70f + 0.18f * eased;
    }

    /** 规格 §3.2：首杀 scale 1.25→1；连击刷新不重弹（恒为 1）。 */
    static float scale(long now) {
        if (!firstPop || exitStartMs >= 0L) {
            return 1f;
        }
        float t = clamp01((now - popStartMs) / (float) POP_MS);
        return 1.25f - 0.25f * Tween.Ease.OUT_CUBIC.apply(t);
    }

    /** 规格 §3.2：退场上飘 8px。 */
    static float exitLiftPx(long now) {
        if (exitStartMs < 0L) {
            return 0f;
        }
        return 8f * Tween.Ease.IN_CUBIC.apply(clamp01((now - exitStartMs) / (float) EXIT_MS));
    }

    /** 故障是否仍在爆发窗口内——只在弹出瞬间释放，之后静止。 */
    static boolean glitchActive(long now) {
        return now - popStartMs < CombatHudMath.glitchDurationMs(tier());
    }

    static String displayName(long now, long frameSeed) {
        float t = clamp01((now - popStartMs) / (float) SCRAMBLE_MS);
        return CombatHudMath.scramble(victim, t, frameSeed);
    }

    static int displayScore(long now) {
        float t = Tween.Ease.OUT_CUBIC.apply(clamp01((now - scoreStartMs) / (float) SCORE_MS));
        shownScore = Math.round(shownScoreFrom + (total - shownScoreFrom) * t);
        return shownScore;
    }

    /** 规格 §3.2：「连杀 ×N」标签 scale 0.5→1 outBack 弹入。 */
    static float streakTagScale(long now) {
        if (streak < 2) {
            return 0f;
        }
        float t = clamp01((now - streakTagStartMs) / (float) STREAK_TAG_MS);
        return 0.5f + 0.5f * Tween.Ease.OUT_BACK.apply(t);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : Math.min(v, 1f);
    }
}
