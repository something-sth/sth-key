# 桌面快捷方式

这份文档是**桌面快捷方式**子系统的设计依据：为什么这么做、哪里不能改。
改动这块之前先读这里。

---

## 1. 它是什么

用户可以在主页创建一个桌面图标，**点它启动悬浮窗并重启监听，全程不打开应用界面**。

两种快捷方式（**互斥**，一个快捷方式只能有一种行为）：

| 名称 | 行为 |
|---|---|
| 启动悬浮窗 | 打开勾选的那几份配置的悬浮窗，并重启采集 |
| 一键关闭所有悬浮窗 | 关掉全部悬浮窗 |

想两个都要就分别创建两次 —— 所以按钮文案是「添加到桌面」而不是「保存」。

---

## 2. ⚠️ 入口必须是 Activity，而且是 `Theme.NoDisplay`

### 为什么不能是 Service / Receiver

要做的两件事都需要"前台身份"：

1. 启动**前台服务**（悬浮窗）—— Android 12+ 限制后台启动 FGS；
2. 启动**采集**（Shizuku 要拿 binder）—— 同样受限。

而"用户点了桌面图标"给的是一个 **Activity 启动**。走 Service 或 Receiver 的话，
从启动器点击到 FGS 真正起来之间有一段没有前台组件的空窗，
部分 ROM 会判定成后台启动直接拦掉。

所以 `ShortcutActivity` 只做"立刻转交给服务、然后结束自己"。

> 反过来也要注意：**Android 10+ 禁止后台启动 Activity** 这条限制在这里
> **不适用** —— 用户点图标不算后台启动。这是最容易让人以为"做不到"的地方。

### ⚠️ `Theme.NoDisplay` 的硬要求

比"全屏透明"更彻底：**连窗口都不创建**，所以点快捷方式时桌面一点动画都没有。

但它要求 Activity 在 **`onResume()` 之前**调用 `finish()`，否则系统直接抛
`IllegalStateException`（"Activity did not call finish() prior to onResume"）。

**所以 `ShortcutActivity.onCreate` 里不能有任何异步等待。**
`OverlayService.start` 与 `CaptureController.start` 本身都是"发起请求就返回"，
正好适合；以后要加需要等待结果的东西，得换个做法（比如让 Service 自己去做）。

### ⚠️ 不要加 `android:noHistory="true"`

它会让 Activity 一不可见就销毁，而那时前台服务可能还没真正起来 ——
进程被判成"没有前台组件"就有被杀的风险。进程一死，Shizuku 的回调接收器
也跟着没了。

已经加了的是 `taskAffinity=""` + `excludeFromRecents="true"`：
让这个 Activity 独立于应用主任务、不进最近任务，
于是"从桌面点快捷方式"不会把应用主界面也带到前台。

---

## 3. ⚠️ 配置清单存偏好，不塞进 Intent

快捷方式一经创建，它的 Intent 就被系统**冻结**了 —— 之后改 Intent 不生效，
用户必须删掉重新创建。

所以 Intent 里带两个标记：动作（`ShortcutSettings.EXTRA_ACTION`）与
**这个快捷方式自己的 id**（`EXTRA_SHORTCUT_ID`）；清单存在
`AppPrefs.shortcutConfigIds`，按 id 分开。

### ⚠️ 可以有**多个**启动类快捷方式，所以 id 必须动态分配

用户想给不同的配置组合各做一个图标，所以每次"添加到桌面"都由
`ShortcutSettings.nextLauncherId` 分配一个新 id
（`launch_overlays_1`、`_2`…）。

**第一版用一个固定 id，bug 就是从这里来的**：系统认为 id 相同就是同一个
快捷方式，于是第二次创建**顶掉**了第一个 —— 用户看到的现象是
"我只能加两个：一个启动的、一个关闭的"。

⚠️ 序号取"**已用过的最大序号 + 1**"，不是"当前有几个"：删掉中间某一个之后
按数量算会重复，新快捷方式又会顶掉已有的。

⚠️ 关闭类是**例外**：只允许存在一个（多一个"关闭全部"图标没有任何意义），
所以它的 id 是固定的 `disable_all_overlays`。

### ⚠️ 必须过滤已删除的配置

用户在主页勾了几份配置做快捷方式，之后去配置页删掉一份 —— 那时清单里
还留着一个不存在的 id。

不过滤的后果：`OverlayController` 会把无效 id 写进"启用了哪些配置"，
而服务找不到那份配置。表现是**"点快捷方式没反应"或"窗口比勾的少一个"**，
完全联想不到是删配置导致的。

见 `ShortcutSettings.resolveExisting`（`ShortcutActivity` 里调用）。

### ⚠️ 刻意**不做**"删除快捷方式"，也不列"已创建了哪些"

曾经有过一个"已有的快捷方式"列表 + 删除按钮，但它**做不到它承诺的事**：

- 我们能移除的只是自己那条记录与系统里的声明；
- **桌面上那个图标是启动器管的** —— 多数启动器不会因为我们调用
  `removeLongLivedShortcuts` 就把用户的图标删掉（各家行为不一致，
  而且那是它们自己的 UI 决定）。

结果是：用户点了删除、列表里没了，但桌面图标还在 ——
而那个图标已经变成**空壳**（记录没了，点它什么也不发生）。

**"删不干净"比"没有删除功能"更糟**，所以整块去掉了。
要删就让用户在桌面上长按删 —— 那是启动器自己的功能，一定删得掉。

于是 `ShortcutSettings` 只负责两件事：**分配 id** 与**按 id 存取清单**；
`ShortcutSetupDialog` 每次打开都是"新建一个"。

---

## 4. ⚠️ 动作名与 extra 键是**对外契约**

```
ShortcutSettings.ACTION_LAUNCH      = "launch"
ShortcutSettings.ACTION_DISABLE_ALL = "disable_all"
ShortcutSettings.EXTRA_ACTION       = "shortcut_action"
ShortcutSettings.EXTRA_SHORTCUT_ID  = "shortcut_id"
ShortcutSettings.DISABLE_SHORTCUT_ID = "disable_all_overlays"
```

**改了它们，已经钉在用户桌面上的快捷方式会失效**（点了没反应），
而编译是通过的、代码里看不出任何问题。

`res/xml/shortcuts.xml` 里的 `shortcutId` 必须与之一致 ——
那份声明缺失或不一致时 `requestPinShortcut` 会**静默失败**（弹不出确认框）。
注意启动类的 id 是动态的，XML 里那个只是**模板**：系统按"这个应用会创建
快捷方式"来读它，不会按前缀去匹配实际创建的 id。
`ShortcutSettingsTest` 里有测试直接读那个 XML 核对。

---

## 5. ⚠️ 用 `requestPinShortcut`，不是 `INSTALL_SHORTCUT` 广播

老办法从 Android 8.0 起已废弃，而且是"应用偷偷往桌面塞图标"的语义 ——
现在的 ROM 基本不认。

标准做法是 `ShortcutManagerCompat.requestPinShortcut`：系统弹一个
"要把这个添加到主屏幕吗"，用户点确定才加上。**不需要任何权限**。

⚠️ 有些启动器（部分国产 ROM 的自研桌面）**不支持**。
`isRequestPinShortcutSupported` 要先判断，不支持时**必须明确告诉用户** ——
静默什么都不做的话，用户点完确定、桌面什么都没多出来，只能怀疑是不是坏了。

⚠️ `shortcuts.xml` 必须挂在**应用主 Activity** 上（`MainActivity`），
哪怕快捷方式实际指向 `ShortcutActivity` —— 系统按"应用入口"读那份声明。
挂错地方的表现同样是 `requestPinShortcut` 静默失败。

---

## 6. 图标

| 样式 | 图标从哪来 |
|---|---|
| key / 自定义 key | **一个固定预设**（`generateKeyboardIcon`），用 Canvas 画 |
| Live2D | **模型自带的预览图** |
| 用户自选 | 传上来的图片 |

### ⚠️ key 样式**不**"每份配置一张"，这是踩过两次坑之后的决定

第一版给每份配置画"用它的配色 + 布局"的图标，失败了两次：

1. **全黑**：`colors.keyUp` 的默认值就是黑色（`0x000000`），
   而图标以它作背景 —— 默认配色的配置画出来就是一张黑图，
   连里面的键盘描边都看不清（描边只是把黑微微调亮）；
2. 就算配色解决了，**收益也很低**：key 样式的布局由 `KeyLayout` 固定，
   用户只能改颜色与间距，图标之间的差异小到看不出来。

所以现在共用一个固定预设（深色渐变底 + 三排浅色键帽）。
想要"就长配置那样"的走「传图片」。

### ⚠️ 也不用配置预览截图

配置预览（`ui/preview/ConfigPreview`）是**纯 Compose** 的，要拿它的位图得用
`GraphicsLayer.toImageBitmap` 或套一层 `AndroidView` 手动 measure/layout/draw。
而且 Live2D 样式的预览是**异步**的（缩略图要先解码），钉图标那一刻多半
还没画出来，抓到的是一张空框。

### ⚠️ 图标必须读成位图，不能用 `createWithContentUri`

传 `file://` 的话，读取方是**启动器进程** —— 它没有权限读我们私有目录里的
文件。结果不是崩，而是**白图标**（各家启动器失败表现不一致，
有的干脆显示成默认图标），从代码里完全看不出来。

用 `BitmapFactory.decodeFile` 读成位图，就是把自己这份数据交给系统，
不存在跨进程读权限的问题。

### ⚠️ 图标文件放 `filesDir`，不是 `cacheDir`

快捷方式图标是钉下去那一刻由**系统**读走的 —— 可能在很久以后
（用户重启手机后桌面重新加载图标）。放缓存里会被系统在存储紧张时清掉，
于是变成白图标。

### ⚠️ 图标与名称在钉下去那一刻定下

之后改配置**不会**自动更新。想换只能重新创建一次。界面上有一句说明，别删。

### 用户自传图片

`ShortcutIcons.saveCustomIcon` 会**复制**进私有目录再缩放，不能只记下那个 Uri：
SAF 给的读权限是临时的，重启后失效，而图标是之后才被系统读走的。

传进来的图先**居中裁成正方形**再缩放 —— 图标是方的，直接拉伸会变形。

---

## 7. 重启采集：`getevent` 不能热插拔

**每次经快捷方式启动都重启一次采集**（`restartCapture = true`）。

原因：当前是 `getevent` 通道，它**不能热插拔** —— 插上手柄/键盘后必须
重新扫描设备才能识别，而重新扫描只能走一次完整的停-启。

⚠️ 这里有个容易写错的地方：`CaptureController.start()` 开头是
`if (_enabled.value) return` —— **采集在跑时它什么都不做**。
只调 `start()` 达不到"重扫设备"的目的，用户插上手柄后点快捷方式仍然认不出来。
所以 `OverlayController.enableConfigs` 里在 `start()` 之前先 `stop()`。

**代价**：窗口会有一瞬间全部显示"未按下"（采集停了，按键集合就空了）。
这是 `getevent` 的固有限制。要做到"只重扫设备、不清状态"得改采集层。

而**界面里点开关**传 `restartCapture = false`：那时采集已经在跑或刚起，
没必要重扫，重扫反而会让正在按的键状态丢失。

---

## 8. ⚠️ 协调层必须共用

`OverlayController` 是"打开某几份配置的悬浮窗"的**唯一实现**，
`MainViewModel` 与 `ShortcutActivity` 都走它。

顺序不能乱：

```
1. 检查悬浮窗权限     —— 没有就什么都别做，否则服务起来也画不出窗口
2. 写"启用了哪些配置" —— 服务的真源是这个集合，不是方法的参数
3. 启动悬浮窗服务
4. 启动采集
```

两份实现的下场是"某个入口漏了一步"，而最容易漏的是**第 4 步** ——
表现是"窗口出现了但按键不动"，看起来像采集坏了，其实只是没人去启动它。

⚠️ 第 1 步的权限检查必须在**最前面**：放到后面会出现"偏好写了、
服务起了，但窗口画不出来"，而那个状态很难诊断
（界面显示开关是开的、服务也在跑，就是屏幕上什么都没有）。

成功之后偏好与界面状态是**同一份**，所以"快捷方式开了、界面显示没开"
这种事不会发生 —— 打开应用就能看到那些开关是亮的。

---

## 9. 相关文件

```
shortcut/ShortcutActivity.kt              入口（NoDisplay，转交后立刻结束）
data/shortcut/ShortcutSettings.kt         动作契约 + 配置清单的持久化
data/shortcut/ShortcutPublisher.kt        钉到桌面（requestPinShortcut）
data/shortcut/ShortcutIcons.kt            生成图标（原生 Canvas 画）
capture/OverlayController.kt              协调层（界面与快捷方式共用）
ui/feature/home/ShortcutSetupDialog.kt    主页的创建窗口
ui/feature/home/ShortcutIconPicker.kt     图标选择
res/xml/shortcuts.xml                     快捷方式声明 + icon 兜底
res/values/themes.xml                     Theme.SthKey.Invisible = NoDisplay
```
