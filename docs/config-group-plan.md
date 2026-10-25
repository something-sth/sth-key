# 配置组合（Config Group）设计方案

> 状态:**待确认**（先出方案，确认后再写代码）
>
> 用户的原话见每节引用。⚠️ 标出的是**必须拍板**或**容易踩坑**的地方。

---

## 1. 术语

| 词 | 含义 |
|---|---|
| **配置** | 现有的 `KeyStrokesConfig`（一张悬浮窗的完整外观与键位） |
| **配置组合** | 一个新的容器概念，持有若干个配置的 id，可整体开关 |
| **启用集合** | 现有的 `AppPrefs.overlayEnabledIdsJson` —— **当前显示哪些悬浮窗**的依据 |

⚠️ 关键区分:配置组合**不是一种样式**，也不产生新的悬浮窗。它只是"一组配置的开关"。

---

## 2. 数据模型

### 2.1 新增 `ConfigGroup`

```kotlin
data class ConfigGroup(
    val id: String,                 // UUID
    val name: String,
    val description: String = "",
    val memberIds: List<String> = emptyList(),   // ⚠️ 有序：决定主页展开后的顺序
    val createdAt: Long,
)
```

### 2.2 存储

- 新增 `GroupStore`（与 `ConfigStore` 同构）:单例 + `load()/persist()`，
  落到 `AppPrefs.configGroupsJson`（JSON 数组）。
  ⚠️ `ConfigStore` 就是存 `AppPrefs` 的，跟它保持一致，不要另开文件。
- 不新增版本号字段:JSON 里缺字段时用默认值，与现有解析风格一致。

### 2.3 ⚠️ 归属规则（**需要拍板**）

用户描述的行为是「加入组合后，配置页里那几个配置就消失」。
→ 我建议规则是 **一个配置最多属于一个组合**:

| 场景 | 行为 |
|---|---|
| 配置页列表 | 只显示**未被任何组合引用**的配置 |
| 从组合里移除 | 配置回到配置页列表（`memberIds` 里删掉即可，原始配置对象不动） |
| 在新组合里选中"已在其它组合"的配置 | ⚠️ **弹确认框**:「「配置X」已在组合「组合Y」里，加入后会从那里移走」→ 确认则从旧组合 `memberIds` 移除 |

⚠️ 为什么不允许多重归属:同一个配置出现在两个组合里，
"关掉组合 A 会不会把组合 B 的悬浮窗也关掉"就没有干净答案了 ——
用户会撞上"我关了 A，B 里的那个也没了"这种无法解释的现象。

---

## 3. 组合的开关状态:**派生，不存储**

⭐ 这一条我建议**完全按用户的想法做，而且不做任何额外状态**:

```kotlin
fun isGroupOn(group: ConfigGroup, enabledIds: Set<String>): Boolean =
    group.memberIds.isNotEmpty() && group.memberIds.all { it in enabledIds }
```

| 用户操作 | 实现 |
|---|---|
| 打开组合开关 | 把 `memberIds` 全部加进启用集合 |
| 关闭组合开关 | 把 `memberIds` 全部移出启用集合 |
| 单独开/关组内某个配置 | 走现有的 `setEnabled(configId, …)`，**组合开关自动跟着变** |

⭐ 这正好实现了用户提的那条规则:
> 关闭状态下单独开启，不全开就不刷新；全开时才变成开启

因为开关是**算出来的**，不存在"两个状态不一致"的可能，也不需要"刷新"逻辑。
⚠️ 空组合的开关应该**禁用**（灰掉），否则点了开关什么都不会发生。

---

## 4. 配置页改动

### 4.1 卡片

```
┌────────────────────────┐
│ 名称            [组合] │ ← ⚠️ 见 4.2，不建议在样式栏写「配置组合」
│ 🎛 3 个配置            │
│                    ⋮   │
└────────────────────────┘
```

### 4.2 ⚠️ 用户担心的那个问题（我的建议）

用户问:
> 卡片显示与其他配置一样，不过配置样式那一栏显示的是「配置组合」
> （我也不清楚这样做好不好，这会不会让用户以为"配置组合"也是一种配置样式呢？）

⭐ **会**。而且更糟:配置卡片上那个位置是"样式"信息，
图标（键盘/手柄/触屏）也和样式绑定。把文字换成「配置组合」但图标还是键盘，会很怪。

**建议改成**:
- 在卡片**右上角放一个"组合"徽章**（沿用主页 `StatusTag` 的样式，小圆角标签）;
- 副标题的位置显示 **「N 个配置」**（替代样式名 + 图标）;
- 这样"是不是组合"靠**徽章**区分，与"样式"这个词彻底分开。

### 4.3 新建按钮的位置（用户让我提方案）

用户的原话:
> 我的理想方案是加在新建配置左边，或上面……但是可能要做文字提示

⚠️ 两个 `FloatingActionButton` 叠在一起很难分清。**建议**:

在列表顶部放一行两个按钮（都带文字与图标，不会认错）:

```
[ + 新建配置 ]   [ ⧉ 新建组合 ]
```

- 文字本身就是提示，不需要额外的说明块;
- 两个按钮都可见，不需要"长按展开"之类的隐藏交互;
- ⚠️ 代价:占一行高度，且与现有 FAB 的交互习惯不同（要么去掉 FAB，要么保留 FAB + 顶部一个按钮 —— 我倾向**去掉 FAB**，统一成顶部一行）。

### 4.4 删除 → 解散

- 组合卡片的菜单里把「删除」换成 **「解散组合」**（红色，带确认框:「解散后里面的 N 个配置会回到配置列表，配置本身不会被删除」）。
- ⭐ 这个设计确实顺带解决了 `default` 的特殊处理:解散只动 `memberIds`，不碰配置。

### 4.5 空状态

⚠️ 如果一个配置都没了（全被装进组合），配置页要显示空状态提示
（"配置都在组合里，点上面新建一个，或去组合里解散"），不能是白屏。

---

## 5. 新建组合对话框

结构复用「新建配置」对话框（`CreateConfigDialog`），把**样式选择区**换成**配置多选区**:

| 项 | 内容 |
|---|---|
| 名称 | `OutlinedTextField`（必填，与配置同一套校验） |
| 描述 | `OutlinedTextField`（可选） |
| 配置列表 | ⚠️ **一行两个卡片**，`LazyVerticalGrid`（或 `LazyRow` + 两列），**限高可滚动**（沿用 `STYLE_LIST_MAX_HEIGHT` 的做法），多选（勾选态） |
| 允许不选 | ✅ 允许（建一个空组合，之后再往里面加） |

⚠️ 一行两个卡片的实现:现有配置页是「算宽度 + `Row` + `chunked`」的手写网格（为了对齐缝隙）。
对话框里建议直接用 `LazyVerticalGrid(columns = Fixed(2))`，更简单，且天然可滚动。

---

## 6. 组合详情页（二级页面）

路由:`group/{groupId}`

```
[←] 组合名称（可就地编辑）
    描述（可就地编辑）
────────────────────────
  成员配置卡片 × N   ← 与配置页同一个卡片组件
────────────────────────
  [ 配置管理 ]        ← 弹窗：列出全部配置，多选，可不选
```

| 部分 | 说明 |
|---|---|
| 顶部 | 名称/描述可编辑（与配置编辑页一致的"点开改"模式） |
| 成员卡片 | **复用配置页的卡片组件**（同一套样式与菜单） |
| 配置管理 | 与「新建组合」里那个多选弹窗**同一个组件**，只是预选为当前成员 |
| 空状态 | "还没有配置，点下面的「配置管理」加进来" |

⚠️ 从详情页移除成员 = 该配置回到配置页列表（即时生效）。

---

## 7. 主页改动

### 7.1 组合行

```
┌──────────────────────────────────────────┐
│ ▸  组合名称              [组合]   ( ●)  │ ← ▸ = 展开箭头，右侧 = 开关
│    3 个配置                              │
└──────────────────────────────────────────┘
```

- 左侧按钮:**只有配置组合**用向下的小三角（展开/收起），
  ⚠️ 点击**不要求**先打开开关（用户明确要求"不做限制"）。
- **其它配置行完全不动**（左侧仍是 `Tune` 配置按钮）。
- 展开后的成员行**也保留各自的配置按钮**（可触摸 / 可移出屏幕等）。

### 7.1.1 ⚠️ 勘误:主页不需要"进详情页"的入口

我之前担心"三角替换掉 `Tune` 按钮之后，组合详情页就没入口了"——**这个担心是多余的**:

用户原话是「点击**配置组合卡片**会进入一个二级页面」，
那个卡片在**配置页**，不在主页。

| 页面 | 组合行/卡片 | 左侧按钮 | 点击卡片/行的作用 |
|---|---|---|---|
| 配置页 | 组合卡片 | （卡片的菜单里是「解散」） | **进组合详情页** |
| 主页 | 组合行 | 小三角（展开） | 三角 = 展开；行本身**不做特别的事** |

⭐ 顺带说明为什么主页的组合行**不需要**配置按钮:
`Tune` 那个按钮打开的是"这一个悬浮窗的可触摸 / 可移出屏幕"设置 ——
而**组合本身没有悬浮窗**，那些设置是每个成员配置各自的。
组合行没有这类设置可调，所以放三角正合适。


### 7.2 展开后的成员

每个成员一行，**与现有配置行的控件一致**（开关 + 可触摸 / 可移出屏幕等），缩进一级以示从属:

```
│   ▾ 组合名称                      ( ●)  │
│      ┌ 配置A                      ( ○)  │
│      └ 配置B                      ( ●)  │
```

⚠️ 展开状态**不持久化**（重组后收起即可）—— 存了反而会让主页每次进来都是展开的，很乱。

### 7.3 单独开关与组合开关的关系

见 §3:组合开关是派生的，所以不需要任何"刷新"逻辑。

---

## 8. 导入 / 导出 `.sthgroup`

### 8.1 扩展名

用户的纠结:
> 我一开始想的是 `.sthgroup`，但又想会不会太长，换成 `.sthg`？好像太简短了

⭐ 建议 **`.sthgroup`**:
- `.sthg` 太短，容易被别的软件当成未知文件;
- 与 `.sthkey` 区分度足够，且能从字面看出是"sth 的 group"。

### 8.2 包结构

```
my-group.sthgroup           (zip)
├── manifest.json           ← 组合的元信息
├── 配置A.sthkey            ← 直接是完整的配置包（含字体/Live2D）
├── 配置B.sthkey
└── ...
```

⭐ 直接把每个配置**导出成完整 `.sthkey`**（而不是解包后平铺），好处:

| 好处 | 说明 |
|---|---|
| 复用全部现有逻辑 | 字体去重、Live2D 去重、`manifest` 校验、重名处理**全都不用重写** |
| 单个也能用 | 用户从 `.sthgroup` 里挑一个 `.sthkey` 出来照样能导入 |
| 不会产生"半份" | 配置包的原子性由 `.sthkey` 自己保证 |

⚠️ 嵌套 zip（zip 里套 zip）是合法的，导入时按 entry 读到临时文件即可。

### 8.3 `manifest.json` 字段

```json
{
  "format": "sthkey-group",
  "formatVersion": 1,
  "appVersion": "2.7.1",
  "exportedAt": 1761...,
  "name": "我的组合",
  "description": "…",
  "configs": [
    { "file": "配置A.sthkey", "name": "配置A", "description": "…", "styleId": "keyboard" }
  ]
}
```

- `format` 用来拒绝"随便一个 zip";`formatVersion` 留给以后。
- ⭐ `configs[]` 不只是好看:导入时要**按它校验**（file 存在、数量对得上），
  并且**顺序**以它为准（否则组合内顺序会变成 zip entry 的随机顺序）。
- ⚠️ 成员顺序（home 里的展开顺序）就靠这个数组。

### 8.4 导入流程

```
选文件 → 读扩展名/MIME
  ├─ .sthgroup → 读 manifest（校验 format）
  │     ├─ 逐个 entry：解到临时 .sthkey → 走现有 import → 得到新 configId
  │     └─ 建组合，memberIds = 新 configId 列表（按 manifest 顺序）
  └─ .sthkey   → 走现有单配置导入
```

⚠️⚠️ **最容易踩的坑**:配置的 id 是 UUID，导入后**一定会变**。
所以组合的 `memberIds` **不能**从包里抄，必须用"导入后拿到的新 id"重建 —— 否则组合是空的。

⚠️ 重名:现有 `ConfigStore.nextAvailableName()` 已经会加后缀，直接复用。
⚠️ 失败处理:某个 `.sthkey` 解不开时 —— 建议**跳过并在报告里说明**，
而不是整组失败（否则一个坏文件毁掉整包）。

### 8.5 导出流程

```
组合 → 建 zip
  ├─ manifest.json
  └─ 对每个 memberId：复用现有单配置导出，把 .sthkey 写进这个 zip 的 entry
```

⚠️ 现有导出是"写到 Uri"（SAF）。需要把落点从 `Uri` 改成"任意 `OutputStream`"才能嵌进外层 zip。
→ 建议给 `ConfigPackageManager.writePackage` 加一个 `OutputStream` 版本（它现在已经有一个 `openOutput(target: Uri)` 可以抽出这一层）。

---

## 9. 删除桌面快捷方式

用户的判断:
> 现在用的都是无障碍悬浮窗……用快捷方式启动时还需要手动授权无障碍，
> 或者等 shizuku/root 用 shell 启动完无障碍，才能显示出悬浮窗，
> 我不知道 shell 授权无障碍的逻辑怎么样，我只知道速度很慢，这个功能基本废了

⭐ **同意删除**。理由（技术层面）:

| 原因 | 说明 |
|---|---|
| 无障碍服务**不能**被 shell 可靠地即时打开 | `settings put secure enabled_accessibility_services` 改完之后，系统要**重启服务/等它重新绑定**，无法保证"启动后立刻能画悬浮窗" |
| 所以快捷方式必然出现"点了没反应，等几秒才出来" | 这不是能靠优化修好的，是机制决定的 |
| 维护成本 | 6 个文件 1200 行 + manifest 的 activity/shortcut 元数据 |

要删的东西:

| 文件 | 行数 |
|---|---|
| `data/shortcut/ShortcutIcons.kt` | 300 |
| `data/shortcut/ShortcutPublisher.kt` | 161 |
| `data/shortcut/ShortcutSettings.kt` | 120 |
| `shortcut/ShortcutActivity.kt` | 126 |
| `ui/feature/home/ShortcutIconPicker.kt` | 265 |
| `ui/feature/home/ShortcutSetupDialog.kt` | 229 |

外加:`AppPrefs` 里的快捷方式偏好、`HomeScreen` 顶部那个「桌面快捷方式」按钮、
manifest 里的 activity / `<meta-data>` / `res/xml` 快捷方式定义、`strings.xml` 相关条目、
`ShortcutSettingsTest`。

⚠️ **副作用**:已经建过桌面快捷方式的用户，图标会变成"应用未安装/打不开"。
这是删功能的必然结果，公告里说明一句即可。

⭐ 顺带:主页顶部那个按钮去掉之后，右上角就空了 —— 可以把「设置」之类的入口放那儿，或者干脆留白。

---

## 10. 已确认的决策

| # | 事项 | 结论 |
|---|---|---|
| 1 | 一个配置能否属于多个组合 | ✅ **不能**。选择时弹确认，确认后从旧组合移走 |
| 2 | 组合详情页入口 | ✅ **在配置页的组合卡片上**（点卡片进二级页面）。主页**不需要**入口 —— 见 §7.1 的勘误 |
| 3 | 「新建组合」按钮位置 | ✅ **方案 A:FAB 展开**（点 `+` 向上冒出「新建配置」「新建组合」两个小按钮） |
| 4 | 空组合的开关 | ✅ **禁用（灰掉）** |
| 5 | `default` 能否入组合 | ✅ **能**。⚠️ 兜底逻辑见 §11 的勘误（**不影响显示**，只影响 CPS 计数） |
| 6 | `.sthgroup` | ✅ 可以，manifest 带 `configs[]` |
| 7 | 桌面快捷方式 | ✅ 删除 |
| 8 | 主页组合行 | ✅ 左侧**只有**三角（展开/收起）；**其它配置行保持原样**；展开后的成员行**保留各自的配置按钮** |

### 10.1 「新建组合」按钮:已定 A（FAB 展开）

用户否掉了"顶部一行两个按钮"（太挤、不好看）。备选:

| 方案 | 样子 | 优点 | 缺点 |
|---|---|---|---|
| **A. FAB 展开** | 点右下角 `+` → 向上弹出两个小按钮（新建配置 / 新建组合） | 不占顶部；位置不变；Material 标准做法 | 多一步点击；要做得清楚才不会被当成"直接新建" |
| **B. 列表末尾虚线卡片** | 网格最后多一张 `+ 新建组合` 的虚线卡 | 完全不碰顶部；视觉上是列表的自然延续 | 配置多了要滚到底才看到 |

⚠️ 不推荐"FAB 长按弹菜单":长按不可发现，用户永远找不到。

---

## 11. 风险与必须处理的坑

| # | 坑 | 处理 |
|---|---|---|
| 1 | **配置 id 在导入后改变** | 组合的 `memberIds` 必须用导入后的新 id 重建（§8.4） |
| 2 | **删除配置时组合里的引用会悬空** | `ConfigStore.delete()` 之后必须同步从所有 `memberIds` 里移除（建议在 ViewModel 层做一次"引用清理"） |
| 3 | ~~`CaptureSession` 兜底会让组合里的配置被当默认显示~~ | ⚠️ **勘误见 §11.1**:这个兜底**不影响显示** |
| 4 | 主页组合行的展开状态 | 不持久化，重组即收起 |
| 5 | 导出组合时的落点 | 需要给现有导出加 `OutputStream` 版本（§8.5） |
| 6 | 组合重名 | 建议允许重名（与配置一致），靠 id 区分 |
| 7 | `builtIn` 配置 | 内置配置「不可删除」，但**可以被放入组合**；解散不影响它 |

### 11.1 ⚠️ 勘误:那个 `ifEmpty` 兜底到底是什么

我上一轮说"兜底可能让组合里的配置被当成默认显示"——**那是错的**，我把两处逻辑搞混了。

实际情况（已核对源码）:

| 位置 | 逻辑 | 影响 |
|---|---|---|
| `OverlayService`（决定显示哪些窗口） | 只按 `OverlayLayouts.enabledIds` 来，**没有任何兜底** → 一个都没开就**没有窗口** | 与配置组合无关，行为正确 |
| `CaptureSession.recordCpsClick`（CPS 计数归属） | `store.all().filter { it.id in enabledIds }.ifEmpty { listOf(store.first()) }` | ⚠️ 一个都没启用时，CPS 会记到**列表第一份配置**上（原本是为了"调试页手点也有数"） |

**对配置组合的实际影响**:如果列表第一份配置被放进组合、且当前什么都没启用，
按键盘时 CPS 会记到那份**组合里的配置**上 —— 只是数字记错了地方，
**不会让它的悬浮窗冒出来**。影响很小，可以不改；真要改就是把兜底换成
"第一份**未被组合引用**的配置"。


---

## 12. 任务拆分（实现顺序）

1. **数据层**:`ConfigGroup` + `GroupStore` + `AppPrefs.configGroupsJson` + 单元测试（增删改、成员移除、引用清理）
2. **派生的开关逻辑**:`isGroupOn()` / `setGroupEnabled()` + 单元测试（覆盖用户那三条规则）
3. **配置页**:卡片徽章 / 顶部两个按钮 / 解散组合 / 空状态
4. **新建组合对话框**（多选 + 可滚动两列）
5. **组合详情页**（名称描述编辑 + 成员卡片 + 配置管理弹窗）
6. **主页**:组合行 + 展开 + 单独开关 + 点行进详情
7. **导入导出**:`.sthgroup` 编码/解码 + 复用单配置导入 + 报告
8. **删桌面快捷方式**（独立提交，方便回滚）
9. 测试 + 文档 + 公告

⚠️ 建议 **1→2→3** 先做一轮（能建、能开关、能解散），验证模型没问题，
再往上叠详情页和导入导出 —— 免得模型返工时代码全改。

---

## 13. 还没想清楚 / 需要你说的

1. **组合里的配置，还能在配置页看到吗？** 我按"看不到"设计的（用户原话）。但如果用户想"看一眼但不操作"，是不是需要一个「显示全部配置」的开关？
2. **组合能否嵌套组合？** 我按**不能**设计（模型里 `memberIds` 只存配置 id）。
3. **主页的组合行要不要显示里面的开关汇总**（比如"2/3 已开启"）？
4. **解散组合要不要二次确认？** 我按"要"设计的（破坏性操作）。
5. **删除配置的入口要不要在组合详情页里也提供？** 我按"不提供"（组合里只能移除，不能删配置）设计的。

---

## 14. 实现进度（持续更新，避免遗忘）

| # | 任务 | 状态 | 落在哪 |
|---|---|---|---|
| 1 | `ConfigGroup` 数据类 | ✅ | `domain/config/ConfigGroup.kt` |
| 2 | `GroupCodec` 编解码 | ✅ | `data/config/GroupCodec.kt` |
| 3 | `GroupStore`（增删改 / 移动语义 / `pruneMissing`） | ✅ | `data/config/GroupStore.kt` |
| 4 | `AppPrefs.configGroupsJson` | ✅ | `core/prefs/AppPrefs.kt` |
| 5 | `GroupSwitch`（派生的开关语义） | ✅ | `domain/config/GroupSwitch.kt` |
| 6 | 单测：`GroupSwitchTest`（7 条）、`GroupPackageCodecTest`（6 条） | ✅ | `test/.../GroupSwitchTest.kt`、`GroupPackageCodecTest.kt` |
| 7 | ViewModel：`groups` / `groupsVersion` / `createGroup` / `addGroupMembers` / `removeGroupMembers` / `dissolveGroup` / `setGroupOverlayEnabled` / `isGroupOn` | ✅ | `ui/MainViewModel.kt` |
| 8 | `deleteConfig` 里清理组合悬空引用 | ✅ | `ui/MainViewModel.kt` |
| 9 | 配置页：`GroupCard` 徽章 / FAB 展开 / 解散 / 空状态 | ✅ | `ui/feature/config/ConfigGroupUi.kt`、`ConfigListScreen.kt` |
| 10 | 新建组合对话框 + 配置管理对话框（多选 + 可滚动两列） | ✅ | `ui/feature/config/GroupDialog.kt` |
| 11 | 组合详情页（名称/描述编辑 + 成员卡片 + 配置管理） | ✅ | `ui/feature/config/ConfigGroupDetailScreen.kt`，路由 `group_detail/{groupId}` |
| 12 | 主页：组合行（三角展开）+ 成员行缩进 + 组合开关 | ✅ | `ui/feature/home/GroupOverlayRow.kt`、`HomeScreen.kt` |
| 13 | `.sthgroup` 导入导出 | ✅ | `GroupPackageCodec.kt`、`GroupPackageManager.kt` |
| 14 | 删桌面快捷方式 | ✅ | 6 个文件 + manifest + strings + themes + AppPrefs |

### 14.1 实现时定下来的细节

| 细节 | 决定 |
|---|---|
| 组合开关打开时 | 走 `ensureAccessibilityForOverlay()` → `OverlayController.enableConfigs(全部成员)`，与单配置开关**同一条路** |
| 组合开关关闭时 | 直接 `OverlayLayouts.setEnabledIds`，集合空了就 `OverlayService.stop`，否则 `refresh` |
| 解散组合 | 只删组合记录；成员悬浮窗**保持原开关状态**（解散是分组操作，不是开关操作） |
| 移除成员 | 同上，不强制关闭它的悬浮窗 |
| 空组合 | `isOn` 恒为 false；UI 上开关要禁用 |
---

## 15. 第二轮细节修正（按用户反馈）

| # | 问题 | 处理 |
|---|---|---|
| 1 | 组合名称空白时**红框**很突兀；新建配置没有红框，**不一致** | 两边**都去掉**红框。名称留空改为**合法用法**:`ConfigStore.create` / `GroupStore.create` 兜底成「新建配置」/「新建配置组合」，重名走 `nextAvailableName` 加 " (2)" 后缀 |
| 2 | 组合详情页里配置卡片的**导出/复制/删除全没了** | 恢复。⚠️ 复制出来的配置**直接加进本组合**（用户在这个组合里点的复制，期望它留在这里） |
| 3 | 组合详情页要不要**新建配置**按钮 | 加了，与「配置管理」并列。⚠️ **只加新建配置**，不加新建组合（会嵌套，语义和界面都乱）。建完直接放进本组合 |
| 4 | 管理配置对话框里「在「X」里，勾选会搬过来」**太长被省略** | 改成 **「已在其它组合」**（7 字，高 DPI 也不会截断） |

### 15.1 命名规则的唯一来源

⚠️ 默认名与去重**只在 store 层做一次**，界面不掺和:

| 位置 | 做什么 |
|---|---|
| `ConfigStore.create` | `nextAvailableName(name.trim().ifEmpty { "新建配置" })` |
| `GroupStore.create` | `nextAvailableName(name.trim().ifEmpty { "新建配置组合" })` |
| `GroupStore.nextAvailableName` | 与 `ConfigStore.nextAvailableName` **同一套规则**（" (2)" 后缀） |

⚠️ 为什么不放在 UI 层:导入组合时也要用同一套命名，两处各写一份必然分叉。
---

## 16. 实现记录：`.sthgroup` 与快捷方式

### 16.1 `.sthgroup` 的关键实现点

| 点 | 做法 |
|---|---|
| 识别类型 | ⚠️ 按**包里 manifest 的内容**判断（`format` 字段），不看后缀 —— 后缀会被系统/用户改名 |
| 导出 | 每个配置先用现有 `ConfigPackageManager.writeTo` 写成**完整 `.sthkey`**（临时文件），再塞进外层 zip。字体/Live2D/字段全部复用既有逻辑 |
| 导入 | 按 `manifest.configs` 的**顺序**逐条抽出 `.sthkey` → 交给 `ConfigPackageManager.import` |
| ⚠️ id 重建 | 组合的 `memberIds` 用**导入后拿到的新 id**（`outcome.config.id`），绝不抄包里的 |
| 单条失败 | **不中断整包**，失败项收进 `ImportOutcome.failures` 并提示 |
| 重名 | `GroupStore.create` 内部走 `nextAvailableName`，与新建组合同一套 |

### 16.2 ⚠️ 单元测试的 `org.json` 坑

Android 单测里 `org.json` 是 **android.jar 的空壳**，任何调用都抛
`"Method put in org.json.JSONObject not mocked"`。

→ 在 `app/build.gradle.kts` 加了 `testImplementation("org.json:json:20240303")`。
⚠️ 只影响单测类路径，**不进 APK**。

### 16.3 桌面快捷方式删除清单

| 删除项 | 说明 |
|---|---|
| `data/shortcut/`（3 个文件 581 行） | `ShortcutIcons` / `ShortcutPublisher` / `ShortcutSettings` |
| `shortcut/ShortcutActivity.kt`（126 行） | 快捷方式入口 Activity |
| `ui/feature/home/ShortcutIconPicker.kt`（265 行） | 图标选择 |
| `ui/feature/home/ShortcutSetupDialog.kt`（229 行） | 创建对话框 |
| manifest | `.ShortcutActivity` 节点 + `android.app.shortcuts` meta-data |
| `res/xml/shortcuts.xml` | 整份删除 |
| `res/values/strings.xml` | 4 个 shortcut 字样 |
| `res/values/themes.xml` | `Theme.SthKey.Invisible`（NoDisplay） |
| `AppPrefs` | `shortcutConfigIds` + `KEY_SHORTCUT_CONFIG_IDS` |
| `HomeScreen` | 顶部按钮、图标选择、创建对话框（约 150 行） |
| `ShortcutSettingsTest` | 5 条测试 |

⚠️ **副作用**:已建过桌面快捷方式的用户，图标会变成"打不开"。
这是删功能的必然结果，公告里需说明一句（⚠️ 但公告只在用户说要发布时才动）。

### 16.4 为什么这个功能救不回来（技术原因）

无障碍服务**不能**被 shell 可靠地即时打开:
`settings put secure enabled_accessibility_services` 改完之后，系统要**重启服务并重新绑定**，
无法保证"快捷方式启动后立刻能画悬浮窗"。
所以必然出现"点了没反应，等几秒才出来" —— 这是机制决定的，不是优化能解决的。
---

## 17. 第三轮修正（用户反馈:导出写了自己的通道 / 导入不收 .sthgroup / 卡片操作不统一）

### 17.1 ⚠️ 我的错误:另开了一条导出通道

第一版我用 `CreateDocument("application/octet-stream")` **自己写了一个 launcher**，
后果有两个，全是硬伤:

| 现象 | 原因 |
|---|---|
| 导出成 `.bin` | ⚠️ `CreateDocument` 的 MIME 决定后缀:给 `application/octet-stream`，Downloads 就按 MIME 补 `.bin`。现有流程用的是 `"*/*"` —— 代码里早就写着原因（"部分文件管理器会据此把 .sthkey 强行改成 .zip / .bin"），我没照抄 |
| 不弹导出方式对话框 | 自己那条路完全不经过 `ExportMethodDialog` / `rememberExportFlow`，所以"手动 / 下载 / 分享"三选一、默认方式、存储权限全都没有 |

**修法:把配置和组合合并到同一条导出通道。**

```
ExportMethodDialog（同一个）
        ↓
rememberExportFlow
        ↓
   PendingExport(displayName, fileName, write)
        ↓
   ┌────────────┬──────────────┬──────────┐
  MANUAL        DOWNLOAD       SHARE
 (SAF */*)   (MediaStore/旧)  (cache+FileProvider)
        ↓
   write(uri)  ← 唯一的分叉点
   ├─ 配置: ConfigPackageManager.export(…)
   └─ 组合: GroupPackageManager.export(…)
```

- `ConfigExporters` 里所有 `config: KeyStrokesConfig` 参数改成 `write: PackageWriter`
  （`typealias PackageWriter = (Uri) -> Boolean`），三种落盘方式完全复用。
- `ExportFlow` 新增 `startGroup(group, configs, method, asDefault)`，与 `start` 共用
  `startInternal` 一个分派点。
- 配置页：`startExport(config)` 与 `exportGroup(group)` 都先看 `prefs.exportMethod`，
  `ASK` 就弹**同一个** `ExportMethodDialog`。
- ⚠️ 文件名一律**带扩展名**（`PendingExport.fileName`），SAF 用 `"*/*"` 才不会补后缀。

### 17.2 ⚠️ 导入不收 `.sthgroup` 的两处

| 入口 | 修法 |
|---|---|
| 配置页「导入配置」 | 已经先嗅探类型（`GroupPackageManager.detectKind`）—— MIME 本来就是 `*/*`，不受后缀影响 |
| **"打开方式 / 分享"进来的文件** | `IncomingConfigDialog` 之前**只**调 `ConfigPackageManager.import`，所以组合包报"manifest 格式标识不匹配"。现在也先嗅探，是组合就走 `GroupPackageManager.import`，并新增 `Phase.DoneGroup` 显示"导入了几个 + 哪几个失败" |

⚠️ 类型判断**一律看包里的 manifest**，不看后缀 —— 后缀会被系统/用户改名，这正是这次踩的坑。

### 17.3 组合卡片操作区统一

| 设置页开关 | 配置卡片 | 组合卡片 |
|---|---|---|
| 操作收进更多菜单 = 关 | 三个独立图标 | **两个独立图标**:导出 / 解散 |
| 操作收进更多菜单 = 开 | 一个「更多」菜单 | 一个「更多」菜单 |
| 显示配置描述 = 关 | 描述行不占位置 | 同 |

⚠️ **组合卡片故意没有「复制」**:
复制一个组合必须连里面的配置一起复制，否则两个组合会**共享同一批配置**，
而"一个配置只属于一个组合"是这套模型的根基。用户也说"你看着办"，这就是我的判断。

### 17.4 教训（写给自己）

⚠️ 这个项目里**导出/导入/命名/去重都只有一条路**。
加新东西时先去 `ConfigListScreen` 看现有流程怎么走，不要在旁边另开一条 ——
另开的那条一定会在**扩展名、对话框、权限、默认方式**这些地方漏掉某一样。
---

## 18. ⚠️⚠️ 事故记录:我把「无障碍服务声明」删掉了（已修复）

### 18.1 怎么发生的

删桌面快捷方式时，我要删掉 manifest 里的两段（`android.app.shortcuts` 的 meta-data
与 `.ShortcutActivity` 节点）。用脚本按行删除时**删过头**，文件被截断（`<activity` 后面没了）。

⚠️ 然后我做了**最糟的一步**:`git checkout -- app/src/main/AndroidManifest.xml`。
那个文件里有**大量未提交的改动**（这个项目的开发还没提交过新版本），
`git checkout` 把工作区**整体回退到上一次提交（v2.6.1 时代）**，于是丢掉了:

| 丢失项 | 后果 |
|---|---|
| `<service …SthKeyAccessibilityService>` | ⚠️ **无障碍模式整个消失**（系统「无障碍」列表里没有本应用） |
| `<service …TouchRegionEditorService>` | 触屏区域编辑器拉不起来 |
| `WRITE_EXTERNAL_STORAGE maxSdkVersion=28` | Android 9 导出到下载目录会失败 |

⚠️ 更糟的是它**不报错**:manifest 少一个 service 编译照样通过，
只有装到手机上才会发现"无障碍没了"。我那几轮只验证了"编译通过"，没验证组件还在。

### 18.2 怎么修的

git 里从来没有过那份声明（它只存在于未提交的工作区），所以**没有历史可恢复**。
只能从**代码反推**重建:

| 依据 | 推出的声明 |
|---|---|
| `SthKeyAccessibilityService : AccessibilityService` | `<service>` + `exported="true"` + `BIND_ACCESSIBILITY_SERVICE` + `AccessibilityService` intent-filter + `meta-data` 指向 `res/xml/accessibility_service_config.xml` |
| `res/xml/accessibility_service_config.xml` 存在 | `android:resource="@xml/accessibility_service_config"` |
| `TouchRegionEditorService.start()` 用 `startForegroundService` | `<service>` + `foregroundServiceType="specialUse"` |
| `ConfigExporters.exportToDownloadsLegacy` 检查 `WRITE_EXTERNAL_STORAGE` | `<uses-permission>` + `maxSdkVersion="28"` |
| `data/permission/PermissionHelper` 用 `enabled_accessibility_services` | 无障碍服务必须是可绑定的（同第一行） |

⚠️ 顺带踩到一个 XML 细节:注释里写了 markdown 表格（`|---|---|`），
而 **XML 注释不允许出现连续 `--`**，整个文件解析失败。表格已改成文字说明。

### 18.3 这次是怎么验证的（以后都这么验）

不看源码，**直接看产物**。解出 APK 里的 `AndroidManifest.xml`（AXML，字符串池是 UTF-16LE）
再搜关键字:

```
SthKeyAccessibilityService   UTF16: ✅
BIND_ACCESSIBILITY_SERVICE   UTF16: ✅
TouchRegionEditorService     UTF16: ✅
WRITE_EXTERNAL_STORAGE       UTF16: ✅
OverlayService               UTF16: ✅
```

⚠️ `accessibility_service_config` 搜不到是**正常**的 —— 它在 AXML 里被编译成资源 ID，
名字只存在于 `resources.arsc`。

### 18.4 教训（最重要的一条）

⚠️⚠️ **这个仓库的工作区里有一大堆未提交的改动。
任何"恢复文件"的操作都不能用 `git checkout --` / `git restore`，
那会把别人（我自己前面几十轮）的工作一起抹掉。**

正确的做法:
1. 改动前先 `git diff <file> > 备份` 或直接把文件复制一份；
2. 删除大块内容时**用 `edit` 工具做精确替换**，不要用按行号的脚本删；
3. 改完 manifest 之后，**必须解 APK 验证组件还在**，光"编译通过"不算数。
---

## 19. 第四轮修正

| # | 问题 | 处理 |
|---|---|---|
| 1 | 组合卡片上**同时**有受控按钮和独立的「更多」按钮 | 上一轮替换操作区时**没删干净**，残留了旧的整块 `Row { Box { Icon(MoreVert) … } }`。已删（文件 307 → 269 行） |
| 2 | shell 授权无障碍**没有反馈** | 新增 `notifyShellAccessibilityGranted(useRoot)`：`ensureAccessibilityForOverlay()` 与 `grantAccessibilityByShell()` 在 `result.ok` 时弹一句「已通过 root / Shizuku 开启无障碍服务，悬浮窗即将显示」 |

### 19.1 ⚠️ 为什么手动授权不会弹

`ensureAccessibilityForOverlay()` 第一行就是 `if (SthKeyAccessibilityService.isConnected()) return true` ——
用户手动在系统设置里开过之后，服务早就连上了，**根本走不到弹提示那一步**。
所以"只有 shell 授权才弹"是**结构上保证**的，不需要额外判断。

### 19.2 ⚠️ 为什么不在 `CaptureController` 里弹

那里也会自动调用 `AccessibilityGrant.grant`，但 `refreshCapabilities`
被到处调用（进主页、切通道、调试页刷新…）。在那个位置弹 Toast 会**反复刷屏**。
提示只留给"用户真的开了个悬浮窗"和"用户主动点重试"这两条路。
---

## 20. ⚠️⚠️ 第二次事故:我把 `MainViewModel.kt` 截断成 416 行（已重建）

### 20.1 怎么发生的

给 shell 授权加 Toast 时，我用脚本删掉刚加的 `notifyShellAccessibilityGranted`。
循环里的 `$skipHelper = false` 被 PowerShell 当成**命令**执行而报错，
于是"跳过模式"一直开着，**把第 417 行之后的全部内容都丢掉了**（1285 → 416）。

⚠️ 又是同一类错误:**用脚本按行删代码**。第一次是 manifest，第二次是 ViewModel。

### 20.2 怎么恢复的（这次有据可依）

`git checkout` **绝对不能用**（工作区全是未提交改动，HEAD 是 v2.6.1 时代）。

⭐ 真正的救命稻草:**构建产物**。上一轮 19:01 的构建留下了
`app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes/`，
里面的 `MainViewModel.class` 就是**截断前那一版**编译出来的。

步骤:
1. 用 `javap`/CFR 反编译:`java -jar cfr.jar MainViewModel.class`（CFR 从阿里云 Maven 镜像下载）；
2. 从 1918 行 Java 里逐段读出真实逻辑；
3. 按原项目的代码风格 + 注释重新写成 Kotlin。

### 20.3 从反编译里纠正的三处（都是"凭记忆会写错"的地方）

| 项 | 我猜的值 | 反编译里的真值 |
|---|---|---|
| `TAG` | `"MainViewModel"` | **`"App"`** |
| `SERVICE_SHUTDOWN_GRACE_MS` | `600L` | **`800L`** |
| `capabilities` 初值 | `CaptureCapabilities()` | **`CaptureController.capabilities.value`** |

⭐ 另外还原了三个**只在 init 里**的关键行为:
1. `scope.launch { syncOverlayState() }` —— 对齐上次退出时残留的开关；
2. `scope.launch { CaptureController.capabilities.collect { capabilities = it } }` ——
   ⚠️ 能力是**外部状态**，必须持续收集；少了这个，界面上的 root/Shizuku 状态永远不更新；
3. 启动自检:`launch { runCatching { refreshCapabilities() } }.join()` 之后才算
   `startupCheckIssue` —— 否则会永远判成"没 root 也没 Shizuku"，给用户一个假警报。

⚠️ `init` 块必须放在**所有属性声明之后**（文件末尾）:`init` 按声明顺序执行，
放前面时协程里读到的是未初始化的 `null`/`0`，Kotlin 不报错、运行时才炸。

### 20.4 教训（写给以后的自己）

⚠️⚠️ **不要用脚本按行号删除 Kotlin/XML 代码。**
- 要么用 `edit` 工具做精确的字符串替换；
- 要么先把整段用 `read` 读出来，再写回去；
- 每改完一次**立刻编译**，两次操作之间不要叠加。

真正的教训不是"脚本写错了"，而是**批量删改本身就危险** —— 已经两次了。
---

## 21. 底栏动画"切完才走"（滑动翻页方向）

用户的原话:

> 软件左右滑动切换页面时，底栏的动画是切换完成才开始走的，经过实测，
> 悬浮底栏和标准底栏都有这个问题，请你解决一下，可以参考sukisu

### 21.1 原因

底栏的选中项来自 `pagerState.settledPage`：

- **点底栏**时我们主动设了本地状态，所以早就没这个问题；
- **左右滑动**时没人改那个状态 —— `settledPage` 只在滚动**停下**之后才变，
  于是整段拖动期间底栏一动不动，松手 settle 的瞬间才"啪"地切过去。

### 21.2 修法:区分"手指拖动"与"编程滚动"

| 操作 | 追踪谁 | 什么时候生效 |
|---|---|---|
| 点底栏 | 本地状态（点下去**立刻**改） | 点击瞬间 |
| 手指左右滑 | `pagerState.currentPage` | **越过半程就跟上**，不等 settle |

⚠️ 不能无脑跟 `currentPage`:
`animateScrollToPage(2)` 从第 0 页出发会**经过第 1 页**，
无脑跟会让指示块先跳 1 再跳 2（来回抽搐，这个坑之前踩过）。

⚠️ 所以加了 `programmaticTarget: Int?`：
- 点底栏时设成目标页 → 拖动跟随逻辑被跳过；
- `settledPage` 一变就清空 → 下一次手指滑动重新生效。

这样两种底栏（`NavigationBar` 与悬浮胶囊）都跟着**同一个** `selectedPage`，
不需要各改一套，也就不会出现"一种好了另一种还没好"。