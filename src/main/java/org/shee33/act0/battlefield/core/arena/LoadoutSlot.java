package org.shee33.act0.battlefield.core.arena;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 配装槽位。大战场自有的槽位模型，取代原先经反射借用的 Arcade {@code LoadoutSlot}。
 *
 * <p><b>为什么槽位索引就是快捷栏索引</b>：出生时装备直接写进玩家快捷栏的对应格子，
 * 部署界面的武器面板也按同一索引横向排列。二者共用一个数字，就不存在"界面第 3 格对应
 * 背包第几格"的映射表需要维护——这也是 {@code DeploySlotOptionsDto.slotIndex} 的既有语义，
 * 保持一致才能让网络 DTO 的线格式在这次改造中完全不动。
 *
 * <p><b>为什么武器与道具分成不同槽位而不是一个通用列表</b>：武器池按类别（步枪/狙击…）配置，
 * 道具池按槽位配置，两者的配置命令、校验规则、来源物品都不同（武器必须是 TaCZ 枪械，
 * 道具是普通物品）。用槽位把两者物理隔开，避免"往道具槽塞进一把狙击枪"这类配置事故。
 */
public enum LoadoutSlot {

    /** 主武器：步枪/冲锋枪/机枪/狙击/霰弹/发射器。 */
    PRIMARY("primary", "主武器", 0),
    /** 副武器：手枪。 */
    SECONDARY("secondary", "副武器", 1),
    /** 近战武器。 */
    MELEE("melee", "近战", 2),
    /** 道具槽 1。 */
    GADGET_1("gadget1", "道具1", 3),
    /** 道具槽 2。 */
    GADGET_2("gadget2", "道具2", 4);

    private final String id;
    private final String displayName;
    private final int hotbarIndex;

    LoadoutSlot(String id, String displayName, int hotbarIndex) {
        this.id = id;
        this.displayName = displayName;
        this.hotbarIndex = hotbarIndex;
    }

    /** 命令里使用的小写字面量。 */
    public String id() {
        return id;
    }

    /** 面板与命令回显用的中文名。 */
    public String displayName() {
        return displayName;
    }

    /** 玩家快捷栏索引，同时也是 {@code DeploySlotOptionsDto.slotIndex}。 */
    public int hotbarIndex() {
        return hotbarIndex;
    }

    /** 是否为道具槽（道具子树只接受这些槽位）。 */
    public boolean isGadget() {
        return this == GADGET_1 || this == GADGET_2;
    }

    /** 该槽位装的是 TaCZ 枪械还是普通物品。 */
    public boolean isWeapon() {
        return !isGadget();
    }

    /** 全部道具槽，按顺序。 */
    public static List<LoadoutSlot> gadgetSlots() {
        return List.of(GADGET_1, GADGET_2);
    }

    /** 按命令字面量解析槽位；无匹配返回 {@code null}。 */
    @Nullable
    public static LoadoutSlot byId(@Nullable String id) {
        if (id == null) {
            return null;
        }
        for (LoadoutSlot slot : values()) {
            if (slot.id.equalsIgnoreCase(id)) {
                return slot;
            }
        }
        return null;
    }

    /** 按快捷栏索引反查槽位；越界返回 {@code null}。 */
    @Nullable
    public static LoadoutSlot byHotbarIndex(int index) {
        for (LoadoutSlot slot : values()) {
            if (slot.hotbarIndex == index) {
                return slot;
            }
        }
        return null;
    }
}
