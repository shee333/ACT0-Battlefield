package org.shee33.act0.battlefield.client;

import org.shee33.act0.battlefield.network.CapturePointEventPacket;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 客户端据点状态边沿事件缓存：驱动 HUD 顶部横幅（220ms 滑入淡入 + 停留 + 280ms 淡出）
 * 与小地图据点图标的一次性 600ms 提亮反馈。
 *
 * <p>用 {@link Deque} 缓存多个据点同一时刻触发事件的情况（参照 {@link ClientKillFeed}）；
 * 横幅同一时刻只展示队首一条，其余排队依次淡入展示。每次 {@link #poll()} 由渲染帧调用一次，
 * 渡染层负责用 age/holdMs 自行做 ease-out 计算（不在此处重复实现，复用
 * {@code BattlefieldHudOverlay} 已有的 {@code easeOut}/{@code withAlpha}）。
 */
public final class ClientCapturePointEvent {

    public static final long BANNER_IN_MS = 220L;
    public static final long BANNER_OUT_MS = 280L;
    private static final long MINIMAP_PULSE_MS = 600L;
    private static final int MAX_QUEUE = 4;

    private static final Deque<Entry> QUEUE = new ArrayDeque<>();
    private static final Map<Integer, Long> pointPulseAt = new LinkedHashMap<>();
    private static final Map<Integer, Snapshot> lastEventByPoint = new LinkedHashMap<>();

    @Nullable
    private static Entry active;
    private static long activeStartedMs;

    private ClientCapturePointEvent() {
    }

    /** 断开服务器连接时兜底清空，防止下次连到另一个世界/服务器时播放上一局遗留的据点事件横幅。 */
    static void reset() {
        QUEUE.clear();
        pointPulseAt.clear();
        lastEventByPoint.clear();
        active = null;
        activeStartedMs = 0L;
    }

    /** 服务端事件包到达时调用。 */
    public static void trigger(int pointId, CapturePointEventPacket.Kind kind, int factionCode) {
        long now = System.currentTimeMillis();
        pointPulseAt.put(pointId, now);
        lastEventByPoint.put(pointId, new Snapshot(kind, factionCode, now));
        QUEUE.addLast(new Entry(pointId, kind, factionCode));
        while (QUEUE.size() > MAX_QUEUE) {
            QUEUE.removeFirst();
        }
    }

    /**
     * 非消费式地查看某据点最近一次边沿事件快照;与 {@link #poll()} 的横幅队列互不干扰(那是 FIFO
     * 消费式的),供 {@link CaptureFocusAnimator} 边沿检测"是否刚发生中立化/占领完成"，
     * 用于驱动 FLIP 特写的两轮制切换与完成确认，不影响横幅本身的显示逻辑。
     */
    @Nullable
    public static Snapshot latestEvent(int pointId) {
        return lastEventByPoint.get(pointId);
    }

    /** 每帧调用一次：推进队首横幅生命周期，过期后自动弹出下一条。可能返回 {@code null}。 */
    @Nullable
    public static Active poll() {
        long now = System.currentTimeMillis();
        if (active == null) {
            active = QUEUE.pollFirst();
            if (active == null) {
                return null;
            }
            activeStartedMs = now;
        }
        long age = now - activeStartedMs;
        long holdMs = holdMsFor(active.kind());
        if (age >= BANNER_IN_MS + holdMs + BANNER_OUT_MS) {
            active = null;
            return null;
        }
        return new Active(active.pointId(), active.kind(), active.factionCode(), age, holdMs);
    }

    /**
     * 某据点最近一次事件距今的毫秒数；没有记录返回 -1。
     *
     * <p>小地图的双波扩散环需要原始年龄而不是归一化强度：第二波要延迟 250ms 起跑，
     * 而 {@link #minimapPulse} 已经把时间轴压成 0~1 且到 600ms 就归零，拿不回来。
     */
    public static long minimapEventAgeMs(int pointId) {
        Long at = pointPulseAt.get(pointId);
        return at == null ? -1L : System.currentTimeMillis() - at;
    }

    /** 小地图某据点的一次性提亮强度 [0,1]：600ms 内线性衰减到 0，不循环。 */
    public static float minimapPulse(int pointId) {
        Long at = pointPulseAt.get(pointId);
        if (at == null) {
            return 0f;
        }
        long age = System.currentTimeMillis() - at;
        if (age >= MINIMAP_PULSE_MS) {
            return 0f;
        }
        return 1.0f - (age / (float) MINIMAP_PULSE_MS);
    }

    /** 停留时长：夺回 &gt; 失守 &gt; 首占 &gt; 争夺开始，夺回视觉上最强烈。 */
    private static long holdMsFor(CapturePointEventPacket.Kind kind) {
        return switch (kind) {
            case STARTED -> 900L;
            case CAPTURED_NEW -> 1400L;
            case CAPTURED_RECOVERED -> 1800L;
            case LOST -> 1500L;
        };
    }

    private record Entry(int pointId, CapturePointEventPacket.Kind kind, int factionCode) {
    }

    /** 当前应渲染的横幅快照：{@code age} 为距开始的毫秒数，{@code holdMs} 为纯停留时长（不含 in/out）。 */
    public record Active(int pointId, CapturePointEventPacket.Kind kind, int factionCode, long age, long holdMs) {
    }

    /** {@link #latestEvent(int)} 的非消费式快照：{@code atMs} 用于边沿检测（与上次读到的时间戳比较）。 */
    public record Snapshot(CapturePointEventPacket.Kind kind, int factionCode, long atMs) {
    }
}
