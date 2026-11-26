package com.something.sthkey.ui.component

import android.view.KeyEvent as AndroidKeyEvent
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.something.sthkey.domain.keys.KeyCodes

/**
 * 采集式按键绑定对话框。
 *
 * ============================================================
 * 为什么不复用原来的"手动选择"对话框
 * ============================================================
 * 手动选择要点三层（大类 → 按键 → 确定），绑一个键要点四五下；
 * 而"按一下要绑的键"是用户天然的直觉。这里用**对话框内拦截**实现：
 *
 * - 键盘 / 手柄按键：`onPreviewKeyEvent` 直接拿到，**全部消费**，
 *   所以 ESC、Back 不会关掉对话框，而是被当成可绑定的键；
 * - 鼠标左右中键：`pointerInput` 读 `PointerEvent.buttons`；
 * - **触摸一律忽略**：`PointerType.Touch` / `Stylus` 直接跳过，
 *   手指点采集区不会有任何反应（用户明确要求，避免误绑）。
 *
 * 摇杆 / 扳机是 `EV_ABS` 轴，不是焦点窗口能拿到的按键事件，
 * 所以这一类仍然走「手动选择」。
 */
@Composable
fun KeyCaptureDialog(
    title: String,
    initialCodes: List<Int>,
    onDismiss: () -> Unit,
    onConfirm: (List<Int>) -> Unit,
    /** 打开原来的手动选择对话框（处理摇杆/扳机等采集不到的输入） */
    onManual: () -> Unit,
) {
    var codes by remember(initialCodes) { mutableStateOf(initialCodes) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        /*
         * ⚠️ 必须等**第一帧之后**再请求焦点：
         * 对话框刚创建时内容还没 attach，立即 requestFocus 会失败，
         * 于是第一下按的 Esc / 小键盘这类键会被窗口吃掉（用户实测的
         * "空的采集区按 Esc 绑不上，先绑一个字母就好了"）。
         */
        withFrameNanos { }
        runCatching { focusRequester.requestFocus() }
    }

    Dialog(
        onDismissRequest = { /* 不响应外部关闭：只能点按钮 */ },
        properties = DialogProperties(
            dismissOnBackPress = false,
            usePlatformDefaultWidth = true,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(24.dp))
                .padding(20.dp)
                /* 焦点和按键处理放在**根节点**：内部任何元素抢走焦点都不影响采集 */
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown) {
                        val native = event.nativeKeyEvent
                        val code = AndroidKeyCodes.toAppCode(native.keyCode, native.source)
                        if (code != null) codes = addCode(codes, code)
                    }
                    /* 全部消费：ESC/Back 也不能穿透去关对话框 */
                    true
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(14.dp))

            /*
             * 采集区：可聚焦 + 拦截键盘；鼠标按键走 pointerInput。
             *
             * ⚠️ 触摸不消费、不绑定：pointerInput 里按 pointerType 过滤。
             */
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerHighest,
                        RoundedCornerShape(16.dp),
                    )
                    .pointerInput(Unit) {
                        /* 鼠标按键交给下面的 AndroidView 处理；这里只负责保持焦点 */
                        awaitPointerEventScope {
                            while (true) awaitPointerEvent(PointerEventPass.Initial)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                /*
                 * 鼠标左右中键：Compose 的 PointerButtons 在**当前版本里
                 * packedValue 是 internal**，拿不到具体按键，只能借用 Android View
                 * 的 MotionEvent（buttonState / actionButton）来区分。
                 *
                 * ⚠️ 只处理 `SOURCE_MOUSE`：触摸、手写笔一律放行 → 采集区对触摸"没反应"。
                 */
                MouseButtonCatcher(
                    modifier = Modifier.matchParentSize(),
                    onButton = { code -> codes = addCode(codes, code) },
                )

                Text(
                    text = if (codes.isEmpty()) "按下要绑定的键（鼠标按键也可以）"
                    else codes.joinToString(" + ") { KeyCodes.displayName(it) },
                    style = if (codes.isEmpty()) MaterialTheme.typography.bodyMedium
                    else MaterialTheme.typography.titleLarge,
                    fontWeight = if (codes.isEmpty()) FontWeight.Normal else FontWeight.SemiBold,
                    color = if (codes.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "触摸屏幕不会绑定；键盘、鼠标左右中键、手柄按键都可以",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = { codes = emptyList() }) { Text("重置") }
                OutlinedButton(onClick = onManual) { Text("手动选择") }
            }

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text("取消") }
                TextButton(onClick = { onConfirm(codes) }) { Text("保存") }
            }
        }
    }
}

/** 组合键：同一个键不重复；最多 4 个（再多就不像"组合"了） */
private fun addCode(current: List<Int>, code: Int): List<Int> {
    if (current.contains(code)) return current
    if (current.size >= 4) return current
    return current + code
}

/**
 * Android `KeyEvent.keyCode` → 项目内部的 evdev 键码。
 *
 * ⚠️ 两套编码**不一样**：Android 的 `KEYCODE_A = 29`，而 Linux evdev 的
 * `KEY_A = 30`。绑定存的是 evdev（getevent 那条链路），所以这里必须转。
 */
object AndroidKeyCodes {

    private val MAP: Map<Int, Int> = buildMap {
        /* 字母 A..Z（Android 29..54 → evdev 的散列顺序） */
        put(AndroidKeyEvent.KEYCODE_A, 30)
        put(AndroidKeyEvent.KEYCODE_B, 48)
        put(AndroidKeyEvent.KEYCODE_C, 46)
        put(AndroidKeyEvent.KEYCODE_D, 32)
        put(AndroidKeyEvent.KEYCODE_E, 18)
        put(AndroidKeyEvent.KEYCODE_F, 33)
        put(AndroidKeyEvent.KEYCODE_G, 34)
        put(AndroidKeyEvent.KEYCODE_H, 35)
        put(AndroidKeyEvent.KEYCODE_I, 23)
        put(AndroidKeyEvent.KEYCODE_J, 36)
        put(AndroidKeyEvent.KEYCODE_K, 37)
        put(AndroidKeyEvent.KEYCODE_L, 38)
        put(AndroidKeyEvent.KEYCODE_M, 50)
        put(AndroidKeyEvent.KEYCODE_N, 49)
        put(AndroidKeyEvent.KEYCODE_O, 24)
        put(AndroidKeyEvent.KEYCODE_P, 25)
        put(AndroidKeyEvent.KEYCODE_Q, 16)
        put(AndroidKeyEvent.KEYCODE_R, 19)
        put(AndroidKeyEvent.KEYCODE_S, 31)
        put(AndroidKeyEvent.KEYCODE_T, 20)
        put(AndroidKeyEvent.KEYCODE_U, 22)
        put(AndroidKeyEvent.KEYCODE_V, 47)
        put(AndroidKeyEvent.KEYCODE_W, 17)
        put(AndroidKeyEvent.KEYCODE_X, 45)
        put(AndroidKeyEvent.KEYCODE_Y, 21)
        put(AndroidKeyEvent.KEYCODE_Z, 44)

        /* 数字 0..9 */
        put(AndroidKeyEvent.KEYCODE_0, 11)
        put(AndroidKeyEvent.KEYCODE_1, 2)
        put(AndroidKeyEvent.KEYCODE_2, 3)
        put(AndroidKeyEvent.KEYCODE_3, 4)
        put(AndroidKeyEvent.KEYCODE_4, 5)
        put(AndroidKeyEvent.KEYCODE_5, 6)
        put(AndroidKeyEvent.KEYCODE_6, 7)
        put(AndroidKeyEvent.KEYCODE_7, 8)
        put(AndroidKeyEvent.KEYCODE_8, 9)
        put(AndroidKeyEvent.KEYCODE_9, 10)

        /* 功能键 */
        put(AndroidKeyEvent.KEYCODE_F1, 59)
        put(AndroidKeyEvent.KEYCODE_F2, 60)
        put(AndroidKeyEvent.KEYCODE_F3, 61)
        put(AndroidKeyEvent.KEYCODE_F4, 62)
        put(AndroidKeyEvent.KEYCODE_F5, 63)
        put(AndroidKeyEvent.KEYCODE_F6, 64)
        put(AndroidKeyEvent.KEYCODE_F7, 65)
        put(AndroidKeyEvent.KEYCODE_F8, 66)
        put(AndroidKeyEvent.KEYCODE_F9, 67)
        put(AndroidKeyEvent.KEYCODE_F10, 68)
        put(AndroidKeyEvent.KEYCODE_F11, 87)
        put(AndroidKeyEvent.KEYCODE_F12, 88)

        /* 编辑键 */
        put(AndroidKeyEvent.KEYCODE_ESCAPE, 1)
        put(AndroidKeyEvent.KEYCODE_TAB, 15)
        put(AndroidKeyEvent.KEYCODE_ENTER, 28)
        put(AndroidKeyEvent.KEYCODE_SPACE, 57)
        put(AndroidKeyEvent.KEYCODE_DEL, 14)
        put(AndroidKeyEvent.KEYCODE_FORWARD_DEL, 111)
        put(AndroidKeyEvent.KEYCODE_CAPS_LOCK, 58)

        /* 修饰键 */
        put(AndroidKeyEvent.KEYCODE_SHIFT_LEFT, 42)
        put(AndroidKeyEvent.KEYCODE_SHIFT_RIGHT, 54)
        put(AndroidKeyEvent.KEYCODE_CTRL_LEFT, 29)
        put(AndroidKeyEvent.KEYCODE_CTRL_RIGHT, 97)
        put(AndroidKeyEvent.KEYCODE_ALT_LEFT, 56)
        put(AndroidKeyEvent.KEYCODE_ALT_RIGHT, 100)
        put(AndroidKeyEvent.KEYCODE_META_LEFT, 125)
        put(AndroidKeyEvent.KEYCODE_META_RIGHT, 126)

        /* 方向键 / 翻页 */
        put(AndroidKeyEvent.KEYCODE_DPAD_UP, 103)
        put(AndroidKeyEvent.KEYCODE_DPAD_DOWN, 108)
        put(AndroidKeyEvent.KEYCODE_DPAD_LEFT, 105)
        put(AndroidKeyEvent.KEYCODE_DPAD_RIGHT, 106)
        put(AndroidKeyEvent.KEYCODE_MOVE_HOME, 102)
        put(AndroidKeyEvent.KEYCODE_MOVE_END, 107)
        put(AndroidKeyEvent.KEYCODE_PAGE_UP, 104)
        put(AndroidKeyEvent.KEYCODE_PAGE_DOWN, 109)
        put(AndroidKeyEvent.KEYCODE_INSERT, 110)

        /* 符号 */
        put(AndroidKeyEvent.KEYCODE_GRAVE, 41)
        put(AndroidKeyEvent.KEYCODE_MINUS, 12)
        put(AndroidKeyEvent.KEYCODE_EQUALS, 13)
        put(AndroidKeyEvent.KEYCODE_LEFT_BRACKET, 26)
        put(AndroidKeyEvent.KEYCODE_RIGHT_BRACKET, 27)
        put(AndroidKeyEvent.KEYCODE_BACKSLASH, 43)
        put(AndroidKeyEvent.KEYCODE_SEMICOLON, 39)
        put(AndroidKeyEvent.KEYCODE_APOSTROPHE, 40)
        put(AndroidKeyEvent.KEYCODE_COMMA, 51)
        put(AndroidKeyEvent.KEYCODE_PERIOD, 52)
        put(AndroidKeyEvent.KEYCODE_SLASH, 53)

        /* 小键盘 */
        put(AndroidKeyEvent.KEYCODE_NUMPAD_0, 82)
        put(AndroidKeyEvent.KEYCODE_NUMPAD_1, 79)
        put(AndroidKeyEvent.KEYCODE_NUMPAD_2, 80)
        put(AndroidKeyEvent.KEYCODE_NUMPAD_3, 81)
        put(AndroidKeyEvent.KEYCODE_NUMPAD_4, 75)
        put(AndroidKeyEvent.KEYCODE_NUMPAD_5, 76)
        put(AndroidKeyEvent.KEYCODE_NUMPAD_6, 77)
        put(AndroidKeyEvent.KEYCODE_NUMPAD_7, 71)
        put(AndroidKeyEvent.KEYCODE_NUMPAD_8, 72)
        put(AndroidKeyEvent.KEYCODE_NUMPAD_9, 73)
        put(AndroidKeyEvent.KEYCODE_NUMPAD_DOT, 83)
        put(AndroidKeyEvent.KEYCODE_NUMPAD_ENTER, 96)
        put(AndroidKeyEvent.KEYCODE_NUMPAD_ADD, 78)
        put(AndroidKeyEvent.KEYCODE_NUMPAD_SUBTRACT, 74)
        put(AndroidKeyEvent.KEYCODE_NUMPAD_MULTIPLY, 55)
        put(AndroidKeyEvent.KEYCODE_NUMPAD_DIVIDE, 98)

        /* 手柄：Android 的 BUTTON_* → evdev BTN_* */
        put(AndroidKeyEvent.KEYCODE_BUTTON_A, KeyCodes.BTN_SOUTH)
        put(AndroidKeyEvent.KEYCODE_BUTTON_B, KeyCodes.BTN_EAST)
        put(AndroidKeyEvent.KEYCODE_BUTTON_X, KeyCodes.BTN_WEST)
        put(AndroidKeyEvent.KEYCODE_BUTTON_Y, KeyCodes.BTN_NORTH)
        put(AndroidKeyEvent.KEYCODE_BUTTON_L1, KeyCodes.BTN_TL)
        put(AndroidKeyEvent.KEYCODE_BUTTON_R1, KeyCodes.BTN_TR)
        put(AndroidKeyEvent.KEYCODE_BUTTON_L2, KeyCodes.BTN_TL2)
        put(AndroidKeyEvent.KEYCODE_BUTTON_R2, KeyCodes.BTN_TR2)
        put(AndroidKeyEvent.KEYCODE_BUTTON_SELECT, KeyCodes.BTN_SELECT)
        put(AndroidKeyEvent.KEYCODE_BUTTON_START, KeyCodes.BTN_START)
        put(AndroidKeyEvent.KEYCODE_BUTTON_MODE, KeyCodes.BTN_MODE)
        put(AndroidKeyEvent.KEYCODE_BUTTON_THUMBL, KeyCodes.BTN_THUMBL)
        put(AndroidKeyEvent.KEYCODE_BUTTON_THUMBR, KeyCodes.BTN_THUMBR)
    }

    fun toAppCode(androidKeyCode: Int, source: Int): Int? {
        /*
         * 手柄的方向键在 Android 上复用 DPAD_* 的 keyCode；
         * 来自手柄时映射成 BTN_DPAD_*，否则会和键盘方向键撞在一起。
         */
        val fromGamepad = (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
        if (fromGamepad) {
            when (androidKeyCode) {
                AndroidKeyEvent.KEYCODE_DPAD_UP -> return KeyCodes.BTN_DPAD_UP
                AndroidKeyEvent.KEYCODE_DPAD_DOWN -> return KeyCodes.BTN_DPAD_DOWN
                AndroidKeyEvent.KEYCODE_DPAD_LEFT -> return KeyCodes.BTN_DPAD_LEFT
                AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> return KeyCodes.BTN_DPAD_RIGHT
            }
        }
        return MAP[androidKeyCode]
    }
}

/**
 * 只负责**鼠标按键**的采集视图。
 *
 * 为什么不用 Compose 的 PointerButtons：当前 Compose 版本里它的 `packedValue`
 * 是 internal，公开 API 只有 `areAnyPressed`，分不出左右中。
 */
@Composable
private fun MouseButtonCatcher(
    modifier: Modifier = Modifier,
    onButton: (Int) -> Unit,
) {
    val callback = rememberUpdatedState(onButton)
    AndroidView(
        modifier = modifier,
        factory = { context ->
            object : View(context) {
                override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                    /*
                     * ⚠️ 只处理真实鼠标；触摸/手写笔直接放行（返回 false），
                     * 所以手指点采集区什么都不会发生。
                     */
                    if (event.source and InputDevice.SOURCE_MOUSE != InputDevice.SOURCE_MOUSE) {
                        return false
                    }
                    val isDown = event.actionMasked == MotionEvent.ACTION_DOWN ||
                        event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS
                    if (isDown) {
                        val state = if (event.buttonState != 0) event.buttonState else event.actionButton
                        when {
                            state and MotionEvent.BUTTON_SECONDARY != 0 ->
                                callback.value(KeyCodes.BTN_RIGHT)
                            state and MotionEvent.BUTTON_TERTIARY != 0 ->
                                callback.value(KeyCodes.BTN_MIDDLE)
                            state and MotionEvent.BUTTON_PRIMARY != 0 ->
                                callback.value(KeyCodes.BTN_LEFT)
                        }
                    }
                    return true
                }
            }
        },
    )
}