package org.shee33.act0.battlefield.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import org.shee33.act0.battlefield.network.BattlefieldNetwork;
import org.shee33.act0.battlefield.network.BattlefieldRoomDto;
import org.shee33.act0.battlefield.network.RequestBattlefieldRoomListPacket;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 对局浏览器（《游戏浏览器动效双版本规格文档》§1 共享底盘 + §3「浏览器 B · 战地模式 · 对局浏览器」）
 * 的动效与绘制承载类。
 *
 * <p><b>为什么绘制也在这里而不在 Screen 里</b>：{@link Tween}/{@link Tween.Ease}/{@link Tween.Anim}
 * 是 {@code client} 包的<b>包私有</b>类型，Java 的包私有可见性不会被 {@code client.screen} 子包继承，
 * 因此所有直接触碰补间引擎的状态与逻辑必须留在本包内。这与本仓库既有的
 * {@link DeployMapPanel} + {@code client.screen.BattlefieldDeployScreen} 分工完全一致：
 * Animator 承载状态机 + 视觉，Screen 只做布局尺寸/输入转发/网络发包。
 *
 * <p><b>与 demo（§5 HTML 源码）的对照口径</b>：动效的<b>时长 / 缓动曲线 / 错峰间隔 / 阶段时序</b>
 * 全部照抄文档数值，不自创；<b>布局像素</b>按 Minecraft GUI 单位（字体行高 9、8px 网格）等比重算，
 * 逐处在常量注释里标注原始 CSS 值。文档里靠 CSS 渐变实现的东西（扫描线、缩略图、扫光）改用
 * 「纯色 + 透明度分层」逼近 —— AGENTS.md 战地视觉规范明令禁止渐变。
 *
 * <p><b>与 demo 数据模型的真实差异</b>（本仓库没有的概念，对应逻辑已删除而非硬造）：
 * <ul>
 *   <li><b>无排队 / 无满员</b>：本 mod 的 {@code quickJoin}/待命名单加入永远成功（容量 cap 是
 *       {@code Integer.MAX_VALUE}），所以没有「满员变灰 / 排队 +N / 加入排队 · 第 N 位」这条支线，
 *       加入按钮恒为可点的「加 入」态。人数迷你条的三档配色仍保留（它表达的是「热度」而非「能否加入」）。</li>
 *   <li><b>无延迟列</b>：文档 §3 本身已移除。</li>
 *   <li><b>多了「等待中」行</b>：本仓库的 {@link BattlefieldRoomDto#running()}{@code =false} 行是
 *       已布置好地图、正在等人开局的待命世界，它没有票数概念，因此手风琴展开时显示
 *       「等待中 · 还差 N 人开局」而非票数对峙条。</li>
 *   <li><b>实时跳动不伪造</b>：demo 里的「每 2.4s/1.5s 随机改数」纯属网页演示。这里的驱动源是
 *       服务端快照推送（{@link ClientBattlefieldRoomList#accept}）—— 新旧快照逐字段比对，
 *       变化了才播滚轮换字 + 行高亮 + 条宽补间，方向由「增/减」决定。</li>
 * </ul>
 *
 * <p><b>配色</b>：文档的金 {@code #ffd76a} 是 ACT0-Arcade 的专属视觉语言，本仓库不继承
 * （见 AGENTS.md 菜单动效规范的仓库范围声明）。「主操作 / 选中指示 / 激活」这个语义位统一改用
 * ALPHA 蓝 {@link #ACCENT}；「进行中/警告」档位改用本仓库既有的橙黄 {@link #WARN}。
 */
public final class BattlefieldRoomBrowserAnimator {

    // =====================================================================
    // 配色（全部取自本仓库既有常量：DeployMapPanel / BattlefieldHudOverlay / PixelTheme）
    // =====================================================================

    /** 舞台底板（文档 #0f1216）。 */
    private static final int STAGE_BG = 0xFF0F1216;
    /** 更深一层的底板，用于表头/手风琴的下沉分层（本仓库 0xFF0A0E12）。 */
    private static final int STAGE_BG_DEEP = 0xFF0A0E12;
    /** 网格线：文档 44px 网格 0.02 白 —— MC 改 16px 网格，alpha 同为约 0.02。 */
    private static final int GRID_LINE = 0x06FFFFFF;
    /** 主操作 / 选中指示 / 激活 / ALPHA 阵营（替代文档金色）。 */
    private static final int ACCENT = 0xFF4FA8FF;
    /** BRAVO 阵营红。 */
    private static final int BRAVO = 0xFFD94A4A;
    /** 良好 / 等待中 / 成功绿。 */
    private static final int GREEN = 0xFF6EE27E;
    /** 危险 / 警告橙黄（人数 ≥75% 档，替代文档此处的金色）。 */
    private static final int WARN = 0xFFFF8C00;
    /** 正文浅灰白。 */
    private static final int TEXT = 0xFFE8EDF2;
    /** 强调正文（激活标签 / 标题）。 */
    private static final int TEXT_STRONG = 0xFFFFFFFF;
    /** 分割线 #3A3A3A @40%。 */
    private static final int DIVIDER = 0x663A3A3A;
    /** 加入转场遮罩 rgba(8,10,13,0.88)。 */
    private static final int OVERLAY_BG = 0xE0080A0D;
    /** 手风琴底 rgba(255,255,255,0.03)。 */
    private static final int ACC_BG = 0x08FFFFFF;
    /** 对峙条底槽 rgba(255,255,255,0.08)。 */
    private static final int BAR_TRACK = 0x14FFFFFF;
    /** 迷你人数条底槽 rgba(255,255,255,0.10)。 */
    private static final int MINI_TRACK = 0x1AFFFFFF;
    /** 转场进度条底槽 rgba(255,255,255,0.12)。 */
    private static final int OVERLAY_TRACK = 0x1FFFFFFF;

    // =====================================================================
    // 布局（doc CSS px → MC GUI px；纵向节奏按 8px 网格重排，注释保留原值）
    // =====================================================================

    /** 左右外边距（doc left/right 20）。 */
    private static final int PAD = 16;
    /** 标题行 y（doc top 16）。 */
    private static final int TITLE_Y = 8;
    /** 副标题行 y（doc 标题下 +3）。 */
    private static final int SUBTITLE_Y = 20;
    /** 右上计数/刷新按钮行 y（doc top 20）。 */
    private static final int TOPRIGHT_Y = 10;
    /** 刷新按钮边长（doc 26×26）。 */
    private static final int REFRESH_SIZE = 14;
    /** 计数文字与刷新按钮的间距（doc gap 12）。 */
    private static final int REFRESH_GAP = 8;
    /** 筛选标签行 y（doc top 64）。 */
    private static final int TABS_Y = 40;
    /** 标签间距（doc gap 22 → 归到 8px 网格）。 */
    private static final int TAB_GAP = 16;
    /** 滑动下划线 y（doc top 86）。 */
    private static final int UNDERLINE_Y = 52;
    /** 滑动下划线厚度（doc 2px）。 */
    private static final int UNDERLINE_H = 2;
    /** 表头 y（doc top 100）。 */
    private static final int HEADER_Y = 58;
    /** 表头下分隔线 y（doc top 116）。 */
    private static final int DIVIDER_Y = 70;
    /** 列表区顶 y（doc top 122）。 */
    private static final int LIST_Y = 76;
    /** 列表区底部留白（doc bottom 14）。 */
    private static final int LIST_BOTTOM_PAD = 12;
    /** 行高（doc padding 7 + 双行内容 ≈ 40）。 */
    private static final int ROW_H = 28;
    /** 手风琴固定高度 —— 文档 §3.4 明确的 64px，原值照搬。 */
    private static final int ACC_H = 64;
    /** 行内左右内边距（doc padding 0 12）。 */
    private static final int ROW_PAD_X = 8;
    /** 悬停缩进量（doc 12→17，+5 原值照搬）。 */
    private static final int HOVER_INDENT = 5;
    /** ★ 收藏列宽（doc flex 0 0 26px）。 */
    private static final int STAR_W = 14;
    /** 地图缩略占位宽（doc 40×26）。 */
    private static final int THUMB_W = 24;
    /** 地图缩略占位高。 */
    private static final int THUMB_H = 16;
    /** 迷你人数条宽（doc 52×4）。 */
    private static final int MINI_BAR_W = 36;
    /** 迷你人数条高。 */
    private static final int MINI_BAR_H = 3;
    /** 手风琴内加入按钮宽（doc 110px）。 */
    private static final int JOIN_BTN_W = 52;
    /** 手风琴内加入按钮高（doc padding 8 0 + 13px 文字）。 */
    private static final int JOIN_BTN_H = 18;
    /** 票数对峙条高（doc 6px，原值照搬）。 */
    private static final int TICKET_BAR_H = 6;
    /** 票数大字列宽（doc width 38）。 */
    private static final int TICKET_NUM_W = 26;
    /** 转场进度条宽（doc 240）。 */
    private static final int OVERLAY_BAR_W = 120;

    // =====================================================================
    // 动效参数 —— 全部照抄文档 §1.3 / §3，不自创
    // =====================================================================

    /** 开场 .ch 块淡入 260ms outCubic。 */
    private static final long CH_MS = 260L;
    /** 开场 .ch 块错峰 70ms/块。 */
    private static final long CH_STEP_MS = 70L;
    /** 开场下划线 0→标签宽 320ms outExpo，延迟 280。 */
    private static final long UNDERLINE_OPEN_MS = 320L;
    private static final long UNDERLINE_OPEN_DELAY_MS = 280L;
    /** 标签切换下划线 FLIP 滑动 220ms outCubic。 */
    private static final long UNDERLINE_SLIDE_MS = 220L;
    /** 列表行入场 260ms outCubic，错峰 50ms/行，translateY 14→0。 */
    private static final long ROW_ENTER_MS = 260L;
    private static final long ROW_STEP_MS = 50L;
    private static final float ROW_ENTER_DY = 14f;
    /** 标签切换旧行下沉淡出 140ms inCubic，错峰 18ms/行，translateY 0→8。 */
    private static final long ROW_EXIT_MS = 140L;
    private static final long ROW_EXIT_STEP_MS = 18L;
    private static final float ROW_EXIT_DY = 8f;
    /** 刷新时旧行淡出 130ms inCubic，错峰 15ms/行。 */
    private static final long REFRESH_EXIT_MS = 130L;
    private static final long REFRESH_EXIT_STEP_MS = 15L;
    /** 刷新图标 rotate 360°，500ms outCubic。 */
    private static final long REFRESH_SPIN_MS = 500L;
    /** 扫描线自列表顶扫到底 450ms outCubic。 */
    private static final long SCAN_MS = 450L;
    /** 行悬停缩进 140ms 双向补间。 */
    private static final long HOVER_MS = 140L;
    /** 展开指示金条（本仓库为 ACCENT 蓝条）scaleY 0→1 200ms outCubic / 收回 140ms inCubic。 */
    private static final long ACCENT_IN_MS = 200L;
    private static final long ACCENT_OUT_MS = 140L;
    /** 手风琴展开 240ms outCubic / 收起 200ms inCubic。 */
    private static final long ACC_OPEN_MS = 240L;
    private static final long ACC_CLOSE_MS = 200L;
    /** 对峙条揭示：蓝条 420ms outCubic 延迟 120；红条同款延迟 220。 */
    private static final long BAR_REVEAL_MS = 420L;
    private static final long BAR1_DELAY_MS = 120L;
    private static final long BAR2_DELAY_MS = 220L;
    /** 滚轮换字 190ms outCubic。 */
    private static final long ROLL_MS = 190L;
    /** 实时变动行整行蓝色高亮衰减 600ms，α 0.06→0。 */
    private static final long HIGHLIGHT_MS = 600L;
    private static final float HIGHLIGHT_ALPHA = 0.06f;
    /** 星标 outBack 弹跳 320ms：scale 0.6→1 + rotate 72°→0。 */
    private static final long STAR_MS = 320L;
    private static final float STAR_ROTATE_DEG = 72f;
    /** 收藏页取消收藏 → 收起后淡出移除 240ms inCubic，延迟 180。 */
    private static final long REMOVE_MS = 240L;
    private static final long REMOVE_DELAY_MS = 180L;
    /** 迷你人数条宽度追赶 250ms outCubic。 */
    private static final long MINI_CHASE_MS = 250L;
    /** 对峙条宽度追赶 300ms outCubic。 */
    private static final long TICKET_CHASE_MS = 300L;
    /** FLIP 重排 320ms outCubic，错峰 25ms/行。 */
    private static final long FLIP_MS = 320L;
    private static final long FLIP_STEP_MS = 25L;
    /** 加入按钮按压回弹 outBack 220ms，scale 0.92→1。 */
    private static final long PRESS_MS = 220L;
    /** 加入按钮扫光 350ms outCubic。 */
    private static final long SWEEP_MS = 350L;
    /** 空状态淡入 300ms outCubic，延迟 150。 */
    private static final long EMPTY_MS = 300L;
    private static final long EMPTY_DELAY_MS = 150L;

    // ---- §3.5 三段式加入转场时间轴（相对「遮罩开始出现」的毫秒偏移） ----
    /** 按下按钮 → 遮罩开始出现之间的先手反馈窗口（doc `await wait(200)`）。 */
    private static final long JOIN_PRESS_LEAD_MS = 200L;
    /** 遮罩淡入 250ms outCubic。 */
    private static final long JOIN_FADE_IN_MS = 250L;
    /** 第一段「正在连接」：条 0→42%，600ms outCubic，延迟 250。 */
    private static final long JOIN_S1_END_MS = 850L;
    /** 第二段「加载地图 · X」：条 →88%，700ms outCubic。 */
    private static final long JOIN_S2_END_MS = 1550L;
    /** 第三段「已连接」变绿：条 →100%，200ms outCubic。 */
    private static final long JOIN_S3_END_MS = 1750L;
    /** 停留 700ms。 */
    private static final long JOIN_HOLD_END_MS = 2450L;
    /** 淡出 300ms inCubic。 */
    private static final long JOIN_TOTAL_MS = 2750L;

    /** 筛选标签（§3.1：全部 / 征服 / 突破 / 收藏）。 */
    private static final String[] TABS = {"全部", "征服", "突破", "收藏"};

    // =====================================================================
    // 导出给 Screen 的类型（跨包边界只走这些，不暴露 Tween）
    // =====================================================================

    /** 加入转场播完后需要真正执行的加入请求 —— Screen 收到后才发命令并关屏。 */
    public record JoinRequest(String roomKey, boolean breakthrough) {
    }

    // =====================================================================
    // 状态
    // =====================================================================

    /** 列表整体的阶段：退场/扫描期间不接受数据重建，避免动效被中途打断。 */
    private enum ListPhase {
        IDLE,
        /** 标签切换：旧行下沉淡出中。 */
        EXIT_TAB,
        /** 刷新：旧行淡出 + 蓝色扫描线扫过中。 */
        EXIT_REFRESH
    }

    private final long openedAtMs;
    private final List<Row> rows = new ArrayList<>();

    private int curTab;
    private boolean sortActive;
    private boolean sortDesc = true;

    private ListPhase listPhase = ListPhase.IDLE;
    private long phaseEndMs;
    private boolean listBuilt;

    private final Tween.Anim refreshSpin = new Tween.Anim();
    private final Tween.Anim scan = new Tween.Anim();
    private final Tween.Anim underline = new Tween.Anim();
    private boolean underlineOpening = true;
    private float underlineFromX;
    private float underlineFromW;
    private float underlineToX;
    private float underlineToW;

    private final Tween.Anim emptyFade = new Tween.Anim();
    private boolean emptyShown;

    private String expandedKey = "";
    private boolean flipPending;
    private long sortBusyUntilMs = -1L;

    /** 列表滚动位置（首个可见行的下标）。恒被 {@link #clampScrollRow} 夹在合法区间内。 */
    private int scrollRow;

    private boolean joinActive;
    private long joinOverlayStartMs;
    private String joinKey = "";
    private String joinMapName = "";
    private boolean joinBreakthrough;
    private JoinRequest pendingJoin;

    // ---- 本帧布局/命中缓存（handleClick 只认这份，与 DeployMapPanel 同款手法） ----
    private final List<Hit> hits = new ArrayList<>();
    private final int[] tabX = new int[TABS.length];
    private final int[] tabW = new int[TABS.length];
    private int refreshX;
    private int sortX;
    private int sortW;
    private int listTop;
    private int listBottom;
    private int contentX;
    private int contentW;
    private int colStarX;
    private int colNameX;
    private int colNameW;
    private int colModeX;
    private int colModeW;
    private int colMapX;
    private int colMapW;
    private int colPlayersX;
    private int colPlayersW;

    public BattlefieldRoomBrowserAnimator() {
        this.openedAtMs = Tween.now();
    }

    // =====================================================================
    // 对外接口（Screen 只能看到这些）
    // =====================================================================

    /**
     * 服务端推送了新的房间快照。逐字段比对新旧值：变化的字段播方向性滚轮换字 + 条宽补间，
     * 该行整行播一次 600ms 蓝色高亮衰减 —— 这是文档「实时行高亮」在真实数据源下的正确形态
     * （demo 里的随机数只是网页演示，不搬）。仅当「行的集合」真的变了才整表重建级联，
     * 避免每 2 秒一次的心跳把开场动效重播一遍。
     */
    public void onRoomsUpdated() {
        if (listPhase != ListPhase.IDLE || !listBuilt) {
            return;
        }
        long now = Tween.now();
        List<BattlefieldRoomDto> data = filteredSorted();
        Set<String> liveKeys = new LinkedHashSet<>();
        for (Row r : rows) {
            if (!r.removing) {
                liveKeys.add(r.key);
            }
        }
        Set<String> newKeys = new LinkedHashSet<>();
        Map<String, BattlefieldRoomDto> byKey = new LinkedHashMap<>();
        for (BattlefieldRoomDto d : data) {
            newKeys.add(d.roomKey());
            byKey.put(d.roomKey(), d);
        }
        if (!liveKeys.equals(newKeys)) {
            rebuildList(now);
            return;
        }
        for (Row r : rows) {
            BattlefieldRoomDto nd = byKey.get(r.key);
            if (nd != null) {
                applyUpdate(r, nd, now);
            }
        }
    }

    /** busy 锁（文档 §1.3）：加入转场 / FLIP 排序期间锁定其余交互。 */
    public boolean isBusy() {
        return joinActive || Tween.now() < sortBusyUntilMs;
    }

    /** 加入转场播完后取走一次加入请求（取走即清空）；未就绪返回 {@code null}。 */
    public JoinRequest pollPendingJoin() {
        JoinRequest out = pendingJoin;
        pendingJoin = null;
        return out;
    }

    /**
     * 左键点击分发。返回 {@code true} 表示本屏幕消费了这次点击 —— 本界面铺满整屏且没有 vanilla
     * 控件，任何点击都归本类处理（未命中任何交互元素 = 文档的「空白点击」，收起手风琴）。
     */
    public boolean handleClick(double mx, double my) {
        long now = Tween.now();
        if (isBusy()) {
            return true;
        }
        if (inRect(mx, my, refreshX, TOPRIGHT_Y, REFRESH_SIZE, REFRESH_SIZE)) {
            startRefresh(now);
            return true;
        }
        for (int i = 0; i < TABS.length; i++) {
            if (inRect(mx, my, tabX[i], TABS_Y - 2, tabW[i], 13)) {
                setTab(i, now);
                return true;
            }
        }
        if (inRect(mx, my, sortX, HEADER_Y - 2, sortW, 13)) {
            doSort(now);
            return true;
        }
        // 行命中只在列表可视区域内有效:部分露出视口下沿的行,其矩形会伸到 listBottom 之下,
        // 那部分像素被 scissor 裁掉了、玩家根本看不见,不能仍然可点。
        if (isInListRegion(mx, my)) {
            for (Hit h : hits) {
                if (inRect(mx, my, h.sx, h.sy, h.sw, h.sh)) {
                    toggleStar(h.key, now);
                    return true;
                }
                if (h.hasJoin && inRect(mx, my, h.jx, h.jy, h.jw, h.jh)) {
                    startJoin(h.key, now);
                    return true;
                }
                if (inRect(mx, my, h.mx, h.my, h.mw, h.mh)) {
                    toggleAccordion(h.key, now);
                    return true;
                }
            }
        }
        collapseExpanded(now);
        return true;
    }

    /** 鼠标是否落在列表可视区域内（Screen 用它决定这次滚轮该不该交给列表）。 */
    public boolean isInListRegion(double mx, double my) {
        return mx >= contentX && mx < contentX + contentW && my >= listTop && my < listBottom;
    }

    /**
     * 滚轮滚动一行。返回 {@code true} 表示真的滚动了（到边界时返回 {@code false}，让 Screen 把事件
     * 交回 vanilla，避免"滚不动却也吞掉事件"）。
     */
    public boolean handleScroll(double delta) {
        if (isBusy()) {
            return false;
        }
        int next = scrollStep(scrollRow, delta, rows.size(), visibleRows(), currentExtraRows());
        if (next == scrollRow) {
            return false;
        }
        scrollRow = next;
        return true;
    }

    // =====================================================================
    // 渲染主流程
    // =====================================================================

    /** 渲染整个浏览器（含加入转场覆盖层）。{@code screenW/screenH} 即 Screen 的 width/height。 */
    public void render(GuiGraphics gg, Font font, int screenW, int screenH, int mouseX, int mouseY) {
        long now = Tween.now();
        layout(font, screenW, screenH);
        advancePhases(now);
        if (!listBuilt) {
            rebuildList(now);
        }
        dropRemovedRows();

        drawStage(gg, screenW, screenH);
        drawChrome(gg, font, now);
        drawList(gg, font, now, mouseX, mouseY);
        drawEmptyState(gg, font, now, screenW, screenH);
        drawScanLine(gg, now, screenW);
        drawJoinOverlay(gg, font, now, screenW, screenH);
    }

    private void layout(Font font, int screenW, int screenH) {
        contentX = PAD;
        contentW = Math.max(120, screenW - PAD * 2);
        listTop = LIST_Y;
        listBottom = Math.max(listTop + ROW_H, screenH - LIST_BOTTOM_PAD);

        int x = contentX;
        for (int i = 0; i < TABS.length; i++) {
            tabX[i] = x;
            tabW[i] = font.width(TABS[i]);
            x += tabW[i] + TAB_GAP;
        }

        int innerX = contentX + ROW_PAD_X;
        int innerW = contentW - ROW_PAD_X * 2;
        int rest = Math.max(60, innerW - STAR_W);
        colStarX = innerX;
        colNameW = Math.round(rest * 0.38f);
        colModeW = Math.round(rest * 0.14f);
        colMapW = Math.round(rest * 0.18f);
        colPlayersW = rest - colNameW - colModeW - colMapW;
        colNameX = colStarX + STAR_W;
        colModeX = colNameX + colNameW;
        colMapX = colModeX + colModeW;
        colPlayersX = colMapX + colMapW;

        sortX = colPlayersX;
        sortW = font.width("人数") + 8;
        refreshX = contentX + contentW - REFRESH_SIZE;
    }

    /** 推进「旧行退场 → 重建 → 级联入场」这类跨帧阶段（Java 没有 demo 里的 await 时间轴）。 */
    private void advancePhases(long now) {
        if (listPhase != ListPhase.IDLE && now >= phaseEndMs) {
            listPhase = ListPhase.IDLE;
            scan.reset();
            rebuildList(now);
        }
        if (joinActive && now - joinOverlayStartMs >= JOIN_TOTAL_MS) {
            joinActive = false;
            pendingJoin = new JoinRequest(joinKey, joinBreakthrough);
            collapseExpanded(now);
        }
    }

    private void dropRemovedRows() {
        long now = Tween.now();
        rows.removeIf(r -> r.removing && r.remove.isDone(now));
    }

    private void drawStage(GuiGraphics gg, int screenW, int screenH) {
        gg.fill(0, 0, screenW, screenH, STAGE_BG);
        // 文档的 44px 网格底纹 → MC 16px 网格，同为极低 alpha 的纯色细线（非渐变）。
        for (int gx = 0; gx <= screenW; gx += 16) {
            gg.fill(gx, 0, gx + 1, screenH, GRID_LINE);
        }
        for (int gy = 0; gy <= screenH; gy += 16) {
            gg.fill(0, gy, screenW, gy + 1, GRID_LINE);
        }
    }

    // =====================================================================
    // 顶部/筛选/表头（.ch 块 70ms 错峰级联）
    // =====================================================================

    private void drawChrome(GuiGraphics gg, Font font, long now) {
        float a0 = chunkAlpha(now, 0);
        if (a0 > 0.01f) {
            gg.drawString(font, "加入战场", contentX, TITLE_Y, withAlpha(TEXT_STRONG, a0), false);
            gg.drawString(font, "战地模式 · 对局浏览器", contentX, SUBTITLE_Y, withAlpha(TEXT, a0 * 0.4f), false);
        }

        float a1 = chunkAlpha(now, 1);
        if (a1 > 0.01f) {
            String count = visibleRowCount() + " 场对局";
            int cw = font.width(count);
            gg.drawString(font, count, refreshX - REFRESH_GAP - cw, TOPRIGHT_Y + 3, withAlpha(TEXT, a1 * 0.45f), false);
            drawRefreshButton(gg, now, a1);
        }

        float a2 = chunkAlpha(now, 2);
        if (a2 > 0.01f) {
            for (int i = 0; i < TABS.length; i++) {
                int color = i == curTab ? withAlpha(TEXT_STRONG, a2) : withAlpha(TEXT, a2 * 0.45f);
                gg.drawString(font, TABS[i], tabX[i], TABS_Y, color, false);
            }
        }
        drawUnderline(gg, now);

        float a3 = chunkAlpha(now, 3);
        if (a3 > 0.01f) {
            int dim = withAlpha(TEXT, a3 * 0.35f);
            gg.drawString(font, "对局", colNameX, HEADER_Y, dim, false);
            gg.drawString(font, "模式", colModeX, HEADER_Y, dim, false);
            gg.drawString(font, "地图", colMapX, HEADER_Y, dim, false);
            int sortColor = sortActive ? withAlpha(TEXT_STRONG, a3) : dim;
            gg.drawString(font, "人数", colPlayersX, HEADER_Y, sortColor, false);
            if (sortActive) {
                drawSortArrow(gg, colPlayersX + font.width("人数") + 3, HEADER_Y + 3, sortDesc, withAlpha(TEXT_STRONG, a3));
            }
        }

        float a4 = chunkAlpha(now, 4);
        if (a4 > 0.01f) {
            gg.fill(contentX, DIVIDER_Y, contentX + contentW, DIVIDER_Y + 1, withAlpha(DIVIDER, a4));
        }
        // 表头与列表之间的下沉分层：纯色 + 透明度，不用渐变。
        gg.fill(contentX, DIVIDER_Y + 1, contentX + contentW, listTop, withAlpha(STAGE_BG_DEEP, 0.5f));
    }

    /** 刷新按钮：1px 细描边方框 + 几何化「回转」指示（三段短线 + 箭头），点击时整体转 360°。 */
    private void drawRefreshButton(GuiGraphics gg, long now, float alpha) {
        int x = refreshX;
        int y = TOPRIGHT_Y;
        int c = withAlpha(TEXT, alpha * 0.7f);
        gg.fill(x, y, x + REFRESH_SIZE, y + 1, withAlpha(TEXT_STRONG, alpha * 0.15f));
        gg.fill(x, y + REFRESH_SIZE - 1, x + REFRESH_SIZE, y + REFRESH_SIZE, withAlpha(TEXT_STRONG, alpha * 0.15f));
        gg.fill(x, y, x + 1, y + REFRESH_SIZE, withAlpha(TEXT_STRONG, alpha * 0.15f));
        gg.fill(x + REFRESH_SIZE - 1, y, x + REFRESH_SIZE, y + REFRESH_SIZE, withAlpha(TEXT_STRONG, alpha * 0.15f));

        float deg = refreshSpin.isRunning() && !refreshSpin.isDone(now) ? 360f * refreshSpin.easedT(now) : 0f;
        PoseStack pose = gg.pose();
        pose.pushPose();
        pose.translate(x + REFRESH_SIZE / 2f, y + REFRESH_SIZE / 2f, 0f);
        pose.mulPose(Axis.ZP.rotationDegrees(deg));
        gg.fill(-4, -4, 3, -3, c);
        gg.fill(-4, -4, -3, 4, c);
        gg.fill(-4, 3, 2, 4, c);
        gg.fill(2, -4, 4, -2, c);
        pose.popPose();
    }

    /** 排序箭头：几何三角形（▼降序 / ▲升序），不依赖 Unicode 字形。 */
    private static void drawSortArrow(GuiGraphics gg, int x, int y, boolean desc, int color) {
        for (int i = 0; i < 3; i++) {
            int row = desc ? y + i : y + 2 - i;
            gg.fill(x + i, row, x + 5 - i, row + 1, color);
        }
    }

    private void drawUnderline(GuiGraphics gg, long now) {
        float[] xw = underlineNow(now);
        if (xw[1] < 0.5f) {
            return;
        }
        gg.fill(Math.round(xw[0]), UNDERLINE_Y, Math.round(xw[0] + xw[1]), UNDERLINE_Y + UNDERLINE_H, ACCENT);
    }

    /** 下划线当前的 {@code [left, width]}：开场时走 outExpo 展开,之后走 220ms outCubic 的 FLIP 滑动。 */
    private float[] underlineNow(long now) {
        if (underlineOpening) {
            float raw = (now - openedAtMs - UNDERLINE_OPEN_DELAY_MS) / (float) UNDERLINE_OPEN_MS;
            float t = Tween.Ease.OUT_EXPO.apply(Mth.clamp(raw, 0f, 1f));
            return new float[]{tabX[curTab], tabW[curTab] * t};
        }
        float t = underline.easedT(now);
        return new float[]{
                Mth.lerp(t, underlineFromX, underlineToX),
                Mth.lerp(t, underlineFromW, underlineToW)};
    }

    /** 开场级联：第 {@code index} 个 .ch 块的透明度（260ms outCubic，错峰 70ms/块）。 */
    private float chunkAlpha(long now, int index) {
        float raw = (now - openedAtMs - chunkDelayMs(index)) / (float) CH_MS;
        return Tween.Ease.OUT_CUBIC.apply(Mth.clamp(raw, 0f, 1f));
    }

    // =====================================================================
    // 列表
    // =====================================================================

    private void drawList(GuiGraphics gg, Font font, long now, int mouseX, int mouseY) {
        hits.clear();
        // 夹紧必须先于布局遍历:错峰用的"可见槽位"= index − scrollRow,scrollRow 越界会让错峰算错。
        scrollRow = clampScrollRow(scrollRow, rows.size(), visibleRows(), currentExtraRows());

        // layoutY 是"内容坐标"(相对整份列表内容顶部,与滚动无关),因此 FLIP 的旧位/新位之差
        // 天然与 scrollRow 无关 —— 滚动中排序也不会把行甩到错误的起飞点。
        int y = 0;
        boolean applyFlip = flipPending;
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            if (applyFlip) {
                r.flipFrom = flipOffset(r.layoutY, y);
                r.flip.start(now, FLIP_MS, Tween.Ease.OUT_CUBIC, flipDelayMs(staggerSlot(i, scrollRow)));
            }
            r.layoutY = y;
            y += ROW_H + Math.round(accordionHeight(r, now));
        }
        flipPending = false;

        int scrollPx = rows.isEmpty() ? 0 : rows.get(scrollRow).layoutY;
        boolean mouseInList = isInListRegion(mouseX, mouseY);

        gg.enableScissor(contentX, listTop, contentX + contentW, listBottom);
        int end = Math.min(rows.size(), scrollRow + visibleRows());
        for (int i = scrollRow; i < end; i++) {
            drawRow(gg, font, now, rows.get(i), scrollPx, mouseInList, mouseX, mouseY);
        }
        gg.disableScissor();

        drawScrollbar(gg, now);
    }

    /**
     * 滚动条:2px 轨道 + 拇指,ACCENT 蓝（非金色）。仅在真的滚不完时出现 —— 没有任何"还有更多对局"
     * 的提示会让玩家以为列表就这么长。
     */
    private void drawScrollbar(GuiGraphics gg, long now) {
        int total = rows.size();
        int visible = visibleRows();
        int max = maxScrollRow(total, visible, currentExtraRows());
        if (max <= 0) {
            return;
        }
        float alpha = rows.get(Math.min(total, scrollRow + visible) - 1).enter.easedT(now);
        if (alpha <= 0.01f) {
            return;
        }
        int trackX = contentX + contentW - 2;
        int trackH = listBottom - listTop;
        gg.fill(trackX, listTop, trackX + 2, listBottom, withAlpha(TEXT, alpha * 0.1f));
        int thumbH = Mth.clamp(trackH * visible / total, 8, trackH);
        int thumbY = listTop + (trackH - thumbH) * scrollRow / max;
        gg.fill(trackX, thumbY, trackX + 2, thumbY + thumbH, withAlpha(ACCENT, alpha * 0.8f));
    }

    private void drawRow(GuiGraphics gg, Font font, long now, Row r, int scrollPx,
                          boolean mouseInList, int mouseX, int mouseY) {
        float enterT = r.enter.easedT(now);
        float alpha = enterT;
        float dy = ROW_ENTER_DY * (1f - enterT);
        if (r.exiting) {
            float t = r.exit.easedT(now);
            alpha = 1f - t;
            dy = (listPhase == ListPhase.EXIT_TAB ? ROW_EXIT_DY : 0f) * t;
        }
        if (r.removing) {
            alpha = Math.min(alpha, 1f - r.remove.easedT(now));
        }
        if (alpha <= 0.01f) {
            return;
        }
        float flipDy = r.flip.isRunning() && !r.flip.isDone(now) ? r.flipFrom * (1f - r.flip.easedT(now)) : 0f;

        int rowY = Math.round(listTop + r.layoutY - scrollPx + dy + flipDy);
        float accH = accordionHeight(r, now);
        if (rowY > listBottom || rowY + ROW_H + accH < listTop) {
            return;
        }

        boolean expanded = r.key.equals(expandedKey);
        boolean hoverable = !expanded && !r.exiting && !r.removing && !isBusy();
        boolean hoveredNow = hoverable && mouseInList
                && mouseY >= rowY && mouseY < rowY + ROW_H;
        if (hoveredNow != r.hovering) {
            r.hovering = hoveredNow;
            r.hover.start(now, HOVER_MS, Tween.Ease.OUT_CUBIC);
        }
        float hoverV = r.hovering ? r.hover.easedT(now) : 1f - r.hover.easedT(now);
        int indent = ROW_PAD_X + Math.round(HOVER_INDENT * (expanded ? 1f : hoverV));

        // 行底色：悬停 0.04 / 展开 0.06（doc §1.3）。
        float bgA = expanded ? 0.06f : 0.04f * hoverV;
        if (bgA > 0.002f) {
            gg.fill(contentX, rowY, contentX + contentW, rowY + ROW_H, withAlpha(TEXT_STRONG, bgA * alpha));
        }
        // 实时变动行高亮：整行 ACCENT α 0.06→0，600ms 衰减。
        if (r.highlight.isRunning() && !r.highlight.isDone(now)) {
            float hv = 1f - r.highlight.easedT(now);
            gg.fill(contentX, rowY, contentX + contentW, rowY + ROW_H, withAlpha(ACCENT, HIGHLIGHT_ALPHA * hv * alpha));
        }
        // 展开指示条：2px，scaleY 0→1（原点居中）。
        float accentV = r.accentUp ? r.accent.easedT(now) : 1f - r.accent.easedT(now);
        if (accentV > 0.01f) {
            int barH = Math.round(ROW_H * accentV);
            int barY = rowY + (ROW_H - barH) / 2;
            gg.fill(contentX, barY, contentX + 2, barY + barH, withAlpha(ACCENT, alpha));
        }

        int shift = indent - ROW_PAD_X;
        drawStar(gg, now, r, colStarX + shift, rowY + (ROW_H - 8) / 2, alpha);
        drawMatchCell(gg, font, r, colNameX + shift, rowY, alpha);
        drawSimpleCell(gg, font, modeText(r.dto), colModeX + shift, rowY, colModeW, alpha);
        drawSimpleCell(gg, font, r.dto.mapName(), colMapX + shift, rowY, colMapW, alpha);
        drawPlayersCell(gg, font, now, r, colPlayersX + shift, rowY, alpha);

        Hit hit = new Hit();
        hit.key = r.key;
        hit.mx = contentX;
        hit.my = rowY;
        hit.mw = contentW;
        hit.mh = ROW_H;
        hit.sx = colStarX + shift - 2;
        hit.sy = rowY + 4;
        hit.sw = STAR_W;
        hit.sh = ROW_H - 8;

        if (accH > 1f) {
            drawAccordion(gg, font, now, r, rowY + ROW_H, accH, alpha, hit);
        }
        if (!r.exiting && !r.removing) {
            hits.add(hit);
        }
    }

    /** ★ 收藏：几何化菱形（AGENTS.md「图标几何化」），outBack 弹跳 scale 0.6→1 + rotate 72°→0。 */
    private void drawStar(GuiGraphics gg, long now, Row r, int x, int y, float alpha) {
        float sc = 1f;
        float rot = 0f;
        if (r.star.isRunning() && !r.star.isDone(now)) {
            float v = r.star.easedT(now);
            if (r.starOn) {
                sc = 0.6f + 0.4f * v;
                rot = STAR_ROTATE_DEG * (1f - v);
            }
        }
        int color = r.starOn ? withAlpha(ACCENT, alpha) : withAlpha(TEXT, alpha * 0.25f);
        PoseStack pose = gg.pose();
        pose.pushPose();
        pose.translate(x + 4f, y + 4f, 0f);
        pose.mulPose(Axis.ZP.rotationDegrees(45f + rot));
        pose.scale(sc, sc, 1f);
        if (r.starOn) {
            gg.fill(-3, -3, 3, 3, color);
        } else {
            gg.fill(-3, -3, 3, -2, color);
            gg.fill(-3, 2, 3, 3, color);
            gg.fill(-3, -3, -2, 3, color);
            gg.fill(2, -3, 3, 3, color);
        }
        pose.popPose();
    }

    /** 对局列：地图缩略占位（纯色，非渐变）+ 名称 + 标签行。 */
    private void drawMatchCell(GuiGraphics gg, Font font, Row r, int x, int rowY, float alpha) {
        int ty = rowY + (ROW_H - THUMB_H) / 2;
        gg.fill(x, ty, x + THUMB_W, ty + THUMB_H, withAlpha(thumbColor(r.dto.mapName()), alpha));
        int border = withAlpha(TEXT_STRONG, alpha * 0.1f);
        gg.fill(x, ty, x + THUMB_W, ty + 1, border);
        gg.fill(x, ty + THUMB_H - 1, x + THUMB_W, ty + THUMB_H, border);
        gg.fill(x, ty, x + 1, ty + THUMB_H, border);
        gg.fill(x + THUMB_W - 1, ty, x + THUMB_W, ty + THUMB_H, border);

        int tx = x + THUMB_W + 6;
        int avail = Math.max(16, colNameW - THUMB_W - 6);
        gg.drawString(font, fit(font, r.dto.displayName(), avail), tx, rowY + 4, withAlpha(TEXT, alpha), false);
        boolean running = r.dto.running();
        String tag = running
                ? "运行中 · " + formatElapsed(r.dto.elapsedSeconds())
                : "等待中 · 还差 " + waitingShortfall(r.dto.cur(), r.dto.max()) + " 人";
        int tagColor = running ? withAlpha(TEXT, alpha * 0.35f) : withAlpha(GREEN, alpha * 0.75f);
        gg.drawString(font, fit(font, tag, avail), tx, rowY + 15, tagColor, false);
    }

    private void drawSimpleCell(GuiGraphics gg, Font font, String text, int x, int rowY, int w, float alpha) {
        gg.drawString(font, fit(font, text, Math.max(8, w - 4)), x, rowY + 10, withAlpha(TEXT, alpha * 0.6f), false);
    }

    /** 人数列：迷你进度条（三档配色）+ `cur/max` 滚轮换字。 */
    private void drawPlayersCell(GuiGraphics gg, Font font, long now, Row r, int x, int rowY, float alpha) {
        float pct = shownFill(r, now);
        int by = rowY + (ROW_H - MINI_BAR_H) / 2;
        gg.fill(x, by, x + MINI_BAR_W, by + MINI_BAR_H, withAlpha(MINI_TRACK, alpha));
        int fw = Math.round(MINI_BAR_W * Mth.clamp(pct, 0f, 1f));
        if (fw > 0) {
            gg.fill(x, by, x + fw, by + MINI_BAR_H, withAlpha(playersFillColor(pct), alpha));
        }
        int textX = x + MINI_BAR_W + 6;
        int textW = Math.max(16, colPlayersW - MINI_BAR_W - 6);
        r.players.render(gg, font, now, textX, rowY + 10, textW, withAlpha(TEXT, alpha), false);
    }

    // =====================================================================
    // 行内手风琴（§3.4）
    // =====================================================================

    private float accordionHeight(Row r, long now) {
        if (r.expanding) {
            return ACC_H * r.accordion.easedT(now);
        }
        if (!r.accordion.isRunning()) {
            return 0f;
        }
        return ACC_H * (1f - r.accordion.easedT(now));
    }

    private void drawAccordion(GuiGraphics gg, Font font, long now, Row r, int top, float h, float alpha, Hit hit) {
        int hh = Math.round(h);
        int bottom = top + hh;
        gg.fill(contentX, top, contentX + contentW, bottom, withAlpha(ACC_BG, alpha));
        gg.enableScissor(contentX, Math.max(listTop, top), contentX + contentW, Math.min(listBottom, bottom));

        int x0 = contentX + ROW_PAD_X + 16;
        int x1 = contentX + contentW - ROW_PAD_X;
        int btnX = x1 - JOIN_BTN_W;
        int btnY = top + (ACC_H - JOIN_BTN_H) / 2;
        int areaRight = btnX - 12;

        long since = r.accordionOpenedAtMs < 0 ? 0L : now - r.accordionOpenedAtMs;
        if (r.dto.running()) {
            drawTicketDuel(gg, font, now, r, x0, top, areaRight, alpha, since);
        } else {
            drawWaitingDetail(gg, font, r, x0, top, alpha, since);
        }
        drawJoinButton(gg, font, now, r, btnX, btnY, alpha);
        gg.disableScissor();

        hit.hasJoin = hh >= ACC_H - 6;
        hit.jx = btnX;
        hit.jy = btnY;
        hit.jw = JOIN_BTN_W;
        hit.jh = JOIN_BTN_H;
    }

    /** 票数对峙条：顶行阵营名 + 主行「蓝数字 / 左锚蓝条 ‖ 中线 ‖ 右锚红条 / 红数字」。 */
    private void drawTicketDuel(GuiGraphics gg, Font font, long now, Row r,
                                 int x0, int top, int areaRight, float alpha, long since) {
        int nameY = top + 8;
        int half = Math.max(20, (areaRight - x0) / 2 - 2);
        gg.drawString(font, fit(font, r.dto.faction1Name(), half), x0, nameY, withAlpha(ACCENT, alpha), false);
        String f2 = fit(font, r.dto.faction2Name(), half);
        gg.drawString(font, f2, areaRight - font.width(f2), nameY, withAlpha(BRAVO, alpha), false);

        int mainY = top + 24;
        drawBigNumber(gg, font, now, r.t1, x0, mainY, TICKET_NUM_W, ACCENT, alpha, false);
        int t2X = areaRight - TICKET_NUM_W;
        drawBigNumber(gg, font, now, r.t2, t2X, mainY, TICKET_NUM_W, BRAVO, alpha, true);

        int barX = x0 + TICKET_NUM_W + 8;
        int barRight = t2X - 8;
        int barW = Math.max(16, barRight - barX);
        int barY = mainY + 5;
        gg.fill(barX, barY, barX + barW, barY + TICKET_BAR_H, withAlpha(BAR_TRACK, alpha));

        float f1Frac = shownTicketFrac(r, now, true);
        float f2Frac = shownTicketFrac(r, now, false);
        float reveal1 = revealProgress(since, BAR1_DELAY_MS);
        float reveal2 = revealProgress(since, BAR2_DELAY_MS);
        int w1 = Math.round(barW * f1Frac * reveal1);
        int w2 = Math.round(barW * f2Frac * reveal2);
        if (w1 > 0) {
            gg.fill(barX, barY, barX + w1, barY + TICKET_BAR_H, withAlpha(ACCENT, alpha));
        }
        if (w2 > 0) {
            gg.fill(barX + barW - w2, barY, barX + barW, barY + TICKET_BAR_H, withAlpha(BRAVO, alpha));
        }
        // 正中 1px 白色刻度线（均势标注），上下各外探 2px。
        int mid = barX + barW / 2;
        gg.fill(mid, barY - 2, mid + 1, barY + TICKET_BAR_H + 2, withAlpha(TEXT_STRONG, alpha * 0.3f));
    }

    /**
     * 等待中的行没有票数概念（本仓库与 demo 数据模型的真实差异），展开显示开局差额。
     * 复用蓝条的 420ms outCubic / 延迟 120 揭示节奏，保持同一套「展开揭示」手感。
     */
    private void drawWaitingDetail(GuiGraphics gg, Font font, Row r, int x0, int top, float alpha, long since) {
        float v = revealProgress(since, BAR1_DELAY_MS);
        if (v <= 0.01f) {
            return;
        }
        gg.drawString(font, r.dto.faction1Name() + "  ‖  " + r.dto.faction2Name(),
                x0, top + 8, withAlpha(TEXT, alpha * v * 0.45f), false);
        String text = "等待中 · 还差 " + waitingShortfall(r.dto.cur(), r.dto.max()) + " 人开局";
        gg.drawString(font, text, x0, top + 26, withAlpha(GREEN, alpha * v), false);
    }

    /** 手风琴内加入按钮：ACCENT 实心（替代文档金色）+ 按压回弹 + 扫光。永远可点（本仓库无满员/排队）。 */
    private void drawJoinButton(GuiGraphics gg, Font font, long now, Row r, int x, int y, float alpha) {
        float sc = 1f;
        if (r.press.isRunning() && !r.press.isDone(now)) {
            sc = 0.92f + 0.08f * r.press.easedT(now);
        }
        PoseStack pose = gg.pose();
        pose.pushPose();
        pose.translate(x + JOIN_BTN_W / 2f, y + JOIN_BTN_H / 2f, 0f);
        pose.scale(sc, sc, 1f);
        int hw = JOIN_BTN_W / 2;
        int hh = JOIN_BTN_H / 2;
        gg.fill(-hw, -hh, hw, hh, withAlpha(ACCENT, alpha));
        String label = "加 入";
        gg.drawString(font, label, -font.width(label) / 2, -4, withAlpha(STAGE_BG_DEEP, alpha), false);
        if (r.sweep.isRunning() && !r.sweep.isDone(now)) {
            drawSweep(gg, r.sweep.easedT(now), -hw, -hh, JOIN_BTN_W, JOIN_BTN_H, alpha);
        }
        pose.popPose();
    }

    /** 扫光：文档用 CSS 渐变，这里用 5 段三角形 alpha 分层的纯色竖条逼近（禁渐变）。 */
    private static void drawSweep(GuiGraphics gg, float v, int x, int y, int w, int h, float alpha) {
        float bandW = w * 0.3f;
        float left = x + (-0.4f + 1.8f * v) * w;
        int slices = 5;
        for (int i = 0; i < slices; i++) {
            float t0 = i / (float) slices;
            float t1 = (i + 1) / (float) slices;
            float mid = (t0 + t1) * 0.5f;
            float a = (1f - Math.abs(mid * 2f - 1f)) * 0.5f * alpha;
            int sx0 = Math.round(left + bandW * t0);
            int sx1 = Math.round(left + bandW * t1);
            if (sx1 > x && sx0 < x + w && a > 0.01f) {
                gg.fill(Math.max(sx0, x), y, Math.min(sx1, x + w), y + h, withAlpha(0xFFFFFFFF, a));
            }
        }
    }

    /**
     * 票数大字：doc 16px 加粗 —— MC 没有字号，用 1.5× pose 缩放建立层级。
     *
     * <p>{@code GuiGraphics#enableScissor} <b>不跟随 PoseStack 变换</b>，所以滚轮的裁剪框必须在
     * 缩放<b>之外</b>按屏幕坐标开好，再在缩放空间里画两段文字（这也是本仓库
     * {@code BreakthroughHudOverlay} 里同类"缩放文字 + 遮罩"的既有处理方式）。
     */
    private void drawBigNumber(GuiGraphics gg, Font font, long now, Roll roll,
                                int x, int y, int w, int color, float alpha, boolean rightAlign) {
        int clipH = Math.round((font.lineHeight + 2) * 1.5f);
        gg.enableScissor(x, y - 1, x + w, y + clipH);
        PoseStack pose = gg.pose();
        pose.pushPose();
        pose.translate((float) x, (float) y, 0f);
        pose.scale(1.5f, 1.5f, 1f);
        roll.drawUnclipped(gg, font, now, 0, 0, Math.round(w / 1.5f), withAlpha(color, alpha), rightAlign);
        pose.popPose();
        gg.disableScissor();
    }

    /** 展开揭示进度：420ms outCubic + 各自延迟（蓝 120 / 红 220）。 */
    private static float revealProgress(long since, long delayMs) {
        float raw = (since - delayMs) / (float) BAR_REVEAL_MS;
        return Tween.Ease.OUT_CUBIC.apply(Mth.clamp(raw, 0f, 1f));
    }

    // =====================================================================
    // 空状态 / 扫描线
    // =====================================================================

    private void drawEmptyState(GuiGraphics gg, Font font, long now, int screenW, int screenH) {
        if (!emptyShown) {
            return;
        }
        float a = emptyFade.easedT(now);
        if (a <= 0.01f) {
            return;
        }
        String text = "没有符合条件的对局";
        gg.drawString(font, text, (screenW - font.width(text)) / 2, Math.round(screenH * 0.46f),
                withAlpha(TEXT, a * 0.3f), false);
    }

    /** 刷新扫描线：文档用横向渐变条 —— 这里用 12 段三角 alpha 的纯色分层逼近（禁渐变）。 */
    private void drawScanLine(GuiGraphics gg, long now, int screenW) {
        if (listPhase != ListPhase.EXIT_REFRESH || !scan.isRunning()) {
            return;
        }
        float t = scan.easedT(now);
        int y = Math.round(listTop + (listBottom - listTop) * t);
        int slices = 12;
        for (int i = 0; i < slices; i++) {
            float t0 = i / (float) slices;
            float t1 = (i + 1) / (float) slices;
            float mid = (t0 + t1) * 0.5f;
            float a = (1f - Math.abs(mid * 2f - 1f)) * 0.8f;
            if (a > 0.01f) {
                gg.fill(Math.round(screenW * t0), y, Math.round(screenW * t1), y + 2, withAlpha(ACCENT, a));
            }
        }
    }

    // =====================================================================
    // 加入转场覆盖层（§3.5 三段式）
    // =====================================================================

    private void drawJoinOverlay(GuiGraphics gg, Font font, long now, int screenW, int screenH) {
        if (!joinActive) {
            return;
        }
        long e = now - joinOverlayStartMs;
        if (e < 0) {
            return;
        }
        float a = joinOverlayAlpha(e);
        if (a <= 0.01f) {
            return;
        }
        gg.fill(0, 0, screenW, screenH, withAlpha(OVERLAY_BG, a));

        int cy = Math.round(screenH * 0.38f);
        String spaced = spacedMapName(joinMapName);
        PoseStack pose = gg.pose();
        pose.pushPose();
        pose.translate(screenW / 2f - font.width(spaced) * 1.5f / 2f, cy, 0f);
        pose.scale(1.5f, 1.5f, 1f);
        gg.drawString(font, spaced, 0, 0, withAlpha(TEXT_STRONG, a), false);
        pose.popPose();

        int stage = joinStage(e);
        String stageText = switch (stage) {
            case 0 -> "正在连接";
            case 1 -> "加载地图 · " + joinMapName;
            default -> "已连接";
        };
        int stageY = cy + 20;
        gg.drawString(font, stageText, (screenW - font.width(stageText)) / 2, stageY,
                withAlpha(TEXT, a * 0.55f), false);

        int barX = (screenW - OVERLAY_BAR_W) / 2;
        int barY = stageY + 14;
        gg.fill(barX, barY, barX + OVERLAY_BAR_W, barY + 2, withAlpha(OVERLAY_TRACK, a));
        int fw = Math.round(OVERLAY_BAR_W * joinBarPercent(e) / 100f);
        if (fw > 0) {
            gg.fill(barX, barY, barX + fw, barY + 2, withAlpha(stage >= 2 ? GREEN : ACCENT, a));
        }
    }

    // =====================================================================
    // 交互动作
    // =====================================================================

    private void setTab(int index, long now) {
        if (index == curTab || listPhase != ListPhase.IDLE) {
            return;
        }
        // 下划线 FLIP 滑动（left+width 同补 220ms outCubic）：起点取"这一刻屏幕上真实的位置/宽度"，
        // 连点标签时不会从上一段动画的终点硬跳。
        float[] xw = underlineNow(now);
        underlineFromX = xw[0];
        underlineFromW = xw[1];
        underlineOpening = false;
        underlineToX = tabX[index];
        underlineToW = tabW[index];
        underline.start(now, UNDERLINE_SLIDE_MS, Tween.Ease.OUT_CUBIC);

        curTab = index;
        collapseExpanded(now);
        listPhase = ListPhase.EXIT_TAB;
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            r.exiting = true;
            r.exit.start(now, ROW_EXIT_MS, Tween.Ease.IN_CUBIC, exitDelayMs(staggerSlot(i, scrollRow)));
        }
        phaseEndMs = now + ROW_EXIT_MS + ROW_EXIT_STEP_MS * staggeredRowCount();
    }

    private void startRefresh(long now) {
        if (listPhase != ListPhase.IDLE) {
            return;
        }
        BattlefieldNetwork.CHANNEL.sendToServer(new RequestBattlefieldRoomListPacket());
        refreshSpin.start(now, REFRESH_SPIN_MS, Tween.Ease.OUT_CUBIC);
        collapseExpanded(now);
        listPhase = ListPhase.EXIT_REFRESH;
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            r.exiting = true;
            r.exit.start(now, REFRESH_EXIT_MS, Tween.Ease.IN_CUBIC, refreshExitDelayMs(staggerSlot(i, scrollRow)));
        }
        scan.start(now, SCAN_MS, Tween.Ease.OUT_CUBIC);
        // 数据更新的时机 = 扫描线走完（届时 Screen 已发出的刷新请求通常已回包；
        // 若尚未回包就用当前缓存重建，下一次推送会自然补上，不阻塞动效）。
        phaseEndMs = now + SCAN_MS;
    }

    private void doSort(long now) {
        if (isBusy() || listPhase != ListPhase.IDLE) {
            return;
        }
        collapseExpanded(now);
        if (sortActive) {
            sortDesc = !sortDesc;
        } else {
            sortActive = true;
            sortDesc = true;
        }
        rows.sort((a, b) -> compareByPlayers(a.dto, b.dto, sortDesc));
        // FLIP:旧位已存在每行的 layoutY 里,新位在下一帧的布局遍历中算出,届时补上位移起点。
        flipPending = true;
        sortBusyUntilMs = now + FLIP_MS + flipDelayMs(Math.max(0, staggeredRowCount() - 1));
    }

    private void toggleStar(String key, long now) {
        Row row = findRow(key);
        if (row == null) {
            return;
        }
        ClientBattlefieldRoomList.toggleFavorite(key);
        row.starOn = ClientBattlefieldRoomList.isFavorite(key);
        row.star.start(now, STAR_MS, Tween.Ease.OUT_BACK);
        if (curTab == 3 && !row.starOn) {
            collapseRow(row, now);
            row.removing = true;
            row.remove.start(now, REMOVE_MS, Tween.Ease.IN_CUBIC, REMOVE_DELAY_MS);
            if (visibleRowCount() == 0) {
                showEmpty(now);
            }
        }
    }

    private void toggleAccordion(String key, long now) {
        Row row = findRow(key);
        if (row == null || row.removing) {
            return;
        }
        if (key.equals(expandedKey)) {
            collapseExpanded(now);
            return;
        }
        collapseExpanded(now);
        expandedKey = key;
        ensureVisible(indexOfRow(key));
        row.expanding = true;
        row.accordionOpenedAtMs = now;
        row.accordion.start(now, ACC_OPEN_MS, Tween.Ease.OUT_CUBIC);
        row.accentUp = true;
        row.accent.start(now, ACCENT_IN_MS, Tween.Ease.OUT_CUBIC);
        row.hovering = false;
        row.hover.start(now, HOVER_MS, Tween.Ease.OUT_CUBIC);
    }

    private void collapseExpanded(long now) {
        if (expandedKey.isEmpty()) {
            return;
        }
        Row row = findRow(expandedKey);
        expandedKey = "";
        if (row != null) {
            collapseRow(row, now);
        }
    }

    private void collapseRow(Row row, long now) {
        if (row.expanding) {
            row.expanding = false;
            row.accordion.start(now, ACC_CLOSE_MS, Tween.Ease.IN_CUBIC);
        }
        if (row.accentUp) {
            row.accentUp = false;
            row.accent.start(now, ACCENT_OUT_MS, Tween.Ease.IN_CUBIC);
        }
        row.accordionOpenedAtMs = -1L;
    }

    private void startJoin(String key, long now) {
        Row row = findRow(key);
        if (row == null || joinActive) {
            return;
        }
        row.press.start(now, PRESS_MS, Tween.Ease.OUT_BACK);
        row.sweep.start(now, SWEEP_MS, Tween.Ease.OUT_CUBIC);
        joinActive = true;
        joinKey = key;
        joinMapName = row.dto.mapName();
        joinBreakthrough = row.dto.breakthrough();
        joinOverlayStartMs = now + JOIN_PRESS_LEAD_MS;
    }

    // =====================================================================
    // 数据 → 行
    // =====================================================================

    private List<BattlefieldRoomDto> filteredSorted() {
        List<BattlefieldRoomDto> out = new ArrayList<>();
        for (BattlefieldRoomDto d : ClientBattlefieldRoomList.rooms()) {
            if (pass(d)) {
                out.add(d);
            }
        }
        if (sortActive) {
            out.sort((a, b) -> compareByPlayers(a, b, sortDesc));
        }
        return out;
    }

    private boolean pass(BattlefieldRoomDto d) {
        return switch (curTab) {
            case 1 -> !d.breakthrough();
            case 2 -> d.breakthrough();
            case 3 -> ClientBattlefieldRoomList.isFavorite(d.roomKey());
            default -> true;
        };
    }

    private void rebuildList(long now) {
        rows.clear();
        expandedKey = "";
        flipPending = false;
        listBuilt = true;
        scrollRow = 0;
        List<BattlefieldRoomDto> data = filteredSorted();
        for (int i = 0; i < data.size(); i++) {
            BattlefieldRoomDto d = data.get(i);
            Row r = new Row(d);
            r.starOn = ClientBattlefieldRoomList.isFavorite(r.key);
            r.enter.start(now, ROW_ENTER_MS, Tween.Ease.OUT_CUBIC, rowDelayMs(i));
            rows.add(r);
        }
        if (rows.isEmpty()) {
            showEmpty(now);
        } else {
            emptyShown = false;
            emptyFade.reset();
        }
    }

    private void showEmpty(long now) {
        if (emptyShown) {
            return;
        }
        emptyShown = true;
        emptyFade.start(now, EMPTY_MS, Tween.Ease.OUT_CUBIC, EMPTY_DELAY_MS);
    }

    /** 新快照落到某一行上：变化的字段播滚轮 + 条宽补间，整行播高亮衰减。 */
    private void applyUpdate(Row r, BattlefieldRoomDto nd, long now) {
        boolean changed = false;
        if (nd.cur() != r.dto.cur() || nd.max() != r.dto.max()) {
            r.players.set(playersText(nd), rollDirection(r.dto.cur(), nd.cur()), now);
            r.miniFrom = shownFill(r, now);
            r.miniTo = fillPct(nd);
            r.mini.start(now, MINI_CHASE_MS, Tween.Ease.OUT_CUBIC);
            changed = true;
        }
        if (nd.running()) {
            boolean ticketsMoved = nd.tickets1() != r.dto.tickets1() || nd.tickets2() != r.dto.tickets2();
            if (nd.tickets1() != r.dto.tickets1()) {
                r.t1.set(String.valueOf(nd.tickets1()), rollDirection(r.dto.tickets1(), nd.tickets1()), now);
            }
            if (nd.tickets2() != r.dto.tickets2()) {
                r.t2.set(String.valueOf(nd.tickets2()), rollDirection(r.dto.tickets2(), nd.tickets2()), now);
            }
            if (ticketsMoved) {
                r.t1From = shownTicketFrac(r, now, true);
                r.t2From = shownTicketFrac(r, now, false);
                r.t1To = ticketBarFraction(nd.tickets1(), nd.ticketsMax());
                r.t2To = ticketBarFraction(nd.tickets2(), nd.ticketsMax());
                r.ticket.start(now, TICKET_CHASE_MS, Tween.Ease.OUT_CUBIC);
                changed = true;
            }
        }
        r.dto = nd;
        r.starOn = ClientBattlefieldRoomList.isFavorite(r.key);
        if (changed) {
            r.highlight.start(now, HIGHLIGHT_MS, Tween.Ease.OUT_CUBIC);
        }
    }

    private Row findRow(String key) {
        for (Row r : rows) {
            if (r.key.equals(key)) {
                return r;
            }
        }
        return null;
    }

    private int visibleRowCount() {
        int n = 0;
        for (Row r : rows) {
            if (!r.removing) {
                n++;
            }
        }
        return n;
    }

    private int visibleRows() {
        return visibleRowsFor(listBottom - listTop);
    }

    private int currentExtraRows() {
        return accordionExtraRows(!expandedKey.isEmpty());
    }

    /** 参与错峰的行数 = 屏幕上真正看得见的行数,不是 {@code rows.size()}。 */
    private int staggeredRowCount() {
        return Math.min(rows.size(), visibleRows());
    }

    private int indexOfRow(String key) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).key.equals(key)) {
                return i;
            }
        }
        return -1;
    }

    /** 展开某行前把它滚进视口,并为手风琴留出高度,否则展开的详情可能整块落在视口之外。 */
    private void ensureVisible(int index) {
        if (index < 0) {
            return;
        }
        int visible = visibleRows();
        int extra = accordionExtraRows(true);
        if (index < scrollRow) {
            scrollRow = index;
        } else if (index + 1 + extra > scrollRow + visible) {
            scrollRow = index + 1 + extra - visible;
        }
        scrollRow = clampScrollRow(scrollRow, rows.size(), visible, extra);
    }

    private float shownFill(Row r, long now) {
        if (!r.mini.isRunning() || r.mini.isDone(now)) {
            return r.miniTo;
        }
        return Mth.lerp(r.mini.easedT(now), r.miniFrom, r.miniTo);
    }

    private float shownTicketFrac(Row r, long now, boolean first) {
        if (!r.ticket.isRunning() || r.ticket.isDone(now)) {
            return first ? r.t1To : r.t2To;
        }
        float t = r.ticket.easedT(now);
        return first ? Mth.lerp(t, r.t1From, r.t1To) : Mth.lerp(t, r.t2From, r.t2To);
    }

    // =====================================================================
    // 纯函数（不读时钟,供 JUnit 直接覆盖）
    // =====================================================================

    /**
     * 可视行数:由列表区域实际高度 ÷ 行高推导,而不是像 Arcade 那样写死 {@code VISIBLE_ROWS} 常量 ——
     * 本类的 {@code layout()} 是自适应的（列表区域 = {@code LIST_Y} 到 {@code 屏高 − 12}），
     * 写死常量会在小 guiScale 下浪费大片空白、在大 guiScale 下重新产生"行放不下"的原始 bug。
     */
    static int visibleRowsFor(int listHeightPx) {
        return Math.max(1, listHeightPx / ROW_H);
    }

    /**
     * 展开手风琴时额外需要的可滚动行数（{@code ceil(64/28)=3}）。
     * 没有它,最后一行展开后的票数对比会永远卡在视口下沿之外 —— 与本次要修的"永久不可见"同类 bug。
     */
    static int accordionExtraRows(boolean anyExpanded) {
        return anyExpanded ? (ACC_H + ROW_H - 1) / ROW_H : 0;
    }

    /** 滚动上限。{@code rowCount-1} 的兜底保证 {@code scrollRow} 永远是 {@code rows} 的合法下标。 */
    static int maxScrollRow(int rowCount, int visibleRows, int extraRows) {
        if (rowCount <= 0) {
            return 0;
        }
        return Mth.clamp(rowCount + extraRows - visibleRows, 0, rowCount - 1);
    }

    static int clampScrollRow(int desired, int rowCount, int visibleRows, int extraRows) {
        return Mth.clamp(desired, 0, maxScrollRow(rowCount, visibleRows, extraRows));
    }

    /** 滚轮一格 = 一行（方向取 {@code signum}，与本仓库/Arcade 的滚动手感一致），结果已夹紧。 */
    static int scrollStep(int current, double delta, int rowCount, int visibleRows, int extraRows) {
        int desired = current - (int) Math.signum(delta);
        return clampScrollRow(desired, rowCount, visibleRows, extraRows);
    }

    /**
     * 错峰用的"可见槽位":屏幕上从上往下数第几行。滚动后 {@code index} 不再等于屏幕行号,
     * 错峰若继续按 {@code index} 算,滚到第 20 行时首个可见行的入场/退场延迟会变成 20 倍步长
     * （tab 切换会先卡住半秒才重建）。
     */
    static int staggerSlot(int index, int scrollRow) {
        return Math.max(0, index - scrollRow);
    }

    /** 开场 .ch 块错峰:70ms/块。 */
    static long chunkDelayMs(int index) {
        return Math.max(0, index) * CH_STEP_MS;
    }

    /** 列表行入场错峰:50ms/行。 */
    static long rowDelayMs(int index) {
        return Math.max(0, index) * ROW_STEP_MS;
    }

    /** 标签切换旧行退场错峰:18ms/行。 */
    static long exitDelayMs(int index) {
        return Math.max(0, index) * ROW_EXIT_STEP_MS;
    }

    /** 刷新旧行退场错峰:15ms/行。 */
    static long refreshExitDelayMs(int index) {
        return Math.max(0, index) * REFRESH_EXIT_STEP_MS;
    }

    /** FLIP 重排错峰:25ms/行。 */
    static long flipDelayMs(int index) {
        return Math.max(0, index) * FLIP_STEP_MS;
    }

    /** FLIP 位移起点:旧位 − 新位。 */
    static int flipOffset(int oldY, int newY) {
        return oldY - newY;
    }

    /** 「还差 N 人开局」:非负差额。 */
    static int waitingShortfall(int cur, int max) {
        return Math.max(0, max - cur);
    }

    /** 对峙条半宽占比:{@code 票数/tmax × 50%}（文档 §3.4）。 */
    static float ticketBarFraction(int tickets, int ticketsMax) {
        if (ticketsMax <= 0) {
            return 0f;
        }
        return Mth.clamp(tickets / (float) ticketsMax, 0f, 1f) * 0.5f;
    }

    /** 迷你人数条填充率。 */
    static float fillPct(BattlefieldRoomDto d) {
        if (d.max() <= 0) {
            return 0f;
        }
        return Mth.clamp(d.cur() / (float) d.max(), 0f, 1f);
    }

    /** 迷你人数条三档配色:{@code <75%} 蓝 / {@code ≥75%} 橙黄 / 满 红（金色档换成本仓库橙黄）。 */
    static int playersFillColor(float pct) {
        if (pct >= 1f) {
            return BRAVO;
        }
        if (pct > 0.75f) {
            return WARN;
        }
        return ACCENT;
    }

    /** 滚轮方向 = 语义:增 → 上滚(+1),减 → 下滚(−1),不变 → +1（不会真的播）。 */
    static int rollDirection(int oldValue, int newValue) {
        return newValue >= oldValue ? 1 : -1;
    }

    /** 仅「人数」列可排序:首点降序,再点升序。 */
    static int compareByPlayers(BattlefieldRoomDto a, BattlefieldRoomDto b, boolean desc) {
        int c = Integer.compare(a.cur(), b.cur());
        return desc ? -c : c;
    }

    /** 转场大字距地图名:字符间插空格（doc `[...s.map].join(' ')`）。 */
    static String spacedMapName(String mapName) {
        if (mapName == null || mapName.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(mapName.length() * 2);
        for (int i = 0; i < mapName.length(); i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(mapName.charAt(i));
        }
        return sb.toString();
    }

    /** 三段式转场的阶段:0=正在连接 / 1=加载地图 / 2=已连接。 */
    static int joinStage(long elapsedMs) {
        if (elapsedMs < JOIN_S1_END_MS) {
            return 0;
        }
        if (elapsedMs < JOIN_S2_END_MS) {
            return 1;
        }
        return 2;
    }

    /** 三段式转场的进度条百分比:0→42%(600ms,延迟250) → 88%(700ms) → 100%(200ms)。 */
    static float joinBarPercent(long elapsedMs) {
        if (elapsedMs < JOIN_FADE_IN_MS) {
            return 0f;
        }
        if (elapsedMs < JOIN_S1_END_MS) {
            return 42f * Tween.Ease.OUT_CUBIC.apply((elapsedMs - JOIN_FADE_IN_MS) / 600f);
        }
        if (elapsedMs < JOIN_S2_END_MS) {
            return 42f + 46f * Tween.Ease.OUT_CUBIC.apply((elapsedMs - JOIN_S1_END_MS) / 700f);
        }
        if (elapsedMs < JOIN_S3_END_MS) {
            return 88f + 12f * Tween.Ease.OUT_CUBIC.apply((elapsedMs - JOIN_S2_END_MS) / 200f);
        }
        return 100f;
    }

    /** 转场遮罩透明度:淡入 250ms outCubic → 持稳 → 停 700ms 后淡出 300ms inCubic。 */
    static float joinOverlayAlpha(long elapsedMs) {
        if (elapsedMs < JOIN_FADE_IN_MS) {
            return Tween.Ease.OUT_CUBIC.apply(elapsedMs / (float) JOIN_FADE_IN_MS);
        }
        if (elapsedMs < JOIN_HOLD_END_MS) {
            return 1f;
        }
        if (elapsedMs < JOIN_TOTAL_MS) {
            return 1f - Tween.Ease.IN_CUBIC.apply((elapsedMs - JOIN_HOLD_END_MS) / 300f);
        }
        return 0f;
    }

    /** 转场总时长(自遮罩出现算起)。 */
    static long joinTotalMs() {
        return JOIN_TOTAL_MS;
    }

    /** 「模式 + 规模」列文案（doc "征服 64"）。 */
    static String modeText(BattlefieldRoomDto d) {
        return (d.breakthrough() ? "突破" : "征服") + " " + d.max();
    }

    /** 人数列文案。本仓库无排队数,所以没有 demo 里的 {@code " +q"} 后缀。 */
    static String playersText(BattlefieldRoomDto d) {
        return d.cur() + "/" + d.max();
    }

    /** 已进行时长 mm:ss。 */
    static String formatElapsed(int seconds) {
        int s = Math.max(0, seconds);
        return s / 60 + ":" + (s % 60 < 10 ? "0" : "") + s % 60;
    }

    // =====================================================================
    // 绘制小工具
    // =====================================================================

    private static boolean inRect(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private static int withAlpha(int argb, float alphaMul) {
        int baseA = (argb >>> 24) & 0xFF;
        int a = Math.round(baseA * Mth.clamp(alphaMul, 0f, 1f));
        return (argb & 0x00FFFFFF) | (a << 24);
    }

    private static String fit(Font font, String text, int maxW) {
        String s = text == null ? "" : text;
        if (font.width(s) <= maxW) {
            return s;
        }
        int dots = font.width("..");
        return font.plainSubstrByWidth(s, Math.max(0, maxW - dots)) + "..";
    }

    /**
     * 地图缩略占位色:文档用 hsl 渐变,这里换成「ALPHA 蓝 与 冷钢灰之间的一档稳定纯色」——
     * 由地图名哈希派生(同一地图恒定同色),不引入新色相、不使用渐变。
     */
    private static int thumbColor(String mapName) {
        int h = (mapName == null ? 0 : mapName.hashCode()) & 0x7fffffff;
        float t = (h % 100) / 100f;
        return blend(0xFF1A2530, ACCENT, 0.10f + 0.16f * t);
    }

    private static int blend(int base, int over, float t) {
        float k = Mth.clamp(t, 0f, 1f);
        int br = (base >> 16) & 0xFF;
        int bg = (base >> 8) & 0xFF;
        int bb = base & 0xFF;
        int or = (over >> 16) & 0xFF;
        int og = (over >> 8) & 0xFF;
        int ob = over & 0xFF;
        int r = Math.round(br + (or - br) * k);
        int g = Math.round(bg + (og - bg) * k);
        int b = Math.round(bb + (ob - bb) * k);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    // =====================================================================
    // 内部状态载体
    // =====================================================================

    /** 一行的全部动效状态。行是「视图对象」,数据快照({@link #dto})会被就地替换。 */
    private static final class Row {
        private final String key;
        private BattlefieldRoomDto dto;

        private final Tween.Anim enter = new Tween.Anim();
        private final Tween.Anim exit = new Tween.Anim();
        private final Tween.Anim remove = new Tween.Anim();
        private boolean exiting;
        private boolean removing;

        private boolean hovering;
        private final Tween.Anim hover = new Tween.Anim();

        private boolean expanding;
        private final Tween.Anim accordion = new Tween.Anim();
        private long accordionOpenedAtMs = -1L;

        private boolean accentUp;
        private final Tween.Anim accent = new Tween.Anim();

        private final Tween.Anim highlight = new Tween.Anim();

        private boolean starOn;
        private final Tween.Anim star = new Tween.Anim();

        private final Tween.Anim press = new Tween.Anim();
        private final Tween.Anim sweep = new Tween.Anim();

        private final Roll players = new Roll();
        private final Roll t1 = new Roll();
        private final Roll t2 = new Roll();

        private final Tween.Anim mini = new Tween.Anim();
        private float miniFrom;
        private float miniTo;
        private final Tween.Anim ticket = new Tween.Anim();
        private float t1From;
        private float t1To;
        private float t2From;
        private float t2To;

        private float flipFrom;
        private final Tween.Anim flip = new Tween.Anim();

        /** 本帧的逻辑 Y(相对列表顶,不含入场/FLIP 位移):命中检测与下一次 FLIP 的旧位都取它。 */
        private int layoutY;

        private Row(BattlefieldRoomDto dto) {
            this.key = dto.roomKey();
            this.dto = dto;
            this.players.text = playersText(dto);
            this.t1.text = String.valueOf(dto.tickets1());
            this.t2.text = String.valueOf(dto.tickets2());
            this.miniTo = fillPct(dto);
            this.t1To = ticketBarFraction(dto.tickets1(), dto.ticketsMax());
            this.t2To = ticketBarFraction(dto.tickets2(), dto.ticketsMax());
        }
    }

    /**
     * 滚轮换字(文档 §1.3 通用 {@code roll(el,txt,dir)}):裁剪框内旧值向 {@code -dir} 滑出、
     * 新值自 {@code +dir} 滑入,190ms outCubic。方向由语义决定,不由动画自己决定。
     */
    private static final class Roll {
        private String text = "";
        private String prev = "";
        private int dir = 1;
        private boolean active;
        private final Tween.Anim anim = new Tween.Anim();

        private void set(String next, int direction, long now) {
            String v = next == null ? "" : next;
            if (v.equals(text)) {
                return;
            }
            prev = text;
            text = v;
            dir = direction >= 0 ? 1 : -1;
            active = true;
            anim.start(now, ROLL_MS, Tween.Ease.OUT_CUBIC);
        }

        private void render(GuiGraphics gg, Font font, long now, int x, int y, int w, int color, boolean rightAlign) {
            gg.enableScissor(x, y - 1, x + w, y + font.lineHeight + 2);
            drawUnclipped(gg, font, now, x, y, w, color, rightAlign);
            gg.disableScissor();
        }

        /** 由调用方自行开好裁剪框的变体 —— 供 {@code drawBigNumber} 在 pose 缩放空间内使用。 */
        private void drawUnclipped(GuiGraphics gg, Font font, long now,
                                    int x, int y, int w, int color, boolean rightAlign) {
            if (!active || anim.isDone(now)) {
                active = false;
                gg.drawString(font, text, alignX(font, text, x, w, rightAlign), y, color, false);
                return;
            }
            int h = font.lineHeight + 2;
            float v = anim.easedT(now);
            int newY = y + Math.round(h * dir * (1f - v));
            int oldY = y - Math.round(h * dir * v);
            gg.drawString(font, prev, alignX(font, prev, x, w, rightAlign), oldY, color, false);
            gg.drawString(font, text, alignX(font, text, x, w, rightAlign), newY, color, false);
        }

        private static int alignX(Font font, String s, int x, int w, boolean rightAlign) {
            return rightAlign ? x + w - font.width(s) : x;
        }
    }

    /** 本帧的命中矩形（{@code m}=整行 / {@code s}=星标 / {@code j}=加入按钮）。 */
    private static final class Hit {
        private String key = "";
        private int mx;
        private int my;
        private int mw;
        private int mh;
        private int sx;
        private int sy;
        private int sw;
        private int sh;
        private boolean hasJoin;
        private int jx;
        private int jy;
        private int jw;
        private int jh;
    }
}
