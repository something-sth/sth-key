# Android 版本兼容性

> 当前状态:**minSdk 28(Android 9)/ targetSdk 36 / compileSdk 37**
>
> 结论:**已支持 Android 9 ~ Android 17**。

---

## 1. 支持范围

| 项 | 值 | 说明 |
|---|---|---|
| `minSdk` | **28** | Android 9 |
| `targetSdk` | **36** | 与 29/35 的行为差异见下 |
| `compileSdk` | **37** | Compose 1.12 的 AAR metadata 要求 ≥37 |

⚠️ **`targetSdk` 为什么不跟着升到 37**:它决定**运行时行为**,与"用什么 SDK 编译"是两件事。
在 Android 17 上,targetSdk 36 的应用以**兼容模式**运行,能装能用。
等 Android 17 的行为变更文档明确之后再升,并单独做一轮回归。

---

## 2. 怎么验证的(不要凭感觉)

⚠️ 判断"降到 Android 9 要改什么"**不能靠读代码猜**,办法是:

```bash
# 1. 临时把 minSdk 改成 28
# 2. 跑 lint,让它把"需要更高 API 却没守护"的地方全列出来
./gradlew :app:lintDebug
# 3. 看 app/build/reports/lint-results-debug.xml 里 id == "NewApi" 的条目
# 4. 改完把 minSdk 改回去
```

第一次跑出来 **4 条 `NewApi` + 1 条历史遗留的 `StateFlowValueCalledInComposition`**。
改完之后 `NewApi` **归零**。

⚠️ 这个办法只查得到 framework API;**运行时行为差异查不出来**(见 §5)。

---

## 3. 改了什么

### 3.1 `ConfigExporters`:`MediaStore.Downloads` 是 API 29 才有的 ⚠️

原来只有一条路:

```kotlin
val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI   // API 29
```

在 API 28 上访问这个字段抛的是 **`NoSuchFieldError`** —— 它是 `Error` 不是
`Exception`,外面那句 `catch (e: Exception)` **接不住**,表现就是"一点导出就闪退"。

现在分成两条:

| 系统 | 走哪条 | 权限 |
|---|---|---|
| Android 10(API 29)及以上 | MediaStore(与以前**完全一致**) | 零权限 |
| Android 9(API 28)及以下 | 老式公共目录 `File` 写入 | `WRITE_EXTERNAL_STORAGE` |

⚠️ 重名处理必须两边**看起来一样**(`x.sthkey` → `x (1).sthkey`),否则会出现
"一台设备改名、另一台直接覆盖" —— 配置包里带字体,覆盖就是丢数据。
Android 9 那条路自己实现了这件事,算法是 `ConfigExporters.uniqueName`,
有 8 条单测覆盖(`ExportNameCollisionTest`)。

### 3.2 权限:`WRITE_EXTERNAL_STORAGE` 带 `maxSdkVersion="28"`

```xml
<uses-permission
    android:name="android.permission.WRITE_EXTERNAL_STORAGE"
    android:maxSdkVersion="28" />
```

⚠️ `maxSdkVersion="28"` 不能省:Android 10+ 走 MediaStore 本来就不需要权限,
不限制的话这条权限会出现在新系统的"应用信息 → 权限"里,显得很奇怪。

### 3.3 授权被拒时**自动退回手动选择位置**

`ExportFlow` 里加了第二个 launcher(`RequestPermission`)。流程:

```
点导出(DOWNLOAD)
  ├─ API 29+ 或有权限 → 直接导出
  └─ API 28 且没权限 → 申请权限
        ├─ 同意 → 导出
        └─ 拒绝 → 自动改为「手动选择保存位置」(SAF,免权限)+ 提示
```

⚠️ **拒绝后不能什么都不做**。用户点了"导出"却毫无反应是最糟的形态;
退回 SAF 至少配置能导出去。

⚠️ `storagePermissionLauncher` 必须声明在 `manualLauncher` **之后** ——
Kotlin 的局部变量不能前向引用(编译会报 `Unresolved reference`)。
两者都会在"拒绝 → 退回手动"这条路上用到。

### 3.4 两处版本守护(只是消 lint,本来也不会崩)

| 位置 | API | 原来 | 现在 |
|---|---|---|---|
| `OverlayService.geometryReport()` | 30 `getMaximumWindowMetrics` | 已在 `runCatching` 里,失败显示"取不到" | 加 `SDK_INT < R` 判断 |
| `SthKeyApplication.currentProcessName()` | 33 `Process.myProcessName` | 已在 `runCatching` 里,回落 `/proc/self/cmdline` | 加 `SDK_INT >= TIRAMISU` 判断 |

⚠️ 这两处**本来就不会崩**(`runCatching` 接得住 `Error`),加判断是为了:
1. 消掉 lint 报错;
2. 不用每次靠抛异常走正常分支(既慢又刷日志)。

### 3.5 顺手修的历史遗留:`StateFlow.value` 读在组合里

`DebugScreen` 的按键高亮直接读了 `CaptureSession.pressedKeys.value`,
`StateFlow` 更新时**不会触发重组** —— 表现是"按键高亮永远不亮"。

改成 `collectAsState()`,这也是 lint 唯一剩下的那条 Error。

---

## 4. 不用改的部分(已核实)

| 项 | 核实结果 |
|---|---|
| Compose ui / foundation / material3 1.12 | AAR 声明 minSdk **23** |
| activity / activity-compose 1.13.0 | minSdk **23** |
| core-ktx / navigation-compose 2.7.7 | 23 / 21 |
| `libtouchmonitor.so` | ELF `.note.android.ident` = **API 24**,只依赖 `libc/libdl/libm` |
| `libgamepadmonitor.so` | 同上,**API 24** |
| 通知权限 | 已有 `SDK_INT < TIRAMISU return` 判断 |
| 动态取色 | 已有 `SDK_INT >= S` 判断 |
| RenderEffect 模糊(`RealBlur.kt`) | 已有 `SDK_INT < S` 退化 |
| 前台服务 | `startForeground(id, notification)` 两参版,全版本兼容 |
| 无障碍悬浮窗 | `TYPE_ACCESSIBILITY_OVERLAY` 是 API 22 的 |
| 通知渠道 | 已有 `SDK_INT >= O` 判断 |

⚠️ 原生 helper 是**独立可执行文件**(不是 JNI 库),按 API 24 编译,通过 `exec` 运行。
查它们的目标 API 的办法:在 `.note.android.ident` 里读 4 字节整数。

---

## 5. lint 查不出来、必须真机验的

| # | 项 | 风险点 |
|---|---|---|
| 1 | 配置导出到 Download | 老式路径 + 权限流程(本轮新写) |
| 2 | 无障碍服务开启与读键 | Android 9 的 SELinux 与新版不同 |
| 3 | 悬浮窗显示 | `TYPE_ACCESSIBILITY_OVERLAY` |
| 4 | 两个 native helper 的 `exec` | root/shell 环境差异 |
| 5 | 深色模式 | Android 9 没有动态取色,会落到固定配色 |
| 6 | 通知常驻 | 渠道、前台服务 |

---

## 6. Android 17(API 37)

- `compileSdk` 已经是 37,本地 SDK 平台装了 `android-37.0` / `android-37.1`
- `targetSdk` 36 → 在 17 上以兼容模式运行,**能装能用**
- 要"完全适配",需把 `targetSdk` 升到 37 并重新回归

⚠️ 具体行为变更**不要猜**,以官方文档为准。本项目已知的历史行为变更节点:

| Android | 变更 | 影响 |
|---|---|---|
| 13(33) | 通知权限变成运行时权限 | 已处理 |
| 14(34) | 前台服务必须声明类型 | 已声明 `specialUse` |
| 15(35) | targetSdk 35+ 强制 edge-to-edge | 已用 `enableEdgeToEdge()` |
| 16(36) | 延续 edge-to-edge 与分区存储收紧 | 无存储权限依赖,不受影响 |
