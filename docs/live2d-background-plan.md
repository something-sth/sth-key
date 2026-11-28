# Live2D 背景（纯色底板）方案

> 目标版本：2.8.8 开发期
> 涉及样式：`keyboard_cat`（配置页里显示为 `LIVE 2D`）—— **所有 Live2D 配置**都有这组设置，
> 与具体模型无关（内置键盘猫 / 内置鼠标猫 / 任一导入模型都适用）。
> 本文只写方案，不含实现。实现按 §9 的顺序分 4 步提交。

---

## 0. 结论先行

1. 配置页分区顺序改成：**模型 → 外观 → 不透明度 →（表情）→（水印）**。
   「键盘猫」这个分区名与它上面那行 item 都改成「模型」语义，放到最上面。
2. 「外观」分区里新增 **背景** 开关（默认关）。开启后同一张卡片里往下长出：
   **宽度** 滑块、**高度** 滑块、**背景颜色** 行（默认 `FFFFFF`，复用 `HexColorRow`）。
3. 「不透明度」分区里新增 **背景不透明度**（仅背景开关开启时出现）；
   原来的「模型」滑块**只影响模型本身**，不再影响背景。
   —— 这一条不是改个乘数就行：现在 Live2D 的不透明度是 Android 侧的
   `view.alpha`（整层一起淡），必须改成**页内乘算**（见 §4.6）。
4. 背景是一块**居中的纯色矩形**，尺寸按「模型内容矩形」的百分比算，
   宽高各自以中心对称伸缩（调宽 = 左右同时长/短）。
5. 背景大于 100% 时**窗口跟着变大**（否则多出来的部分会被窗口裁掉，滑块等于没效果），
   并保持**模型在屏幕上的位置不动**（窗口按模型内容锚定而不是按窗口左上角，见 §7）。
6. 配置页预览要**合成**出来：底色块 + 模型预览图（`preview.png` / `cover.png` /
   `icon.png`，没有就占位图标）按同一套比例缩放，与真实悬浮窗一致。

### 最后确认的取舍（2026-11-28 已定，实现按这个）

| # | 问题 | 结论 |
|---|---|---|
| A | 纯色背景和**包自带底图**（`#background`，Mver 包的桌面/键盘底图、内置键盘猫的桌子）谁在上面？ | **背景垫在最底层**（`z-index: 0`，插在 `#stage` 第一个子节点）。盖住模型就不叫背景了；包底图仍在其上，所以背景只在模型透明处与扩大后的边距里可见。 |
| B | 背景 >100% 时窗口是否变大？ | **变大**，且模型在屏幕上的位置保持不动（窗口按模型内容锚定，见 §7.3）。 |
| C | 宽高百分比的基准 | **模型内容矩形**（`contentBounds`；自带底图的包/内置模型用设计画布矩形）。 |
| D | 滑块步长 | 宽/高、背景不透明度都 **5% 一档**（连续滑块会触发大量窗口重排与跨桥下发，实测卡顿，见 §4.10）。数值按 1% 存/显示，点数值仍可精确输入。 |
| E | 「模型」分区里那行 item 的标题 | **「当前模型」**（避免与分区标题「模型」重复）。 |
| F | 新增 UI 的副标题 | **尽量不写**。只保留开关/滑块的标题本身（开源项目，界面要简洁；后续还会删掉其它无用副标题）。 |
| G | 模型不透明度的合成方式 | **整层 CSS opacity**（一层 `#modelLayer` 容器），**不能**逐个 drawable 乘 alpha —— 后者会"透视"到内部图层（2026-11-28 实测踩过，见 §4.6）。 |
| H | 包自带底图（桌子/键盘）跟着谁动 | **跟着模型**：① 背景撑大窗口时钉回**设计画布矩形**（否则桌子被拉宽错位，见 §4.11）；② 跟着模型不透明度一起淡，纯色底独立。 |


---

## 1. 需求拆解与验收标准

| # | 需求 | 验收 |
|---|---|---|
| R1 | 「键盘猫」分区挪到最上面，改名「模型」 | 配置页第一个分区标题是「模型」，里面就是模型选择那一行 |
| R2 | 「外观」里新增「背景」开关，默认关 | 新建配置默认为关；关着的时候不产生任何背景、不出现下面三行 |
| R3 | 开关开启后出现宽度 / 高度两个滑块 | 只出现两个滑块 + 颜色行；宽度/高度调整时背景**左右/上下对称**伸缩 |
| R4 | 背景颜色行，默认 `FFFFFF` | 点开复用现有取色器；保存后才提交（与其它颜色行一致） |
| R5 | 「不透明度」里新增背景不透明度 | 只在背景开启时出现；调它只改背景 |
| R6 | 模型不透明度只控模型 | 模型调到 20% 时背景仍是 100%（反之亦然） |
| R7 | 有预览贴图的模型，配置页预览要正确显示 | 预览里能看到「底色块 + 模型预览图」，比例、位置与悬浮窗一致；没有预览图时占位图标仍居中在模型矩形里 |
| R8 | 老配置不受影响 | 解码缺 `background` 时 = 关闭、默认值；已发布的 2.8.7 配置打开后外观零变化 |
| R9 | 悬浮窗实时生效 | 拖动滑块时悬浮窗立即变，且**不重载页面**（模型不重新解析） |

---

## 2. 现状盘点（动手前必须知道的几件事）

### 2.1 数据

| 位置 | 内容 |
|---|---|
| `domain/config/KeyStrokesConfig.kt` `Live2DSettings` | 现有 5 个字段：`modelId` / `opacityPercent`(20..100) / `expression` / `expressionBindings` / `hideWatermark` |
| `data/config/JsonConfigCodec.kt` | 编码：`if (appliesTo(config.styleId, "live2d")) { put("live2d", JSONObject…) }`（约 992 行）<br>解码：`decodeLive2D(json)`（约 1470 行） |
| `data/config/ConfigJsonComments.kt` | `"live2d" to "…"` 一行 + 嵌套说明（约 117 / 151 行）；`ConfigJsonCommentsTest` 会逐字段检查"有没有说明" |
| `data/config/StyleSpecificFieldsTest` | 断言 `live2d` 这个键只写给 `KEYBOARD_CAT`。背景塞进 `live2d` 子对象后**这张表不用改**（门控粒度是顶层键） |

### 2.2 渲染

| 位置 | 内容 |
|---|---|
| `ui/overlay/live2d/Live2DOverlayView.kt` | WebView 承载页面。已有 `applyWatermark(hidden)` / `applyExpressions(...)` / `setFrameRate(...)`；`pageReady` 后才 `dispatchRaw`；`onPageFinished → flushAllToPage()` 整体补发；`SthKeyBridge.onContentAspect(aspect)` 把**模型内容比例**回报给原生侧 |
| `assets/bongocat/keyboard/runtime.js` | 自研渲染器。`CoreRenderer`：`resize()` 里三分支算 `this.scale/offsetX/offsetY`（1️⃣ 自带底图 `full` 2️⃣ Mver 手部贴图 `HANDS` 3️⃣ 导入模型按 `contentBounds`；4️⃣ 内置按 612×354）；`render()` 里 `gl.clearColor(0,0,0,0)` + 按 `renderOrders` 画 drawable；`bindGeometry()` 里 `uOpacity = d.opacities[i]`；`window.AxonBongoCat` 是给原生侧调的 API |
| `assets/bongocat/standard/runtime.js` | 内置**鼠标版**页面，渲染器是一个精简副本（只有 612×354 一条分支），`AxonBongoCat` 也少一些方法。背景要两条 runtime 都做 |
| 页面 DOM（内置 `index.html` 与导入器的 `buildHtml()` 生成的页面**同构**） | `#stage > #background(z0) / #fallback(z1) / #live2dCanvas(z2) / #leftKey,#rightKey(z3)`，导入的 Mver 包还有 `#leftHand/#rightHand(z4)`；`cat` 布局的生成页面额外把 `#fallback/#leftKey/#rightKey` 压成 `display:none` |
| `domain/style/OverlayStyles.kt` `KEYBOARD_CAT` 的 `baseSize` | `width = LIVE2D_DESIGN_HEIGHT * contentAspect`，`height = LIVE2D_DESIGN_HEIGHT(=354)`；`contentAspect` 取模型记录，0 时退回 612×354 |
| `capture/OverlayService.kt` | `applyConfigToView()` 只做"不用重建页面就能生效"的改动（模型页 / 表情 / 去水印 / **`alpha`**）；`syncWindows()` 每次保存配置后对所有窗口 `applyLayout()` → `updateWindowSize()` → `applyPosition()`；`updateWindowSize()` 已经会在尺寸变化后重新夹位置 |
| `ui/preview/ConfigPreview.kt` | Live2D 分支直接 `return` 一个 `Live2DModelThumbnail`（`cover.png`→`icon.png`→占位图标，`ContentScale.Fit`） |

### 2.3 两个关键约束

1. **窗口尺寸 = 内容尺寸**。Live2D 的窗口宽高来自 `baseSize × uiScale + 6px×2 内边距`，
   而页面画布 = 窗口大小。所以"背景比模型大"这件事**必须**同步进 `baseSize`，
   否则多出来的背景永远看不见。
2. **不透明度现在是整层 alpha**。`OverlayService` 里有
   `live2d.alpha = config.live2d.opacityPercent/100f`（两处：创建时约 1109 行、
   `applyConfigToView` 约 1206 行）。要"只控模型不控背景"，这个 `alpha` 必须变成
   页内乘算，Android 侧 `alpha` 恒为 1。

---

## 3. 总体设计

```
                        ┌─────────────── 窗口（= 画布，尺寸随背景扩大） ───────────────┐
                        │                                                            │
                        │        ┌────────── 背景矩形（纯色，宽高 %） ──────────┐       │
                        │        │                                            │       │
                        │        │      ┌──── 模型内容矩形（基准，100%）────┐   │       │
                        │        │      │                                  │   │       │
                        │        │      │        canvas / 模型绘制         │   │       │
                        │        │      │                                  │   │       │
                        │        │      └──────────────────────────────────┘   │       │
                        │        └────────────────────────────────────────────┘       │
                        │                                                            │
                        └────────────────────────────────────────────────────────────┘
   背景宽 = 模型内容宽 × widthPercent/100，以模型内容中心为轴左右对称
   背景高 = 模型内容高 × heightPercent/100，上下对称
   窗口宽 = 窗口基准宽 × max(1, widthPercent/100)
   窗口高 = 窗口基准高 × max(1, heightPercent/100)
```

- 背景是**页面里的一个 DOM 层**（纯色 div），不是 Compose 画的。
  理由：模型是 WebGL 画的，背景要垫在它后面；用 `view.alpha` 之类的 Android 手段
  都没法只淡模型不淡背景，而页内一个 div 天然是独立的。
- 模型尺寸**不随**背景百分比变化（100% 背景 = 紧贴模型内容边界），
  变的是窗口和背景块。

---

## 4. 设计细节

### 4.1 数据模型

在 `KeyStrokesConfig.kt` 里、`Live2DSettings` 旁边新增：

```kotlin
/**
 * Live2D 的纯色背景。
 *
 * 默认关闭；关闭时字段仍然完整保存 —— 这样用户来回切开关，
 * 上一次调好的宽高/颜色不会丢（与项目里"颜色与不透明度分开存"同一思路）。
 */
data class Live2DBackground(
    /** 是否显示背景。默认关（老配置解码后也是关，外观零变化） */
    val enabled: Boolean = false,
    /** 背景宽 = 模型内容宽 × 这个百分比。20..200，100 = 紧贴模型内容边界 */
    val widthPercent: Int = 100,
    /** 背景高 = 模型内容高 × 这个百分比。20..200 */
    val heightPercent: Int = 100,
    /** 背景颜色，只存 RGB（0xRRGGBB，默认白）。不透明度由 [opacityPercent] 单独管 */
    val color: Long = 0xFFFFFF,
    /** 背景不透明度 0..100 */
    val opacityPercent: Int = 100,
)
```

`Live2DSettings` 增加一个字段（放最后，保持既有构造调用不用改）：

```kotlin
val background: Live2DBackground = Live2DBackground(),
```

不采用 `Live2DSettings` 里平铺 5 个字段的理由：配置类已经很长，5 个同进同出的字段
平铺会让 `copy(...)` 的调用点非常啰嗦；嵌套后"切背景开关"只碰一个对象。
（`hideWatermark` 那种单字段不值得嵌套，5 个字段值得。）

### 4.2 JSON 结构

`live2d` 对象里新增一个嵌套对象。颜色沿用项目惯例**存十进制 RGB 整数**
（与 `colors.keyUp` 等一致，导出说明里注明是 24 位 RGB）。

```json
"live2d": {
  "modelId": "builtin:keyboard",
  "opacityPercent": 100,
  "hideWatermark": true,
  "background": {
    "enabled": true,
    "widthPercent": 140,
    "heightPercent": 120,
    "color": 16777215,
    "opacityPercent": 85
  }
}
```

编码：**总是把 5 个字段都写出去**（与 `hideWatermark` 现有做法一致 ——
只写"非默认值"的话，用户调过再关掉开关，那组值就永久丢了）。

解码（`decodeLive2D` 里）：

```kotlin
background = json.optJSONObject("background")?.let { bg ->
    Live2DBackground(
        enabled = bg.optBoolean("enabled", false),
        widthPercent = bg.optInt("widthPercent", 100).coerceIn(20, 200),
        heightPercent = bg.optInt("heightPercent", 100).coerceIn(20, 200),
        color = bg.optLong("color", 0xFFFFFF).and(0xFFFFFF),
        opacityPercent = bg.optInt("opacityPercent", 100).coerceIn(0, 100),
    )
} ?: Live2DBackground()
```

- 越界一律 `coerceIn`（手工改过配置包的情况）—— 与 `CustomLayoutCodec` 的既有做法一致。
- 颜色 `and 0xFFFFFF` 抹掉可能的 alpha，避免"颜色里藏 alpha 又乘一次不透明度"
  （`docs/color-picker-plan.md` §3 的既有约定）。
- **不动 `schemaVersion`**：这是纯新增字段，旧版本读到会忽略；新版本读到旧的没有该字段
  → 默认关。

`ConfigJsonComments.kt` 同步：

- 顶层 `"live2d"` 的说明补上 `background`；
- 嵌套说明加一句：`live2d.background：纯色背景。enabled 开关；widthPercent/heightPercent 为模型内容尺寸的百分比（20..200，100 = 紧贴内容）；color 为 24 位 RGB（默认 FFFFFF）；opacityPercent 0..100。`

> `ConfigJsonCommentsTest` 会检查"每个预期字段都有说明"，漏了会直接报红。

### 4.3 配置页（`ConfigEditorScreen.kt` → `live2DStyleSections`）

分区顺序与新结构（`SectionHeader` 是分区标题，`SettingsCard` 是一张卡）：

```
[模型]                        ← 新增：由原「键盘猫」分区改名并移到最前
  SettingItem("当前模型", subtitle = 模型名, onClick = openPicker)

[外观]
  SliderRow("整体缩放", 50..150%)
  CardDivider
  背景 开关行（ExpressiveSwitch）
  ── 仅 enabled 时 ──
  CardDivider
  SliderRow("宽度", 20..200%，5% 一档)
  CardDivider
  SliderRow("高度", 20..200%，5% 一档)
  CardDivider
  HexColorRow("背景颜色", value = bg.color, onValueChange = …, alphaPercent = bg.opacityPercent, onAlphaChange = null)

[不透明度]
  SliderRow("模型", 20..100%)                 ← 语义收窄：只控模型
  ── 仅背景 enabled 时 ──
  CardDivider
  SliderRow("背景", 0..100%)

[表情]（模型自带表情时）
[水印]（模型声明了水印参数/部件时）
```

细则：

- **标题去重**：分区标题是「模型」，里面那行 item 别再叫「模型」，改叫 **「当前模型」**
  （副标题仍是模型名）。分区标题与 item 标题完全一样会显得像渲染重复。
- 开关行**不写副标题**（只有「背景」两个字 + 右侧 `ExpressiveSwitch`）——开源项目界面要简洁，
  这类说明性文字后面对其它分区也会一起删掉。
- 宽度/高度滑块 **5% 一档**（`steps = 35`，范围 20..200），`onValueChange` 取整后 `coerceIn`。
  背景不透明度同理 **5% 一档**（`steps = 19`）。
  为什么不给连续滑块：见 §4.10。
  显示与存储按 1% 粒度；想要精确值点右侧数字直接输入。
- 颜色行 `onAlphaChange = null`：颜色对话框里不出现 A 滑杆 —— 不透明度已经有独立滑块了，
  两个入口改同一个值只会让人以为"改了没生效"。
  `alphaPercent` 只用于色块预览（让用户看到半透明背景的实际观感）。
- 背景开关关闭时，宽高/颜色**仍然保存**，只是不显示（见 4.1）。
- `ConfigEditorScreen` 里分区 key（`expandedSections`）目前只给可折叠分区用；
  `live2DStyleSections` 用的是 `SectionHeader`（不可折叠），本次顺序变化不涉及 key 变更。

### 4.4 配置页预览（`ConfigPreview.kt` + 新几何工具）

现状是 Live2D 分支直接 `return` 一个缩略图。改成**合成**：

```
预览框（widthDp × heightDp，已有 6dp padding 与圆角边框）
└─ 背景色块（bgW × bgH，颜色 = bg.color，alpha = opacityPercent/100，圆角沿用外层）
   └─ 模型预览图（modelW × modelH，居中）
```

几何全部收进一个**纯函数**，方便单测（不要在 Composable 里散着算）：

新文件 `domain/live2d/Live2DBackgroundGeometry.kt`：

```kotlin
object Live2DBackgroundGeometry {
    const val WIDTH_MIN = 20; const val WIDTH_MAX = 200
    const val HEIGHT_MIN = 20; const val HEIGHT_MAX = 200

    /** 窗口基准尺寸（不含 scalePercent 与内边距）：背景 >100% 时把窗口一起撑大 */
    fun windowSize(aspect: Float, bg: Live2DBackground, designWidth: Float, designHeight: Float): Pair<Float, Float>

    /** 预览框内的三段尺寸：union / 模型 / 背景（顺序：外层 → 模型 → 背景） */
    data class PreviewRects(val modelWidth: Float, val modelHeight: Float, val bgWidth: Float, val bgHeight: Float)
    fun previewRects(boxWidth: Float, boxHeight: Float, aspect: Float, bg: Live2DBackground): PreviewRects
}
```

`previewRects` 的算法：

```
Wmax = max(1, widthPercent/100)
Hmax = max(1, heightPercent/100)
unionAspect = aspect × Wmax / Hmax
把 unionAspect 的矩形按 contain 塞进预览框 → unionW × unionH
modelW = unionW / Wmax ; modelH = unionH / Hmax      // 模型矩形（＝100% 基准）
bgW    = modelW × widthPercent/100 ; bgH = modelH × heightPercent/100
```

- `aspect` 取 `Live2DModels.find(modelId)?.contentAspect`（**不**用预览图自身的比例，
  否则"背景 100%"在预览里和悬浮窗里含义不一致）；为 0（还没测到 / 内置模型）时
  退回 `612f/354f`。
- 背景关闭 → 保持现状的分支（不合成，直接缩略图），预览外观零变化。
- 没有预览贴图的模型：`Live2DModelThumbnail` 自己会画占位图标；
  把它放进 `modelW × modelH` 的框里即可 —— 图标居中在**模型矩形**中心，
  而不是预览框中心（背景不对称时两者不同，这点必须对）。
- 预览图本身比例与 `modelW/modelH` 不一致时由 `ContentScale.Fit` 处理（会留白），
  这是预览图的固有问题，不做拉伸。

### 4.5 悬浮窗渲染：背景层

**位置：背景 div 插在 `#stage` 的**第一个子节点**（`z-index: 0`，DOM 顺序在最前）
—— 它就是**最底层**。也就是：

```
#solidBackground (新, z0, DOM 第一个)     ← 纯色，尺寸 = 背景矩形（独立不透明度）
└─ #modelLayer (z1，CSS opacity = 模型不透明度)
   #background (包自带底图, z0)            ← 跟随模型淡（见 §4.11）
   #fallback   (封面图, z1)               ← 跟随模型淡
   #live2dCanvas (模型, z2)               ← 跟随模型淡
   #leftKey/#rightKey/#leftHand/#rightHand/#faceLayer (z3/z4/z7)  ← 跟随模型淡
```

盖住模型就不叫背景了，所以纯色块只在**模型内容透明处**与"背景比模型大"留出的边距里可见；
包自带底图（若模型包有）仍原样显示在纯色之上。

⚠️ 背景开启时，`#background / #leftKey / #rightKey / #fallback` 会被 `syncDesignLayers()`
**钉回设计画布矩形**（否则会被拉满变大的窗口，桌子与角色错位，见 §4.11）。

DOM 由 JS 动态创建（`ensureBackgroundLayer()`，仿现有 `ensureFaceLayer()`），
**不改 `index.html`，也不改导入器的 `buildHtml()`** —— 老模型的页面是导入时生成的，
改模板只会影响新导入的模型，动态创建对存量模型同样生效。

### 4.6 不透明度解耦

现状：`view.alpha = opacityPercent/100`，整层（含背景）一起淡。

改成：

1. Android 侧 `Live2DOverlayView.alpha` **恒为 1f**，不再用 `alpha` 表达模型不透明度。
2. 新增 `fun applyOpacity(percent: Int)`；页面就绪时下发 `AxonBongoCat.setModelOpacity(percent)`，
   `flushAllToPage()` 里补发。
3. JS 侧用一层 **`#modelLayer` 容器 + CSS `opacity`** 做"整组合成"：
   - 容器把 `#fallback / #live2dCanvas / #leftKey / #rightKey / *#Hand / #handLayer / #faceLayer`
     搬进去（`modelLayerRoot()`），`setModelOpacity` 只改这一层的 `style.opacity`；
   - `#background`（包底图）与 `#solidBackground`（纯色底）**留在容器外** → 不受模型不透明度影响；
   - 画布里的 `uOpacity` **不再乘** `MODEL_OPACITY`（乘了就是逐层透明，正是"透视"的来源）。

> ⚠️ **踩过的坑（2026-11-28）**：第一版把 `MODEL_OPACITY` 乘进每个 drawable 的 `uOpacity`，
> 结果模型下半透明后能**清晰看到眼睛/头发等内部图层**（用户描述"像透过皮肤看到骨头"）。
> 原因：模型是"多层不透明图层叠起来"画的，逐层降 alpha 等于把每层都变半透明 ——
> 这不是"整体变淡"，而是"透视"。必须等模型内部按正常 alpha 合成完，再整层淡一次，
> 效果才与旧的 `view.alpha` 一致。
>
> 同理 DOM 贴图（手/键帽/表情）**也不能**各自设 `style.opacity` —— 它们之间有遮挡关系，
> 逐元素设一样会互相透出来。

4. `OverlayService` 两处 `alpha = …` 改成 `applyOpacity(…)`。

`applyConfigToView()` 因此新增两项（都是"不用重载页面"的改动）：

```kotlin
live2d.applyOpacity(config.live2d.opacityPercent)
live2d.applyBackground(config.live2d.background)
```

### 4.7 JS API（两条 runtime 同名同签名）

```js
window.AxonBongoCat = {
  …,

  /**
   * 纯色背景。enabled=false 时把层隐藏（不渲染、不占位）。
   * @param enabled        开关
   * @param widthPercent   模型内容宽的百分比 20..200
   * @param heightPercent  模型内容高的百分比 20..200
   * @param rgb            0xRRGGBB
   * @param opacityPercent 0..100
   */
  setBackground(enabled, widthPercent, heightPercent, rgb, opacityPercent) { … },

  /** 模型不透明度 0..100（只影响模型与手/键/表情贴图） */
  setModelOpacity(percent) { … },
}
```

- 两者都必须**幂等**且**在模型还没 init 完时也能记住**：改全局变量，
  渲染循环/resize 用它们；`setBackground` 里顺手 `updateBackgroundLayer()`（见 4.8）。
- 原生侧在 `flushAllToPage()` 里补发，顺序放在 `setHideWatermark` 之后即可。

### 4.8 JS 布局：背景矩形怎么算进 scale/offset

这是整个改动里唯一有"数学"的地方。**实际实现**（比最初设想的"并集再收尾"更稳）：

1. `resize()` 里先算两个放大系数
   `expandX = max(1, widthPercent/100)`、`expandY = max(1, heightPercent/100)`
   （背景关闭时都是 1）。
2. **各分支的 fit 按"放大后的框"算**：
   - 内置键盘猫 / 自带底图包：`stageScale = min(width/(612*expandX), height/(354*expandY))`
   - Mver 手部贴图包：`designScale = min(width/(designW*expandX), height/(designH*expandY))`
   - 导入 `cat`：`fit = min(width/(contentBounds.w*expandX), height/(contentBounds.h*expandY))`
   - 内置鼠标版（`standard/runtime.js`）：同第一条。
3. 各分支原有的 `offsetX/Y = (width - 原始尺寸*scale)/2 …` **写法不变** ——
   因为数学上"放大后的框居中"与"原始框居中"给出的左上角公式相同
   （推导：`(width - W*expand*s)/2 + W*(expand-1)*s/2 = (width - W*s)/2`）。
4. 统一的收尾 `finishLayout(width, height, basisPx)`：
   - `basisPx` = 该分支**原始内容矩形在画布里的像素矩形**（各分支直接给）；
   - `bgRect` = 以 `basisPx` 中心为轴、宽高乘百分比；
   - 只有两种例外才需要整体缩放（`k = min(1, width/union.w, height/union.h)`）：
     窗口被屏幕夹过、或页面先于窗口重排渲染了一帧；缩放以内容中心为不动点；
   - `BG_RECT = { x: cx - w*k/2, y: cy - h*k/2, w: w*k, h: h*k }`（**已是像素，不再乘 scale**）。

```js
// 收尾（与实现一致）
const bgWidth = basis.w * (BG_WIDTH / 100);
const bgHeight = basis.h * (BG_HEIGHT / 100);
const cx = basis.x + basis.w * 0.5;
const cy = basis.y + basis.h * 0.5;
const k = Math.min(1, width / Math.max(1, basis.w, bgWidth), height / Math.max(1, basis.h, bgHeight));
…
BG_RECT = { x: cx - bgWidth * k * 0.5, y: cy - bgHeight * k * 0.5, w: bgWidth * k, h: bgHeight * k };
```

**关键不变量**：背景关闭时 `expand = 1`、`k = 1`，`resize()` 算出来的 `scale/offset` 与加背景之前
**逐位相同** —— 这是唯一的回归风险点，已用截图验证（见 §11）。

各分支给的 `basisPx`：

| 分支 | basisPx（画布像素） | 说明 |
|---|---|---|
| 内置键盘猫（`native`） | 设计框：`stageOffsetX/Y + 612*stageScale × 354*stageScale` | 现有 `stageScale/stageOffset` 就是"把设计框 contain 进画布" |
| 内置鼠标版（`standard/runtime.js`） | 设计框（同上） | 只有一条分支 |
| 导入模型 `cat` | `contentBounds` 铺进画布后的矩形（居中） | 现有分支原样保留 |
| 导入模型 `full`（自带底图皮肤包） | 设计框（612×354 那个组合框） | `modelToDesign` 逻辑不动，只是 `stageScale` 按放大后的框算 |
| 导入模型 `HANDS`（Mver 手部贴图包） | 设计框：`designX/Y + designW*designScale × designH*designScale` | `designScale` 按放大后的框算；手部贴图用的是同一套 `designScale`，所以不会错位 |

> ⚠️ 回报给原生侧的 `contentAspect` 仍然是**模型内容本身**的比例（不是放大后的）：
> 那个值会写进模型记录、作为"这个模型多大"的长期依据；放大是**当前配置**的事，
> 由 Kotlin 侧按百分比再乘一次（见 §7）。写成放大后的会让"关掉背景后窗口不缩回去"。


### 4.9 背景层本身

```js
function ensureBackgroundLayer() {
  let el = document.getElementById('solidBackground');
  if (!el) {
    el = document.createElement('div');
    el.id = 'solidBackground';
    el.style.position = 'absolute';
    el.style.pointerEvents = 'none';
    el.style.zIndex = '0';
    el.style.display = 'none';
    const stage = document.getElementById('stage') || document.body;
    // ⚠️ 插在 stage 的**第一个**子节点：同 z-index 时 DOM 顺序在前的在下层，
    // 这样它垫在包自带底图（#background）下面 —— 背景就该是最底层。
    stage.insertBefore(el, stage.firstChild);
  }
  return el;
}

function updateBackgroundLayer() {
  const el = ensureBackgroundLayer();
  if (!BG_ENABLED || !BG_RECT) { el.style.display = 'none'; return; }
  el.style.display = 'block';
  const dpr = Math.max(1, devicePixelRatio || 1);
  el.style.left   = (BG_RECT.x / dpr) + 'px';
  el.style.top    = (BG_RECT.y / dpr) + 'px';
  el.style.width  = (BG_RECT.w / dpr) + 'px';
  el.style.height = (BG_RECT.h / dpr) + 'px';
  el.style.backgroundColor = `rgba(${r}, ${g}, ${b}, ${BG_OPACITY / 100})`;
}
```

- 尺寸变化（窗口 resize、缩放改变）都要重算 → 在 `layout` 收尾里和 `setBackground()` 里调。
- 日志：`[background] 开启 140%×120% #FFFFFF 85%`，和 `[watermark]` 的风格一致，
  用户反馈时看日志就能确认参数到了页面里。
  ⚠️ 只在**开关状态变化**时打一条：拖滑块时每帧打日志会拖慢 UI（logcat 是同步写入）。
- ⚠️ 实现时踩过的坑（已修）：`BG_RECT` 的输入 basis **已经是画布像素**，
  不能再乘一次 `this.scale` —— 否则背景会被缩成一个点，看起来像"开关没反应"。

### 4.10 性能：拖滑块为什么卡 / 怎么修的

拖滑块时的每一次取值都会走完整条链路：

```
Compose onValueChange → updateConfigById（存盘：整份配置 JSON 序列化 + prefs 写入）
  → OverlayService 刷新（跨进程 Intent）
  → applyConfigToView（JS 桥下发）
  → updateWindowSize（宽高变化时重排窗口）→ WebView resize / 画布重建
```

连续滑块一秒能触发几十次，于是"存盘 + 跨进程 + 跨桥 + 窗口重排"全被打满 → 明显卡顿。
四处修：

| 修法 | 位置 | 效果 |
|---|---|---|
| **滑块加 5% 步长** | `ConfigEditorScreen`（宽/高 `steps=35`，背景不透明度 `steps=19`） | 一档一次，次数直接除以 5 |
| **值没变就短路** | `MainViewModel.updateConfigById`：`transform(before) == before` 直接返回 | 带步长的滑块会把同一档位反复回调，挡掉绝大部分无效"存盘+刷新" |
| **相同值不下发** | `Live2DOverlayView.applyBackground / applyOpacity`：与上次相等就 return | 少一次 `evaluateJavascript` 跨桥 |
| **合并下发** | `Live2DOverlayView` 的 `post(renderSettingsRunnable)`：一帧内多次变化合成一次 | 背景 + 不透明度只发一次 |
| **JS 侧只做必要工作** | `setBackground`：只有开关/宽/高变了才 `renderer.resize()`；只改颜色/透明度时只改 div | 拖不透明度不再重排画布 |
| **去掉每帧日志** | 两条 runtime 的 `setBackground` / `setModelOpacity` | logcat 同步写很贵，尤其 slider 拖动时 |

> 实测（模拟器，窗口开着，拖宽度滑块）：一次拖动只产生 **2–3 次**窗口重排
> （修前是每一次取值都重排），滑块数值也正确停在 5% 的整数档（例：145%、150%）。

### 4.11 包自带底图 / 键帽的对齐（2026-11-28 补）

**现象**（用户报，样本 `超天酱_bongo_cat_mver_0.1.6_64.zip`）：开启背景后，
**桌子始终贴在背景最下方、和角色错位**。

**根因**：这类 Mver 包的「桌子/键盘」不是画在 moc 里，而是作者按设计画布
（`__BONGO_DESIGN`，如 612×352）画的整幅 `bg.png`，页面里是
`#background{inset:0;width:100%}` —— **默认铺满整个窗口**。
背景把窗口按百分比撑大之后，"铺满窗口"就把这张图横向/纵向拉长了，
而模型与手部贴图仍按设计画布居中 → 桌子被拉宽、贴到窗口底部，与角色错位。

**修法**：新增 `syncDesignLayers(rect)`，把**按设计画布绘制的整幅图层**钉回
"设计画布那块矩形"（单位与模型同源，并按 k 缩放；背景关闭时清掉内联样式回到"铺满窗口"）：

| 图层 | 说明 |
|---|---|
| `#background` | 包自带底图（桌子 / 键盘） |
| `#leftKey` / `#rightKey` | 按键时盖上去的键帽贴图 |
| `#fallback` | 加载期间显示的封面 |

矩形来自 `finishLayout` 的 `basis`（= 设计画布 / 内容矩形在画布里的像素矩形），
多出来的边距就自然留给纯色背景。两条 runtime（keyboard / standard）都实现。

> 顺带确定了另一个语义：**包自带底图属于"模型"**（它是这个模型长什么样的一部分），
> 所以 `#background` 被放进 `#modelLayer` 容器，**跟着模型不透明度一起淡**；
> 只有新加的纯色底是独立的、走「背景不透明度」。
> 这样模型调到 40% 时是"角色+桌子一起变淡、底板仍实心"，而不是"角色幽灵、桌子实心"。


---

## 5. 预览贴图（R7）的实现说明

「预览贴图」实际有三个来源，优先级**已经**在 `Live2DModelThumbnail.loadThumbnail()` 里定好：

1. `preview/<pageDir>.png`（运行时抓帧；`Live2DPreviewStore` 有读写工具，但**目前没有写入方**
   —— 已知缺口，本方案不处理，见 §10）；
2. 模型包里的 `preview.png`（Mver 包常见，按模式目录 → 根目录顺序找）；
3. `cover.png`（皮肤包）→ `icon.png`（VTube Studio 缩略图）；
4. 都没有 → 占位图标。

本方案要做的只有两件事：

- **尺寸**：把缩略图放进"模型矩形"（`modelW × modelH`）里，而不是预览框里；
- **层次**：背景块画在缩略图**下面**，且背景块尺寸遵循百分比。

这样"有预览贴图的模型"在预览里看到的就是"底板 + 缩略图"，与悬浮窗的相对比例一致。
（不新起 WebView：一个一百多 dp 的框再养一个 WebView 不值，这是现状注释里已经写明的取舍。）

---

## 6. 改动清单（按文件）

### 6.1 新增

| 文件 | 内容 |
|---|---|
| `domain/live2d/Live2DBackgroundGeometry.kt` | 纯函数：`windowSize(...)`、`previewRects(...)`、范围常量 |
| `app/src/test/.../Live2DBackgroundGeometryTest.kt` | 见 §8 |
| `app/src/test/.../Live2DBackgroundCodecTest.kt` | 见 §8 |
| `docs/live2d-background-plan.md` | 本文 |

### 6.2 修改

| 文件 | 改动 |
|---|---|
| `domain/config/KeyStrokesConfig.kt` | 新增 `Live2DBackground`；`Live2DSettings` 加 `background` 字段 |
| `data/config/JsonConfigCodec.kt` | `put("live2d", …)` 里加 `put("background", JSONObject…)`；`decodeLive2D` 里解析 + clamp |
| `data/config/ConfigJsonComments.kt` | `live2d` 的说明 + 嵌套说明 |
| `domain/style/OverlayStyles.kt` | `KEYBOARD_CAT` 的 `baseSize`：取 `Live2DBackgroundGeometry.windowSize(aspect, bg, 612, 354)` |
| `ui/overlay/live2d/Live2DOverlayView.kt` | 新增 `applyOpacity(percent)`、`applyBackground(bg)`（记字段 + `pageReady` 时 `dispatchRaw`）；`flushAllToPage()` 补发这两项 |
| `capture/OverlayService.kt` | ① `applyConfigToView` 改用 `applyOpacity` + `applyBackground`；② 创建视图处去掉 `alpha = …`；③ `applyPosition` 增加"模型内容锚定"补偿（见 §7.3） |
| `ui/feature/config/ConfigEditorScreen.kt` | `live2DStyleSections` 分区顺序重排 + 新控件 |
| `ui/preview/ConfigPreview.kt` | Live2D 分支合成"背景 + 缩略图" |
| `assets/bongocat/keyboard/runtime.js` | `MODEL_OPACITY`、背景层、`resize()` 统一布局、两个新 API |
| `assets/bongocat/standard/runtime.js` | 同上（精简版：只有设计框一条基准） |

> 导入器的 `buildHtml()` **不用改**（背景层动态创建）。

---

## 7. 窗口尺寸与位置

### 7.1 窗口尺寸公式

`OverlayStyles.KEYBOARD_CAT.baseSize`：

```
aspect = 模型记录里的 contentAspect（0 时按 612/354）
Wmax = 背景开启 ? max(1, widthPercent/100)  : 1
Hmax = 背景开启 ? max(1, heightPercent/100) : 1
baseWidth  = LIVE2D_DESIGN_HEIGHT(354) × aspect × Wmax
baseHeight = LIVE2D_DESIGN_HEIGHT(354) × Hmax
```

- 背景关闭 / 宽高都是 100% → 与现状**完全一致**（回归风险最低）。
- 窗口最终 px = `baseSize × KeyLayout.uiScale(config) + 6px×2`（既有逻辑不动）。
- 例：`aspect=1.0`、`W=150%`、`H=100%`、`uiScale=1`、density 2.625
  → base 531×354 → 窗口 ≈ 531×2.625+12 ≈ 1406×941px。
  （`W/H` 上限 200% 时窗口可能超出屏幕；夹边界由 `applyPosition`/`OverlayBounds.clamp` 兜住，
  超出时模型不会消失，只会贴着屏幕边。见 §7.4 的取舍。）

### 7.2 为什么窗口必须跟着变大

背景是画布里的 DOM 层，超出画布的部分会被 `#stage{overflow:hidden}` 裁掉；
而画布尺寸 = 窗口尺寸。所以"背景宽 140%"若不同步窗口，用户看到的是"背景没变宽"。

### 7.3 位置锚定：模型在屏幕上不能动

现在位置 = `layout.base + offset`（窗口**左上角**），窗口变大时内容整体向右下漂。
背景宽高是对称扩展，用户拖滑块时模型必须**原地不动**，否则感觉猫在自己爬。

做法：Live2D 样式在 `applyPosition` 里把锚点从"窗口左上角"换成"**模型内容矩形左上角**"：

```
windowW  = 当前窗口宽（含背景扩展）
contentW = 背景关闭时的窗口宽（= 模型内容宽 + 内边距）
insetX   = (windowW - contentW) / 2
insetY   = (windowH - contentH) / 2
x = layout.positionX(defaultX) - insetX     // 再走 OverlayBounds.clamp
y = layout.positionY(defaultY) - insetY
```

- 背景关闭时 `inset = 0` → 与现状完全一致。
- 这是一处**只影响 Live2D** 的分支（其它样式内容 = 窗口，inset 恒为 0），
  实现上可以做成 `OverlayStyleRegistry` 的一个可选回调，或者 `applyPosition` 里按样式判断；
  推荐前者（样式自己知道"内容比窗口小多少"，与 `baseSize` 同一个真源）。
- 夹边界仍然只走 `OverlayBounds.clamp` 一处，不新增第二套边界逻辑。
- 拖动保存的 `baseX/baseY` 语义**不变**（它存的是模型内容的位置），已存的用户位置不会跳。

### 7.4 屏幕上限

`W/H` 上限 200% 时，超大模型（`aspect` 很宽）乘出来可能超屏。取舍：

- **不做**尺寸截断（截断会让"200% 与 150% 看起来一样"，是假功能）；
- 靠 `OverlayBounds.clamp` 让它贴边，并在日志里记一条
  `窗口尺寸 … 超过屏幕 …`，调试页可见；
- 用户觉得太大时用「整体缩放」压回来（两者是相乘关系）。

---

## 8. 测试计划

### 8.1 单元测试（`app/src/test`）

| 测试 | 断言 |
|---|---|
| `Live2DBackgroundCodecTest` | ① 默认 `Live2DBackground()` = 关 / 100 / 100 / FFFFFF / 100<br>② 编码后 `live2d.background` 五个字段齐全（含 enabled=false 时）<br>③ 解码缺字段 → 默认；越界 → clamp（宽高 20..200，不透明度 0..100）<br>④ 颜色带 alpha（如 `0xFF123456`）→ 抹成 `0x123456`<br>⑤ 2.8.7 的配置 JSON（无 background）解码后 `enabled=false` 且其它字段零变化 |
| `Live2DBackgroundGeometryTest` | ① `windowSize`：关背景 = 现状值（354×aspect, 354）；W=150 → 宽×1.5，高不变；H=150 → 高×1.5<br>② `previewRects`：W=H=100 时 `model == 预览框 contain 后的尺寸`，`bg == model`<br>③ 对称性：`bgWidth/modelWidth == widthPercent/100`（±0.01）<br>④ W=300（越界）被 clamp 到 200<br>⑤ aspect 退化（0/NaN）→ 用 612/354 |
| `ConfigJsonCommentsTest`（既有） | 自动覆盖：`live2d.background` 没有说明会报红 |
| `StyleSpecificFieldsTest`（既有） | 应保持通过（`live2d` 门控粒度不变） |
| `OverlayPositionAnchorTest`（新增，若 7.3 做成纯函数） | 背景关闭 inset=0；W=150 时 `x` 左移 `windowW-contentW)/2`，且 clamp 后不出屏 |

### 8.2 设备验证（模拟器即可，日志判据写清楚）

准备：内置键盘猫 + 导入的 `清宵`（`cat` 布局、无包底图）+ 一个带 `#background` 底图的 Mver 包。

| # | 操作 | 期望 |
|---|---|---|
| V1 | 打开 Live2D 配置页 | 第一个分区是「模型」；「外观」里能看到「背景」开关（关） |
| V2 | 打开背景开关 | 出现宽度/高度/颜色三行；悬浮窗出现纯色底板，日志 `[background] 开启 100%×100% #FFFFFF 100%` |
| V3 | 改颜色为 `FF0000` | 悬浮窗底板立即变红（不重载页面）；首帧无白闪 |
| V4 | 宽度 100→150 | 底板**左右同时**变宽；窗口宽变大（日志 `窗口尺寸 …` 宽变大、高不变）；**模型在屏幕上的位置不动** |
| V5 | 高度 150 | 底板上下同时变高；窗口高变大 |
| V6 | 背景不透明度 100→40 | 底板变淡，**模型不变淡**；日志 `[background]` 带新值 |
| V7 | 模型不透明度 100→20 | **模型变淡、底板不变**（关键验收：旧的 `view.alpha` 做法下两者会一起淡） |
| V8 | 关掉背景开关 | 底板消失、窗口缩回原尺寸、模型回原位；再开回来宽高/颜色仍在 |
| V9 | 配置页预览 | 有预览贴图的模型显示"底色块 + 缩略图"；无预览图的模型是"底色块 + 居中占位图标"；比例与悬浮窗一致 |
| V10 | 内置鼠标版（`builtin:standard`） | 同样生效（这条走 `standard/runtime.js`，别漏测） |
| V11 | 带包底图的 Mver 包 | 开关打开后包底图仍照常显示，纯色只在包底图透明处 / 边距里可见（背景垫最底层的表现） |
| V12 | 去水印回归 | 清宵：水印仍隐藏且模型层叠正常（`[watermark] 已隐藏图层 [311] opacity=0`） |
| V13 | 老配置回归 | 用 2.8.7 导出的配置包导入 → 背景关、外观与以前一致；导出再导入字段不丢 |
| V14 | 截图对比 | 背景关时与改动前的同模型截图**像素级一致**（防止 resize 重构引入偏移） |

> V14 是最重要的一条：`resize()` 三段统一是本方案唯一有回归风险的地方，
> 必须用"背景关闭"的截图做基线对比（参考上一轮层叠顺序修复的做法：Pillow 算差异百分比）。

---

## 9. 实施顺序（建议 4 个提交）

1. **P1 数据层**：`Live2DBackground` + JSON 编解码 + 注释 + 单测（`Live2DBackgroundCodecTest`）。
   —— 此时界面上看不到变化，但导出配置里已经带字段，可先验证"老配置零变化"。
2. **P2 渲染层（JS + Android 接线）**：两条 runtime 的背景层 / `setBackground` /
   `setModelOpacity` / `resize()` 统一 / `Live2DOverlayView` 新方法 /
   `OverlayService` 去 `alpha` + `updateWindowSize` 联动 / `OverlayStyles.baseSize`。
   —— 这一步完成后，**用调试代码往 JS 灌参数**就能在悬浮窗上看到背景，
   可以先在模拟器上把 V2/V4/V5/V6/V7/V10/V11/V14 全部验一遍，再动 UI。
3. **P3 配置页 UI + 预览**：分区重排、开关/滑块/颜色行、`ConfigPreview` 合成、
   `Live2DBackgroundGeometryTest`。
4. **P4 收尾**：§8 全量验证、回归（去水印 / 层叠顺序 / 老配置）、
   下一个版本公告（`AppAnnouncement`）补一条「Live2D 背景」。

---

## 10. 风险 / 已知缺口

| 风险 | 说明 | 应对 |
|---|---|---|
| `resize()` 重构影响现有布局 | 四个分支的 `scale/offset` 都要按"放大后的框"算 fit，`full`/`HANDS` 还有额外偏移 | 用 V14 像素级回归（实测：窗口 366×366、位置 (42,315) 与改动前完全一致，模型位置/大小一致）；P2 单独提交，出问题可整段回退 |
| 窗口变大导致拖动越界 | 宽高 >100% 时窗口可能超屏 | `OverlayBounds.clamp` 兜底 + 日志；建议 >150% 时配合「整体缩放」 |
| 实时拖动滑块触发窗口重排 | 连续滑块每一步都可能改窗口尺寸，`updateWindowSize` 内部有"尺寸没变就返回" | 实测拖动流畅；若以后卡顿再在 `onValueChangeFinished` 节流 |
| `Live2DPreviewStore` 没有写入方 | 运行时抓帧的 `preview/<mode>.png` 永远不存在 | 本方案不碰；以后要做"运行时预览图"，需在 `Live2DOverlayView` 加抓帧，是独立需求 |
| 模型不透明度语义变化 | 以前整层淡（含包底图），现在只淡模型 | 与需求 R6 一致；公告里写清楚 |

---

## 11. 决策记录（2026-11-28 已确认，无开放问题）

1. **决策 A**：背景垫在最底层（不盖包自带底图）。"盖住模型就不叫背景了"。
2. **决策 B**：背景 >100% 时窗口跟着变大，模型在屏幕上原地不动。
3. **宽高范围**：20%–200%，**5% 一档**（连续滑块实测卡顿），100% = 紧贴模型内容边界。
4. 「模型」分区里那行 item 标题用「当前模型」。
5. 新增 UI **不写副标题**（开关/滑块只有标题本身）。

### 实现完成后的实测记录（模拟器，清宵 / 内置键盘猫）

| 项 | 结果 |
|---|---|
| 背景关闭回归 | 窗口 366×366、位置 (42,315) 与改动前**完全一致**；并排截图模型位置/大小一致（差异只来自眨眼动画与系统主题） |
| 背景 100% 纯色 | 窗口尺寸不变，纯色只在模型透明处可见 |
| 背景 150%×120% | 窗口 543×543 / 930×437 按百分比变大；模型内容中心不动（y 补偿生效，x 在屏幕左缘被 clamp） |
| 模型不透明度 40% | 模型**整层**半透明、内部图层不互相透出（见下方"透视"那条）、背景仍 100% 不透明 |
| 模型不透明度 40%（背景关） | 与旧 `view.alpha` 观感一致：整个模型均匀变淡，后面的界面透出来 |
| 背景不透明度 40% | 背景半透明（透出后面的桌面）、模型仍不透明 |
| 关掉背景 | 窗口立刻缩回、三行设置消失、参数仍保留在配置里 |
| 配置页预览 | 内置键盘猫（自带 `cover.png`）显示"纯色底 + 预览图"；无预览图的模型显示占位图居中在模型矩形 |
| 去水印回归 | `[watermark] 已隐藏图层 [311] opacity=0`，层叠正常 |
| 滑块步长 | 拖宽度滑块后停在 145% / 150% 这类 5% 整数档；一次拖动只产生 2–3 次窗口重排 |
| 「透视」问题（已修） | 第一版逐 drawable 乘 alpha → 半透明时能看见眼睛/头发等内部图层；改为 `#modelLayer` 整层 CSS opacity 后，与旧版观感一致 |
| 包底图错位（已修） | 样本 `超天酱_bongo_cat_mver_0.1.6_64`：背景开启后桌子被拉满窗口、贴到最下方 → `syncDesignLayers()` 钉回设计画布矩形后，桌子与角色对齐、边距由纯色填充；背景关闭时恢复"铺满窗口"原样 |
| 包底图与不透明度 | 模型不透明度 40% + 背景开启：角色与桌子**一起**变淡、纯色底仍实心（与旧 `view.alpha` 的手感一致） |
