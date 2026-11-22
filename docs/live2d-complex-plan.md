# Live2D 复杂模型「完全适配」方案

> 状态：**待确认**（先出方案，不动代码）
>
> 参考实现（只读）：`D:\Project\_axon\Axon-Input-main`（`bongocat/custom/runtime.js`，228 KB / 5008 行）
>
> 关联文档：`docs/live2d.md`（设计定稿）、`docs/live2d-fix-plan.md`（阶段 1–4 的修复记录）

---

## 0. 一句话结论

**导入的模型至今仍跑「内置键盘模型」的简化 runtime**（734 行，`physics` / `motion` / `expression` **各 0 处**），
它只会眨眼和让手上下动。所以复杂模型在我们这儿的表现必然是：**头发/尾巴不摆、Idle 动作不播、表情没有、物理全无**。

要"完全适配"，只有一条路：**把 Axon 的 `custom/runtime.js`（完整 Cubism 管线）搬进来**，
并把 `model3` 里声明的 `Physics` / `Pose` / `Motions` / `Expressions` / `DisplayInfo` **解析成它要的配置对象**。

真正的工作量不在"复制文件"，而在**构造那个配置对象**（§4 P2）与**输入/窗口的适配**（§4 P4）。

---

## 1. 现状盘点

### 1.1 已经做完的（不用重做）

| 能力 | 落地位置 | 状态 |
|---|---|---|
| 导入健壮性：深度优先 + 逐个校验 + 多变体 | `Model3Selection` / `Live2DModelImporter.chooseModel3` | ✅（对照 Axon `chooseModel3`） |
| 校验 `Version>=3`、`Physics/Pose/DisplayInfo` 声明即必须存在 | `validateModel3` | ✅ |
| 无用文件过滤（exe/dll/doc） | `Live2DJunkFilter` | ✅ |
| 贴图预算：按 2 的幂统一降采样 | `TextureBudget` / `Live2DTextureAdapter` | ✅ |
| 多模式（标准/键盘/手柄变体各一条库记录） | `Live2DModels` + importer | ✅ |
| 表情（`.exp3.json`）在**键盘 runtime** 里按组合键切 | importer `loadExpressions` + `runtime.js` | ✅（仅简化版） |
| 窗口按**模型内容比例**排布 | `onContentAspect` 回传 + `Live2DModels.updateContentAspect` | ✅ |
| Mver 包「按按键手消失」的临时规避 | importer `mverHands` 标记 | ✅（保守：不动手臂参数） |

> 阶段 3（贴图预算）与阶段 6 的一部分已在 `live2d-fix-plan.md` 记录；**阶段 4（VTubeStudio 待机动画）与阶段 5（换 runtime）仍未做**。

### 1.2 缺口（复杂模型"不行"的真正原因）

| # | 缺口 | 证据 |
|---|---|---|
| A | 导入模型用的是 `assets/bongocat/keyboard/runtime.js`（734 行） | `Live2DModelImporter.ASSET_RUNTIME_JS` |
| B | 该 runtime 无 physics / motion / expression | 关键字计数 0 / 0 / 0 |
| C | importer 只写 `moc(base64)` + 贴图路径 + **扁平化**表情，没有 physics/pose/motions/cdi | `writeModelData()` |
| D | 没有 `parameterDefinitions` / `keyParameterMap`，按键只能靠硬编码 `CatParam*` | 同上 |
| E | 大 moc 走 base64 内联，20 MB 的 moc 峰值约 47 MB | `live2d.md` §12 |

### 1.3 Axon 的 custom runtime 要什么（已逐项核对）

`window.__AXON_STYLE_CONFIG__`（我们改名 `__BONGO_STYLE_CONFIG__` 也行，但**建议保持 `__AXON_STYLE_CONFIG__` 以免再改上游**）：

| 字段 | 来源 | 我们现状 |
|---|---|---|
| `designWidth` / `designHeight` | 内置 612×354（可被 model3 `Layout` 覆盖） | 有常量，未读 Layout |
| `mode` / `format` / `mver` / `useLive2d` / `spriteMode` | Axon 用 `mver016 + useLive2d` 复用它的渲染路径 | 无 |
| `mocBase64` / `mocUri` / `mocVersion` | moc3 文件 | 只有 base64，无 version |
| `textures` | 贴图的 `file://` URI 数组 | 有相对路径，够用 |
| `physics` / `pose` | `physics3.json` / `pose3.json` 原文对象 | ❌ 没读 |
| `expressions` | `exp3.json` 原文数组，**必须保持索引与 FileReferences 对齐**（缺失填 `null`） | ❌ 只有扁平格式 |
| `motions` + `motionGroups` | `motion3.json` 原文 + 组名 → 索引表；`Sound` 一并带上 | ❌ 没读 |
| `eyeBlinkIds` / `lipSyncIds` | model3 `Groups` | ❌ 没读 |
| `parameterDefinitions` | `cdi3.json`（参数名、分组、默认触发、语义） | ❌ 没读 |
| `keyParameterMap` / `keyboardDownParameterIds` / `mouse*ParameterIds` | 由参数定义**推断** | ❌ 没读 |
| `leftKeys` / `rightKeys` / `leftKeyAssets` / `rightKeyAssets` | 自带键帽的包 | 有资源目录，未做映射 |
| `background` / `cover` / `layout` | 显示层 | 页面里是 `<img>` 自己画 |

> ⚠️ `custom/runtime.js` **不自带模型发现**：它只吃配置里的 base64/URI，不会自己去读 `model3.json`。
> 所以"谁解析 model3"这件事依然在我们（Kotlin）这边 —— 这正是 P2 的主要工作。

---

## 2. 目标与验收标准

### 2.1 目标

导入的 Live2D 模型（含 VTube Studio 复杂模型）在悬浮窗里**表现与 Axon 一致**：

1. 物理：头发 / 尾巴 / 饰品 / 衣摆按 physics3 摆动；
2. 动作：model3 的 `Motions.Idle`（及 Tap 等）能自动播放，带声音的按 Axon 的规则播放；
3. 表情：`Expressions` 可用（至少组合键/点击触发路径与 Axon 一致）；
4. 基础动画：眨眼、呼吸、视线/头部跟随鼠标；
5. 渲染正确性：多贴图、遮罩（mask）、叠加模式、draw order 不出错；
6. 窗口比例与模型内容一致（不露大片透明）；
7. 内存/帧率可接受：不白屏、不黑屏、不 OOM。

### 2.2 明确不做（本轮）

- 原生 Cubism SDK（NDK/.so）——本项目无法编译验证；
- 让**任意**第三方模型都响应键盘（没有 `CatParam*` / 没有可推断参数就是没有，**给提示而不是假装支持**）；
- 触屏 / 手柄手部手势（`live2d.md` §1 已定不做）。

### 2.3 内置两个模型保持不变

`builtin:keyboard` / `builtin:standard` 继续走 `assets/bongocat/{keyboard,standard}/runtime.js`。
**只把"导入模型"切到 custom runtime**，避免把已经稳定的东西一起搅。

---

## 3. 方案选型

| 方案 | 做法 | 工作量 | 风险 | 结论 |
|---|---|---|---|---|
| **A. 移植 Axon 的 custom runtime** | 复制 228 KB JS + 补齐配置解析 | 中（主要 P2/P4） | 许可、配置字段遗漏 | ✅ **推荐** |
| B. 在现有 734 行 runtime 上补 physics/motion | 自己实现 Cubism 物理/动作/表情 | 大（≥3× A，且要长期对齐） | 高，容易半吊子 | ❌ |
| C. 原生 Cubism SDK（C++/JNI） | 引 `live2dcubismcore` + 自写 renderer | 很大 | 本项目无法编译/验证原生部分 | ❌ |

**A 的关键论据**：Axon 的 custom runtime 与我们的键盘 runtime **同源、全局名一致**（`window.AxonBongoCat`），
桥接方法（`key` / `mouseButtons` / `mouseDelta` / `clear` / `isReady`）**一个不缺**（已逐个核对），
所以 Kotlin 侧几乎不用改输入链路 —— 换页面 + 换配置即可。

---

## 4. 实施步骤（每步都能单独编译、单独验证）

### P1 资产与页面骨架（小）

| 改动 | 内容 |
|---|---|
| 1.1 | 复制 `Axon-Input-main/app/src/main/assets/bongocat/custom/runtime.js` → `assets/bongocat/custom/runtime.js`（**先原样**） |
| 1.2 | 新增 `assets/bongocat/custom/NOTICE.txt`：写明来源（BongoCat / Axon）、许可证与修改点 |
| 1.3 | 新增 `Live2DCustomPage.kt`（或并入 importer）：生成 `index.html`，按 `core → modeldata.js → runtime.js` 顺序引脚本（与键盘页一致，**不内联**，避免 228 KB 进 HTML） |
| 1.4 | `modeldata.js` 内容改为 `window.__AXON_STYLE_CONFIG__ = {...}`（一个 JSON） |
| 1.5 | **小补丁**：custom runtime 在模型就绪后回传内容比例（`window.SthKeyBridge.onContentAspect(aspect)`），用 `renderer.canvasInfo.CanvasWidth/Height`；同时把补丁写进 NOTICE |

**验证**：临时把某个导入模型指向 custom 页，只给 `mocBase64 + textures + mocVersion`，
应该能画出**静态模型**（先不追求物理/动作）。画面出来即 P1 通过。

### P2 model3 → 配置解析（大，核心）

新增 Kotlin 解析器（建议 `data/live2d/Live2DModelConfig.kt`，纯函数、可单测）：

| 输出 | 读法（照 Axon `appendLive2dConfig`） |
|---|---|
| `mocBase64` / `mocVersion` | moc3 前 4 字节是版本（照 Axon `readMocFormatVersion`）；大 moc 另给 `mocUri` |
| `textures` | `Uri.fromFile().toString()`，顺序与 model3 一致 |
| `expressions` | 每个 `exp3.json` **原文解析**；缺失/非法填 `JSONObject.NULL`，**保持索引对齐** |
| `motions` / `motionGroups` | 按组解析 `motion3.json` → `{name,json,sound}`；组名 → 索引数组；缺失填 `-1` |
| `physics` / `pose` | `physics3.json` / `pose3.json` 原文 |
| `eyeBlinkIds` / `lipSyncIds` | model3 `Groups` 里对应组的 `Ids` |
| `parameterDefinitions` | `cdi3.json` 的 `Parameters` + `ParameterGroups`；没有 cdi3 时从 `Groups` 兜底 |
| `keyParameterMap` 等 | 照 Axon `inferKeySemantic` / `classifyParameter` 的规则推断（**先照抄，不自己发明**） |
| `designWidth/Height` | model3 `Layout` 或 612×354 |

**预算**：Axon 在 display 模式下有 `expression/motion/aux` 三个预算（`RuntimeMetadataBudget`），
超过就填 `null`/`-1`。我们要照做，否则一个 VTube 包的几百 MB JSON 会先把内存吃光。

**验证**：单测（解析结果字段级断言）+ 用 2–3 个复杂模型在真机上跑：物理动、Idle 播、表情能切。

### P3 页面生成、切换与老模型迁移（中）

| 改动 | 内容 |
|---|---|
| 3.1 | `writeModelData()` 改为写 style config（保留 `ENTRY_MODEL_DATA` 文件名，减少改动面） |
| 3.2 | `buildHtml()` 改为引用 `custom/runtime.js`；`layout` 只影响 `leftKeys/rightKeys` 等字段，不再影响引哪个 runtime |
| 3.3 | 页面里加**版本标记**（如 `<!-- sthkey-page:2 -->`）；`repairImportedPages()` 检测到旧标记就重写，**老模型升级后自动切换** |
| 3.4 | 导入报告里注明「已启用完整渲染（物理/动作/表情）」；解析失败的项目逐条列出（不静默） |

**验证**：升级安装后，之前导入的复杂模型**不用重新导入**就能动；内置两模型行为与升级前一致。

### P4 输入语义与窗口比例（中）

| 点 | 现状 | 要做 |
|---|---|---|
| 按键 | Kotlin 推 `key(语义名, pressed)` | custom runtime 用 `KEY_PARAMETER_MAP` 推断；**语义名表必须与它一致**（`KeyA`、`Space`…），逐个核对 |
| 鼠标 | `mouseDelta` / `mouseButtons` | API 同名同义，直接可用 |
| 左右镜像 | `setGlobalReverse` | 同名，可用 |
| 窗口比例 | 依赖 `onContentAspect` | P1.5 的补丁 + 首次 `isReady` 后立即报一次 |
| 清空/补发 | `clear()` + 重放 pressed | 同名，可直接用 |

⚠️ 复杂模型大多**没有** `CatParamLeftHandDown` 之类的参数 → 按键不会让手动画。
这是"模型本身没有这个通道"，不是 bug。导入报告里要明确写：
「该模型没有可识别的按键参数，只有视线/物理/动作」。

### P5 全键帽包与 Mver PNG 手（可后置）

- 自带 `resources/left-keys` / `right-keys` 的包：构造 `leftKeys` / `rightKeys` / `leftKeyAssets` / `rightKeyAssets`，
  让 custom runtime 画键帽（它原生支持，见 `LEFT_KEY_ASSETS`）；
- Mver 包的 PNG 手：custom runtime 有完整的 hand compositor（`mverHandSprites` 等）；
  但我们的导入器目前**不解析** Mver `config.json` 的绑定 —— 这一块建议**单独一轮**，不要和"复杂模型渲染"混在一起。

### P6 性能与内存（中）

| 项 | 做法 |
|---|---|
| moc 加载 | 小文件 base64（兼容老 WebView），大文件走 `mocUri` 流式（Axon 同款策略） |
| 元数据 | 三个预算 + 只保留 Idle 组优先 |
| 帧率 | custom runtime 自带 `physicsActiveFps/physicsIdleFps` 与 renderQuality；暴露到设置页可调 |
| 贴图 | 已有 2 的幂降采样，保留 |

### P7 验证矩阵（每步都跑）

| 场景 | 期望 |
|---|---|
| 内置 keyboard / standard | 与升级前逐像素级一致（至少手感一致） |
| 简单导入模型（只有 moc+贴图） | 正常显示，不崩 |
| 复杂模型（物理+动作+表情） | 头发/尾巴动、Idle 播、表情可用 |
| 多贴图（≥8 张）/ 4096² 图集 | 不白屏、不 OOM（贴图预算生效） |
| VTube 包 | 有 Idle 或至少不"一动不动"（阶段 4 补 vtube idle 注入） |
| 4K 屏 / 低端机 | 帧率与内存可接受 |
| 断网 / 飞行模式 | 全程不联网（`blockNetworkLoads=true` 不变） |

---

## 5. 关键契约（写下来防止以后改坏）

```text
/ 顺序不能变 /
<script src="file:///android_asset/bongocat/live2dcubismcore.min.js"></script>
<script src="./modeldata.js"></script>        <!-- window.__AXON_STYLE_CONFIG__ -->
<script src="file:///android_asset/bongocat/custom/runtime.js"></script>
```

- `modeldata.js` 必须**同步**赋值（runtime 在脚本执行时就读 `CONFIG`，没有回调）；
- `window.AxonBongoCat` 的 API 是稳定契约：`key/mouseButtons/mouseDelta/setGlobalReverse/clear/isReady/debugState`；
- `window.SthKeyBridge.onContentAspect(aspect)` 只有一个参数、必须是正有限数；
- 内置页面**不得**引用 custom runtime；导入页面**不得**引用 keyboard runtime。

---

## 6. 风险与对策

| 风险 | 说明 | 对策 |
|---|---|---|
| **许可** | Axon 仓库**没有 LICENSE 文件**，只有 `BONGOCAT_THIRD_PARTY_NOTICE.txt`（BongoCat = MIT）。`custom/runtime.js` 是 Axon 在其上做了大量修改的产物 | **需要拍板**（见 §7）。可选：加 NOTICE 注明来源与修改 / 向作者取得许可 / 仅自用不分发 |
| WebView `file://` 读取 | `allowFileAccessFromFileURLs=true` 已开；但 XHR 读取二进制在老版本可能被拒 | moc 默认 base64；大文件再试 `mocUri`，失败回退 base64 |
| 内存峰值 | base64 1.33×；元数据可能几十 MB | 三个预算；大 moc 流式；拒绝超大 moc 时给明确报错 |
| 表情索引错位 | 跳过坏文件会导致"按 A 出 B 的脸" | 缺一个就填 `null`，**绝不压缩数组** |
| 窗口比例 | custom runtime 不主动上报 | P1.5 补丁 + 首帧后再报一次（模型布局可能延迟计算） |
| 按键无反应 | 第三方模型没有 `CatParam*` | 导入报告写明；UI 上不承诺 |
| 与内置 runtime 混淆 | 两套页面两套契约 | 页面里写死标记 + `repairImportedPages` 校验 |

---

## 7. 需要你拍板

1. **许可路线**（最关键）
   - A：直接移植，附件 NOTICE 注明「runtime 来自 Axon-Input / BongoCat，MIT 部分保留版权」——**快，但有版权灰区**；
   - B：先向 Axon 作者取得许可，再移植——**干净，但要等**；
   - C：不移植，继续在 734 行 runtime 上补——**不推荐，等于半吊子**。
2. **本轮范围**：只做「仅角色」的复杂模型（多数 VTube 包），还是连「自带键帽资源包」一起做？
   （建议：先 1，P5 单独一轮）
3. **验证样本**：能不能给 2–3 个**在同期版本上出问题的复杂模型**（zip/网盘都行）？
   没有样本的话，P2 只能按 Axon 源码推理，回归风险会高很多。
4. 是否接受 APK 体积 +约 230 KB（JS 压缩前）/ +约 60 KB（APK 内压缩后）。

---

## 8. 预估

| 阶段 | 相对工作量 | 阻塞点 |
|---|---|---|
| P1 页面骨架 | 小（1 天级） | 无 |
| P2 配置解析 | **大（主要工作量）** | 字段多，需要真模型验证 |
| P3 切换/迁移 | 中 | 老模型页面重写 |
| P4 输入/窗口 | 中 | 语义名逐项核对 |
| P5 键帽/Mver | 可后置 | 依赖 P2 |
| P6 性能 | 中 | 需要真机内存曲线 |
| P7 验证 | 贯穿 | 需要样本 |

> 建议节奏：**P1 → P2（先只做 physics + idle motion，最见效）→ P3 → 用真模型跑一轮 →
> 再补 expression/参数定义 → P4/P6**。不要一次全上，否则出问题时无法定位是哪一层。

---

## 9. 实测样本（2026-11-22，用户提供）

### 9.1 `鸣潮卡提希娅最牛逼键盘猫模型.zip` —— 复杂模型的验收样本

| 项 | 值 |
|---|---|
| 性质 | Mver（Windows 版 BongoCat）发行包，`config.json` 分 `keyboard/standard/gamepad` 三段 |
| 体积 | 276 文件 / 124.9 MB |
| model3 | 4 个：`img/cat.model3.json`（**孤儿**，无 Moc/贴图）+ 三套完整变体（各 depth 4） |
| 贴图 | `cat.8192/texture_00.png` **8192×8192**、`texture_01.png` **8192×4096**（合计 100 M 像素） |
| 其他 | moc3 3.17 MB、physics3 21 KB、cdi3 12 KB、19 个 motion3、4 个 exp3 |
| 预期 | 能导入（多变体：keyboard / standard / gamepad）；贴图按预算降到 1/2；**现在的 runtime 不会出物理/动作/表情** |

> ⚠️ 8192² 的 PNG 有 23 MB，解码后 268 MB —— 导入时**必须**先按预算降采样，
> 否则 WebView 侧 `MAX_TEXTURE_SIZE`（常见 4096）直接白屏。

### 9.2 `星野桌宠猫猫2K.zip` —— 「导入都导入不了」的根因

| 项 | 值 |
|---|---|
| 结构 | 压缩包根目录**多套了一层**：`星野桌宠猫猫2K/2K/img/<变体>/cat_model/cat.model3.json` |
| model3 深度 | 相对解压根 **depth = 5**（`星野桌宠猫猫2K/2K/img/keyboard/cat_model/…`） |
| 三个变体 | 全部有效：`Version=3`、Moc/Textures/Physics/DisplayInfo 都在（已逐文件核对） |
| 贴图 | 2048×2048，不需要降采样 |

**根因（已用 kotlinc 在真样本上实测）**：

```text
walkTopDown().maxDepth(5)  → 找到 0 个 model3
walkTopDown().maxDepth(10) → 找到 3 个 model3
```

Kotlin 的 `maxDepth(5)` 在「解压根 + 包名目录 + 风格目录 + img + 变体 + cat_model」这种结构上
**一个都扫不到**，于是 `Model3Selection` 返回 `NoModel3`，用户看到的是
「这个压缩包里没有 .model3.json」——**文案还是误导的**（包里有，只是深了一层）。

> 这也解释了为什么同一类包在 Axon 上能导入：Axon 的 5 层是相对它的 style root，
> 而我们的 root 是**整个解压目录**，天然多一层。

**已修（P0，2026-11-22）**：

| 文件 | 改动 |
|---|---|
| `domain/live2d/Model3Selection.kt` | `MAX_DEPTH` 5 → **10**；注释写清样本与原因 |
| `data/live2d/Live2DModelImporter.kt` | `NoModel3` 的报错补上「已查 N 层目录」，不再误导 |
| `test/.../Model3SelectionTest.kt` | 断言从「等于 5」改为「≥ 10」，防止以后被收回去 |

验证：827 用例全过；`maxDepth(10)` 在真样本上找到 3 个候选（见上）。

### 9.3 测试环境（虚拟机）

本机已具备：

| 项 | 状态 |
|---|---|
| Android Emulator | ✅ `emulator.exe` 存在，`-accel-check`：**WHPX 可用** |
| AVD | ✅ `Medium_Phone_API_37.0`（Android 17 / 1080×2400 / x86_64） |
| adb | ✅ 37.0.3 |
| 实测 | ✅ headless 启动成功；v2.8.4 安装并启动（截图见会话记录） |

⚠️ 局限：无 GPU（走 SwiftShader），**不能代表真机帧率**；应用内的导入流程是 SAF 文件选择器，
自动化点击较繁琐。所以验证策略定为：

1. **JS 渲染层**：桌面 Chrome headless + 生成的配置页面，直接截图验证物理/动作/表情（快）；
2. **Android 层**：emulator + `adb` 安装、启动、`uiautomator` 驱动关键路径（慢，但能验 SAF/权限/WebView）。

### 9.4 本地渲染验证台（已落地，2026-11-22）

工具：`tools/live2d-harness/build.py`（说明见同目录 `README.md`）。做法是把任意 model3 目录
+ Axon 的 `custom/runtime.js` 拼成一个自包含 HTML，用 Chrome 无头渲染并抓 `debugState()`。

**两个真样本都跑通了**：

| 模型 | ready | 参数 | physics | motions | expressions | 贴图处理 |
|---|---|---|---|---|---|---|
| 星野 2K（standard） | ✅ | 41 | 1 | 0 | 8 | 2048²，不降采样 |
| 卡提希娅（keyboard） | ✅ | 102 | 13 | 19 | 4 | **100.7M → 25M 像素（1/2）** |

动画确认：同一页面在 `virtual-time-budget=900` 与 `7000` 各截一帧，模型区域
**24195 个像素**不同 → 呼吸/物理/眨眼确实在动，不是静态图。

截图（本机 `_samples`，不进仓库）：

- `D:\Project\_samples\live2d\harness\kati_keyboard\shot.png`
- `D:\Project\_samples\live2d\harness\star_standard\shot.png`

⭐ 这一步的意义：**P2 的配置解析不需要每次都装包验证**，只要 Chrome 里渲染正确就说明字段对了。
后面 P1 挪 runtime 进 assets、P2 写解析器时，可以先用这个台子对 3 套变体反复回归。

