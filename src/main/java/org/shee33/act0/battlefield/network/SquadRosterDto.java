package org.shee33.act0.battlefield.network;

import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * 本阵营全部小队的名册，驱动暂停菜单的小队管理子页面（规格文档 §3.4/§3.5）。
 *
 * <p>与 HUD 里的 {@code SquadMateHudDto} 刻意分开：后者只含"我自己这一队"，而子页面必须列出
 * 同阵营所有小队的人数、锁定状态与可加入性。只下发本阵营——敌方小队的人数与锁定状态是战术
 * 情报，不该借这个界面泄露。
 */
public record SquadRosterDto(int mySquadId, List<Squad> squads) {

    private static final int MAX_SQUADS = 64;

    /** 一名成员；{@code null} 空位不编码，由 {@link Squad#size()} 与上限相减得出。 */
    public record Member(String name, boolean self, boolean leader, boolean downed) {

        void encode(FriendlyByteBuf buf) {
            buf.writeUtf(name);
            buf.writeBoolean(self);
            buf.writeBoolean(leader);
            buf.writeBoolean(downed);
        }

        static Member decode(FriendlyByteBuf buf) {
            return new Member(buf.readUtf(), buf.readBoolean(), buf.readBoolean(), buf.readBoolean());
        }
    }

    public record Squad(int squadId, boolean locked, List<Member> members) {

        public int size() {
            return members.size();
        }

        void encode(FriendlyByteBuf buf) {
            buf.writeVarInt(squadId);
            buf.writeBoolean(locked);
            buf.writeVarInt(members.size());
            for (Member m : members) {
                m.encode(buf);
            }
        }

        static Squad decode(FriendlyByteBuf buf) {
            int id = buf.readVarInt();
            boolean locked = buf.readBoolean();
            int n = buf.readVarInt();
            List<Member> members = new ArrayList<>(Math.max(0, Math.min(n, MAX_SQUADS)));
            for (int i = 0; i < n; i++) {
                members.add(Member.decode(buf));
            }
            return new Squad(id, locked, members);
        }
    }

    public static SquadRosterDto empty() {
        return new SquadRosterDto(0, List.of());
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(mySquadId);
        buf.writeVarInt(squads.size());
        for (Squad s : squads) {
            s.encode(buf);
        }
    }

    public static SquadRosterDto decode(FriendlyByteBuf buf) {
        int mine = buf.readVarInt();
        int n = buf.readVarInt();
        List<Squad> squads = new ArrayList<>(Math.max(0, Math.min(n, MAX_SQUADS)));
        for (int i = 0; i < n; i++) {
            squads.add(Squad.decode(buf));
        }
        return new SquadRosterDto(mine, squads);
    }
}
