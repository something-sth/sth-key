# 界面优化计划

> ## 状态：Miuix 已删除（2026-10-25）
>
> 用户决定：**删掉整个 Miuix 主题，只用 Material**。原话:
>
> > miuix 其实我觉得这个东西只会白白让我们工作量增加，而且半天也没修好，
> > 你把 miuix 主题删了吧，我们还是使用 material 就好了
>
> 已删除的东西:
>
> | 项 | 内容 |
> |---|---|
> | 依赖 | `miuix-ui` / `miuix-icons` / `miuix-preference`（0.9.0） |
> | 源码 | `SthKeyFloatingBottomBar.kt`、`ui/component/gesture/`（DampedDragAnimation 等）、`ui/theme/UiMode.kt` |
> | 枚举 | `UiMode`（皮肤）、`BottomBarStyle`（底栏样式） |
> | 偏好 | `ui_mode`、`bottom_bar_style` |
> | 组件分派 | `ScreenScaffold` / `SettingsCard` / `SectionHeader` / `SectionHint` / `CardDivider` / `SettingItem` / `SwitchItem` / `SettingDropdownItem` 全部还原成纯 Material |
>
> 保留下来、仍然有效的:
>
> * `AppearanceColorMode`（明暗模式）—— 它对 Material 是**真正生效**的
> * `SettingDropdownItem`（行内 `DropdownMenu`，纯 Material）
> * 主页工作状态大卡片、一级页面 `HorizontalPager`、无障碍采集通道等
>
> 下面的 §4 / §6 保留作**历史记录**：Miuix 的版本约束、踩过的坑都在里面，
> 将来若要重新引入可以直接参考，不用再重踩。


> 本文记录 UI 优化的**分步计划**与已做的取舍，防止跨轮次遗忘用户的要求。
>
> 参考实现（**只读，绝不修改**）：`D:\Project\_sukisu\SukiSU-Ultra-main`

---

## 1. 总计划（用户口述，按顺序）

| 步 | 内容 | 状态 |
|---|---|---|
| **①** | 主页「工作状态」卡片换成 **SukiSU / KSU 管理器风格**的大卡片 | ✅ 完成 |
| **①b** | 调试页的「采集模式」搬进卡片（点卡片进入），卡片可点 | ✅ 完成 |
| **②** | **底栏**优化：一级页面换成 `HorizontalPager`，切页时中间那页也渲染 | ✅ 完成 |
| **③** | 接入 **Miuix** UI 组件库 + 主题相关设置 | ⏳ 待做 |

> 用户的原话:
>
> > 这一步我们把工作状态卡片换一下，下一步把底栏优化一下，
> > 让主页切到设置栏的过程中，配置页也渲染在滚动过程中，
> > 再下一步接入 miuix 的 ui 以及主题相关设置
>
> > 我赞同你说的"A（真正的左右滑动 + 底栏指示条，这才是现在 Android 的
> > 标准做法）"，按你节奏来做就行

---

## 2. ① 工作状态大卡片（已完成）

### 2.1 用户要的样子

> > 主页卡片显示依次是：左上方 **LKM 角标**，左下方「**工作中**」主标题，
> > 左下方「**版本**」副标题，我认为可以借鉴，
> > 左上方改为「**Root / Shizuku / 无障碍**」，
> > 左下方主标题「**监听中**」，副标题「**v 版本号**（从 versionname 获取）」

### 2.2 与 SukiSU 的对应关系

SukiSU 的实现在
`manager/app/src/main/java/com/sukisu/ultra/ui/screen/home/HomeMaterial.kt`
的 `ActiveStatusCard`：

| SukiSU | 我们 | 说明 |
|---|---|---|
| `Surface(color = primary, shape = RoundedCornerShape(28.dp))` | 同 | ⚠️ 但**颜色按状态变**，见下 |
| `Box(drawBehind { drawStatusRings(...) })` | 同 | 右上角三层同心圆 + 一个实心点 |
| 左上 `StatusTag("LKM")` | `StatusTag("Root"/"Shizuku"/"无障碍")` | 反色角标 |
| 左下 `Text(displaySmall, FontWeight.Black)` | `Text(captureState.label)` | 取值就是「监听中」 |
| 左下 `Text(bodyMedium, Monospace)` | `Text("v" + BuildConfig.VERSION_NAME)` | 等宽字体 |
| `heightIn(min = 188.dp)` + `SpaceBetween` | 同 | 角标顶到上、标题沉到下 |
| `drawStatusRings`: 半径 112/84/56/18dp，圆心距右上角 (36dp, 32dp) | 同 | 数字照抄 —— 那是调好的观感 |
| 第 2 层描边用虚线（4dp 实 / 8dp 空） | 同 | 三层全实线会像水印 |
| `StatusTagMaterial`: 圆角 4dp + padding(2dp, 4dp) + labelSmall | 同 | 小尺寸才像"角标" |

### 2.3 ⚠️ 刻意**不**照抄的地方

| 不照抄 | 理由 |
|---|---|
| **配色只用 `primary`** | SukiSU 那张卡只表示"工作中"，而我们还要表示"启动中 / 未监听 / **失败**"。⚠️ 全刷成一种色的话，用户看不出出事了 |
| 角标写"当前模式" | ⚠️ `requestedMode` 可能是「自动」，用户看不出**实际**用哪条通道 —— 所以角标用的是 `channel.label`（实际通道） |

状态 → 配色：

| 状态 | 容器色 | 内容色 |
|---|---|---|
| 监听中 | `primary` | `onPrimary` |
| 启动中 | `tertiary` | `onTertiary` |
| 未监听 | `surfaceVariant` | `onSurfaceVariant` |
| 监听失败 | `errorContainer` | `onErrorContainer` |

### 2.4 ⚠️ 拆成**两张卡**，不是一张

| 卡 | 内容 |
|---|---|
| `WorkStatusCard` | 视觉主体：角标 + 状态标题 + 版本号 + 右侧「重启监听」图标 |
| `WorkStatusDetailCard` | 诊断细节：三条通道可用性、实际通道与设备数、失败原因、无障碍能力边界红字 |

> ⚠️ 大卡片的价值就在**留白**。把设备数、通道可用性那些塞回去，
> 立刻变回原来那个"信息面板"的样子，等于白换。

⚠️ 细节里**必须保留**的两条（以前用户明确要求过，别在重构时弄丢）：

1. **无障碍模式下不显示设备数** —— 它没有"设备"这个概念，显示会是永远 0 的假数字。
   判据用 `channel.keyboardOnly`，**不要**写 `channel == ACCESSIBILITY`。
2. **无障碍能力边界的红字** —— 只在 `channel.keyboardOnly == true` 时出现。
   ⚠️ 不能用"模式是 ACCESSIBILITY"判：AUTO 降级到无障碍时 `requestedMode` 是「自动」，
   只看模式那条红字就不会出现，而那时恰恰最需要它。

### 2.5 「重启监听」为什么搬进大卡片

它是这张卡**唯一的动作**，而且与"现在读得怎么样"直接相关 ——
用户看到「监听失败」时视线就在这一块，不用再去下面找按钮。

⚠️ 用 `IconButton(32.dp)` + 半透明图标，不抢主标题的视觉重心。

---

## 3. ② 一级页面 pager（已完成）

> 用户的原话:
>
> > 下一步把底栏优化一下，让主页切到设置栏的过程中，
> > **配置页也渲染在滚动过程中**
>
> > 我赞同你说的"A（真正的左右滑动 + 底栏指示条，这才是现在 Android 的
> > 标准做法）"

### 3.1 改了什么

| 以前 | 现在 |
|---|---|
| 三个一级页面是 `NavHost` 的三条路由（`home` / `config` / `settings`） | 一个 `SubRoutes.MAIN` 路由，里面装 `HorizontalPager` 三页 |
| 切页靠 `AnimatedContent` 的 `slideIntoContainer` | 真的横向滚动（手指能滑、点底栏也滚动过去） |
| ⚠️ 过渡期间**中间那一页压根没被组合** —— 主页→设置时配置页不存在 | 三页都在 pager 里，主页→设置的途中配置页真实地滑过去 |
| `TopDestination(route, label)` | `TopDestination(label)`，页序 = `ordinal` |

⚠️ **`route` 字段已删** —— 一级页面不再是路由名。页序号直接用 `ordinal`，
**不要另存一份 `page` 数字**（两处各写一份迟早对不上）。

### 3.2 ⚠️ 顺带去掉的老补丁

以前切标签要盖一层透明层拦触摸（`tabTransitioning`），因为
**退场的旧页面还在合成里、还在收事件** —— 实际踩过：从配置页切回主页后
立刻点悬浮窗开关，那一击落在了还没退场的配置列表上，把配置切成了列表里对应位置的那个。

⚠️ pager 里每一页都在**自己正确的位置**上，手指落点所见即所得，
所以那层拦截**不再需要**（留着反而会误伤滑动）。

### 3.3 ⚠️ 必须补的一处行为回归：返回键

| 以前（三条路由） | 换成 pager 之后（不补的话） |
|---|---|
| `switchTab` 用 `popUpTo(HOME)`，栈是 `[HOME, CONFIG]` | 栈里只剩 `[MAIN]` |
| 在配置页按返回 → **回主页** | 在配置页按返回 → ⚠️ **直接退出应用** |

⚠️ 用 `BackHandler(enabled = currentRoute == MAIN && 不在主页)` 补回来。
`enabled` 必须判 `currentRoute == MAIN` —— 停在二级页时返回键要交给
NavHost 自己 pop，不能被它抢走。

### 3.4 几个刻意的取舍

| 取舍 | 理由 |
|---|---|
| 点底栏用 `animateScrollToPage` + `tween(320)`，**不用** `scrollToPage` | 瞬移的话中间那页一帧都看不到，等于白改 |
| 不用默认弹簧 | 跨两页时弹簧先冲过头再回弹，像"抖了一下" |
| 底栏仍用 Material3 的 `NavigationBar` | 它自带选中指示条和过渡动画，不用自己画 |
| ⚠️ 底栏**不跟着滑动连续移动** | 想做就得分偏移量重画整个底栏，等于放弃 `NavigationBar` 的全部内建行为，收益只是"滑到一半两个图标都半亮"，**不值得** |
| `rememberPagerState` 提到 `MainScaffold` 一层 | 底栏要读它决定高亮；且这一层一直活着，进二级页再返回不会丢页号 |

---

## 4. ③ Miuix + 主题（调研完成，待决策）

> 用户的原话:
>
> > 接下来对 miuix 要做什么，**这方面你也做成 sukisu 那样吧**，
> > 毕竟真的很好看，sukisu 的审美无需多言。
> > 主题设置里 sukisu 也做了**悬浮底栏**和**液态玻璃底栏**，可以使用，挺好看的。
> > 你先看一下 miuix 怎么写吧，有问题问我

### 4.1 SukiSU 是怎么接的（已读源码）

| 机制 | 说明 |
|---|---|
| 依赖 | `top.yukonga.miuix.kmp:miuix-ui / -icons / -nav / -preference / -blur`，版本 **0.9.4** |
| 皮肤切换 | `enum class UiMode { Miuix("miuix"), Material("material") }` + `LocalUiMode = staticCompositionLocalOf { Material }` |
| 每个界面 | 拆成 `XxxMaterial.kt` / `XxxMiuix.kt`，外层 `Xxx.kt` 里 `when (LocalUiMode.current)` 分派 —— **SukiSU 有 60 多处这样的分派** |
| 主题 | `MaterialTheme`（含 materialKolor 动态取色）与 `MiuixTheme` 两套，各有一个 `XxxTheme` 包装 |
| 底栏三个开关 | `enableBlur` / `enableFloatingBottomBar` / `enableFloatingBottomBarBlur`（都在 `SettingsRepository`） |
| 底栏 | `BottomBarMaterial.kt` / `BottomBarMiuix.kt`，后者用 `LocalMainPagerState` 驱动（⭐ 他们一级页面也是 pager，和我们 ② 的做法一致） |
| 液态玻璃 | `FloatingBottomBar.kt`（**514 行**），改编自 `compose-miuix-ui` 示例 + `Kyant0/AndroidLiquidGlass`（Apache 2.0），依赖 `miuix-blur` 的 `Backdrop` / `LayerBackdrop` / `highlight.BloomStroke` |

### 4.2 ⚠️⚠️ 版本矩阵（**这是关键约束，已实测**）

| miuix | 需要 kotlin-stdlib | 需要 compose-foundation |
|---|---|---|
| 0.9.0 | 2.3.20 | 1.10.3 |
| 0.9.1 | 2.3.21 | 1.11.0 |
| 0.9.2 / 0.9.3 | 2.4.0 | 1.11.1 |
| 0.9.4（SukiSU 用的） | **2.4.20** | 1.12.0 |

⚠️ 我们的 Kotlin 编译器是 **2.2.10**，而它"能读到的元数据上限是 2.3.0"。
用 0.9.4 直接编译报错：

```
Module was compiled with an incompatible version of Kotlin.
The binary version of its metadata is 2.4.0, expected version is 2.2.0.
```

⚠️ 而**升 Kotlin 不是改一个数字**：本项目用的是 **AGP 9 的内建 Kotlin 支持**
（`plugins { alias(libs.plugins.android.application); alias(libs.plugins.kotlin.compose) }`，
**没有** `org.jetbrains.kotlin.android`），所以编译器版本由 AGP 决定。
实测三种升法都失败：

| 尝试 | 结果 |
|---|---|
| toml 里 `kotlin = "2.4.20"` | 只改了 **Compose 插件**，编译器仍是 2.2.0 |
| 加 `android.builtInKotlin=false` + 独立 `kotlin-android` 插件 | ❌ `plugin is already on the classpath with an unknown version` |
| AGP 9.2.1 → 9.4.1 | ❌ 同一个错（AGP 9.4.1 的 POM 同样只声明 kotlin 2.2.10） |

⭐ **结论：用 miuix `0.9.0` 即可，工具链一个字都不用改**（已实测编译 + 619 测试通过）。

### 4.3 ⚠️ 0.9.0 有 / 没有的东西

| 有 | 说明 |
|---|---|
| `NavigationBar` + `NavigationBarItem` | 普通底栏 |
| **`FloatingNavigationBar` + `FloatingNavigationBarItem`** | ⭐ **悬浮底栏是内建的**，不用照搬 SukiSU 那 514 行 |
| `NavigationBarDisplayMode` | IconAndText / IconOnly / TextOnly / IconWithSelectedLabel |
| `FloatingNavigationBarDisplayMode` | IconAndText / IconOnly / TextOnly |
| `miuix-blur` 的 `Backdrop` / `LayerBackdrop` / 高斯模糊 | 磨砂玻璃的原料 |

| ❌ 没有 | 影响 |
|---|---|
| `blur.highlight.*`（`BloomStroke` / `LightSource`） | ⚠️ **SukiSU 那种"液态玻璃"的高光/折射做不出来** —— 它 514 行里有相当一部分是为这些高光服务的 |
| `miuix-shader` / `miuix-squircle`（0.9.4 才有） | 平滑圆角（squircle）拿不到 |

### 4.4 ⚠️⚠️ 硬阻塞：`miuix-blur` 要求 **minSdk 31**，我们是 **30**

实测报错：

```
uses-sdk:minSdkVersion 30 cannot be smaller than version 31
declared in library [top.yukonga.miuix.kmp:miuix-blur-android:0.9.0]
```

| 模块 | minSdk |
|---|---|
| `miuix-ui` / `miuix-icons` / `miuix-preference` / `miuix-core` | **23** ✅ |
| `miuix-blur` | **31** ❌ |

⚠️ 所以"要不要玻璃"直接等价于"**要不要放弃 Android 11（API 30）**"。

三个选项：

| 方案 | minSdk | 能拿到 | 代价 |
|---|---|---|---|
| **A** | 保持 30 | 普通底栏 + **悬浮底栏**（实色/半透明） | ⚠️ **没有模糊、没有玻璃** |
| **B** | 提到 31 | 上面 + **磨砂模糊底栏** | 放弃 API 30（Android 11） |
| **C** | 保持 30 + `tools:overrideLibrary` | 同上，但 API 30 走降级分支 | ⚠️ 官方明确说 "may lead to runtime failures" —— 若 miuix-blur 在类加载期就碰 shader API 会在 API 30 上崩 |

### 4.5 待用户决策的三个问题

1. **minSdk 30 → 31 可以吗？**（= 液态/磨砂玻璃要不要）
2. **双皮肤还是只换 Miuix？** SukiSU 是 `UiMode` 可切换（每个界面两份，60+ 处）；只换 Miuix 的话工作量小一个数量级，但用户没得选。
3. **主题设置放哪？** 建议在「设置」里新开一个二级页「外观」：皮肤（Material/Miuix）、深浅色、主题色、底栏样式（普通/悬浮/玻璃）。

---

### 4.5 ✅ 实际落地的东西（第一版）

| 项 | 做了什么 |
|---|---|
| 依赖 | `miuix-ui` / `miuix-icons` / `miuix-preference` **0.9.0**（不引 `miuix-blur`） |
| 主题 | `SthKeyTheme` 里**套了一层 `MiuixTheme`**，外面 Miuix、里面 Material3 —— 存量二十来个页面不用重写 |
| 底栏 | 换成 Miuix 的 `NavigationBar`（标准）/ `FloatingNavigationBar`（悬浮），设置里可切 |
| 设置页 | 新增「外观」分组，用 Miuix 的 `Card` + `OverlayDropdownPreference`（照 SukiSU 排版） |
| 偏好 | `ui_mode` / `appearance_color_mode` / `bottom_bar_style`（⚠️ 存**字符串 id**，不存 ordinal） |
| 单测 | `AppearanceTest` 7 条（id 稳定、往返一致、没设置过 / 认不出都回落默认） |

⚠️ **刻意没做的**:

| 没做 | 原因 |
|---|---|
| 液态玻璃 / 磨砂 | 用户明确说「不做磨砂玻璃」；且 `miuix-blur` 要 minSdk 31 |
| ⚠️ **色彩风格（palette）** | 做过又删了：实测**一点用没有**（Material 侧不读 palette；Miuix 侧 `keyColor = null` 时也不生效），且不是 SukiSU 的那种做法。用户要求删除 |
| Material / Miuix 双皮肤 | 用户选了「只换 Miuix」；双份要把 15 个页面各写两遍 |
| 按钮等其它组件的 Miuix 化 | ⏳ **下一步** |

### 4.6 ⚠️ 版本坑（复用结论，别再试一遍）

| 想用 | 结果 |
|---|---|
| miuix 0.9.4（SukiSU 的版本） | ❌ 需要 Kotlin 2.4.20 元数据，我们编译器只能读到 2.3.0 |
| 升 Kotlin | ❌ 本项目用 AGP 9 内建 Kotlin，编译器版本由 AGP 决定；改 toml / `android.builtInKotlin=false` / 升 AGP 9.4.1 **全都失败**（`plugin is already on the classpath with an unknown version`） |
| **miuix 0.9.0** | ✅ **能用**，工具链一个字没改 |
| `miuix-blur` | ❌ 要求 minSdk **31**，我们是 30 |

---

## 5. 相关文件

| 文件 | 关系 |
|---|---|
| `ui/feature/home/HomeScreen.kt` | ①`WorkStatusCard` / `WorkStatusDetailCard` / `StatusTag` / `drawStatusRings` |
| `ui/feature/capture/CaptureModeScreen.kt` | ①b 采集通道页（原调试页的「采集模式」） |
| `ui/feature/debug/DebugScreen.kt` | ①b 只剩真正的诊断（能力检测 / Shizuku 自检 / 悬浮窗 / 日志） |
| `ui/AppNavigation.kt` | ②`TopDestination`（无 route，页序=ordinal）、`SubRoutes.MAIN` |
| `MainActivity.kt` | ②`MainScaffold`（pager + 底栏 + 返回键）/ `SthKeyBottomBar` / `BlockInputWhenHidden` |
| `capture/InputSource.kt` | `CaptureState`（卡片标题文案的单一真源）、`CaptureChannel` |
| `domain/capture/CaptureCapabilities.kt` | 三条通道的可用性 |

---

## 6. ⚠️⚠️ 双皮肤（Material / Miuix）—— **必须照 SukiSU 做**

> 用户的原话:
>
> > **不应该 material 的时候用 material 组件，切到 miuix 再换成 miuix 组件吗**，
> > 你参考一下 sukisu 呗
>
> > 对，是这个意思没错，但是**别自己乱改，可以写文档提醒自己参考 sukisu**，
> > **sukisu 这方面很完善**

### 6.1 ⚠️ 铁律

1. **动手前先读 SukiSU 对应实现**（`D:\Project\_sukisu\SukiSU-Ultra-main`，**只读**）
2. **不要自创组件形态** —— 它怎么做就怎么对应
3. 每个分派点都要在注释里写明**对应 SukiSU 的哪个文件/组件**

### 6.2 SukiSU 的机制（已读源码）

| 机制 | 位置 |
|---|---|
| `enum UiMode { Miuix("miuix"), Material("material") }` | `ui/UiMode.kt` |
| `LocalUiMode = staticCompositionLocalOf { Material }` | 同上 |
| 页面级分派 | 每个 `Xxx.kt` 里 `when (LocalUiMode.current) { Miuix -> XxxMiuix(); Material -> XxxMaterial() }` |
| **设置列表的组件级分派** | ⭐ `ui/component/material/SegmentedList.kt`（`SegmentedColumn` / `SegmentedListItem` / `SegmentedSwitchItem` / `SegmentedDropdownItem`）与 Miuix 的 preference **一一对应** |
| Material 侧主题 | `ui/theme/MaterialTheme.kt`（materialKolor 动态取色 + `MaterialExpressiveTheme`） |
| Miuix 侧主题 | `ui/theme/MiuixTheme.kt`（`ThemeController` + `MiuixTheme`） |

⭐ **关键发现:SukiSU 在设置列表上本来就是"组件级"的** ——
它自建了一套 Material 侧列表组件，和 Miuix preference 一一对应。
所以我们照它这么做**不算自创**。

### 6.3 我们的对应表（**改任何一项都要先对照这张表**）

| 用途 | Material 侧 | Miuix 侧 | 我们的分派点 |
|---|---|---|---|
| 页面骨架 | Material3 `Scaffold` + `TopAppBar` | Miuix `Scaffold` + `TopAppBar` | `Common.kt` → `ScreenScaffold` |
| 分组标题 | `Text`(titleMedium/primary) | `SmallTitle` | `Common.kt` → `SectionHeader` |
| 说明文字 | `Text`(bodyMedium) | `Text`(body2 / onSurfaceVariantSummary) | `Common.kt` → `SectionHint` |
| 卡片容器 | `Card`(surfaceContainer) | `Card` | `Common.kt` → `SettingsCard` |
| 分隔线 | `HorizontalDivider` | `HorizontalDivider` | `Common.kt` → `CardDivider` |
| 可点行 | 自绘 `Row` | `ArrowPreference` | `SettingItem.kt` → `SettingItem` |
| 开关行 | `Switch` | `SwitchPreference` | `SettingItem.kt` → `SwitchItem` |
| 下拉行 | `RadioButton` 对话框 | `WindowDropdownPreference` | `SettingDropdownItem.kt` |
| 底栏 | Material3 `NavigationBar` | Miuix `NavigationBar` / `FloatingNavigationBar` | `MainActivity` → `SthKeyBottomBar` |

### 6.4 ⚠️ 已踩过的坑（别重犯）

| 坑 | 后果 | 正确做法 |
|---|---|---|
| 用 `OverlayDropdownPreference` | ⚠️ **点了完全没反应** —— 它把弹层挂到 Miuix 的根 `Scaffold`（`LocalRootPopupStates`），全项目**只有 Miuix 的 `Scaffold` 会渲染**那些状态 | 用 **`WindowDropdownPreference`**（走真 `Dialog`，不依赖 Scaffold 祖先） |
| 外观那段写死 Miuix 组件 | ⚠️ 同一页里两种风格混着，用户说"怪怪的" | 全部走分派组件 |
| 直接 import 同名组件 | `NavigationBar` / `NavigationBarItem` 是 `RowScope` 扩属函数，全限定名解析不了；两家同名会撞 | 用**别名 import**（`MaterialNavigationBar` 等） |
| miuix 0.9.0 没有 `Divider` | 编译失败 | 只有 `HorizontalDivider` / `VerticalDivider` |
| `ThemeController` 首个参数名 | 0.9.0 是 `colorSchemeMode`，不是 0.9.4 的 `colorMode` | 认 getter 名 |
---

## 7. v2.7.1 发布状态

| 项 | 状态 |
|---|---|
| 版本号 | `versionCode = 271` / `versionName = "2.7.1"` |
| 界面皮肤 | Material / **Miuix（实验性）**，`UiMode.MIUIX.label` 已带标注 |
| 更新公告 | `AppAnnouncement` 新增 2.7.1 条目（纯文本 + 可选 `**粗体**`） |
| 测试 | 626 条全绿 |
| 构建 | debug + release 均通过（R8） |

### 7.1 ⚠️ 已知问题（公告里也写了）

**Miuix 悬浮底栏的高亮块在某些切换下位置不准。** 已定位并修过两轮:

1. ✅ 手势收不到 → 移植 SukiSU 的 `inspectDragGestures`（`Initial` pass、按下即触发）
2. ✅ 副本被父级约束钳住 → `requiredWidth` + `wrapContentWidth(unbounded)`
3. ✅ `currentPage` 的中间值把动画拽回去 → 改用 `settledPage`
4. ❌ **仍偶发错位**，未定位

⚠️ 排查手段已经留在代码里:三条低频日志（按下 / 松手 / 重组），
可在「设置 → 调试 → 运行日志」直接看，不需要 adb。

⚠️ 下一轮若要继续查，建议方向:比较 SukiSU 的 `LocalMainPagerState` 与我们的
`PagerState` 在**拖动过程中**的 `selectedPage` 语义差异 ——
SukiSU 的 `mainState.selectedPage` 很可能不是 pager 的实时页，而是它自己维护的目标页。

### 7.2 公告的 markdown 支持

⚠️ 只实现了 `**粗体**`（见 `AnnouncementDialog.markdown()`）。
理由:不引入第三方库（一个弹窗不值得多一个依赖，且那些库会误伤中文文案里的 `*`/`#`/`-`）；
公告本身已有 `Section` 结构与 `·` 前缀，再叠一层列表/标题会乱。
⚠️ 未配对的 `**` 会**原样保留**，不会吞掉后面的文字。
### 7.3 更新公告的写作规范（用户明确要求过两次）

| 规矩 | 说明 |
|---|---|
| **不许用 emoji / 颜文字** | 旧公告里 0 个 emoji，只用 `「」` 与 `→`。我第一版写了 `⚠️`，被用户指出 |
| **不把"开发过程中自己踩的坑"写成"修复"** | ⚠️ 这是最重要的一条 |

⚠️ 第二点的道理:一次发版里"修复"的，应该只写**上一个正式版对用户真实存在的问题**。

⚠️ 反例（我第一版写错的内容）:

| 我写的 | 为什么错 |
|---|---|
| 「修复：按键对应错误」 | 无障碍通道是**本版新增**的功能，键码错位从没发布过，用户没见过 |
| 「修复：无障碍被系统取消后不提示」 | 同上，校验机制也是本版新增的 |
| 「修复：有 root 时点开悬浮窗会卡一下」 | 这正是我自己这一版写错的脚本 |
| 「修复：开启悬浮窗按钮点不动」 | 删 `SYSTEM_ALERT_WINDOW` 引起的问题，也在本版之内 |

⚠️ 判断方法:去上一版公告里搜一下这个功能是否存在（例如 2.7.0 公告里
`无障碍` 与 `Shizuku` 各出现 0 次 → 说明无障碍整体是本版新增，
相关内部 bug 一律不能写成"修复"）。

### 7.4 主次要分清

⚠️ 用户指出的第三点：**本版的主题是无障碍适配**（让没有 root / Shizuku、
也没有无线调试的机型能用上），而我把 Miuix 界面放在了第一位。

⚠️ 现在的顺序（按用户认可的主次）:
1. 新增：无障碍采集通道（本版重点）
2. 变更：不再需要「显示在其他应用上层」权限
3. 新增：界面皮肤 Miuix（实验性）
4. 新增：外观设置
5. 新增：主页工作状态卡片
6. 改进：一级页面可以左右滑动
7. 改进：底栏
8. 其它

---

## 8. Material 主题细化（v2.7.1 后半段）

用户的要求:

> 现在我想把 material 的 ui 主题也做细节一点，我们也像 sukisu 那样单独分一个「主题设置」

### 8.1 做了什么

| 项 | 内容 | 照谁做的 |
|---|---|---|
| 主题设置页 | `ThemeScreen`（二级页面，路由 `SubRoutes.THEME`） | SukiSU `ColorPaletteScreen` |
| 入口 | 设置 → 外观 → 一行「主题设置」，副标题显示当前明暗模式 | 同上 |
| 明暗模式 | 从设置页搬进主题设置页 | — |
| **悬浮底栏** | `MaterialFloatingBottomBar`（纯 Material3） | SukiSU `BottomBarMaterial` 非玻璃分支 |
| 底栏预览 | 主题页里渲染**真实底栏组件**，可点击 | SukiSU `ThemePreviewCard` |

### 8.2 ⚠️ 悬浮底栏的规格（不要随手改）

| 部分 | 值 |
|---|---|
| 外框 | `Box` + 系统栏内边距 + `padding(horizontal = 24, vertical = 12)` |
| 胶囊 | `Surface(CircleShape)`、`surfaceContainerHighest`、`shadowElevation = 6.dp` |
| 最大宽度 | `widthIn(max = 480.dp)` |
| 行高 | `64.dp`，水平 padding `8.dp`，`SpaceBetween` |
| 选中项 | `primary` 铺底 + `onPrimary` + **展开文字**（`animateContentSize` + `AnimatedVisibility`） |
| 未选中 | `surfaceContainerHighest` + `onSurfaceVariant`，只有图标 |
| 图标 | 选中实心 / 未选中描边（两套） |

⚠️ **选中项要"展开成文字"** —— 这是这个底栏最好认的特征。
靠 `animateContentSize()` + `AnimatedVisibility` 两个一起动才有"文字从图标里长出来"的效果。

⚠️ ⚠️ 未选中项的颜色必须**与胶囊同色**（`surfaceContainerHighest`）。
用 `primary` 的淡色会让三个胶囊一起抢眼。

⚠️ `Icons.Outlined.X` 需要**逐个 import** 扩展属性
（`androidx.compose.material.icons.outlined.Home` 等），
只 import `Icons` 的话报 `receiver type mismatch`。

### 8.3 ⚠️ 为什么主题页里要有「预览」

底栏**只在三个一级页面上显示**（`showBottomBar` 判 `MAIN` 路由），
而主题设置是二级页面 —— 改完「底栏样式」屏幕上**什么都看不到**，得返回才看得见。

⚠️ "点了没反应"是最糟的形态，所以预览渲染的是**真正的底栏组件**，
不是示意图（示意图迟早会和真实观感脱节）。

⚠️ 预览要传 `applyWindowInsets = false`：卡片里已有内边距，
再加一次导航栏高度会多出一大块空白。

### 8.4 刻意**没有**做的三项

| 没做的 | 原因 |
|---|---|
| 色彩风格 / 色彩标准 | 必须配"取色种子"才生效，单给 palette 是空转（上一轮删过一次） |
| 液态玻璃 | 需要 `miuix-blur 0.9.1+`（minSdk 32）或 `0.9.4`（Kotlin 2.4），为了它抬 minSdk 不划算 |
| 导航栏角标 | 我们底栏上没有数量可显示（SukiSU 显示的是"已授权应用数"） |

### 8.5 ⚠️ 偏好 id 一旦发布就不能改

`bottom_bar_style` 存的是字符串 id（`standard` / `floating`），不是 `ordinal`。
`BottomBarStyleTest` 里有 7 条测试卡住这件事，包括"认不出的值静默回落到默认值"
（降级安装会遇到）。
---

## 9. 关于页「捐赠」按钮

用户的要求:

> 在设置-关于加一个捐赠的按钮，样式与这个页面其他组件相同（别漏左边的圆角正方形和图标），
> 点击后弹一个窗口，上面放图片，下面写一点话……"右下角：by sth."

### 9.1 实现

| 项 | 做法 |
|---|---|
| 行 | `SettingItem(title = "捐赠", subtitle = "请我喝杯奶茶，支持项目继续更新", leadingIcon = Icons.Rounded.Favorite)` —— 与同页其它行**同一个组件**，所以左边的圆角方块底和图标自动就有 |
| 位置 | 单独一栏「支持项目」，放在「项目链接」**之前**。⚠️ 不塞进项目链接:上面几行都是"出去看"（浏览器/QQ），这一行是"点开窗口"，混在一起会让人以为点捐赠也会跳走 |
| 弹窗 | `ui/feature/settings/DonateDialog.kt` |
| 图 | `app/src/main/res/drawable-nodpi/donate_qr.png`（原图 1160×1160） |

### 9.2 ⚠️ 为什么放 `drawable-nodpi`

赞赏码必须**能扫**。放在 `drawable-xxhdpi` 这类目录里，系统会按设备密度
缩放，二维码的像素被插值之后，部分扫码器会读不出来。
`drawable-nodpi` = **不随密度缩放**，用原像素。

⚠️ AAPT2 打包时会做**无损**重压缩（146 KB → 129 KB）并改名为 `res/xx.png`，
像素数据不变，所以不影响识别 —— 已解包确认仍是 1160×1160。

### 9.3 文案

```
这个项目由我一个人独立维护，开发时间都是业余挤出来的。
如果你觉得它有用，欢迎请我喝杯奶茶 ——
你的每一份支持，都是我继续更新的动力。

                                              by sth.
```

⚠️ 不用 emoji（与更新公告的口径一致）。
---

## 10. v2.7.2 公告与版本号

| 项 | 值 |
|---|---|
| 版本号 | `versionCode = 272` / `versionName = "2.7.2"` |
| 公告日期 | `2026-10-25` |
| 公告位置 | `AppAnnouncement.releases` 第一条 |

### 10.1 这一版的内容（用户确认过的口径）

用户的原话:

> 这个版本主要还是UI层面优化，新功能只有"配置组合"，改进的地方就是外部导入支持字体/live2d了

| 分类 | 内容 |
|---|---|
| 新增 | **配置组合**（本版唯一新功能） |
| 变更 | 字体与 Live2D 集中到「库」（以前只能在配置编辑页里导入） |
| 改进 | 外部导入支持字体与 Live2D |
| 改进 | 界面统一（图标方块 / 开关 / 灰底白卡 / 底栏跟随手指） |
| 改进 | 外观设置（主色 / 色彩风格 / 底栏样式 + 预览） |
| 兼容 | 最低系统版本降到 **Android 9**（上一版公告写的是 Android 11） |
| 变更 | 移除桌面快捷方式（用户要求**明说原因**） |
| 其它 | 设置页配置相关几栏合并成一栏；关于页捐赠入口 |

### 10.2 ⚠️ 写公告的两条硬规矩（用户反复强调过）

1. **不要把开发过程中遇到的 bug 写成「修复」** —— 这一版公告里**没有任何「修复」小节**。
   用户看到的是"这版有什么变化"，不是我们的开发日志。
2. **不写 emoji**；只认 `**粗体**` 一种标记，其余按原文显示。