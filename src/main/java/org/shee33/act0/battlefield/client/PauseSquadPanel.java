package org.shee33.act0.battlefield.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import org.shee33.act0.battlefield.core.PauseMenuAnim;
import org.shee33.act0.battlefield.core.SquadJoinRules;
import org.shee33.act0.battlefield.core.SquadManagerLimits;
import org.shee33.act0.battlefield.network.BattleHudDto;
import org.shee33.act0.battlefield.network.SquadActionPacket;
import org.shee33.act0.battlefield.network.SquadRosterDto;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * 暂停菜单「小队管理」子面板（规格文档 §2「小队管理子页面」+ §3.4）。
 *
 * <p>按 §6「Minecraft 移植对照」的要求，这不是一个新的 {@code Screen}——它是同一个
 * {@link BattlefieldPauseScreen} 内部的一块面板，靠 {@code xOffset} 补间 + {@code enableScissor}
 * 从右缘滑入。这样「返回」是零成本的（主菜单一直在场，不需要重建），而且子面板里的滚动/焦点状态
 * 天然不会因为来回切页而丢失。
 *
 * <p>本类<b>不持有任何小队数据</b>：每帧现读 {@link ClientSquadRoster}。规格文档 §3.5 要求
 * 「右侧缩略与子页面共用同一数据源，任一处变更双侧同步重渲染」——共用数据源的唯一可靠做法就是
 * 两边都不缓存。操作也一律不做乐观更新，只发包（{@link SquadActionPacket}）；服务端复核完
 * {@link SquadJoinRules} 后会重新下发名册，下一帧自然就画对了。本地先改再等回包只会在服务端
 * 驳回时留下一个与真实状态不一致的假象。
 */
final class PauseSquadPanel {

    /** 子面板宽度（规格文档 §2：右缘、宽 340、全高）。 */
    static final int WIDTH = 340;

    private static final int PAD_X = 20;
    private static final int HEADER_TITLE_Y = 18;
    private static final int DIVIDER_Y = 42;
    private static final int BODY_TOP = 56;

    private static final int BACK_W = 14;
    private static final int BACK_H = 14;

    private static final int MEMBER_ROW_H = 20;
    private static final int MEMBER_ROW_STRIDE = 25;
    private static final int OTHER_ROW_H = 22;
    private static final int OTHER_ROW_STRIDE = 27;
    private static final int LEAVE_BTN_H = 18;
    private static final int JOIN_BTN_W = 56;
    private static final int JOIN_BTN_H = 14;

    /** 锁定开关：外框 26×13、旋钮 9×9（规格文档 §3.4）。 */
    private static final int TOGGLE_W = 26;
    private static final int TOGGLE_H = 13;
    private static final int KNOB = 9;
    private static final int KNOB_TRAVEL = 12;

    private static final int SUB_BG = 0xF50B0E13;
    private static final int SUB_BORDER = 0x1FFFFFFF;
    private static final int ROW_BG = 0x08FFFFFF;
    private static final int LABEL = 0x59E8EDF2;
    private static final int DIVIDER = 0x1AFFFFFF;
    private static final int OUTLINE = 0x40FFFFFF;
    private static final int ALIVE = 0xFF6EE27E;
    private static final int DOWNED = 0xFFFFB64F;
    private static final int FULL_BG = 0x14FFFFFF;

    /** 锁定旋钮的 outBack 滑动（§3.4：200ms）。 */
    private static final int KNOB_SLIDE_MS = 200;

    /** 一次待下发的小队操作 + 点击当刻要弹的 Toast 文案。 */
    record Request(int kind, int targetSquadId, String toast) {
    }

    /**
     * 上一帧观察到的服务端锁定状态，与 {@link #lockChangedAtMs} 一起驱动旋钮的 outBack 滑动。
     *
     * <p>刻意<b>不做乐观更新</b>：旋钮位置永远等于服务端真值，只在真值变化的那一帧启动滑动
     * （规格文档 §3.5「操作结果以服务端回包为准」）。乐观翻转的代价是服务端驳回时旋钮会永远停在
     * 错误一侧——而点击反馈由 Toast 立即给出，玩家并不缺即时响应。{@code null} = 尚未观察过。
     */
    @Nullable
    private Boolean lockShown;
    private long lockChangedAtMs = -1L;

    /** 每帧重算的布局，点击判定复用同一份，避免绘制与命中框错位。 */
    @Nullable
    private Layout layout;

    /**
     * 绘制子面板。
     *
     * @param xOffset       右滑偏移（0=完全展开，{@link #WIDTH}=完全收起），由调用方按
     *                      {@code SUB_IN_MS} outExpo / {@code SUB_OUT_MS} inCubic 算好
     * @param sinceOpenedMs 子面板本次打开至今的毫秒数，驱动内容行 45ms/行 错峰滑入
     */
    void render(GuiGraphics gg, Font font, int screenW, int screenH, int mouseX, int mouseY,
                float xOffset, long sinceOpenedMs) {
        int baseX = screenW - WIDTH;
        int x = baseX + Math.round(xOffset);
        Layout lay = buildLayout(baseX);
        layout = lay;

        // 面板整体在滑动，超出屏幕右缘的部分必须裁掉；否则 translate 之后的文字会画到屏幕外的
        // 负空间里被 MC 保留，视觉上表现为文字提前"贴"在右缘。
        gg.enableScissor(Math.max(0, x), 0, screenW, screenH);
        gg.fill(x, 0, x + WIDTH, screenH, SUB_BG);
        gg.fill(x, 0, x + 1, screenH, SUB_BORDER);

        int dx = x - baseX;
        drawHeader(gg, font, lay, dx, mouseX, mouseY);
        gg.fill(lay.dividerX1 + dx, DIVIDER_Y, lay.dividerX2 + dx, DIVIDER_Y + 1, DIVIDER);
        drawBody(gg, font, lay, dx, mouseX, mouseY, sinceOpenedMs);
        gg.disableScissor();
    }

    /** 点击判定；仅在面板完全展开时由调用方转发。返回 {@code null} 表示没命中任何可操作元素。 */
    @Nullable
    Request click(double mx, double my) {
        Layout lay = layout;
        if (lay == null) {
            return null;
        }
        if (lay.hasLock && lay.lockEnabled && hit(mx, my, lay.lockX, lay.lockY, TOGGLE_W, TOGGLE_H)) {
            return new Request(SquadActionPacket.KIND_TOGGLE_LOCK, 0,
                    lay.locked ? "已解锁小队" : "已锁定小队");
        }
        if (lay.hasLeave && hit(mx, my, lay.leaveX, lay.leaveY, lay.bodyW, LEAVE_BTN_H)) {
            return new Request(SquadActionPacket.KIND_LEAVE, 0, "已离开小队");
        }
        for (OtherRow row : lay.others) {
            if (row.joinable && hit(mx, my, row.btnX, row.btnY, JOIN_BTN_W, JOIN_BTN_H)) {
                return new Request(SquadActionPacket.KIND_JOIN, row.squadId(),
                        "已加入 " + squadName(row.squadId()));
            }
        }
        return null;
    }

    /** 返回「‹」是否被点中——收起子面板由调用方负责（动画状态在动画层）。 */
    boolean clickedBack(double mx, double my) {
        Layout lay = layout;
        return lay != null && hit(mx, my, lay.backX, lay.backY, BACK_W, BACK_H);
    }

    // ============================================================
    // 布局
    // ============================================================

    private Layout buildLayout(int baseX) {
        SquadRosterDto roster = ClientSquadRoster.get();
        BattleHudDto hud = ClientBattleHud.hud();
        boolean leader = hud != null && hud.isSquadLeader();
        int mine = roster.mySquadId();
        int bodyX = baseX + PAD_X;
        int bodyW = WIDTH - PAD_X * 2;

        Layout lay = new Layout();
        lay.bodyX = bodyX;
        lay.bodyW = bodyW;
        lay.mySquadId = mine;
        lay.backX = baseX + PAD_X - 4;
        lay.backY = HEADER_TITLE_Y - 3;
        lay.titleX = baseX + PAD_X + 16;
        lay.dividerX1 = baseX + PAD_X;
        lay.dividerX2 = baseX + WIDTH - PAD_X;

        SquadRosterDto.Squad mySquad = findSquad(roster, mine);
        lay.mineHeaderY = BODY_TOP;
        lay.hasLock = mySquad != null;
        lay.lockEnabled = SquadJoinRules.canToggleLock(leader, mine);
        lay.locked = mySquad != null && mySquad.locked();
        lay.lockX = bodyX + bodyW - TOGGLE_W;
        lay.lockY = BODY_TOP - 2;

        int y = BODY_TOP + 20;
        if (mySquad != null) {
            List<SquadRosterDto.Member> members = mySquad.members();
            for (int i = 0; i < SquadManagerLimits.MAX_SQUAD_SIZE; i++) {
                lay.members.add(new MemberRow(y + i * MEMBER_ROW_STRIDE,
                        i < members.size() ? members.get(i) : null));
            }
            y += SquadManagerLimits.MAX_SQUAD_SIZE * MEMBER_ROW_STRIDE;
            lay.hasLeave = true;
            lay.leaveX = bodyX;
            lay.leaveY = y;
            y += LEAVE_BTN_H + 8;
        } else {
            lay.hintY = y;
            y += 18;
        }

        lay.otherLabelY = y;
        y += 16;
        int idx = 0;
        for (SquadRosterDto.Squad squad : roster.squads()) {
            if (squad.squadId() == mine) {
                continue;
            }
            SquadJoinRules.Result result = SquadJoinRules.canJoin(mine, squad.squadId(),
                    squad.size(), squad.locked(), true);
            int rowY = y + idx * OTHER_ROW_STRIDE;
            lay.others.add(new OtherRow(rowY, squad, result == SquadJoinRules.Result.OK,
                    result, bodyX + bodyW - JOIN_BTN_W, rowY + (OTHER_ROW_H - JOIN_BTN_H) / 2));
            idx++;
        }
        return lay;
    }

    // ============================================================
    // 绘制
    // ============================================================

    private void drawHeader(GuiGraphics gg, Font font, Layout lay, int dx, int mouseX, int mouseY) {
        boolean backHot = hit(mouseX, mouseY, lay.backX, lay.backY, BACK_W, BACK_H);
        gg.drawString(font, "\u2039", lay.backX + dx + 4, lay.backY + 3,
                backHot ? 0xFFFFFFFF : 0x99E8EDF2, false);
        gg.drawString(font, "小队管理", lay.titleX + dx, HEADER_TITLE_Y, 0xFFFFFFFF, false);
    }

    private void drawBody(GuiGraphics gg, Font font, Layout lay, int dx, int mouseX, int mouseY,
                          long sinceOpenedMs) {
        String tag = lay.mySquadId > 0 ? squadName(lay.mySquadId) : "未加入";
        gg.drawString(font, "我的小队 · " + tag, lay.bodyX + dx, lay.mineHeaderY, LABEL, false);
        if (lay.hasLock) {
            drawLockToggle(gg, font, lay, dx, mouseX, mouseY);
        }

        int rowIndex = 0;
        for (MemberRow row : lay.members) {
            float v = rowProgress(sinceOpenedMs, rowIndex++);
            if (v > 0f) {
                drawMemberRow(gg, font, lay, row, dx, v);
            }
        }
        if (lay.mySquadId <= 0) {
            gg.drawString(font, "你当前未加入任何小队", lay.bodyX + dx, lay.hintY, LABEL, false);
        }
        if (lay.hasLeave) {
            drawLeaveButton(gg, font, lay, dx, mouseX, mouseY);
        }
        gg.drawString(font, "其他小队", lay.bodyX + dx, lay.otherLabelY, LABEL, false);
        for (OtherRow row : lay.others) {
            float v = rowProgress(sinceOpenedMs, rowIndex++);
            if (v > 0f) {
                drawOtherRow(gg, font, lay, row, dx, mouseX, mouseY, v);
            }
        }
    }

    /** §3.4：内容行从右 12px 错峰滑入（240ms outCubic，45ms/行）。 */
    private static float rowProgress(long sinceOpenedMs, int index) {
        return PauseMenuAnim.outCubic(PauseMenuAnim.progress(sinceOpenedMs,
                index * PauseMenuAnim.SUB_ROW_STAGGER_MS, PauseMenuAnim.SUB_ROW_IN_MS));
    }

    private void drawLockToggle(GuiGraphics gg, Font font, Layout lay, int dx, int mouseX, int mouseY) {
        boolean hot = lay.lockEnabled && hit(mouseX, mouseY, lay.lockX, lay.lockY, TOGGLE_W, TOGGLE_H);
        float labelAlpha = lay.lockEnabled ? 0.5f : 0.25f;
        String label = "锁定小队";
        gg.drawString(font, label, lay.lockX + dx - font.width(label) - 7, lay.lockY + 3,
                withAlpha(0xFFE8EDF2, hot ? 0.8f : labelAlpha), false);
        int x = lay.lockX + dx;
        int y = lay.lockY;
        int frame = withAlpha(0xFFFFFFFF, lay.lockEnabled ? 0.3f : 0.15f);
        gg.fill(x, y, x + TOGGLE_W, y + 1, frame);
        gg.fill(x, y + TOGGLE_H - 1, x + TOGGLE_W, y + TOGGLE_H, frame);
        gg.fill(x, y, x + 1, y + TOGGLE_H, frame);
        gg.fill(x + TOGGLE_W - 1, y, x + TOGGLE_W, y + TOGGLE_H, frame);

        if (lockShown == null) {
            lockShown = lay.locked;
        } else if (lockShown != lay.locked) {
            lockShown = lay.locked;
            lockChangedAtMs = Tween.now();
        }
        boolean target = lay.locked;
        float v = lockChangedAtMs < 0 ? 1f
                : PauseMenuAnim.outBack(PauseMenuAnim.progress(Tween.now() - lockChangedAtMs, 0, KNOB_SLIDE_MS));
        float from = target ? 2f : 2f + KNOB_TRAVEL;
        float to = target ? 2f + KNOB_TRAVEL : 2f;
        int kx = x + Math.round(from + (to - from) * v);
        int ky = y + (TOGGLE_H - KNOB) / 2;
        gg.fill(kx, ky, kx + KNOB, ky + KNOB, target ? DocPalette.PROGRESS : 0x66FFFFFF);
    }

    private void drawMemberRow(GuiGraphics gg, Font font, Layout lay, MemberRow row, int dx, float v) {
        int slide = Math.round(12f * (1f - v));
        int x = lay.bodyX + dx + slide;
        int y = row.y;
        gg.fill(x, y, x + lay.bodyW, y + MEMBER_ROW_H, withAlpha(ROW_BG, v));
        int dotY = y + (MEMBER_ROW_H - 8) / 2;
        SquadRosterDto.Member m = row.member;
        if (m == null) {
            drawHollowDot(gg, x + 10, dotY, v);
            gg.drawString(font, "空位", x + 24, y + 6, withAlpha(0xFFE8EDF2, 0.3f * v), false);
            return;
        }
        gg.fill(x + 10, dotY, x + 18, dotY + 8, withAlpha(m.downed() ? DOWNED : ALIVE, v));
        String name = m.name() + (m.self() ? " (你)" : "");
        gg.drawString(font, name, x + 24, y + 6, withAlpha(0xFFE8EDF2, (m.self() ? 0.95f : 0.72f) * v), false);
        int right = x + lay.bodyW - 8;
        if (m.downed()) {
            String s = "倒地";
            right -= font.width(s);
            gg.drawString(font, s, right, y + 6, withAlpha(DOWNED, v), false);
            right -= 6;
        }
        if (m.leader()) {
            String s = "\u2605 队长";
            gg.drawString(font, s, right - font.width(s), y + 6, withAlpha(DocPalette.PROGRESS, v), false);
        }
    }

    private void drawLeaveButton(GuiGraphics gg, Font font, Layout lay, int dx, int mouseX, int mouseY) {
        int x = lay.leaveX + dx;
        int y = lay.leaveY;
        boolean hot = hit(mouseX, mouseY, lay.leaveX, y, lay.bodyW, LEAVE_BTN_H);
        int border = withAlpha(DocPalette.ENEMY, hot ? 0.85f : 0.5f);
        gg.fill(x, y, x + lay.bodyW, y + 1, border);
        gg.fill(x, y + LEAVE_BTN_H - 1, x + lay.bodyW, y + LEAVE_BTN_H, border);
        gg.fill(x, y, x + 1, y + LEAVE_BTN_H, border);
        gg.fill(x + lay.bodyW - 1, y, x + lay.bodyW, y + LEAVE_BTN_H, border);
        String s = "离开小队";
        gg.drawString(font, s, x + (lay.bodyW - font.width(s)) / 2, y + 5, DocPalette.ENEMY, false);
    }

    private void drawOtherRow(GuiGraphics gg, Font font, Layout lay, OtherRow row, int dx,
                              int mouseX, int mouseY, float v) {
        int slide = Math.round(12f * (1f - v));
        int x = lay.bodyX + dx + slide;
        int y = row.y;
        gg.fill(x, y, x + lay.bodyW, y + OTHER_ROW_H, withAlpha(ROW_BG, v));
        gg.drawString(font, squadName(row.squadId()), x + 10, y + 7, withAlpha(0xFFE8EDF2, 0.8f * v), false);
        String count = row.squad.size() + " / " + SquadManagerLimits.MAX_SQUAD_SIZE
                + (row.squad.locked() ? " · 已锁定" : "");
        gg.drawString(font, count, x + lay.bodyW - JOIN_BTN_W - 12 - font.width(count), y + 7,
                withAlpha(0xFFE8EDF2, 0.4f * v), false);

        int bx = row.btnX + dx + slide;
        int by = row.btnY;
        String label = switch (row.reason) {
            case OK -> "加入";
            case LOCKED -> "已锁定";
            default -> "已满";
        };
        boolean hot = row.joinable && hit(mouseX, mouseY, row.btnX, by, JOIN_BTN_W, JOIN_BTN_H);
        if (row.joinable) {
            gg.fill(bx, by, bx + JOIN_BTN_W, by + JOIN_BTN_H, withAlpha(DocPalette.PROGRESS, v));
            if (hot) {
                gg.fill(bx, by, bx + JOIN_BTN_W, by + 1, withAlpha(0xFFFFFFFF, 0.5f * v));
            }
            gg.drawString(font, label, bx + (JOIN_BTN_W - font.width(label)) / 2, by + 3,
                    withAlpha(0xFF14181D, v), false);
        } else {
            gg.fill(bx, by, bx + JOIN_BTN_W, by + JOIN_BTN_H, withAlpha(FULL_BG, v));
            gg.drawString(font, label, bx + (JOIN_BTN_W - font.width(label)) / 2, by + 3,
                    withAlpha(0xFFE8EDF2, 0.35f * v), false);
        }
    }

    private static void drawHollowDot(GuiGraphics gg, int x, int y, float v) {
        int c = withAlpha(OUTLINE, v);
        gg.fill(x, y, x + 8, y + 1, c);
        gg.fill(x, y + 7, x + 8, y + 8, c);
        gg.fill(x, y, x + 1, y + 8, c);
        gg.fill(x + 7, y, x + 8, y + 8, c);
    }

    // ============================================================
    // 辅助
    // ============================================================

    /** 服务端只下发 {@code squadId}，本仓库没有小队代号表，故按序号成文案，不臆造 ALPHA/BRAVO 名。 */
    static String squadName(int squadId) {
        return "小队 " + squadId;
    }

    @Nullable
    private static SquadRosterDto.Squad findSquad(SquadRosterDto roster, int squadId) {
        if (squadId <= 0) {
            return null;
        }
        for (SquadRosterDto.Squad s : roster.squads()) {
            if (s.squadId() == squadId) {
                return s;
            }
        }
        return null;
    }

    private static boolean hit(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private static int withAlpha(int argb, float mul) {
        int base = (argb >>> 24) & 0xFF;
        int a = Math.round(base * PauseMenuAnim.clamp01(mul));
        return (argb & 0x00FFFFFF) | (a << 24);
    }

    private record MemberRow(int y, @Nullable SquadRosterDto.Member member) {
    }

    private record OtherRow(int y, SquadRosterDto.Squad squad, boolean joinable,
                            SquadJoinRules.Result reason, int btnX, int btnY) {

        int squadId() {
            return squad.squadId();
        }
    }

    /** 每帧重建的布局快照：绘制与命中判定共用，杜绝两套坐标算法漂移。 */
    private static final class Layout {
        int bodyX;
        int bodyW;
        int mySquadId;
        int backX;
        int backY;
        int titleX;
        int dividerX1;
        int dividerX2;
        int mineHeaderY;
        boolean hasLock;
        boolean lockEnabled;
        boolean locked;
        int lockX;
        int lockY;
        boolean hasLeave;
        int leaveX;
        int leaveY;
        int hintY;
        int otherLabelY;
        final List<MemberRow> members = new ArrayList<>();
        final List<OtherRow> others = new ArrayList<>();
    }
}
