# 检查更新方案与测试方法

> 结论先说：**不部署任何服务器**。数据源是仓库里的一个 JSON（走 jsDelivr CDN）+ GitHub Releases API 兜底；
> 发版时由 GitHub Actions 自动把 JSON 写好，日常零维护。本文记录设计取舍和**怎么测**。

---

## 1. 数据源与判定

```
① https://cdn.jsdelivr.net/gh/something-sth/sth-key@master/update/version.json   ← 主
② https://cdn.jsdelivr.net/gh/something-sth/sth-key/update/version.json        ← 主（分支改名兜底）
③ https://api.github.com/repos/something-sth/sth-key/releases/latest           ← 备
```

- ①/② 是同一个文件：显式 `@master` + 不带 ref（跟随默认分支）两种写法，防分支改名。
- ③ 是 GitHub API：国内经常不通，且**未认证限流 60 次/小时/IP**（运营商 NAT 下多用户共享，容易 403）。
  兜底解析时优先从**附件名**拿 `versionCode`（约定 `…_v2.8.8_288.apk`，参考 SukiSU 的做法），
  拿不到再从 tag 推（`v2.8.8` → `2*100+8*10+8 = 288`）。
- 全部失败 → 返回 null：**自动检查静默**（打一条日志），**手动检查**才 toast「检查更新失败，请检查网络」。

判定规则：`versionCode > BuildConfig.VERSION_CODE` 才算新版（整数比较）；
远端没给 code 时退化为版本名逐段数字比较（`2.8.10 > 2.8.9`）。

## 2. 自动检查的时机与开关

| 项 | 取值 | 说明 |
|---|---|---|
| 触发时机 | 进入主界面后（公告弹窗关掉之后） | 不和公告弹窗抢焦点（两个 AlertDialog 同开时部分 ROM 会顶掉先弹的） |
| 冷却 | **24h** | 应用一天会开很多次；不冷却会浪费请求、容易撞 API 限流 |
| 开关 | 「关于 → 更新 → 启动时检查更新」，默认**开** | 关掉后只有手动点「检查更新」才会请求 |
| 跳过版本 | 弹窗点「以后再说」会记住该版本 | 之后**自动检查**不再弹；**手动检查**仍然弹（用户主动要看的） |
| 手动检查 | 「关于 → 检查更新」 | 不受冷却限制；失败/已是最新都用 toast 反馈 |

## 2.5 弹窗长什么样（2026-11-28 定稿）

```
┌────────────────────────────────────────┐
│ 发现新版本 v2.8.8        ← v+版本号用主题色 │
│                                        │
│ sth key v2.8.8已更新      ← Release 正文 │
│ 1.Live2D 纯色背景          原文，保留换行  │
│ …（超出高度可上下滚动，最高 360dp）…       │
│                                        │
│ 以后再说                    前往下载    │ ← 左下 / 右下
└────────────────────────────────────────┘
```

- 版本号带 `v` 前缀、连同 `v` 一起用主题色（与更新公告的写法统一）；
- **不再显示**"当前版本 → 新版本"那一行（版本号在标题里已经有了）；
- 正文是 Release 说明**纯文本**（不解析 Markdown），多段、空行、`-` 列表都按原样显示；
- 正文**必须可滚**：高度上限 360dp，超出时垂直滚动，按钮固定在底部；
- 两个按钮分居**左下 / 右下** —— 所以没有用 Material3 `AlertDialog`（它把按钮固定挤在右下），
  而是自绘 `Dialog + Surface`。

关闭语义：

| 操作 | 行为 |
|---|---|
| 前往下载 | 打开浏览器：优先 APK 直链 → Release 页 → Releases 列表 |
| 以后再说 | 记住这个版本，之后**自动检查**不再提醒；手动检查仍会弹 |
| 点弹窗外部 / 返回键 | 只关掉本次，**不记跳过**（下次仍可能提醒） |

> 已核对用户 GitHub 上现有 Release 正文（v2.8.7 / 2.8.5 / 2.8.3 / 2.8.2 / 2.8.1）：
> 全部是**纯文本**（编号、`-`、空行），没有 Markdown 标题/加粗/链接，所以纯文本显示完全适配。
> 若以后正文改用 Markdown，再决定是否加极简渲染。

## 3. 与 SukiSU 做法的对比（本地源码 `_sukisu/SukiSU-Ultra-main`）

SukiSU 的检查更新在 `manager/.../ui/util/Downloader.kt` 的 `checkNewVersion()`：

| 项 | SukiSU | 本项目 | 为什么不同 |
|---|---|---|---|
| 数据源 | 只有 GitHub API `/releases/latest` | jsDelivr JSON 主 + GitHub API 备 | 本项目用户在国内更集中，`api.github.com` 失败率高；多一个 CDN 源 |
| 版本号来源 | 解析**附件名** `SukiSU_v{ver}_{code}-release.apk` | 主源显式字段；兜底也学它解析附件名 | 附件名解析很聪明（不用额外文件），但它在 API 挂掉时就全挂 |
| 触发 | 主页每次刷新都查 | 进主界面一次 + 24h 冷却 | 本应用打开频率高，每次查既费流量又撞限流 |
| 展示 | 主页内联「发现新版本」卡片 | 弹窗（用户要求） | 用户明确要"有新版本弹窗、没新版 toast" |
| 下载 | 拿 asset 直链 `ACTION_VIEW` 打开 | 同样打开浏览器（优先直链，没有就发布页） | 不自建下载/安装流程 |
| 网络预检 | `isNetworkAvailable()` | 抄了 | 没网时别陪超时 6~8 秒（自动检查在启动路径上） |

> 值得抄的两点已落地：**联网预检**、**附件名解析 versionCode**。

## 4. 发版流程（清单）

1. `app/build.gradle.kts`：`versionCode = 288`、`versionName = "2.8.8"`。
2. 打 tag / 发 Release：`v2.8.8`（tag 必须是三段数字）。
3. Release 附件的 APK **建议命名** `sth-key_v2.8.8_288.apk`（客户端兜底会从名字读版本号；不按也行，只是精度依赖 tag）。
4. GitHub Actions `update/version.json` 工作流会自动：
   - 从 tag 算 `versionCode`；
   - 找第一个 `.apk` 附件写 `downloadUrl`；
   - 把 Release 正文写 `notes`；
   - 提交回 `master`。
5. 等 jsDelivr 缓存刷新（一般几分钟；**不要**发完立刻又改文件）。

## 5. 怎么测试

### 5.1 单元测试（已覆盖，跑 `:app:testDebugUnitTest`）

`UpdateManifestTest`（10 条）：manifest 解析、GitHub Release 解析（附件名/ tag 兜底）、
预发布/草稿被忽略、坏 JSON、`versionCodeFromTag`、版本名比较、`isNewerThan` 两种路径。

### 5.2 端到端（可控数据源，推荐）

检查更新的正确性全在"远端返回什么"，所以给 checker 留了**调试用 URL 覆盖**：
`AppPrefs.updateManifestUrlOverride`（正式包没有入口，debug 包用 `run-as` 写 prefs）。

步骤：

```powershell
# 1) 本机起一个静态服务（PC 上）
cd D:\Project\_tmp\update-server
& $py -m http.server 8765

# 2) 模拟器访问 PC 的地址是 10.0.2.2；把覆盖 URL 写进 debug 包 prefs
#    （脚本：D:\Project\_tmp\set_update_override.py，或手动改 shared_prefs）
py D:\Project\_tmp\set_update_override.py <prefs.xml> "http://10.0.2.2:8765/version.json"
# 3) force-stop 后重启应用，看 关于 → 检查更新 / logcat tag=Update
```

`version.json` 换成不同内容即可构造全部场景：

| 场景 | 服务端返回 | 期望 |
|---|---|---|
| 有新版本 | `{"versionCode":288,"versionName":"2.8.8","notes":"测试说明","pageUrl":"https://example.com","downloadUrl":"https://example.com/a.apk"}` | 启动后弹窗「发现新版本 2.8.8」；「前往下载」打开浏览器 |
| 没新版本 | `{"versionCode":287,"versionName":"2.8.7"}` | 自动：静默；手动：toast「当前已是最新版本」 |
| 版本名比本地大但 code 一样 | `{"versionCode":287,"versionName":"9.9.9"}` | 仍判定"已是最新"（**code 优先**，防止远端手滑写错名） |
| 坏 JSON | `{not json` | 手动：toast「检查更新失败，请检查网络」；自动：静默 |
| 404 / 连不上 | 停掉服务 | 同上（连接被拒/超时路径） |
| 超时 | 服务端 sleep 12s（>8s read timeout） | 同上，且日志有 `java.net.SocketTimeoutException` |
| 跳过此版本 | 弹窗点「以后再说」后重启（同版本） | 自动检查**不弹**；点「检查更新」**仍弹** |
| 开关关闭 | 关于页关掉「启动时检查更新」后重启 | 无任何请求日志 |
| 24h 冷却 | 不改数据源，连续重启两次 | 第二次启动日志里没有请求（或直接跳过） |

### 5.3 真实网络验证（最简单的一条）

把 `versionCode` 临时改成 **286** 构建 debug 包 → 真实数据源（`update/version.json` 里是 287）
会判定"有新版本 2.8.7"→ 启动后弹窗。验完改回 287。

### 5.4 双源回退怎么测

- 用 `updateManifestUrlOverride` 只能测"单源"（覆盖后不再走默认链）；
- 想看"jsDelivr 失败 → GitHub API 成功"，把覆盖 URL 指向**一个返回 404 的本地地址**只能验证失败路径；
  要验证回退，可在 PC 上用一个会改写响应的代理（如 mitmproxy / 本地反向代理）挡掉 jsDelivr，
  或用 `adb shell settings put global http_proxy 10.0.2.2:65533` 让模拟器走 PC 代理，在代理里对
  `cdn.jsdelivr.net` 返回 404 —— 这条路能完整验证 ①失败→②失败→③成功。
  日常不常做，属于"发版前手工抽查一次"。

## 6. 日志与排查

统一 tag `Update`（`AppLog`）。关键日志：

```
检查更新：使用调试数据源 http://10.0.2.2:8765/version.json
检查更新：manifest 命中 2.8.8（288）
检查更新：远端 2.8.8(288)，本地 2.8.7(287)，有新版=true，来源=manifest
检查更新：所有来源都失败（jsDelivr x2 / GitHub API）
检查更新跳过：当前没有可用网络
检查更新：用户跳过 2.8.8
```

`adb logcat | Select-String "Update"` 即可。

## 8. 实测记录（2026-11-28，模拟器 + 本机 HTTP 服务）

| 场景 | 结果 |
|---|---|
| 本地数据源 288 / 本机 287，启动（公告已读） | 弹窗「发现新版本 **v2.8.8**」（版本号主题色）+ Release 正文；按钮「以后再说」左下、「前往下载」右下 |
| 长正文（多段 + 空行 + 列表） | 正文区域可上下滚动，滚到底可见最后一段，按钮始终贴在底部 |
| 点「以后再说」 | `update_skipped_version=2.8.8` 落盘；重启后自动检查仍会请求，但日志 `已被用户跳过，不弹窗`、**不弹窗** |
| 跳过后手动「检查更新」 | 仍然弹窗（手动不受跳过影响） |
| 本地数据源 287 / 本机 287，手动检查 | toast「当前已是最新版本」 |
| 停掉本地服务，手动检查 | toast「检查更新失败，请检查网络」（自动检查则静默，只记日志） |
| 关闭「启动时检查更新」后重启 | 完全没有请求日志（0 条 Update 日志） |
| **真实网络（不设覆盖 URL）** | jsDelivr 两个地址都返回 **HTTP 403**（远端仓库此时还没有 `update/version.json`）→ **自动回落到 GitHub API 成功**：`GitHub Release 命中 2.8.7（287）` → 判定"已是最新"，静默。**双源回退在真实网络下验证通过** |

> 两个只有在测试里才会踩到的坑：
> 1. **明文 HTTP**：debug 包默认禁止 cleartext，指向 `http://10.0.2.2:8765` 会直接失败；
>    已在 `app/src/debug/AndroidManifest.xml` 里加 `android:usesCleartextTraffic="true"`（仅 debug）。
> 2. **jsDelivr 403 ≠ 代码错**：仓库里没有那个文件时 jsDelivr 返回的是 403（消息是
>    "Couldn't find the requested file"），不是 404。首次把 `update/version.json`
>    提交进远端 `master` 之后才会生效。

## 9. 已知的坑 / 以后可优化

| 项 | 说明 |
|---|---|
| jsDelivr 缓存 | 发版后 `version.json` 一般几分钟才生效；急着验证可用 GitHub API 兜底路径或等缓存 |
| `versionCode` 编码碰撞 | `major*100+minor*10+patch` 在 minor/patch ≥10 时会撞号；真相源是 `version.json` 的显式字段，tag 推导只是兜底 |
| 手动检查没有"下载进度" | 下载交给浏览器（`ACTION_VIEW`），本应用不申请安装包权限 |
| 后续可选 | Gitee 镜像源（国内最稳，但要实名 + 同步 release）；`version.json` 加 `minSupportedVersion` 做强制更新 |
