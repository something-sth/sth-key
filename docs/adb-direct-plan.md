# 软件内 ADB 授权（Shell / 直连通道）方案

> 状态：进行中（5555 直连、Shell 折叠盒、ADB 采集通道已在模拟器验证；配对真实流程待验）
> 本文只描述设计与实施计划，不改任何代码。

## 1. 目标

让**没有 root、也不想装 Shizuku** 的用户，直接在应用内完成 ADB 授权：

- Android 11+：走系统「无线调试」的**配对码**流程；
- 任意版本：检测到 **5555** 端口（用户自己用 root / `adb tcpip 5555` 打开）时，直接连端口启动；
- 授权成功后，采集通道与现在 Shizuku 的**直连**完全一致：用 shell（uid 2000）起 `getevent` /
  原生手柄 helper，读它们的 stdout；**不引入 UserService、不在应用进程里提权**。

## 2. 关键结论

1. **ADB 能替代 Shizuku 的"输入读取"**：ADB shell 的 uid 就是 `shell(2000)`，和 Shizuku
   `newProcess` 拿到的身份一样，`getevent`、`/dev/input`、手柄 native helper 都能用。
2. **悬浮窗仍然走无障碍，这是有意的设计**：`TYPE_ACCESSIBILITY_OVERLAY` 由
   `AccessibilityService` 的 context 创建，这条不变。ADB 只解决"读输入"。
3. **ADB 连接会随进程/无线调试状态断开**：应用进程被杀、Wi-Fi 断、无线调试关闭都会断；
   需要重连状态机 + mDNS 重新发现端口。
4. **我们不需要自己的 `.so` 启动器**（与 Shizuku 的关键区别，详见 §3）：
   - Shizuku 需要 `.so`，是因为它要**启动一个常驻的 Java 服务进程**（`shizuku_server`），
     必须借 ADB/root 身份把它拉起来；
   - 我们的 App 自己就是 ADB 客户端：`openStream("shell:...")` 直接就是 shell 进程，
     **不需要常驻服务、不需要 launcher**；
   - 5555 路径更直接：连上 adbd 就能开 shell，连配对都不需要。
   → 原方案里的 "native 桥 + 命令启动" **从核心计划中移除**，只在未来确实需要
     "不依赖 adbd 端口/配对"时才考虑。

## 3. Shizuku 启动方式研究：start.sh → libshizuku.so

### 3.1 老版本：start.sh

老版本让用户执行：

```
adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh
```

`start.sh` 由 App 写入外部目录，负责设置 `CLASSPATH` 再 exec `app_process`。
问题：Android 11 scoped storage 后路径不可靠；外部存储 `noexec`；脚本要自己处理
APK 路径、引号、dex 路径，安装路径一变就可能失效。

### 3.2 新版本：libshizuku.so

Shizuku 13.x 改成：

```
adb shell /data/app/~~<随机>==/moe.shizuku.privileged.api-<随机>==/lib/arm64/libshizuku.so
```

- 它其实是一个 **ELF 可执行文件**，故意命名 `.so`，以便作为 native lib 被解压到
  `nativeLibraryDir`；该目录的文件天然可执行，shell 可直接 exec；
- 路径每次安装都变，但 App 可以用 `applicationInfo.nativeLibraryDir` 算出来
  （Shizuku 的 `Starter.kt` 就是 `File(application.applicationInfo.nativeLibraryDir, "libshizuku.so")`），
  所以能生成给用户复制的命令；
- `starter.cpp` 干的事：检查 uid 必须是 0 或 2000 → fork → `execvp("/system/bin/app_process")`，
  设置 `CLASSPATH=<base.apk>`、`--nice-name=shizuku_server`，启动
  `rikka.shizuku.server.ShizukuService`；父进程打印 pid 后退出。

你看到的日志：

```
Starting with wireless adb in port 36835...
info: starter begin
info: killing old process...
info: apk path is .../base.apk
info: starting server...
info: shizuku_server pid is 5496
```

前半段是 Shizuku 的无线启动流程（它自己先建立一个 ADB 连接/端口），
后半段就是 `libshizuku.so` 在起 `shizuku_server`。**这个 `.so` 对 Shizuku 是必需的**，
因为它的服务必须作为独立进程常驻。

### 3.3 对我们的含义

- 我们不启动服务，只借用 `adb shell`；所以 **libshizuku.so 那种 launcher 对我们是可选的**；
- 我们真正需要的是 **ADB 客户端**（配对 / 5555 连接 / 开 shell 流），
  这部分用 libadb 或移植 Shizuku 的 `AdbClient` 即可；
- 因此原方案 B（native 桥）降级为"未来可选"，核心计划只做 A（ADB 客户端）。

## 4. 技术选型

| 需求 | 方案 | 理由 |
|---|---|---|
| ADB 协议 / TLS / 配对 | **libadb-android 3.1.1**（Apache-2.0 / GPL-3.0 双许可） | `pair()`、`connectTls()`、`autoConnect()`、`openStream("shell:")`、`AdbMdns` 都是现成的 |
| 备用：自己实现 | 参考/移植 Shizuku 的 `AdbClient`、`AdbKey`、`AdbPairingClient`、`AdbMdns`（Apache-2.0） | 与 Shizuku 行为一致；但要自己接 SPAKE2 |
| 无线调试端口发现 | libadb 自带 `AdbMdns` | 不用自己写 mDNS |
| 5555 检测 | 尝试连接 `127.0.0.1:5555`（以及用户手填端口） | 用户 root/`adb tcpip` 打开后可直接用 |
| 配对码 | 用户在应用内输入 6 位码 | 系统弹窗只显示码，没有 API 可读 |
| 客户端密钥/证书 | RSA 2048 + 自签 X.509，首次生成后存应用私有目录 | 每个安装独立密钥；**不用 BouncyCastle** |
| 证书生成 | 优先 `sun-security-android:1.1`，不合适就手写最小 DER | 控制体积 |
| TLS Provider | Android 平台 TLS 1.3（Android 10+） | 配对要求 Android 11+；Android 10 及以下走 5555 |
| 原生手柄 helper 下发 | ADB `sync:` push 到 `/data/local/tmp`，`chmod 700` 执行 | 和 Shizuku 路径一样 |

> 依赖决策：优先 libadb（JitPack）；拉不到就 vendor 源码；再不行才移植 Shizuku 的 ADB 客户端。
> **不引入 BouncyCastle**。

## 5. 架构

```
┌────────────────────────────── App 进程 ──────────────────────────────┐
│                                                                      │
│  AdbAuthorization (单例)                                             │
│   ├─ KeyStore：RSA 私钥 + 自签证书（filesDir/adb/）                   │
│   ├─ AdbConnectionManager（libadb）                                  │
│   ├─ AdbMdns：配对端口 / 连接端口发现                                 │
│   ├─ PortProbe：检测 5555 / 手填端口                                  │
│   └─ StateFlow<AdbState>                                            │
│                                                                      │
│  AdbShellProvider : ShellProvider                                    │
│   ├─ start("getevent -t") -> RemoteShell(inputStream, close)         │
│   ├─ exec("id") -> (exitCode, stdout, stderr)                        │
│   └─ push(asset/helper, /data/local/tmp/...)                         │
│                                                                      │
│  CaptureController                                                   │
│   ├─ KeyMode.ADB -> AdbGeteventInputSource(AdbShellProvider)         │
│   └─ KeyMode.ADB -> AdbGamepadLauncher(AdbShellProvider)             │
└──────────────────────────────────────────────────────────────────────┘
```

## 6. 授权 UI 与流程

### 6.1 Shell 授权折叠盒（权限引导页）

把现在的「Shizuku 授权」卡换成一个**折叠盒**：

```
Shell 授权                    [已授权 / 未授权]
  ├─ Shizuku 授权（原卡片，保持不变）
  └─ ADB 授权（新卡片，按钮动态变化）
```

### 6.2 ADB 卡片状态

| 条件 | 卡片按钮 |
|---|---|
| 检测到可用端口（5555 / 手填端口） | **通过 5555 端口启动**（端口号动态） |
| 无可用端口，Android 11+ | **启动** + **配对** 两个按钮 |
| 无可用端口，Android 10 及以下 | 按钮位置显示 **不可用** |

> ⚠️ 5555 首次连接的现实问题：adbd 默认 `ro.adb.secure=1`，会校验客户端公钥。
> 我们 App 的密钥不在 `adb_keys` 里时，连接会触发系统的「允许 USB 调试吗？」弹窗
> （或直接 AUTH 失败）。所以 5555 路径要处理两种情况：
> - 系统弹窗出现 → 引导用户点「允许」（需要亮屏）；
> - 无弹窗、直接失败 → 提示"该端口需要先授权本应用的调试密钥"；
>   有 root 的用户可以把 App 导出的公钥追加到 `/data/misc/adb/adb_keys` 后重启 adbd。
> 配对路径没有这个问题（`pair()` 会把密钥写进 adbd 的授权列表）。

「配对」按钮点击后：

1. 申请通知权限（Android 13+，配对的保活前台服务需要）；
2. 跳转开发者选项 / 无线调试页；
3. 应用在后台用 mDNS 监听 `_adb-tls-pairing._tcp`，提示用户输入 6 位配对码；
4. 配对成功后保存密钥，回到应用点「启动」即可连接（`_adb-tls-connect._tcp`）。

「启动」按钮：

- 有 5555 端口 → 直接 `connect("127.0.0.1", 5555)`，跑 `id` 校验；
- 已配对无线调试 → `connectTls()`，跑 `id` 校验；
- 成功后状态变「已授权」，按钮变为「已连接 / 断开」。

### 6.3 状态机

```
IDLE ──检测端口──> PORT_FOUND(5555)
  ├─ connect 成功 ──> CONNECTED
  └─ connect 失败 ──> IDLE
IDLE ──配对──> PAIRING(等待配对码)
  ├─ pair 成功 ──> PAIRED
  └─ 失败/取消 ──> IDLE
PAIRED ──启动──> CONNECTED
CONNECTED ──socket 断/Wi-Fi 断──> RECONNECTING(退避)
CONNECTED ──无线调试关闭──> NEEDS_WIRELESS_DEBUGGING
CONNECTED ──adbd 不认密钥──> NEEDS_PAIRING
```

所有 ADB 操作在 `Dispatchers.IO`，单飞（`Mutex`）；状态用 `StateFlow` 暴露给 UI。

## 7. 需要改动的代码清单

| 文件 | 改动 |
|---|---|
| `gradle/libs.versions.toml`, `app/build.gradle.kts` | 加 libadb、证书生成库、JitPack（或 vendor） |
| `capture/shell/ShellProvider.kt`（新） | `RemoteShell`、`CommandResult`、`ShellProvider` |
| `capture/shizuku/ShizukuShell.kt` | 适配 `ShellProvider`（对外 API 不变） |
| `capture/adb/AdbAuthorization.kt`（新） | 密钥/证书、连接、配对、mDNS、端口探测、状态机 |
| `capture/adb/AdbShellProvider.kt`（新） | shell 流、exec（`EXIT:` 标记）、`sync:` push |
| `capture/adb/AdbPortProbe.kt`（新） | 5555 / 手填端口可连性检测 |
| `capture/shizuku/ShizukuGeteventInputSource.kt` | 抽成通用 `ShellGeteventInputSource` |
| `capture/gamepad/GamepadNativeMonitor.kt` | 加 `AdbGamepadLauncher` |
| `domain/capture/KeyMode.kt` | 加 `ADB(id="adb", label="ADB")` |
| `domain/capture/CaptureCapabilities.kt` | 加 `adbReady`/`adbDetail`；AUTO：`root → shizuku → adb → 无障碍` |
| `capture/CaptureController.kt` | ADB 分支；能力探测 |
| `capture/InputSource.kt` | `CaptureChannel.ADB` |
| `ui/feature/setup/SetupScreen.kt` | 「Shell 授权」折叠盒 + ADB 卡片 |
| `ui/feature/capture/CaptureModeScreen.kt` | ADB 分段按钮 + 授权卡片 |
| `ui/MainViewModel.kt` | ADB 状态、授权/启动/断开动作 |
| `AndroidManifest.xml` | `INTERNET`、`ACCESS_NETWORK_STATE`、`POST_NOTIFICATIONS`（已有） |
| `NOTICE` | libadb（Apache-2.0）、spake2（LGPL-3.0）、sun-security（GPL-2 + classpath） |

> 原方案里的 `libsthkey_adb.so` / native 桥 / 命令入口**从本期删除**（见 §2.4）。
> 如果以后要做，再单开一期。

## 8. 安全与许可

- ADB 客户端密钥 = 设备授权凭据：RSA 2048，`filesDir/adb/`（`MODE_PRIVATE`），
  不导出、不进日志；配对码只在内存里用一次；
- 5555 是**明文**且对局域网开放：用户自己用 root 打开时要知道风险；
  应用只连 `127.0.0.1` / 用户手填的地址，不主动扫描局域网；
- ADB shell 权限 = uid 2000，能读所有输入事件（和 Shizuku 一样），不涉及 root；
- 许可：libadb 选 Apache-2.0；spake2-android 为 LGPL-3.0（动态链接、保留声明）；
  sun-security 为 GPL-2.0 + classpath 例外——都写进 NOTICE。

## 9. 体积影响

| 依赖 | 预估 dex 增量 |
|---|---|
| libadb（含 spake2） | 200~400 KB |
| sun-security-android | 100~300 KB |
| 若用 BouncyCastle | +3~5 MB，**不做** |

当前 APK 6.0 MB，控制在 **+0.5 MB 以内**。

## 10. 分阶段实施

| 阶段 | 内容 | 验收 |
|---|---|---|
| P0 | 依赖 + 密钥/证书 + 空白页 pair/connect；`id` 打印 2000 | 真机能连 |
| P1 | `ShellProvider`；ADB `exec` / `start("getevent -t")`；`AdbGeteventInputSource` | 能读键盘/鼠标/手柄 |
| P2 | 「Shell 授权」折叠盒 + ADB 卡片（启动/配对/不可用）、状态机、重连 | 无 root、无 Shizuku 手机走通 |
| P3 | 5555 端口探测 + 一键「通过 5555 端口启动」 | 用户自行开端口后可用 |
| P4 | 手柄 helper `sync:` push + `AdbGamepadLauncher` | 手柄摇杆/扳机生效 |
| P5 | 错误文案、日志、文档、测试矩阵 | Android 10 / 无 mDNS 环境可用 |

## 11. 风险与未决问题

1. **无线调试随时可能被系统关掉**：部分 ROM 熄屏/切网后会关，需要文案与重连策略。
2. **mDNS 在部分路由器/ROM 上不可靠**：保留手动填端口；5555 作为兜底。
3. **5555 的安全面**：明文、局域网可连；只在用户明确打开后用，UI 要提示风险。
4. **libadb 未经安全审计**：只连本机/用户指定地址，不接受任意 host。
5. **JitPack 可达性**：当前网络不稳，需先验证；不行就 vendor。
6. **ADB 与 Shizuku 并存**：AUTO 用 `root → Shizuku → ADB → 无障碍`；
   Shell 折叠盒里说明"两者能力相同，选一个即可"。
7. **Android 10 及以下无 5555**：按你的要求显示「不可用」，不提供命令桥。

## 12. 测试矩阵

| 场景 | 预期 |
|---|---|
| 有 root，手动开 5555 | 卡片显示「通过 5555 端口启动」，连接后 `id`=2000 |
| 无 5555，Android 11+，无线调试配对 | 「配对」→ 权限/引导 → 输码 → 配对成功 →「启动」→ 已连接 |
| 配对码输错 | 明确报错，可重试，不残留半连接 |
| 关闭无线调试 / 重启 | 状态变为需要无线调试/需要重新启动；5555 失效 |
| Android 10，无 5555 | 卡片显示「不可用」 |
| 有 Shizuku 的设备 | Shizuku 卡片行为不变；Shell 折叠盒状态正确 |
| 有 root / ADB 同时可用 | AUTO 优先级符合 `root → Shizuku → ADB → 无障碍` |
| 配对后撤销授权 | 重连报"需要重新配对" |
