# Live2D（键盘猫）样式

这份文档是 Live2D 样式的**唯一设计依据**：为什么这么做、契约是什么、哪里不能改。
改动这个子系统之前先读这里，改完如果契约变了，回来同步。

参考实现（**不在本仓库**，只是本地参考）：
`D:\Project\guoqudebanben\Sth-Android-KeyStrokes-2.1_HomeCPSOverlayFix_Source`

---

## 1. 已定的决策

| 事项 | 结论 |
|---|---|
| 渲染路线 | **WebView + Live2D Cubism Core**，照搬参考实现，适当变通 |
| 功能范围 | **只内置两套模型**（keyboard / standard）。用户导入模型、模型库、配置包携带模型 **本版不做** |
| 与按键显示的关系 | Live2D 是一种**样式**，与按键显示二选一（一个配置只会是其中一种） |
| 鼠标 | **做**：左右键 + 光标跟随/位移（EV_REL） |
| 手柄 / 触屏 | **不做**（参考实现里有，属于另一个特性，本版不接） |
| 资产路径 | 保持 `assets/bongocat/`，**不改名**（原因见第 3 节） |

---

## 2. 为什么用 WebView，而不是原生 Cubism SDK

参考实现已经验证过这条路：Kotlin 只负责**输入状态、拖动、配置**，渲染全部交给 JS。
原生 SDK 需要 `.so` + JNI + CMake/NDK，而本项目**无法编译验证原生部分**，出问题排查成本极高。
WebView 的代价是内存（约 30–60 MB）与耗电更高，接受。

**关键点：JS 侧一行都不用改。** 上游文件保持字节一致 —— 许可证上更干净
（专有 js 要求"原样转发"），将来照 BongoCat 上游更新也方便。

---

## 3. 资产布局（路径不能改）

```
app/src/main/assets/bongocat/
  BONGOCAT_LICENSE.txt          MIT（© 2025 ayangweb）
  live2dcubismcore.min.js       Live2D Cubism Core（专有软件，不得修改）
  keyboard/                     键盘版：猫在敲键盘
    index.html                  入口页
    cat.model3.json?            模型描述（keyboard 用的是 demomodel2）
    demomodel2.moc3 / .1024/    模型本体 + 贴图
    mocdata.js                  base64 内嵌的 moc3
    runtime.js                  渲染与状态机（26 KB）
    resources/                  background.png / cover.png / left-keys / right-keys
  standard/                     鼠标版（结构和 keyboard 相同）
```

⚠️ **`keyboard/runtime.js` 第 8 行写死了绝对路径**：

```js
const BUILTIN_RESOURCE_BASE = 'file:///android_asset/bongocat/keyboard/resources';
```

它是内置模型找不到资源时的回退地址。**所以资产必须放在 `assets/bongocat/` 下**，
改成 `assets/live2d/...` 会让回退加载断掉。这是"不要为了命名整齐去动上游文件"的典型例子。

被丢弃的文件：`live2d.min.js`（126 KB，Cubism 2.1 运行时）—— 全仓库**没有任何引用**，
是上游的历史遗留。不要拷进来。

---

## 4. JS 桥接契约

宿主通过 `WebView.evaluateJavascript` 调这些全局函数（`window.AxonBongoCat`）：

| 函数 | 参数 | 说明 |
|---|---|---|
| `key(name, pressed)` | `name` 是语义名（见第 6 节），`pressed` 布尔 | 按键 |
| `mouseButtons(mask)` | `1`=左键，`2`=右键，按位或 | 鼠标按键。**不要**走 `key()` |
| `mouseDelta(dx, dy, w, h)` | 位移量 + 屏幕宽高（像素） | 相对位移，JS 内部累加成归一化坐标 |
| `pointerRatio(x, y)` | `0..1` | 绝对坐标。本版用不到（触屏/数位板才需要） |
| `setGlobalReverse(b)` | 布尔 | 左右镜像 |
| `clear()` | — | 清空全部按键与光标状态 |
| `isReady()` | — | 渲染器是否就绪（`state.ready`） |

---

## 5. 模型参数契约

runtime.js 会去改这些参数，**参数不存在时 `setOverride` 是空操作**（不会报错）：

- **必需**（决定按键有没有反应）：`CatParamLeftHandDown`、`CatParamRightHandDown`
- 可选（鼠标/视线）：`ParamMouseX`、`ParamMouseY`、`ParamAngleX/Y/Z`、`ParamEyeBallX/Y`
- 可选（鼠标键）：`ParamMouseLeftDown`、`ParamMouseRightDown`
- 内部驱动（眨眼/呼吸，宿主不用管）：`ParamEyeLOpen`、`ParamEyeROpen`、`ParamBodyAngleX`、`ParamBreath`

**本版只用 `moc3` + 贴图**：模型自带的 `exp3.json`（表情）、`motion3.json`（动作）、
`live2d_motion1.flac`（音效）**都没有被使用**。所以不要指望表情和动作。

### 两套内置模型实际有哪些参数（查自 cdi3.json）

| 参数 | keyboard（demomodel2） | standard（demomodel） |
|---|---|---|
| `CatParamLeftHandDown` / `RightHandDown` | ✅ | ✅ |
| `ParamMouseX` / `ParamMouseY` | ❌ **没有** | ✅ |
| `ParamMouseLeftDown` / `RightDown` | ❌ 没有 | ✅ |
| `ParamAngleX/Y/Z`、`ParamEyeBallX/Y` | ✅ | ✅ |
| `ParamBodyAngleX/Y/Z`、`ParamBreath` | ✅ | ✅ |

含义：**鼠标位移在两个模型上都有可见反应** —— 鼠标模型靠 `ParamMouseX/Y`，
键盘模型没有那两个参数，但头与眼球（`ParamAngle*` / `ParamEyeBall*`）**是有的**，所以头会转。
`setOverride` 对不存在的参数是空操作，因此"多发几个参数名"永远安全。

> ⚠️ 与旧项目的一处**刻意差异**：旧项目只在鼠标模型下转发位移
> （`addMouseMotion` 里有 `if (!mouseMode) return`）。我们**两个模型都转发** ——
> 因为键盘模型也有 Angle / EyeBall，让头跟着转更自然。若觉得键盘模式下头部晃动干扰，
> 加一个 `if (!mouseMode) return` 即可，是**一处**改动。

设计尺寸 `DESIGN_WIDTH=612` / `DESIGN_HEIGHT=354` 定义在 runtime.js 里，
JS 会把舞台**等比缩放并居中**（letterbox）—— 意味着**画布给任意尺寸都不会变形**，
我们的窗口尺寸不必正好是 612×354。

---

## 6. 按键语义映射（evdev 键码 → 语义名）

参考实现在 `KeyboardCatOverlayView.sourceKeyName()` 里硬编码了一张表。
我们把它放到 `domain/live2d/`，因为它是**领域知识**，不是 UI 细节（配置页以后也可能要用）。

字母：`16-25 → KeyQ..KeyP`，`30-38 → KeyA..KeyL`，`44-50 → KeyZ..KeyM`
数字：`2-11 → Num1..Num9, Num0`
其它：`1 Escape` `14 Backspace` `15 Tab` `28 Return` `29 ControlLeft` `41 BackQuote`
`42 ShiftLeft` `53 Slash` `54 ShiftRight` `55 Fn` `56 Alt` `57 Space` `58 CapsLock`
`97 ControlRight` `100 AltGr` `103 UpArrow` `105 LeftArrow` `106 RightArrow`
`108 DownArrow` `111 Delete` `125/126 Meta` `59-68,87,88 → Fn`

鼠标左右键（`272/273`）**不进 `key()`**，走 `mouseButtons()` ——
否则它们会污染 JS 内部"最近按下的键"的恢复顺序。

---

## 6.5 配置模型：Live2D 单独一块

Live2D 与按键显示是**两套完全不同的实现**，设置项没有一项是共用的，所以配置上分开：

```kotlin
data class KeyStrokesConfig(
    ... 按键样式那一整套（键位/颜色/描边/圆角/动画/CPS/字体…）
    val live2d: Live2DSettings = Live2DSettings(),   // ← Live2D 专用
)

data class Live2DSettings(
    val mouseMode: Boolean = false,      // false=右侧方向键（键盘模型），true=右侧鼠标
    val opacityPercent: Int = 100,       // 20..100，整层透明度
)
```

"整体缩放"是**共用**的（`scalePercent`）：它对两种样式含义相同，都是把内容与窗口一起放大。

**编辑页按样式分流**（`ConfigEditorScreen`）：

| 分区 | 按键样式 | Live2D |
|---|---|---|
| 基本信息（名称/描述/预览） | ✅ | ✅ |
| 外观（整体缩放） | ✅ | ✅ |
| 透明度 | ✅ 键帽/文字/描边三项 | ✅ 只有一项（整层） |
| 键盘猫（右侧方向键/鼠标） | — | ✅ |
| 颜色 / 描边 / 圆角 / 行为 / 动画 / CPS / 键位映射 / 字体 | ✅ | ❌ 一律不显示 |
| 其它（重置 / 删除） | ✅ | ✅ |

按键那套设置对一只猫**完全不起作用**，显示出来只会让用户对着十几个改了没反应的选项发呆。

实现上为了不动那 400 多行已有代码，`else` 分支里的缩进保持原样（少一级）——
纯粹是把改动压到最小，逻辑与结构不受影响。

### ⚠️ 重置参数：这里踩过一个"两份实现"的坑

"重置参数"这件事原本在**两个地方各写了一遍**，而且各自都列了一整份字段清单：

| 实现 | 状态 | 问题 |
|---|---|---|
| `ConfigStore.resetToDefault` | 实际**没人调用** | — |
| 配置编辑页重置对话框里内联的 `copy(...)` | **真正在跑的就是它** | 写了 `styleId = preset.styleId` → 重置 Live2D 配置会把它变回按键样式；且漏了 `live2d`，Live2D 自己的设置重置不掉 |

上一轮我改的是"没人调用"的那份，所以毫无效果 —— 教训是**先确认调用路径再改，不要假设**。

现在只有**一个实现**：`KeyStrokesConfig.resetParamsToDefault()`（domain 层），
`ConfigStore` 与编辑页都走它。身份字段（id / 描述 / styleId）**故意不列出来**（copy 不写即保持原样），
避免以后又有人顺手写成 `styleId = preset.styleId`。

> 通用教训：任何"必须枚举全部配置字段"的清单，**全项目只能有一份**。
> 加字段时漏改其中一份不会有编译错误，只会表现为"某个设置重置不掉"这种很难查的现象。

---

## 7. 要动的既有文件

| 文件 | 状态 | 改动 |
|---|---|---|
| `domain/style/OverlayStyles.kt` | ✅ P1 | `KEYBOARD_CAT` 置 `enabled = true`；新增 `OverlayBaseSize` 与 `baseSize`，**尺寸由样式自己声明**；删掉了没有引用的 `OverlayRendererSpec` 占位接口 |
| `ui/overlay/OverlayContent.kt` | ✅ P1 | 窗口尺寸改走 `OverlayStyleRegistry.baseSizeOf`；**只画按键样式**（Live2D 不走 Compose） |
| `domain/style/KeyLayout.kt` | ✅ P1 | 删掉 `windowWidthPx` / `windowHeightPx`（有了第二种样式后不能再由按键布局单独决定窗口尺寸，留着会被误用） |
| `ui/preview/ConfigPreview.kt` | ✅ P1 | Live2D 样式不再画**错误的**按键网格，改为一句说明 |
| `ui/overlay/live2d/Live2DOverlayView.kt` | ✅ P1 | 新增：FrameLayout + WebView 宿主（直接作为窗口根视图） |
| `capture/OverlayService.kt` | ✅ P1 | 按样式造根视图 + 样式切换时重建；窗口加 `FLAG_HARDWARE_ACCELERATED` |
| `domain/config/KeyStrokesConfig.kt` + `JsonConfigCodec` | ✅ 本轮 | 新增 `live2d: Live2DSettings`（`mouseMode` / `opacityPercent`） |
| `ui/feature/config/ConfigEditorScreen.kt` | ✅ 本轮 | 编辑页按样式分流，Live2D 只显示自己那几项 |
| `data/config/ConfigStore.kt` | ✅ 本轮 | `resetToDefault` 不再连样式一起重置 |
| `capture/CaptureController.kt` | ⬜ P3 | `EV_REL` 现在是空占位分支，接上鼠标位移（按键不需要改它：`pressedKeys` 早就在流里了） |
| `domain/live2d/Live2DSemantics.kt` | ✅ P2 | 新增：evdev 键码 → BongoCat 语义名 + 左右侧判定 |
| `capture/OverlayService.kt` | ✅ P2/P3 | `startKeyFeed()`：订阅 `pressedKeys` 与 `mouseMotion` 推给 Live2D 宿主 |
| `capture/CaptureSession.kt` | ✅ P3 | 新增 `mouseMotion` 累计值（永不重置、无订阅者时不累加） |
| `capture/CaptureController.kt` | ✅ P3 | `EV_REL` 分支接上（原来是个空占位） |

---

## 7.1 P1 踩到的坑（改这块之前务必看）

**1. ❌ 不要用 Compose 的 `AndroidView` 托管 WebView —— 实测渲染不出来。**

第一版是把 WebView 塞进 Compose 的互操作层（`AndroidView`），结果是**悬浮窗什么都不显示**，
而且没有任何日志能说明是哪一层没画。旧项目（社区版）用的是另一条路：
**FrameLayout + WebView 直接作为窗口根视图** `windowManager.addView(...)`。

现在照搬旧项目那条路（`Live2DOverlayView`）。教训：
**互操作层 + 硬件层 WebView + WindowManager 窗口，三个变量叠在一起没法排查；
能跑通的那条路优先。**

**2. 窗口要显式要求硬件加速。**

用 `WindowManager` 加的窗口（不是 Activity 的窗口）不一定拿到 GPU 上下文，
而 WebView 走 WebGL，没有 GPU 就什么都画不出来 —— 表现是"窗口在、里面全空"，且不报错。
所以 `params` 里加了 `FLAG_HARDWARE_ACCELERATED`；旧项目给它的全屏编辑窗口也加了这一条。

**3. 触摸必须抢回来，否则拖不动窗口。**

拖动挂在**根视图**的 `OnTouchListener` 上，而子 View 优先：WebView 会吃掉 `ACTION_DOWN`。
`Live2DOverlayView.onInterceptTouchEvent` 直接返回 `true` 把整个手势抢过来，
之后事件才会送到根视图的拖动逻辑。

**4. 模式（键盘/鼠标模型）不能"等页面就绪再切"。**

构造时会先按默认值加载一次页面；如果配置是鼠标模型，而 `applyMouseMode` 只在
"页面已就绪"时才重载，那次设置就被丢掉了，表现是**选了鼠标模型却一直是键盘模型**。
现在规则很简单：**期望值变了就重载**（`mouseMode` 是唯一真源）。

**5. 出问题时看日志，不要猜。**

`Live2D` 这个 tag 下有四条关键日志：
- `悬浮窗已挂载：样式=… 硬件加速=… 尺寸=…` ← **硬件加速=false 就是根因**，与 JS 无关；
- `页面加载完成：…（硬件加速=…）`；
- `健康检查：（首次/复检）{core:.., ready:.., canvas:..}` ← `core=false` 说明核心库没加载（路径问题）、
  `ready=false` 说明模型没起来（紧接着的 console.error 会说原因）、`canvas=1x1` 说明画布尺寸为 0；
- `JS …` ← runtime.js 的 console 输出，模型初始化失败的原因就在这里。

---

## 8. 输入推送：两个必须处理的坑

**1. 节流。** 参考实现**每个按键事件调一次 `evaluateJavascript`**，高频输入下会顶着
WebView 主线程。改成：观察 `CaptureSession.pressedKeys`（已经是 StateFlow），
只推**变化了**的语义，并且带最小间隔（约 16–33 ms）。

**2. 状态补发（最容易出的 bug）。** WebView 加载要几百毫秒，这期间推过去的输入
**会被静默丢掉**，猫的状态就永远和实际不同步。必须：页面未就绪时只记录最新状态，
`onPageFinished` 后 `clear()` + 把所有仍按下的键补发一遍。
参考实现的 `flushInputState()` 就是干这个的。

另外：`onDetachedFromWindow` 要 `stopLoading()` + `loadUrl("about:blank")` + `destroy()`，
否则 WebView 泄漏（参考实现是这么处理的，照做）。

WebView 设置（照搬参考实现，安全相关）：`javaScriptEnabled=true`、
`allowFileAccess=true`、`allowFileAccessFromFileURLs=true`、
**`allowUniversalAccessFromFileURLs=false`**、**`blockNetworkLoads=true`**（禁网）、
`domStorageEnabled=false`、`setLayerType(LAYER_TYPE_HARDWARE)`、背景透明。

---

## 9. 许可证（发版前必做）

- **BongoCat 资产**（runtime.js、猫模型、键帽图）：MIT，© 2025 ayangweb。
  已随资产放入 `BONGOCAT_LICENSE.txt`。**保留版权声明即可**。
- **`live2dcubismcore.min.js`：不是开源软件。** 适用
  [Live2D Proprietary Software 使用授权协议](https://www.live2d.com/eula/live2d-proprietary-software-license-agreement_cn.html)：
  - 出版派生作品原则上要签"Live2D 出版许可协议"，但**一般用户 / 小规模企业
    （最近一个会计年度销售收入 < 1000 万日元）免除**；
  - 允许转发"可转发代码"，但必须**原样、不得修改**；
  - 要求终端用户同意同等条款 → **app 内要附许可文本**（关于页 + assets 里的 LICENSE）；
  - 未签出版许可时**不得让人误以为由 Live2D 官方制作或认可**。

  TODO（发版前）：核实 Cubism SDK for Web 的 `RedistributableFiles.txt` 是否列了
  `live2dcubismcore.min.js`（协议 §1.15 规定"可转发代码"以该文件为准）；在关于页加许可条目。

---

## 10. 分阶段（每阶段都必须能单独编译、单独验证）

### 🔁 P1 静态渲染（已重做，待验证）

把猫显示出来，**不接任何输入**；窗口尺寸按样式分流。

> 第一版用 Compose 的 `AndroidView` 托管 WebView，**不显示**。
> 已按旧项目跑通的结构重做（FrameLayout + WebView 直接当窗口根视图），
> 并加了 `FLAG_HARDWARE_ACCELERATED` 与自诊断日志。原因记录见 7.1。

**验证方法**：新建一个配置、样式选 `LIVE 2D`、切到它并打开悬浮窗。应该看到键盘猫。
判读信号（按这个顺序看日志，不要靠猜）：

1. `悬浮窗已挂载：样式=keyboard_cat，硬件加速=?`
   —— 如果是 `false`，WebView 画不出任何东西，问题在窗口不在 JS。
2. `页面加载完成：file:///android_asset/bongocat/keyboard/index.html`
   —— 如果这条都没有，是资源路径问题。
3. `健康检查（首次）：{"core":true,"ready":true,"canvas":"WxH"}`
   —— `core=false` 核心库没加载；`ready=false` 模型没起来（看下一条）；
   `canvas=1x1` 画布尺寸为 0。
4. `JS …` 里的 `BongoCat source model init failed` ← 模型初始化失败的真实原因。

画面判读：**只看到一张静止的猫图** = WebGL 没起来（`cover.png` 是 JS 自己的回退图，
初始化失败时显示、成功时隐藏）。**全空** = 连回退图都没画出来，问题在窗口/WebView 层。

### ⬜ P2 键盘输入（本轮已完成，待验证）

链路：`CaptureController` → `CaptureSession.pressedKeys`（StateFlow）
→ `OverlayService.startKeyFeed()` → `Live2DOverlayView.updateKeys(整个集合)`
→ 映射/去重/合并 → `AxonBongoCat.key(名字, 按下)`。

分工刻意与旧项目一致：**Service 只推原始键码集合**，BongoCat 的语义、
左右侧、节流全部封装在 View 里，上层不需要理解它。

三个必须做对的地方（漏掉任何一个都会表现为"猫的反应不对"）：

1. **一帧合并**：不能一条事件发一次 `evaluateJavascript`（跨进程调用，高速输入会刷爆
   WebView 主线程）。改动累积到下一帧统一发（`postOnAnimation`），最多 60 次/秒。
2. **页面就绪后整体补发**：WebView 加载要几百毫秒，这期间推过去的输入会被静默丢掉。
   所以 `onPageFinished` 里先 `clear()` 再按当前按下的键重建。
3. **同侧恢复**：JS 每一侧只显示"最后一个按下的键"，松开时会**清空整侧**。
   所以某个键松开时若同侧还有键按着，要把最近的那个补发一次，
   否则 Shift 按着不放、点一下 A 再松开，Shift 的手也会掉下来。

另外一个细节：左右 Meta 都映射到 `Meta`、F1..F12 都映射到 `Fn`，
所以"是否按下"要**按语义计数**而不是布尔（同时按左右 Meta，松开一个不能把整侧清掉）。

**验证方法**：开着 `LIVE 2D` 悬浮窗按键盘，猫的手应该跟着动。
不动时先看健康检查日志里的"本端按住 N 个键"：
按住键时 N 一直是 0 → 采集层没送到；N 有值而猫不动 → 问题在 JS 侧（看 console 日志）。

### ⬜ P3 鼠标位移（本轮已完成，待验证）

链路：`CaptureController` 的 `EV_REL` 分支（原来是个**空占位**）
→ `CaptureSession.addMouseMotion()`（累计值）
→ `OverlayService` 第二条订阅 → `Live2DOverlayView.updateMouseMotion()`
→ 差分出增量、按帧合并 → `AxonBongoCat.mouseDelta(dx, dy, 屏宽, 屏高)`。

三个设计点：

1. **累计值而不是事件流**：鼠标能报到 1000Hz，事件流会把订阅方刷爆。
   采集层只维护"自启动以来的总位移"，消费方差分即可 —— 中间丢多少帧都不丢位移
   （丢帧时差分会得到一个更大的增量，位置仍然正确）。
   ③ 这个值**永不重置**：重置会让消费方算出巨大的反向位移，猫的头会瞬移。
2. **一帧一条 `mouseDelta`**：与按键共用同一套 `postOnAnimation` 合并。
3. **`subscriptionCount == 0` 时不累加**：只有 Live2D 会订阅这条流，
   按键样式下没人要，白写 1000 次/秒（还带对象分配）没有意义。

`mouseDelta` 的宽高参数必须是**屏幕**像素而不是悬浮窗自己的尺寸 ——
JS 用它们把位移换算成 0..1 的归一化位置，给错了反应幅度就不对。

**验证方法**：动鼠标，猫的头 / 眼球（鼠标模型还有鼠标本身）应该跟着动。
不动时看日志里有没有 `收到鼠标位移，开始转发（首帧 x,y）`：
没有这条 → 采集层没把 `EV_REL` 送上来；有这条而猫不动 → 问题在 JS 侧。

### ⬜ P4 收尾
加载失败的兜底表现、性能与耗电、许可条目（第 9 节）、镜像开关。

---

## 11. 自定义模型（P5）设计定稿

### 11.1 模型库：对齐字体库

| | 字体（已有） | Live2D 模型 |
|---|---|---|
| 标识 | `system:` / `builtin:` / `imported:<uuid>` | `builtin:` / `imported:<uuid>` |
| 落盘 | `filesDir/fonts/` | `filesDir/live2d_models/<uuid>/` |
| 配置里存 | 只存 `fontId` | 只存 `live2d.modelId` |
| 作用域 | **全局**，导入一次所有配置可用 | 同左 |
| 去重 | 文件 SHA-256 | **目录摘要**（见 11.4） |
| 导出 | 把用到的字体复制进包 | 把用到的模型复制进包 |

内置两个模型**不进库**（它们在 assets 里，不需要复制与管理）：

- `builtin:keyboard` —— 键盘版（assets/bongocat/keyboard）
- `builtin:standard` —— 鼠标版（assets/bongocat/standard）

### 11.2 配置字段：`mouseMode` → `modelId`

```kotlin
data class Live2DSettings(
    val modelId: String = "builtin:keyboard",  // builtin:keyboard / builtin:standard / imported:<uuid>
    val opacityPercent: Int = 100,
)
```

**为什么废掉 `mouseMode`**：「右侧显示方向键还是鼠标」本质上就是"用哪个内置模型" ——
`builtin:keyboard` 与 `builtin:standard` 正是那两页。两个字段并存会打架
（`modelId=standard` + `mouseMode=false` 听谁的？）。

编辑页的「右侧显示」**照旧保留**，只是它现在等于"选哪个内置模型"；
选中导入模型时该项不显示（导入模型用自带的键帽资源包）。

**老配置迁移**（读取时）：`mouseMode=true → builtin:standard`，`false → builtin:keyboard`。

### 11.3 配置包：解压目录，不是内嵌 zip

```
我的配置.sthkey                 （本质是 zip）
├── manifest.json
├── params.json                 live2d.modelId = "sthkey-live2d://assets/live2d/我的模型"
└── assets/
    ├── font/xxx.ttf
    └── live2d/
        └── 我的模型/            一个模型一个目录，原始文件原样保留
            ├── xxx.model3.json
            ├── xxx.moc3
            └── xxx.1024/*.png
```

manifest 新增一段（按键样式时为 `null`）：

```json
"live2d": {
  "path": "assets/live2d/我的模型",
  "displayName": "我的模型",
  "sha256": "<目录摘要>",
  "layout": "full"          // full=自带 BongoCat 键帽资源包；cat=只有角色
}
```

**为什么是解压目录**：配置包本身就是 zip，内嵌 zip 要解两次、要临时文件、
目录名与摘要都得额外记录；而现在包里所有东西用任何解压工具都能看懂。
`index.html` / `modeldata.js` 是导入时生成的，**不进包**。

`formatVersion` **不升**：新增可选字段是向后兼容的（老版本读到 `live2d` 会当"未识别字段"
跳过并如实报告；新版本读到没有 `live2d` 的老包回落到内置模型）。破坏性改动才升。

### 11.4 导入流程（照旧项目，加两条它没有的）

1. ZIP 解压到 `filesDir/live2d_models/<uuid>/`，**流式写盘**
   （绝不能把模型读成 ByteArray：moc3 可能 20MB+）；
   上限照旧项目：512 个文件 / 单条 192MB / 总计 512MB；带路径穿越校验。
2. 在目录树里找第一个 `*.model3.json`；没有就报"不是 Live2D 模型包"。
3. 读 `FileReferences` 校验 `Moc` 与 `Textures` 都存在。
4. **兼容性检查**（旧项目没有）：读 `cdi3.json` 的参数表，缺
   `CatParamLeftHandDown/RightHandDown` 就在导入报告里提示
   「按键不会有反应，只有鼠标能让它动」—— 否则用户会以为功能坏了。
5. **目录摘要**（旧项目没有）：所有文件按相对路径排序，逐个 SHA-256 再拼起来算一次
   （排除生成的 `index.html` / `modeldata.js`）。摘要命中已有模型就直接复用，不重复占空间。
6. 生成 `modeldata.js`（**moc3 转 base64** + 贴图相对路径 + 资源基址 + layout 标记）
   与 `index.html`（照抄旧项目的 CUSTOM_HTML，引用
   `file:///android_asset/bongocat/live2dcubismcore.min.js` 与 `.../keyboard/runtime.js`）。
7. 登记进全局库（名称取 ZIP 的显示名，重名自动加序号）。

> **base64 不是多余的**：`file://` 页面里 `fetch`/XHR 读二进制受限制，
> 而 `<script src>` 不受限 —— 这是旧项目踩过之后的选择。代价是 moc3 膨胀 1/3。

### 11.5 分两阶段

- **P5a 模型库 + 导入 + 选择器** ✅（待验证）：`modelId` 字段与老配置迁移、ZIP 导入、
  落盘校验、生成页面、编辑器的模型选择/导入/改名/删除、兼容性提示。
- **P5b 配置包携带模型** ⬜（下一步）：导出复制目录 + `manifest.live2d` +
  导入装入库（摘要去重）+ 报告行（已导入 / 复用 / 缺失）。

---

## 12. 已知风险

1. **WebView 在悬浮窗里可能渲染不出来**（部分 ROM 限制）。P1 就是为了尽早暴露它。
2. **耗电**：常驻 WebGL 循环比原来的 Canvas 画按键重得多，需要实测。
3. **首次加载延迟**：WebView 初始化 + 解 moc3 约几百毫秒，期间窗口可能是空的。
4. **任意模型不一定"按键有反应"**：只保证头/眼球跟随（Angle / EyeBall 参数较普遍），
   手部动作依赖 `CatParamLeftHandDown/RightHandDown` —— 所以导入时要给兼容性提示（11.4 第 4 条）。
5. **大模型的内存**：moc3 转 base64 时峰值约为 moc 的 2.3 倍，
   20MB 的 moc3 会有约 47MB 峰值。必要时把 base64 分块写入以压低峰值。
