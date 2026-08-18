package org.shee33.act0.battlefield.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import org.shee33.act0.battlefield.core.PauseMenuAnim;
import org.shee33.act0.battlefield.network.BattleHudDto;
import org.shee33.act0.battlefield.network.SquadMateHudDto;
import org.shee33.act0.battlefield.network.SquadRosterDto;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * 战地暂停菜单的全部动画状态与绘制（《战地暂停菜单动效规格文档》§2–§3 的 Minecraft 移植）。
 *
 * <p>分工与本仓库既有的 {@code CreateRoomScreen + CreateRoomAnimator} / {@code BattlefieldDeployScreen
 * + DeployMapPanel} 一致：{@link BattlefieldPauseScreen} 只做输入转发与命令下发，一切时间轴、
 * 命中框与像素都在本类。动画状态做成<b>实例字段</b>（随 Screen 创建/销毁），于是"每次打开重播、
 * 无需手动复位"是天然结果，不需要一个容易漏调的 {@code reset()}。
 *
 * <p>所有时长/延迟/缓动一律取自 {@link PauseMenuAnim}——那是本规格唯一的时序真源，且已被
 * {@code PauseMenuAnimTest} 逐个边界钉死。本类不自己推导任何毫秒数。
 */
final class BattlefieldPauseAnimator {

    // ---- 布局（规格文档 §2；demo 舞台 900×540 与 GUI scale 2 下的 960×540 近乎 1:1，故直接取用） ----
    private static final int MARGIN_LEFT = 36;
    private static final int TAG_TOP = 26;
    private static final int MENU_TOP = 130;
    private static final int MENU_W = 230;
    /** 项高 24 + 间距 8 = 步距 32，落在 AGENTS.md 的 8px 网格上。 */
    private static final int ITEM_H = 24;
    private static final int ITEM_STRIDE = 32;
    private static final int IND_OUTSET = 14;
    private static final int IND_W = 3;
    private static final int IND_H = 22;

    private static final int PANEL_W = 280;
    private static final int PANEL_MARGIN_RIGHT = 26;
    private static final int PANEL_TOP = 26;
    private static final int CARD_PAD_X = 16;
    private static final int CARD1_H = 88;
    private static final int CARD2_H = 86;
    private static final int CARD_GAP = 10;
    private static final int TICKET_NUM_W = 34;
    private static final int TICKET_BAR_H = 5;
    private static final int MINI_ROW_STRIDE = 12;

    private static final int TOAST_BOTTOM = 26;
    private static final int TOAST_PAD_X = 18;
    private static final int TOAST_H = 20;

    /** 右侧状态区/子面板要占位，屏幕太窄时（低分辨率或 GUI scale 4）只保留左侧菜单。 */
    private static final int WIDE_ENOUGH = MARGIN_LEFT + MENU_W + 24 + PANEL_W + PANEL_MARGIN_RIGHT;

    /** 遮罩纵向切条数：条越多渐变越平滑，48 条在 1080p 下每条 ~20px，肉眼已看不出台阶。 */
    private static final int OVERLAY_STRIPS = 48;
    private static final int OVERLAY_RGB = 0x080A0E;

    private static final int CARD_BG = 0xD90D1116;
    private static final int CARD_BORDER = 0x1AFFFFFF;
    private static final int CARD_DIVIDER = 0x12FFFFFF;
    private static final int BAR_TRACK = 0x14FFFFFF;
    private static final int TOAST_BG = 0xF20D1116;
    private static final int ALIVE = 0xFF6EE27E;
    private static final int DOWNED = 0xFFFFB64F;
    private static final int OUTLINE = 0x40FFFFFF;

    /** 数值滚轮时长（规格文档 §4 的 {@code roll()}：190ms outCubic）。 */
    private static final int ROLL_MS = 190;
    /** 对峙条宽度追赶时长（§3.3：300ms）。 */
    private static final int BAR_CHASE_MS = 300;
    /** 红点呼吸周期基数：{@code 0.45+0.55·|sin(t/600)|}（§2）。 */
    private static final float DOT_PERIOD_MS = 600f;
    /**
     * 子页面打开时右侧状态区「压暗 + 左移让位」的补间时长。
     *
     * <p>规格文档 §3.4 给的是 220ms，而 {@link PauseMenuAnim} 没有为这一处单列常量。
     * 这里取同值的 {@link PauseMenuAnim#OVERLAY_IN_MS} 而不是另写一个 220 字面量——
     * 时序真源只有 PauseMenuAnim 一处，写字面量就等于埋下一个改不动的漂移点。
     */
    private static final int SUB_DIM_MS = PauseMenuAnim.OVERLAY_IN_MS;

    /** 菜单项（顺序与文案即规格文档 §1 的验收基准，不得增删换序）。 */
    enum Item {
        RESUME("回到游戏", false, 0, ""),
        SQUAD("小队管理", false, 0, ""),
        SETTINGS("设置", false, 0, ""),
        LOADOUT("配装", false, 0, ""),
        LEAVE_MATCH("退出战局", true, DocPalette.PROGRESS, "已退出战局"),
        QUIT_GAME("退出游戏", true, DocPalette.ENEMY, "正在退出游戏");

        final String label;
        /** 是否为长按确认项（危险操作不用弹窗，用 800ms 填充）。 */
        final boolean hold;
        final int holdColor;
        final String doneToast;

        Item(String label, boolean hold, int holdColor, String doneToast) {
            this.label = label;
            this.hold = hold;
            this.holdColor = holdColor;
            this.doneToast = doneToast;
        }
    }

    /**
     * 本次打开实际展示的菜单项。
     *
     * <p>不是 {@code Item.values()}：ESC 菜单现在全局接管，对局外「小队管理」与「退出战局」
     * 无从执行，画出来只会让玩家点到一个没有反应的按钮。列表在构造时定下，之后不再变——
     * 菜单开着的这几百毫秒里对局状态不会翻转，而一个长度会变的数组会让所有按索引推进的
     * 级联动画错位。
     */
    private final Item[] items;

    private static Item[] itemsFor(boolean inMatch) {
        List<Item> out = new ArrayList<>(Item.values().length);
        for (Item item : Item.values()) {
            if (inMatch || (item != Item.SQUAD && item != Item.LEAVE_MATCH)) {
                out.add(item);
            }
        }
        return out.toArray(new Item[0]);
    }

    private final PauseSquadPanel squadPanel = new PauseSquadPanel();
    private final Ticker alphaTicker = new Ticker();
    private final Ticker bravoTicker = new Ticker();

    private final long openedAtMs;
    private long closingAtMs = -1L;

    private int focus;
    private long indicatorStartMs;
    private float indicatorFrom;
    private float indicatorTo;

    private int holdIndex = -1;
    private long holdStartMs;
    private int rewindIndex = -1;
    private float rewindFrom;
    private long rewindStartMs;

    private boolean subOpen;
    private boolean subClosing;
    private long subStartMs = -1L;

    private String toastText = "";
    private long toastStartMs = -1L;

    @Nullable
    private Item pendingItem;

    BattlefieldPauseAnimator(long nowMs, boolean inMatch) {
        this.items = itemsFor(inMatch);
        this.openedAtMs = nowMs;
        this.indicatorStartMs = nowMs;
        this.indicatorFrom = indicatorTargetY(0);
        this.indicatorTo = this.indicatorFrom;
    }

    // ============================================================
    // 对外状态
    // ============================================================

    boolean isClosing() {
        return closingAtMs >= 0L;
    }

    boolean isSubOpen() {
        return subOpen && !subClosing;
    }

    /** 收起子面板；返回 {@code true} 表示这次按键/点击被子面板消费掉了。 */
    boolean collapseSub(long now) {
        if (!isSubOpen()) {
            return false;
        }
        subClosing = true;
        subStartMs = now;
        return true;
    }

    /** 开始关闭序列（反向播放）；真正 {@code setScreen(null)} 由 Screen 在 {@link #closeDoneAt} 后执行。 */
    void beginClose(long now) {
        if (closingAtMs >= 0L) {
            return;
        }
        // 规格文档 §3.1：关闭前若子页面开着先收子页面。
        collapseSub(now);
        cancelHold(now);
        closingAtMs = now;
    }

    /** 关闭序列结束时刻（毫秒绝对时间）；未在关闭中返回 {@link Long#MAX_VALUE}。 */
    long closeDoneAt() {
        return closingAtMs < 0L ? Long.MAX_VALUE : closingAtMs + PauseMenuAnim.closeTotalMs(items.length);
    }

    /** 取走一次已达成的菜单动作（长按填满 / 普通项点击 / 键盘确认）。 */
    @Nullable
    Item pollPendingItem() {
        Item item = pendingItem;
        pendingItem = null;
        return item;
    }

    void toast(String text, long now) {
        toastText = text;
        toastStartMs = now;
    }

    // ============================================================
    // 焦点与输入
    // ============================================================

    int focusIndex() {
        return focus;
    }

    Item focusedItem() {
        return items[focus];
    }

    Item itemOf(int index) {
        return items[Math.floorMod(index, items.length)];
    }

    /** 移动焦点（键盘上下 / 鼠标悬停共用；指示器是唯一焦点指示物，因此这里只动指示器）。 */
    void setFocus(int index, long now) {
        int clamped = Math.floorMod(index, items.length);
        if (clamped == focus) {
            return;
        }
        // 从"当前实际画到哪"起补间而非从上一个目标起：连点两次上键时指示器不会跳回去重滑。
        indicatorFrom = indicatorY(now);
        focus = clamped;
        indicatorTo = indicatorTargetY(clamped);
        indicatorStartMs = now;
        cancelHold(now);
    }

    /** 鼠标移动时更新悬停焦点。 */
    void onMouseMoved(double mx, double my, long now) {
        int hovered = itemAt(mx, my);
        if (hovered >= 0) {
            setFocus(hovered, now);
        } else if (holdIndex >= 0) {
            // 规格文档 §3.2：移出长按项等于松手取消。
            cancelHold(now);
        }
    }

    /** @return 命中的菜单项下标，未命中返回 -1 */
    int itemAt(double mx, double my) {
        if (mx < MARGIN_LEFT || mx >= MARGIN_LEFT + MENU_W) {
            return -1;
        }
        for (int i = 0; i < items.length; i++) {
            int top = MENU_TOP + i * ITEM_STRIDE;
            if (my >= top && my < top + ITEM_H) {
                return i;
            }
        }
        return -1;
    }

    /** 开始长按确认（鼠标按下或 Enter 按下）。 */
    void beginHold(int index, long now) {
        if (index < 0 || index >= items.length || !items[index].hold) {
            return;
        }
        holdIndex = index;
        holdStartMs = now;
        rewindIndex = -1;
    }

    /** 松手/移出：按<b>当前宽度</b>启动 180ms 回退（不是从满条回退）。 */
    void cancelHold(long now) {
        if (holdIndex < 0) {
            return;
        }
        rewindFrom = PauseMenuAnim.holdFill(now - holdStartMs, -1f, 0L);
        rewindIndex = holdIndex;
        rewindStartMs = now;
        holdIndex = -1;
    }

    boolean isHolding() {
        return holdIndex >= 0;
    }

    int holdingIndex() {
        return holdIndex;
    }

    /** 触发一个普通项（非长按）；小队管理在本类内部处理，其余交给 Screen。 */
    void activate(Item item, long now) {
        if (item == Item.SQUAD) {
            openSub(now);
            return;
        }
        pendingItem = item;
    }

    private void openSub(long now) {
        if (subOpen && !subClosing) {
            return;
        }
        subOpen = true;
        subClosing = false;
        subStartMs = now;
    }

    /** 子面板区域的点击转发；返回待下发的小队操作。 */
    @Nullable
    PauseSquadPanel.Request clickSub(double mx, double my, long now) {
        if (!isSubOpen()) {
            return null;
        }
        if (squadPanel.clickedBack(mx, my)) {
            collapseSub(now);
            return null;
        }
        return squadPanel.click(mx, my);
    }

    boolean insideSub(double mx, int screenW) {
        return isSubOpen() && mx >= screenW - PauseSquadPanel.WIDTH;
    }

    // ============================================================
    // 渲染
    // ============================================================

    void render(GuiGraphics gg, Font font, int screenW, int screenH, int mouseX, int mouseY, long now) {
        Frame f = frame(now);
        drawOverlay(gg, screenW, screenH, f.overlay);
        checkHoldCompletion(now);

        drawTag(gg, font, f.tag, now);
        drawMenu(gg, font, f, now, mouseX, mouseY);
        drawIndicator(gg, f, now);

        boolean wide = screenW >= WIDE_ENOUGH;
        if (wide) {
            drawStatusPanel(gg, font, screenW, f, now);
        }
        if (subOpen) {
            float off = subOffset(now);
            if (subClosing && off >= PauseSquadPanel.WIDTH) {
                subOpen = false;
                subClosing = false;
            } else {
                squadPanel.render(gg, font, screenW, screenH, mouseX, mouseY, off,
                        subStartMs < 0 ? 0L : now - subStartMs);
            }
        }
        drawToast(gg, font, screenW, screenH, now);
    }

    /** 长按填满即执行（规格文档 §3.2：填满就是确认，没有弹窗）。 */
    private void checkHoldCompletion(long now) {
        if (holdIndex < 0 || !PauseMenuAnim.holdCompleted(now - holdStartMs)) {
            return;
        }
        Item item = items[holdIndex];
        holdIndex = -1;
        rewindIndex = -1;
        pendingItem = item;
        toast(item.doneToast, now);
    }

    /** 每帧的各区域不透明度/位移：开场用级联延迟，关闭用反向错峰。 */
    private Frame frame(long now) {
        if (closingAtMs >= 0L) {
            long e = now - closingAtMs;
            float fade = 1f - PauseMenuAnim.inCubic(PauseMenuAnim.progress(e, 0, PauseMenuAnim.CLOSE_FADE_MS));
            float ov = 1f - PauseMenuAnim.inCubic(PauseMenuAnim.progress(e,
                    PauseMenuAnim.CLOSE_OVERLAY_DELAY_MS, PauseMenuAnim.CLOSE_OVERLAY_MS));
            float[] itemA = new float[items.length];
            float[] itemX = new float[items.length];
            for (int i = 0; i < items.length; i++) {
                float v = PauseMenuAnim.itemCloseProgress(e, i);
                itemA[i] = 1f - v;
                itemX[i] = PauseMenuAnim.CLOSE_ITEM_SLIDE_PX * v;
            }
            return new Frame(ov, fade, itemA, itemX, fade, fade);
        }
        long e = now - openedAtMs;
        float ov = PauseMenuAnim.outCubic(PauseMenuAnim.progress(e, 0, PauseMenuAnim.OVERLAY_IN_MS));
        float tag = PauseMenuAnim.outCubic(PauseMenuAnim.progress(e,
                PauseMenuAnim.TAG_DELAY_MS, PauseMenuAnim.TAG_IN_MS));
        float panel = PauseMenuAnim.outCubic(PauseMenuAnim.progress(e,
                PauseMenuAnim.PANEL_DELAY_MS, PauseMenuAnim.PANEL_IN_MS));
        float ind = PauseMenuAnim.outCubic(PauseMenuAnim.progress(e,
                PauseMenuAnim.INDICATOR_DELAY_MS, PauseMenuAnim.INDICATOR_IN_MS));
        float[] itemA = new float[items.length];
        float[] itemX = new float[items.length];
        for (int i = 0; i < items.length; i++) {
            float v = PauseMenuAnim.itemOpenProgress(e, i);
            itemA[i] = v;
            itemX[i] = PauseMenuAnim.ITEM_SLIDE_PX * (1f - v);
        }
        return new Frame(ov, tag, itemA, itemX, panel, ind);
    }

    /** 遮罩：横向三档渐变（左重右轻），保证右侧战场保持可见。 */
    private static void drawOverlay(GuiGraphics gg, int screenW, int screenH, float alpha) {
        if (alpha <= 0.002f) {
            return;
        }
        for (int i = 0; i < OVERLAY_STRIPS; i++) {
            int x1 = screenW * i / OVERLAY_STRIPS;
            int x2 = screenW * (i + 1) / OVERLAY_STRIPS;
            if (x2 <= x1) {
                continue;
            }
            float ratio = (i + 0.5f) / OVERLAY_STRIPS;
            int a = Math.round(255f * PauseMenuAnim.overlayAlphaAt(ratio) * alpha);
            gg.fill(x1, 0, x2, screenH, (a << 24) | OVERLAY_RGB);
        }
    }

    private void drawTag(GuiGraphics gg, Font font, float alpha, long now) {
        if (alpha <= 0.002f) {
            return;
        }
        int dy = Math.round(PauseMenuAnim.TAG_SLIDE_PX * (1f - alpha));
        int y = TAG_TOP + dy;
        // 红点呼吸：这是"游戏没停"这条第一原则唯一的持续动效，不能跟着开场淡入的 alpha 一起冻住。
        float pulse = 0.45f + 0.55f * Math.abs((float) Math.sin(now / DOT_PERIOD_MS));
        gg.fill(MARGIN_LEFT, y + 1, MARGIN_LEFT + 7, y + 8, withAlpha(DocPalette.ENEMY, pulse * alpha));
        gg.drawString(font, "对局仍在进行", MARGIN_LEFT + 15, y, withAlpha(DocPalette.TEXT, 0.6f * alpha), false);

        String mode = modeName();
        drawScaled(gg, MARGIN_LEFT, y + 16, 1.5f,
                () -> gg.drawString(font, mode, MARGIN_LEFT, y + 16, withAlpha(0xFFFFFFFF, alpha), false));
        String map = mapName();
        if (!map.isBlank()) {
            gg.drawString(font, map, MARGIN_LEFT, y + 34, withAlpha(DocPalette.TEXT, 0.4f * alpha), false);
        }
    }

    private void drawMenu(GuiGraphics gg, Font font, Frame f, long now, int mouseX, int mouseY) {
        for (int i = 0; i < items.length; i++) {
            float a = f.itemAlpha[i];
            if (a <= 0.002f) {
                continue;
            }
            Item item = items[i];
            int top = MENU_TOP + i * ITEM_STRIDE;
            int x = MARGIN_LEFT + Math.round(f.itemShiftX[i]);
            float fill = holdFillOf(i, now);
            if (fill > 0.001f) {
                int w = Math.round(MENU_W * fill);
                gg.fill(x, top, x + w, top + ITEM_H, withAlpha(item.holdColor, 0.22f * a));
            }
            // 焦点唯一由金色竖条表达：这里绝不画行底色或边框，只提亮文字。
            boolean lit = i == focus;
            int color = lit ? withAlpha(0xFFFFFFFF, a) : withAlpha(DocPalette.TEXT, 0.55f * a);
            gg.drawString(font, item.label, x + 12, top + 8, color, false);
        }
    }

    private float holdFillOf(int index, long now) {
        if (index == holdIndex) {
            return PauseMenuAnim.holdFill(now - holdStartMs, -1f, 0L);
        }
        if (index == rewindIndex) {
            float v = PauseMenuAnim.holdFill(0L, rewindFrom, now - rewindStartMs);
            if (v <= 0.001f) {
                rewindIndex = -1;
            }
            return v;
        }
        return 0f;
    }

    private void drawIndicator(GuiGraphics gg, Frame f, long now) {
        if (f.indicator <= 0.002f) {
            return;
        }
        float y = indicatorY(now);
        float v = PauseMenuAnim.outCubic(PauseMenuAnim.progress(now - indicatorStartMs, 0,
                PauseMenuAnim.INDICATOR_SLIDE_MS));
        float stretch = PauseMenuAnim.indicatorStretch(v);
        int x = MARGIN_LEFT - IND_OUTSET;
        int top = Math.round(y);
        int cy = top + IND_H / 2;
        PoseStack pose = gg.pose();
        pose.pushPose();
        pose.translate(0, cy, 0);
        pose.scale(1f, stretch, 1f);
        pose.translate(0, -cy, 0);
        gg.fill(x, top, x + IND_W, top + IND_H, withAlpha(DocPalette.PROGRESS, f.indicator));
        // 微光：规格文档的 box-shadow 在 MC 里没有等价物，用向外一圈低透明度同色描边近似。
        gg.fill(x - 1, top, x, top + IND_H, withAlpha(DocPalette.PROGRESS, 0.25f * f.indicator));
        gg.fill(x + IND_W, top, x + IND_W + 1, top + IND_H, withAlpha(DocPalette.PROGRESS, 0.25f * f.indicator));
        pose.popPose();
    }

    private float indicatorY(long now) {
        float v = PauseMenuAnim.outCubic(PauseMenuAnim.progress(now - indicatorStartMs, 0,
                PauseMenuAnim.INDICATOR_SLIDE_MS));
        return indicatorFrom + (indicatorTo - indicatorFrom) * v;
    }

    private static float indicatorTargetY(int index) {
        return MENU_TOP + index * ITEM_STRIDE + (ITEM_H - IND_H) / 2f;
    }

    // ============================================================
    // 右侧实时状态区
    // ============================================================

    private void drawStatusPanel(GuiGraphics gg, Font font, int screenW, Frame f, long now) {
        BattleHudDto hud = ClientBattleHud.hud();
        if (hud == null || f.panel <= 0.002f) {
            return;
        }
        // 子面板打开时右侧状态区让位：压暗到 0.25 并左移 14px。
        float dim = 1f;
        float shift = 0f;
        if (subOpen) {
            float t = subDimProgress(now);
            dim = subClosing ? PauseMenuAnim.SUB_PANEL_DIM + (1f - PauseMenuAnim.SUB_PANEL_DIM) * t
                    : 1f - (1f - PauseMenuAnim.SUB_PANEL_DIM) * t;
            shift = subClosing ? PauseMenuAnim.SUB_PANEL_SHIFT_PX * (1f - t)
                    : PauseMenuAnim.SUB_PANEL_SHIFT_PX * t;
        }
        float a = f.panel * dim;
        int x = screenW - PANEL_MARGIN_RIGHT - PANEL_W
                + Math.round(PauseMenuAnim.PANEL_SLIDE_PX * (1f - f.panel) + shift);
        drawMatchCard(gg, font, hud, x, PANEL_TOP, a, now);
        drawSquadCard(gg, font, hud, x, PANEL_TOP + CARD1_H + CARD_GAP, a);
    }

    private void drawMatchCard(GuiGraphics gg, Font font, BattleHudDto hud, int x, int y, float a, long now) {
        card(gg, x, y, PANEL_W, CARD1_H, a);
        gg.drawString(font, "对局状态", x + CARD_PAD_X, y + 14, withAlpha(DocPalette.TEXT, 0.35f * a), false);

        int contentX = x + CARD_PAD_X;
        int contentW = PANEL_W - CARD_PAD_X * 2;
        int nameY = y + 29;
        gg.drawString(font, "ALPHA", contentX, nameY, withAlpha(DocPalette.FRIEND, a), false);
        String bravo = "BRAVO";
        int bravoX = contentX + contentW - font.width(bravo);
        gg.drawString(font, bravo, bravoX, nameY, withAlpha(DocPalette.ENEMY, a), false);
        // 我方那侧加一条 1px 金线：阵营名本身是绝对色（蓝=ALPHA/红=BRAVO），
        // 光靠颜色玩家分不出哪边是自己。
        if (hud.myFaction() == 1) {
            gg.fill(contentX, nameY + 10, contentX + font.width("ALPHA"), nameY + 11,
                    withAlpha(DocPalette.PROGRESS, 0.8f * a));
        } else if (hud.myFaction() == 2) {
            gg.fill(bravoX, nameY + 10, bravoX + font.width(bravo), nameY + 11,
                    withAlpha(DocPalette.PROGRESS, 0.8f * a));
        }

        int max = Math.max(1, hud.maxTickets());
        alphaTicker.accept(hud.alphaTickets(), hud.alphaTickets() / (float) max * 0.5f, now);
        bravoTicker.accept(hud.bravoTickets(), hud.bravoTickets() / (float) max * 0.5f, now);

        int mainY = y + 43;
        drawRoll(gg, font, alphaTicker, contentX, mainY, TICKET_NUM_W, DocPalette.FRIEND, a, false, now);
        int rightNumX = contentX + contentW - TICKET_NUM_W;
        drawRoll(gg, font, bravoTicker, rightNumX, mainY, TICKET_NUM_W, DocPalette.ENEMY, a, true, now);

        int barX = contentX + TICKET_NUM_W + 8;
        int barRight = rightNumX - 8;
        int barW = Math.max(16, barRight - barX);
        int barY = mainY + 3;
        gg.fill(barX, barY, barX + barW, barY + TICKET_BAR_H, withAlpha(BAR_TRACK, a));
        int w1 = Math.round(barW * alphaTicker.bar(now));
        int w2 = Math.round(barW * bravoTicker.bar(now));
        if (w1 > 0) {
            gg.fill(barX, barY, barX + w1, barY + TICKET_BAR_H, withAlpha(DocPalette.FRIEND, a));
        }
        if (w2 > 0) {
            gg.fill(barX + barW - w2, barY, barX + barW, barY + TICKET_BAR_H, withAlpha(DocPalette.ENEMY, a));
        }
        int mid = barX + barW / 2;
        gg.fill(mid, barY - 2, mid + 1, barY + TICKET_BAR_H + 2, withAlpha(0xFFFFFFFF, 0.3f * a));

        gg.fill(contentX, y + 60, contentX + contentW, y + 61, withAlpha(CARD_DIVIDER, a));
        gg.drawString(font, "当前区域", contentX, y + 68, withAlpha(DocPalette.TEXT, 0.45f * a), false);
        String focusText = focusText(hud);
        gg.drawString(font, focusText, contentX + contentW - font.width(focusText), y + 68,
                withAlpha(DocPalette.PROGRESS, a), false);
    }

    private void drawSquadCard(GuiGraphics gg, Font font, BattleHudDto hud, int x, int y, float a) {
        card(gg, x, y, PANEL_W, CARD2_H, a);
        SquadRosterDto roster = ClientSquadRoster.get();
        String tag = roster.mySquadId() > 0 ? PauseSquadPanel.squadName(roster.mySquadId()) : "未加入";
        gg.drawString(font, "我的小队 · " + tag, x + CARD_PAD_X, y + 14,
                withAlpha(DocPalette.TEXT, 0.35f * a), false);

        List<SquadMateHudDto> mates = hud.squad();
        int contentX = x + CARD_PAD_X;
        for (int i = 0; i < 4; i++) {
            int rowY = y + 30 + i * MINI_ROW_STRIDE;
            if (i >= mates.size()) {
                drawHollowDot(gg, contentX, rowY + 1, a);
                gg.drawString(font, "空位", contentX + 14, rowY, withAlpha(DocPalette.TEXT, 0.3f * a), false);
                continue;
            }
            SquadMateHudDto mate = mates.get(i);
            boolean downed = mate.downed();
            gg.fill(contentX, rowY + 1, contentX + 7, rowY + 8, withAlpha(downed ? DOWNED : ALIVE, a));
            String name = mate.name() + (mate.self() ? " (你)" : "");
            gg.drawString(font, name, contentX + 14, rowY,
                    withAlpha(DocPalette.TEXT, (mate.self() ? 0.95f : 0.7f) * a), false);
            int right = x + PANEL_W - CARD_PAD_X;
            if (downed) {
                String s = "倒地";
                right -= font.width(s);
                gg.drawString(font, s, right, rowY, withAlpha(DOWNED, a), false);
                right -= 6;
            }
            if (mate.isSquadLeader()) {
                gg.drawString(font, "\u2605", right - font.width("\u2605"), rowY,
                        withAlpha(DocPalette.PROGRESS, a), false);
            }
        }
    }

    // ============================================================
    // Toast
    // ============================================================

    private void drawToast(GuiGraphics gg, Font font, int screenW, int screenH, long now) {
        if (toastStartMs < 0L || toastText.isEmpty()) {
            return;
        }
        float a = PauseMenuAnim.toastAlpha(now - toastStartMs);
        if (a <= 0.002f) {
            toastStartMs = -1L;
            return;
        }
        int w = font.width(toastText) + TOAST_PAD_X * 2;
        int x = (screenW - w) / 2;
        int y = screenH - TOAST_BOTTOM - TOAST_H;
        gg.fill(x, y, x + w, y + TOAST_H, withAlpha(TOAST_BG, a));
        int border = withAlpha(DocPalette.PROGRESS, 0.5f * a);
        gg.fill(x, y, x + w, y + 1, border);
        gg.fill(x, y + TOAST_H - 1, x + w, y + TOAST_H, border);
        gg.fill(x, y, x + 1, y + TOAST_H, border);
        gg.fill(x + w - 1, y, x + w, y + TOAST_H, border);
        gg.drawString(font, toastText, x + TOAST_PAD_X, y + 6, withAlpha(DocPalette.PROGRESS, a), false);
    }

    // ============================================================
    // 子面板补间
    // ============================================================

    /** 子面板横向偏移：0=完全展开、{@link PauseSquadPanel#WIDTH}=完全收起。 */
    private float subOffset(long now) {
        long e = subStartMs < 0L ? Long.MAX_VALUE / 4 : now - subStartMs;
        if (subClosing) {
            return PauseSquadPanel.WIDTH
                    * PauseMenuAnim.inCubic(PauseMenuAnim.progress(e, 0, PauseMenuAnim.SUB_OUT_MS));
        }
        return PauseSquadPanel.WIDTH
                * (1f - PauseMenuAnim.outExpo(PauseMenuAnim.progress(e, 0, PauseMenuAnim.SUB_IN_MS)));
    }

    /** 右侧状态区让位补间的进度（打开 220ms / 收起 240ms，均 outCubic）。 */
    private float subDimProgress(long now) {
        long e = subStartMs < 0L ? Long.MAX_VALUE / 4 : now - subStartMs;
        int dur = subClosing ? PauseMenuAnim.SUB_OUT_MS : SUB_DIM_MS;
        return PauseMenuAnim.outCubic(PauseMenuAnim.progress(e, 0, dur));
    }

    // ============================================================
    // 数据文案
    // ============================================================

    private static String modeName() {
        var deploy = ClientDeployStatus.status();
        if (deploy != null && !deploy.modeName().isBlank()) {
            return deploy.modeName();
        }
        return ClientBreakthroughHud.isShown() ? "突破模式" : "征服模式";
    }

    private static String mapName() {
        var deploy = ClientDeployStatus.status();
        return deploy == null ? "" : deploy.mapName();
    }

    /** 「当前区域」行是数据驱动的；文案与 {@code CaptureFocusAnimator} 的状态词保持一致。 */
    private static String focusText(BattleHudDto hud) {
        if (hud.focusState() == 0 || hud.focusName().isBlank()) {
            return "未在据点内";
        }
        String state = switch (hud.focusState()) {
            case 1 -> "正在占领";
            case 2 -> "正在防守";
            default -> "争夺中";
        };
        return hud.focusName() + " · " + state;
    }

    // ============================================================
    // 绘制原语
    // ============================================================

    private static void card(GuiGraphics gg, int x, int y, int w, int h, float a) {
        gg.fill(x, y, x + w, y + h, withAlpha(CARD_BG, a));
        int border = withAlpha(CARD_BORDER, a);
        gg.fill(x, y, x + w, y + 1, border);
        gg.fill(x, y + h - 1, x + w, y + h, border);
        gg.fill(x, y, x + 1, y + h, border);
        gg.fill(x + w - 1, y, x + w, y + h, border);
    }

    private static void drawHollowDot(GuiGraphics gg, int x, int y, float a) {
        int c = withAlpha(OUTLINE, a);
        gg.fill(x, y, x + 7, y + 1, c);
        gg.fill(x, y + 6, x + 7, y + 7, c);
        gg.fill(x, y, x + 1, y + 7, c);
        gg.fill(x + 6, y, x + 7, y + 7, c);
    }

    /** 数值滚轮：裁剪区内旧值离场、新值进场；票数掉落时新值自上而下（方向即语义）。 */
    private static void drawRoll(GuiGraphics gg, Font font, Ticker t, int x, int y, int w,
                                 int color, float a, boolean rightAligned, long now) {
        int h = 11;
        gg.enableScissor(x, y - 1, x + w, y - 1 + h);
        float e = t.rollEased(now);
        String cur = String.valueOf(t.cur);
        if (e < 1f) {
            String prev = String.valueOf(t.prev);
            int oy = y + Math.round(-t.dir * h * e);
            gg.drawString(font, prev, rightAligned ? x + w - font.width(prev) : x, oy, withAlpha(color, a), false);
        }
        int ny = y + Math.round(t.dir * h * (1f - e));
        gg.drawString(font, cur, rightAligned ? x + w - font.width(cur) : x, ny, withAlpha(color, a), false);
        gg.disableScissor();
    }

    private static void drawScaled(GuiGraphics gg, int ax, int ay, float scale, Runnable draw) {
        PoseStack pose = gg.pose();
        pose.pushPose();
        pose.translate(ax, ay, 0);
        pose.scale(scale, scale, 1f);
        pose.translate(-ax, -ay, 0);
        draw.run();
        pose.popPose();
    }

    private static int withAlpha(int argb, float mul) {
        int base = (argb >>> 24) & 0xFF;
        int a = Math.round(base * PauseMenuAnim.clamp01(mul));
        return (argb & 0x00FFFFFF) | (a << 24);
    }

    /** 每帧的区域可见度快照，避免 render 方法之间来回重算级联进度。 */
    private record Frame(float overlay, float tag, float[] itemAlpha, float[] itemShiftX,
                         float panel, float indicator) {
    }

    /**
     * 一侧票数的"数字滚轮 + 条宽追赶"状态。
     *
     * <p>菜单打开期间票数仍在掉（对局没停），所以这里必须能随时接受新值并从<b>当前显示值</b>
     * 起播，而不是每次同步包都从头重置。
     */
    private static final class Ticker {
        private static final int UNSET = Integer.MIN_VALUE;

        int cur = UNSET;
        int prev;
        int dir = -1;
        private long rollStartMs = -1L;
        private float barFrom;
        private float barTo;
        private long barStartMs = -1L;

        void accept(int value, float targetFrac, long now) {
            if (cur == UNSET) {
                cur = value;
                prev = value;
                barFrom = targetFrac;
                barTo = targetFrac;
                return;
            }
            if (value != cur) {
                prev = cur;
                cur = value;
                dir = value < prev ? -1 : 1;
                rollStartMs = now;
            }
            if (Math.abs(targetFrac - barTo) > 0.0005f) {
                barFrom = bar(now);
                barTo = targetFrac;
                barStartMs = now;
            }
        }

        float rollEased(long now) {
            if (rollStartMs < 0L) {
                return 1f;
            }
            return PauseMenuAnim.outCubic(PauseMenuAnim.progress(now - rollStartMs, 0, ROLL_MS));
        }

        float bar(long now) {
            if (barStartMs < 0L) {
                return barTo;
            }
            float v = PauseMenuAnim.outCubic(PauseMenuAnim.progress(now - barStartMs, 0, BAR_CHASE_MS));
            return barFrom + (barTo - barFrom) * v;
        }
    }
}
