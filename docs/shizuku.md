# Shizuku 通道

这份文档记录 Shizuku 这条通道的**做法与踩过的坑**。改这块之前先读这里。

> **⚠️ 现在有两条后端了。** 默认走「直连」（§9），UserService（§1–§8）
> 保留作为回退。两条的取舍与代价见 §9。

---

## 1. 它是什么（UserService 版）

Shizuku 让普通应用借 shell(uid 2000) 的身份干活。UserService 的用法是
让 Shizuku 在**另一个进程**里跑我们自己的类，我们在那边读
`/dev/input/event*`，再把事件跨进程回传。

```
应用进程                           UserService 进程（shell 身份）
┌──────────────────┐   bindUserService   ┌─────────────────────────┐
│ ShizukuUserService│ ──────────────────▶ │ ShizukuInputService     │
│ （客户端）        │ ◀────────────────── │ （AIDL Stub 实现）      │
└──────────────────┘   批量事件回调        └─────────────────────────┘
                                                  │ getevent / 直接读设备
```

关键点：**远端那个类不是"启动一个 Service 组件"** ——
它由 Shizuku 服务端用 `app_process` 启动，并**拿字符串类名反射加载、反射构造**。
所以：

- 它**不需要**在 AndroidManifest 里声明 `<service>`；
- 客户端对它的唯一引用是一个**字符串常量**（见 §3）。

---

## 2. ⚠️ 绑定失败时不要瞎猜：先把原因分类

有用户报告：Shizuku 运行中、已授权、版本 13.5，但**每次**都卡在绑定超时；
而**同一台设备**上另一个用 Shizuku 的应用却正常。

这种"别人好好的、就他不行"最难查，所以代码里**把失败原因分开**了
（`ShizukuUserService.BindAttempt`）：

| 结果 | 含义 | 值得重试吗 |
|---|---|---|
| `timedOut` | 等满超时都没有 `onServiceConnected` | ✅ **值得** |
| `uid != 2000` | 连上了但身份不是 shell | ❌ 确定性失败 |
| `exception` | `bindUserService` 直接抛异常 | ❌ 确定性失败 |

> 规矩：**只有超时才重试**。把确定性失败也重试一遍，只会让用户白等，
> 还会让"真正的错误原因"被第二次的超时覆盖掉。

---

## 3. ⚠️ 服务标识（tag）必须是常量，不能用类名

`UserServiceArgs.tag` 是 Shizuku 用来**区分不同服务**的标识。
官方 javadoc 说得很直接：

> If you want to obfuscate the user service class, you need to set a stable tag.

也就是说 tag 的用途正是"**与类名解耦**"。早先这里填的是类名——能用，
但埋了两个雷：改类名等于换服务；混淆环境下说不清。

现在是固定常量 `SERVICE_TAG = "sthkey-input"`。
**改它等于换服务**，别随手动。

---

## 4. ⚠️ 关于 keepRules：先说结论，它一直是对的

`proguard-rules.pro` 里现在有 `ShizukuInputService` 的 keep 规则，
但它**不是**为了修某个真实 bug 才加的 —— 加它是为了防止将来出事。

事实（用 R8 mapping 实证过）：

```
com.something.sthkey.capture.shizuku.ShizukuInputService -> 同名
com.something.sthkey.IShizukuInputService -> 同名
com.something.sthkey.IShizukuInputListener -> 同名
```

即使项目里**没有**显式 keep 规则，AGP 的默认规则也保住了这些类
（AIDL 相关的类有默认 keep）。所以"release 构建下 Shizuku 能用"
不是运气。

> **教训**：早先绑定失败的提示里写的是
> "release 构建请确认 keepRules 保留了 …ShizukuInputService"。
> 那句话是**错的**，而且**把人带偏** —— 用户会去查混淆，
> 而真正的原因在远端进程起不来。
>
> 现在的提示改成如实说明"下一步该看什么、该反馈什么"，
> 并把请求参数（tag / version / 进程后缀 / debuggable / 构建标记）
> 一起写进诊断，方便定位。

---

## 5. 绑定策略：首次 15s，超时后清理并重试一次

```kotlin
BIND_TIMEOUT_MS       = 15_000L   // 首次：拉起进程本来就慢
BIND_RETRY_TIMEOUT_MS = 10_000L   // 重试：进程已经起过一次，该快得多
```

超时后的动作是**解绑（并请求结束远端进程）→ 等 300ms → 重绑**。

为什么值得重试：这类"远端进程没起来/卡住"的失败有一个共同特点 ——
**再绑一次通常就好了**。第一次的尝试往往已经把进程拉起来了，
或者把卡住的旧进程清掉了。上游 issue 里有大量同类报告：

- [Shizuku #1171 无法启动用户服务进程](https://github.com/RikkaApps/Shizuku/issues/1171)
- [Shizuku #475 bindUserService 有时卡住](https://github.com/RikkaApps/Shizuku/issues/475)
- [Shizuku #2241 在 Oppo(CPH2591) 上卡在 Waiting for service…](https://github.com/RikkaApps/Shizuku/issues/2241)

⚠️ 代价要说清楚：**失败路径全程可能二十多秒**（15 + 10）。
所以重试期间会把"正在重试…"写进诊断，否则界面一直显示"绑定中"，
用户会以为卡死了。

---

## 6. ⚠️ 构建能不能跑，还取决于守护进程用哪个 JDK

`gradle/gradle-daemon-jvm.properties` 里钉着 Gradle 守护进程的 JDK 版本。
它由 `gradlew updateDaemonJvm` 生成，**对不上就是所有 gradle 命令一起失败**：

```
Unable to download toolchain matching the requirements ({languageVersion=21 ...})
```

这个报错指向一个下载地址，很容易被误解成网络问题。实际上：

- 项目原本钉的是 **21**，而开发机只装了 **25** → 离线必然失败；
- 之所以之前一直没炸，是因为**复用一个早就启动好的守护进程**
  （它在这个文件生效之前就起来了）。守护进程一重建就暴露。

现在钉的是本机实际安装的版本（25）。**换机器 / 升级 JDK 时改这一行**，
或者删掉整个文件让 Gradle 用 `JAVA_HOME`。

> 教训：**"一直能跑"不等于配置正确**。守护进程热着的时候，
> 一个错误的 JVM criteria 可以隐藏很久，然后在某次冷启动时突然炸掉。

---

## 7. 诊断面板要回答"卡在哪一步"

`ui/feature/debug/DebugScreen.kt` 里那块「Shizuku 诊断」按顺序列：

```
Shizuku 服务（运行中 / 未运行）
本应用授权（已授权 / 未授权）
Shizuku 版本（UserService 需要 v11+）
UserService ← 就是绑定状态
服务进程   ← ps 查不到是常态，见下
服务 uid
远端读取
```

⚠️ **"服务进程"那一行有语义陷阱**：非 root 的普通应用执行 `ps`
**只能看到自己 uid 的进程**，而 UserService 跑在 shell(2000) 身份下 ——
它明明活着，这里也查不到。所以：

- 查到了 → 进程确实在运行（有效信息）；
- 查不到 → **不能**断定它没起来，只能说明"看不见"。

判断采集是否真在工作，要看**远端状态**（`isRemoteRunning`），那才是权威依据。
界面上的文案必须把这一点说清楚，否则用户会拿"查不到"当成故障。

---

## 8. 其他要点（都在代码注释里，这里列个索引）

- **`daemon = true`**（不调用 `.daemon(false)`）：应用进程被杀后
  UserService 继续活着，采集能续上。旧项目显式设 false，
  这是"挂一会后台监听就没了"的根因之一。
- **`version(2)`**：AIDL 接口变更后必须递增，让 Shizuku 重建进程；
  否则会连上旧版本的进程，出现 `TransactionTooLargeException` 之类的怪问题。
- **`debuggable(BuildConfig.DEBUG)`**：这个标志让 Shizuku 用调试模式启动
  `app_process`（等 jdwp 附加）。正式包里开着没有好处，反而可能让进程
  迟迟不起来 —— 那正是"绑定超时"的典型表现。
- **先等 SDK binder 就绪**（`awaitServiceReady`）：`pingBinder()` 只说明
  binder 对象存在，**不代表** SDK 内部的 `service` 字段已赋值；
  在那个时间窗里调绑定会立刻抛异常。
- **绑定成功不代表可用**：还要确认 `uid == 2000`，否则读设备一定失败，
  不如当场报错。

---

## 9. 「直连」后端：绕开 UserService（`ShizukuShell`）

### 9.1 为什么要加它

有用户反馈：Shizuku 在运行、已授权、版本 13.5，但**每次都**绑定 UserService
超时；而**同一台设备**上另一个用 Shizuku 的应用却正常。

我去读了那个应用（[Axon-Input](https://github.com/keepBacon/Axon-Input)）的源码，
发现它**完全不用 UserService** —— 它直接对 Shizuku 发**裸 Binder 事务**调
`newProcess`，拿一个长驻 `sh` 进程来用。

这就是关键结论：**Shizuku 服务端本身是好的，坏的只是 UserService 那一段**
（怎么 spawn、怎么托管子进程）。所以补一条直连通道绕开它。

### 9.2 ⚠️ `newProcess` 是 private：只能手写 Parcel

SDK 源码（13.1.5）里它是：

```java
@Deprecated
private static ShizukuRemoteProcess newProcess(String[] cmd, String[] env, String dir)
```

**private**，而且 javadoc 写明：

> This method is planned to be **removed from Shizuku API 14**.

所以 Axon-Input 手写事务号不是炫技，是被逼的。我们也只能照做。

### 9.3 ⚠️ 事务号是硬编码的，这是最脆弱的一环

```
newProcess 事务号 = 8
```

它是照 `IShizukuService.aidl` 的方法顺序数出来的：

| 序 | 方法 | 序 | 方法 |
|---|---|---|---|
| 1 | destroy | 5 | checkPermission |
| 2 | exit | 6 | checkSelfPermission |
| 3 | getVersion | 7 | getApplications |
| 4 | getUid | **8** | **newProcess** |

事务号 = `FIRST_CALL_TRANSACTION + 序号 − 1` = 序号。

⚠️ **如果 Shizuku 将来在 `newProcess` 前面插入方法，这个号就会错位**，
表现为 `transact` 失败或行为诡异。所以：

- **升级 Shizuku 后要重新数一遍**（改 `ShizukuShell.TX_NEW_PROCESS` 的注释）；
- 调试页有个「检测直连通道」按钮（`ShizukuShell.verifyNewProcessTransaction`），
  跑一条 `echo` 并读回显 —— 事务号错位时它会立刻报出来，
  而不是让用户面对一句"启动失败"。

`IRemoteProcess` 那几个号（`getInputStream` = 2、`destroy` = 6）稳定性高得多，
它们排在接口最前面，几乎不会动。

### 9.4 ⚠️ 进程会随应用一起死

javadoc 写得很明确：

> From version 11, like `su`, the process will be killed when
> **the caller process is dead**.

也就是说**应用被系统回收后，采集会中断** ——
这一点比 UserService（daemon 模式，应用死了远端还活着）**更脆弱**。
这是选直连必须接受的代价，也是两条后端都保留的原因。

### 9.5 读取进程用 `getevent`，不自己读设备

直连通道拿到的是一条**文本流**（进程 stdout），所以用系统自带的
`getevent -q` 来读设备：

```
应用 ──裸 Binder──▶ Shizuku ──spawn──▶ sh -c "getevent -q /dev/input/event0 …"
     ◀──────────── 进程 stdout（管道）──────── 文本行
```

好处是不用自己解析二进制 `input_event`、也不用先知道每个设备的能力。
代价：

- **多一次文本解析**（对这个应用完全可以接受）；
- ⚠️ **`getevent` 不能在运行中追加设备**：设备列表在启动那一刻定死，
  所以"中途插上手柄"不会自动开始工作。这是选它换来的简单性，
  **记在这里免得以后当成 bug 查**。

### 9.6 ⚠️ 解析文本时最容易错的一处：负数

`getevent` 把字段按 **int** 打印，所以负位移显示成 `ffffffff`。

```kotlin
"ffffffff".toInt(16)   // ❌ 抛 NumberFormatException
"ffffffff".toLong(16).toInt()   // ✅ -1
```

用前者的话，**鼠标往左/往上移动的事件会被整条丢掉**，
表现成"鼠标只能往右下动" —— 极其容易被误判成硬件问题。
`GeteventParserTest` 里有专门几条钉住这个（含设备前缀下的负数）。

### 9.7 两条后端怎么选

| | 直连（默认） | UserService |
|---|---|---|
| 谁 spawn 读取进程 | Shizuku 直接起 `sh` | Shizuku 托管用户服务 |
| 事件回传 | 进程 stdout 文本行 | AIDL 批量回调 |
| 应用被杀后 | **采集中断** | 远端继续活着，能续上 |
| 依赖的 API | `newProcess`（**已废弃**，API 14 移除） | `bindUserService`（正式 API） |
| 出问题的设备 | — | 有用户恒定时败 |

切换入口在**调试页 → 采集模式 → Shizuku 后端**，改完立即重启采集。
默认值定义为 `ShizukuBackend.DEFAULT`（= 直连）。

### 9.9 ⚠️ 启动速度：先快后稳（`StartupPolicy`）

#### 曾经慢得离谱：5 秒

第一版是**串行**给每个设备起进程：

```
for (device in devices) {
    起 shell（binder 往返）→ 读握手 → 探活等满 300ms → 查 /proc 复核
}
```

7 个设备 × 约 700ms ≈ **5 秒**。根因不是"这条通道天生慢"，
而是**把本来独立的事情排成了一队** —— 设备之间毫无依赖。

#### 现在：并行 + 两轮策略

```
枚举设备
  ├─ 第 1 轮「偏快」：并行起，150ms 探活，不做 /proc 复核
  │    └─ 失败且"看着只是太急了" → 保留原因，继续
  └─ 第 2 轮「偏稳」：并行起，500ms 探活，带 /proc 复核
       ├─ 成功 → 监听中，日志记一句"偏快失败、偏稳成功"
       └─ 失败 → 报出**两轮**的全部原因
```

并行之后总耗时约等于**单个设备**的耗时。另加 `MAX_CONCURRENT_STARTS = 6`
限制并发：某些机器有十几个输入节点，一口气全发会让 Shizuku 服务端
同时 spawn 十几个进程。

#### ⚠️ 不是所有失败都值得重试

有些失败是**确定性**的，重试只让用户白等一倍时间：

| 失败表现 | 值得跑第二轮吗 |
|---|---|
| 探活窗口内没确认存活、但**没有任何报错输出** | ✅ 典型的"太急了" |
| 有报错输出（用法提示、`could not open …`） | ❌ 换多厚的探活都一样 |
| **一个设备都没打开** | ✅ 可能是整体性时序问题 |
| 部分成功、部分失败 | ❌ 已经能用了，重试只会拖慢 |

判据实现在 `StartupPolicy.decide`，由 `StartupPolicyTest` 的 10 条用例钉住
（那段逻辑在真机上不容易复现 —— 要碰巧遇到慢启动才会走第二轮）。

#### ⚠️ 两轮的失败原因都要留着

只报最后那一轮的话，**真正有用的线索可能在前一轮**：第一轮读到了
`getevent` 的用法提示（说明参数不对），第二轮因为探活更厚，
报的可能只是一句笼统的"进程已退出"。

这个项目已经踩过一次"证据被覆盖"（见 §9.6 附近关于 `Probe.evidence` 的说明），
所以 `StartupPolicy.describeFailures` 会把所有轮次的原因一起输出，
连"试了几种策略"都写在结论里 —— 否则用户会以为我们只试了一次。

### 9.10 ⚠️ 一个设备一个进程，且**一个进程只能一个设备**

`getevent` 的用法是：

```
Usage: getevent [-t] [-n] … [-c count] [-r] [device]
                                               ^^^^^^^^ 单数！
```

**它只接受一个设备参数**，传多个会直接打印用法然后退出。

这里踩过一个很贵的坑：为了"省事"曾经写成
`getevent /dev/input/event0 /dev/input/event1 …`（单进程监听全部），
结果在真机上直接失败，现象是"探测期读到 13 行非事件输出、随后进程立刻退出" ——
那 13 行就是这段用法文本。

正确做法是**每个设备一个进程**（老的 UserService 版本也是这么做的，
它给每个设备一个独立读取线程）。

> 教训：**别为了"优化"去合并一个已经被验证过能工作的结构。**
> 那里的一设备一进程不是随意写的，是 `getevent` 的参数限制决定的。

### 9.8 R8 注意

⚠️ 新增的 `ShizukuShell` / `ShizukuGeteventInputSource` **不需要** keep 规则 ——
它们只被直接引用，被混淆是**正确**的（mapping 里能看到
`ShizukuGeteventInputSource -> tk1`）。

**只有** `ShizukuInputService` 需要 keep：它由 Shizuku 在另一个进程里
**按类名字符串反射加载**。别把两者的规则搞混。
