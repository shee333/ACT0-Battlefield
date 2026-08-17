package org.shee33.act0.battlefield.network;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DeployLoadoutDto#isValidOverride}/{@link DeployLoadoutDto#withOverrides} 是纯函数，
 * 不依赖 {@code ServerPlayer}，覆盖"合法覆盖被接受"/"非法覆盖被拒绝"两种场景。
 */
class DeployLoadoutOverrideTest {

    /** 构造可选项：显示名刻意与 ID 不同，用来确认校验走的是 ID 而不是显示名。 */
    private static List<DeployOptionDto> opts(String... ids) {
        List<DeployOptionDto> out = new ArrayList<>(ids.length);
        for (String id : ids) {
            out.add(new DeployOptionDto(id, "名称:" + id));
        }
        return out;
    }

    private static DeployLoadoutDto sampleLoadout() {
        DeploySlotOptionsDto primary = new DeploySlotOptionsDto(0, "主武器", "m4a1",
                opts("m4a1", "ak74", "scar_h"));
        DeploySlotOptionsDto secondary = new DeploySlotOptionsDto(1, "副武器", "g17",
                opts("g17", "deagle"));
        return new DeployLoadoutDto("", List.of(primary, secondary));
    }

    /** 显示名绝不能被当成选择键：否则界面上同名的两把枪会互相顶替。 */
    @Test
    void displayNameIsNeverAcceptedAsSelectionKey() {
        DeployLoadoutDto loadout = sampleLoadout();
        assertFalse(loadout.isValidOverride(0, "名称:ak74"), "显示名不是合法的选择键");
        assertTrue(loadout.isValidOverride(0, "ak74"));
        assertEquals("名称:m4a1", loadout.slots().get(0).currentDisplayName());
        assertEquals("名称:ak74", loadout.slots().get(0).displayNameOf("ak74"));
        assertEquals("unknown", loadout.slots().get(0).displayNameOf("unknown"),
                "不在可选项里的 ID 应退回 ID 本身，而不是空串");
    }

    /** 覆盖后可选项列表必须原样带过去，否则换装一次之后面板就再也列不出别的枪。 */
    @Test
    void overrideKeepsOptionList() {
        DeployLoadoutDto overridden = sampleLoadout().withOverrides(Map.of(0, "ak74"));
        DeploySlotOptionsDto slot = overridden.slots().get(0);
        assertEquals(List.of("m4a1", "ak74", "scar_h"), slot.availableItemNames());
        assertEquals("名称:ak74", slot.currentDisplayName());
    }

    @Test
    void legalOverrideIsAccepted() {
        DeployLoadoutDto loadout = sampleLoadout();
        assertTrue(loadout.isValidOverride(0, "ak74"), "该槽位可选列表里的物品应被接受");
    }

    @Test
    void unknownItemIsRejected() {
        DeployLoadoutDto loadout = sampleLoadout();
        assertFalse(loadout.isValidOverride(0, "未解锁的枪"), "不在可选列表内的物品应被拒绝");
    }

    @Test
    void itemValidForAnotherSlotIsRejected() {
        DeployLoadoutDto loadout = sampleLoadout();
        assertFalse(loadout.isValidOverride(0, "g17"), "副武器的物品提交到主武器槽位应被拒绝");
    }

    @Test
    void unknownSlotIndexIsRejected() {
        DeployLoadoutDto loadout = sampleLoadout();
        assertFalse(loadout.isValidOverride(99, "m4a1"), "不存在的槽位序号应被拒绝");
    }

    @Test
    void blankItemNameIsRejected() {
        DeployLoadoutDto loadout = sampleLoadout();
        assertFalse(loadout.isValidOverride(0, ""));
        assertFalse(loadout.isValidOverride(0, null));
    }

    @Test
    void withOverridesReplacesCurrentItemNameForOverriddenSlot() {
        DeployLoadoutDto loadout = sampleLoadout();
        DeployLoadoutDto overridden = loadout.withOverrides(Map.of(0, "ak74"));
        assertEquals("ak74", overridden.slots().get(0).currentItemName());
        assertEquals("g17", overridden.slots().get(1).currentItemName(), "未覆盖的槽位应维持原样");
    }

    @Test
    void withOverridesIgnoresIllegalOverride() {
        DeployLoadoutDto loadout = sampleLoadout();
        DeployLoadoutDto overridden = loadout.withOverrides(Map.of(0, "未解锁的枪"));
        assertEquals("m4a1", overridden.slots().get(0).currentItemName(), "非法覆盖不应改变当前显示项");
    }

    @Test
    void withOverridesReturnsSameInstanceWhenNoChangeApplies() {
        DeployLoadoutDto loadout = sampleLoadout();
        assertSame(loadout, loadout.withOverrides(null));
        assertSame(loadout, loadout.withOverrides(Map.of()));
        assertSame(loadout, loadout.withOverrides(Map.of(0, "m4a1")), "覆盖成当前已选中的同一项不应产生变化");
    }
}
