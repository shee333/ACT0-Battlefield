# ACT0-Battlefield 建图手册（aew1 分支）

面向服主与管理员。

-载具、场景破坏与复原 在下个版本会添加
* 适用版本：Minecraft 1.20.1 / Forge 47.4.10 及以上 / 本模组 `0.2.8`（aew1 分支）
* 所有建图命令都在 `/aew1` 下，需要 **OP 权限等级 2**
* 枪械依赖 **TaCZ**（Timeless and Classics Zero）

---

## 1. 最短路径（非必要不用参考）

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

最后确认：

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

### 2.1 命名地图与阵营

```
/aew1 map name <地图名> <阵营1> <阵营2>
```

三个参数**全部必填**，阵营1 是蓝方，阵营2 是红方。

每张图可以有自己的阵营名称


阵营名的限制：

| 规则 | 说明 |
|---|---|
| 不能为空 | |
| 最多 16 个字符 | TAB 面板和对局浏览器按这个宽度排版，更长会被截断成 `xxx..` |
| 不能含 `§` 或控制字符 | `§` 会覆盖阵营配色、`§k` 会把整行搅乱 |
| 两方不能同名 | 大小写和首尾空格不算区别，`Alpha` 和 `alpha` 视为同名 |

阵营改名（不动地图名）：

```
/aew1 map factions <阵营1> <阵营2>
```

> **为什么要先命名再配军械库：** 军械库是按**地图名**索引的。如果你先配枪再命名，军械库会绑在维度 ID
> （如 `minecraft:overworld`）上，改名之后本图按新名字查询、查到空的，玩家就空手上场了。

### 2.2 双方基地

站到出生点，面朝你希望玩家出生时看的方向：

```
/aew1 base set alpha
/aew1 base set bravo
```

输入命令时会取**执行者的坐标和视角朝向**

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



### 2.4 战斗区域（可选）

不设置时，系统按「双方基地 + 所有据点」的包围盒外扩 16 格自动推导。玩家跑出区域会被警告并计时拉回。

```
/aew1 area info                                          查看当前生效区域
/aew1 area here <8-4096>                                 以自己为中心按半径设定
/aew1 area set <minX> <minY> <minZ> <maxX> <maxY> <maxZ>  精确设定
/aew1 area clear                                         清除，恢复自动推导
```

只有在自动推导的范围不合理时（比如地图是狭长走廊、或有不希望玩家进入的区域）才需要手动设。

### 2.5 军械库

军械库按地图名索引，与地图布置分开存储

**上架枪械**（手持要上架的枪执行）：

```
/aew1 arena <地图名> weapon <类别> add [备弹数]
```

| 类别 | 落到哪个槽 |
|---|---|
| `rifle` `smg` `machinegun` `sniper` `shotgun` `launcher` | 主武器（快捷栏 1） |
| `pistol` | 副武器（快捷栏 2） |
| `melee` | 近战（快捷栏 3） |

备弹数不填时按弹匣容量自动推导（约 3 个备用弹匣）。若模组读不出弹匣容量会退回 0。手动范围 0–9999。

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




原版物品同样可以放。

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



### 2.6 人数规则

```
/aew1 map minplayers <0-128>     候选名单达到这个人数自动开局
/aew1 map maxplayers <0-128>     对局人数上限
```

设置时系统会校验自洽性

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

> **突破模式暂时没有手动开局命令。** 只能靠候选名单达到 `minplayers` 自动开始。测试时把
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

