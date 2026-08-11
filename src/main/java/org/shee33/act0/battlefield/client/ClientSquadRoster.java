package org.shee33.act0.battlefield.client;

import org.shee33.act0.battlefield.network.SquadRosterDto;

/** 客户端侧本阵营小队名册快照，供暂停菜单的小队管理页读取。 */
public final class ClientSquadRoster {

    private static volatile SquadRosterDto roster = SquadRosterDto.empty();

    public static void accept(SquadRosterDto dto) {
        roster = dto == null ? SquadRosterDto.empty() : dto;
    }

    public static SquadRosterDto get() {
        return roster;
    }

    public static void clear() {
        roster = SquadRosterDto.empty();
    }

    private ClientSquadRoster() {
    }
}
