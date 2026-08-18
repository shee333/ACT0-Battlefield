# ACT0-Battlefield 建图手册（aew1 分支）

面向服主与管理员。读完本文你能从一片空地搭出一张可以开打的征服地图。

* 适用版本：Minecraft 1.20.1 / Forge 47.4.10 及以上 / 本模组 `0.2.8`（aew1 分支）
* 所有建图命令都在 `/aew1` 下，需要 **OP 权限等级 2**
* 枪械依赖 **TaCZ**（Timeless and Classics Zero）。没装 TaCZ 也能建图开局，但军械库里放不进任何枪

---

## 1. 最短路径：建出第一张能打的图

在你要当战场的那个世界里，站到 A 方出生点，依次执行：

```
/aew1 map name Dust2 "反恐精英" "恐怖分子"
/aew1 base set alpha
```

走到 B 方出生点：

```
/aew1 base set bravo
```

走到每个据点位置，各执行一次（`~ ~ ~` 表示脚下）：

```
/aew1 point add ~ ~ ~
```

手持一把 TaCZ 步枪、一把手枪、一把近战武器，各执行一次：

```
/aew1 arena Dust2 weapon rifle add
/aew1 arena Dust2 weapon pistol add
/aew1 arena Dust2 weapon melee add
```

手持医疗针、弹药箱，各执行一次：

```
/aew1 arena Dust2 item gadget1 add 1
/aew1 arena Dust2 item gadget2 add 3
```

最后确认并开打：

```
/aew1 map info
/aew1 arena Dust2 info
/aew1 join
/aew1 start
```

> **中文名必须加引号。** Minecraft 的命令参数不加引号时只认 ASCII，`/aew1 map name 沙城 红队 蓝队` 会报
> `Expected whitespace to end one argument`——这句话跟"要加引号"毫无字面关联，很容易卡住。
> 正确写法：`/aew1 map name "沙城" "红队" "蓝队"`。按 Tab 键可以直接补出带引号的形式。

---

## 2. 建图详解

### 2.1 命名地图与阵营（第一步，别跳过）

```
/aew1 map name <地图名> <阵营1> <阵营2>
```

三个参数**全部必填**，少一个都建不出来。阵营1 是蓝方，阵营2 是红方。

阵营名称跟着地图走，每张图可以有自己的番号——凡尔登用「凡尔登守备军 / 第七装甲师」，现代图用别的，互不影响。名称会出现在聊天提示、TAB 战绩面板、对局浏览器和战报里。

**阵营颜色固定为蓝（阵营1）/ 红（阵营2），不可配置。** 整套 HUD、小地图敌我标记、据点归属色都靠这两个颜色区分敌我，允许改色等于允许把敌我涂成一个颜色。

阵营名的限制：

| 规则 | 说明 |
|---|---|
| 不能为空 | |
| 最多 16 个字符 | TAB 面板和对局浏览器按这个宽度排版，更长会被截断成 `xxx..` |
| 不能含 `§` 或控制字符 | `§` 会覆盖阵营配色、`§k` 会把整行搅乱 |
| 两方不能同名 | 大小写和首尾空格不算区别，`Alpha` 和 `alpha` 视为同名 |

事后改名（不动地图名）：

```
/aew1 map factions <阵营1> <阵营2>
```

> **为什么要先命名再配军械库：** 军械库是按**地图名**索引的。如果你先配枪再命名，军械库会绑在维度 ID
> （如 `minecraft:overworld`）上，改名之后本图按新名字查询、查到空的，玩家就空手上场了。
> 真发生了这种情况，命令会当场警告你，照提示重配或把名字改回去即可。

### 2.2 双方基地

站到出生点，面朝你希望玩家出生时看的方向：

```
/aew1 base set alpha
/aew1 base set bravo
```

取的是**执行者的坐标和视角朝向**，所以这条命令必须真人站位执行，控制台不行。两方基地都必须设置，缺一个就开不了局。

### 2.3 据点

两种方式，效果完全一样：

* **放方块**：手持 `act0_battlefield:control_point` 放下即登记，破坏即注销
* **发命令**：`/aew1 point add <x> <y> <z>`，支持 `~ ~ ~` 相对坐标

据点编号自动分配（A、B、C……）。查看已登记的据点：

```
/aew1 point list
```

调整单个据点（`<id>` 用 `point list` 里显示的编号）：

```
/aew1 point radius <id> <1-64>      占领区半径，默认 8
/aew1 point height <id> <1-64>      占领区高度，默认 4
/aew1 point name <id> <名称>         自定义显示名
```

据点在世界里会有一个悬浮标记，可以单独调：

```
/aew1 point marker size <id> <0.4-5.0>          标记大小
/aew1 point marker distance <id> <32-1000>      多远开始可见
/aew1 point marker offset <id> <x> <y> <z>      相对据点的位移，±64
```

**平衡性建议**（参考 BF3/BF4）：占领区半径 8 格、高度 4 格适合步战为主的中小图；据点数量建议 3–5 个，奇数个避免僵持。

### 2.4 战斗区域（可选）

不设置时，系统按「双方基地 + 所有据点」的包围盒外扩 16 格自动推导。玩家跑出区域会被警告并计时拉回。

```
/aew1 area info                                          查看当前生效区域
/aew1 area here <8-4096>                                 以自己为中心按半径设定
/aew1 area set <minX> <minY> <minZ> <maxX> <maxY> <maxZ>  精确设定
/aew1 area clear                                         清除，恢复自动推导
```

只有在自动推导的范围不合理时（比如地图是狭长走廊、或有不希望玩家进入的区域）才需要手动设。

### 2.5 军械库（不配就是空手上场）

这一步**不在开局校验里**——不配军械库照样能开打，但玩家出生时两手空空，只有服务端日志会打一条告警。所以别忘。

军械库按地图名索引，与地图布置分开存储，服务器级保存。

**上架枪械**（手持要上架的枪执行）：

```
/aew1 arena <地图名> weapon <类别> add [备弹数]
```

| 类别 | 落到哪个槽 |
|---|---|
| `rifle` `smg` `machinegun` `sniper` `shotgun` `launcher` | 主武器（快捷栏 1） |
| `pistol` | 副武器（快捷栏 2） |
| `melee` | 近战（快捷栏 3） |

备弹数不填时按弹匣容量自动推导（约 3 个备用弹匣）。若模组读不出弹匣容量会退回 0，此时命令会明确提示你手动指定——**看到这个提示一定要补**，否则玩家打完出厂那一匣就没弹了。手动范围 0–9999。

**上架道具**（手持要上架的物品执行）：

```
/aew1 arena <地图名> item gadget1 add [数量]     道具槽 1（快捷栏 4）
/aew1 arena <地图名> item gadget2 add [数量]     道具槽 2（快捷栏 5）
```

数量 1–64。枪械不能放道具槽，普通物品不能放武器槽，放错会被拒绝。

本模组自带的三件道具：

| 物品 ID | 作用 |
|---|---|
| `act0_battlefield:medic_syringe` | 医疗针。**手持时可跨小队扶起倒地者，且扶起速度 3 倍**（不手持只能扶本小队队友）。每次救援后 2 秒冷却 |
| `act0_battlefield:medic_box` | 医疗箱。放下后存活 30 秒，半径 3 格内友军延迟 1.5 秒起效、3 秒回满；对同一人 10 秒才能再触发 |
| `act0_battlefield:ammo_box` | 弹药箱。放下后存活 30 秒，半径 3 格内友军单次补 60 发 |

部署物的地面提示圆就是真实生效范围（3 格），玩家看到的圈不会骗人。

> **医疗针决定了这张图有没有"医疗兵"。** 本模组不设兵种选择，能不能救人完全取决于玩家道具槽里
> 有没有医疗针。**不把医疗针放进军械库，这张图就没有跨小队救援**，倒地的人只能等本小队队友。
> 这是配军械库时最容易漏掉、又最影响体验的一项。

原版物品同样可以放（绷带、方块、投掷物都行），按你的玩法设计来。

**同一个槽位最多 64 个可选项。** 玩家在部署界面从池子里选，不选则随机抽一个。

**查看与下架**：

```
/aew1 arena list                                  列出所有已知地图及其配置概况
/aew1 arena <地图名> info                          本图各槽位配了几项
/aew1 arena <地图名> weapon <类别> list            某类别的枪列表
/aew1 arena <地图名> item gadget1 list             某道具槽的列表
/aew1 arena <地图名> weapon <类别> remove <枪械ID>
/aew1 arena <地图名> item gadget1 remove <物品ID>
```

**每张图至少要配**：一把主武器 + 一把近战 + 两个道具槽各一项。副武器可以不配（玩家就没副武器）。

### 2.6 人数规则

```
/aew1 map minplayers <0-128>     候选名单达到这个人数自动开局
/aew1 map maxplayers <0-128>     对局人数上限，满了进不来
```

填 `0` 表示跟随全局配置（默认自动开局 8 人、上限 32 人）。这两个是独立概念：`minplayers` 是"够了就开打"，`maxplayers` 是"满了就进不来"。设置时系统会校验自洽性——开局人数大于上限会被当场拒绝，因为那样对局永远开不了。

---

## 3. 突破模式

突破模式（`/aew1 breakthrough ...`）是进攻方逐个区域推进、防守方阻止的模式。ALPHA = 进攻方，BRAVO = 防守方。

建图流程与征服模式**共用地图名、阵营名、据点、基地**，只多一步：**必须至少划分 1 个区域（sector）**。

```
/aew1 breakthrough map name <地图名> <进攻方> <防守方>
/aew1 breakthrough base set attacker      站在进攻方出生点
/aew1 breakthrough base set defender      站在防守方出生点
/aew1 point add ~ ~ ~                     据点用征服模式的命令登记
/aew1 breakthrough sector add <id> <区域名> <据点ID列表>
```

据点 ID 列表用空格分隔，例如把 A、B 两个据点划为第 1 区域：

```
/aew1 breakthrough sector add 1 "第一区域" 0 1
```

区域按 id 从小到大依次开放，进攻方打完一个才推进到下一个。

```
/aew1 breakthrough sector list
/aew1 breakthrough sector remove <id>
/aew1 breakthrough map info
/aew1 breakthrough status
```

票数规则：进攻方初始 300 票，每占下一个区域补 50 票。防守方无票数，靠耗尽进攻方票数取胜。

> **突破模式没有手动开局命令。** 只能靠候选名单达到 `minplayers` 自动开始。测试时把
> `/aew1 breakthrough map minplayers 2` 调小。

---

## 4. 开局与对局中运维

### 玩家怎么进

```
/aew1                          打开对局浏览器（也可以用 /aew1 ui）
/aew1 join                     加入当前世界的候选名单，自动分配阵营
/aew1 quickjoin <房间键>        直接进指定房间，房间键形如 bf@minecraft:overworld
/aew1 leave                    退出候选名单或对局
/aew1 squad                    查看自己在哪个阵营、哪个小队
/aew1 status                   查看当前世界对局状态
```

小队自动编组，每队最多 4 人，两个阵营各自独立编号。小队长可以下达攻防命令：

```
/aew1 order attack <据点ID>
/aew1 order defend <据点ID>
```

小队完成攻击命令会获得 +5 票数奖励。

### 管理员控制

```
/aew1 joinall                          把当前世界所有玩家拉进候选名单
/aew1 start [票数] [战役名]             手动开局，票数默认 300
/aew1 stop                             结束当前对局
/aew1 pause                            暂停
/aew1 resume                           恢复
/aew1 kick <玩家>                       把玩家踢出对局
/aew1 force <玩家> alpha|bravo          强制把玩家分到指定阵营
/aew1 tickets set alpha <0-100000>      直接设定票数
/aew1 tickets add alpha <1-100000>      加票
/aew1 tickets sub bravo <1-100000>      扣票
```

**平衡性提示**：票数是对局长度的主要旋钮。300 票适合 8–16 人的中小图，人多或据点多时往 400–600 调。不要为了"让某方赢"临时改票数，那会毁掉玩家的胜负感。

### AI 士兵（凑人数 / 测试）

```
/aew1 bot add [数量] [alpha|bravo]     往进行中的对局补 AI，不指定阵营则补人少的一方
/aew1 bot spawn [数量]                 在自己位置裸生成 AI（不入队，用于测试）
/aew1 bot list
/aew1 bot remove <名字>
/aew1 bot clear
/aew1 bot difficulty <档位> [名字]      不给名字则对所有 AI 生效
```

难度档位：`rookie`（新手，30 格外基本打不中）→ `normal`（普通，互有胜负）→ `advanced`（高级，稳定命中）→ `ultimate`（极限）。另有旁支 `realistic`（拟真，反应快但压枪一般）。

数量上限单次 32。AI 需要该方基地已设置才能加入。

### 入口全息投影

在大厅世界给玩家做一个可交互的进入点：

```
/aew1 hologram battlefield          在自己位置创建战场入口
/aew1 hologram all                  创建全部类型入口
/aew1 hologram clear [半径]          清除附近的全息投影，默认半径 6
```

---

## 5. 复用地图布置

### 预设（preset）：同一世界内保存布场快照

保存据点、双方基地、战斗区域。**不含地图名、阵营名和军械库。**

```
/aew1 preset save <名称>
/aew1 preset load <名称>       会先清空当前布置再载入
/aew1 preset list
/aew1 preset delete <名称>
```

适合在同一个世界里反复试不同的据点布局。

### 模板（template）：跨世界搬运地形

把战斗区域内的**方块**保存下来，开局时可以载入到另一个世界。

```
/aew1 template save <名称>      必须先设置战斗区域
/aew1 template list
/aew1 template info <名称>
/aew1 template delete <名称>
```

未指定模板开局时，系统会自动保存一份默认模板，用于对局结束后还原地形。

---

## 6. 全局配置

服务端配置文件位于 `<世界目录>/serverconfig/act0_battlefield-server.toml`，首次启动后生成。常用项：

| 配置项 | 默认 | 说明 |
|---|---|---|
| `minPlayersToStart` | 8 | 未单独设置的世界的自动开局人数 |
| `maxPlayers` | 32 | 未单独设置的世界的对局人数上限 |
| `squadSize` | 4 | 每小队人数（`SquadManager` 硬上限 4） |
| `startCountdownTicks` | 100 | 开局倒计时（5 秒） |
| `redeployDelayTicks` | 100 | 死亡后重新部署的等待（5 秒） |
| `spawnProtectionTicks` | 60 | 部署后无敌时间（3 秒） |
| `downedDurationTicks` | 300 | 倒地后流血至死时间（15 秒） |
| `reviveDurationTicks` | 60 | 扶起耗时（3 秒；手持医疗针减半） |
| `ticketPerDeath` | 1.0 | 每次死亡扣多少票 |
| `captureIntervalTicks` | 10 | 占领进度更新间隔（0.5 秒） |
| `enemyMarkDistance` | 96.0 | 敌人高亮的最大可见距离 |
| `minimapNorthUp` | false | 小地图是否固定北朝上（默认跟随视角旋转） |

改动需要重启服务器或重载配置生效。

---

## 7. 排错

| 提示 / 现象 | 原因 | 解法 |
|---|---|---|
| `Unknown or incomplete command`（执行 `map name` 时） | 少给了阵营名。三个参数必填 | 补全两个阵营名 |
| `Expected whitespace to end one argument` | 中文参数没加引号 | 用 `"中文名"`，或按 Tab 补全 |
| `§c该世界尚未布置完毕（据点/双方基地未设置）` | 没有据点，或有一方基地没设 | `/aew1 point list` 和 `/aew1 map info` 逐项核对 |
| `§c还没有玩家选择阵营` | 候选名单是空的 | 先 `/aew1 join` 或 `/aew1 joinall` |
| `§c该世界已有进行中的大战场对局` | 上一局没结束 | `/aew1 stop` |
| `A player is required to run this command here` | 该命令需要真人执行 | 见下方「已知限制」第 4 条 |
| `§c没有名为 xxx 的地图` | 军械库命令的地图名拼错，或该图还没命名 | `/aew1 arena list` 查已知地图名 |
| `§c主手不是 TaCZ 枪械，读不出枪械 ID` | 手上不是 TaCZ 枪，或 TaCZ 没装 | 换枪；确认 TaCZ 已加载 |
| `§c枪械请用 weapon 子命令上架` | 拿着枪执行了 `item add` | 改用 `weapon <类别> add` |
| `§e注意：原地图键 xxx 下的军械库不会跟着改名` | 改地图名之后，旧军械库成了孤儿 | 按提示重配，或把地图名改回去 |
| **玩家出生两手空空** | 本图没配军械库 | `/aew1 arena <地图名> info` 核对；服务端日志会有告警 |
| 玩家打完一匣就没弹 | 上架时备弹推导退回了 0 | `weapon <类别> add <备弹数>` 手动指定 |
| 老客户端进服后各种表现错乱 | 客户端模组版本与服务端不一致 | 本分支协议版本为 19，客户端必须同版本 |

---

## 8. 已知限制

以下是当前版本确实存在的行为，不是 bug 报告，而是你需要知道的边界：

1. **`map name` 不是「可玩」的门槛。** 开局校验只看「有据点 + 双方基地都设了」。一个从没执行过 `map name` 的世界，只要有据点和基地，就会出现在对局浏览器里（显示为维度 ID）、阵营名回落成默认的「北大西洋公约 / 无邦军团」，并且能正常开打。换句话说"不指定阵营名就建不出地图"这条只在命令层成立。**建图时请把 `map name` 当作必做的第一步**，不要依赖系统拦你。

2. **军械库不是开局门槛。** 同上，没配也能开，玩家空手。上线前务必用 `/aew1 arena <地图名> info` 过一遍。

3. **`/aew1 breakthrough setup` 是个空操作**，只打印「突破模式已就绪」，不做任何检查也不创建任何东西。不要以为执行它就配好了突破模式。

4. **多数命令必须真人执行，控制台 / 命令方块不行。** 可以从控制台执行的只有这几条：

   | 控制台可用 | 必须真人执行 |
   |---|---|
   | `map name` `map factions` `map info` | `map minplayers` `map maxplayers` |
   | `point add` | `point list` `point radius` `point height` `point name` |
   | `arena list` `arena <图> info` | `arena <图> weapon\|item add`（要读主手物品） |
   | `arena <图> weapon\|item list\|remove` | `base set`（要取坐标和朝向） |
   | `template list` `template info` `template delete` | `template save` `preset` 全部 `area` 全部 |
   | | `start` `stop` `status` `join` `leave` `squad` `joinall` `suicide` |

   需要脚本化时用 `execute as <玩家> run /aew1 ...` 借一个在线玩家的身份，例如
   `execute as @p run aew1 start 300`。测试环境里可以先 `/aew1 bot spawn 1` 生成一个 AI，
   再用 `execute as <AI名字> run ...` 当执行者。

5. **突破模式没有手动开局命令**，只能靠 `minplayers` 自动触发。

6. **旧存档的阵营名会回落成默认值。** `0.2.7` 及更早创建的地图没有阵营名字段，读档时整对回落成「北大西洋公约 / 无邦军团」，地图照常可用。想改就跑一次 `/aew1 map factions`。

7. **地图名区分不了大小写但会归一。** `dust2`、`DUST2` 都能解析到 `Dust2`，军械库命令随便打哪种都行。

---

## 9. 命令速查

### 建图

| 命令 | 权限 | 说明 |
|---|---|---|
| `/aew1 map name <名> <阵营1> <阵营2>` | 2 | 命名地图并设定阵营名，三参必填 |
| `/aew1 map factions <阵营1> <阵营2>` | 2 | 只改阵营名 |
| `/aew1 map info` | 2 | 查看本图配置 |
| `/aew1 map minplayers <0-128>` | 2 | 自动开局人数，0 = 跟随全局 |
| `/aew1 map maxplayers <0-128>` | 2 | 人数上限，0 = 跟随全局 |
| `/aew1 base set alpha\|bravo` | 2 | 在当前位置设阵营基地 |
| `/aew1 point add <x> <y> <z>` | 2 | 登记据点 |
| `/aew1 point list` | 2 | 据点列表 |
| `/aew1 point radius\|height <id> <值>` | 2 | 占领区尺寸 |
| `/aew1 point name <id> <名称>` | 2 | 据点显示名 |
| `/aew1 point marker size\|distance\|offset <id> …` | 2 | 据点标记外观 |
| `/aew1 area info\|set\|here\|clear` | 2 | 战斗区域边界 |

### 军械库

| 命令 | 权限 | 说明 |
|---|---|---|
| `/aew1 arena list` | 2 | 所有已知地图 |
| `/aew1 arena <图> info` | 2 | 本图各槽位统计 |
| `/aew1 arena <图> weapon <类别> add [备弹]` | 2 | 手持枪上架 |
| `/aew1 arena <图> weapon <类别> list\|remove <ID>` | 2 | 查看 / 下架 |
| `/aew1 arena <图> item gadget1\|gadget2 add [数量]` | 2 | 手持道具上架 |
| `/aew1 arena <图> item gadget1\|gadget2 list\|remove <ID>` | 2 | 查看 / 下架 |

### 突破模式

| 命令 | 权限 | 说明 |
|---|---|---|
| `/aew1 breakthrough map name <名> <进攻> <防守>` | 2 | 同征服，三参必填 |
| `/aew1 breakthrough base set attacker\|defender` | 2 | 攻防双方基地 |
| `/aew1 breakthrough sector add <id> <名> <据点ID…>` | 2 | 划分推进区域 |
| `/aew1 breakthrough sector list\|remove <id>` | 2 | 查看 / 删除区域 |
| `/aew1 breakthrough map info\|factions\|minplayers\|maxplayers` | 2 | 同征服 |
| `/aew1 breakthrough join\|leave\|status\|quickjoin` | — | 玩家侧 |
| `/aew1 breakthrough order attack\|defend <据点ID>` | — | 小队命令 |
| `/aew1 breakthrough stop` | 2 | 结束对局 |

### 对局运维

| 命令 | 权限 | 说明 |
|---|---|---|
| `/aew1` / `/aew1 ui` | — | 打开对局浏览器 |
| `/aew1 join` / `/aew1 leave` | — | 加入 / 退出 |
| `/aew1 quickjoin <房间键>` | — | 直接进指定房间 |
| `/aew1 squad` / `/aew1 status` | — | 查询 |
| `/aew1 order attack\|defend <据点ID>` | — | 小队命令 |
| `/aew1 suicide` | — | 自杀重生 |
| `/aew1 joinall` | 2 | 拉全世界玩家进名单 |
| `/aew1 start [票数] [战役名]` | 2 | 手动开局 |
| `/aew1 stop` / `pause` / `resume` | 2 | 对局控制 |
| `/aew1 kick <玩家>` | 2 | 踢出对局 |
| `/aew1 force <玩家> alpha\|bravo` | 2 | 强制分配阵营 |
| `/aew1 tickets set\|add\|sub alpha\|bravo <数>` | 2 | 调票数 |
| `/aew1 bot add\|spawn\|list\|remove\|clear\|difficulty …` | 2 | AI 士兵 |
| `/aew1 hologram battlefield\|all\|clear [半径]` | 2 | 入口全息投影 |
| `/aew1 preset save\|load\|list\|delete <名>` | 2 | 布场预设 |
| `/aew1 template save\|list\|info\|delete <名>` | 2 | 地形模板 |
