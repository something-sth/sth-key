package com.something.sthkey.domain.custom

import com.something.sthkey.capture.MouseMotion
import com.something.sthkey.domain.keys.KeyCodes
import kotlin.math.hypot

/*
 * ============================================================
 * 摇杆的**输入源归一化**（"三层分离"里的第一层）
 * ============================================================
 * 用户给的参考实现里那句话就是这一整个文件存在的理由:
 *
 * > 第一层:输入采集层 —— 把键盘、鼠标、手柄、触屏这四种完全不同的输入源，
 * > 全部转换成统一的 `(x, y)` 二维向量（范围 -1 到 1）
 * > 第二层:显示面板层 —— 拿到 `(x, y)` 后，根据当前"移动样式"决定怎么表现
 *
 * ⚠️ 本项目的**第二层早就是那个形状**了 ——
 * [com.something.sthkey.ui.overlay.gamepad.Joystick] 只吃 `(x, y)` + 外观，
 * 内部自己跑弹簧。所以这里只要把三种输入源都算成 `(x, y)`，
 * 渲染与动画**一行都不用改**。
 *
 * ============================================================
 * ⚠️⚠️ 坐标系:X 向右为正，**Y 向下为正**（屏幕坐标）
 * ============================================================
 * 与 [com.something.sthkey.capture.StickState] 的约定**完全一致** ——
 * 那份文件里记着一次真实的翻车:
 *
 * > ⚠️ **这里踩过坑**:第一版文件写的是"Y 向上为正"，渲染层于是按那个约定
 * > 取了反 —— 结果**两个摇杆的 Y 都反了**（往上推、拇指往下走）。
 *
 * ⚠️ 所以本文件里"上"永远是 **`-1`**。下面每一处取反都写明了为什么，
 * 不要再"顺手改成直觉上的正数"。
 */

/**
 * 键盘 WASD → 摇杆偏移（**八段式**）。
 *
 * ============================================================
 * 为什么是"八段"而不是连续量
 * ============================================================
 * 用户的原话:"键盘摇杆其实就是检测 WASD，然后**分八段式**，决定摇杆帽方向而已"。
 *
 * ⚠️ 键盘只有"按下/抬起"两态，**没有"推了一半"** ——
 * 所以方向只有 8 个:四个正方向 + 四个斜向。这也是为什么
 * [KeyboardJoystickFeel] 里没有"死区"（死区是给会漂移的连续轴用的）。
 *
 * | 按住的键 | 方向 | 结果 |
 * |---|---|---|
 * | 一个 | 上 / 下 / 左 / 右 | `(0,-1)` / `(0,1)` / `(-1,0)` / `(1,0)` |
 * | 相邻两个 | 四个斜向 | `(±0.707, ±0.707)` |
 * | **相对两个**（如 W+S） | 互相抵消 | `(0, 0)` |
 *
 * ⚠️ 斜向乘 `0.707`（`√2/2`）是**归一化**，不是随手取的数:
 * 不乘的话 `(1,1)` 的长度是 `1.414` —— 斜推比直推"推得更远"，
 * 帽会跑出底盘。与 [com.something.sthkey.capture.StickState.leftMagnitude]
 * 里那个 `min(1f, hypot)` 是同一个问题的两种防法（那边是夹住，这边是归一）。
 *
 * @param pressedCodes 当前按住的键码（evdev），见 [KeyCodes]
 * @param keys 四个方向**各自的一组键**，顺序是 [JoystickDirection] 的声明顺序
 *   （上 / 左 / 下 / 右）。⚠️ **不写死 WASD** —— 用户可能用别的键
 * @param threshold 触发阈值，`0.5f` = 严格八段式
 */
fun keyboardJoystickVector(
    pressedCodes: Set<Int>,
    keys: List<List<Int>> = DEFAULT_KEYBOARD_JOYSTICK_KEYS,
    threshold: Float = 0.5f,
): Pair<Float, Float> {
    /*
     * ⚠️ 每一向**任一键按下就算这一向按了** —— 那是"绑多个键"的语义:
     * 比如左右 Shift 是两个键码，两个都绑上就都能触发"上"。
     *
     * ⚠️ 用 `getOrNull` 而不是 `keys[0]` —— 存档里那个数组可能是**残缺的**
     * （手改过的 JSON、或者来自更新版本的配置）。缺的那一向就当"没绑键"
     * （永远不触发），而不是让渲染抛 `IndexOutOfBoundsException`。
     *
     * ⚠️ 与按键组件"没绑定就不亮"同一个取舍。
     */
    fun held(index: Int): Float {
        val group = keys.getOrNull(index) ?: return 0f
        return if (group.any { it in pressedCodes }) 1f else 0f
    }

    val w = held(0)
    val a = held(1)
    val s = held(2)
    val d = held(3)

    /*
     * ⚠️ **Y 轴要取反** —— 按 W 是"往上"，而本坐标系里"上"是 `-1`
     * （屏幕坐标，见文件头）。写成 `w - s` 的话整个 Y 会反，
     * 表现就是"按 W 帽子往下跑" —— 正是 StickState 里记的那个坑。
     */
    val rawY = s - w
    val rawX = d - a

    if (rawX == 0f && rawY == 0f) return 0f to 0f

    /*
     * 斜向归一化。`hypot` 而不是 `sqrt(x*x + y*y)`:后者在数值上会溢出，
     * 而项目里 [com.something.sthkey.capture.StickState] 也是用 `hypot`。
     */
    val length = hypot(rawX, rawY)
    val nx = rawX / length
    val ny = rawY / length

    /*
     * ⚠️ 阈值只对**斜向**有意义。
     *
     * 八段式的方向是离散的:按了 W 就是"上"，不存在"推了 70% 的上"。
     * 所以阈值的唯一作用是"两个方向都按住时，算不算斜向" ——
     * 这也是为什么它的取值范围是 `0.05f .. 1f` 而不是随便一个放大倍率
     * （见 [KeyboardJoystickFeel.threshold]）。
     *
     * ⚠️ 现在是"**归一化之后**再按阈值缩小长度"，而不是"低于阈值就不算"：
     * 后者会让用户把滑块拉到 1.0 时什么都点不亮（而那是合法的极端设置）。
     * 这里保证**方向永远对**，阈值只改"推得多满"。
     */
    val clamped = threshold.coerceIn(0.05f, 1f)
    return (nx * clamped) to (ny * clamped)
}

/**
 * 鼠标位移 → 摇杆偏移（**纯累加，没有回中**）。
 *
 * ============================================================
 * 做法与参考实现、以及 axon 完全一致
 * ============================================================
 * ```
 * 鼠标在动:  accum += 本次增量 × 灵敏度，夹到 ±1
 * 鼠标停下:  accum **停在原地**
 * ```
 *
 * ⚠️ 帽子看起来仍然平滑，是因为**外层的弹簧**在追这个 target
 * （`JoystickSlot` → `Joystick`），与这里无关。
 *
 * ============================================================
 * ⚠️⚠️ 曾经有"自动回中"，已经删除（不要再加回来）
 * ============================================================
 * 用户的原话:
 *
 * > 算了，我们还是不做回中了，清理一下吧，回中等有精力再做
 *
 * ⚠️ 删掉不是因为不需要，而是**它把整个组件弄坏了** —— 回中是纯时间驱动的
 * （鼠标停下后没有任何外部事件能推进它），必须靠一个帧循环，而那个循环
 * 试了**四版**都没做稳，最后一版甚至让摇杆**彻底不动**。
 *
 * ⚠️ 所以这里刻意保持**无状态、纯函数式**:
 *
 * | 做法 | 为什么 |
 * |---|---|
 * | 不存"上次位移的时刻" | 不需要它 —— 没有回中就没有时间的概念 |
 * | 不做衰减 | 衰减就是回中 |
 * | **不启动任何帧循环** | 帧循环要跨重组存活，那是四版失败的共同根源 |
 *
 * ⚠️ 状态只有"已处理到哪"（[seenDx]/[seenDy]）与"现在偏到哪"（[target]），
 * 两者都由**调用方持有**（`remember`），这个函数本身没有任何副作用。
 *
 * ============================================================
 * ⚠️ 参数是**累计量**，不是增量
 * ============================================================
 * [MouseMotion] 是"自窗口创建以来的净位移"（见那个类的说明）。
 * 所以这里做**差分**，而不是直接累加 —— 直接累加会让值一路涨到天上、
 * 帽子永远贴在边缘。
 *
 * ⚠️ 差分也意味着**丢中间帧不会丢位移**:掉帧时差值自然变大，
 * 位置仍然正确。
 *
 * @param motion 位移流的最新值（累计量）
 * @param seen 上一次处理到的累计量（调用方持有）
 * @param target 当前的偏移（调用方持有）
 * @return 新的 `(seen, target)`
 */
fun mouseJoystickStep(
    motion: MouseMotion,
    seen: MouseMotion,
    target: Pair<Float, Float>,
    feel: MouseJoystickFeel,
): Pair<MouseMotion, Pair<Float, Float>> {
    val dx = motion.dx - seen.dx
    val dy = motion.dy - seen.dy

    /* ⚠️ 没有新位移就原样返回 —— 别白算，也别让 Compose 无谓重组 */
    if (dx == 0L && dy == 0L) return seen to target

    val sensitivity = feel.sensitivity.coerceIn(
        JOYSTICK_SENSITIVITY_MIN,
        JOYSTICK_SENSITIVITY_MAX,
    )

    /*
     * ⚠️ 坐标**不取反** —— [MouseMotion] 的 dy 是"屏幕向下为正"
     * （与 evdev 的 `REL_Y` 一致），而本坐标系也是"下为正"。
     * 取反的话鼠标往下移帽子会往上跑。
     *
     * ⚠️ 夹到 ±1:一是帽子不能出底盘，二是"灵敏度"这个滑块才有个上限
     * 可言（不夹的话一直推鼠标，累积值会无限大）。
     */
    val nx = (target.first + dx * sensitivity).coerceIn(-1f, 1f)
    val ny = (target.second + dy * sensitivity).coerceIn(-1f, 1f)

    return motion to (nx to ny)
}