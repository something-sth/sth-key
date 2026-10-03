package com.something.sthkey.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.something.sthkey.domain.font.bitmap.AtlasGrid
import com.something.sthkey.domain.font.bitmap.BitmapFontImporter
import com.something.sthkey.domain.font.bitmap.BitmapFontSpec
import com.something.sthkey.domain.font.bitmap.BitmapFontStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * ============================================================
 * 图片字体的导入流程：起名 -> 登记 -> 回报结果
 * ============================================================
 * 放在 `ui/component` 而不是某个编辑页里，是因为**两个编辑页都要用**：
 * Key 样式的配置页与自定义布局编辑页各有一份自己的字体对话框。
 *
 * ⚠️ 一开始它俩各自内联在自己的页面里（其中一份还只是 private 函数），
 * 结果是"在 A 页面能用、在 B 页面点了没反应" —— 需求里报的
 * "key 样式配置里点击导入图片字体没有任何反应"就是这个。
 * 抽出来之后两边走的是同一段代码，不会再出现不对称。
 */

/**
 * 待命名的图片字体导入：起名 -> 登记 -> 回报结果。
 *
 * ============================================================
 * 为什么整段抽成独立函数
 * ============================================================
 * 一是它有自己的协程生命周期（读图 + 解码 + 落盘），混在调用方的
 * 组合逻辑里会让"什么时候在做 IO"变得不明显；
 *
 * 二是把"导入一张图"这件事的**全部入口与出口**（成功给 id、失败给原因、
 * 取消则什么都不做）收在一个签名里，调用方那边就只剩"结果怎么用"。
 *
 * @param source 待登记的图集；null 表示没有待办（函数什么都不画）
 * @param onDismiss 用户取消命名
 * @param onRegistered 登记成功，回传字体 id 与用户起的名字
 * @param onFailed 登记失败，回传一句可直接显示的中文原因
 */
@Composable
fun PendingBitmapImportDialog(
    source: BitmapFontImporter.Source?,
    onDismiss: () -> Unit,
    onRegistered: (fontId: String, name: String) -> Unit,
    onFailed: (reason: String) -> Unit,
) {
    if (source == null) return

    val scope = rememberCoroutineScope()

    BitmapFontNameDialog(
        defaultName = BitmapFontStore.fallbackName(),
        onDismiss = onDismiss,
        onConfirm = { name ->
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    BitmapFontStore.registerFromSource(
                        source = source,
                        displayName = name,
                        /*
                         * 规格先用原版 ascii.png 的那一套：
                         * 16x16 格、ASCII 从第 2 行第 0 列起、height 8、ascent 7。
                         *
                         * 遮罩模式**自动猜**（透明图集与"白底灰度图"是两种
                         * 完全不同的编码，猜错的后果是整块实心方块）。
                         */
                        spec = BitmapFontSpec(
                            atlasFileName = "",
                            grid = AtlasGrid(
                                columns = 16,
                                rows = 16,
                                firstCodePoint = 0x20,
                                firstColumn = 0,
                                firstRow = 2,
                            ),
                            maskMode = source.suggestedMaskMode,
                        ),
                    )
                }
                when (result) {
                    is BitmapFontStore.RegisterResult.Ok ->
                        onRegistered(result.fontId, name)

                    is BitmapFontStore.RegisterResult.Failed ->
                        onFailed(result.reason)
                }
            }
        },
    )
}

/**
 * 图片字体的命名对话框。
 *
 * ============================================================
 * 为什么必须让用户命名，而不是直接用文件名
 * ============================================================
 * Minecraft 的字体图集**几乎都叫 `ascii.png`**（基岩版叫 `default8.png`）。
 * 用文件名当字体名的话，导入几张之后字体列表里全是同名条目，
 * 用户根本分不清哪个是哪个。
 *
 * 默认值给**导入时刻**（`yyyyMMddHHmmss`）而不是空 ——
 * 用户想跳过时直接点确定就行，名字也是唯一的、能按时间排序的。
 */
@Composable
private fun BitmapFontNameDialog(
    defaultName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(defaultName) }
    val valid = name.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("给这个字体起个名字") },
        text = {
            Column {
                Text(
                    text = "字体图的名字通常都是 ascii.png，所以需要你另起一个 —— " +
                        "否则导入几张之后列表里会全是同名条目。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("字体名称") },
                    isError = !valid,
                    supportingText = {
                        Text(
                            text = "默认是当前时间，直接确定也行",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim()) }, enabled = valid) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
