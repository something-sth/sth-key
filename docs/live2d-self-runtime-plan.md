# Live2D 复杂模型：自研 runtime 方案

> 决策（2026-11-22）：**不移植 Axon 的 `custom/runtime.js`**。
> Axon 没有 LICENSE、README 限定非商业/个人使用，且作者失联；
> 我们的目标是自己维护、许可干净。Axon 只作为**架构参考**，不复制代码。
>
> 关联：`docs/live2d-complex-plan.md`（现状盘点）、`docs/live2d.md`（原有契约）

---

## 1. 为什么可以自研（起点比想象中高）

`assets/bongocat/keyboard/runtime.js` 现在已经是一个**能画对模型**的渲染器，已具备：

| 能力 | 现状 |
|---|---|
| moc3 加载 / 贴图上传 | ✅ 基64 + `<img>` |
| 绘制顺序、`renderOrder` | ✅ |
| **遮罩**（含 inverted mask，stencil） | ✅ |
| 预乘 Alpha / 混合 | ✅ 单一 `ONE / ONE_MINUS_SRC_ALPHA` |
| 呼吸（`ParamAngle*` / `ParamBreath`） | ✅ 内置公式 |
| 眨眼 | ✅ |
| 表情（exp3，Add/Multiply/Overwrite） | ✅ |
| 指针/按键参数覆盖 | ✅ |
| 内容包围盒 + 比例回报 | ✅ |

**缺的四块**，正好就是"复杂模型 bug"的来源：

| # | 缺口 | 表现 |
|---|---|---|
| 1 | **physics3** | 头发/尾巴/饰品不摆动 |
| 2 | **motion3** | Idle/动作不播放，模型像一张图 |
| 3 | **pose3 + 混合模式 + multiply/screen 颜色** | 换装/部件显隐不对、颜色发灰/发白 |
| 4 | **按键 → 动作绑定（Mver `config.json` / cdi3）** | 按键只能动"手"，不能触发动作/表情 |

---

## 2. 架构（保持现有契约）

```text
导入时（Kotlin）                          运行中（JS）
model3 + physics3 + motion3 + exp3 …  →  modeldata.js（window.__BONGO_*）
                                          ↓
                                   keyboard/runtime.js
                                          ↓
                                    CoreRenderer.render()
```

- **仍然全部内联**：moc 基64、纹理相对路径、movement/physics 的 JSON 原文；
  不新增 `fetch`/XHR（`file://` 限制不变）；
- 旧模型（`index.html` 已生成）通过 `repairImportedPages()` 重写，
  页面版本标记 `<!-- sthkey-page:N -->` 控制；
- 内置两套模型**不启用**新数据（它们的 modeldata 里没有这些字段，逻辑自动跳过）。

---

## 3. 里程碑

### S1 物理（physics3）—— 最见效，先做

| 项 | 内容 |
|---|---|
| Kotlin | 导入时把 `Physics` 文件原文写进 `window.__BONGO_PHYSICS` |
| JS | `PhysicsEvaluator`：解析 `PhysicsSettings`（Normalization / Input / Output / Particles） |
| 算法 | Cubism 官方物理：`1 - (1-x)^(1/3)` 归一化、输入加权、粒子链（pendulum）积分、输出映射 |
| 顺序 | 呼吸/眨眼/覆盖之后、`model.update()` 之前求值 |
| 验收 | 卡提：头发/尾巴/饰品在鼠标移动和呼吸时摆动；星野：发梢轻微摆动 |

### S2 动作（motion3）

| 项 | 内容 |
|---|---|
| Kotlin | `Motions` 分组 → `__BONGO_MOTIONS` / `__BONGO_MOTION_GROUPS`（原文 JSON） |
| JS | `MotionPlayer`：曲线求值（Linear / Bezier / Stepped / InverseStepped）、时长、循环、淡入淡出 |
| 播放 | `Idle` 自动循环；暴露 `AxonBongoCat.motion(group, index)` 供按键/测试调用 |
| 顺序 | 参数重置之后、呼吸之前（motion 提供基础值，呼吸再叠加） |
| 验收 | 有 Idle 的模型自动动起来；卡提的 `CAT_motion_lock` 可手动触发 |

### S3 姿态 / 混合

| 项 | 内容 |
|---|---|
| `pose3` | Part 的 `Opacity` 覆盖（切换部件） |
| 混合模式 | 按 drawable `constantFlags` 的 `Multiply` / `Screen` 分别用不同 shader（现在只有 Normal） |
| 颜色 | `multiplyColor` / `screenColor` 传入 shader（否则深色模型会发灰） |
| 验收 | 带换装/高光的模型颜色正确，不再"发白/发灰" |

### S4 按键绑定

| 项 | 内容 |
|---|---|
| Mver 包 | 读 `config.json` 的按键 → motion/表情索引 |
| 第三方 | 用 cdi3 推断参数语义；没有可绑定参数的在导入报告里说明 |
| 验收 | 卡提键盘模式按键能触发 `CAT_motion_lock` 里的动作 |

---

## 4. 数据结构（JS 全局，全部可缺省）

```js
window.__BONGO_PHYSICS      = { "Version":3, "Meta":{...}, "PhysicsSettings":[...] } | null
window.__BONGO_MOTIONS      = [ { "name":"Idle/0", "json":{...}, "fadeIn":0.5, "fadeOut":0.5 } ]
window.__BONGO_MOTION_GROUPS= { "Idle":[0], "CAT_motion_lock":[1,2,...] }
window.__BONGO_POSE         = { "Version":3, "Groups":[...] } | null
```

> 体积控制：physics/pose/单条 motion 都设上限（沿用 Axon 的思路，但自己定值），
> 超限就跳过并在导入报告里写一句。

---

## 5. 关键算法要点（避免踩坑）

### 物理（Cubism Physics）

1. **Normalization**：`normalize(x) = 1 - (1 - x)^(1/3)`（官方公式），范围 `Minimum`/`Maximum` 要正确；
2. **输入**：`Position` / `Angle` 两种，按 `Weight` 求和后除以总权重；
3. **粒子链**：
   ```
   a = (input - output) * 速度
   v += a * dt
   v *= (1 - 阻尼)
   output += v * dt
   ```
   注意每段 `Radius` 与 `Particle` 的 `Mobility`/`Delay`/`Acceleration`；
4. **输出**：按 `Scale`/`Weight` 映射回参数，**必须 clamp 到参数范围**；
5. 物理要在 `model.update()` 前算完；`dt` 用真实帧间隔并 clamp。

### 动作（motion3）

- `Curves[i].Segments` 是一维数组：
  - `0` Linear：`[0, t0,v0, t1,v1, ...]`（多点折线）
  - `1` Bezier：`[1, t0,v0, c1t,c1v, c2t,c2v, t1,v1]`
  - `2` Stepped：`[2, t0,v0, t1,v1]`
  - `3` InverseStepped：与 stepped 相反（值在**前一节点**保持）
- 淡入淡出：权重 `w` 在 `fadeIn`/`fadeOut` 区间线性变化；
- 应用：`param = param * (1-w) + motionValue * w`，**不要**用加法（否则呼吸叠加会漂移）；
- `Meta.Loop` 为 true 时循环；`FadeInTime`/`FadeOutTime` 在 model3 里也有，优先用 model3 的值。

---

## 6. 验证

| 层 | 方法 |
|---|---|
| JS | `tools/live2d-harness`（Chrome headless）：截图 + 帧差，确认物理/动作在动 |
| JVM | Kotlin 解析器单测（合成 JSON，不依赖样本） |
| Android | 模拟器：导入卡提/星野，观察悬浮窗；真机最终确认 |

样本：卡提（physics 13 组、19 motion、4 exp3）、星野（physics 1 组、8 exp3）。

---

## 7. 风险与回退

| 风险 | 对策 |
|---|---|
| 物理参数语义写错 → 抖动/爆掉 | 先跑官方公式，`dt` clamp，输出 clamp；用卡提对比"摆幅合理" |
| 动作曲线求值错 | 单测 + 在 Chrome 里逐帧对比参数值 |
| 混合模式 shader 改动影响内置模型 | 默认 Normal 分支保持现状，只有明确标记时才切换 |
| 数据体积 | 每条 motion ≤ 1 MB、physics/pose ≤ 2 MB，超限跳过并报告 |
| 回退 | 每个字段都可缺省；没有 physics/motion 时行为与现在**完全一致** |

---

## 8. 实施记录

### S1 物理：已接入（2026-11-22）

| 文件 | 改动 |
|---|---|
| `domain/live2d/Live2DModelImporter.kt` | 新增 `loadPhysics()`：读 `FileReferences.Physics` 原文（≤2MB，失败/缺失返回 null），两处导入路径都写 `window.__BONGO_PHYSICS` |
| `assets/bongocat/keyboard/runtime.js` | 新增 `PhysicsRig`（归一化 → Input → 粒子链 → Output），在 `prepareParameters()` 末尾求值；无 physics 时为 null，旧模型行为不变 |
| `tools/live2d-harness/build_sthkey.py` | 用**我们自己的 runtime** 生成验证页（区别于 `build.py` 的 Axon 对照页），带参数探针 |
| `assets` 体积 | physics 原文内联，卡提 21KB |

**实测（卡提 keyboard，Chrome headless）**：

```json
{"ready":true,"physics":true,"angleX":5.09,
 "params":{"hair1":-2.913,"hair2":2.107,"chest1":-0.115},
 "debug":{"hair1":-0.0728,"hair2":0.0527,"chest1":-0.1539}}
```

- 参数值非 0 且随输入变化 → 物理确实在跑；
- 画面正常（没有炸裂/错位），827 个 JVM 用例全过。

⚠️ **踩到的两个坑（都写进注释了）**：

1. `Normalization` 的键名是 **`Default`**，不是 `DefaultValue`；
2. 粒子链的 `lastGravity` 初始值必须是 **(0, 1)** —— 官方 `initialize()` 先写 `(0,-1)` 再 `*= -1`，漏掉这一步第一帧会按 π 旋转，头发直接贴在极限角。

⚠️ **老模型不会自动获得物理**：`repairImportedPages()` 只重写页面骨架、不动 `modeldata.js`。
测试时**重新导入一次**模型即可；以后可以考虑给 `modeldata.js` 加版本标记并重生成。

### S2 动作：已接入（2026-11-22）

| 文件 | 改动 |
|---|---|
| `Live2DModelImporter.kt` | `loadMotions()`：按 model3 的组顺序读 motion3 原文（单条 ≤1MB / 合计 ≤8MB，超限填 `-1` 占位不压缩索引），写 `__BONGO_MOTIONS` / `__BONGO_MOTION_GROUPS` |
| `keyboard/runtime.js` | `MotionPlayer`：四种曲线（Linear/Bezier/Stepped/InverseStepped）求值、淡入淡出、循环；`Idle` 组自动循环；公开 `motion(group, index)` / `motionGroups()` / `motionState()` |
| 求值顺序 | 默认值 → **动作** → 呼吸 → 眨眼 → 覆盖 → 表情 → 物理（动作提供基础姿势，呼吸叠加） |

**实测（卡提 `CAT_motion_lock`，Chrome headless）**：

```json
"motion":{"name":"CAT_motion_lock/0","time":1.4,"duration":1.633,"loop":true,"weight":1}
"angleX":11.09  "hair1":-3.801  "chest1":-0.112
```

动作时间、权重、曲线结果都在变化，且物理随后跟着动 → 动作链路成立。

⚠️ 无头 Chrome 的 `requestAnimationFrame` 跑不满，验证台用 `__BONGO_DEBUG` 下的
`AxonBongoCat.debugTick(1/60)` 手动推进；真机不需要。

### S4 按键 → 动作：已接入（2026-11-22）

| 文件 | 改动 |
|---|---|
| `Live2DModelImporter.kt` | `loadMotionBindings()`：读包根 `config.json`（允许注释），按模式取 `l2d_motion` → `CAT_motion`、`l2d_motion_lockhand` → `CAT_motion_lock`；外层下标 = 组内动作下标，内层 VK 码用 `vkKeyName()` 翻成键名 |
| `keyboard/runtime.js` | `evaluateMotionBindings()`：组合**刚凑齐**时触发一次（按住不重播，松开可再触发）；`playedGroup(group,index,loop)` 支持一次性动作；动作状态不再被上一次的 `loop` 污染 |

**实测（卡提 standard，Chrome headless）**：

```json
"motion":{"name":"CAT_motion_lock/0","time":0.8,"duration":1.633,"loop":false,"weight":1}
```

模拟 `Alt + Num1` 成功触发第 0 条动作（`bsdk`），一次性播放，物理随后跟随。

⚠️ 行为按包配置**忠实**执行：卡提的 `keyboard` 段里 `l2d_motion`/`l2d_motion_lockhand` 都是空数组，
所以**键盘变体没有动作**（只有手部贴图），动作在 `standard` 段。别的包可能相反。

### S5 按键 → 手部贴图：已接入（2026-11-22）

Mver 包的"按键盘有反应"其实是**手部 PNG 覆盖**，不是模型动作。做法：

| 文件 | 改动 |
|---|---|
| `Live2DModelImporter.kt` | `loadHandSprites()`：读 config 的 `lefthand`/`righthand`（键组合 → 图号）；**目录不存在就不写绑定**（避免收手臂却 404）；写 `__BONGO_HANDS` |
| `keyboard/runtime.js` | 新增 `leftHand`/`rightHand` 图层：按下时显示最近按键对应的手图、松开隐藏；同时把 `CatParam*HandDown` 设为 1 收起模型手臂；**有手图时按设计画布（1400×1400）铺模型**，手图与模型共用同一变换 |
| 页面 | `buildHtml`/验证台加 `#leftHand`/`#rightHand` 两个 `<img>` |

⚠️ 关键：手图是 1400×1400 的**设计画布**坐标，模型必须按画布等比例铺；
按内容包围盒放大会让手糊在脸上（这一轮踩过）。

⚠️ 真机第一版"按键没反应"的三个修正（hands2）：

1. 手图图层改为**运行时动态创建** —— 老模型页没有 `#leftHand`，`getElementById` 为 null，永远画不出手；
2. 补 `standard` 的**单层 `hand/`**（Axon 对 standard 就是这么取的）；
3. 新增 `Live2DMverConfig`（纯解析，JVM 可测）+ `Live2DHandSpritesTest`：
   真样本验证键盘变体左右手、`KeyA` 绑定、标准单层 —— 3 个测试全过。

⚠️ **真机日志定位到的真根因（hands3）**：包里的 `config.json` 在
`<zip根>/A-卡提/config.json`，而导入器拿的是 zip 根 —— `loadPackageConfig()` 只找根目录，
所以 **手图、动作组合键、表情组合键的绑定全是空**（无头验证台当时能过，是因为 Python 脚本
自己向上找了 config）。已改为「根目录找不到就浅扫 3 层」，并加了嵌套目录的单测。

⚠️ **真机日志定位到的第二个坑（hands4）**：日志确认 `[hands] loaded {"left":31,"right":27,"hand":0}`
（键盘）/ `{"hand":25}`（标准），数据没问题；但键盘模式按键时手会"消失"。
原因是页面模板里的 `<img id="leftHand" class="hidden">` 带 `hidden` 类，
CSS `.hidden { display: none !important }` —— 内联 `style.display='block'` **压不过 `!important`**。
标准模式用的是运行时**动态创建**的 `handLayer`（没有这个类），所以只有它正常。
修法：`ensureHandLayer()` 里先 `classList.remove('hidden')`。

⚠️ 第一次运行（hands3/4）后老模型必须重新导入：页面骨架会自修复，但 `modeldata.js` 不会重生成，
没有 `__BONGO_HANDS` 就仍然没手。runtime 会打一行 `[hands] loaded {left,right,hand}` 方便排查。

### S6 手柄：按键已接入（2026-11-22）

| 环节 | 改动 |
|---|---|
| `Live2DSemantics` | 补 `BTN_*` → `GamepadA/B/X/Y/LB/RB/LT/RT/Back/Start/L3/R3/Dpad*`；不映射的话 overlay 会把事件当"不认识"丢掉 |
| `Live2DMverConfig.gamepadButtonName` | Mver 按钮编号表，**照 Axon 的 `mverGamepadName` 核对**：`0 A / 1 B / 2 X / 3 Y / 4 LB / 5 RB / 6 LT / 7 RT / 8 L3 / 9 R3 / 10 DpadLeft / 11 DpadRight / 12 DpadUp / 13 DpadDown / 14 Start / 15 Back` |
| 测试 | 手柄变体左右手各 6 条；断言 `GamepadA`、`GamepadLB`、`GamepadDpadLeft`、`GamepadDpadDown` |

⚠️ 第一版把 10/11 当成 Back/Start、12~15 当成 Dpad，真机表现就是"方向键左右没反应"。

**摇杆（2026-11-22 接入，第二版改为参数驱动）**：

| 环节 | 改动 |
|---|---|
| 服务 | `OverlayService` 订阅 `CaptureSession.sticks`，扇出给所有 Live2D 窗口（新窗口也补发当前值） |
| View | `updateSticks()` 按帧合并 → 一帧一条 `AxonBongoCat.gamepadAxes(lx,ly,rx,ry)` |
| runtime | **不画 PNG**：按 Axon `syncGamepadStickHandParameters` 的做法驱动模型参数 —— `CatParamStickLX/LY/RX/RY`（按参数 min/max 映射）、`CatParamStickShowLeftHand/RightHand`（摇杆幅度 > 0.04 或 L3/R3）、`CatParamStickLeftDown/RightDown` |

⚠️ 第一版用 `left_stick.png` 覆盖，位置和效果都不对 —— Mver 那两张图是**纯贴图模式**用的；
Live2D 模型自己的摇杆/手由上面那组参数控制。改参数驱动后**不需要重新导入**。

### 6.5 原生手柄 monitor 的 Switch Pro 左摇杆 bug（已修，2026-11-22）

**现象**：Switch Pro 左摇杆几乎不动（GAMEPAD 行里 `lx` 最大 ~48/1000 且回中后漂移），右摇杆正常；
手柄配置页同样无反应。**不是 Live2D 的问题**。

**根因**：`native/gamepadmonitor.cpp` 的 `isNonGamepadAnalogNode()` 黑名单只挡了
`gyro / accelerometer / sensor`，**没挡 `imu`**。Switch Pro 会多出一个
`"Nintendo Switch Pro Controller IMU"` 节点（event16），它也暴露 `ABS_X/Y`、名字里带
"Controller"，评分比"左半节点"更高 → 被当成左摇杆来源；但它给的是姿态数据（幅度极小、会漂）。

**修法**：黑名单加 `imu / motion / accelerat / gyroscope / compass / barometer`，
用 NDK 交叉编译替换 `app/src/main/jniLibs/arm64-v8a/libgamepadmonitor.so`（32,048 字节）。
源码已放入本仓库 `native/gamepadmonitor.cpp`。

⚠️ 这影响所有"手柄 + IMU 双节点"的设备（Switch Pro 类），单节点 XInput/DS4 不受影响。

### 6.6 桌面/键盘底图（2026-11-22）

**现象**：星野 standard 在软件里只有上半身，下面的键盘/桌面没有 —— 桌面端正常。

**根因**：Mver 的"键盘猫"里，桌面+键盘**不是模型的一部分**，而是一张背景图：
`standard/mousebg.png`（1600×1000）或 `tabletbg.png`，其它模式用 `bg.png`。
我们导入时没带它，所以只剩人物。

**修法**：`Live2DMverConfig.loadBackground()` 选图 → 写 `__BONGO_BACKGROUND`；
runtime 在 `IMPORTED_MODEL` 且没有底图时隐藏内置背景，有底图就铺满窗口（`object-fit: fill`）。

⚠️ 手部贴图在"画布 ≠ 1400²"的包（星野 standard 3840×2400 / keyboard 612×354）仍会错位，
下一步按 Mver 的 `window_size + l2d_correct/l2d_offset` 重做手图坐标映射。

### 6.7 模型摆放与物理抖动（2026-11-22）

**现象**：星野 standard 人物飘在桌子上方、和键盘有缝；头发"一抽一抽"。

**根因 1（摆放）**：Mver 会用 `decoration.l2d_correct`（缩放）和 `l2d_offset`
（相对窗口的平移）把模型摆进窗口，我们直接按画布居中，所以偏上。
⚠️ `l2d_offset` 的符号是 **屏幕向下为正**（`-0.145` = 往下压 14.5%），
写成 `+ ratio*height` 会让人物往天上跑（实测过）。公式：
`scale = contain * correct`，`offsetY = (h - canvasH*scale)/2 - ratioY*h`。

**根因 2（物理）**：物理按**每帧可变 dt** 积分，帧时间一抖就"抽"。
改成固定步长累加器（`1/60`，单帧最多 6 步，累积上限 250ms）。


### S3 姿态 / 混合：下一步









