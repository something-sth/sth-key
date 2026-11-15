# sth key · 应用图标（MinecraftAE / key / 冷调玫瑰）

## 设计参数

| 项 | 值 |
|---|---|
| 字形 | 应用自带 `assets/fonts/Minecraft AE.ttf`，小写 `key` |
| 字宽 | 240 / 512（约 47%；自适应 108 下约 50.6dp，落在 66dp 安全区内） |
| 背景 | `#F9DCEA → #E3A6C6`（垂直）；这是 1 冷调玫瑰的**加深版**，保证真机图标不发白 |
| 文字 | `#FFFFFF → #FFF0F6` 极轻垂直渐变 |
| 阴影 | `#3B1F33` 柔光 + 8 向 4px 极细描边（512 画布），让 48px 仍能读出白字 |
| 高光/暗部 | 左上白色径向高光；底部 5.5% 同色暗部 |
| 圆角 | 115 / 512（预览与旧版图标；自适应由系统 mask） |

## 文件

| 文件 | 用途 |
|---|---|
| `final-preview.png` | 最终效果（512 / 96 / 48） |
| `actual-android.png` | 模拟器「应用信息」里的实际自适应图标（放大） |
| `key-512.png` | 512 预览 / 商店用 |
| `key-square.svg` | 圆角方形设计稿 |
| `key-round.svg` | 圆形设计稿 |
| `key-fg.svg` | 透明前景（文字 + 阴影），用于自适应前台位图 |
| `ic_launcher_background.xml` | Android 自适应背景矢量 |
| `ic_launcher_monochrome.xml` | Android 13 主题单色图标 |
| `transform.json` | 字形包围盒与缩放/平移 |

## Android 资源

```
res/drawable/ic_launcher_background.xml     渐变背景（108）
res/drawable/ic_launcher_monochrome.xml     单色 key（108）
res/mipmap-*/ic_launcher_foreground.webp    自适应前景（108/162/216/324/432，带透明）
res/mipmap-anydpi-v26/ic_launcher.xml       adaptive-icon
res/mipmap-anydpi-v26/ic_launcher_round.xml adaptive-icon
```

⚠️ **旧版 `mipmap-*/ic_launcher.webp` / `ic_launcher_round.webp` 已删除**：
minSdk 28，所有设备都走 adaptive icon，不再需要位图 fallback；
也避免了「某些 ROM 优先显示旧位图」的不一致。

## 关于浅色 / 深色

启动器图标是**静态资源**，Android 没有官方的「浅色模式图标 / 深色模式图标」两套切换。
`-night` 资源可以被部分启动器选中，但缓存与 OEM 行为不一致，不可靠。
所以最终只用一张 **1 冷调玫瑰的加深版**：
它在浅色桌面上不脏，在深色桌面上也能立住；不依赖系统主题切换。

## 字体

字形来自项目已自带的 `Minecraft AE.ttf`；这里只把 `key` 转成 path，
没有在图标里再打包一份字体。
