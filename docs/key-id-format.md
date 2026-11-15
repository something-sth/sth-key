# 键位 ID 格式（跨平台契约）

> 2026-11-15 起，配置里的键位绑定**不再写 Linux evdev 码**，改写成明文 ID。
> 本文是 Android / 后续 Windows 版本共用的契约。**ID 一旦发布就不能改。**

---

## 1. 为什么

| 旧（数字） | 新（明文 ID） |
|---|---|
| `"inputKeyCodes": [272]` | `"inputKeyCodes": ["MOUSE_LMB"]` |
| 只有 Linux/Android 看得懂 | 任何平台都能读 |
| 手柄摇杆方向没有键码，只能造假码 | 直接 `JOYSTICK_LEFT_UP` |
| 看配置/对比备份全是数字 | 人和 diff 都能读 |

运行时仍然把 ID 转回 **evdev `Int`**（采集、`pressedCodes`、CPS 都按整数跑）。
Windows 端同理：自己的 `ID -> VK / 扫描码` 表，读同一份 JSON。

---

## 2. 读取规则（兼容老配置）

`inputKeyCodes` 数组的元素**可以是数字，也可以是字符串**：

```json
["KEY_A", 304, "GAMEPAD_B"]
```

| 元素 | 解释 |
|---|---|
| `30`（数字） | 老配置的 evdev 码；先做旧伪码迁移（768/769 → 312/313）再使用 |
| `"KEY_A"`（字符串） | 规范 ID，按本文表解析 |
| 不认识的字符串 | 跳过并记日志（**不猜、不置 0**）|

**写出去永远是字符串。** 新版本保存一次后，老数字就被替换成 ID。

⚠️ 因此新格式**单向兼容**：旧版本 App 读新配置时
`optInt` 会得到 0，键位绑定失效。降级前请备份/重新配置。

---

## 3. 命名空间

统一前缀，避免同名冲突（键盘 A 与手柄 A 是两个 ID）：

| 前缀 | 类别 | 例子 |
|---|---|---|
| `KEY_` | 键盘 | `KEY_A`、`KEY_1`、`KEY_F1`、`KEY_MINUS`、`KEY_LEFT_CTRL`、`KEY_KP_0` |
| `MOUSE_` | 鼠标 | `MOUSE_LMB`、`MOUSE_RMB`、`MOUSE_MMB`、`MOUSE_SIDE`、`MOUSE_EXTRA` |
| `GAMEPAD_` | 手柄按键（含十字键/摇杆按下） | `GAMEPAD_A`、`GAMEPAD_LB`、`GAMEPAD_LT`、`GAMEPAD_DPAD_UP`、`GAMEPAD_LS` |
| `JOYSTICK_` | 手柄摇杆八段式 | `JOYSTICK_LEFT_UP`、`JOYSTICK_RIGHT_DOWN_LEFT` |
| `EVDEV_` | 未知/厂商自定义键 | `EVDEV_514` |

### 3.1 键盘 `KEY_*`

- 字母：`KEY_A` … `KEY_Z`
- 数字行：`KEY_1` … `KEY_9`、`KEY_0`
- 符号：`KEY_MINUS`、`KEY_EQUAL`、`KEY_LEFT_BRACKET`、`KEY_RIGHT_BRACKET`、
  `KEY_SEMICOLON`、`KEY_APOSTROPHE`、`KEY_GRAVE`、`KEY_BACKSLASH`、
  `KEY_COMMA`、`KEY_DOT`、`KEY_SLASH`
- 功能键：`KEY_ESC`、`KEY_BACKSPACE`、`KEY_TAB`、`KEY_ENTER`、`KEY_SPACE`、
  `KEY_CAPS_LOCK`、`KEY_SYSRQ`、`KEY_SCROLL_LOCK`、`KEY_PAUSE`、`KEY_F1` … `KEY_F12`
- 修饰键：`KEY_CTRL_LEFT/RIGHT`、`KEY_SHIFT_LEFT/RIGHT`、`KEY_ALT_LEFT/RIGHT`、
  `KEY_META_LEFT/RIGHT`
- 导航：`KEY_UP/DOWN/LEFT/RIGHT`、`KEY_HOME`、`KEY_END`、`KEY_PAGE_UP`、
  `KEY_PAGE_DOWN`、`KEY_INSERT`、`KEY_DELETE`
- 小键盘：`KEY_NUM_LOCK`、`KEY_KP_DIVIDE`、`KEY_KP_MULTIPLY`、`KEY_KP_MINUS`、
  `KEY_KP_PLUS`、`KEY_KP_ENTER`、`KEY_KP_0` … `KEY_KP_9`、`KEY_KP_DOT`

### 3.2 鼠标 / 手柄

| 显示 | ID |
|---|---|
| LMB / RMB / MMB | `MOUSE_LMB` / `MOUSE_RMB` / `MOUSE_MMB` |
| 鼠标侧键 / 扩展键 | `MOUSE_SIDE` / `MOUSE_EXTRA` |
| 手柄 A / B / X / Y | `GAMEPAD_A` / `GAMEPAD_B` / `GAMEPAD_X` / `GAMEPAD_Y` |
| LB / RB / LT / RT | `GAMEPAD_LB` / `GAMEPAD_RB` / `GAMEPAD_LT` / `GAMEPAD_RT` |
| BACK / START / GUIDE | `GAMEPAD_BACK` / `GAMEPAD_START` / `GAMEPAD_GUIDE` |
| 左/右摇杆按下 | `GAMEPAD_LS` / `GAMEPAD_RS` |
| 十字键 | `GAMEPAD_DPAD_UP/DOWN/LEFT/RIGHT` |

### 3.3 摇杆八段式 `JOYSTICK_*`

| 方向 | 左摇杆 | 右摇杆 |
|---|---|---|
| ↑ 上 | `JOYSTICK_LEFT_UP` | `JOYSTICK_RIGHT_UP` |
| ↓ 下 | `JOYSTICK_LEFT_DOWN` | `JOYSTICK_RIGHT_DOWN` |
| ← 左 | `JOYSTICK_LEFT_LEFT` | `JOYSTICK_RIGHT_LEFT` |
| → 右 | `JOYSTICK_LEFT_RIGHT` | `JOYSTICK_RIGHT_RIGHT` |
| ↖ 左上 | `JOYSTICK_LEFT_UP_LEFT` | `JOYSTICK_RIGHT_UP_LEFT` |
| ↗ 右上 | `JOYSTICK_LEFT_UP_RIGHT` | `JOYSTICK_RIGHT_UP_RIGHT` |
| ↙ 左下 | `JOYSTICK_LEFT_DOWN_LEFT` | `JOYSTICK_RIGHT_DOWN_LEFT` |
| ↘ 右下 | `JOYSTICK_LEFT_DOWN_RIGHT` | `JOYSTICK_RIGHT_DOWN_RIGHT` |

⚠️ 方向是 **45° 八扇区**：斜推只命中斜向，不会同时触发两个正向。
JSON 里**只有这些 ID**；`0x400` 这类内部码永远不会出现在配置里。

---

## 4. 哪些字段用这套 ID

| 文件 | 字段 |
|---|---|
| `params.json` → `keyMappings[]` | `inputKeyCodes` |
| `params.json` → `custom.components[]`（按键组件） | `inputKeyCodes` |
| `params.json` → `custom.components[]`（文本组件） | `cpsKeyCodes`、`cpsKeyCodesPerPlaceholder` |
| `params.json` → `custom.components[]`（摇杆组件） | `inputKeyCodes`（四向，二维数组） |

示例：

```json
{
  "keyMappings": [
    { "id": "W", "displayText": "W", "inputKeyCodes": ["KEY_W"] },
    { "id": "LMB", "displayText": "LMB", "inputKeyCodes": ["MOUSE_LMB"] }
  ],
  "custom": {
    "components": [
      {
        "type": "key",
        "label": "W",
        "inputKeyCodes": ["KEY_W", "JOYSTICK_LEFT_UP"]
      },
      {
        "type": "text",
        "text": "(cps)",
        "cpsKeyCodesPerPlaceholder": [["GAMEPAD_A", "JOYSTICK_RIGHT_DOWN"]]
      }
    ]
  }
}
```

---

## 5. 规则与边界

- **ID 冻结**：显示名可以改（"手柄A" → "A 键"），ID 不能改。
- **不认显示名**：`"A"` 有歧义（键盘/手柄），解码器不会猜；只认规范 ID 与 `EVDEV_<码>`。
- **未知键**：编码成 `EVDEV_<evdev码>`，能原样往返；Windows 端可选择忽略。
- **摇杆方向只属于自定义 Key 的按键/文本组件**：
  - 摇杆组件的四向键位、以及 Key/Gamepad 样式的 `keyMappings` 都会在解码时过滤掉 `JOYSTICK_*`；
  - 因为这些地方没有"方向"的语义。
- **旧扳机伪码**：数字 `768/769` 读入时迁成 `GAMEPAD_LT` / `GAMEPAD_RT`，写出也是后者。
- **测试**：`KeyIdsTest`（覆盖全部可选键、唯一性、往返、摇杆区间）、
  `KeyIdCodecRoundTripTest`（四个字段的明文往返 + 老数字兼容）、
  `KeyCodeJsonTest`（数字/字符串混读）。

---

## 6. Windows 端怎么用

给每个 ID 建一张 `ID -> 平台码` 表（VK / 扫描码 / XInput 按钮位）：

| ID | Windows |
|---|---|
| `KEY_A` | `VK_A`（0x41） |
| `MOUSE_LMB` | `VK_LBUTTON` |
| `GAMEPAD_A` | XInput `XINPUT_GAMEPAD_A` |
| `JOYSTICK_LEFT_UP` | 左摇杆 Y 轴 < -0.5 |
| `EVDEV_514` | 不认识就忽略 |

`JOYSTICK_*` 没有平台键码，按**轴 + 阈值 + 八扇区**算，与 Android 端一致
（`StickDirectionCodes.ENTER_MAGNITUDE = 0.5`）。
