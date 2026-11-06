# FPS 显示：实现说明（当前状态）

> 参考实现:[FPS-Meter-Android](https://github.com/rdevz-ph/FPS-Meter-Android)
> 相关文档:`docs/fps-plan-review.md`（早期调研与踩坑记录）

---

## 1. 数据结构与入口

| 位置 | 内容 |
|---|---|
| `data/fps/FpsMonitor.kt` | 采集单例:`Reading(value, source, seq)` + `Mode(AUTO/CHOREOGRAPHER/SURFACE_FLINGER)` |
| `domain/custom/FpsText.kt` | `(fps)` 占位符解析与求值（纯函数） |
| `ui/overlay/CustomKeyGrid.kt` | 渲染时把 `displayTextAt(...)` 结果套一层 `FpsText.render(text, fps)` |
| `ui/feature/capture/CaptureModeScreen.kt` | 「FPS 读取通道」卡片（自动 / Choreographer / Surfaceflinger） |
| `core/prefs/AppPrefs.kt` | `fpsMode`（存 id 字符串，不存 ordinal） |
| `capture/OverlayService.kt` | `refreshFpsNeed(...)`：按 `(fps)` 占位符启停；`onDestroy` 兜底停止 |

---

## 2. 采样链路（Surfaceflinger 模式）

```
前台包  dumpsys window | grep mCurrentFocus/mFocusedApp
         （排除 android / systemui / launcher / quickstep / 我们自己）
   ↓
层解析  Output Layer 优先 + --list 兜底，按优先级取:
        SurfaceView+BLAST → SurfaceView → BLAST/BBQ → 其它
        （按包缓存;切包时清空）
   ↓
① --latency '<层名>'  时间戳差量
         （层名先试 8 种写法，第一个成功的按包缓存）
   ↓ 失败
② frame= 计数器差量   shell 侧 grep 过滤，逐个候选层试
         （连续 2 拍靠 ② 成功 → 该包标记"只能靠计数器"）
   ↓
Reading → UI（1 Hz）
```

⚠️ **Vulkan 判定**：`dumpsys gpu` 里 `createdVulkanSwapchain = 1`（或
`gfxinfo` 的 `Skia (Vulkan)`）→ 直接走 ②，一条 `--latency` 都不跑。

---

## 3. 执行通道

| 通道 | 实现 | 备注 |
|---|---|---|
| Shizuku | `ShizukuShell.start(cmd)`（binder 直连） | 每拍几十毫秒 |
| root | **常驻 `su` shell**（起一次，之后写 stdin / 读 stdout，`__FPS_DONE__` 收尾） | 每拍省掉 ≈300ms 启动；超时/死亡自动重启，失败退回一次性 `su -c` |
| 都无 | Choreographer 节拍 | **估算上限**，不是游戏帧率 |

⚠️ 通道优先级：Shizuku 有权限就用 Shizuku，否则 root。
手动选「Surfaceflinger」但没特权时 → **N/A，不偷偷降级**（便于排查）。

---

## 4. 启停（省电）

1. `syncWindows()` 每次重建悬浮窗后调用 `refreshFpsNeed(configs)`
2. 任一启用配置里有文本组件含 `(fps)` → `FpsMonitor.start()`
3. 没有 → **5 秒 debounce** 后 `stop()`（避免编辑文本时反复启停）
4. `OverlayService.onDestroy()` 兜底 `stop()`（占位符全关、窗口全关时）

---

## 5. 占位符语法

```
(fps)                       → 帧率
(fps+1000) / (fps-5) / (fps*2)
(fps+1000,offset=20-80)     → 先运算，再随机 ±[20,80]（每秒重抽）
```

- 写错（`(fps+abc)`）→ **原样显示**，不吞文本
- 读不到 → `N/A`，不参与运算

---

## 6. 已知行为（不是 bug）

| 现象 | 原因 |
|---|---|
| 游戏登录页/加载时闪几次 `N/A` | 那几秒没有可测的层（FPS-Meter 同样会跳 0） |
| 桌面/我们自己的界面显示 `N/A` | 前台不是游戏，按 FPS-Meter 的语义就不报 |
| 无权限时数字约等于刷新率 | Choreographer 是**估算上限**，不是游戏帧率 |

---

## 7. 后续可做

| # | 内容 | 价值 |
|---|---|---|
| 1 | 把 `latencyCandidates` 持久化（现在只在内存，重启后重新试探一次） | 中 |
| 2 | 冷启动前 3 秒不采样（跳过 Splash 层） | 低 |
| 3 | 调试页显示「数据源 / 目标层 / 采样耗时」 | 中 |
| 4 | 1% Low（需要 `--latency` 原始时间戳） | 低 |
| 5 | 公告里写一句「无权限时是估算上限」 | 中 |
