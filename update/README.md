# 检查更新的数据源

应用更新检查**不需要服务器**，按顺序读两个来源（见 `UpdateChecker.kt`）：

1. **本目录的 `version.json`**（主来源，走 jsDelivr CDN）：
   `https://cdn.jsdelivr.net/gh/something-sth/sth-key@master/update/version.json`
   - 无 API 限流、国内相对可用（`api.github.com` 经常不通，且未认证只有 60 次/小时/IP）。
2. **GitHub Releases API**（备用）：`/repos/something-sth/sth-key/releases/latest`
   - 从 Release 附件名里解析版本号（约定见下），拿不到再从 tag 推。

两个来源都失败时：**自动检查静默、手动检查提示网络问题**。

## 发版时必须做的事（任选其一，第一种是自动的）

### 自动（推荐）：GitHub Actions 在发布 Release 时更新本文件

`.github/workflows/update-version-json.yml` 在 **Release published** 时自动：

- 从 tag 解析 `versionCode`（`v2.8.8` → `288`，规则是 `major*100 + minor*10 + patch`）；
- 找到 Release 里第一个 `.apk` 附件，写进 `downloadUrl`；
- 把 Release 正文写进 `notes`；
- 生成 `update/version.json` 并提交回默认分支（`master`）。

提交后 jsDelivr 一般几分钟内生效（有缓存），所以**发版后不要立刻改文件再发一次**。

### 手动：发完 Release 后自己改这个文件

```json
{
  "versionCode": 288,
  "versionName": "2.8.8",
  "tag": "v2.8.8",
  "pageUrl": "https://github.com/something-sth/sth-key/releases/tag/v2.8.8",
  "downloadUrl": "https://github.com/something-sth/sth-key/releases/download/v2.8.8/sthkey-2.8.8.apk",
  "notes": "更新说明……",
  "updatedAt": "2026-11-28T00:00:00Z"
}
```

## 附件命名约定（可选但强烈建议）

客户端在 GitHub API 兜底时会从**附件名**里读版本号，格式为：

```
任意名字_v{版本名}_{versionCode}.apk
例如：sth-key_v2.8.8_288.apk
```

不按这个命名也能用（会从 tag 推 versionCode），只是精度依赖 tag。

## 冷知识：为什么不用「自建服务器」

这个方案的维护成本只有**一个 JSON 文件**（而且 Actions 自动写），
没有服务器要部署、没有证书要续、没有可用性要盯。国内网络确实不是 100%，
但失败是**静默**的：不会打扰用户，用户也能从「关于 → GitHub 开源仓库」自己去看。
