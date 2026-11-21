# 取色盘（颜色选择对话框）方案

> 2026-11-21 起草。需求来自用户的一张截图（另一个 App 的 HSV 取色弹窗）+ 口述。
>
> **本文是方案 + 实施记录。** 决策与落地结果见 **§14「实施记录（2026-11-21）」**；
> §12 的四个「待拍板」已经在那一节里给了答案。

---

## 1. 一句话说清

把配置页里「颜色小方块 + 十六进制输入框」换成**整行可点的按钮**；点开一个取色对话框，
里面是 **2D 饱和度/亮度面板 + 色相条 + 6 位十六进制输入**，右下角「取消 / 保存」。

颜色仍然**只存 RGB**（`#RRGGBB`），alpha 由各分区的「不透明度」单独管；
对话框里的预览按**真实不透明度**合成，做到"看到的就是悬浮窗上的"。

---

## 2. 需求（用户原文要点）

| # | 要求 |
|---|---|
| 1 | 删除原本的颜色码输入框，换成**纯文本** |
| 2 | 与左侧的颜色预览**一同作为一个可点击的 button** |
| 3 | 点击弹出对话框，里面放取色盘 |
| 4 | 对话框里**保留手动输入 16 进制颜色码**的功能 |
| 5 | 右下角放**取消**和**保存** |
| 6 | **只能有 6 位颜色码**（透明度已有单独的「不透明度」分区） |
| 7 | **预览也要读那个不透明度**，不能出现预览与实际不一致 |

### 2.1 我的理解 / 补充（需要确认的都在 §12）

- 「纯文本」= 只读的 `#RRGGBB`，**不能就地编辑**（编辑只发生在对话框里）。
- 「button」的点击范围：**整行**，不是只有 24dp 的色块 —— 与设置页其它可点行一致，
  触摸目标更大（Material 的推荐做法）。
- 「预览读不透明度」：色块按 `alphaPercent` 半透明绘制，下面垫**棋盘格**，
  这样 30% 的颜色看起来就是"能透出底下"，而不是被卡片背景冲淡成一个假颜色。
- 对话框里 **P1 不放 alpha 滑杆**。原因与替代方案见 §3.4。

### 2.2 明确不做（P1）

- 不做屏幕吸色（eye dropper）、不做"最近使用"、不做预设色板。
- **不改配置格式**：JSON、`.sthkey`、`.sthgroup` 一个字节都不动。
- 不改「不透明度」分区的交互与数值。
- 不动主题色（`ThemeScreen` 仍是 9 个预设色，见 §11）。

---

## 3. 要不要把「颜色」和「不透明度」融合？（用户提问，结论在这）

### 3.1 先看清：现在其实有**三层**不透明度

| 层 | 存在形式 | 例子 | 作用域 |
|---|---|---|---|
| ① 元素级 | `Opacity` 8 个 Int%（`KeyStrokesConfig.kt`）、`JoystickStyle` 5 个 Float 0..1 | `opacity.keyUp = 70` | 每个颜色元素各一份 |
| ② 容器级 | `customOpacityPercent`（自定义 Key 整体）、Live2D「模型不透明度」 | 整块画布 80% | 盖在所有元素之上 |
| ③ 颜色自带的 alpha | 曾经有（`0xB3000000`），**现在一律忽略** | — | 已废弃 |

③ 是历史包袱：`docs` 与 `JoystickStyleTest` 记着那次事故 ——
颜色里带 alpha，进过一次配置页就被 `HexColorRow` 抹成 `0x00xxxxxx`，
渲染时 `0 × 1.0 = 0`，**摇杆整个消失**。所以现在的不变量是：
**颜色只存 RGB，透明只由 ①② 表达。**

### 3.2 融合到数据层（把 alpha 写进颜色）要付的代价

| # | 代价 | 具体后果 |
|---|---|---|
| 1 | 配置格式破坏性变更 | `colors.keyUp`（int）+ `opacity.keyUp`（int）要合成一个 ARGB；**所有老配置**都要迁移，包括用户手里的一堆 `.sthkey` / `.sthgroup` 包 |
| 2 | 跨端契约破坏 | `docs/key-id-format.md` 写明 Windows 端读同一份 JSON；ARGB 需要那边同步实现，否则颜色全变不透明 |
| 3 | 降级灾难 | 旧版本 App 的 `HexColorRow` 会 `and 0xFFFFFF` 把 alpha 抹掉 → 用户回退一个版本，所有半透明元素变实心 |
| 4 | 事故复发 | "颜色 alpha × opacity" 的双重乘法回来了，而且这次是**存进存档**的，出错更难查 |
| 5 | 并没有变简单 | ②「整体不透明度」是容器级，**不可能**塞进颜色。融合后仍然是"每个颜色一个 alpha + 一个整体百分比"两个概念，只是位置变了 |

顺带说明：③ 之所以被删掉，正是因为它和 ① 表达的是同一件事（"这个元素多透"），
两个真源必然打架。这一次不要再造第二个真源。

### 3.3 只融合到界面层（可选）

对话框里加一条 **alpha 滑杆**，但它**写回的是 ① 那个已有字段**（同一个 `opacity.keyUp`），
不是把 alpha 塞进颜色。用户体验上是"一个地方调完"，数据上仍然只有一个真源。

代价：每个调用点要多传一个 `onAlphaChange`（约 24 处，模板化）；
"不透明度"分区里那个滑杆**必须保留**（否则用户在别处找不到它，且列表页摘要要显示）；
没有配对 opacitiy 的颜色（媒体组件的染色色、描边色）不能加这条滑杆 → 界面不一致。

### 3.4 结论

> **不在数据层融合。P1 只做 RGB 取色盘；预览按 ① 的真实不透明度合成。**
>
> 如果之后仍觉得"调颜色还要滑下去调不透明度"很割裂，再做 **P2：对话框内加一条绑定同一字段的滑杆**。
> 这条既能满足"一个地方调完"，又不动配置格式。

为什么 P1 不放滑杆：用户的原始需求只要求"预览要读不透明度"，没要求"在对话框里改不透明度"；
先把改动面压到最小，滑杆是纯增量（P2 可随时加，接口已经预留 `onAlphaChange: ((Int) -> Unit)? = null`）。

---

## 4. 现状盘点（改动面）

### 4.1 现有组件 `ui/component/ColorEditor.kt`（278 行）

| 组件/函数 | 作用 | 去留 |
|---|---|---|
| `parseHexColor` / `formatHexColor` / `formatHexDigits` | 6 位解析与格式化（**已经严格 6 位**） | **保留**，新对话框直接复用 |
| `ColorSwatch` | 24dp 色块，按 `alphaPercent` 绘制 | 保留（可能加棋盘底） |
| `HexColorRow` | 行：label + 色块 + 内嵌 `OutlinedTextField` | **改造**成只读可点行 + 弹窗 |
| `HexColorDialog` | 色块 + 输入框 + 确定/取消 | **替换**为 `ColorPickerDialog`（目前**没有任何调用点**，是死代码，正好清理） |

### 4.2 调用点：24 处

| 文件 | 次数 | 颜色元素 | 是否传了 `alphaPercent` |
|---|---|---|---|
| `ui/feature/config/ConfigEditorScreen.kt` | 8 | 键帽/文字/描边/文字阴影 × （未按下/按下） | ✅ 全传（`editable.opacity.*`） |
| `ui/feature/custom/ComponentProperties.kt` | 8 | 同上（自定义组件） | ✅ 全传（`component.style.*OpacityUp/Down`） |
| `ui/feature/config/JoystickSections.kt` | 5 | 摇杆/底盘描边/内圆/摇杆帽/摇杆帽描边 | ❌ **全没传**（默认 100%） |
| `ui/feature/custom/JoystickProperties.kt` | 1（helper，5 处调用） | 同上 5 个颜色 | ❌ 没传 |
| `ui/feature/custom/MediaSection.kt` | 2 | 染色颜色 / 描边颜色 | ❌ 没传（这两个**没有** alpha，本来就该 100%） |

> ⚠️ 也就是说：**摇杆那 10 个颜色现在的预览是假的**（恒 100%，实际可能 50%）。
> 这正好是用户说的"预览与实际不一致"，顺手在本轮修掉（§9 P2）。

### 4.3 数据形态（不改）

- 颜色：`Long` / `Int`，值域 `0x000000..0xFFFFFF`，**没有 alpha 语义**。
- 不透明度：`Opacity` 是 Int%`0..100`；`JoystickStyle` 是 Float`0..1`；
  媒体组件没有 alpha（`tintPercent` 是染色程度，不是透明）。
- 渲染：`alpha = opacity`（**不看颜色里的 alpha**）。

---

## 5. 目标结构

```
ui/component/
├── ColorEditor.kt        ← ColorSwatch 保留；HexColorRow 改成"只读行 + 弹窗"；
│                            HexColorDialog 删除
├── ColorPicker.kt        ← 新增：
│                            · HsvColor + rgbToHsv / hsvToRgb（纯函数）
│                            · HsvSquare / HueBar（绘制 + 手势）
│                            · ColorPickerDialog（组装）
└── (parseHexColor 等)
```

依赖方向：`ColorEditor` → `ColorPicker`（单向），`ColorPicker` **不认识**任何配置模型，
只吃 `Long` 和回调 —— 这样它能被主题色页、以后任何地方复用。

### 5.1 API 草案

```kotlin
/** P1 只编辑 RGB；alphaPercent 仅用于预览 */
@Composable
fun ColorPickerDialog(
    title: String,
    initialRgb: Long,                  // 0xRRGGBB
    alphaPercent: Int = 100,           // 只读，来自「不透明度」分区
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
    onAlphaChange: ((Int) -> Unit)? = null,   // P2 才用；null = 不显示滑杆
)

/** 行：整行可点；未打开时就是 label + 色块 + #RRGGBB + › */
@Composable
fun HexColorRow(
    label: String,
    value: Long,
    onValueChange: (Long) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    alphaPercent: Int = 100,
)
```

> `HexColorRow` 的**签名不变**（`alphaPercent` 早就有了），所以 24 个调用点里
> 16 个一行都不用改；剩下 8 个补 `alphaPercent = ...` 即可。

---

## 6. 交互规格

### 6.1 行（未打开对话框）

```
┌──────────────────────────────────────────────┐
│ 键帽（未按下）        ▉  #FF8800        ›    │   ← 整行可点（ripple）
└──────────────────────────────────────────────┘
```

- 左侧 label，`weight(1f)`；右侧色块 24dp + 8dp 间距 + `#RRGGBB`（等宽字体 `FontFamily.Monospace`）+ 4dp + `›`。
- 高亮/禁用：`enabled = false` 时整行降透明度且不可点（当前没有调用点用，但保留）。
- 不再有焦点、不再有"失焦提交"这条路径（那套逻辑随输入框一起删掉）。

### 6.2 对话框结构

```
┌──────────────────────────────────────────┐
│ 键帽（未按下）                            │  ← title
│                                          │
│ ┌──────────────────────────────────────┐ │
│ │                                      │ │  ← 饱和度(S, 横) × 亮度(V, 纵)
│ │      白 → 当前色相   （渐变方块）      │ │     约 280dp 见方，圆角 12dp
│ │               ○                      │ │     圆圈 = 当前 S/V
│ │      透明 → 黑                        │ │
│ └──────────────────────────────────────┘ │
│ ┌──────────────────────────────────────┐ │
│ │ 红 黄 绿 青 蓝 品 红   （色相条 22dp） │ │
│ └──────────────────────────────────────┘ │
│                                          │
│  预览  ▉▉  #FF8800     不透明度 70%      │  ← 色块（棋盘底）+ HEX 输入 + 只读提示
│         └ 可编辑输入框 ┘                  │
│                                          │
│                       [ 取消 ]  [ 保存 ]  │  ← 右下角
└──────────────────────────────────────────┘
```

- 预览色块与输入框联动：拖方块/色相条 → 输入框文字实时变；输入框合法 → 方块实时变。
- 「不透明度 70%」是**只读提示**（点它不做事），文案："不透明度在下面的分区调整"。
  这样用户不会误以为对话框里能改，也不会疑惑"为什么预览是半透明的"。
- 右下角按钮：`TextButton("取消")` + `TextButton("保存")`（Material3 顺序，与项目里
  `NumberEditorDialog` / `HexColorDialog` 一致：confirm 在右）。

### 6.3 手势与命中

| 操作 | 行为 |
|---|---|
| 点在方块内任意位置 | 立刻跳到该点（tap 与 drag 用同一套坐标换算） |
| 按住拖动 | 连续更新，**不松手也能看到预览变** |
| 拖出方块边界 | 夹到 0..1（不取消、不回弹） |
| 点/拖色相条 | 只改 hue，S/V 不动 |
| 方块内拖动时 S=0 或 V=0 | 仍记录 hue（否则拖动时色相会"粘住"上一次的值） |

实现：`pointerInput` + `detectDragGestures`（`onDragStart` 当 tap 用）+ 一次 `detectTapGestures`；
坐标用 `size.width/height` 归一化，**不用 dp**，避免旋转/密度换算。

### 6.4 十六进制输入规则

| 输入 | 行为 |
|---|---|
| 非十六进制字符 | 过滤掉（沿用现在的做法） |
| 超过 6 位 | 截断到 6 |
| 小写 | 显示成大写 |
| 粘贴 `#FF8800` | 先去掉 `#` 再过滤 → 接受 |
| 粘贴 `0xFF8800` | **先去掉前缀 `0x`** 再过滤 → 接受（现在直接过滤会变成 `0FF880`，是错的，顺手修） |
| 少于 6 位 / 空 | 输入框标红 + 下方提示「需要 6 位十六进制，例如 FF8800」+ **保存按钮禁用** |
| 合法 | 保存可用；方块与预览实时跟随 |

复用 `parseHexColor`（已经是"非 6 位即 null"），保证对话框与其它地方**只有一套规则**。

### 6.5 保存 / 取消

| 动作 | 结果 |
|---|---|
| 保存 | `onConfirm(rgb)`，**只提交一次** |
| 取消 / 返回键 / 点外部 | 丢弃全部改动，不写配置 |
| 保存时值没变 | 仍然调 `onConfirm`（同上值），由调用方的 `applyChange` 决定是否记为一步；`CustomLayoutDraft` 那边相同的值不会产生新的 undo 步 |

> 为什么不做"实时生效"：24 个调用点大多走 `onComponentChange(..., true)`（记一步 undo），
> 拖动时实时写会把 undo 栈刷成几百步，而且每帧都会落盘。

### 6.6 键盘与焦点

- 打开对话框**不自动弹出软键盘**（用户多半是先拖色，不是先打字）。
- 点输入框才弹；`ImeAction.Done` → `clearFocus()` 收键盘（不提交，提交只认「保存」）。
- 外接键盘的 Enter 同上；Esc 走 `onDismiss`（AlertDialog 默认）。

### 6.7 无障碍

- 方块与色相条是 Canvas，**读屏拿不到**：给它们 `contentDescription`
  （"饱和度与亮度面板" / "色相"），并在方块的 `semantics` 里给出当前 `#RRGGBB` 的值。
- 真正的无障碍路径是**输入框**：读屏用户直接输 6 位即可，功能不受影响。
- 按钮文字用"取消 / 保存"，不用图标。

---

## 7. 技术要点

### 7.1 方块怎么画（两层渐变，不用位图）

```kotlin
// 1) 横向：白 → 纯色相（S 从 0 到 1）
drawRect(Brush.horizontalGradient(listOf(Color.White, Color.hsv(hue, 1f, 1f))))
// 2) 纵向：透明 → 黑（V 从 1 到 0）
drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
```

- 顺序不能反：先铺色相再压黑，才是标准 HSV 面板。
- 色相条同理，7 个停靠点（红黄绿青蓝品红）的 `horizontalGradient`。
- 圆角用 `clip(RoundedCornerShape(12.dp))`，不要用 `drawRoundRect` 再手算渐变裁剪。
- ⚠️ 不要每帧生成 bitmap（`ImageBitmap` + 逐像素）—— 一个渐变就够，拖动时零分配。

### 7.2 唯一真源：`rgb`；`hsv` 只是视图状态

| 事件 | 更新 |
|---|---|
| 打开 | `rgb = initial`；`hsv = rgbToHsv(rgb)`；`hex = format(rgb)` |
| 拖方块 / 色相条 | `hsv = 新值` → `rgb = hsvToRgb(hsv)` → `hex = format(rgb)` |
| 输入合法 hex | `rgb = parsed` → `hsv = rgbToHsv(parsed)`（**保留旧 hue**，见下） |
| 保存 | `onConfirm(rgb)` |

⚠️ **灰/黑时保留 hue**：`rgbToHsv(0x000000)` 的 hue 无定义（归 0）。如果用户先选红、再把
亮度拖到 0、再拖回来，直接重算 hue 会跳成红色而不是他原本选的蓝。所以 `hsv` 是**独立状态**，
只在新 hex 输入时"合并"：`hsv = HsvColor(旧hue, 新s, 新v)`。

### 7.3 预览 = 实际（这是用户最在意的一条）

| 层 | 是否进预览 |
|---|---|
| 颜色 RGB | ✅ |
| 元素级不透明度（`opacity.keyUp` 等） | ✅ |
| 容器级「整体不透明度」 | ❌ 不可能（它是整块画布的乘法，单行预览没有容器） |

- 色块：`Color(rgb).copy(alpha = alphaPercent/100f)`，**底下垫棋盘格**，
  这样 20% 的颜色看起来是"半透"，而不是被白卡片冲淡成浅色（后者会让人误判）。
- 预览文案里那个「不透明度 70%」就是它的来源，数字与分区滑块同源（同一个字段）。
- 「整体不透明度」在配置页的**大预览**（`ConfigPreview`）里已经体现了，单行不重复。

### 7.4 往返精度：打开→保存必须**零漂移**

`hsvToRgb(rgbToHsv(c))` 对某些颜色会差 ±1（浮点 + 取整）。因此：

- 保存时递出去的是 **`rgb`**，不是从 `hsv` 反算的值 → "打开就保存"永远等于原值。
- 只有用户真的拖了方块，才会产生 ±1 级别的变化（视觉上无差别）。
- 单测钉住：1000 个随机颜色，**打开→保存 = 原值**；拖动后的值在 ±1 内。

### 7.5 尺寸与旋转

- 用 `BoxWithConstraints` 在对话框里算：`边长 = min(maxWidth, maxHeight * 0.55f, 280.dp)`。
- 270~320dp 宽的小屏：边长跟着缩，色相条与输入框不缩。
- 横屏（可用高度可能只有 ~300dp）：整体 `verticalScroll`，方块取 `maxHeight * 0.55`，
  保证「保存」始终可见。
- 对话框沿用 `AlertDialog`（项目里 40+ 处都是它，自带 insets / 返回键 / 点外部关闭）。

### 7.6 性能

- 拖动时只有 `hsv` 状态变 → 重组范围限制在方块 + 色相条 + 预览 + 输入框，配置**不写**。
- 手势里不要 `remember` 分配对象（坐标直接用 `Offset`）。
- 打开对话框时才读一次 `initialRgb`；之后与外部配置解耦（避免外部刷新导致拖动中跳变）。

---

## 8. 依赖调研（用户要求"看看要加什么依赖"）

> ⚠️ **2026-11-21 更新：Kotlin 已升到 2.4.21**（见 [`kotlin-version.md`](kotlin-version.md)），
> 下面「元数据过新」这条否决理由**已经消失**：
>
> | 库 | 版本 | 编译用 Kotlin | 升级前 | **升级后** |
> |---|---|---|---|---|
> | skydoves/colorpicker-compose | **1.3.0** | 2.4.20 | ❌ | ✅ 可编译（`minCompileSdk=37` 也正好对上） |
> | side-codes/anyColorPicker | 2.x | 2.4.20 | ❌ | ✅ 可编译 |
> | com.kborowy:kolor-picker | 2.1.0 | 2.2.20 | ⚠️ | ✅ |
>
> **所以 §8.2 的结论需要重新拍板**：现在"能不能用库"不再是技术障碍，只剩"要不要用"的取舍
> （自研 ~350 行 vs 引入一个 150 KB 的 CMP 依赖 + 仍需自己写 hex 输入与弹窗壳）。
> 我仍然偏向自研（可控性 + 与项目主题一致），但这是产品口味问题，见 §12 第 4 条。

### 8.1 候选库与实测结论

判断依据：本项目 Kotlin 是 **2.2.10**（AGP 9 内置，`docs/ui-plan.md` 记着升不上去）。
Kotlin 编译器**读不了比它新的元数据**：2.2 编译器遇到 2.3/2.4 的库会直接
`Module was compiled with an incompatible version of Kotlin`。所以每个候选都查了 POM 里的
`kotlin-stdlib` 版本（≈ 编译它用的 Kotlin）：

| 库 | 版本 | 编译用 Kotlin | 结论 |
|---|---|---|---|
| [skydoves/colorpicker-compose](https://github.com/skydoves/colorpicker-compose) | 1.3.0（最新） | 2.4.20 | ❌ 元数据过新；且 `minCompileSdk=37`、依赖 CMP 1.12 |
| 同上 | 1.2.0 | 2.4.0 | ❌ 同上 |
| 同上 | 1.1.4 | 2.3.21 | ❌ 同上 |
| 同上 | **1.1.3** | **2.2.21** | ⚠️ **唯一能编过的版本**；依赖 `org.jetbrains.compose.*` 1.9.3（Android 端会重定向到 androidx），需实测版本冲突 |
| [side-codes/anyColorPicker](https://github.com/side-codes/anyColorPicker) | 2.x | 2.4.20（自述） | ❌ |
| [com.kborowy:kolor-picker](https://github.com/krizzu/kmp-kolor-picker) | 2.1.0 | 2.2.20 | ⚠️ 能编过，但自带 alpha 滑杆（与"只存 RGB"冲突）、样式要改 |
| [tberghuis/compose-color-picker](https://github.com/tberghuis/compose-color-picker) | 1.0.0（2021） | 1.5 时代 | ❌ 停更 5 年，Compose 1.1 API |

### 8.2 结论（前提是 Kotlin 2.2.10；该前提已于 2026-11-21 失效，见开头更新）：**不新增依赖，自己画**

四条理由：

1. **能用版本的窗口很窄**：只要哪天 AGP/Kotlin 一动，能编的版本就变；而项目已经被
   Kotlin 元数据坑过一次（miuix 0.9.4），没必要再引入一个需要"钉版本"的 UI 依赖。
2. **库只解决 60% 的问题**：方块 + 色相条它们有，但"6 位 hex 输入、跟外部不透明度联动的预览、
   取消/保存语义、项目主题与字号"仍然要自己写 —— 省下的其实是那 90 行 Canvas。
3. **样式要对齐**：项目 UI 是高度定制的 Material3（`SettingsCard`、`SettingRowSurface`、
   自定义滑块），库的调色板/滑块外观要逐个覆盖，改起来比自己画还碎。
4. **可控性**：HSV↔RGB 的边界（黑/灰的 hue、±1 漂移）必须自己掌握，否则会出现
   "拖了一下颜色变了 1" 这种没法解释的问题。

代价：约 **300~400 行**新代码 + 约 **150 行**测试，**1 个工作日**（不含审阅与真机回归）。

### 8.3 如果你还是想用库（决策门）

只在 `P0` 之前做一次 30 分钟 spike，任一条件不满足就回到自研：

```powershell
# 1) 加依赖（仅 spike 分支；Kotlin 2.4.21 之后 1.3.0 才编得动，1.1.3 已无必要）
implementation("com.github.skydoves:colorpicker-compose:1.3.0")
# 2) 确认解析得到、且没有把 Compose 降级
.\gradlew.bat :app:dependencies --configuration releaseRuntimeClasspath > deps.txt
#    搜 androidx.compose.foundation / runtime：应为 1.12.x（BOM 2026.08.00），不能出现 1.9.x
# 3) 编译 + 单测
.\gradlew.bat :app:compileDebugKotlin :app:testDebugUnitTest
```

阿里云镜像已确认有这个坐标（`https://maven.aliyun.com/repository/public/com/github/skydoves/...` 返回 200），
所以解析不是问题；**风险只在于 Compose 版本被 CMP 依赖往下拽**。

---

## 9. 实施阶段（每阶段都能单独编译、单独验证）

### P0 · 纯函数 + 单测（不动任何 UI）

- 新增 `ColorPicker.kt` 里的 `HsvColor` / `rgbToHsv` / `hsvToRgb`。
- 新增 `ColorPickerMathTest`（§10.1）。
- 验收：`.\gradlew.bat :app:testDebugUnitTest` 全绿；UI 无任何变化。

### P1 · 对话框（先接到一个入口上验证）

- 新增 `ColorPickerDialog`（方块 + 色相条 + hex + 预览 + 取消/保存）。
- 先只把「键盘 → 键帽（未按下）」这一处接上（`ConfigEditorScreen.kt:968`），
  用真机/模拟器把绘制、手势、小屏、横屏、深色模式都过一遍。
- 验收：改色 → 悬浮窗立刻变；取消 → 不变；打开→保存 → 完全不变。

### P2 · 全站替换 + 补 8 处 `alphaPercent`

- `HexColorRow` 改成只读行 + 弹窗（一次改，24 处同时生效）。
- 删除 `HexColorDialog`（死代码）。
- 摇杆 6 处补 `alphaPercent`（`JoystickSections` 5 + `JoystickProperties` 1），
  媒体 2 处**保持 100**（它们没有 alpha，别硬塞）。
- 验收：§10.2 手测矩阵；24 处逐一确认"预览与滑块数字一致"。

### P3 · 可选增强（本轮不做，接口已留）

- 对话框内 alpha 滑杆（`onAlphaChange`，写回同一字段）。
- H/S/V 数值滑杆（截图里有；对小块屏幕与无障碍更友好）。
- 色板/最近使用、吸色。

---

## 10. 验证清单

### 10.1 单测（JVM，跑得快）

| 用例 | 断言 |
|---|---|
| 往返 | 1000 个随机 `0xRRGGBB`：`打开→保存` 结果**完全等于**原值 |
| 漂移 | `hsvToRgb(rgbToHsv(c))` 与 `c` 每通道差 ≤ 1（仅用于拖动路径） |
| 端点 | 黑 / 白 / 纯红 / 纯绿 / 纯蓝 / `0x808080` 的 S、V 边界值 |
| 灰与黑 | `rgbToHsv(0x000000)` 不崩、hue 有定义；配合"保留旧 hue"的合并函数不跳色 |
| hex 解析 | `FF8800` / `#ff8800` / `0xFF8800` / `FF880` / `GGGGGG` / 空 / 7 位 |
| 格式化 | 永远是 6 位大写，带 `#` 与不带 `#` 两个函数不混用 |

### 10.2 手测矩阵

| 维度 | 取值 |
|---|---|
| 主题 | 浅色 / 深色 / 壁纸取色（Android 12+） |
| 屏幕 | 竖屏 360dp、竖屏 320dp、横屏、平板 |
| 字号 | 默认 / 1.3x（系统字体放大） |
| 键盘 | 软键盘 / 外接键盘（Enter、Esc） |
| 无障碍 | TalkBack 能读到输入框与两个按钮 |
| 颜色元素 | 24 处逐一：键盘 8、摇杆 5、自定义 8、媒体 2、摇杆组件 1 |

### 10.3 回归（不能破的）

- 打开→保存，配置**一个字节都不变**（`params.json` diff 为空）。
- 取消后 JSON 不变。
- 颜色字段仍是 6 位数字，**永远不出现 alpha**（导出文件里 `colors.*` 值 ≤ `0xFFFFFF`）。
- 不透明度滑杆、`整体不透明度`、`.sthkey` 导入导出全部不受影响。
- 旧配置导入后颜色不变（`KeyIdCodecRoundTripTest` / `StyleSpecificFieldsTest` 仍绿）。

---

## 11. 与「主题色取色盘」的关系

`ui/feature/settings/ThemeScreen.kt` 里有一段注释：

> 为什么是一排预设色，而不是 HSV 取色盘 … 将来要加取色盘的话，这里换成 `HsvColorPicker` 即可。

本次**不动**主题色（它是"选一个感觉"，预设色更快；而且它是 ARGB `Int`，与"只存 RGB"不同）。
但组件是通用的：`ColorPickerDialog(initialRgb = seed and 0xFFFFFF, ...)` 以后要接只差一行。
到时候再决定"主题色要不要 alpha"这个独立问题。

---

## 12. 待你拍板

1. **点击范围**：整行可点（我推荐），还是只有右边的"色块 + 文字"可点？
2. **P2 是否要做**：对话框里加一条"绑定同一字段"的不透明度滑杆？（我倾向先不做，看 P1 用起来的感觉）
3. **H/S/V 数值滑杆**：截图里那三条要不要？好处是小屏/无障碍/精确输入；坏处是对话框变高约 100dp。
4. **要不要用现成库**：Kotlin 升到 2.4.21 之后，`skydoves/colorpicker-compose:1.3.0`
   （Kotlin 2.4.20、`minCompileSdk=37`）已经能编；是直接用它，还是按 §8.2 自研？

---

## 13. 相关文件

```
ui/component/ColorEditor.kt                 现有三件套（改 1 删 1 留 1）
ui/component/ColorPicker.kt                 新增（数学 + 绘制 + 对话框）
ui/feature/config/ConfigEditorScreen.kt     8 处调用（签名不变，不用改）
ui/feature/config/JoystickSections.kt       5 处调用（补 alphaPercent）
ui/feature/custom/ComponentProperties.kt    8 处调用（签名不变）
ui/feature/custom/JoystickProperties.kt     1 处 helper（补 alphaPercent）
ui/feature/custom/MediaSection.kt           2 处调用（保持 100）
domain/config/KeyStrokesConfig.kt           Opacity 模型（只读，不改）
data/config/JsonConfigCodec.kt              颜色/不透明度字段（只读，不改）
docs/custom-key.md                          「颜色」与「不透明度」分区的既有约定
```

---

## 14. 实施记录（2026-11-21）

> ⚠️ 本节分两段：**§14.1–§14.6 是第一版（用库）的记录，已被 §14.7 取代**。
> 最终代码是 §14.7 的**自绘**实现，仓库里**没有** `colorpicker-compose` 依赖。
> 保留第一版是因为它记下了三个仍然有效的库集成坑（selectedColor / initialColor / Color(Int)）。

### 14.1 拍板结果

| §12 的问题 | 结论 |
|---|---|
| 点击范围 | **整行可点**（含 `›` 箭头暗示） |
| 对话框内 alpha 滑杆 | **不做**（不透明度仍只在各分区调） |
| H/S/V 数值滑杆 | **不做**（面板 + 色相条 + hex 足够；也避免对话框过高） |
| 用库还是自研 | **用库**：`com.github.skydoves:colorpicker-compose:1.3.0`（用户：自研耗时，不划算） |

### 14.2 实际改动

| 文件 | 改动 |
|---|---|
| `gradle/libs.versions.toml` | 新增 `colorpickerCompose = "1.3.0"` 与 `colorpicker-compose` 条目 |
| `app/build.gradle.kts` | `implementation(libs.colorpicker.compose)`（注释里写明"受 Kotlin 2.4 约束"） |
| `ui/component/ColorPicker.kt` | **新增**：`ColorPickerDialog` / `ColorPreview`（棋盘底）/ `normalizeHexDigits` / `rgbToOpaqueColor` |
| `ui/component/ColorEditor.kt` | `HexColorRow` 改成**只读可点行**（label + 色块 + `#RRGGBB` + `›`）；**删除** `HexColorDialog`（死代码） |
| `ui/feature/config/JoystickSections.kt` | 5 个颜色行补 `alphaPercent`（`js.opacity` / `strokeOpacity` / `ringOpacity` / `knobOpacity` / `knobStrokeOpacity`） |
| `ui/feature/custom/JoystickProperties.kt` | `JoystickColorRow` 增加 `alpha` 参数，5 个调用点全部传入 |
| `ui/component/ColorHexTest.kt` | **新增** 11 个用例（解析/格式化/输入整理/往返） |

> 媒体组件那 2 个颜色（染色色、描边色）**保持 100%**：它们没有 alpha，`tintPercent` 是染色程度不是透明。

### 14.3 实现上值得记的三件事

1. **不要用 `HsvColorPicker.onColorChanged` 当唯一出口**
   —— 那个回调只覆盖"点/拖面板"，拖**色相条**走的是 `HueSlider` 的另一条回调。
   正确做法是监听 `controller.selectedColor`（这里用 `snapshotFlow`），
   两种来源一次收齐，也不会漏掉程序化设置。
2. **`initialColor` 必须传稳定值**
   —— 如果传跟着 `rgb` 变的值，会形成"库设色 → selectedColor 变 → 我们改 rgb → 再传下去"的自激。
   这里 `val initialColor = remember { rgbToOpaqueColor(initialRgb) }`。
3. **`Color(Int)` 是 ARGB，不是 RGB**
   —— 直接 `Color(rgb.toInt())` 会得到 **alpha=0 的透明色**（高 8 位是 0）。
   统一走 `rgbToOpaqueColor()`（`0xFF000000 or rgb`），否则面板初始色/预览会发虚。

### 14.4 可滚动（用户明确要求）

- 对话框内容整块 `verticalScroll`；
- 面板用 `BoxWithConstraints` 取 `min(可用宽度, 280.dp)` 的**正方形**；
- 色相条 24dp、输入框单行、提示一行 —— 窄屏/低 dpi/大字体下超出高度时**滚动**，不再挤压按钮。

### 14.5 验证

| 项 | 结果 |
|---|---|
| `:app:testDebugUnitTest` | ✅ 86 个测试类 / **798 个用例**，0 失败（新增 11 个） |
| `:app:assembleDebug` / `:app:assembleRelease` | ✅ 通过（R8 + lintVital） |
| 依赖 | ✅ 解析到 `colorpicker-compose-android:1.3.0`；**没有**把 Compose 从 1.12.0 降级，也没有引入 `org.jetbrains.compose.*` |
| APK | 10,263,265 B（比加库前多 **50 KB**），签名证书不变 |
| 真机 | ⏳ **未验证**（本机没有连接设备）：面板拖动手感、初始色是否正落到面板、小屏滚动、保存后悬浮窗实时变化 |

### 14.6 已知限制

- 只有 **6 位 RGB**，没有 alpha —— 这是刻意的（§3），不要"顺手"改成 8 位。
- 打开→保存不写盘（`onConfirm` 只在值真的变了才回调），但**每次打开会重建控制器**，
  面板初始色来自当前值，不会"跳到白色"。
- 库的调色板外观固定（圆角由 `clip` 控制），与项目主题只共享对话框外壳；
  如果以后要"完全贴合 Material 3 配色"，再考虑 P3 自绘。

---

### 14.7 返工记录（2026-11-21 晚）：库的色轮不满足，改为自绘

#### 为什么换

用户上手后反馈（附截图）：

- `HsvColorPicker` 画的是**色轮**：色相 = 角度、饱和度 = 半径，**明度固定**
  → 调不出灰/黑（比如想要深灰、纯黑）；
- 下面那根色相条与色轮重复，用户原话是"不知道什么意义的条"；
- 参考对象是**画世界Pro**：色环 + 内嵌 S/V 方板 + H/S/V 三条滑杆 + 色板。

调研结论（§8 已列）：anyColorPicker 是"planes + 滑杆"、kolor-picker 是"方板 + 色相条"、
compose-color-picker 是"方板 + alpha"——**没有库提供"色环 + 内嵌方板"**。→ 自绘。

#### 最终布局

```
┌────────────────────────────────┐
│  键帽（未按下）                 │
│         ╭────────────╮         │  ← 色环：色相 = 角度
│        │   ┌──────┐   │        │
│        │   │ S/V  │   │        │  ← 内嵌方板：x = 饱和度，y = 1 - 明度
│        │   └──────┘   │        │     （左上 = 白，右下 = 黑，能调灰）
│         ╰────────────╯         │
│  H ▭▭▭▭▭▭▭▭▭▭▭▭   120°       │  ← 三条滑杆，与画世界Pro 一致
│  S ▭▭▭▭▭▭▭▭▭▭▭▭    60%
│  V ▭▭▭▭▭▭▭▭▭▭▭▭    80%
│  ▨  #FF8800                    │
│  不透明度在…分区调整（当前 70%） │
│                     取消   保存 │
└────────────────────────────────┘
```

#### 代码结构（全部在 `ui/component/ColorPicker.kt`）

| 部分 | 说明 |
|---|---|
| `Hsv` + `rgbToHsv` / `hsvToRgb` | 纯函数（`internal`），JVM 单测覆盖 |
| `ColorRingAndSquare` | **一个 Canvas** 同时画色环与方板；手势按"离圆心距离"分区 |
| `HsvSlider` | 渐变轨道 + 拇指，`fractionAt()` 扣掉两端圆角半径 |
| `ColorPreview` | 棋盘底 + 按真实不透明度叠色 |
| `normalizeHexDigits` | 6 位输入整理（`#` / `0x` 前缀） |

#### 关键实现细节（改这块前必读）

1. **同一套几何算两次**：`ringGeometry(size)` 同时供绘制与命中测试用 ——
   两边各写一份迟早会错位（点的地方和看到的地方对不上）。
2. **手势 `pointerInput(Unit)` + `rememberUpdatedState(hsv)`**：
   key 写成 `hsv` 的话，拖动中每次状态变化都会重启手势识别，**拖一下就断**。
3. **色环 0° 在 3 点钟方向、顺时针增加**，与 `Brush.sweepGradient` 的起点方向一致；
   命中测试用 `atan2(dy, dx)`，两者必须同一坐标系。
4. **H 滑杆最右端夹到 `0.999`**：`hue = 360` 与 `0` 是同一个颜色，
   但会让拇指从最右端突然跳回最左端，看起来像 bug。
5. **灰阶保留旧色相**：`rgbToHsv(rgb, previousHue)`，否则"明度拖到 0 再拖回来"会变红。

#### 验证

| 项 | 结果 |
|---|---|
| 单测 | ✅ 87 个测试类 / **806 个用例**（新增 `HsvColorTest` 8 例：端点/灰阶/越界/往返/无 alpha） |
| debug / release | ✅ 通过（R8 + lintVital） |
| 依赖 | ✅ 已移除 `colorpicker-compose`，依赖图里查不到它 |
| APK | 10,229,833 B（自绘代码约 +17 KB，比用库时小 ~33 KB），签名证书不变 |
| 真机 | ⏳ **未验证**：色环/方板拖动是否跟手、拇指位置、小屏滚动、保存后悬浮窗刷新 |

