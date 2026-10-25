# 外部导入字体 / Live2D 方案

> 状态:**待确认**（先出方案，确认后再写代码）
>
> 用户的原话:
> > 有用户反馈导入字体/live2d太麻烦，想像导入配置那样，直接从外部打开方式导入进来

---

## 1. 现状（三条通道，各走各的）

| 通道 | 入口 | 实现 | 能收到什么 |
|---|---|---|---|
| **配置包** | 系统「打开方式 / 分享」 | `IncomingConfig.extract` → `IncomingConfigDialog` → 嗅探类型 → `ConfigPackageManager` / `GroupPackageManager` | `application/octet-stream` / `application/zip` / `application/x-zip-compressed` |
| **矢量字体** | 设置 → 「库」→ 字体库 → 导入 | `FontRegistry.import(uri)` | SAF 里手动挑（`font/ttf` 等） |
| **图片字体** | 同上（第二个按钮） | `BitmapFontImporter.readFrom(context, uri)` → `PendingBitmapImportDialog` 改名 | 同上 |
| **Live2D** | 编辑配置 → 选模型 → 导入 | `Live2DModelImporter.importZip(context, uri)` | SAF 里手动挑 zip |

⭐ 好消息:**三个导入器都已经是"吃一个 Uri"的形状**，不需要重写导入逻辑，只需要把"文件从哪来"接到它们身上。

---

## 2. 核心难点:一个 Uri 到底是**什么**

外部打开时我们只拿到一个 `content://` Uri，**后缀和 MIME 都不可信**:

| 不可信的东西 | 现实情况 |
|---|---|
| 后缀 | 聊天软件常把文件改名成 `xxx.bin`、`xxx.dat`；SAF 也可能给不出名字 |
| MIME | `.mcpack` / `.sthkey` 这类自定义后缀，系统一路报 `application/octet-stream` |
| 文件大小 | 与类型无关 |

⚠️ 而现有两个导入器**都依赖后缀**:

| 导入器 | 依赖后缀的地方 | 后果（后缀丢了） |
|---|---|---|
| `FontRegistry.import` | `sourceName.isSupportedFont()` 不在 `.ttf/.otf/.ttc` 里就直接 `return null` | 无文件名 → **静默失败** |
| `BitmapFontImporter.readFrom` | `.zip` → 找 `ascii.png`；`.mcpack` → 找 `default8.png`；否则当成单张图片 | renamed zip 被当成图片 → **报"读不出图片"** |
| `Live2DModelImporter.importZip` | 不依赖后缀（直接按 zip 打开找 `*.model3.json`） | ✅ 本来就没问题 |

⭐ **所以本方案的基石是一件事:按内容嗅探类型，再把"合成的正确文件名"喂给现有导入器。**

---

## 3. 类型嗅探

### 3.1 判定顺序（**顺序不能变**）

| 序 | 判定依据 | 结论 | 为什么排这个位置 |
|---|---|---|---|
| 1 | zip 里 `manifest.json` 的 `format == "sthkey-config"` | **配置包** | 我们自己的格式，最确定 |
| 2 | zip 里 `manifest.json` 的 `format == "sthkey-group"` | **配置组合包** | 同上 |
| 3 | zip 里任意位置有 `*.model3.json` | **Live2D 模型包** | 比"图片字体"更具体，必须先判 |
| 4 | zip 里任意位置有 `ascii.png` 或 `default8.png` | **图片字体**（Java / 基岩） | 见 §3.3 的歧义 |
| 5 | 头几个字节是图片魔数（PNG/JPEG/WebP） | **图片字体**（单张图） | 用户可能只抠了一张图集 |
| 6 | 头 4 字节是字体魔数 | **矢量字体** | 见 §3.2 |
| 7 | 其它 | **未知** | 报"支持哪些类型"，不能只说"失败" |

### 3.2 字体魔数（不靠后缀的判据）

| 字节 | 含义 |
|---|---|
| `00 01 00 00` | TrueType |
| `4F 54 54 4F`（`OTTO`） | OpenType (CFF) |
| `74 74 63 66`（`ttcf`） | TrueType Collection |
| `74 72 75 65`（`true`）/ `74 79 70 31`（`typ1`） | 老式 Apple 字体 |

⚠️ 只读前 4~8 字节就够，不需要整个文件读进内存。

### 3.3 两个真实的歧义

| 歧义 | 处理 |
|---|---|
| 一个 zip 里**同时**有 `ascii.png` 和 `default8.png`（有些材质包两份都带） | 优先 `ascii.png`（Java 版更常见），并在报告里说明"按 Java 版图集导入" |
| zip 里有 `*.model3.json` **也** 有 `ascii.png`（几乎不可能，但代码要能答） | 按 Live2D 处理（第 3 条优先） |
| 一个 `.zip` 既不是配置包、也不像字体/模型 | 报未知，不要猜 |

### 3.4 实现上的注意

- ⚠️ **必须先落到私有缓存**再嗅探:SAF 的 Uri 是流式的，`ZipFile` 需要随机访问；而且同一个 Uri **不能重复打开两次**（有些 provider 第二次就 EOF 或抛异常）。
  做法沿用现有导入器:`copyToCache(context, uri)`，嗅探完直接把这个缓存文件交给后续导入。
- ⚠️ **大小上限**:超过 64 MB 直接拒绝（防止用户选了个 2 GB 的电影，先把存储写满）。
- ⚠️ **条目数上限**:扫描 zip 时超过 4000 条就放弃（防 zip 炸弹）。
- ⚠️ **zip-slip**:解压时目标路径必须 `canonicalPath` 校验在根目录内（`Live2DModelImporter` 已有，新代码不要绕开它）。

---

## 4. Manifest 改动（含一个取舍）

现在 `MainActivity` 只有三组 MIME:`application/octet-stream` / `application/zip` / `application/x-zip-compressed`。

**要补的**（VIEW + SEND + SEND_MULTIPLE 三处都要）:

```
font/ttf、font/otf、font/collection(ttc)
application/x-font-ttf、application/x-font-otf、application/font-sfnt
application/vnd.ms-opentype、application/x-font
```

### 4.1 ⚠️ 需要你拍板:`image/png` 要不要加

| 选择 | 后果 |
|---|---|
| **不加**（推荐） | 单张 `.png` 图集只能走「设置 → 库 → 导入图片字体」。外部导入只支持**压缩包形式**的图片字体（材质包本来就是 zip/mcpack，这是绝大多数场景） |
| 加 `image/png` | 单张图集可以从外部导入，但用户**每张照片**的「打开方式」里都会出现我们的应用 —— 噪音很大 |
| 加 `image/*` | 更糟，不要 |

⭐ 我倾向**不加**，理由:图片字体本身就是"材质包"的产物，用户手上通常是 `.zip` / `.mcpack`；单独一张 `ascii.png` 的场景是"自己抠图"，那种人已经会用 SAF 了。

---

## 5. 代码结构

### 5.1 新增

| 文件 | 职责 |
|---|---|
| `data/incoming/IncomingFile.kt` | `Request(uris, fromShare)`（⚠️ 复数，见 §7.2）+ 从 Intent 提取（把现有 `IncomingConfig.extract` 泛化） |
| `data/incoming/FileKind.kt` | 枚举:`ConfigPackage / GroupPackage / Live2DModel / BitmapFont(archive) / BitmapFont(image) / VectorFont / Unknown` |
| `data/incoming/FileSniffer.kt` | 按 §3 判定；返回 `Sniffed(kind, cacheFile, nameHint)` |
| `ui/feature/incoming/IncomingFileDialog.kt` | 唯一的对外对话框:识别中 → 确认 → 导入中 → 结果 |
| `ui/feature/incoming/IncomingImportReport.kt` | 多文件时的逐条结果（成功/失败原因） |

### 5.2 改动（都很小）

| 文件 | 改什么 |
|---|---|
| `MainActivity` | `handleIncomingIntent` 改用 `IncomingFile.extract`；对话框换成 `IncomingFileDialog` |
| `IncomingConfigDialog` | **删除**，逻辑并入 `IncomingFileDialog`（它现在已经在做类型嗅探了，两处嗅探必然分叉） |
| `FontRegistry` | 加 `import(uri, displayNameHint: String?)`；⚠️ 有 hint 时**跳过** `isSupportedFont` 检查（已经按魔数确认过了） |
| `BitmapFontImporter` | 已经支持传 `displayName`（现有重载），嗅探层传合成名字即可，**不用改** |
| `Live2DModelImporter` | 不用改 |
| `AndroidManifest.xml` | §4 的 MIME |

### 5.3 关键:嗅探结果怎么"喂"给现有导入器

```kotlin
when (kind) {
    ConfigPackage    -> ConfigPackageManager.import(context, uri)
    GroupPackage     -> GroupPackageManager.import(context, uri)
    Live2DModel      -> Live2DModelImporter.importZip(context, uri)
    BitmapFont       -> BitmapFontImporter.readFrom(context, uri, nameHint)   // nameHint 带 .zip/.mcpack/.png
    VectorFont       -> FontRegistry.import(uri, nameHint)                    // nameHint 带 .ttf/.otf/.ttc
    Unknown          -> 报告"不认识这个文件"
}
```

⭐ `nameHint` 由**嗅探结果**决定，不是原文件名:
- zip + ascii.png → `字体.zip`
- zip + default8.png → `字体.mcpack`
- 图片魔数 → `字体.png`
- 字体魔数（TrueType/OpenType）→ `字体.ttf` / `字体.otf`
- `ttcf` → `字体.ttc`

---

## 6. 各类型的交互流程

### 6.1 配置包 / 组合包（保持现状）

识别 → 「要导入这个配置包吗？」→ 导入中 → `ImportReportContent`（现有那套完整报告）。

⚠️ 行为**一点都不能变**:这是现在唯一在用的外部导入路径。

### 6.2 矢量字体

识别 → 「这是一个字体，导入到字体库？」（显示文件名 + 大小）→ 导入中 →
「已导入字体「XX」，可以在 设置 → 库 → 字体库 里查看」+ **重复时明确说出来**（见 §7.3）。

### 6.3 图片字体（需要多一步）

⚠️ 图片字体导入后要**让用户确认名字**（`PendingBitmapImportDialog`），
所以流程比别的类型长一步:

```
识别 → 确认 → readFrom 得到 Source → PendingBitmapImportDialog（改名）→ 完成
```

⚠️ **对话框套对话框**的坑:`PendingBitmapImportDialog` 是独立 AlertDialog，
在 `IncomingFileDialog` 还没关的时候弹第二个，某些 ROM 会把第一个顶掉。
做法:**先关掉 IncomingFileDialog，再弹命名对话框**（命名完成后走 Toast 提示）。

### 6.4 Live2D 模型

识别 → 「这是一个 Live2D 模型（名称取自 model3.json 的 name 字段）」→ 导入中 → 结果。

⚠️ 现有 `importZip` 已经按内容哈希去重:重复导入会直接**复用已有模型**，
结果文案要说清是"已存在，直接复用"而不是"导入成功"。

---

## 7. 必须处理的坑

### 7.1 ⚠️ SAF Uri 只能读一次

同一个 `content://` Uri **不能**反复 `openInputStream`（部分 provider 第二次直接抛 `SecurityException`）。
→ 嗅探时**复制到缓存**，后续所有步骤都用缓存文件（用 `FileProvider` 或 `file://` + `ContentResolver`）。

### 7.2 ⚠️ 多文件（SEND_MULTIPLE）

现在 `IncomingConfig` **只取第一个**。分享字体时用户很可能一次选 3 个字体。
→ 建议 `Request` 改成 `uris: List<Uri>`，逐个嗅探 + 逐个导入，最后给一份**逐条报告**（成功几个、失败几个及原因）。
⚠️ 不要"一个失败就整批回滚"——字体之间没有依赖关系。

### 7.3 ⚠️ 重复导入（现在完全没拦）

| 类型 | 现状 | 建议 |
|---|---|---|
| 矢量字体 | ⚠️ 同名字体会加 `(2)`，**同一份文件导入两次就是两条** | 导入前算 sha256 查 `FontRegistry.findBySha256` → 命中就提示"这个字体已经在库里（名 X）"并跳过 |
| 图片字体 | 需要看 `BitmapFontStore` 有没有哈希（实现时确认） | 同上 |
| Live2D | ✅ 已按 digest 去重 | 文案区分"复用"与"新导入" |

### 7.4 ⚠️ 别让外部导入绕过"库"的管理

字体/模型是**全局资源**，不是某份配置的附属物。所以:
- 导入成功后**只能**落在 设置 → 库 里；
- **不要**因为用户在某个配置编辑页里打开了一个字体就自动把它设成那份配置的字体 —— 外部导入时根本不知道用户在编辑哪份配置。

### 7.5 ⚠️ 失败文案要具体

| 情况 | 文案方向 |
|---|---|
| 压缩包里没有 `*.model3.json` | 已有:「这个压缩包里没有 .model3.json，不是 Live2D 模型包」 |
| 字体魔数对但解析失败 | 「这个字体文件已损坏或用了不支持的格式」 |
| 未知 | 「不认识这个文件。支持:配置包(.sthkey)、配置组合(.sthgroup)、字体(.ttf/.otf/.ttc)、图片字体(材质包 zip 或 png)、Live2D 模型(zip)」 |
| 太大 | 「文件太大（超过 64 MB）」 |

---

## 8. 任务拆分

| # | 任务 | 依赖 |
|---|---|---|
| 1 | `FileKind` + `FileSniffer`（纯逻辑，**可单测**） | — |
| 2 | 嗅探单测:字体魔数、zip 结构、优先级、未知 | 1 |
| 3 | `IncomingFile.Request`（多文件）+ MainActivity 接线 | — |
| 4 | `IncomingFileDialog`（纯配置包/组合包先跑通，**行为与现在完全一致**） | 3 |
| 5 | 接入矢量字体（含 `FontRegistry` 的 nameHint 重载 + sha256 去重） | 1,4 |
| 6 | 接入 Live2D | 1,4 |
| 7 | 接入图片字体（含"先关对话框再弹命名"） | 1,4 |
| 8 | manifest 补 MIME | — |
| 9 | 删掉 `IncomingConfigDialog`，文档更新 | 4 |

⚠️ 建议 **1→2→3→4** 先做（先把现有配置导入迁到新框架且行为不变），
再逐类型接字体/模型 —— 这样随时可回退，也不会一次炸掉现有功能。

---

## 9. 需要你拍板

| # | 问题 | 我的建议 |
|---|---|---|
| 1 | `image/png` 要不要加进 manifest（单张图集外部导入） | **不加**（噪音大，材质包是 zip 已经覆盖） |
| 2 | 要不要支持一次分享多个文件 | **要**（同时导入，逐条报告） |
| 3 | 导入成功后要不要自动跳到「设置 → 库」 | **不跳**，只在结果里说明去哪看（跳转会打断用户手头的事） |
| 4 | 矢量字体重复导入怎么处理 | **按 sha256 拦住**，提示已在库里 |
| 5 | 外部导入要不要确认框（还是直接进库） | **要确认**（字体/模型是全局资源，装错了会出现在所有配置里） |
| 6 | 单个 `ascii.png` 能不能外部导入 | 见 #1:不加 `image/png` 就不行，只能走 SAF |

---

## 10. 实现记录（已完成）

用户确认:**1 按建议不做**（不加 `image/png`），其余也都按建议。
另外一句要求:

> 导入字体/live2d成功后，对话框显示的字符可以写已加入字体/Live2D库，
> 其他跟导入配置差不多就行了

### 10.1 落地清单

| 文件 | 状态 | 说明 |
|---|---|---|
| `data/incoming/FileKind.kt` | ✅ 新增 | 9 种类型 + 合成文件名（`suggestedFileName`） |
| `data/incoming/IncomingFile.kt` | ✅ 新增 | 多文件请求 + Intent 提取（旧 `IncomingConfig` 的泛化版） |
| `data/incoming/FileSniffer.kt` | ✅ 新增 | 按魔数 / zip 结构嗅探；带 64 MB 与 4000 条目上限 |
| `ui/feature/incoming/IncomingFileDialog.kt` | ✅ 新增 | 识别 → 确认 → 导入 → 结果；多文件逐条报告 |
| `FontRegistry.importDetailed` | ✅ 改造 | 支持 `displayNameHint`（外部文件常没名字）+ **sha256 去重** |
| `MainActivity` | ✅ 改造 | `IncomingFile.extract` + 新对话框 + 图片字体命名接力 |
| `AndroidManifest.xml` | ✅ 改造 | VIEW / SEND / SEND_MULTIPLE 三处都补了字体 MIME |
| `IncomingConfig.kt` / `IncomingConfigDialog.kt` / `IncomingConfigTest.kt` | ✅ 删除 | 已被上面取代，避免两套嗅探逻辑分叉 |

### 10.2 关键实现细节（不看会踩）

| 点 | 做法 / 原因 |
|---|---|
| ⚠️ `InputStream.readNBytes` | **是 API 33+ 的 API**。minSdk 28 直接用会在旧机抛 `NoSuchMethodError`，崩在导入路径里、看起来像"文件坏了"。已换成手写 `readAtMost` |
| ⚠️ 每个导入器**只调一次** | 写成 `?: return Failed(importer(...).exceptionOrNull())` 会**导两遍**（一遍进库、一遍只为拿错误），既慢又可能生成两份数据 |
| ⚠️ 缓存文件的生命周期 | 嗅探阶段复制出的缓存:导入成功由 `runImport` 的 `finally` 删；用户**取消**时由 `DisposableEffect` 统一删。不删的话一次取消就永久占几十 MB |
| ⚠️ 图片字体要接力 | 命名窗口是第二个 `AlertDialog`，所以统一对话框先把 `Source` 抛给 Activity、**关掉自己**，Activity 再渲染 `PendingBitmapImportDialog` |
| ⚠️ 去重文案 | 字体/模型的内容哈希命中时说「这个字体已经在字体库里」/「这个模型已经在 Live2D 库里」，**不能**说"导入成功"（列表里不会多一条，用户会以为失败） |

### 10.3 结果文案（按用户要求）

| 类型 | 成功文案 |
|---|---|
| 配置 | 已导入为配置 |
| 配置组合 | 已导入配置组合（N 个配置） |
| 矢量字体 | **已加入字体库** |
| 图片字体 | 请在接下来的命名窗口里确认名称 |
| Live2D | **已加入 Live2D 库** |

底部还加了一句「字体与模型都可以在「设置 → 库」里查看、改名或删除。」

### 10.4 测试

| 类型 | 覆盖 |
|---|---|
| 单测 | `IncomingFileTest`（8 条）:哪种 action 从哪取、多文件、去重、VIEW 只取一个 |
| 待真机 | 字体/材质包/模型/配置包各自的识别与导入（见下面的验证清单） |
---

## 11. 名称问题（用户反馈）

> 导入的矢量字体/模型的名称有问题，live2d 模型外部导入，去库里看名称是「live2d-model」，
> 矢量字体外部导入，名称是「字体」，而不是源文件名称
>
> 后缀不需要加载库里看到的名称，名称只要文件名

### 11.1 两个原因

| 类型 | 原因 |
|---|---|
| Live2D | `importZip` 内部用 `queryDisplayName(context, uri)` 取名字。我们传的是**自己的缓存文件**（`incoming_xxx.bin`），`contentResolver.query` 查不到 `DISPLAY_NAME` → 落到兜底的 **`live2d-model`** |
| 矢量字体 | 我把 `FileKind.suggestedFileName`（占位名 **`字体.ttf`**）当成了 hint，而不是原文件名 |

### 11.2 修法

1. `Live2DModelImporter.importZip` 加 `displayNameHint` 参数（外部导入时传原名）；
2. 新增 `namedForImport(originalName, kind)`:**原文件名主体 + 嗅探出的正确后缀**。

| 原名 | 识别为 | 交给导入器 | 库里显示 |
|---|---|---|---|
| `MyFont.ttf` | TrueType | `MyFont.ttf` | **MyFont** |
| `MyFont`（无后缀） | TrueType | `MyFont.ttf` | **MyFont** |
| `my font.bin`（被改名） | TrueType | `my font.ttf` | **my font** |
| `我的模型.zip` | Live2D | `我的模型.zip` | **我的模型** |
| `材质包.zip` | 图片字体 | `材质包.zip` | 用户自己命名 |

⚠️ **为什么交给导入器的名字仍带后缀**:
`FontRegistry.isSupportedFont()` 和 `BitmapFontImporter` 的 `endsWith(".zip")`
都是**按后缀分派**的 —— 不给后缀，前者静默失败、后者把 zip 当成单张图片。

⚠️ **为什么库里看到的不带后缀**:剥后缀由各导入器自己做
（`FontRegistry` 的 `substringBeforeLast('.')`、`Live2DModelImporter` 同理），
这样"分派用全名、显示用主体名"两件事各归各处，不会互相污染。

### 11.3 测试

新增 `IncomingNamedImportTest`（7 条）把它钉死:
带后缀 / 无后缀 / 本来就是正确后缀（不能变成 `.ttf.ttf`）/ 多段后缀 /
图片字体的 zip·mcpack / Live2D 原样 / 空名字退回占位名。

⚠️ 该测试**不需要 Android 环境** —— 为此特意把 `namedForImport` 的入参
从 `SniffedItem`（带 `Uri`）改成 `(String, FileKind)`:
`Uri.EMPTY` 在本地单测里会直接抛 "EMPTY must not be null"。