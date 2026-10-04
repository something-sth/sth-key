package com.something.sthkey.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.something.sthkey.domain.config.KeyMapping
import com.something.sthkey.domain.keys.AvailableKey
import com.something.sthkey.domain.keys.KeyCodes

/**
 * 键位映射编辑器。
 *
 * ============================================================
 * 这一版的范围（刻意收窄）
 * ============================================================
 * 悬浮窗上显示哪些按键是**固定的**（W / A / S / D / 空格 / Shift / LMB / RMB），
 * 用户能改的只有两件事：
 * 1. 显示文字（飘窗上写什么，例如把 SPACE 改成 ————）；
 * 2. 这个位置绑定哪些物理键（**支持多个**，例如 Shift 同时绑左右 Shift）。
 *
 * 因此这里**没有**"添加键位映射"和"删除键位映射"：
 * 那两件事在这个模式下没有意义——键位集合由样式决定，
 * 加一个进来渲染层也不知道该画在哪。
 *
 * 注意：数据模型仍然支持任意数量的映射（[KeyMapping] 是个普通列表），
 * 只是 UI 暂不提供增删入口。以后要放开（例如允许自定义新增一个键位），
 * 只需要在这里补回按钮，不需要动任何其它文件。
 */
@Composable
fun KeyMappingEditor(
    mappings: List<KeyMapping>,
    onChange: (List<KeyMapping>) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 要**隐藏**的槽位 id。
     *
     * ⚠️ 给"摇杆"这类**不绑键码**的槽位用。
     *
     * 摇杆位置在布局里占一个 `KeyBox`（好让它参与尺寸计算），
     * 但它没有"按下的键码"这个概念 —— 让它出现在键位映射列表里，
     * 用户会去绑一个键，然后发现绑了没反应。
     *
     * 用户的原话:"摇杆有些功能或许需要单独配置"。
     */
    hiddenSlotIds: Set<String> = emptySet(),
) {
    /** 正在编辑绑定键的映射 id；null 表示没有打开对话框 */
    var editingId by remember { mutableStateOf<String?>(null) }

    val visible = mappings.filter { it.id !in hiddenSlotIds }

    Column(modifier = modifier) {
        visible.forEachIndexed { index, mapping ->
            if (index > 0) {
                CardDivider()
            }
            KeyMappingRow(
                mapping = mapping,
                onEditKeys = { editingId = mapping.id },
                onChangeDisplayText = { text ->
                    onChange(
                        mappings.map { if (it.id == mapping.id) it.copy(displayText = text) else it },
                    )
                },
            )
        }
    }

    val editing = visible.firstOrNull { it.id == editingId }
    if (editing != null) {
        KeyBindingDialog(
            mapping = editing,
            onDismiss = { editingId = null },
            onConfirm = { codes ->
                onChange(
                    mappings.map { if (it.id == editing.id) it.copy(inputKeyCodes = codes) else it },
                )
                editingId = null
            },
        )
    }
}

/**
 * 一行映射。
 *
 * 只有两样东西：可直接编辑的**显示文字**，和"绑定哪些键"的入口。
 * 不显示内部标识、不提供"新增/删除映射"——
 * 悬浮窗显示哪些位置是样式决定的，用户只需要调这两项。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeyMappingRow(
    mapping: KeyMapping,
    onEditKeys: () -> Unit,
    onChangeDisplayText: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        val focusManager = LocalFocusManager.current

        OutlinedTextField(
            value = mapping.displayText,
            onValueChange = onChangeDisplayText,
            singleLine = true,
            label = { Text("显示文字") },
            /*
             * "完成"要真的**结束编辑**。
             *
             * 软键盘上点「完成」（或外接键盘按 Enter）默认只是收起键盘，
             * 焦点仍留在输入框里 —— 表现是"编辑完了还在编辑中"：
             * 光标一直闪，得再点一下别处才退出。
             */
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(10.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "绑定按键",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))

                if (mapping.inputKeyCodes.isEmpty()) {
                    Text(
                        text = "未绑定（该按键不会亮起）",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    // 一个位置可以绑多个物理键，用流式布局换行展示
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        mapping.inputKeyCodes.forEach { code ->
                            AssistChip(
                                onClick = {},
                                label = {
                                    Text("${KeyCodes.displayName(code)} · $code")
                                },
                            )
                        }
                    }
                }
            }

            IconButton(onClick = onEditKeys) {
                Icon(Icons.Default.Edit, contentDescription = "编辑绑定按键")
            }
        }
    }
}

/**
 * 绑定按键对话框：增删这个位置绑定的物理键。
 *
 * 这里保留了"一个位置绑多个键"的能力 —— 例如 Shift 需要同时接受
 * 左 Shift(42) 与右 Shift(54)，只绑一个的话另一边按下去不亮。
 */
@Composable
private fun KeyBindingDialog(
    mapping: KeyMapping,
    onDismiss: () -> Unit,
    onConfirm: (List<Int>) -> Unit,
) {
    MultiKeyPickerDialog(
        title = "绑定按键 · ${mapping.displayText.ifBlank { mapping.id }}",
        hint = "这个位置可以绑定多个物理键，任意一个按下都会点亮它。",
        initialCodes = mapping.inputKeyCodes,
        emptyHint = "当前未绑定任何按键",
        onDismiss = onDismiss,
        onConfirm = onConfirm,
    )
}

/**
 * **多选按键**对话框（通用）。
 *
 * ============================================================
 * 为什么抽出来公用
 * ============================================================
 * 自定义 Key 里有两处需要"选一组键"：按键组件监听的键、以及文本组件
 * CPS 统计的键。两者的交互与键位映射**完全一样**（增删、去重、确定/取消），
 * 各写一份的话，改一处必然漏一处 —— 而且它们看起来都"能跑"。
 *
 * 编辑是**暂存**的（`codes` 是本地状态）：点"取消"就整体丢弃。
 * 直接改到配置上的话，用户点取消也回不去了。
 */
@Composable
fun MultiKeyPickerDialog(
    title: String,
    hint: String,
    initialCodes: List<Int>,
    onDismiss: () -> Unit,
    onConfirm: (List<Int>) -> Unit,
    emptyHint: String = "当前未选择任何按键",
) {
    var codes by remember(initialCodes) { mutableStateOf(initialCodes) }
    var picking by remember { mutableStateOf(false) }

    /**
     * 已选中的大类；`null` = 还没选（也就是停在"大类选择"那一层）。
     *
     * ⚠️ 两层对话框用**两个状态**而不是一个枚举:
     * `picking` 管"要不要弹"，`pickingGroup` 管"弹到第几层"。
     * 合成一个的话"从搜索返回大类"这种回退很难表达。
     */
    var pickingGroup by remember { mutableStateOf<KeyCodes.PickerGroup?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (codes.isEmpty()) {
                    Text(
                        text = emptyHint,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    codes.forEach { code ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${KeyCodes.displayName(code)}（code=$code）",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { codes = codes - code }) {
                                Icon(Icons.Default.Close, contentDescription = "移除")
                            }
                        }
                    }
                }

                TextButton(
                    onClick = { picking = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("添加一个按键")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(codes) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )

    if (picking) {
        /*
         * ⚠️ 先选**大类**，再进搜索框。
         *
         * 用户的原话:"应该在'添加一个按键'点击后先弹一个选择键位分类的窗口，
         * 选择键位在键盘分类还是手柄分类，否则可能会搞混……想调 A 这类按键
         * 就会同时搜索出键盘的 A 和手柄的 A"。
         *
         * 所以这里弹的是 [KeyGroupPickerDialog]，它选中之后再弹
         * [KeyPickerDialog]（带着大类过滤）。
         */
        KeyGroupPickerDialog(
            onDismiss = { picking = false },
            onPicked = { group -> pickingGroup = group },
        )
    }

    pickingGroup?.let { group ->
        KeyPickerDialog(
            title = "选择按键 · ${group.label}",
            group = group,
            onDismiss = {
                /*
                 * ⚠️ 返回时回到**大类选择**，而不是直接关掉整个流程。
                 *
                 * 用户选了"手柄"进去发现没有自己要的键（比如想绑键盘的 A），
                 * 直接关掉的话要重新点"添加一个按键"再走一遍 ——
                 * 回到大类那一层只需要再点一下。
                 */
                pickingGroup = null
            },
            onPicked = { key ->
                // 去重：同一个键加两次没有意义，而且会让"CPS 求和"重复计一次
                if (key.keyCode !in codes) codes = codes + key.keyCode
                picking = false
                pickingGroup = null
            },
        )
    }
}

/**
 * 按键选择器的**第一步**:选大类（键盘 / 手柄 / 全部）。
 *
 * ============================================================
 * ⚠️ 为什么要这一步
 * ============================================================
 * 键盘的 `A`（`KEY_A` = 30）与手柄的 `A`（`BTN_SOUTH` = 304）
 * 在搜索列表里**名字都叫 A**，只有 `code` 不同。
 *
 * 没有经验的用户会随便点一个，然后"按了没反应" —— 而那是
 * **选错了大类**，不是功能坏了。用户的原话:
 *
 * > "有些用户没有经验，想调 A 这类按键就会同时搜索出键盘的 A
 * >  和手柄的 A，所以这个是有必要做的"
 *
 * ⚠️ 保留"全部"这一项:高级用户知道自己要找什么（比如直接输 `304`），
 * 强制两步会让改键变得很烦。
 */
@Composable
private fun KeyGroupPickerDialog(
    onDismiss: () -> Unit,
    onPicked: (KeyCodes.PickerGroup) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择按键分类") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "手柄和键盘上有一些同名的键（例如都叫 A），" +
                        "先选分类可以避免搞混。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(4.dp))

                KeyCodes.PickerGroup.entries.forEach { group ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPicked(group) }
                            .padding(vertical = 14.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = group.label,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = group.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
/**
 * 按键选择器。
 *
 * 搜索行为由 [KeyCodes.search] 决定：**前缀匹配**，而不是任意子串。
 * 这样搜 `L` 只会得到 `L`、`LMB`、`LEFT`… 这类以 L 开头的键，
 * 不会翻出 `EQUAL`、`LEFT_BRACKET` 这种"中间含 L"的噪声。
 *
 * 直接返回原始键码，不做任何 UI 侧转换 —— 保证"选中的键"与"设备上报的键"
 * 始终是同一个数值体系（Linux evdev）。
 */
@Composable
fun KeyPickerDialog(
    title: String,
    onDismiss: () -> Unit,
    onPicked: (AvailableKey) -> Unit,
    /**
     * 只看哪个大类。
     *
     * ⚠️ 默认 [KeyCodes.PickerGroup.ALL] —— 老调用方（直接弹选择器、
     * 不经大类那一步的）行为完全不变，不需要跟着改。
     */
    group: KeyCodes.PickerGroup = KeyCodes.PickerGroup.ALL,
) {
    var query by remember { mutableStateOf("") }
    val results = remember(query, group) { KeyCodes.searchIn(query, group) }
    val focusManager = LocalFocusManager.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text("搜索按键") },
                    placeholder = { Text("例如 L / space / 272") },
                    /*
                     * 搜索框的"完成"同样只收起键盘即可 ——
                     * 点一下结果就选中了，不需要额外提交。
                     * 但焦点要清掉，否则键盘收起后光标还在闪。
                     */
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = { focusManager.clearFocus() },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "共 ${results.size} 个结果",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(4.dp))

                if (results.isEmpty()) {
                    Text(
                        text = "没有匹配的按键",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                        items(results, key = { it.keyCode }) { key ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPicked(key) }
                                    .padding(vertical = 12.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = key.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = "code ${key.keyCode}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(modifier = Modifier.size(8.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}
