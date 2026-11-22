# Live2D 本地渲染验证台

> 只用于**开发验证**，不进 APK、不参与构建。
>
> 作用：把任意 `*.model3.json` 模型目录变成一个自包含的 HTML 页面，
> 用桌面 Chrome（无头）跑 Axon 的 `custom/runtime.js`，**在不用手机/模拟器的情况下**
> 验证「配置解析 + 物理 + 动作 + 表情」是否正常。

---

## 为什么需要它

Android 侧的验证链路很长：装包 → 建配置 → 选 LIVE2D → 导入 → 开悬浮窗 → 看画面。
而 P1/P2 阶段改的其实是 **`__AXON_STYLE_CONFIG__` 这个配置对象**——
只要页面能在 Chrome 里渲染出来，就说明配置解析是对的。

实测这个办法很有效：`鸣潮卡提希娅` 的 8192 贴图模型、`星野桌宠猫猫2K` 的多层目录模型
都是在 Chrome 里先跑通、再进 Android 的。

---

## 用法

```powershell
$py = "<python.exe>"          # 任意带 Pillow 的 Python
$axon = "D:\Project\_axon\Axon-Input-main\app\src\main\assets\bongocat"

$env:AXON_BONGOCAT_ASSETS = $axon
& $py tools\live2d-harness\build.py `
    "D:\...\img\keyboard\cat_model\cat.model3.json" `
    "D:\...\harness\kati_keyboard" `
    --design 900x1200
```

产物：`index.html` / `config.js`（`window.__AXON_STYLE_CONFIG__`）/ `core.js` / `runtime.js` / `probe.js` / `tex/*`

- 贴图超过 48M 像素会**统一按 2 的幂降采样**（与 App 的 `TextureBudget` 一致），存到 `tex/`；
- `probe.js` 每 0.5 秒把 `debugState()` 写进 `<title>`，方便无头模式抓状态。

## 渲染 + 抓状态

```powershell
$chrome = "C:\Program Files\Google\Chrome\Application\chrome.exe"

# 截图（--virtual-time-budget 让 rAF 跑够时间）
& $chrome --headless=new --no-sandbox `
  --enable-unsafe-swiftshader --use-angle=swiftshader `
  --allow-file-access-from-files --window-size=900,1200 `
  --virtual-time-budget=10000 `
  --screenshot="...\harness\kati_keyboard\shot.png" `
  "file:///.../harness/kati_keyboard/index.html"

# 取 debugState（title 里是 JSON）
& $chrome --headless=new --no-sandbox --enable-unsafe-swiftshader --use-angle=swiftshader `
  --allow-file-access-from-files --window-size=900,1200 --virtual-time-budget=8000 `
  --dump-dom "file:///.../harness/kati_keyboard/index.html" 2>$null | Select-String "PROBE"
```

⚠️ 页面必须保留 `#background / #fallback / #leftKey / #rightKey` 四个元素：
runtime 会对它们 `classList`，缺一个就抛 `Cannot read properties of null`，画面全空。

## 用**我们自己的** runtime 验证（build_sthkey.py）

`build.py` 建的是 Axon custom runtime 的对照页；自研功能（物理/动作）要验的是项目自己的
`app/src/main/assets/bongocat/keyboard/runtime.js`：

```powershell
& $py tools\live2d-harness\build_sthkey.py `
    "D:\...\cat.model3.json" "D:\...\harness\sthkey_kati" --design 900x1200
```

- 产出 `modeldata.js`（`__BONGO_*` 格式，含 `__BONGO_PHYSICS`）；
- 打开 `__BONGO_DEBUG` 后，页面会把 renderer 挂到 `window.__STHKEY_RENDERER`，
  `probe_sthkey.js` 会报告 `ParamAngleX`、`hair1` 等参数值，用来确认物理输出非 0 且随输入变化；
- 探针每 250ms 自动 `mouseDelta(90,0)` 推指针，模拟"鼠标移动 → 头发摆动"。

## 已验证（2026-11-22）

| 模型 | ready | 参数 | physics | motions | expressions | 贴图 |
|---|---|---|---|---|---|---|
| 星野 2K（standard） | ✅ | 41 | 1 | 0 | 8 | 2048²（不降） |
| 卡提希娅（keyboard） | ✅ | 102 | 13 | 19 | 4 | 100.7M → 25M px（1/2） |

动画确认：同一页面在 `virtual-time-budget=900` 与 `7000` 各截一帧，
模型区域有 **24195** 个像素不同（呼吸/物理/眨眼在动），不是静态图。

## 局限

- SwiftShader 软渲染：**只能验正确性，不能验帧率**；
- 只覆盖 JS/WebGL 层，SAF 导入、权限、窗口尺寸这些仍要在模拟器/真机验。
