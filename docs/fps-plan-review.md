# FPS 显示方案评审（对照 DroidPerf 源码）

> 状态:**评审意见，未开工**
>
> 评审对象:用户写的《fps 接入设计方案》+ DeepSeek 的提案
> 参考实现:`DroidPerf-main`（已解压到 `D:\Project\_droidperf`，只读）

---

## 1. ⚠️ 必须先纠正一处事实：Choreographer 测不到游戏的帧率

方案里写了:

> 只授权无障碍（没有 shizuku/root）时，走 choreographer，有 shizuku/root 时，走 surfaceflinger

DeepSeek 也顺着说"Choreographer……大约等于系统全局帧率"。

**这两句都不成立。** DroidPerf 的 `FpsMonitor.kt` 文件头注释写得很直白:

> Choreographer is deliberately NOT used for the game: our callbacks only ever describe
> our own process. It is only used for the overlay's own FPS, reported separately.

原因:Choreographer 的帧回调是**本进程**的 VSync 节拍。把它放在我们自己的无障碍服务里，
它拿到的是"屏幕刷新节拍"（120Hz 机就是 120），**与前台游戏实际渲染多少帧毫无关系** ——
游戏卡到 20fps 时，我们的 Choreographer 依然按 120 回调。

### 结论:没有 Shizuku/root 时，只有三条路

| 方案 | 说明 | 建议 |
|---|---|---|
| **A. 显示 `N/A`** | 诚实。DroidPerf 的 "Zero Fabrication" 就是这个态度 | ⭐ **推荐** |
| B. 另做一个 `(hz)` 占位符 | 显示屏幕刷新率，语义是"上限"，不是 FPS | 可选，别混进 `(fps)` |
| C. 只支持有权限 | 没权限就不显示 | 等同于 A |

⚠️ 如果硬把 Choreographer 当 FPS 用，用户会看到"游戏明明卡成 PPT，悬浮窗还显示 120" ——
那比 `N/A` 糟糕得多，而且无法解释。

---

## 2. Android 14+ 的主数据源是 `--timestats`，不是 `--latency`

DroidPerf 的注释:

> on Android 14+ builds (including HyperOS) SurfaceFlinger no longer reports frames
> through this interface (`--latency`), so it only works on older devices.
>
> SurfaceFlinger timestats …… This is the working per-layer FPS source on Android 14+/HyperOS.

用户的机器是 **API 36（Android 16）**，所以:

| 优先级 | 数据源 | 适用 |
|---|---|---|
| 1 | `dumpsys SurfaceFlinger --timestats -enable/-dump/-clear/-disable` | **A14+（我们的主路径）** |
| 2 | `dumpsys SurfaceFlinger --latency '<layer>'` | ≤ A13 回退 |
| 3 | `dumpsys gfxinfo <pkg> framestats` | 非 SurfaceView 的普通应用 |
| 4 | `N/A` | 全失败 |

⚠️ **timestats 是有状态的**:`-enable` 之后会在系统里持续累积缓冲。
DroidPerf 在 `shutdown()` 里 `-clear` + `-disable` —— 我们**必须照做**，
否则用户关掉悬浮窗后系统还在为我们记账（耗电、内存增长）。

---

## 3. 采样成本:方案里没提到的一处大头

DroidPerf 每次采样实际跑 **3 条 shell 命令**:

```
dumpsys SurfaceFlinger --list        ← 找 layer
dumpsys SurfaceFlinger --dump        ← 取 timestats
dumpsys SurfaceFlinger --clear
```

`dumpsys` 是重操作。1 Hz 已经不算轻，我们还能再省:

| 优化 | 做法 |
|---|---|
| **缓存 layer** | `--list` 只在「前台包变化 / 解析失败 / 窗口重建」时跑，不要每秒跑 |
| **限读大小** | `--dump` 输出可能有几 MB；只读前 N KB 并只解析目标 layer 段（⚠️ 我们刚在媒体导入上踩过 OOM:读整段进内存） |
| **只在我们采样时 enable** | 停止时必须 `-clear` + `-disable` |
| **1 Hz + 后台线程** | 绝不主线程;两次采样之间不允许重叠（上一次没完就跳过本次） |
| **值不变不推** | 数值没变就不更新 state，避免每秒无谓重组 |

---

## 4. 占位符语法:方向对，但有四个细节要定死

方案:`(fps+1500,offset=10-300)`，含义 `n + m ± b`。

采纳 DeepSeek 的核心建议:**解析一次、存成数据类、渲染时只做算术**（不要每帧正则）。
在此之上定四条:

1. **优先级**:先运算、后偏移
   `n' = op(n, m)` → `final = n' ± random(x, y)`。
   `×` 与 `+` 不混用（一个占位符只允许一个运算符），避免歧义。
2. **取值约束**:`m` 为非负整数且 `×` 时 `m ≥ 1`；`0 ≤ x ≤ y ≤ 9999`；
   最终结果 clamp 到 `0..99999`（防止 `(fps*9999)` 撑爆文本宽度）。
3. ⚠️ **随机偏移每秒跳一次会很假**。`offset=10-300` 时每秒可能跳 290 —— 反而更不像真帧率。
   建议:**限制相邻两次的跳变幅度**（例如每秒最多变 ±5%），或者做成"随机游走"（在上次值附近抖动）。
4. **宽容解析**:写错时**当作普通文本原样显示**（`(fps+abc)` 就显示这串），
   而不是整段消失 —— 否则用户只会看到"我的文字不见了"。

---

## 5. 显示 `0` 还是 `N/A`

**`N/A`**（与 DroidPerf 一致）。理由:显示 0 会被当成"游戏卡死"，
而实际更可能是"没权限/在桌面/读失败"。

⚠️ 但注意一个排版差异:`(fps)` 显示成 `N/A` 会改变文本宽度（比 `60` 宽），
嵌在一句话里可能换来换去。可选做法:给一个 `(fps?)`（不可用时整段隐藏），
但**先不做**，避免占位符语义膨胀。

---

## 6. 启停策略:在方案基础上补三条

方案:「配置里含 `(fps)` 才启动，不含就关闭」——与手柄/触屏的思路一致，是对的。

我建议再补:

| 补充 | 原因 |
|---|---|
| **屏幕关闭时暂停采样** | 熄屏期间采 FPS 纯浪费电 |
| **Shizuku 掉线时停止并显示 N/A** | 否则每秒都在失败重试 |
| **停止要 debounce（3~5 秒）** | 用户编辑文本时可能连续删/加 `(fps)`，避免反复 enable/disable |

⚠️ 另外:`-enable`/`-disable` 是有副作用的全局状态，**服务被杀时也要 disable**
（`onDestroy` 里做，且做成幂等）。

---

## 7. 我们相对 DroidPerf 的改进点

| # | DroidPerf | 我们 |
|---|---|---|
| 1 | 走 `UserService` binder | **直连 Shizuku shell**（无中间层，开销更小）✓ 用户已确认 |
| 2 | 常驻采样（整个 overlay 生命周期） | **按 `(fps)` 占位符按需启停** |
| 3 | 只有 FPS/帧时间 | 只需要 FPS 这一个值 |
| 4 | — | 有调试页，可展示「当前数据源 / 最近错误 / 采样耗时」 |
| 5 | 有 1% Low（`FpsStatsTracker`） | 先不做，以后可加 `(fps_low)` |
| 6 | layer 选择是一套启发式 | 直接复用那套启发式（活的经验），但**加缓存** |

---

## 8. 建议的落地顺序

| 阶段 | 内容 |
|---|---|
| **P1** | `FpsMonitor`（timestats 主路径 + layer 缓存 + `N/A`），只跑日志验证 |
| **P2** | 占位符 `(fps)` 接入渲染 + 表达式解析（照 CPS 那一套） |
| **P3** | 按需启停（配置扫描 + debounce + 熄屏/掉线） |
| **P4** | 预览固定 120 + 调试页展示数据源 |
| P5（以后） | `--latency` / `gfxinfo` 回退、1% Low、自定义刷新间隔 |

⚠️ 明确不做:`Choreographer` 当 FPS（见 §1）。

---

## 9. 风险清单

| 风险 | 说明 | 对策 |
|---|---|---|
| 部分 ROM 读不到 | HyperOS / 某些游戏加速层即使 shell 也拿不到 | 一律 `N/A`，日志写清原因 |
| layer 匹配失败 | Vulkan / 自绘 SurfaceView、分屏、小窗 | 多候选 + 失败时重试 list；调试页可看选中的 layer 名 |
| `--dump` 输出过大 | 个位数 MB 不罕见 | 限读 + 只解析目标段（**别整段进内存**） |
| timestats 常开 | 系统缓冲持续增长 | 停止必 disable，且幂等 |
| 每秒重组 | 文本每秒变化触发布局 | 值不变不更新;固定宽度数字可选 |

---

## 10. 讨论结论（2026-11-01 定稿）

### 10.1 Choreographer 保留 —— 但它给的是"上限"，不是游戏帧率

用户决定保留这条路，理由是"部分用户要的只是视觉效果"，配合 `offset` 也够用。

**采纳**，但有三条工程约束（不影响体验，只影响"我们说的是不是真话"）:

| 约束 | 做法 |
|---|---|
| 数值来源要**真的在动** | 不是写死 120。测「我们这进程的 VSync 回调间隔」:系统忙（游戏吃满）时回调会被推迟，测出来就**低于**刷新率。所以它更像"当前系统的帧节拍上限"，不是固定常数 |
| 数据源要可查 | 调试页显示 `数据源: Choreographer(上限)` / `SurfaceFlinger timestats` / `N/A` |
| 失败语义分开 | Choreographer 模式**不显示 N/A**（它总能给个数）;只有 SurfaceFlinger 模式读不到才 `N/A` |

⚠️ 也就是说:没有 Shizuku 时显示的是**估算上限**（通常 120 或 60），有 Shizuku 时才是**游戏真实帧率**。
公告里要写这一句 —— 否则用户会拿它当真实帧率去跟别人对线。

### 10.2 预览值

| 占位符 | 预览显示 | 悬浮窗 |
|---|---|---|
| `(cps)` | `0` | 实时 |
| **`(fps)`** | **`120`（固定）** | 实时（无数据时 `N/A`） |

⚠️ 两者**互不相干**，不要互相"统一"。

### 10.3 `offset` 不加额外限制 —— 但随机逻辑写清楚

用户的意思:范围交给用户自己填，不给硬限制。采纳。

**语法（最终版）**

```
(fps)                          → 只显示帧率
(fps+1000)                     → n + 1000
(fps-5)                        → n - 5（结果 < 0 时取 0）
(fps*2)                        → n × 2
(fps+1000,offset=20-80)        → 先 n+1000，再随机 ± [20,80]
```

**求值顺序**:先运算、后偏移，**一次刷新只算一遍**。

```
n'  = op(n, m)                       // +、-、× 三选一，不允许连写
b   = randomInt(x, y)                // 闭区间，均匀分布
sign= 每刷新一次随机取 +1 或 -1
out = max(0, n' + sign * b)          // 不为负
```

⚠️ **每次刷新（1 Hz）重新抽一次 `b` 和 `sign`**，不跨秒保留。
所以你看到的是"每秒抖一下"，而不是平滑变化 —— 这正是要的效果（真帧率也是每秒一个数）。

⚠️ 真实可用范围（用户给的例子）:`(fps+1000,offset=20-80)`。
`offset` 填 `10-300` 也不会崩，只是看起来更像"随机数生成器"而不是帧率 —— 这个交给用户判断。

**边界与容错**

| 情况 | 行为 |
|---|---|
| `x > y` | 自动交换（不报错、不消失） |
| `x`/`y` 为负 | 取绝对值 |
| `m` 为负（`*` 时） | 取绝对值;`m = 0` 且 `*` → 视为 `1`（否则永远显示 0，像坏了） |
| 语法写错（`(fps+abc)`） | **原样显示这串文本**，不吞、不消失 |
| 值不可用（SurfaceFlinger 读不到） | 显示 `N/A`，**不参与运算** |

### 10.4 落地顺序（不变）

P1 `FpsMonitor`（timestats 主路径 + Choreographer 估算 + layer 缓存 + `N/A`）
→ P2 占位符解析与渲染 → P3 按需启停 → P4 预览固定值 + 调试页数据源。
---

## 11. P2 实现记录（真机格式已确认）

### 11.1 真机上确认的事实（OnePlus PLC110 / API 36）

| 项 | 结论 |
|---|---|
| `--latency` | **不可用**。三种层名写法都只回 `11111111`，与 DroidPerf 注释一致 → 放弃 |
| `--list` 格式 | `RequestedLayerState{989f6ac SurfaceView[com.pkg/Act](BLAST)#246727 parentId=..}` — 带 hash 前缀和 `#id` |
| `--timestats -dump` | 逐层输出 **`layerName = ...` + `averageFPS = ...` 成对**，这才是帧率 |
| `renderRate` | ⚠️ **不是**逐层帧率，是"全局聚合"段的字段（与 `displayRefreshRate`、`totalTimelineFrames` 一起） |
| 噪音层 | `Toast`（实测到 125）/ `StatusBar` / `NavigationBar` / `Splash Screen` / launcher 都在 dump 里 |

### 11.2 ⚠️ 我踩的坑：给解析器加了 DroidPerf 没有的"兜底"

第一版我写了"前台包匹配不到时，退回**全量层**挑最大值"。
结果目标层落到了 Toast / 启动器 / 自己的悬浮窗层，数值在 **23.8 ~ 125** 之间乱跳，
还超过了屏幕刷新率 120Hz。

DroidPerf 的做法是**严格**的:
1. 有包名 → **只**看层名含该包名的层；一个都没有就返回 0（= 没有帧）。
2. 没包名 → 才排除 `Wallpaper/StatusBar/NavigationBar/Overlay`。
3. 选择:`firstOrNull { SurfaceView }` → 否则 `maxByOrNull`。

现在 `parseAverageFps` 是它 `TimeStatsParser.parse` 的**逐行移植**（见 `FpsMonitor.kt` 注释）。

### 11.3 前台包名

照抄 `ForegroundAppDetector`:
`dumpsys activity activities` 中**含 `ResumedActivity` 的行** → 正则 `([a-zA-Z0-9_.]+)/`。
（DroidPerf 还用 `UsageStatsManager` 作为首选，需要"使用情况访问"权限;我们这一段已经有
Shizuku，直接走 shell 更省事。）

### 11.4 下一步

- 目标层日志 `FPS 目标层：...（前台包=...）` 用来确认抓到的是游戏而不是自己
- 通过后做 **P3**:`(fps)` 占位符接入文字渲染 + `offset` 运算 + 按需启停
### 11.5 P2 验证通过（真机）

日志（游戏内，120Hz 屏）:

```
fps=119.0 / 119.5   ← 菜单（120Hz 满帧）
fps=16.1            ← 加载瞬间
fps=59.3 / 59.1 / 59.3 …  ← 游戏内稳定在 60 上限
```

数值不再乱跳、没有超过刷新率、进入游戏后稳定 —— **P2 收口**。

### 11.6 P3a 已就绪:`FpsText`（占位符解析）

`domain/custom/FpsText.kt`:

- 语法:`(fps)` / `(fps+1000)` / `(fps-5)` / `(fps*2)` / `(fps+1000,offset=20-80)`
- 求值:先运算后偏移，每秒重抽一次幅度与方向，结果 clamp 到 ≥ 0
- 容错:写错原样显示;读不到显示 `N/A`（不参与运算）
- **纯函数**（随机源可注入），便于写单元测试

⏭️ P3b（下一轮）:把 `FpsMonitor.reading` 接进 `CustomKeyGrid` / `CustomLayout.renderTextAt`
（与 `cps` 同一条路径）；预览固定 120;并按 `(fps)` 占位符做按需启停。