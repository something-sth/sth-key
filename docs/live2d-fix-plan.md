# Live2D 适配修复方案

> 状态:**待确认**（先出方案。参考实现= `D:\Project\_axon\Axon-Input-main`，只读）
>
> 用户的原话:
> > 我朋友主要玩的就是 live2d 配置，但是他反应我的 live2d bug 太多了，
> > 复杂一点的模型就有很多 bug，反观 axon 的，则几乎完美适配……
> > 希望多多参考 axon，它的 live2d 是非常值得称赞的

---

## 1. 结论先行：三条根因

| # | 根因 | 证据 |
|---|---|---|
| **A** | 导入的模型用的是**内置键盘模型的 runtime**（为 BongoCat 内置模型手写的简化版），它**根本不支持** physics / motion / expression | 我们的 `assets/bongocat/keyboard/runtime.js` 只有 **734 行**，`physics` 0 处、`motion` 0 处、`expression` 0 处 |
| **B** | 找 `*.model3.json` 只取"遍历到的**第一个**"，不比较目录深度、也不逐个校验资源完整性 | `Live2DModelImporter.kt:187` `dir.walkTopDown().firstOrNull { … }` |
| **C** | 压缩包**全量解压**、导出时**整个目录**打包 —— exe/dll/doc 之类全进库、还会随配置导出 | 解压：`extract()` 无类型过滤；导出：`ConfigPackageManager.kt:591` `model.dir.walkTopDown()` |
| **D** | 没有纹理体积预算：大图集（11×4096² ≈ 700MB 解码后）在手机上必然炸 | 我们完全没有这一步；Axon 有 `adaptLargeTextures`（`Live2DModelStore.java:426`） |

### 1.1 A 是最致命的一条（用户说的"复杂模型 bug 多"就是它）

Axon 把两条路**分得很清楚**:

| 用途 | Axon 的 runtime | 我们 |
|---|---|---|
| 内置 BongoCat 模型 | `bongocat/keyboard/runtime.js`（24353 字节） | 同左（26371 字节，同源） |
| **用户导入的模型** | **`bongocat/custom/runtime.js`（228184 字节 / 5008 行）** | ❌ **没有**，导入的模型也走 keyboard 那份 |

能力对照（同一批关键字在文件里出现的次数）:

| 能力 | 我们的 keyboard/runtime.js | Axon custom/runtime.js |
|---|---|---|
| physics（物理摆动、头发/尾巴） | **0** | 108 |
| motion（动作/待机动画） | **0** | 169 |
| expression（表情） | **0** | 103 |
| EyeBlink（眨眼） | 10 | 19 |
| Drawable（部件绘制控制） | 16 | 56 |

⭐ 所以「复杂模型」在 Axon 上"几乎完美"、在我们这儿"很多 bug"，
**不是调参问题，是那个 runtime 压根没实现这些能力**。

⚠️ 但有个好消息:我们的 `runtime.js` 本来就是从 Axon 的键盘版派生的，
而且**暴露的全局名一模一样**（`window.AxonBongoCat`，我们 `runtime.js:230`，Axon `custom/runtime.js:2589`），
方法名也一致（`key` / `mouseButtons` / `gamepadButton` …）。
所以移植 = **换文件 + 补齐它要的配置对象**，不是重写一套桥接。

---

## 2. 逐项对照 Axon 的实现

### 2.1 找 model3：Axon 是"深度优先 + 逐个校验"

```java
// Live2DModelStore.java:517
private static File chooseModel3(File root) throws IOException {
    List<File> models = findFilesBySuffix(root, ".model3.json", 5);   // 最多 5 层
    models.sort(comparingInt(f -> pathDepth(root, f))                  // 浅的优先
            .thenComparing(File::getName, CASE_INSENSITIVE_ORDER));
    for (File candidate : models) {
        try { validateModel3(root, candidate, readJson(candidate)); return candidate; }
        catch (Exception ignored) {}                                   // 校验不过就看下一个
    }
    throw new IOException("找到 model3，但模型资源不完整");
}
```

而我们:

```kotlin
// Live2DModelImporter.kt:187
val modelJsonFile = dir.walkTopDown()
    .firstOrNull { it.isFile && it.name.endsWith(".model3.json", true) }
```

⚠️ 三个后果:
1. **一级目录不是模型目录**（BongoCat PC 包常见）时，遍历顺序不保证，
   可能先命中一个残缺的/无关的 `model3.json` → 直接报错；
2. 一个包里带**多套模型**（换装/多种形态）时，选哪个是随机的；
3. 报错文案是"没有 model3"，而实际是"有但资源不全"，用户被误导。

### 2.2 校验：Axon 还检查 version 与可选引用

```java
// Live2DModelStore.java:340
int version = model.optInt("Version", 0);
if (version < 3) throw new IOException("仅支持 Cubism 3/4/5 model3 模型");
… Moc 必须存在；Textures 逐个必须存在；
validateOptionalReference(…, "Physics");   // 声明了就必须在
validateOptionalReference(…, "Pose");
validateOptionalReference(…, "DisplayInfo");
```

我们只验 `Moc` 与 `Textures`。
⚠️ `Physics` 声明了但文件缺失时，Axon 的 runtime 会报错；我们的现在会**静默忽略**，
但那也是"这个模型不对"的来源之一。

### 2.3 纹理预算：Axon 会统一降采样

```java
// Live2DModelStore.java:464
// A model with eleven 4096² atlases occupies about 704 MiB after RGBA decode.
// Downsample the whole atlas set uniformly by powers of two so UV coordinates remain valid…
long texturePixelBudget = clearQuality ? 80M : 48M;   // 像素预算
while (totalPixels / (sample * sample) > budget) sample *= 2;
```

⚠️ 关键在**统一按 2 的幂降采样** —— UV 坐标不变，所以不需要改 model3；
等比缩放任意倍数才会破坏 UV。

### 2.4 VTube Studio 包的待机动画

```java
// Live2DModelStore.java:383 injectVTubeStudioIdleIfPresent
// VTubeStudio packages may keep their idle motion only in *.vtube.json instead of model3.
```

⚠️ 这类包在 model3 里**没有** `Motions.Idle`，模型会一动不动 —— 看起来像"导入坏了"。

### 2.5 无用的 Windows 程序文件（用户明确提的第 3 条）

Axon 也没做过滤（它也是全量解压），所以这条**没有参考实现，要自己定规则**。
我们的导出确实会把它们带出去:`ConfigPackageManager.kt:591` 对整个模型目录 `walkTopDown()`。

---

## 3. 分阶段方案

> ⚠️ 每阶段都必须能单独编译、单独验证。建议按顺序做，A 之前的部分都是小改动。

### 阶段 1：导入健壮性（小，先做）

| 改动 | 内容 |
|---|---|
| 1.1 | 找 model3 改成 **Axon 那套**:深度上限 5、按深度排序、**逐个校验**、都不通过时报"有 model3 但资源不完整" |
| 1.2 | 校验补 `Version >= 3` 与 `Physics` / `Pose` / `DisplayInfo` 的存在性 |
| 1.3 | 支持包内**多套模型**:候选里有多套时，优先取**资源最完整**的那套；日志记下选了哪个 |
| 1.4 | 报错文案带上"找到几套 model3、各自缺什么"，让用户能贴给我们 |

### 阶段 2：无用文件过滤（小）

| 改动 | 内容 |
|---|---|
| 2.1 | **导入时**丢弃明显无关的文件（黑名单，见 §4 的清单），并在结果里报"已忽略 N 个无关文件" |
| 2.2 | **导出时**再过滤一次（老模型库里已经有的 junk 不再带出去） |
| 2.3 | 提供一次性的「清理模型库」入口（设置 → 库 → Live2D 库），⚠️ **不自动动用户数据** |
| 2.4 | 摘要（digest）计算时也排除它们，避免"同一个模型因为带了 exe 而摘要不同" |

### 阶段 3：纹理预算（中）

| 改动 | 内容 |
|---|---|
| 3.1 | 移植 `adaptLargeTextures`：先读所有纹理的尺寸，算总像素；超预算就**统一按 2 的幂**降采样 |
| 3.2 | 预算值先按 Axon 的 48M 像素（约等于 4096²×3）起步，按真机结果调 |
| 3.3 | 降采样后**重新校验**一次（Axon 也是这么做的，见 `:289`） |
| 3.4 | 结果里报"已把贴图降到 X% 以适配手机" |

### 阶段 4：VTubeStudio 待机动画（小）

移植 `injectVTubeStudioIdleIfPresent`:model3 没有 `Motions.Idle` 时，
从同目录的 `*.vtube.json` 里找 `IdleAnimation`，写回 model3。

### 阶段 5：**换 runtime（大，但这是"复杂模型能用"的唯一办法）**

| # | 要做的事 | 说明 |
|---|---|---|
| 5.1 | 复制 `Axon-Input-main/app/src/main/assets/bongocat/custom/runtime.js` 到 `assets/bongocat/custom/runtime.js` | 228 KB，**原样复制，先不改** |
| 5.2 | 我们现有的 `buildHtml()` 改成加载 custom runtime（只对**导入的模型**；内置的 BongoCat 继续用 keyboard 那份） | 内置模型别动，它现在是对的 |
| 5.3 | 构造它要的配置对象（Axon 叫 `window.__AXON_STYLE_CONFIG__`，我们叫 `window.__BONGO_STYLE_CONFIG__`） | ⚠️ **这是本阶段真正的工作量**：需要逐字段核对 runtime 里 `CONFIG.xxx` 的用法（designWidth/Height、mode、leftKeys/rightKeys、keycap 路径、physicsControls、renderQuality、textureEstimatedRgbaBytes…） |
| 5.4 | 桥接：`window.AxonBongoCat.key/mouseButtons/gamepadButton/gamepadState/gamepadAxes/…` | 名字已经一致，主要是确认**参数语义**（例如 `key` 的事件是 `keydown` 还是 `pressed` 布尔） |
| 5.5 | 保留我们自己的部分：按键语义映射（evdev → `CatParam*`）、多窗口、CPS | 这些在 Kotlin 侧，不受影响 |
| 5.6 | 老模型（已导入的）也要切换到新 runtime | 它们是按旧模板生成的 `index.html`；`repairImportedPages()` 已经有"页面内容变了就重写"的机制，可以复用 |

⚠️ **5.3 是风险最大的一步**:Axon 的 runtime 与它自己的模型库产物（`mver` 格式、
键帽资源布局、`modeldata.js` 结构）耦合。我会逐项核对后再动手，
不会"先跑起来再到处打补丁"——那样最容易变成新的半吊子。

---

## 4. 无用文件过滤：黑名单清单（待确认）

我倾向**黑名单**而不是白名单:白名单会误删我们还不认识但模型要用的资源
（例如作者自带的字体、贴图变体、自定义 js）。

删除（不区分大小写）:

```
exe dll bat cmd ps1 vbs msi sys scr com pif
zip rar 7z tar gz           ← 嵌套压缩包（解压出来也没用）
lnk url torrent
txt md pdf doc docx xls ppt  ← 说明文档（可选，见下面问题 3）
```

保留一切其它文件（含 `*.json`、`*.png`、`*.moc3`、`*.wav`、`*.mp3`、未知后缀）。

⚠️ 两个细节:
- **`.exe`/`.dll` 的同名资源不能误删** —— 黑名单只看**后缀**，不看名字；
- 过滤后要保证 `Moc` / `Textures` / `Physics` 等**被 model3 引用的文件一个都没删**
  （先算引用集合，再删黑名单里的非引用文件）。

---

## 5. 需要你确认

| # | 问题 | 我的建议 |
|---|---|---|
| 1 | **能不能给我一个出问题的复杂模型？**（一个就好） | ⭐ 强烈建议。我可以在本机用 Axon 的 runtime 对照跑，确认"是 runtime 能力缺失"还是"还有别的坑"。**没有复现样本，阶段 5 只能盲改** |
| 2 | 阶段 5 是否按"整份移植 Axon 的 custom runtime"来做 | **是**。补个最小 physics/motion 实现只会变成新的半吊子 |
| 3 | 黑名单里要不要连 `txt/md/pdf/doc` 一起删 | 建议**删**（说明文档留着没意义，还占体积）；你要是想保留作者说明就说不删 |
| 4 | 已有模型库里的 junk 要不要自动清 | **不自动**。加一个手动「清理模型库」按钮 —— 自动删用户数据风险太大 |
| 5 | 阶段顺序 | 1 → 2 → 3 → 4 → 5。前四步都能立刻改善，阶段 5 单独一轮做 |

---

## 6. 验证清单（每阶段都要过）

### 阶段 1
- [ ] BongoCat PC 包（一级目录不是模型目录）能导入
- [ ] 带两套模型的包:导入后能选中**完整的那一套**
- [ ] 残缺包（缺贴图）报"资源不完整"而不是"没有 model3"

### 阶段 2
- [ ] 带 exe/dll 的包导入后，模型库里**看不到**这些文件
- [ ] 导出配置后再解压 `.sthkey`，`assets/live2d/` 里没有 exe/dll
- [ ] 同一个模型重复导入仍能命中"已在库里"（摘要未因过滤而漂移）

### 阶段 3
- [ ] 大图集模型（≥ 4 张 4096²）能正常显示，不白屏、不 OOM
- [ ] 降采样后 UV 不错位（贴图不串、不花）
- [ ] 普通模型（1~2 张图集）**分辨率不变**

### 阶段 4
- [ ] VTube Studio 包导入后**有待机动画**

### 阶段 5
- [ ] 复杂模型:物理摆动、待机动作、表情、眨眼都动
- [ ] 内置 BongoCat 模型（keyboard / standard）**行为不变**
- [ ] 键鼠/手柄输入驱动的表情与动作正确
- [ ] 多窗口同时开两个导入模型不互相干扰

---

## 7. 实证：用户提供的出问题模型

样本:`鸣潮卡提希娅最牛逼键盘猫模型(1).zip`（约 105 MB，301 个条目，解压 121 MB）

### 7.1 包结构（是个 Windows 版 BongoCat 发行包）

```
按键表情使用说明.docx                    ← 无关文件
A-卡提/
├── Bongo Cat Mver.exe / BongoCatUI.exe              ← 无关（Windows 程序）
├── 13 个 .dll（SFML / WPF / VC 运行时）              ← 无关，14.8 MB
├── 3 个 .flac（音效）                                ← 我们用不到，5 MB
├── 1 个 .psd                                         ← 无关，1.4 MB
└── img/
    ├── cat.model3.json          ⚠️ 孤儿：引用的 cat.moc3 / cat.8192/* 在同一层**都不存在**
    ├── keyboard/                 ← 完整变体（112 项：bg/cat/face/lefthand/righthand/keyboard + cat_model/）
    ├── standard/                 ← 另一套完整变体（91 项）
    └── gamepad/                  ← 第三套完整变体（75 项）
```

每套变体的 `cat_model/` 里有:`cat.moc3`、`cat.physics3.json`、`cat.cdi3.json`、
4 个 `expression*.exp3.json`、`CAT_motion_lock/` 下 19 个 `.motion3.json`、两张贴图。

### 7.2 四个问题**全部**踩中

| 问题 | 这个模型的实际情况 | 我们的表现 | 对应阶段 |
|---|---|---|---|
| **B** 选错 model3 | 最浅的那个 `img/cat.model3.json`（深度 2）是**孤儿**；三套真模型都在深度 4 | `walkTopDown().firstOrNull` **可能先命中孤儿** → 报"缺少主体文件（Moc）"→ **导入失败**（"有时候"就是遍历顺序不同） | 阶段 1 |
| **B'** 变体选错 | 一个包里有 keyboard / standard / gamepad **三套**，键帽资源各不相同 | 即使没命中孤儿，也会**随机**取一套 → 布局与键帽不匹配 | 阶段 1 |
| **D** 贴图超大 | `texture_00.png` = **8192×8192**，`texture_01.png` = **8192×4096** → 单套 96 M 像素 ≈ **384 MB** 解码 | 超过很多机型的 `GL_MAX_TEXTURE_SIZE`(4096) → 纹理上传失败 / OOM → **白屏、缺部件** | 阶段 3 |
| **A** runtime 能力缺失 | 卖点就是**按键表情**:4 个 expression + 19 个 motion + physics | 我们的 keyboard runtime physics/motion/expression **全为 0** → 表情与动作**一动不动** | 阶段 5 |
| **C** 无关文件 | 2 exe + 13 dll + docx + psd + flac ≈ 24 MB | 全进模型库，导出配置时**一起打包带出去** | 阶段 2 |

### 7.3 按 Axon 规则，这个模型会被怎么处理

| 步骤 | Axon 的做法 | 结果 |
|---|---|---|
| 找 model3 | 深度 ≤5、按深度排序、逐个 `validateModel3` | 孤儿（深度 2）校验失败被跳过，最终选中三套完整模型之一 |
| 变体 | 我们还需要补一条:识别 `keyboard` / `standard` / `gamepad` 变体名 | 选 `keyboard`（键盘模式主变体） |
| 贴图 | 96 M 像素 > 48 M 预算 → 统一 2 的幂降采样（`sample = 2`） | 8192² → 4096²、8192×4096 → 4096×2048 = **24 M 像素 ≈ 96 MB**，落在手机上可承受的范围 |
| 无关文件 | Axon 不做（它自己也全量） | 我们按 §4 黑名单丢掉 24 MB |
| 渲染 | custom runtime 支持 physics / motion / expression | 按键表情与待机动作**能动** |

### 7.4 因此本模型的验收标准

- [ ] **能导入**（不再报"缺少主体文件"）
- [ ] 导入结果提示:包含 3 套变体、已选 keyboard 版本；已忽略 N 个无关文件；贴图已降到 50%
- [ ] 悬浮窗里模型**完整显示**，不白屏、不缺部件
- [ ] 按键时**表情切换**（4 个表情:正常眼 / 哭泣眼 / 眯眯眼 / 混乱眼）
- [ ] 有待机动作（`CAT_motion_lock` 里的 19 个 motion）
- [ ] 物理摆动正常（头发/身体）
- [ ] 导出配置后解包:`assets/live2d/` 里**没有** exe/dll/docx/psd/flac
- [ ] 导出包体积明显变小（贴图降采样 + 过滤，从 ~97 MB 降到 ~30 MB 量级）
---

## 8. 实施记录

### 8.1 阶段 1 + 2 已完成（2026-10-31）

| 改动 | 落在哪 |
|---|---|
| `Model3Selection`（候选排序与决策，**纯函数**） | `domain/live2d/Model3Selection.kt`（新） |
| `Live2DJunkFilter`（无关文件黑名单，**纯函数**） | `domain/live2d/Live2DJunkFilter.kt`（新） |
| 导入器改用"深度排序 + 逐个校验 + 变体优先" | `Live2DModelImporter.chooseModel3 / validateModel3` |
| 解压时**直接跳过**无关文件（不落盘） | `Live2DModelImporter.extract` → 返回跳过数量 |
| 摘要计算跳过无关文件（去重不被 junk 污染） | `Live2DModelImporter.computeDigest` |
| 导入结果报告里说明"几套变体 / 忽略了多少无关文件" | `finishImport` 的 notes |
| **导出时**再过滤一次（老模型库里的 junk 不带走） | `ConfigPackageManager.writeModel` |
| 单测 8 条 | `test/…/Model3SelectionTest.kt`（新） |

### 8.2 用真样本验证过（不是推理）

样本:`鸣潮卡提希娅最牛逼键盘猫模型(1).zip`，解压 121 MB / 275 个文件。

**按新规则逐个校验四个 model3 候选**（脚本复刻同一套判据）:

| 深度 | 变体 | 路径 | 结论 |
|---|---|---|---|
| 2 | — | `A-卡提/img/cat.model3.json` | **无效**：Moc、两张贴图、Physics、DisplayInfo 全都不存在 |
| 4 | gamepad | `…/gamepad/cat_model/cat.model3.json` | 有效 |
| 4 | keyboard | `…/keyboard/cat_model/cat.model3.json` | 有效 |
| 4 | standard | `…/standard/cat_model/cat.model3.json` | 有效 |

⭐ 选择结果:**`keyboard`**（有效集合里深度并列 → 变体优先级 keyboard 第一）。
旧实现会"取遍历到的第一个"，也就是**可能**命中那个深度 2 的孤儿 → 报"缺少主体文件（Moc）"。

**无关文件**（按黑名单实测）:17 个 / 18.2 MB

| 后缀 | 数量 | 体积 |
|---|---|---|
| `.dll` | 13 | 14.77 MB |
| `.exe` | 2 | 1.71 MB |
| `.psd` | 1 | 1.36 MB |
| `.docx` | 1 | 0.35 MB |

⚠️ `.flac`（3 个 / 5 MB）**按设计保留** —— BongoCat 生态的按键音效，
删了以后想支持就得让用户重新导入。

**另做了一次临时探针**（跑完已删）:确认这个包的 `model3.json` 里带非 UTF-8 字节
（作者用本地编码写了中文表情名），但 `org.json` **仍能正常解析**（4 个候选都读出
`Moc` 与 4 个表情）—— 所以本次不需要动解析层。

### 8.3 还没做

| 阶段 | 内容 | 状态 |
|---|---|---|
| 3 | 纹理预算降采样（这个模型的 8192×8192 会白屏） | ⬜ |
| 4 | VTubeStudio 待机动画注入 | ⬜ |
| 5 | 移植 Axon 的 custom runtime（表情/动作/物理） | ⬜ |

⚠️ 也就是说:**这个模型现在能导入了、库和导出包里不再有 exe/dll，但画面仍会因为
8192 贴图出问题，表情与动作也还不会动** —— 那要等阶段 3 与阶段 5。
---

## 9. 阶段 3 已完成：贴图预算降采样（2026-10-31）

### 9.1 做了什么

| 文件 | 内容 |
|---|---|
| `domain/live2d/TextureBudget.kt`（新） | **纯函数**算降采样倍数:总像素预算 48 M + 单张边长上限 4096，取两者的较大倍数 |
| `data/live2d/Live2DTextureAdapter.kt`（新） | 真正解码/重编码:用 `inSampleSize` **解码时就缩小**，写回原路径 |
| `Live2DModelImporter.finishImport` | 在**摘要之前**调用它，并在之后**重新校验**一遍 |
| `notes` | 「这个模型的贴图对手机来说太大了，已把 N 张贴图缩小到 1/2……」 |

### 9.2 三个必须这么写的细节

| 细节 | 为什么 |
|---|---|
| **`inSampleSize`**，不是"解码后缩放" | 8192² 解码就是 256 MB —— 先解码那一步就 OOM 了，`createScaledBitmap` 根本没机会跑 |
| **`inPremultiplied = false`** | Live2D 的 WebGL 在上传纹理时自己做一次 alpha 预乘；解码/重编码时再乘一次会让**半透明边缘发黑**（Axon 注释里专门记过这个坑） |
| **统一按 2 的幂** | UV 是归一化的，只有严格等比缩放才能不改 model3；`inSampleSize` 也只接受 2 的幂 |

⚠️ 还有一条**顺序**要求:降采样必须在**内容摘要之前**。
否则同一个包在不同机型上（一个降了、一个没降）算出的摘要不同，"已在库里"就永远命中不了。

### 9.3 实测样本会怎么处理

`鸣潮卡提希娅最牛逼键盘猫模型` 单套贴图 = 8192² + 8192×4096 = **96 M 像素**（≈384 MB 解码）:

- 96 M / 2² = **24 M** ≤ 48 M ✓
- 边长 8192 / 2 = **4096** ≤ 4096 ✓
- → **降 2 倍**:`texture_00` 8192² → 4096²、`texture_01` 8192×4096 → 4096×2048
- 解码内存 384 MB → **96 MB**

⚠️ 普通模型（1~2 张 2048²）**不做任何处理**（`sampleFor` 返回 1）。

### 9.4 阶段 4（VTubeStudio 待机）为什么**推迟**

`injectVTubeStudioIdleIfPresent` 只改 model3 里的 `Motions.Idle` 元数据，
而**当前 runtime 根本不播 motion**（physics/motion/expression 全为 0）。
现在写它等于改了一份没人读的数据 —— 所以并到阶段 5 一起做，
那时才有东西去消费它。

### 9.5 验证清单（阶段 3）

- [ ] 那个 8192 贴图的模型:导入**成功**且画面**完整显示**（不再白屏/缺部件）
- [ ] 导入结果提示「已把 2 张贴图缩小到 1/2」
- [ ] 普通模型（2048 贴图）:画面**没有变糊**（没被降采样）
- [ ] 降采样后贴图 UV 不错位（图案不串、不花）
- [ ] 半透明边缘**不发黑**（`inPremultiplied=false` 是否生效）
- [ ] 同一个包重复导入 → 仍命中「已在库里」（摘要稳定）
---

## 10. 鼠标左右方向反了（旧问题）—— 已修

用户反馈:

> 鼠标移动时，live2d 头部跟随的左右颠倒了，貌似是一个很久远的问题了

### 10.1 根因：X 轴公式抄反了

两个 runtime（`keyboard` / `standard`）里，指针参数都是这一条公式:

```js
value = max - ratio * (max - min);   // ratio: 0 = 屏幕最左，1 = 最右
```

代入标准 Cubism 取值范围（`ParamAngleX` = -30..30、`ParamEyeBallX` = -1..1）:

| 光标 | ratio | 公式给出 ParamAngleX | 应该是 |
|---|---|---|---|
| 最左 | 0 | **+30** | -30（头朝左） |
| 最右 | 1 | **-30** | +30（头朝右） |

⭐ 而且同一份公式也套在 `ParamEyeBallX` 上 —— 光标往右时**瞳孔也往左**，
头和眼一起反，所以看起来更像"整个跟踪坏了"。

⚠️ 这条公式是照 BongoCat 原版 runtime 抄的；查了 Axon 的**四个** runtime
（`keyboard` / `standard` / `custom` 双路径），**全都是同一条** —— 也就是说
这不只是我们抄错，而是那个源头的老问题。

### 10.2 改法

```js
/* X 轴取正、Y 轴保持原样 */
value = id.endsWith('X')
  ? min + ratio * (max - min)
  : max - ratio * (max - min);
```

⚠️ **只翻 X 轴**:上下方向没有报问题（光标在上 → 抬头，符合预期），
一起翻会把没坏的东西弄坏。

⚠️ `ParamAngleZ`（歪头）**跟着翻**:它原本用 `1 - 2 * xRatio`，与旧的 X 走法同向；
X 翻了它不翻的话，光标在左右两端时"歪头方向"会和"转头方向"相反。
现在改成 `2 * xRatio - 1`。

⚠️ `globalReverse`（输入反向开关）逻辑没动，仍然只作用于 `ParamMouseX`。

### 10.3 验证清单

- [ ] 鼠标**往右**移 → 猫的**头朝右**、**瞳孔朝右**
- [ ] 鼠标**往左**移 → 头朝左、瞳孔朝左
- [ ] 上下移动方向**仍然正常**（光标在上 → 抬头）
- [ ] 光标在左下 / 右下角时，歪头方向与转头方向一致
- [ ] 内置模型与导入模型**都**是正常方向（两个 runtime 都改了）
---

## 11. 第三方模型「按按键手部直接消失」—— 已修（并说明根治要走哪条路）

用户反馈:

> 现在第三方模型，按按键手部会直接消失，也是我们要解决的

### 11.1 机制：只做了"收起手臂"那一半

实测样本的 `config.json`（Mver 的包配置）里:

```json
"keyboard" : {
  "lefthand"  : [[9],[49],[50],...],   // 每个键 → lefthand/<N>.png
  "righthand" : [[...]],
  "face"      : [[17,97],[17,98],[17,99],[17,100]],
  "l2d_expression" : [[18,219],[18,57],[18,48],[18,189]]
}
```

也就是说 Mver 的模型是**三层合成**:

| 层 | 内容 | 我们 |
|---|---|---|
| 底图 | `bg.png` | ❌ 没画（`LAYOUT_CAT` 把 background 藏了） |
| 角色 | Live2D `cat_model/`（含双臂） | ✅ 画了 |
| 手 | 按键时换成 `lefthand/<N>.png` / `righthand/<N>.png` | ❌ 没画 |

关键在**模型自己的手臂会被参数收起来**:模型里有 `CatParamLeftHandDown`（"左手按下"）
与 `CatParamRightHandDown`（"右手按下"）。Mver 按下时把 PNG 手画上去、同时把这两个参数
置 1 让模型的手臂让位。

我们原来**照着设了 1**，但 PNG 手没画 → 模型的手臂收起、又没有东西顶替 → **手部直接消失**。

### 11.2 本轮修法：这类包先去动它

- 导入时判断"这个变体目录里有没有 `lefthand/` + `righthand/`"（[hasMverHands]）
- 结果写进 `modeldata.js`:`window.__BONGO_MVER_HANDS = true`
- runtime 的 `syncHandOverrides()`:这类包**显式置 0**（保持手臂在 idle 姿势），
  其它模型行为不变

⚠️ 取舍:**宁可没有按压动画，也不能让手消失**。
按键反馈要靠 §11.3 才真正做出来。

### 11.3 根治要做什么（下一步）

| 缺口 | 说明 |
|---|---|
| **motion** | 这个包的 `cat_model/CAT_motion_lock/` 下 **19 个** `*.motion3.json`，`config.json` 的 `l2d_motion_lockhand` 把它们绑到按键上 —— 这才是"按键有反应"正规做法。我们的 runtime **完全没有 motion 实现** |
| **expression** | 4 个 `expression*.exp3.json`（正常眼 / 哭泣眼 / 眯眯眼 / 混乱眼），`config.json` 的 `l2d_expression` 绑了快捷键 |
| **PNG 手 / 底图** | Mver 的合成层（`bg.png` + `lefthand/<N>.png`），要用它就得先复刻 Mver 的**键位表语义**（`lefthand` 数组下标 = 哪个键），不能靠猜 |
| **physics** | 模型自带 `cat.physics3.json`，我们也不跑 |

⭐ 顺序建议:**先给 runtime 加 motion + expression**（纯数据格式，标准 Cubism 规范，
不需要猜 Mver 的键位表），这是"复杂模型动起来"最短的一条路；
PNG 手合成留到能确认键位表语义之后。

### 11.4 验证清单

- [ ] 导入这个模型，**按任意键:手不再消失**
- [ ] 手保持在 idle 姿势（没有按压动画 —— 这是本轮已知的取舍）
- [ ] 普通第三方模型（没有 `lefthand/`）按键行为不变
- [ ] 内置 BongoCat 模型按键行为不变
### 11.5 补丁:标准模式仍然消失（按资源目录名判断的坑）

用户反馈:

> 键盘模式手确实不消失了，但是标准模式还是一样

**原因**:第一版的判定是"目录里有 `lefthand/` **且** `righthand/`"。
实测样本三个变体的资源命名**各不相同**:

| 变体 | 手部资源 |
|---|---|
| `keyboard` | `lefthand/`(34) + `righthand/`(30) |
| **`standard`** | **`hand/`(25)** —— 两个名字一个都没有 |
| `gamepad` | `lefthand/`(7) + `righthand/`(7) |

所以 `standard` 没被识别成 Mver 包 → runtime 照旧把 `CatParam*HandDown` 设成 1 → 手消失。

**修法（两步，缺一不可）**:

1. **判据不再决定行为**:`syncHandOverrides()` 改成对**所有 `IMPORTED_MODEL`**
   一律置 0。理由:我们不画 PNG 手，所以对**任何**导入模型设 1 都是错的 ——
   这跟包怎么命名无关。
2. `hasMverHands()` 放宽（`lefthand/` `righthand/` `hand/` `left_hand/` 或包根
   `config.json`），只用来在 `modeldata.js` 里**记录**这个信息，供后续 PNG 手合成使用。

⚠️ 教训写在这里:**别用资源目录名判断包的类型** —— 社区包命名没有统一规范，
同一套模型的不同模式都可能不一样。能用一个"与行为无关"的兜底（这里是
`IMPORTED_MODEL`）就不要用猜测性的特征。

### 11.6 验证清单（补充）

- [ ] 键盘模式:按键手不消失
- [ ] **标准模式:按键手不消失**
- [ ] 手柄模式:按键手不消失
- [ ] 三个模式都能在库里切换且各自正常
### 11.7 补丁:导入的模型重启后消失（多模式连带 bug）

用户反馈:

> 导入后杀掉软件重进，导入的模型就消失了，库里也没有

**原因**:`Live2DModels.pruneMissing()`（每次 `Application.onCreate` 都跑）判定
"记录还活着"的条件是:

```kotlin
records.filter { File(File(importDir(), it.id), ENTRY_INDEX).isFile }   // 只查根目录
```

⚠️ 多模式模型的页面写在**变体自己的目录**里（`<dirId>/A-卡提/img/keyboard/index.html`），
**根目录没有 `index.html`** → 启动时被当成"记录还在、文件没了"的失效条目删掉。

也就是说:这是多模式那次改动**连带**引入的 —— 单模式模型不受影响。

**修法**:

| 改动 | 内容 |
|---|---|
| `pruneMissing()` | 改查**当前选中变体**的页面（`pageFileOf(record)`）；没有变体信息的老记录仍然查根目录 |
| `pruneOrphanDirectories()`（新） | 回收"没有记录、但文件还在"的目录 —— 之前被误删记录的模型，100+ MB 的目录还占着盘 |

⚠️ 孤儿目录的判据**很保守**（两条都满足才删）:
目录名是导入器生成的 32 位十六进制 id，**并且**目录里确实有 `index.html` 或 `*.model3.json`。
不满足就不动 —— 宁可留着，也不误删用户自己放进去的东西。

⚠️ **已经被删掉的记录回不来**，需要重新导入一次；但目录如果还在，
新版本启动时会把它们当孤儿**回收掉**（省回空间）。

### 11.8 验证清单

- [ ] 导入多模式模型 → 切到标准/手柄 → **杀进程重进** → 模型还在库里、模式选择也还在
- [ ] 单模式模型:重启后仍在（行为不变）
- [ ] 手动删掉某个模型的目录 → 重启 → 库里那条记录被清掉（旧行为保留）
- [ ] 设置 → 应用信息 → 存储:Live2D 占用不再有无主目录堆积
---

## 12. 表情（`.exp3.json`）—— 已接入（2026-11-01）

### 12.1 做了什么

| 层 | 改动 |
|---|---|
| 导入器 | 读 model3 的 `Expressions` → 逐个读 `.exp3.json` → **预解析成扁平数组**写进 `modeldata.js` |
| 导入器 | 读包 `config.json` 的 `l2d_expression` → 翻成**键名组合**（虚拟键码 → 我们的语义键名） |
| 运行时 | `applyExpression()` + `Blend` 三种语义（Add / Multiply / Overwrite） |
| 运行时 | 维护"当前按住的键"集合，组合键全按下即切表情 |
| 语义表 | 补了符号键与小键盘数字（`BracketLeft` `Minus` `Numpad0-9` …），否则组合键永远匹配不上 |

⚠️ **必须内联**：页面是 `file://`，WebView 拒绝 `fetch`/XHR 读本地文件
（moc 当初也是因此用 base64）。所以表情与绑定都写进 `modeldata.js`。

### 12.2 组合键语义（照 Axon，不是猜的）

Mver 的 `config.json`:

```json
"l2d_expression" : [[18,219],[18,57],[18,48],[18,189]]
```

Axon 的 `mverComboBindings` 就是这么解的：**外层下标 = 表情下标**，
内层每个数字是一个 **Windows 虚拟键码**，要**一起按下**才触发。
所以我们得到:Alt + `[` / Alt + `9` / Alt + `0` / Alt + `]` → 四个表情
（正常眼 / 哭泣眼 / 眯眯眼 / 混乱眼）。

⚠️ 匹配不上时**什么都不做** —— 不能让"按 A"把用户刚切的表情重置掉。

### 12.3 还没做（下一步）

| 项 | 为什么还没做 |
|---|---|
| **motion**（19 个 `CAT_motion_lock/*.motion3.json`） | 这类动作主要动 **部件不透明度**（`Target: "Model", Id: "Opacity"`），而我们的渲染器**还没有部件不透明度**（只有逐 drawable 的静态 opacity）。要先把 moc3 的 Part 解析 + 逐帧合成补上，否则动作"播了但看不见" |
| **PNG 手合成** | 要先确认 Mver 的键位表语义（`lefthand` 数组下标到底对应哪个键） |
| **physics** | `cat.physics3.json` 未跑 |

### 12.4 验证清单

- [ ] 导入这个模型 → 按 **Alt + 9** → 眼睛变（哭泣眼）
- [ ] Alt + 0 → 眯眯眼；Alt + `]` → 混乱眼；Alt + `[` → 正常眼
- [ ] 表情切换后**保持住**（不会因为按别的键就重置）
- [ ] 没有表情的模型：按键行为完全不变
- [ ] 内置模型：行为不变
---

## 13. 悬浮窗「左右下多一截空气」—— 已修（2026-11-01）

用户反馈:

> 用我发你那个模型，左、右、下都会多一截空气

### 13.1 原因

| 环节 | 旧行为 |
|---|---|
| 窗口尺寸 | Live2D 样式**固定**声明 `612 × 354`（`OverlayStyles.baseSize`），与模型内容无关 |
| 页面排版 | 导入模型被塞进设计坐标里的 `CAT_TARGET = {x:82, y:4, w:448, h:286}`，居中留白 |

两件事叠加:窗口是 612×354，而内容只占其中一块 → **左/右各 82px、下 64px 透明空气**，
如果模型是竖构图，留白只会更多。

### 13.2 修法:让窗口尺寸跟随**内容比例**

| 环节 | 新行为 |
|---|---|
| 页面 | 导入模型**按内容包围盒铺满窗口**（不再塞进 CAT_TARGET），并把这个比例回报给原生 |
| 原生 | `Live2DModels.updateContentAspect(id, aspect)` 存进模型记录（`contentAspect`，落盘） |
| 窗口 | Live2D 的 `baseSize` 改成:有比例时 `宽 = 354 × aspect、高 = 354`；没测到仍用 612×354 |
| 触发 | 比例变化时回调 `OverlayService.updateWindowSize(window)` 立刻重排窗口 |

⚠️ 桥接用 `addJavascriptInterface(..., "SthKeyBridge")` → `onContentAspect(aspect)`。
回调在 JavaBridge 线程，切主线程后再写记录/重排窗口。

⚠️ **只在第一次需要重排**:比例存在模型记录里，下次开窗口直接就是对的。

⚠️ 高度保持 354 不变、只收窄宽度:高度是"内容能画多高"的上限，
两者一起变会让同一模型在不同比例下整体缩放跳变。

### 13.3 验证清单

- [ ] 用那个模型开悬浮窗:四周**没有多余透明区**，猫填满窗口
- [ ] 第一次开可能闪一下（先按 612×354 排，比例回报后重排），第二次起直接正确
- [ ] 换个竖构图的模型:窗口变窄、仍然没有留白
- [ ] 内置 BongoCat 模型:尺寸与以前一致（它们没有内容比例，走 612×354 兜底）
- [ ] 缩放（UI 缩放）调大调小:窗口跟着变，比例不变