# 配置包（`.sthkey`）的导出与导入

这份文档是**配置包**子系统的设计依据：包结构、外部导入的接法、
为什么这么做、哪里不能改。改动这个子系统之前先读这里。

> 图片字体在包里的处理见 [`bitmap-font.md`](bitmap-font.md) 第 9 节 ——
> 那份讲"一个字体的规格怎么随包走"，这份讲"包本身"。

---

## 1. 包是什么

一个 `.sthkey` 就是一个 **zip**：

```
manifest.json          元信息（格式标识、版本、字体/模型清单）
params.json            配置本体
assets/fonts/*.ttf     随包携带的矢量字体
assets/fonts/*.png     随包携带的图片字体图集
assets/live2d/<名>/…   随包携带的 Live2D 模型
```

**为什么用 zip 而不是 JSON**：要连字体、模型一起带走。纯 JSON 只能存 id，
而 id 在别人设备上不存在。

### 1.1 不随包走的资源

| 资源 | 进包？ | 原因 |
|---|---|---|
| 系统字体 | ❌ | 只存 id，对方本来就有 |
| 内置字体 | ❌ | 随应用打包，从"加入自定义字体"那个版本起人人都有 |
| 内置 Live2D 模型 | ❌ | 同上（`builtin:keyboard` / `builtin:standard`） |
| 导入的字体 / 模型 | ✅ | 只存在于导出方的设备上 |

内置字体当年是跟着包一起导出的（那时是 Key 样式，字体也只有内置那一个），
那是**多此一举**，还让每份包大好几 MB。现在与系统字体同一套处理。

---

## 2. 入口

| 入口 | 位置 |
|---|---|
| 导出 | 配置页卡片上的「导出」/「更多」菜单 |
| 导入 | 配置页顶部的「导入配置」按钮 |
| **外部导入** | 系统分享 / 用本应用打开（见第 4 节） |

---

## 2.1 导出：三种方式、两种机制

点导出时按**默认导出方式**（设置页可调）走；没设过就弹选择窗口。

| 方式 | 机制 | 要弹系统界面 | 权限 |
|---|---|---|---|
| 导出到目录 | MediaStore → `Download/sthkeyconfigs/` | 否 | **零权限** |
| 手动选择位置 | SAF `CreateDocument` | 是 | 零权限 |
| 分享 | FileProvider + `ACTION_SEND` | 是 | 零权限 |
| 每次询问 | —— | 弹本应用的窗口 | —— |

### ⚠️ 不需要 `MANAGE_EXTERNAL_STORAGE`

`minSdk = 30` → Android 11+ 上写 `Download/` 走 MediaStore **本来就不需要权限**。

**不要加那个权限。** 它是"特殊应用权限"，要跳到系统设置页手动打开
（比手选位置还麻烦），而且一个悬浮窗 + root/Shizuku 的应用再申请
"所有文件访问"在用户眼里非常可疑，反而会让用户不敢装。

### ⚠️ 曾经有过"自定义导出目录"，已删除

那套是 SAF 目录树（`OpenDocumentTree` + `takePersistableUriPermission`
+ `DocumentsContract.createDocument`），看起来更"完整"，但**实测在多种设备上
都会失败**（报"无法在所选目录里创建文件"），而且失败原因难以定位：

> `createDocument` 抛的 `FileNotFoundException` 在不同 DocumentsProvider 下
> 含义不同 —— 有的表示重名、有的表示该目录不允许写入，光看异常分不出来。

用户实际只需要导到 Download，所以整条路删掉了。留下的好处不只是少一处代码：

- 少了"授权被系统回收"这一整类失败；
- 少了 SAF 持久化权限的管理；
- 少了一个"用户以为设好了、其实存不进去"的困惑源。

⚠️ [MANUAL 手动选择位置] 与它**不是**一回事：那是**用户当次自己选位置**，
走 `ACTION_CREATE_DOCUMENT`（系统界面），一直正常。
区别在"预先设一个目录、之后静默写入" —— 那才是被删掉的。

### ⚠️ MediaStore 的 MIME 决定后缀，**必须给 `application/octet-stream`**

这是实测踩到的：给 `application/zip` 时，导出文件名变成
**`配置名.sthkey.zip`**。

原因：MediaStore 会把 MIME 与文件后缀**对一遍**，对不上就按 MIME
补一个后缀。`配置名.sthkey` + `application/zip` 正是"对不上"，
于是它补了 `.zip`。

`application/octet-stream` 是"未知二进制"的正式说法，
**不会**触发任何后缀补全。

⚠️ 而**分享**时反而要给 `application/zip`（见下）—— 两处要求正好相反，
别互相"统一"。

### ⚠️ MediaStore 与 SAF 的重名行为不同

| 机制 | 重名时 |
|---|---|
| MediaStore `insert` | **自动改名**（`x (1).sthkey`），返回的 Uri 已是新的 |
| SAF `createDocument` | **抛 `FileNotFoundException`**，不会自动改名 |

所以 MediaStore 那条路要把**实际落盘的文件名**回报给界面 ——
用户去文件管理器里得按那个名字找。

### ⚠️ MediaStore 的 `IS_PENDING`

写入前置 `IS_PENDING = 1`、写完清零。**这不是可选的优化**：
不置的话别的应用（文件管理器、扫描器、同步工具）可能在
**我们写了一半**时读到那个文件，拿到一个残缺的 zip。

### 分享：必须走 FileProvider

Android 7+ 传 `file://` 出去抛 `FileUriExposedException`。必须
`FileProvider.getUriForFile` + `FLAG_GRANT_READ_URI_PERMISSION`，
否则接收方（微信等）读不到内容。

⚠️ 分享的 MIME 给 `application/zip`，而**保存**给 `application/octet-stream`。
给前者时微信/QQ/邮件都认；给 `octet-stream` 的话一部分接收方会直接拒收
（"不支持的文件类型"）—— 那是"发不出去"，比后缀难看严重得多。

⚠️ `file_paths.xml` **只暴露 `cacheDir/share/`**，不要写 `path="."` ——
共享出去的 Uri 带上临时读权限后，接收方在那段时间里能读到该路径下任何文件。
每次分享前清掉上一次的（配置包可能带几 MB 字体）。

---

## 3. ⚠️ 导出时的 MIME 必须传通配符

`ActivityResultContracts.CreateDocument` 的 MIME 参数传的是**通配符**。

看起来应该传 `application/zip`（内容确实是 zip，更"正确"），但**不能**：

> 传具体 MIME 时，**部分文件管理器会因为"类型与后缀不匹配"
> 强行改成它认识的组合** —— 于是 `.sthkey` 变成了 `.zip` / `.bin`。

而"后缀不匹配"恰恰一定会发生：`.sthkey` 与 `application/zip` 本来就不是一对。
通配符下系统不会去纠后缀，我们给什么就是什么。

**连带的后果**：导出文件的 MIME 也是通配符，所以外部导入那边
**必须**容忍 `application/octet-stream` 这类"大路货"类型 ——
不能指望我们自己导出的文件带着一个体面的 MIME。

---

## 4. 外部导入（分享 / 打开）

### 4.1 ⚠️ Android 没有"自定义文件类型"这回事

这是整个功能最容易踩空的地方。

`android.webkit.MimeTypeMap` 靠一张**内建表**，只认已知后缀。
`.sthkey` 不在表里 → `getMimeTypeFromExtension("sthkey")` 返回 null →
系统解析出来是 `application/octet-stream` 之类。

**所以 manifest 里写 `<data android:mimeType="application/x-sthkey" />`
永远不会被匹配到。** 只能把别人实际会报的类型全列上。

### 4.2 实际声明的 intent-filter

| Action | 触发场景 |
|---|---|
| `VIEW` | 文件管理器里**点**这个文件 |
| `SEND` | 别的应用里**分享**这个文件 |
| `SEND_MULTIPLE` | 一次分享多个（不声明的话某些分享面板里不显示我们） |

三种都声明了 `application/octet-stream`、`application/zip`、
`application/x-zip-compressed`。

⚠️ **`*/*` 是故意不加的**（虽然能提高命中率）：加了之后用户在任何文件上点
"打开方式"都会看到我们这个与文件无关的应用，非常烦人。

⚠️ `pathPattern=".*\\.sthkey"` 也**不能当主力**：它只对路径里真的带后缀的
Uri 有效，而 content Uri 的路径常常是一串数字 id。

### 4.3 ⚠️ `onCreate` 与 `onNewIntent` 必须走同一条路

| 应用状态 | 拿到 Intent 的回调 |
|---|---|
| 没在运行（冷启动） | **`onCreate`** |
| 在后台 | **`onNewIntent`** |

只写 `onNewIntent` 的症状是"**第一次分享没用、再分享一次才行**"——
因为第一次是冷启动，走了 `onCreate` 而那里没处理。

两条路都调 `handleIncomingIntent`（见 `MainActivity`）。

⚠️ `onCreate` 里要判 `savedInstanceState == null`：
屏幕旋转等原因重建时系统会把**原来的** Intent 再给一次，
不判断的话每次转屏都会重新弹一次导入确认框。

⚠️ `onNewIntent` 里要调 `setIntent(intent)`：不调的话 `getIntent()`
之后一直返回**旧的**那个 Intent。

⚠️ `launchMode="singleTask"`：否则分享时会开出第二个 Activity 实例。

### 4.4 ⚠️ 取 Uri 的分层：判断层不碰 Android 类型

`IncomingConfig` 分成两层：

- **`pickUri`**（纯函数）：只吃 `action / data / stream / streamList`
  四个 `String?`，**完全不碰 Android 类型**；
- **`extract`**（唯一碰 `Intent` 的地方）：把上面四个值取出来，
  最后做一次 `Uri.parse`。

为什么这么分：本地 JVM 单测里 **`Uri` 与 `Intent` 都是空壳** ——
连 `Uri.parse` 都会抛 `Method ... not mocked`。
而本项目的测试环境没有 Robolectric，构建又是离线的，加不了依赖。

于是"哪条分支取哪个字段"这个**真正容易写错**的部分可以完整穷举测试
（见 `IncomingConfigTest`），Android 那层只剩几行无分支的读取。

### 4.5 要处理的几种变态形态

| 形态 | 为什么要管 |
|---|---|
| `ACTION_SEND` 用 `EXTRA_STREAM`、`ACTION_VIEW` 用 `data` | 两个 action 的约定完全不同，混用取不到 |
| `EXTRA_STREAM` 是 `ArrayList<Uri>` | 官方约定是单个 Uri，但**确实有应用**（哪怕只分享一个）塞列表。不认这种形态 = "某些应用分享了没反应" |
| `ACTION_VIEW` 把 Uri 也塞进 extra | 不规范但存在，多看一眼没代价 |
| 一次分享多个 | **只取第一个**。不做批量：一次弹好几个确认框是骚扰，而且很难分辨哪个框对应哪个包 |

### 4.6 ⚠️ 不按 MIME 过滤

`pickUri` **压根没有 MIME 参数**，这是刻意的。

按 MIME 过滤会把一部分来源**静默丢掉** —— 各家应用报的 MIME 五花八门，
而在用户看来就是"这个功能时好时坏"。

**宁可多收一个再报错，也不要静默不响应。** 收下之后由
`ConfigPackageManager.import` 校验 manifest，格式不对会明确报错。

### 4.7 确认框：不静默导入、不覆盖

`.sthkey` 本质是 zip，而我们是靠几个"大路货" MIME 接住的 ——
也就是说**任何**这类文件都可能被送到这里。所以**先确认，再导入**。

⚠️ **不提供"覆盖同名配置"**：`import` 遇到重名会自动改名并在结果里标记
`renamed`（于是用户得到"配置 (2)"）。这比覆盖安全得多：
**覆盖是不可逆的**，而用户点"导入"时想的是"把这份加进来"，
不是"把我现在那份毁掉"。

确认框要明确说出被改名了 —— 用户明明导入的是"A"、列表里却出现"A (2)"，
不说的话会以为导入坏了。

⚠️ **导入进行中不允许关掉对话框**：关掉不会取消已经开始的写入
（协程还在跑），用户以为"取消了"、实际配置已经进去了。

⚠️ 确认框挂在**最外层**（`SthKeyApp`）而不是某个页面里：
分享进来时应用可能停在任意页面，也可能还在引导页。

---

## 5. 兼容与容错

- **格式标识**：`manifest.json` 里的 `format` 不是 `sthkey-config` 就报
  "这不是 sth key 的配置包"，而不是尝试解析。
- **缺文件**：缺 `manifest.json` / `params.json` 分别报明确原因。
- **未知字段**：来自更新版本、本版本不认识的字段会被**跳过并列出**，
  而不是丢弃整份配置。见 `JsonConfigCodec.KNOWN_KEYS` 的说明 ——
  ⚠️ 它是**推导**出来的（把探针配置编码一遍看写出哪些键），
  所以任何"条件写入"的字段都可能不在清单里而被误报成"未识别"。
  **新增字段一律无条件写。**
- **字体/模型缺失**：回落默认值，在导入报告里**按名字列出**缺了哪些。
  ⚠️ 是列表而不是一个布尔：自定义 Key 里每个组件都能有自己的字体，
  只报"有字体缺失"的话用户不知道该去改哪个组件。

---

## 6. 打包时怎么找到要带的资源

字体从 **params 树**里收集，不是从 config 对象 —— 因为自定义 Key 的每个组件
都有自己的 `style.fontId` / `style.bitmapFontId`，从 `config.fontId` 取只能拿到
顶层那一个。

收集规则：字段名**以 `fontId` 结尾**（`== "fontId"` 或 `endsWith("FontId")`）。

⚠️ **新增字体类字段时沿用这个命名约定**（`bitmapFontId` 就是这么来的），
否则它不会被打包，也不会被改写成包内引用。

---

## 7. 相关文件

```
data/config/ConfigPackageCodec.kt     包结构、manifest 读写
data/config/ConfigPackageManager.kt   导出 / 导入 / 资源落盘
data/config/ConfigExporters.kt        两种"不用挑位置"的导出：下载目录（MediaStore）/ 分享（FileProvider）
data/config/ExportMethod.kt           导出方式的枚举
data/config/ParamsTree.kt             从 params 树里收集与改写字体 id
data/config/IncomingConfig.kt         外部 Intent → 待导入的位置（纯逻辑 + 薄壳）
ui/feature/config/ExportFlow.kt       导出流程与 launcher（方向、回调、重入保护）
ui/feature/config/ExportMethodDialog.kt     导出方式选择窗口
ui/feature/config/ImportReportDialog.kt     导入报告（两个导入入口共用）
ui/feature/config/IncomingConfigDialog.kt   外部导入的确认与结果
ui/feature/config/ConfigListScreen.kt       导出 / 导入入口
ui/feature/settings/SettingsScreen.kt       默认导出方式
core/prefs/AppPrefs.kt                默认导出方式的持久化
res/xml/file_paths.xml                FileProvider 只暴露 cacheDir/share/
AndroidManifest.xml                   外部导入的 intent-filter、FileProvider 声明
```
