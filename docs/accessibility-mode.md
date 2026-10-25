# 纯无障碍模式（AccessibilityService）

> 目标版本：**2.8.0**
>
> 本文是**设计文档 + 需求清单**。实现过程中的取舍与踩过的坑也记在这里，
> 避免下一轮再问一遍"当初为什么这么定"。

---

## 1. 一句话说清

新增一条**不需要 root、不需要 Shizuku** 的输入通道：靠系统的
`AccessibilityService.onKeyEvent` 读物理键盘按键。

> 用户的原话:
>
> > 纯无障碍模式设计的初衷就是为了让国内某些**没有无线调试**的机型
> > （华为荣耀等）能不通过复杂的 adb 使用我们的软件，
> > 即便纯无障碍功能不全，但是**很方便**。

---

## 2. ⚠️ 能力边界（先划线，不能吹）

### 2.1 能拿到

| 能 | 说明 |
|---|---|
| ✅ 物理键盘按键 | 走 `onKeyEvent`，`KeyEvent.keyCode` **恰好等于** Linux `KEY_*` |
| ✅ 组合键（Shift/Ctrl/Alt/Win） | 同上 |
| ✅ 多键同时按 | 每个键各自 DOWN/UP |

### 2.2 拿不到（**必须**在界面上声明清楚）

| 拿不到 | 原因 |
|---|---|
| ❌ 鼠标移动 | 是 `MotionEvent`，不在 `onKeyEvent` 里 |
| ❌ **鼠标左右中键** | 同上，`ACTION_BUTTON_PRESS` 也在 `MotionEvent` 里 |
| ❌ 手柄摇杆 / 扳机 / 按键 | 内核 evdev 轴/键，需要 `/dev/input` |
| ❌ 触摸 / 触屏区域 | 完全拿不到 |
| ⚠️ HOME / BACK / 音量等系统键 | 系统优先处理，无障碍拦不住 |

### 2.3 ⚠️⚠️ 为什么不用 `onMotionEvent` 补鼠标（一条走过就别再走的路）

`AccessibilityService.onMotionEvent` **能**收 `MotionEvent`，但有两个死条件：

1. **API 34（Android 14）才加进来** —— 我们 `minSdk = 30`，
   Android 11/12/13 上根本没有这个方法。
2. ⚠️⚠️ **官方文档明确写着**：

   > 来自 `getMotionEventSources()` 的 `MotionEvent`
   > **不会被发送到系统的其余部分**

   ⭐ 也就是说：**一登记监听鼠标，系统的鼠标就被我们"吃"了** ——
   光标不动、游戏收不到点击。

   ⚠️ 一个"只读的按键显示工具"把鼠标吃掉是**不可接受**的，
   所以这条路**不走**。

| | 键盘 | 鼠标 |
|---|---|---|
| 通道 | `onKeyEvent` | 不在 `onKeyEvent` 里 |
| 能否只观察不拦截 | ✅ 返回 `false` 就照常传下去 | ⚠️ 不能，登记即吞 |

参考:
- [onMotionEvent](https://learn.microsoft.com/en-us/dotnet/api/android.accessibilityservices.accessibilityservice.onmotionevent?view=net-android-35.0)
- [setMotionEventSources](https://learn.microsoft.com/zh-cn/dotnet/api/android.accessibilityservices.accessibilityserviceinfo.motioneventsources?view=net-android-35.0)

---

## 3. 窗口方案：**全部走 Axon 那条路**（用户最终拍板）

> 用户的原话:
>
> > 走 axon 的路吧我认为，虽然工作量大，但是这样就**不需要用户单独授权
> > 悬浮窗权限**了，然后用户授权 shizuku/root 时**直接用 shell 授权无障碍**，
> > 纯无障碍模式就更不用说了……axon 也是这么做的而且**基本没有什么负面影响**

### 3.1 最终结论

**所有悬浮窗都用 `TYPE_ACCESSIBILITY_OVERLAY`，删掉 `SYSTEM_ALERT_WINDOW`。**

⚠️ 这一版**推翻了早先的"方案 A（分层 + 开关）"** —— 用户权衡后选了更彻底的方案。
早先担心"窗口生死绑到无障碍上"，用户的判断是：**有 shell 就顺手授权，
没 shell 就让用户在引导页给授权，兜底再跳一次系统页**，实际不会出问题。

### 3.2 ⭐ 关键发现：**不需要搬两个服务**

我一开始以为要把 `OverlayService`（1844 行）和
`TouchRegionEditorService`（2263 行）**都**搬进无障碍服务。

⚠️ 查了之后发现不必：两者唯一的共同依赖是 `OverlayBounds`（纯计算），
**窗口代码本身没有交集**。所以正确做法是：

| 做法 | 说明 |
|---|---|
| ❌ 把两个服务都塞进 `AccessibilityService` | 4000+ 行挤一个类，且服务生命周期完全不同 |
| ✅ **两个服务都保留**，只把"**拿到 WindowManager**"这一步改成从无障碍服务取 | 改动面小，两个服务的代码几乎不动 |

⭐ 也就是说：**新建一个 `OverlayWindowHost` 单例**，由 `AccessibilityService`
在 `onServiceConnected` 时登记自己；两个服务建窗口时**改用它给的
`WindowManager` + 窗口类型**，其它逻辑（拖动、粘边、多窗口、flags）**一行不用改**。

```
AccessibilityService（新增）
  └─ onServiceConnected → OverlayWindowHost.attach(this)
                              │
                              ├─ OverlayService          ← 保留，窗口建在它手上
                              └─ TouchRegionEditorService ← 保留，同上
```

### 3.3 ⚠️ 硬约束（这是 3.2 存在的理由）

`TYPE_ACCESSIBILITY_OVERLAY` **必须由 `AccessibilityService` 的 context 建** ——
普通 Service 建会抛：

```
WindowManager$BadTokenException: token null is not for an application
```

⚠️ 这与之前 `AlertDialog` 闪退是**同一类**错误。给多大权限都没用，
是系统在窗口类型那一层拒绝。

### 3.4 两个开关与窗口类型无关（已核实）

用户特意问过「是否可触摸」「是否可移出屏幕外」会不会不通用：

| 开关 | 对应 flag | 结论 |
|---|---|---|
| 可触摸 | `FLAG_NOT_TOUCHABLE` | ✅ **完全通用** |
| 可移出屏幕外 | `FLAG_LAYOUT_NO_LIMITS` | ✅ **完全通用** |

⭐ `OverlayService.windowFlags(touchable, movableOffScreen)` 本来就是纯函数，
换窗口类型**一个字都不用改**。

### 3.5 ⚠️ 无障碍没授权时的兜底（用户明确要求）

> > 就算用户**跳过了授权直接进来**，也只需要开启悬浮窗的时候做个弥补，
> > **跳转系统的无障碍授权页**让用户授权就好了
>
> > 而如果用户授权了 shizuku/root 然后用户又玩心大起**自己关掉了**，
> > 那也是一样的，不过既然有了 shell
> > **那直接启动的时候也授权一下无障碍做个保障**就好了

因此三条兜底缺一不可：

| 时机 | 行为 |
|---|---|
| 启动采集时（有 root / Shizuku） | ⚠️ **主动用 shell 授权无障碍**（每次启动都做，做保障） |
| 有 shell 但无障碍被用户关掉 | 同上，下次启动自动修好 |
| 无 shell 且无障碍没授权 | 开悬浮窗时**跳系统无障碍设置页** |
| 纯无障碍模式 | 引导页让用户自己给授权 |

### 3.2 窗口类型集中到一个函数

现状（**要改**）：`TYPE_APPLICATION_OVERLAY` 写死在 7 处

| 文件 | 处数 |
|---|---|
| `OverlayService.kt` | 2 |
| `TouchRegionEditorService.kt` | 5 |

改法：新增 `OverlayWindowType.resolve(context): Int`，**唯一**决定处。

### 3.3 ⚠️ 好消息：两个被点名的开关**与窗口类型无关**

用户特意问过「是否可触摸」「是否可移出屏幕外」会不会不通用。查过了：

| 开挂 | 对应 flag | 与窗口类型有关吗 |
|---|---|---|
| 可触摸 | `FLAG_NOT_TOUCHABLE` | ❌ **无关**，完全通用 |
| 可移出屏幕外 | `FLAG_LAYOUT_NO_LIMITS` | ❌ **无关**，完全通用 |

⭐ `OverlayService.windowFlags(touchable, movableOffScreen)` **本来就是纯函数**
（只吃两个布尔、只吐 flag 位），换窗口类型**一个字都不用改**。

### 3.4 ⚠️ 但 `TYPE_ACCESSIBILITY_OVERLAY` 有一个硬约束

**必须由 `AccessibilityService` 的 context 建，普通 Service 建不了。**

后果：如果开关打开，悬浮窗的生命周期就要从
`OverlayService`（普通 Service）搬到无障碍服务里，
或者让无障碍服务持有窗口。

⚠️ 这是本方案**最大的一块工作量**，也是最容易出错的地方。
所以它排在最后，且默认关闭。

---

## 4. 开关放哪

用户的原话:

> 开关做在**调试页**上面位置，就是**采集模式那一块下面**，没问题吧

✅ 对应 `DebugScreen.kt` 的「采集模式」section 之后
（`SettingsCard` 里那串 `SettingItem` 之后、`SectionHeader("能力检测")` 之前）。

文案方向：

> 悬浮窗改用无障碍窗口（实验）
>
> 打开后三种采集模式都用无障碍悬浮窗。需要无障碍服务已授权。
> 出问题时关掉即可恢复。

---

## 5. 界面要改的地方

### 5.1 主页「工作状态」卡片

现状：显示"正在监听 N 个输入设备"。
⚠️ 无障碍**没有"设备"这个概念**（它不读 `/dev/input`）。

改法（用户同意了"卡片做具体一点 + 红字提示"）：

| 通道 | 显示 |
|---|---|
| root / Shizuku | 照旧：`监听中 · 3 个输入设备` |
| **无障碍** | `监听中 · 无障碍模式`<br>副行：`仅支持键盘按键；鼠标、手柄、触摸读不到` |

⚠️ 红字只在**无障碍通道生效时**出现，不打扰其它模式的用户。

### 5.2 权限引导页（`SetupScreen.kt`）

新增一张卡：

| 元素 | 内容 |
|---|---|
| 标题 | 无障碍服务 |
| 说明 | 不折腾也能用：不给 root / Shizuku 时的备用通道 |
| 未授权 | 状态 + 「去开启」按钮 → 系统无障碍设置页 |
| 已授权 | 状态「已开启」 |
| ⚠️ 附注 | 仅支持键盘；鼠标、手柄、触摸读不到 |

⚠️ 用户明确说：

> 权限引导页给一些**声明**就好了，用户自然会清楚

所以这里**只声明、不做复杂交互**。

### 5.3 ⚠️ root / Shizuku 可以直接代为开启无障碍

Axon 的做法（`MainActivity.java:3565 accessibilityGrantCommand`）：

```sh
settings put secure enabled_accessibility_services "<组件名>"
settings put secure accessibility_enabled 1
```

⚠️ 要点（照 Axon 抄，别自己简化）：

| 要点 | 为什么 |
|---|---|
| **只增删自己那一项**，保留其它已启用服务 | 否则会把用户的屏幕阅读器/自动化工具一起关掉 |
| 写完之后**必须验证服务真的连上** | Axon 注释原话：settings 写成功**不等于**服务起来了 |
| 支持"强制重绑"（先删自己再加回来） | 某些 ROM 上服务卡死时需要它 |

---

## 6. 输入层怎么接

⭐ **好消息：`InputSource` 这个接口已经在了**，上层状态机 / 悬浮窗 / 配置
**全都不用动**，只加一个实现。

```
InputSource (interface)
 ├─ RootInputSource            getevent
 ├─ ShizukuGeteventInputSource getevent(借 Shizuku)
 └─ AccessibilityInputSource   onKeyEvent   ← 新增
```

### 6.1 `AccessibilityInputSource`

| 项 | 做法 |
|---|---|
| 事件来源 | `AccessibilityService.onKeyEvent` 回调 |
| 转换 | `InputEvent(type = EV_KEY, code = event.keyCode, value = 0/1)` |
| ⚠️ 为什么 keyCode 能直接用 | Android 的 `KeyEvent.KEYCODE_*` 数值**等于** Linux `KEY_*`（如 30 = A、42 = Shift） |
| 返回值 | **恒 `false`** —— 表示"我看过了，照常传下去"，不拦截用户输入 |
| `deviceCount` | **不实现**（返回 `null`）—— 无障碍没有设备概念 |
| `capabilities` | 空表 |

### 6.2 ⚠️ 不能与 getevent 重复计数

`KeyStateManager` / CPS 是按"按键位"计数的。如果无障碍和 getevent
同时上报同一个键，CPS 会**翻倍**。

做法：`CaptureController` 按通道**二选一**挂监听：
- 走 root / Shizuku → 不挂 `onKeyEvent`
- 走无障碍 → 只挂 `onKeyEvent`，不启 `getevent`

### 6.3 `KeyMode` 增加 `ACCESSIBILITY`，并**并入 `AUTO`**

> 用户的原话:
>
> > 无障碍**并列进去**会比较方便，虽然能做的确实不同，但是"auto"的能力
> > 就是这样子的

| 模式 | 含义 |
|---|---|
| `ROOT` | 只用 root |
| `SHIZUKU` | 只用 Shizuku |
| **`ACCESSIBILITY`（新增）** | 只用无障碍 |
| `AUTO` | ⭐ **root → Shizuku → 无障碍**，逐级降级 |

⚠️ `AUTO` 的语义就是"你能给什么权限我就用什么"，把无障碍排除在外
反而会让用户困惑（"我开了无障碍，AUTO 怎么不用？"）。

---

## 7. 实施进度

| 阶段 | 做什么 | 状态 |
|---|---|---|
| **1** | manifest + `accessibility_service_config.xml` + `SthKeyAccessibilityService` | ✅ 完成 |
| **2** | `AccessibilityInputSource`（`onKeyEvent` → `InputEvent`） | ✅ 完成 |
| **3** | `KeyMode.ACCESSIBILITY` + 并入 `AUTO` + `CaptureController` 分支 | ✅ 完成 |
| **4** | `OverlayWindowHost` + 两个服务改用它借来的 `WindowManager` | ✅ 完成 |
| **5** | 删 `SYSTEM_ALERT_WINDOW` | ✅ 完成 |
| **6** | 主页状态卡 + 引导页无障碍卡 + 红字声明 | ✅ 完成 |
| **7** | root / Shizuku 代为开启无障碍（探测能力时顺手做） | ✅ 完成 |

### 7.2 ⚠️ 踩过的坑（记下来，别再犯）

| 坑 | 症状 | 教训 |
|---|---|---|
| **`KeyEvent.keyCode` ≠ evdev 码** | ⚠️ **"按键对应错误 / 有些键反映不出来"** | ⚠️⚠️ 我按"两者应该同一套编号"直接透传了。实际:A=29↔30、S=47↔31、Space=62↔57、Shift=59↔42。**必须转换**，表在 `AccessibilityKeyCode`（照 Axon 抄），9 条单测 + 跨层一致性测试钉住 |
| **把 shell 多行脚本压成一行** | ⚠️ **"开悬浮窗卡一会、然后跳到无障碍设置页"** | ⚠️⚠️ `.replace("\n", "; ")` 产出 `then;` / `else;` / `do;` —— **全是语法错误**，整条命令从来没成功过。教训:**shell 只做最简单的事**，列表运算搬到 Kotlin（`mergeServiceList`，有 9 条单测） |
| **XML 注释里写 `--`** | `Error parsing AndroidManifest.xml`，报错**不指向**注释 | ⚠️ **踩了两次**（表格分隔线 + 中文破折号）。注释里分隔线用 `====`，中文不用破折号 |
| Kotlin 字符串里的 shell `$` | 编译报"Unresolved reference 'CURRENT'" | 后来整段 shell 都简化掉了，这个问题自然消失 |
| `-replace` 替换了所有匹配 | 一个 import 被插了 20 遍 | 要只插一次时用**逐行重建**，不要用 `-replace` |
| `AlertDialog` 在 Service 里 | `BadTokenException` 闪退 | ⚠️ Service 里**不能**用系统对话框，用悬浮窗自己画 |
| **删了权限却忘了改判据** | ⚠️ **"开启悬浮窗的按钮点不动"** | 删 `SYSTEM_ALERT_WINDOW` 后 `canDrawOverlays()` 恒 false，而 `OverlayController.enableConfigs` 仍拿它当前置检查 → 直接 return。**删一个权限要把它的所有判据一起找出来**（我第一轮只找了 UI 层，漏了控制器） |
| **主页提前挂"无障碍未授权"红卡** | ⚠️ 多余且误导 | 用户原话:「开启悬浮窗的时候就会有校验了，**不用多此一举**」。改成**只在真的要用时**校验（`OverlayController` / `OverlayService`） |

### 7.3 ⚠️ 权限流程的正确判据（用户明确要求）

> 用户的原话:
>
> > 有 shizuku/root/无障碍**三者其一**应该都可以进入软件……所以**不需要让用户
> > 已经授权 shizuku/root 的时候还单独开启无障碍**
> >
> > 授权了 shizuku/root 的时候**不要在主页提示无障碍未开启**，应该在
> > **开启悬浮窗的时候自动用 shell 授权无障碍**
> >
> > 其实授权 shizuku/root 后**进入软件就要自动授权一次无障碍**了，axon 就是这么做的
> >
> > 我觉得主页无障碍未授权的卡片**可以删掉**，开启悬浮窗的时候就会有校验了，
> > **不用多此一举**

| 场景 | 正确行为 | 实现位置 |
|---|---|---|
| 应用启动 | ⚠️ **探测能力时顺手用 shell 开无障碍** | `MainViewModel.init` → `CaptureController.refreshCapabilities` |
| 点开悬浮窗时无障碍还没开 | ⚠️ **先 shell 授权，再建窗口**（顺序不能反） | `MainViewModel.setConfigOverlayEnabled` → `ensureAccessibilityForOverlay` |
| 有 root/Shizuku，引导页 | 无障碍卡片**不是必填**，显示"会自动开启" | `SetupScreen` 的 `canGrantByShell` |
| 主页 | ⚠️ **不挂任何无障碍状态卡** | 已删除（校验挪到真正要用时） |
| 什么都没有 + 无障碍没开 | ✅ 点开关时才提示 + 跳系统设置页 | `OverlayController.enableConfigs` |
| 引导页"全部就绪" | ⚠️ = **能用**（三者其一），不是"每样都授权" | `SetupScreen.allReady = captureReady` |
| 引导页「暂不授权」 | ⚠️ **只本次生效**，不写"引导已完成"；下次启动**重新校验** | `SetupScreen` 的跳过回调调 `resetSetupCompleted()` 而**不**调 `markSetupCompleted()` |
| ⚠️ **每次启动都校验** | ⚠️ 即使点过「进入应用」，下次启动也**重新判**；没问题**冷跳过**，有问题**留在引导页并说明** | `MainActivity` 的 `AppStage.CHECKING` → `MainViewModel.startupCheckIssue` |

### 7.4 ⚠️ 启动校验的设计（用户最在意的一点，改了**三次**）

> 用户的原话:
>
> > 我主要是为了**防止无障碍服务被系统自己取消然后用户还不知道**，
> > 所以即便是点了进入应用，**下次启动也仍然需要校验**，
> > 没问题**直接冷跳过**，有问题**再留在权限授权页**
>
> > 而且我并不想让用户每次进入都放一个这么丑的检查权限的页面，
> > **你冷检查就行了**

#### 最终做法：**静默校验**（没有等待页）

```
启动 → 首帧**同步**决定去哪（不查任何异步的东西）
         ├─ setupCompleted == true  → MAIN   ← ⚠️ 绝大多数情况，零延迟、零闪屏
         └─ setupCompleted == false → SETUP
         │
         └─ 后台静默跑探测 + 校验
              └─ 只在"以前配好过、这次查出问题"时 → 切到 SETUP 并说明原因
```

⚠️ **走过的两个弯路**（都记下来，别再走）:

| 弯路 | 问题 |
|---|---|
| 启动时先等 `RootProbe` 再决定 | ⚠️ 它超时 **15 秒** —— 用户报"检查太久了" |
| 加一屏"正在检查权限…" | ⚠️ 用户报"**这么丑的检查页面**，冷检查就行" |

⚠️ 三个**必须**的设计点:

| 设计点 | 少了会怎样 |
|---|---|
| 首帧**同步**决定（只看 `setupCompleted`） | 要等异步结果 → 就有等待页/闪屏 |
| 无障碍**已连着就一秒不等**（`STARTUP_GRACE_MS` 只用于没连上时） | 好用户也要陪着等 |
| 校验**只干预一次**（`startupVerificationApplied`） | 用户从设置进引导页会被反复弹回主界面 |

⚠️ 另外**探测只能跑一遍**:早先 `init` 与 `MainActivity` 各调一次，
串起来等就是 30 秒（`RootProbe` 的 15 秒 × 2）。

#### ⚠️⚠️ 判据必须是"**能不能用**"，不是"无障碍开了没"

> 用户报的:
>
> > 我授权了 root 进入软件主页后过了一会又弹出权限引导页
> > **提示我无障碍没了，但无障碍可以用 root 的 shell 启动啊**
> > **你这校验写错了吧**
>
> > 而且你**也不需要加一个"上次配好的权限没了"的卡片，很丑**，
> > **别做这些无用的**

⚠️ 我原来只有一条判据 `无障碍连上了吗`，那是错的:

| 情况 | 错的判断 | 正确判断 |
|---|---|---|
| 无障碍已连 | 正常 | 正常 |
| 无障碍没连、**有 root / Shizuku** | ⚠️ **报问题、踢回引导页** | ✅ **正常**（shell 能开） |
| 无障碍没连、也没有特权 | 报问题 | 报问题（确实用不了） |

⚠️ 判据现在是三条:
1. 无障碍已连 → 正常
2. **有 root / Shizuku → 正常**（无障碍随时能开起来）
3. 只有"什么都没有"才算问题

⚠️ 另外还有一个**叠加的错**:我给等待探测加了 `withTimeoutOrNull(1500ms)`。
超时到点时 `capabilities` 里 `rootAvailable` 还是 `false`，于是
**有 root 的用户被误判成"什么都没开"**。
⚠️ 结论:**不设超时** —— 要的是准确的结论，而不是早点出结论。
（没有等待页，所以等多久用户都看不到。）

⚠️ 还有那张"上次配好的权限没了"的红卡 —— **已删**。
它不只是丑，而且当时还是**错的结论**（有 root 的用户被误判）。
引导页本身的卡片已经标出哪一项没完成，再加一层横幅是重复。

### 7.5 ⚠️ 还没做 / 已知限制

| 项 | 说明 |
|---|---|
| 实际真机验证 | ⚠️ **全部代码只过了编译与单测，没在真机上跑过** |
| 纯无障碍的键位映射 | ⚠️ 理论上 `keyCode == Linux KEY_*`，但**没有真机验证过** |
| 用户关掉无障碍后窗口的行为 | 已有兜底（跳设置页），但"窗口在开着时被关掉"的表现**未验证** |
| 无障碍模式的 CPS | 走同一条 `InputEvent` 路，理论可用，**未验证** |

---

## 8. 用户明确提过的要求（**别忘**）

---

## 8. 用户明确提过的要求（**别忘**）

| # | 要求 | 出处 |
|---|---|---|
| 1 | 窗口**走 Axon 那条路**：全部无障碍窗口，删掉悬浮窗权限 | "走axon的路吧我认为……这样就不需要用户单独授权悬浮窗权限了" |
| 2 | 有 root/Shizuku 时**用 shell 直接授权无障碍**，且**每次启动都做** | "直接启动的时候也授权一下无障碍做个保障就好了" |
| 3 | 跳过授权时，**开悬浮窗那一步跳系统无障碍页** | "只需要开启悬浮窗的时候做个弥补，跳转系统的无障碍授权页" |
| 4 | 无障碍**并列进 `AUTO`** | "无障碍并列进去会比较方便" |
| 5 | 手柄/触屏组件**不用过多处理**，靠**声明**说清 | "不用过多处理……给一些声明就好了" |
| 6 | 主页状态卡**具体一点 + 红字提示** | "亦或者也可以给主页工作状态的卡片做具体一点，给红字提示一下" |
| 7 | 这一版**按我思路来**，又快又稳 | "这一版按你思路来，做得又快又稳才最好" |
| 8 | **常写 md**，防止忘记要求 | "记得常写 md，防止我的要求你忘记" |
| 9 | ⚠️ **别乱编**，不确定就查 | "你自己研究一下，别乱编就行" |

---

## 9. 相关文件

| 文件 | 关系 |
|---|---|
| `capture/InputSource.kt` | 要新增实现 |
| `capture/CaptureController.kt` | 加 `ACCESSIBILITY` 分支 |
| `capture/CaptureSession.kt` | 通道能力上报 |
| `domain/capture/KeyMode.kt` | 加枚举值 |
| `capture/OverlayService.kt` | 窗口类型（2 处） |
| `capture/touch/TouchRegionEditorService.kt` | 窗口类型（5 处） |
| `ui/feature/debug/DebugScreen.kt` | 实验开关 |
| `ui/feature/setup/SetupScreen.kt` | 无障碍授权卡 |
| `ui/feature/home/HomeScreen.kt` | 状态卡文案 |
| `core/prefs/AppPrefs.kt` | 开关持久化 |
| `app/src/main/AndroidManifest.xml` | service 声明 |
| `app/src/main/res/xml/accessibility_service_config.xml` | 新增 |

参考实现（**只读，绝不修改**）：
`D:\Project\_axon\Axon-Input-main\app\src\main\java\com\axon\input\AccessibilityService.java`
