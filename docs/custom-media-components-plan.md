# 自定义 Key 新增「图片 / 动图 / 视频」组件 —— 可行性结论与方案

> 状态:**可行性已确认，待开工**（2026-11-01）
>
> 用户的需求:
> > 自定义 key 里，组件样式的通用栏新增「图片」「动图」「视频」，
> > 兼容 .png / .gif / .mp4 等内容绘制在悬浮窗上；
> > 动图与视频都循环播放，视频可以多个、各自配是否静音。
> >
> > 核心难点:让其他按键组件与这些资源组件绘制在同一悬浮窗上；
> > 动图与视频循环、视频静音、悬浮窗尺寸计算。

---

## 1. 结论

**能做，而且比想象的顺** —— 因为现有架构的三个关键点已经天然适配:

| 关键点 | 现状 | 对这个需求意味着 |
|---|---|---|
| 组件怎么摆 | 每个组件就是一个 `Box`，用 `Modifier.offset(x,y).size(w,h)` 定位（`CustomComponentView`） | 图片/动图/视频直接是"另一种 Box 内容"，**不需要改画布结构** |
| 窗口尺寸 | `CustomLayout.bounds()` 用所有组件的 `x/y/width/height` 算包围盒 | 新组件只要给出宽高，**尺寸计算自动就对了** |
| 资源库 | 字体库（`FontRegistry`）已经把"全局导入 → 私有目录 → 记录在 prefs → 配置引用 id → 随包导出"跑通 | 图片/视频库**照抄这套**即可 |

真正的难点只有两个，且都有成熟解法（见 §3）:
1. **GIF 循环** —— Compose 本身不支持，但 Android 自带 `AnimatedImageDrawable`（API 28，minSdk 正好 28）；
2. **视频循环 + 静音 + 与 Compose 同窗** —— `TextureView` + `MediaPlayer`，静音用 `setVolume(0,0)`。

⚠️ 需要提醒的代价（不是"做不到"，是"要接受"）:
- 视频是**独立解码器实例**，同时放多个会吃内存/发热，必须限制并发数（建议 2~3 个）；
- 视频循环在部分编码上有**极小的接缝**（见 §5 风险表）；
- 导出配置包会**变大**（视频随包走）。

---

## 2. 数据模型

新增一种组件类型（不影响现有三种）:

```kotlin
sealed interface CustomComponent { id, x, y, width, height }

/** 图片 / 动图 / 视频（三者共用一种组件，靠 kind 区分） */
data class MediaComponent(
    override val id: String,
    override val x: Float,
    override val y: Float,
    override val width: Float,
    override val height: Float,
    val kind: MediaKind,            // IMAGE / GIF / VIDEO
    val assetId: String,            // 指向全局资源库
    val fit: MediaFit = COVER,      // 铺满方式: FIT(留边) / COVER(裁切)
    val opacityPercent: Int = 100,
    val cornerRadiusPercent: Int = 0,
    val muted: Boolean = true,      // ⚠️ 只对 VIDEO 有意义
) : CustomComponent

enum class MediaKind { IMAGE, GIF, VIDEO }
enum class MediaFit { FIT, COVER }
```

⚠️ **静音字段只有视频用** —— 按项目里"`TextualComponent` 才带文字字段"的同一个思路，
`muted` 不放在基类上，属性面板也只在 VIDEO 时显示它（否则用户会改一个没用的开关）。

---

## 3. 技术选型（逐项确认过）

### 3.1 静态图片（.png / .jpg / .webp）

Compose 原生:`Image(painter = rememberAsyncImagePainter(...))` 需要库；
不引库也可以 `BitmapFactory.decodeFile` + `Image(bitmap.asImageBitmap())`，
⚠️ 大图要按组件尺寸做 `inSampleSize`，否则一张 4K 图直接吃掉几十 MB。

**结论:零依赖可做。**

### 3.2 动图（.gif / 动态 .webp）

- `ImageDecoder.decodeDrawable(ImageDecoder.createSource(file))` → 对动图返回
  [`AnimatedImageDrawable`](https://developer.android.com/reference/android/graphics/drawable/AnimatedImageDrawable)（API 28+，我们 minSdk = 28 ✓）。
- 循环:`setRepeatCount(AnimatedImageDrawable.REPEAT_INFINITE)` + `start()`。
- Compose 里没有 GIF 支持，用 `AndroidView { ImageView(it).apply { setImageDrawable(drawable) } }`。
- ⚠️ `AnimatedImageDrawable` 每个实例会占一个解码线程，**同时放十几个 GIF 会明显发热**。

**结论:零依赖可做**（不需要 Coil）。

### 3.3 视频（.mp4 / .webm）

两条路:

| 方案 | 优点 | 代价 |
|---|---|---|
| **A. `MediaPlayer` + `TextureView`**（推荐先做） | 零新依赖；`setLooping(true)` + `setVolume(0,0)` 就是全部 | 循环接缝（部分编码）比 ExoPlayer 明显；错误处理要自己写 |
| B. media3 `ExoPlayer` | 循环更稳（`REPEAT_MODE_ONE`）、支持更多格式 | 多一个依赖（release 后约 1~2 MB），更多样板代码 |

⚠️ `TextureView` **必须硬件加速**才出画面 —— 我们的悬浮窗已经显式加了
`FLAG_HARDWARE_ACCELERATED`（`OverlayService`），这条满足 ✓。

⚠️ 音频:`MediaPlayer` 默认**不申请音频焦点**，所以静音时不会把游戏的音量压掉；
不静音时也**不要**去 `requestAudioFocus`，否则会抢焦点把游戏声音停掉。

**结论:A 方案零依赖可做，B 作为以后的升级项。**

### 3.4 与按键组件同窗

每个组件本来就是独立的 `Box`；`AndroidView` 与 Compose 的 `Text`/`Canvas` 在同一棵
Compose 树里并存是官方支持的互操作方式。⚠️ 但要注意层级:
`AndroidView` 是**原生 View**，它的 z 序在同层 Compose 内容之上 ——
如果媒体组件要压在按键下面/上面，必须靠**组合顺序**（先画谁）而不是 `zIndex`。

---

## 4. 资源库与导入导出（照字体库）

| 环节 | 做法 |
|---|---|
| 导入 | SAF `OpenDocument`（`image/*` `video/*`）→ 复制到 `filesDir/custom_media/<uuid>/` |
| 记录 | prefs 里一条 JSON（id / 显示名 / kind / 文件名 / sha256 / 大小） |
| 去重 | 与字体一样按 sha256；同一个文件再导入直接复用 |
| 配置引用 | 组件里只存 `assetId`（**不存路径**，路径会随机器变） |
| 导出 | 包里 `assets/media/<显示名>/`，只带**被引用**的资源（与字体同一套逻辑） |
| 导入 | 解包后登记资源、组件按 id 重新绑定 |
| 体积上限 | ⚠️ 建议单个文件 ≤ 64 MB、一个包 ≤ 128 MB（视频很容易超） |

⚠️ 视频**不做转码**。用户给什么就放什么，放不了就报"这个编码不支持"
（Android 自带解码器覆盖 H.264/VP8/VP9/AV1 依机型而定）。

---

## 5. 风险与对策

| 风险 | 说明 | 对策 |
|---|---|---|
| **多视频发热/内存** | 每个视频一个解码器 + 一块 Surface | 限制同时播放的视频数（建议 3），超出时报警告；编辑器里预览可自动暂停 |
| **循环接缝** | `MediaPlayer` 的 `setLooping` 在某些编码上会有一帧空白/音频断点（[已知问题](https://issuetracker.google.com/issues/36931073)） | 先接受；要更平滑再换 media3 |
| **GIF 线程数** | 每个 `AnimatedImageDrawable` 一个解码线程 | 限制同时动图数量（建议 6） |
| **大图 OOM** | 直接 `decodeFile` 一张 4K 图 = 几十 MB | 按组件显示尺寸算 `inSampleSize` |
| **导出包变大** | 视频动辄几十 MB | 包里显示总体积；超限提示用户 |
| **AndroidView 层级** | 原生 View 会盖住同层 Compose 内容 | 用组合顺序控制遮挡；文档写清 |
| **窗口尺寸** | 已自动（`bounds` 取所有组件包围盒） | 无需额外处理 ✓ |
| **编辑器画布预览** | 编辑器里也要能看到这些组件 | 同一套渲染函数复用到编辑器画布（视频在编辑器里**默认暂停**并显示首帧，避免卡顿） |

---

## 6. 分阶段实施（每阶段可单独发版）

| 阶段 | 内容 | 风险 |
|---|---|---|
| **P1 静态图片** | 组件类型 + 资源库 + 属性面板（铺满方式/透明度/圆角）+ 渲染 + 导出导入 + 窗口尺寸 | 低 |
| **P2 动图** | `AnimatedImageDrawable` 路径 + 循环 + 数量上限 | 低 |
| **P3 视频** | `MediaPlayer` + `TextureView` + 循环 + 静音开关 + 并发上限 | 中（解码器/Surface 生命周期） |
| P4（可选） | 换 media3、每组件音量、播放进度控制 | 中 |

⚠️ 建议按 P1 → P2 → P3 分开验证:P3 出问题时 P1/P2 已经能用。

---

## 7. 验证清单（按阶段）

### P1
- [ ] 新增「图片」组件 → 选 .png → 悬浮窗里出现在指定位置、大小正确
- [ ] 悬浮窗尺寸随图片位置/大小变化（没有多余空白、没有裁切）
- [ ] 图片与按键/文本组件**同窗同时显示**
- [ ] 导出配置 → 换设备导入 → 图片还在
- [ ] 透明度 / 圆角 / 铺满方式生效

### P2
- [ ] .gif 循环播放，不卡顿
- [ ] .gif 与按键同窗，按键动画正常
- [ ] 放 6 个 gif:不崩、发热可接受

### P3
- [ ] .mp4 循环播放（接缝可接受）
- [ ] 「静音」开关:静音时**不打扰**游戏声音；不静音时能听到
- [ ] 放 2 个视频:同时循环、不互相干扰
- [ ] 关掉悬浮窗/改配置后视频**停止**（不能偷偷在后台解码）
- [ ] 组件尺寸变化时视频画面跟着缩放（`TextureView` 会自己拉伸）

---

## 8. 需要用户拍板的点

1. **视频技术路线**:先 `MediaPlayer`（零依赖，够用）还是直接 media3（更稳，多 1~2 MB）？
   —— 我建议先 `MediaPlayer`。
2. **数量上限**:视频 3 个、动图 6 个，这个上限可以吗？
3. **单文件/单包体积上限**:建议 单文件 64 MB、单包 128 MB。
4. **导出时是否带上媒体资源**:必须带（不然对方打开是空白），但要告诉用户包会变大。

---

## 9. 实施进度（2026-11-01）

### 9.1 本轮完成（P1 骨架 + P2 渲染）

| 层 | 内容 |
|---|---|
| 数据模型 | `MediaKind`（IMAGE/GIF/VIDEO）、`MediaFit`（FIT/COVER）、`MediaComponent`（id/x/y/w/h + kind/assetId/fit/opacity/cornerRadius/muted） |
| 组件类型 | `ComponentType.IMAGE / GIF / VIDEO`，都在「通用」栏 |
| 工厂 | `createComponent` 三种各返回一个 `MediaComponent`，初始尺寸 200×120（横向矩形），**不给默认资源** |
| 存档 | `CustomLayoutCodec` 写/读 `mediaKind`、`assetId`、`fit`、`opacityPercent`、`cornerRadiusPercent`、`muted`；`typeIdOf` 与解码都按 `kind` ↔ `type` 反查 |
| 资源库 | `CustomMediaStore`（`filesDir/custom_media/<uuid>/<文件名>` + 同目录 `index.json`；sha256 去重；**不做数量/体积上限**，按用户要求） |
| 渲染 | `MediaComponentView`：静态图片（按显示尺寸算 `inSampleSize` 解码）、动图（`AnimatedImageDrawable` 自动循环）、视频（**占位**，下一步） |
| 安全 | 画布里媒体组件走**独立分支**——否则 `component as TextualComponent` 会抛 `ClassCastException`（加个图片组件就崩悬浮窗） |
| 初始化 | `SthKeyApplication` 里 `CustomMediaStore.init(this)`（不初始化则永远画成"未选择资源"） |

### 9.2 还没做（按顺序）

| # | 内容 | 说明 |
|---|---|---|
| 1 | **属性面板** | 现在四处「外观」栏对媒体组件是 `Unit`（空）。需要:选资源（库列表 + 现场导入）、铺满方式、不透明度、圆角、静音（仅视频） |
| 2 | **视频播放** | `MediaPlayer` + `TextureView`，`setLooping(true)`、`muted ? setVolume(0,0) : 正常` |
| 3 | **导出带资源** | `ConfigPackageManager` 收集被引用的媒体资源，打进包 |
| 4 | **资源库界面** | 列表 / 删除 / 占用统计（`CustomMediaStore.all/delete/totalBytes` 已就绪） |
| 5 | **编辑器画布静态预览** | `MediaComponentView` 已有 `staticPreview` 参数，编辑器调用时要传 `true`（视频不播） |

### 9.3 当前可用性

- ✅ 能**新建**图片/动图/视频组件（出现在「通用」栏），能**存档/读档**，能**复制/移动/缩放**
- ⚠️ 但**还没法给它选资源** —— 属性面板那一栏还是空的
- ⚠️ 所以现在新建出来显示的是「图片（未选择资源）」占位框

⚠️ 也就是说:**这一轮是"骨架 + 渲染"，还差属性面板才能真用**。
下一步先做属性面板（选资源 + 静音开关），做完 P1/P2 就能实际跑起来。
---

## 10. 第二轮（2026-11-01）:属性面板 + 拉伸 + 静态预览

按用户的四条要求做的:

> 选资源调用相册应该就可以了吧；组件的 X 和 Y 轴滑块肯定要有；
> 要不要做「拉伸」呢……具体你看着办；预览就跟相册软件差不多，
> 外面的预览也是，视频动图这些，预览静态就好，不要动态

### 10.1 选资源 = 相册（SAF）

`MediaSection` 里用 `ActivityResultContracts.OpenDocument`:
- 图片 / 动图 → `image/…`（gif 在相册的图片一栏，MIME 各机型判断不一，给宽一点）
- 视频 → `video/…`

选完立刻 `CustomMediaStore.import(...)`（IO 线程）→ 成功后把 `assetId` 写进组件。

### 10.2 X / Y 滑块

✅ **本来就有** —— `GeometrySection` 的形参是 `CustomComponent`（不是 `TextualComponent`），
所以 X 轴 / Y 轴 / 宽 / 高 四个滑块对媒体组件**自动生效**，不需要新写。

### 10.3 「拉伸」:做，而且是第三种铺满方式

最终有三档（`MediaFit`）:

| 值 | 行为 | 什么时候用 |
|---|---|---|
| `FIT` 完整显示 | 保比例、留边 | 表情图、logo |
| `COVER` 铺满裁剪 | 保比例、裁掉超出 | 背景图 |
| **`STRETCH` 拉伸铺满** | 宽高各自拉满、会变形 | 进度条、色块、边框 |

⚠️ **没有**把宽/高滑块换成"缩放滑块" —— 因为宽度和高度滑块同时存在时，
`STRETCH` 已经能做到"任意拉伸"，而缩放滑块会丢掉"只改宽度"的能力。

### 10.4 静态预览

`CustomKeyCanvas(staticMedia = …)` → `CustomKeyCanvasContent` → `MediaComponentView(staticPreview = …)`。

| 场合 | `staticMedia` | 表现 |
|---|---|---|
| 悬浮窗 | `false` | 动图循环、视频播放 |
| 编辑器画布 | **`true`** | 动图取**首帧**静态图，不占解码线程 |
| 配置预览 | **`true`** | 同上 |
| 视频（任何场合） | — | 目前仍是占位框（下一步做） |

⚠️ 静态预览用"首帧位图"而不是"暂停的 AnimatedImageDrawable":
一屏几十个组件时，后者每个都带一个解码线程，编辑器会明显发热。

### 10.5 属性面板的媒体栏

`MediaSection`（内容栏）:
- 资源：当前文件名 + 「从相册选择 / 更换资源」+「清除」
- 铺满方式：三个 chip + 一句说明当前选项
- 不透明度滑块 0..100
- 圆角滑块 0..50
- **静音开关** —— ⚠️ **只在视频时显示**（图片/动图没有声音，显示它就是骗人）

⚠️ 外观栏（描边/颜色/按压动画）对媒体组件**不显示**（`is MediaComponent -> Unit`），
因为那些概念对它不成立。

### 10.6 还没做

| # | 内容 |
|---|---|
| 1 | **视频播放**（`MediaPlayer` + `TextureView`，循环 + 静音）—— 目前占位框 |
| 2 | **导出带资源**（配置包收集被引用的媒体） |
| 3 | 资源库界面（列表/删除/占用统计） |

### 10.7 当前可用

- ✅ 新建图片/动图组件 → 从相册选资源 → 悬浮窗里真的画出来
- ✅ 动图循环；编辑器与预览里是静态首帧
- ✅ X/Y/宽/高滑块、三种铺满方式、不透明度、圆角
- ⚠️ 视频还是占位框（下一步）
---

## 11. 两个实测问题（2026-11-01）

### 11.1 「添加组件」弹窗:通用栏挤成一行、且不能滚动

用户的原话:
> 通用栏全挤在一行，看着非常丑…… 而且这个好像不能下滑，
> 要是之后组件数量越来越多，会有显示问题

**原因**:每一栏用的是 `Row` + `Modifier.weight(1f)` —— 卡片会被**均分宽度**，
通用栏有 5 个类型（按键/文本/图片/动图/视频）时就挤成一坨；高度超了也没有滚动。

**修法**:

| 问题 | 改动 |
|---|---|
| 挤成一行 | `Row` → **`FlowRow`**，每个卡片 `widthIn(min = 132.dp)`，放不下自动折行 |
| 不能滚动 | 弹窗内容 `Column` 加 `heightIn(max = 420.dp)` + `verticalScroll(...)` |

⚠️ 用 `heightIn(max=…)` 而不是 `fillMaxHeight`:弹窗高度本来由内容决定，写死撑满会让只有两栏时也很空。

### 11.2 GIF 组件「拦截触摸但拖不动悬浮窗」

用户的原话:
> 添加的 gif，在悬浮窗上触摸，移动不了悬浮窗，但是它又确确实实被计入悬浮窗尺寸了……
> 一个配置，左上角放一点组件，右下角隔一段放个 gif 组件，然后启动悬浮窗后，
> 拖动 gif 的位置，会拦截触摸，但是拖不动悬浮窗，然后尝试摸 gif 左边、上面的空气，
> 能拖动了

**原因**:

| 事实 | 说明 |
|---|---|
| 悬浮窗拖动是挂在**根视图**的 `setOnTouchListener` | 见 `OverlayService.setupDragListener` |
| GIF 是 `AndroidView { ImageView }` —— **真实的原生 View 子树** | 它把 `ACTION_DOWN` 接走之后，根视图收不到事件 |
| 所以"能拦触摸（事件被 GIF 吃了）但不能拖（根视图没收到）" | 与用户描述完全一致 |
| 而"摸 GIF 左边的空气能拖" | 那里没有原生 View，事件直接落到根视图 ✓ |

⚠️ 顺带解释了"它又确实被计入悬浮窗尺寸":尺寸是按**所有组件包围盒**算的，
GIF 组件确实在那儿 —— 所以窗口变大、但那一块拖不动。

**修法**:给 `AndroidView` 加 `Modifier.pointerInteropFilter { false }`
（让 Compose 不把触摸交给原生 View），并在 `ImageView` 上把
`isClickable / isLongClickable / isFocusable / isHapticFeedbackEnabled` 全关掉做双保险。

⚠️ 视频以后同样用 `AndroidView`（`TextureView`），**必须照这个做** ——
否则同一个问题会以"视频那块拖不动"的形式再来一次。

### 11.3 验证清单

- [ ] 打开「添加组件」:通用栏的 5 个卡片**分多行**排列，不再挤成一坨
- [ ] 弹窗内容能**上下滚动**（类型变多时能看到最下面一栏）
- [ ] 悬浮窗里按在 GIF 上拖动 → **窗口跟着动**
- [ ] 按在 GIF 旁边的空气拖动 → 仍然能动（与以前一致）
- [ ] GIF 仍然正常循环播放、仍然被算进窗口尺寸
---

## 12. GIF 拖不动 —— 第二次修（去掉原生 View）

### 12.1 第一次修法为什么没用

第一版给 `AndroidView` 加了 `Modifier.pointerInteropFilter { false }`，用户实测**仍然拖不动**。

原因:`pointerInteropFilter` 只影响 **Compose 自己的指针分发**。
`AndroidView` 在真实 View 层级里**仍然是一个子 View**，系统照样把触摸派发到它 →
根视图的 `setOnTouchListener` 依然收不到 `ACTION_DOWN`。

⚠️ 教训:**能"绕过 Compose"的手段，绕不过 View 层级本身**。
只要悬浮窗里存在一个会接收触摸的原生子 View，根视图的拖动就会在那一块失效。

### 12.2 第二次修法:让悬浮窗里**没有**原生 View

GIF 改成**完全由 Compose 绘制**:

| 环节 | 做法 |
|---|---|
| 解码 | `Drawable.createFromPath` → 得到 `AnimatedImageDrawable`（它自己推进帧） |
| 重绘 | `LaunchedEffect { while (true) { withFrameMillis { tick++ } } }` 按屏幕帧节奏触发重组 |
| 绘制 | `Canvas { drawIntoCanvas { it.nativeCanvas … drawable.draw(native) } }` |
| 铺满 | 三种 `fit` 都在这里手算（拉伸 / 等比铺满裁剪 / 等比完整显示） |

⚠️ 逐帧循环**只在有 drawable 时才挂** —— 资源没选/解码失败时不该白唤醒组合。

⚠️ `AnimatedImageDrawable` 没有"帧回调"（`AnimationCallback` 只有 start/end），
所以只能按帧驱动；这是 Compose 里放 GIF 的固有代价。

### 12.3 ⚠️ 对后续「视频」的约束

视频**必须**用 `TextureView`/`SurfaceView`（那是原生 View），
按上面这条教训，**它会再次吃掉触摸**。

所以视频那一版要**先解决"原生 View 上也能拖窗口"**，可选做法:
1. 在视频 view 上也装一份拖动监听（与根视图共用同一段逻辑）；
2. 或者把视频放到一个**单独的、不可触摸**的悬浮窗（`FLAG_NOT_TOUCHABLE`），
   拖动交给主窗口。

⚠️ 不解决这个就做视频，等于把刚修掉的 bug 原样搬回来。

### 12.4 验证清单

- [ ] 按在 **GIF 上**拖动 → 窗口**跟着动**
- [ ] 按在 GIF 旁边空气拖动 → 仍然能动
- [ ] GIF 仍然**循环播放**
- [ ] GIF 仍在窗口尺寸内（不裁、不外溢）
- [ ] 三种铺满方式对 GIF 都生效（拉伸/裁剪/完整）
---

## 13. GIF 拖不动 —— 第三次修（容器层接管触摸，根治）

### 13.1 前两次为什么都失败

| 次 | 做法 | 为什么没用 |
|---|---|---|
| 1 | `AndroidView` 加 `pointerInteropFilter { false }` | 只影响 Compose 自己的分发；**真实 View 层级里的子 View 照样被系统派发** |
| 2 | GIF 改成纯 Compose 绘制（无原生 View） | 说明问题**不只是** AndroidView —— 用户实测仍拖不动 |

⚠️ 结论:**"指望事件能漏到根视图"本身就是脆的**。
`View.setOnTouchListener` 只有在**没有子 View 消费手势**时才会被调用，
这是 Android 分发顺序决定的 —— 任何会接手势的子 View（WebView、ImageView、
以后的 `TextureView`）都能让那块区域拖不动。

### 13.2 根治做法

新增 `DragTouchHost`（一个 `FrameLayout`）：

```kotlin
override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = true
```

**容器无条件接管**整个手势 → 子 View 只收到一个 CANCEL → 拖动在任何区域都生效。

| 改动 | 位置 |
|---|---|
| `DragTouchHost`（新） | `capture/DragTouchHost.kt` |
| 视图套一层容器，拖动监听装到**容器**上 | `OverlayService` 两处创建点 |
| `OverlayWindow` 新增 `host` 字段 | 窗口级操作（`addView` / `updateViewLayout` / `removeView`）**必须**用它，用内容视图会抛 "is not a child of this window" |
| `addView(host)` | 窗口真正加入的是容器 |

⚠️ 前提是"悬浮窗里没有需要点击的东西" —— 这在本项目成立:
可触摸悬浮窗的唯一交互就是**拖动**；触屏样式的触点走采集层，不经过这些视图。

⚠️ 这条修法**同时解决未来的视频**:`TextureView` 接手势也没关系了，
容器会先把整个手势抢走（见 §12.3 的担忧，现在不需要了）。

### 13.3 验证清单

- [ ] 按在 **GIF 上**拖动 → 窗口跟着动
- [ ] 按在**图片上**拖动 → 窗口跟着动（图片是 Compose 画的，本来就行，回归确认）
- [ ] 按在空白处拖动 → 仍然能动
- [ ] **Live2D 模型上**拖动 → 仍然能动（它原来是自己 `onInterceptTouchEvent` 抢的，
     现在被外层容器统一接管）
- [ ] 拖动后位置**会保存**（松手后再开悬浮窗位置不变）
- [ ] 多窗口时每个窗口都能独立拖动
---

## 14. ⚠️ release 包「一开悬浮窗即闪退」（2026-11-01）

### 14.1 现象与日志

用户反馈:前几个版本的 **release** 包启动悬浮窗直接闪退，**debug 包不崩**。

日志关键行:

```
java.lang.IllegalStateException: ViewTreeLifecycleOwner not found from r80{…}
    at …WrappedComposition…
    at …ComposeView.onAttachedToWindow
    at android.view.View.dispatchAttachedToWindow
```

### 14.2 原因

`ComposeView` 的**默认**销毁策略是 `DisposeOnViewTreeLifecycleDestroyed`:
它在 `onAttachedToWindow` 里会去读 `ViewTreeLifecycleOwner`，
**读不到直接抛异常**。

我们的悬浮窗是 `Service` 里用 `WindowManager` 加的独立窗口，
`setViewTreeLifecycleOwner(this@OverlayService)` 虽然在，但:

⚠️ **R8 混淆之后那条 tag 写入被裁掉了**，而默认策略仍去读 —— 于是
release 崩、debug 不崩。这是"同一份源码、混淆前后行为不同"的典型陷阱。

⚠️ 教训:**release 包必须自己验**。这轮所有 Live2D/GIF 的验证都是 debug 包做的，
release 的崩溃一直藏着没被发现。

### 14.3 修法

给悬浮窗的 `ComposeView` **显式指定不依赖 lifecycle 的策略**:

```kotlin
setViewCompositionStrategy(
    ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool,
)
```

⚠️ 这不是权宜之计:悬浮窗的窗口是我们自己 `addView` / `removeView` 的，
本来就不该跟着 Activity 的生命周期销毁 —— detached 策略才是**语义正确**的那个。

`setViewTreeLifecycleOwner` / `setViewTreeSavedStateRegistryOwner` 仍然保留
（`rememberSaveable` 之类还要用），只是不再**依赖**它们才能启动。

### 14.4 验证清单（**必须用 release 包验**）

- [ ] release 包启动悬浮窗**不闪退**
- [ ] 悬浮窗内容正常显示（自定义 Key / Live2D 两种都试）
- [ ] 能拖动（空白处 / 图片上 / GIF 上 / Live2D 上）
- [ ] 多窗口同时开不崩
---

## 15. 闪退已修 + 动图变静态（2026-11-01 收尾）

### 15.1 闪退的真正原因（第三次才对）

之前两次都在猜"Compose 内部哪里要 lifecycle"，真正的线索是日志里那个 `r80{…}` ——
它是**窗口的根视图**，不是 ComposeView。

`ComposeView` 创建 recomposer 时会**从窗口根视图往上找** `ViewTreeLifecycleOwner`。
我们后来把窗口根换成了 `DragTouchHost`（触摸容器），而 owner 只挂在 `ComposeView` 上 ——
从根开始找自然找不到 → `IllegalStateException: ViewTreeLifecycleOwner not found`。

**修法**:容器也挂 owner。

```kotlin
val dragHost = DragTouchHost(this).apply { addView(view) }
dragHost.setViewTreeLifecycleOwner(this)
dragHost.setViewTreeSavedStateRegistryOwner(this)
```

现在三层:
1. 容器 `DragTouchHost` 挂 owner ← **对症的那一条**
2. `ComposeView` 挂 owner（原有）
3. `setContent` 里 `CompositionLocalProvider` 提供 `LocalLifecycleOwner`（上一轮加的，留着无害）

✅ 用户确认:release 包**不再闪退**，而且拖动也正常了（触摸容器接管顺带解决）。

### 15.2 动图变静态

把 GIF 从 `AndroidView` 改成 Compose 绘制时，**把 `drawable.start()` 丢了**。

`AnimatedImageDrawable.draw()` **只画当前帧**；帧的推进靠它自己的动画驱动，
而那个驱动只在 `start()` 之后才跑 —— 于是悬浮窗里成了静态图。

⚠️ 编辑器与配置预览本来就是静态（用户要求），所以**只有悬浮窗**能看出这个差别 ——
这也是它藏过一轮的原因。

**修法**:

```kotlin
DisposableEffect(drawable) {
    runCatching { drawable.start() }
    onDispose { runCatching { drawable.stop() } }
}
```

⚠️ `onDispose` 里必须 `stop()`:不 stop 的话视图被移除后解码线程还在跑。

### 15.3 验证清单

- [ ] release 包启动悬浮窗不闪退（已确认 ✅）
- [ ] 拖动正常（已确认 ✅）
- [ ] **动图在悬浮窗里循环播放**
- [ ] 动图在**编辑器画布 / 配置预览**里是静态（首帧）
- [ ] 移除动图组件后不再看到它继续解码（`stop()` 生效）
---

## 16. 静音开关移除 + 资源随包导出（2026-11-01）

### 16.1 视频一律静音

用户的原话:
> 把视频组件静音开关删了吧，改为默认静音，因为我测试这个有声音的 bug 很多，
> 不好搞，干脆删了，用户应该也不会用这玩意听歌吧

**彻底移除**（不是藏起来）:`MediaComponent.muted` 字段、属性面板开关、
编解码写入/读取全部删除，播放侧固定 `setVolume(0f, 0f)`。

⚠️ 按本项目一贯的取舍:留着字段就是"改了没反应的死设置"，
以后要加回来再加 —— 解码是宽容的，老包里多出来的 `muted` 会被忽略。

### 16.2 资源随包导出/导入

用户在建议基础上问过目录布局，最终采用**收一层 `media/`**:

```
assets/
├── fonts/…
├── live2d/…
└── media/
    ├── images/
    ├── gifs/
    └── videos/
```

| 环节 | 做法 |
|---|---|
| manifest | 新增 `media: [{id, name, kind, path}]`（没有就写空数组，读的人不用猜） |
| 导出 | `collectMedia(config)` 只收**被组件引用**的资源（媒体库是全局的，全打进去会白白变大） |
| 导入 | 从 zip 取字节 → 缓存中转文件 → `CustomMediaStore.importFile`（sha256 去重） |
| **重映射** | 导入方库里可能已有同一张图（id 不同）→ 建 `旧 id → 本机 id` 映射，**在 decode 之前**改写 params 树里的 `assetId` |

⚠️ 重映射是这一步的核心:不做的话，别人导入后所有媒体组件都会显示"资源已删除"。

⚠️ 导入顺序上与字体、Live2D 模型一致 —— **都必须在 `JsonConfigCodec.decode` 之前**
完成"包内引用 → 本机 id"的改写。

### 16.3 验证清单

- [ ] 视频组件:没有静音开关，播放**无声**
- [ ] 带图片/动图/视频的配置导出 → 解包看 `assets/media/images|gifs|videos/` 里有文件
- [ ] 同一个配置包在**同一台设备**再导入:资源不重复入库（sha256 去重）
- [ ] 换一台设备导入:媒体组件**正常显示**（重映射生效）
- [ ] 导出时**没被引用**的资源不进包（包体积对得上）