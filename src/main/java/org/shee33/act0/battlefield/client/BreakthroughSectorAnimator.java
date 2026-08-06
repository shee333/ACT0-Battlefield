package org.shee33.act0.battlefield.client;

import net.minecraft.util.Mth;
import org.shee33.act0.battlefield.network.BreakthroughHudDto;
import org.shee33.act0.battlefield.network.BreakthroughPointDto;

import java.util.ArrayList;
import java.util.List;

/**
 * 突破模式区域级动效状态机 —— 占点 HUD 动效规格文档 §3.2/§3.3/§3.4:
 * 区域目标入场(落下)、区域突破序列(横幅 + 战线扫过 + 进度条点亮 + 旧图标退场)、
 * 区域标签遮罩换字、终局横幅 + 径向碎片爆炸。
 *
 * <p>区域推进信号:纯客户端推断,每帧比较 {@link BreakthroughHudDto#currentSectorId()} 是否变化
 * (§3.3 末段"数据来源与触发信号"),不新增网络包。
 *
 * <p>HUD 提前置空的安全网:最后一个区域突破时,服务端 {@code BreakthroughMatch#end} 会在同一 tick
 * 内清空 HUD(见 {@code checkSectorAdvance} 的补发广播注释),网络时序上不能保证客户端一定能在清空
 * 前渡染出中间帧。因此本类缓存最近一次非空 HUD 快照,只要动效序列仍在播放({@link #effectiveHud}
 * 返回非 {@code null}),即便服务端已经清空,也继续用缓存快照渡染,直到整套演出播完。
 */
final class BreakthroughSectorAnimator {

    private BreakthroughSectorAnimator() {
    }

    // ---- 入场动效参数(§3.2) ----
    private static final float ENTRANCE_OFFSET_PX = 34f;
    private static final long ENTRANCE_DUR_MS = 420L;
    private static final long ENTRANCE_STAGGER_MS = 120L;

    // ---- 区域突破序列时序(§3.3),均相对 triggerMs;文档"以下延迟均相对序列起点"本身还有一个
    // 序列起点前置的 350ms 等待(§3.3 表头"延迟350ms启动"),这里直接把该 350ms 并入下列常量。
    private static final long BAR_START = 350L, BAR_GROW_DUR = 400L;
    private static final long TITLE_START = 500L, TITLE_DUR = 500L;
    private static final long SUBTITLE_START = 650L, SUBTITLE_DUR = 500L;
    private static final long SWEEP_START = 700L, SWEEP_DUR = 900L;
    private static final long SWEEP_FADE_START = 1600L, SWEEP_FADE_DUR = 600L;
    private static final long PIP_START = 700L, PIP_DUR = 300L;
    private static final long OLD_EXIT_START = 700L, OLD_EXIT_DUR = 300L, OLD_EXIT_STAGGER = 80L;
    private static final long BANNER_TEXT_EXIT_START = 2000L, BANNER_TEXT_EXIT_DUR = 300L;
    private static final long BANNER_BAR_RETREAT_START = 2100L, BANNER_BAR_RETREAT_DUR = 300L;
    /** 横幅完整播放完毕(含收回)的时刻——也是"是否为终局"分支点:非终局继续走标签换字,终局直接进终幕。 */
    private static final long BANNER_DONE = 2400L;
    private static final long LABEL_OUT_START = BANNER_DONE, LABEL_OUT_DUR = 250L;
    private static final long LABEL_IN_START = LABEL_OUT_START + LABEL_OUT_DUR, LABEL_IN_DUR = 300L;
    private static final long LABEL_DONE = LABEL_IN_START + LABEL_IN_DUR; // 2950

    // ---- 终局(§3.4) ----
    private static final long FINAL_BANNER_DUR = 500L;
    private static final long SHARD_DUR = 600L;
    private static final int SHARD_COUNT = 12;
    private static final float SHARD_DISTANCE_PX = 100f;

    private static final String[] CN_NUM = {"一", "二", "三", "四", "五", "六", "七", "八", "九", "十"};

    private enum Phase { IDLE, BREAK, FINAL }

    private static Phase phase = Phase.IDLE;
    private static long triggerMs;
    private static int brokenSectorIndex;
    private static boolean finalBreak;
    private static List<BreakthroughPointDto> breakingOldPoints = List.of();

    private static long finalStartMs = -1L;

    private static int lastSectorId = Integer.MIN_VALUE;
    private static int lastTotalSectors;
    private static List<BreakthroughPointDto> lastRowSnapshot = List.of();

    private static long entranceStartMs = -1L;
    private static List<Integer> entranceOrder = List.of();

    private static BreakthroughHudDto lastNonNullHud;

    /**
     * 每帧调用一次的总入口:更新内部状态机并返回本帧应实际用于渡染的 HUD 快照
     * (可能是缓存快照,详见类文档的"安全网"说明);{@code null} 表示应完全隐藏突破模式 HUD。
     */
    static BreakthroughHudDto effectiveHud(BreakthroughHudDto live) {
        long now = Tween.now();
        if (live != null) {
            if (phase == Phase.FINAL) {
                // 新一局突破对局已经开始(旧局的终局横幅还没被外部清理掉),重置整套状态机。
                reset();
            }
            lastNonNullHud = live;
            detectSectorChange(live, now);
        }
        BreakthroughHudDto activeHud = live != null ? live : lastNonNullHud;
        if (activeHud != null) {
            tickPhase(now, activeHud);
        }
        if (live != null) {
            return live;
        }
        return phase == Phase.IDLE ? null : lastNonNullHud;
    }

    /** 包内可见（非 private）：{@link ClientLifecycleHandler} 在断线时也会调用它兜底清空，
     * 防止下次连到另一个世界/服务器时播放上一局遗留的区域突破动画。 */
    static void reset() {
        phase = Phase.IDLE;
        lastSectorId = Integer.MIN_VALUE;
        lastRowSnapshot = List.of();
        entranceStartMs = -1L;
        entranceOrder = List.of();
        finalStartMs = -1L;
    }

    private static void detectSectorChange(BreakthroughHudDto hud, long now) {
        if (lastSectorId == Integer.MIN_VALUE) {
            lastSectorId = hud.currentSectorId();
            lastTotalSectors = hud.totalSectors();
            lastRowSnapshot = pointsOfSector(hud, lastSectorId);
            beginEntrance(lastRowSnapshot, now);
            return;
        }
        if (phase == Phase.IDLE && hud.currentSectorId() != lastSectorId) {
            boolean isFinal = hud.currentSectorId() >= lastTotalSectors;
            beginBreakSequence(lastSectorId, lastRowSnapshot, isFinal, now);
            lastSectorId = hud.currentSectorId();
            lastTotalSectors = hud.totalSectors();
        } else if (phase == Phase.IDLE) {
            lastRowSnapshot = pointsOfSector(hud, hud.currentSectorId());
        }
    }

    private static void tickPhase(long now, BreakthroughHudDto hudForEntrance) {
        if (phase != Phase.BREAK) {
            return;
        }
        long t = now - triggerMs;
        long breakEnd = finalBreak ? BANNER_DONE : LABEL_DONE;
        if (t >= breakEnd) {
            if (finalBreak) {
                phase = Phase.FINAL;
                finalStartMs = now;
            } else {
                phase = Phase.IDLE;
                List<BreakthroughPointDto> pts = pointsOfSector(hudForEntrance, hudForEntrance.currentSectorId());
                lastRowSnapshot = pts;
                beginEntrance(pts, now);
            }
        }
    }

    private static void beginBreakSequence(int brokenIdx, List<BreakthroughPointDto> oldPts, boolean isFinal, long now) {
        brokenSectorIndex = brokenIdx;
        breakingOldPoints = oldPts;
        finalBreak = isFinal;
        triggerMs = now;
        phase = Phase.BREAK;
    }

    private static void beginEntrance(List<BreakthroughPointDto> pts, long now) {
        entranceStartMs = now;
        List<Integer> order = new ArrayList<>(pts.size());
        for (BreakthroughPointDto p : pts) {
            order.add(p.pointId());
        }
        entranceOrder = order;
    }

    private static List<BreakthroughPointDto> pointsOfSector(BreakthroughHudDto hud, int sectorIndex) {
        List<BreakthroughPointDto> list = new ArrayList<>();
        for (BreakthroughPointDto p : hud.points()) {
            if (p.sectorIndex() == sectorIndex) {
                list.add(p);
            }
        }
        return list;
    }

    // ---- 是否需要抑制"正常当前区域行"渡染(突破序列播放期间,当前行改由 exitIcons()/横幅接管) ----
    static boolean isBreakSequenceActive() {
        return phase == Phase.BREAK;
    }

    static boolean isFinalActive() {
        return phase == Phase.FINAL;
    }

    /** 当前区域目标行(含入场落下动效);突破序列播放期间返回空列表(改由 {@link #exitIcons} 接管)。 */
    static List<RowIcon> currentRowIcons(BreakthroughHudDto hud, long now) {
        if (phase != Phase.IDLE) {
            return List.of();
        }
        List<BreakthroughPointDto> pts = pointsOfSector(hud, hud.currentSectorId());
        List<RowIcon> result = new ArrayList<>(pts.size());
        for (BreakthroughPointDto p : pts) {
            result.add(new RowIcon(p.pointId(), p.name(), p.owner(), p.progress(), p.x(), p.y(), p.z(),
                    entranceOffsetY(p.pointId(), now), entranceAlpha(p.pointId(), now)));
        }
        return result;
    }

    private static float entranceOffsetY(int pointId, long now) {
        int idx = entranceOrder.indexOf(pointId);
        if (idx < 0 || entranceStartMs < 0) {
            return 0f;
        }
        long age = now - entranceStartMs - idx * ENTRANCE_STAGGER_MS;
        if (age <= 0) {
            return -ENTRANCE_OFFSET_PX;
        }
        if (age >= ENTRANCE_DUR_MS) {
            return 0f;
        }
        float t = Tween.Ease.OUT_BACK.apply(age / (float) ENTRANCE_DUR_MS);
        return -ENTRANCE_OFFSET_PX * (1f - t);
    }

    private static float entranceAlpha(int pointId, long now) {
        int idx = entranceOrder.indexOf(pointId);
        if (idx < 0 || entranceStartMs < 0) {
            return 1f;
        }
        long age = now - entranceStartMs - idx * ENTRANCE_STAGGER_MS;
        if (age <= 0) {
            return 0f;
        }
        if (age >= ENTRANCE_DUR_MS) {
            return 1f;
        }
        float t = Tween.Ease.OUT_BACK.apply(age / (float) ENTRANCE_DUR_MS);
        return Mth.clamp(t, 0f, 1f);
    }

    /** 突破序列播放期间,旧区域目标图标的逐个上飞退场(§3.3:300ms inCubic,错峰80ms)。 */
    static List<RowIcon> exitIcons(long now) {
        if (phase != Phase.BREAK) {
            return List.of();
        }
        long t = now - triggerMs;
        List<RowIcon> result = new ArrayList<>(breakingOldPoints.size());
        for (int i = 0; i < breakingOldPoints.size(); i++) {
            BreakthroughPointDto p = breakingOldPoints.get(i);
            long localStart = OLD_EXIT_START + i * OLD_EXIT_STAGGER;
            long age = t - localStart;
            float offsetY;
            float alpha;
            if (age < 0) {
                offsetY = 0f;
                alpha = 1f;
            } else if (age >= OLD_EXIT_DUR) {
                continue; // 已飞出屏幕,不再渡染
            } else {
                float et = Tween.Ease.IN_CUBIC.apply(age / (float) OLD_EXIT_DUR);
                offsetY = -40f * et;
                alpha = 1f - et;
            }
            result.add(new RowIcon(p.pointId(), p.name(), p.owner(), p.progress(), p.x(), p.y(), p.z(), offsetY, alpha));
        }
        return result;
    }

    // ---- 区域标签(持续显示 + 突破时的遮罩换字,§3.4) ----

    static String labelText(BreakthroughHudDto hud, long now) {
        if (phase == Phase.BREAK) {
            long t = now - triggerMs;
            if (t >= LABEL_OUT_START && t < LABEL_IN_START) {
                return "第 " + (brokenSectorIndex + 1) + " 区域";
            }
        }
        return "第 " + (hud.currentSectorId() + 1) + " 区域";
    }

    /** 标签遮罩换字的垂直偏移比例(以标签行高为单位):0=正常位置,负值向上滑出,正值(110%)在下方待入场。 */
    static float labelOffsetFrac(long now) {
        if (phase != Phase.BREAK) {
            return 0f;
        }
        long t = now - triggerMs;
        if (t < LABEL_OUT_START) {
            return 0f;
        }
        if (t < LABEL_IN_START) {
            float p = Tween.Ease.IN_CUBIC.apply((t - LABEL_OUT_START) / (float) LABEL_OUT_DUR);
            return -1.10f * p;
        }
        if (t < LABEL_DONE) {
            float p = Tween.Ease.OUT_EXPO.apply((t - LABEL_IN_START) / (float) LABEL_IN_DUR);
            return 1.10f - 1.10f * p;
        }
        return 0f;
    }

    // ---- 区域突破横幅(§3.3) ----

    static boolean bannerVisible(long now) {
        return phase == Phase.BREAK && (now - triggerMs) < BANNER_DONE;
    }

    static String bannerTitle() {
        String cn = brokenSectorIndex < CN_NUM.length ? CN_NUM[brokenSectorIndex] : String.valueOf(brokenSectorIndex + 1);
        return "第 " + cn + " 区域 — 已突破";
    }

    static String bannerSubtitle() {
        return "战线正在推进";
    }

    /** 横幅横条 scaleX(0→1 生长,随后收回 1→0),对应 §3.3 表第 1 行与末行。 */
    static float bannerBarScaleX(long now) {
        long t = now - triggerMs;
        if (t < BAR_START) {
            return 0f;
        }
        if (t < BANNER_BAR_RETREAT_START) {
            float p = Tween.Ease.OUT_EXPO.apply(Mth.clamp((t - BAR_START) / (float) BAR_GROW_DUR, 0f, 1f));
            return p;
        }
        if (t < BANNER_DONE) {
            float p = Tween.Ease.IN_CUBIC.apply((t - BANNER_BAR_RETREAT_START) / (float) BANNER_BAR_RETREAT_DUR);
            return 1f - p;
        }
        return 0f;
    }

    /** 主标题遮罩滑入/滑出的垂直偏移比例(以文字行高为单位)。 */
    static float bannerTitleOffsetFrac(long now) {
        return bannerTextOffsetFrac(now, TITLE_START, TITLE_DUR);
    }

    /** 副标题遮罩滑入/滑出,与主标题共用退场窗口(§3.3:两者同一条 tw 调用一起上滑)。 */
    static float bannerSubtitleOffsetFrac(long now) {
        return bannerTextOffsetFrac(now, SUBTITLE_START, SUBTITLE_DUR);
    }

    private static float bannerTextOffsetFrac(long now, long enterStart, long enterDur) {
        long t = now - triggerMs;
        if (t < enterStart) {
            return 1.10f;
        }
        if (t < enterStart + enterDur) {
            float p = Tween.Ease.OUT_EXPO.apply((t - enterStart) / (float) enterDur);
            return 1.10f - 1.10f * p;
        }
        if (t < BANNER_TEXT_EXIT_START) {
            return 0f;
        }
        if (t < BANNER_TEXT_EXIT_START + BANNER_TEXT_EXIT_DUR) {
            float p = Tween.Ease.IN_CUBIC.apply((t - BANNER_TEXT_EXIT_START) / (float) BANNER_TEXT_EXIT_DUR);
            return -1.15f * p;
        }
        return -1.15f;
    }

    // ---- 战线扫过(§3.3 招牌效果) ----

    /** 竖线/浸染层扫过进度 [0,1](随屏幕宽度线性映射);未激活返回 0。 */
    static float sweepXFrac(long now) {
        if (phase != Phase.BREAK) {
            return 0f;
        }
        long t = now - triggerMs;
        if (t < SWEEP_START) {
            return 0f;
        }
        if (t < SWEEP_START + SWEEP_DUR) {
            return Tween.Ease.OUT_CUBIC.apply((t - SWEEP_START) / (float) SWEEP_DUR);
        }
        return 1f;
    }

    /** 竖线/浸染层的整体不透明度倍率:扫过期间为 1,扫完 600ms 淡出归零。 */
    static float sweepAlpha(long now) {
        if (phase != Phase.BREAK) {
            return 0f;
        }
        long t = now - triggerMs;
        if (t < SWEEP_START) {
            return 0f;
        }
        if (t < SWEEP_FADE_START) {
            return 1f;
        }
        if (t < SWEEP_FADE_START + SWEEP_FADE_DUR) {
            return 1f - Tween.Ease.OUT_CUBIC.apply((t - SWEEP_FADE_START) / (float) SWEEP_FADE_DUR);
        }
        return 0f;
    }

    // ---- 区域进度条 pip 点亮弹跳(§3.3) ----

    static int breakingPipIndex() {
        return phase == Phase.BREAK ? brokenSectorIndex : -1;
    }

    static float pipBounceScaleY(long now) {
        long t = now - triggerMs;
        if (t < PIP_START || t >= PIP_START + PIP_DUR) {
            return 1f;
        }
        float raw = (t - PIP_START) / (float) PIP_DUR;
        float eased = Tween.Ease.OUT_BACK.apply(raw);
        return 1f + 1.5f * (float) Math.sin(eased * Math.PI);
    }

    // ---- 终局(§3.4):全线突破横幅 + 12 枚碎片径向爆开 ----

    static float finalBannerAlpha(long now) {
        if (phase != Phase.FINAL) {
            return 0f;
        }
        float x = finalRawX(now);
        return Mth.clamp(x, 0f, 1f);
    }

    static float finalBannerScale(long now) {
        if (phase != Phase.FINAL) {
            return 0.9f;
        }
        return 0.9f + 0.1f * finalRawX(now);
    }

    private static float finalRawX(long now) {
        float t = Mth.clamp((now - finalStartMs) / (float) FINAL_BANNER_DUR, 0f, 1f);
        return Tween.Ease.OUT_BACK.apply(t);
    }

    /** 12 枚 5x5px 蓝色碎片按 30° 等分径向爆开的当前位移/透明度;已飞出生命周期的碎片不返回。 */
    static List<float[]> shardOffsets(long now) {
        if (phase != Phase.FINAL) {
            return List.of();
        }
        long age = now - finalStartMs;
        if (age < 0 || age >= SHARD_DUR) {
            return List.of();
        }
        float x = Tween.Ease.OUT_CUBIC.apply(age / (float) SHARD_DUR);
        List<float[]> result = new ArrayList<>(SHARD_COUNT);
        for (int i = 0; i < SHARD_COUNT; i++) {
            double angle = Math.toRadians(i * 30.0);
            float dx = (float) Math.cos(angle) * SHARD_DISTANCE_PX * x;
            float dy = (float) Math.sin(angle) * SHARD_DISTANCE_PX * x;
            result.add(new float[]{dx, dy, 1f - x});
        }
        return result;
    }

    /** 单个据点图标行渡染快照:{@code offsetY} 相对静止位置的像素偏移,{@code alpha} 不透明度倍率。 */
    record RowIcon(int pointId, String name, int owner, int progress, double x, double y, double z,
                    float offsetY, float alpha) {
    }
}
