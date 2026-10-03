package com.something.sthkey.ui.feature.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.something.sthkey.data.config.ConfigPackageManager
import com.something.sthkey.data.config.JsonConfigCodec

/**
 * 导入结果报告。
 *
 * ============================================================
 * 为什么要有这个弹窗
 * ============================================================
 * 配置结构会随版本演进，导入一份"来自其它版本"的配置时，
 * 双方字段不可能完全对齐。如果不说明，用户只会看到
 * "有些设置没生效/有些设置丢了"却不知道为什么。
 *
 * 因此这里**双向都报**：
 * - **未识别字段**：配置里有、本版本没有 —— 通常是新版功能，已跳过；
 * - **缺失字段**：本版本有、配置里没写 —— 已使用默认值。
 *
 * 只报"缺失"会漏掉一半信息（导入新版配置到旧版时，用户看不到设置被丢了）。
 * 默认折叠，避免一次刷出几十行；需要细节时再展开。
 *
 * ============================================================
 * ⚠️ 两个导入入口**共用这一份**
 * ============================================================
 * 配置页里的「导入配置」与外部分享/打开 `.sthkey` 是**同一个功能**，
 * 只是入口不同。早先外部导入那边自己写了一个"只显示配置名"的简易结果框 ——
 * 于是同一个包从两个入口导入，看到的**信息量不一样**（外部导入看不到
 * 字段差异、版本号、字体去向）。
 *
 * 那是设计缺陷，不是少写几行。所以现在只有一个报告实现：
 * - [ImportReportDialog]：带对话框外壳，给配置页用；
 * - [ImportReportContent]：只有内容，给外部分享那条已经自带对话框的流程用。
 *
 * 以后要加报告项，**改 [ImportReportContent] 一处**，两个入口同时生效。
 */
@Composable
fun ImportReportDialog(
    report: ConfigPackageManager.ImportResult,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (report.hasDifferences) "导入完成（有差异）" else "导入成功") },
        text = { ImportReportContent(report) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("知道了") }
        },
    )
}

/**
 * 报告正文（标题与按钮由调用方提供）。
 *
 * 抽成独立 composable 是为了让外部分享那条流程能把它放进自己的对话框里 ——
 * 那条流程多一个"确认"阶段，不能直接套 [ImportReportDialog]。
 */
@Composable
fun ImportReportContent(report: ConfigPackageManager.ImportResult) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 400.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = buildString {
                append("已导入「${report.config.name}」")
                if (report.renamed) {
                    append("（原名称「${report.originalName}」，因重名已自动改名）")
                }
            },
            style = MaterialTheme.typography.bodyLarge,
        )

        Text(
            text = "包版本：格式 v${report.packageFormatVersion}" +
                if (report.packageAppVersion.isNotBlank()) {
                    " · 导出自 ${report.packageAppVersion}"
                } else {
                    ""
                },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        /* ---- 字体 ---- */

        if (report.importedFonts.isNotEmpty()) {
            ReportSection(
                title = "已加入字体库",
                lines = report.importedFonts,
                hint = "这些字体对所有配置可用，无需重复导入",
            )
        }

        if (report.reusedFonts.isNotEmpty()) {
            ReportSection(
                title = "复用了已有的字体",
                lines = report.reusedFonts,
                hint = "内容相同，没有重复占用空间",
            )
        }

        if (report.missingFonts.isNotEmpty()) {
            ReportSection(
                title = "⚠ 字体缺失（已回落为默认字体）",
                lines = report.missingFonts,
                hint = "包里没带这些字体。用到它们的地方已回落为默认字体 —— " +
                    "到「自定义编辑 → 外观」里给那些组件重新选一个即可，其余组件不受影响",
                titleColor = MaterialTheme.colorScheme.error,
            )
        }

        /* ---- Live2D 模型 ---- */

        if (report.importedModels.isNotEmpty()) {
            ReportSection(
                title = "已加入模型库",
                lines = report.importedModels,
                hint = "该模型对所有配置可用，无需重复导入",
            )
        }

        if (report.reusedModels.isNotEmpty()) {
            ReportSection(
                title = "复用了已有的模型",
                lines = report.reusedModels,
                hint = "内容相同，没有重复占用空间",
            )
        }

        if (report.modelMissing) {
            Text(
                text = "⚠ 配置包里的 Live2D 模型缺失或导入失败，该配置已回落到内置模型。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        /* ---- 字段差异 ---- */

        if (report.unknownKeys.isNotEmpty() || report.missingKeys.isNotEmpty()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Text(
                text = "字段差异 " +
                    "（未识别 ${report.unknownKeys.size} · 默认值 ${report.missingKeys.size}）",
                style = MaterialTheme.typography.titleMedium,
            )

            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "收起详情" else "展开详情")
            }

            if (expanded) {
                if (report.unknownKeys.isNotEmpty()) {
                    ReportSection(
                        title = "未识别的字段（已跳过）",
                        lines = report.unknownKeys.map { JsonConfigCodec.labelOf(it) },
                        hint = "这些多半来自更新版本的功能，本版本无法应用",
                    )
                }
                if (report.missingKeys.isNotEmpty()) {
                    ReportSection(
                        title = "配置中未定义的项（已用默认值）",
                        lines = report.missingKeys.map { JsonConfigCodec.labelOf(it) },
                        hint = "这些项在配置包里没有写，因此使用本版本的默认值",
                    )
                }
            }
        }
    }
}

/** 报告里的一个小节：标题 + 若干条目 + 一句说明 */
@Composable
private fun ReportSection(
    title: String,
    lines: List<String>,
    hint: String,
    titleColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleSmall, color = titleColor)
        lines.forEach { line ->
            Text(
                text = "· $line",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = hint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
