# 主题页与页面过渡：对齐 SukiSU（Material 侧）方案

> 2026-11-21 起草。**本文只给方案，不含代码改动。**
>
> 参考对象：`D:\Project\_sukisu\SukiSU-Ultra-main`（下称 SukiSU）。
> 目标是"感觉一样"，不是"代码一样"——原因见 §2。

---

## 1. 一句话说清

把「设置 → 外观/主题」重做成 SukiSU 那种：**手机壳实时预览 + 一排主色块 + 明暗四选 + 色彩风格/规格下拉**，
并把页面切换从"纯右滑"升级成 **新页滑入 + 旧页缩小压暗 + 可预测返回手势**。

---

## 2. 边界（先说不能做的）

| 限制 | 说明 |
|---|---|
| **许可** | SukiSU 是 **GPL-3.0**，本项目是 **MIT**。**不能复制它的代码**（哪怕改改变量名）——会把整个项目拖成 GPL。只借鉴**交互、视觉参数、实现思路**，代码自己写。这条是硬约束。 |
| **miuix** | SukiSU 的导航/过渡/玻璃都走 `top.yukonga.miuix.kmp`。我们**不引 miuix**（依赖重、且 `miuix-blur` 要 minSdk 31）。它的 `NavDisplay` / `NavDisplayEffects` 只能用来看效果，不能拿来用。 |
| **液态玻璃** | 跳过（SukiSU 用 `miuix-blur` + RenderEffect，API 33+，且与我们的 Material 体系不搭）。 |
| SukiSU 专属设置 | 导航角标、模块描述行数、susfs/刷机相关，不做。 |

---

## 3. SukiSU 是怎么做的（读源码的结论）

### 3.1 主题页

文件：`manager/.../ui/screen/colorpalette/ColorPaletteScreenMaterial.kt`（约 700 行，Material 版）

从上到下：

| 区块 | 实现 |
|---|---|
| **实时预览卡** | 按屏幕宽高比画一个"手机壳"（宽 0.4、圆角 20dp、1dp 描边），里面用**当前生成的 ColorScheme** 画顶栏、主色块、信息卡、悬浮底栏。等于"主题效果缩略图"。 |
| **主色块** | `LazyRow`：第一个是"跟随壁纸"（`Color.Unspecified`），后面是 10 个预设色。每块 72dp 圆角 20dp，内部 `Canvas` 画**上半 `primaryContainer` / 下半 `tertiaryContainer` 的半圆**；选中时 `scale 1.1` + 白色对勾（`AnimatedVisibility` + `scaleIn`）。**每一块都按自己的种子色实时生成配色**，不是写死的两色。 |
| **明暗模式** | 4 个连体按钮（system / light / dark / AMOLED），Material3 `ButtonGroupDefaults` + 自己封装的 `ExpressiveToggleButton`，只有图标。 |
| **色彩风格 / 规格** | 两个 `SegmentedDropdownItem`：`PaletteStyle.entries`（9 种）、`ColorSpec.SpecVersion.entries`（2021/2025）。 |
| **其它** | 玻璃开关、导航角标、**预测性返回开关**、完整状态、**滑动返回开关**、pager 手势模式、**页面缩放滑块 0.8–1.1**、模块描述行数。 |

配色引擎：`com.materialkolor` 的 `rememberDynamicColorScheme(seed, isDark, isAmoled, style, spec)`，
再 `amoledBackground()` 把 surface 系列刷成纯黑。

### 3.2 导航与过渡

- `MainActivity` 里是 `NavDisplay(backStack, effects = NavDisplayEffects(cornerClipRadius = rememberNavSystemCornerRadius()), onBack = ...)`；
- 每个 `entry` 带 `swipeDismiss = NavSwipeDirection...`；
- **预测性返回**是 miuix-nav 内建能力，`enablePredictiveBack` 只是个开关；
- 过渡是 miuix-nav 的默认效果：**新页从右滑入（带圆角裁切），旧页缩小并压暗**，支持"滑动跟手返回"。

> 也就是说：**SukiSU 最想要的那两个效果（压暗 + 预测性返回）不是它自己写的，而是 miuix-nav 的**。
> 我们不用 miuix，就得自己实现——这是本方案的主要工作量。

### 3.3 能借鉴 / 不能直接用的

| 能借鉴 | 不能 |
|---|---|
| 页面结构、色块画法、预览卡比例、四连按钮的分段形状 | 任何 `.kt` 代码（GPL） |
| 过渡的**观感参数**（缩放多少、压暗多少、圆角多大） | miuix-nav / `NavDisplayEffects` |
| "每个色块用自己的种子实时算配色"这个思路 | `com.materialkolor` 的封装 API（见 §4.1 我们已有等价物） |

---

## 4. 我们现状（决定了要补什么）

### 4.1 主题引擎：比想象中完整

`ui/theme/SeedColor.kt` + `Theme.kt` 已经用 `material-color-utilities`（纯算法库，无 Compose 依赖）实现了：

- **9 种色彩风格**（`AppearancePalette`：TonalSpot / Neutral / Vibrant / Expressive / Rainbow / FruitSalad / Monochrome / Fidelity / Content）——与 `PaletteStyle` 一一对应；
- **2025 规格写死**（`private val SPEC = SPEC_2025`，注释写明"与 SukiSU 一致"）；
- 6 种明暗模式（含 `monet_*`），**没有 AMOLED**；
- 没有"规格"用户可选项。

所以引擎基本够用，**缺的是**：AMOLED 纯黑、规格可选、以及"每个色块独立实时算配色"的调用方式（这个直接调 `buildSeededColorScheme` 就行）。

### 4.2 主题页：403 行，功能有、观感平

`ui/feature/settings/ThemeScreen.kt` 现在是：一排预设色块 + 明暗模式 + 色彩风格 + 底栏样式 + 预览说明。
没有手机壳预览、没有色块半圆、没有 AMOLED、没有规格下拉。

### 4.3 导航：`NavHost` + 纯右滑

`MainActivity` 用 `NavHost` + `slideIntoContainer/slideOutOfContainer`（`TRANSITION_DURATION_MS`）：

- 旧页**只是被滑走**，不缩小、不压暗；
- **没有**预测性返回（manifest 里也没有 `android:enableOnBackInvokedCallback`）；
- 一级三页是 `HorizontalPager`，二级页在它之上。

---

## 5. 方案 A：主题设置页

### 5.1 新页面结构

```
┌────────────────────────────────┐
│  外观                           │
│  ┌──────────┐                  │  ← 预览卡（手机壳，宽 0.4、比例跟屏幕）
│  │ 顶栏      │                  │     内部用当前 scheme 画：顶栏 / 主色块 /
│  │ ████     │                  │     信息卡 / 悬浮底栏
│  │ ░░░░░    │                  │
│  │  (胶囊底栏)│                 │
│  └──────────┘                  │
│  ◯  ◐  ◑  ◒  ◓ …             │  ← 主色块 LazyRow：跟随壁纸 + 预设 + 自定义
│  [系统][浅色][深色][纯黑]        │  ← 四连按钮（图标）
│  ┌──────────────────────────┐  │
│  │ 色彩风格        TonalSpot ›│  │  ← 下拉
│  │ 色彩规格         2025     ›│  │
│  └──────────────────────────┘  │
│  ┌──────────────────────────┐  │
│  │ 底栏样式     标准 / 悬浮   │  │  （现有，保留）
│  └──────────────────────────┘  │
│  ┌──────────────────────────┐  │
│  │ 页面切换                  │  │  ← 新增「高级」组
│  │ 预测性返回        [开关]   │  │
│  │ 切换缩放          100%    │  │
│  └──────────────────────────┘  │
└────────────────────────────────┘
```

### 5.2 组件规格（照 SukiSU 的观感，代码自己写）

| 组件 | 规格 |
|---|---|
| **预览卡** | 宽度 `fillMaxWidth(0.4f)`、`aspectRatio(屏宽/屏高)`、圆角 20dp、`outlineVariant` 1dp 描边、底色 `surfaceContainer`；内部：顶栏 48dp（应用名）、主色块 52dp、信息卡 `weight(1f)`、底部胶囊（`surfaceContainerHighest`，高 28dp，左边主色胶囊 + 3 个小圆点） |
| **主色块** | 72dp 方形、圆角 20dp、底色 `surfaceContainer`；内部 `Canvas` 画两个半圆：上 `primaryContainer`、下 `tertiaryContainer`；选中 → `scale 1.1` + 白色对勾（`scaleIn(0.8f)`）；点击加 `VirtualKey` 振动 |
| **"跟随壁纸"块** | 与主色块同尺寸，用 `dynamicLight/DarkColorScheme` 的两个容器色画半圆（API 31 以下退化为当前 scheme） |
| **"自定义"块** | 排最后：虚线/加号图标；点击打开**我们自己的取色盘**（`ColorPickerDialog`），初始色 = 当前种子色 |
| **明暗四连** | `SingleChoiceSegmentedButtonRow`（项目里已经在用）或自绘；4 段：跟随系统 / 浅色 / 深色 / 纯黑；只有图标 + `contentDescription` |
| **下拉** | 复用项目里的 `SettingDropdownItem` / `SegmentedChoice`，不要引入新控件 |

### 5.3 数据模型与迁移（关键）

现在 `AppearanceColorMode` 把"明暗"和"是否取色"耦合成了 6 个值（`system` / `light` / `dark` / `monet_system` / …）。
SukiSU 是"明暗 4 选 + 规格"，取色只体现在"主色块=跟随壁纸"。

**建议改成两个正交字段**：

| 字段 | 值 | 说明 |
|---|---|---|
| `themeMode` | SYSTEM / LIGHT / DARK / **BLACK**（新增） | 只表达明暗；BLACK = AMOLED 纯黑 |
| `seedColor` | `0` = 跟随壁纸 / 具体 ARGB | 已有，语义不变 |
| `palette` | 9 种 | 已有 |
| **`colorSpec`** | SPEC_2021 / SPEC_2025（默认 2025） | **新增** |

⚠️ 迁移：旧 id `monet_system/light/dark` → `themeMode` 保持原明暗、`seedColor` 置 0（=跟随壁纸）。
写成一次性迁移函数 + 单测，**不要**在读取处到处判旧 id。

### 5.4 颜色切换的过渡

SukiSU 给整个 `ColorScheme` 的每个字段套了 `animateColorAsState(spring())`。
我们照这个思路做一个 `ColorScheme.animateAsState()`，好处是：换主色/风格时，**页面里的颜色是渐变过去的**，不是硬切。
（注意：这会带来每帧重组，但只在主题页/切换瞬间，可接受。）

### 5.5 自定义主色（"之前用 Java 取色还原不出来"的那件事）

我们已经有了自绘的色环 + 方板取色盘（`ColorPickerDialog`，2026-11-21 完成）。
这里只要：

```kotlin
ColorPickerDialog(
    title = "主色",
    initialRgb = currentSeed and 0xFFFFFF,
    alphaPercent = 100,
    alphaEditable = false,
    onConfirm = { rgb, _ -> onSetSeedColor(0xFF000000.toInt() or rgb.toInt()) },
)
```

即"主色"固定不透明（`seedColor` 里 0 表示跟随壁纸，不能是透明色）。

---

## 6. 方案 B：页面过渡 + 预测性返回

### 6.1 目标效果（参数先定死，方便以后调）

| 量 | push（进二级页） | pop（返回） |
|---|---|---|
| 新页水平位移 | `+100% → 0`（从右滑入） | `0 → +100%` |
| 新页圆角 | `16dp → 0`（滑到一半就基本直了） | `0 → 16dp` |
| 新页阴影 | 轻微（8dp，alpha 0.15） | 反向 |
| 旧页缩放 | `1 → 0.92`（以中心为锚点） | `0.92 → 1` |
| 旧页压暗 | 黑色 `alpha 0 → 0.32` | `0.32 → 0` |
| 时长 | 300ms（`FastOutSlowIn`）；预测性返回时**跟手**，不用时长 | 同左 |

> 压暗是"旧页上面盖一层黑"，不是把旧页做成半透明——半透明会漏出底下的白/黑底，看起来发灰。

### 6.2 三条实现路线对比

| 路线 | 做法 | 优 | 劣 |
|---|---|---|---|
| A. 增强现有 `NavHost` | 只改 `enter/exit/popEnter/popExit` | 改动最小 | **做不到"旧页压暗"**：`NavHost` 不给每层的过渡进度，压暗必须叠在旧页上；而且它没有预测性返回的可控进度 |
| B. 迁移 Navigation 3 | `androidx.navigation3:navigation3-ui:1.2.2`（已有稳定版） | 官方方向；`NavDisplay` 有 `predictivePopTransitionSpec` + `LocalNavAnimatedContentScope`，场景/过渡是一等公民 | 路由要改成 `NavKey` + `@Serializable`（要加 kotlinx-serialization 插件）；`entryProvider`/装饰器整套重写；对 7 个二级页来说迁移成本 > 收益 |
| **C. 自建栈（推荐）** | 自己维护 `List<Route>`，只渲染**栈顶两层**，用一个 `Animatable<Float>` 驱动全部过渡 | 无新依赖、无插件；进度完全可控，**预测性返回天然支持**（把手势进度直接喂给同一个 `Animatable`）；参数好调 | 自己管回栈/状态保存（用 `SaveableStateHolder` 按 entry key 保存）；`BackHandler` 要接过来 |

**推荐 C**。SukiSU 之所以"免费"拿到这些效果，是因为 miuix-nav 就是一套自建栈；我们只是把那 200~300 行自己写一遍，换来零依赖和完全可控。

### 6.3 自建栈的结构

```kotlin
// 1) 路由：现有 7 个二级页（capture_mode / debug / about / theme / config_editor / custom_editor / group_detail）+ 参数（configId / groupId）
data class SubRoute(val key: String, val arg: String? = null)

// 2) 栈：只存"当前深度"，行为与 navController 一致
class AppNavigator(initial: SubRoute) {
    val stack = mutableStateListOf(initial)
    fun push(route: SubRoute)
    fun pop(): Boolean
    fun popTo(key: String)
}

// 3) 宿主：最多画两层
@Composable
fun StackHost(navigator: AppNavigator) {
    val depth = navigator.stack.size
    val p = remember { Animatable(1f) }      // 0 = 完全在上一个页，1 = 完全在当前页
    // 层 1（下）：stack[depth-2]，scale = lerp(1f, .92f, p)，上面盖黑色 scrim alpha = .32f * p
    // 层 2（上）：stack[depth-1]，translationX = (1 - p) * width，圆角 = 16dp * (1 - p)
}
```

要点：

- **只渲染两层**：更深的层不组合，状态交给 `SaveableStateHolder`（`key = entry.id`），避免"进三级页回来丢草稿"；
- **push/pop 共用同一个进度**：push 时 `p: 0→1`；pop 时 `p: 1→0`，动画结束再 `stack.removeLast()`；
- 圆角用 `Modifier.clip(RoundedCornerShape(16.dp * (1 - p)))` + `graphicsLayer { translationX }`，**不要**用 `AnimatedContent`（它拿不到连续进度）。

### 6.4 预测性返回的接线

```kotlin
PredictiveBackHandler(enabled = navigator.stack.size > 1) { progress ->
    // progress 是 Flow<BackEventCompat>，0f..1f
    p.snapTo(1f - progress.progress)      // 直接跟手
    if (progress.progress >= 1f) {        // 松手且过阈值
        withContext(NonCancellable) { p.animateTo(0f); navigator.pop() }
        progress.finish()
    } else if (!progress.isAnimating) {   // 取消
        p.animateTo(1f)
        progress.cancel()
    }
}
```

另外两件事：

1. **Manifest 要开** `android:enableOnBackInvokedCallback="true"`（API 33+ 的预测性返回开关）；
2. 现在"在配置/设置页按返回先回主页"的 `BackHandler` 要继续生效——它管的是 **pager**，与二级页栈不冲突：
   `enabled = 栈深 == 1 && pager 不在首页`。

### 6.5 边界与坑

| 坑 | 处理 |
|---|---|
| 预测性返回只有 **API 33+**（`PredictiveBackHandler` 在低版本自动退化为普通返回） | 逻辑里不假设一定拿到 progress |
| 手势进行中又触发 `navigate()` | push 前先 `p.snapTo(1f)` 并取消进行中的手势 |
| 深色/浅色主题切换时过渡层颜色 | scrim 用 `Color.Black.copy(alpha)`，不跟随主题（SukiSU 也是黑） |
| 横屏/平板（可用宽度大） | 位移仍按 `100%`，缩放压暗参数不变；预览卡按屏宽比自适应 |
| 页面里自己处理返回（如触摸区域编辑器） | 它在栈顶时 `PredictiveBackHandler(enabled = true)` 会先接管；若那个页面要拦截，给它一个 `canPop` 回调 |
| 快捷方式/外部 Intent 进入 | 走原来的入口，不往栈里塞特殊 state |

---

## 7. 分阶段实施（每阶段可单独编译、单独验证）

| 阶段 | 内容 | 验收 |
|---|---|---|
| **P0** 主题引擎补字段 | `themeMode` 加 BLACK、`colorSpec` 字段 + 迁移 + 单测；`ColorScheme.animateAsState()` | 单测全绿；换风格不闪 |
| **P1** 主题页重做 | 预览卡、色块（含跟随壁纸/自定义）、四连按钮、两个下拉；自定义主色接取色盘 | 与 SukiSU 截图并排对比 |
| **P2** 过渡 + 预测性返回 | `AppNavigator` + `StackHost` + `PredictiveBackHandler` + manifest 开关；替换 `NavHost` | 三件事：push 有压暗、pop 反向、手势跟手 |
| **P3** 收尾 | 「高级」分组（预测性返回开关、切换缩放滑块）；文档；回归 | 全量单测 + release |

---

## 8. 验收清单

- 主题页：浅色/深色/纯黑三种下预览卡、色块、下拉都正常；切换主色时整页颜色**渐变**过去；
- 色块：选中缩放 + 对勾；"跟随壁纸"在 Android 12+ 用壁纸色、以下用主色；"自定义"能开取色盘并立刻生效；
- 过渡：进/出二级页时旧页**缩小 + 变黑**；圆角随进度；无跳帧；
- 预测性返回：从屏幕边缘慢慢拖，页面跟手；松手未过阈值回弹、过了则完成；返回后栈正确；
- 回归：pager 三页切换、底栏、无障碍返回、`BackHandler`（配置/设置页先回主页）都不坏；
- 单测：导航栈的 push/pop/popTo、迁移函数、颜色动画的纯逻辑部分。

---

## 9. 风险与回滚

| 风险 | 影响 | 回滚 |
|---|---|---|
| 自建栈替换 `NavHost` | 导航相关回归面较大（7 个二级页） | 保留 `NavHost` 版本在一个分支/提交里，`git revert` 即可 |
| 预测性返回与现有 `BackHandler` 冲突 | 返回行为异常 | 开关默认关（P3 才暴露给用户） |
| 主题页改动大 | 视觉回归 | 按 P1 单独提交，便于对比 |
| **GPL 污染** | 法务风险 | 代码全部自己写；评审时禁止从 SukiSU 复制片段 |

---

## 10. 待拍板

1. **明暗模式怎么合并**：按 §5.3 拆成 `themeMode(4) + seedColor + colorSpec`（推荐），还是保留现在的 6 个 `monet_*` 再叠 AMOLED？
2. **"跟随壁纸"的位置**：放主色块第一位（SukiSU 做法，推荐），还是保留现在"取色模式"那一组按钮？
3. **过渡参数**：§6.1 那组（缩放 0.92 / 压暗 0.32 / 圆角 16dp / 300ms）先按这个来？
4. **要不要"滑动返回"**（从屏幕边缘横滑关闭二级页）：SukiSU 有，我们做不做？
