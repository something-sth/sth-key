package com.something.sthkey.ui.feature.config

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Notes
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.something.sthkey.core.prefs.AppPrefs
import com.something.sthkey.data.config.ConfigPackageCodec
import com.something.sthkey.data.config.ConfigPackageManager
import com.something.sthkey.domain.config.KeyStrokesConfig
import com.something.sthkey.ui.component.CardDivider
import com.something.sthkey.ui.component.ScrollableScreen
import com.something.sthkey.ui.component.SectionHeader
import com.something.sthkey.ui.component.SectionHint
import com.something.sthkey.ui.component.SettingItem
import com.something.sthkey.ui.component.SettingsCard
import com.something.sthkey.ui.MainViewModel





/**
 * **配置组合详情页**。
 *
 * ============================================================
 * ⚠️ 内容排布（用户的要求）
 * ============================================================
 * > 点击配置组合卡片会进入一个二级页面，这里面就是添加进来的配置卡片了，
 * > 样式与配置页一级页面相同，最上面编辑这个配置组合的名称与描述，
 * > 再往下可以分别编辑各个配置，然后这个二级页面下面要加一个配置管理 button
 *
 * ⚠️ 顺序:**名称/描述 → 成员卡片 → 配置管理按钮**。
 *
 * ⚠️ 成员卡片复用配置页的 `ConfigCard`（同一个 composable）:
 * 这样"样式与配置页一级页面相同"是**结构上保证**的，
 * 而不是靠两处手抄得一模一样（抄的那份迟早会分叉）。
 */
@Composable
fun ConfigGroupDetailScreen(
    viewModel: MainViewModel,
    groupId: String,
    onBack: () -> Unit,
    onEditConfig: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    /*
     * ⚠️ 读一次版本号建立"组合变了就重读"的关系。
     * 直接 `viewModel.groups.find { … }` 在组合被解散后会是 null，
     * 那时直接退出这一页（返回上一级），避免显示一个空壳。
     */
    val group = viewModel.groups.firstOrNull { it.id == groupId }
    if (group == null) {
        /* 组合没了（被解散）→ 自动返回，不要留一个空白页 */
        androidx.compose.runtime.LaunchedEffect(Unit) { onBack() }
        return
    }

    val prefs = remember { AppPrefs.get(viewModel.getApplication()) }
    val columns = prefs.configListColumns
    val showDescription = prefs.configListShowDescription
    val compactActions = prefs.configListCompactActions

    /* 成员配置（按 memberIds 的顺序；被删掉的 id 已由 store 清理） */
    val members = remember(group, viewModel.configVersion) {
        group.memberIds.mapNotNull { id -> viewModel.allConfigs().firstOrNull { it.id == id } }
    }

    var renaming by remember { mutableStateOf(false) }
    var editingDescription by remember { mutableStateOf(false) }
    var managing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<KeyStrokesConfig?>(null) }
    var exporting by remember { mutableStateOf<KeyStrokesConfig?>(null) }
    var showCreateConfig by remember { mutableStateOf(false) }

    /* ⚠️ 一次性提示（复制/导出结果）统一走 Toast，和配置页一致 */
    val toast: (String) -> Unit = { message ->
        Toast.makeText(viewModel.getApplication(), message, Toast.LENGTH_SHORT).show()
    }

    /* ⚠️ 导出复用配置页那一整套流程（默认方式 / 选择位置 / 分享） */
    val exportFlow = rememberExportFlow(onMessage = toast)

    ScrollableScreen(
        title = group.name,
        onBack = onBack,
        modifier = modifier,
    ) {
        /* ---------------- 名称 / 描述 ---------------- */
        item { SectionHeader("组合信息") }

        item {
            SettingsCard {
                /*
                 * ⚠️ 名称/描述用**带图标方块的行**（与设置页同一套），不是内联文本框。
                 * 用户给了两个方案，选这个的理由:
                 *
                 * | 方案 | 取舍 |
                 * |---|---|
                 * | 内联 `OutlinedTextField`（配置编辑页那种） | 改起来快，但那是"编辑一份配置"的形态，和这一页的卡片列表不是一套东西 |
                 * | **行 + 圆角方块图标**（选的这个） | 与全应用统一；40dp 方块让行**自然变高**，正好是用户要的"高度拉大一点" |
                 *
                 * ⚠️ 点开是对话框，不是就地编辑:就地编辑要处理焦点、键盘顶起、失焦保存，
                 * 收益不值那个复杂度。
                 */
                SettingItem(
                    title = "名称",
                    subtitle = group.name,
                    leadingIcon = Icons.Rounded.DriveFileRenameOutline,
                    onClick = { renaming = true },
                )
                CardDivider()
                SettingItem(
                    title = "描述",
                    subtitle = group.description.ifBlank { "（未填写）" },
                    leadingIcon = Icons.Rounded.Notes,
                    onClick = { editingDescription = true },
                )
            }
        }

        /* ---------------- 成员配置 ---------------- */
        item { SectionHeader("配置") }

        if (members.isEmpty()) {
            item {
                SectionHint("还没有配置。点下面的「配置管理」把配置加进来。")
            }
        }

        /* ⚠️ 与配置页同一套"按列切行"的排版，卡片宽度算法也一致 */
        members.chunked(columns).forEachIndexed { rowIndex, rowItems ->
            item(key = "member_row_${group.id}_$rowIndex") {
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val gap = 12.dp
                    val gaps = gap * (columns - 1).coerceAtLeast(0)
                    val cardWidth = (maxWidth - gaps) / columns.coerceAtLeast(1)

                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        rowItems.forEach { config ->
                            ConfigCard(
                                config = config,
                                width = cardWidth,
                                showDescription = showDescription,
                                compactActions = compactActions,
                                onClick = { onEditConfig(config.id) },
                                /*
                                 * ⚠️ 组合里**保留**导出/复制/删除（用户要求）:
                                 *
                                 * > 在配置组合里编辑配置的这些东西还是挺有必要的，
                                 * > 例如在配置组合里复制某份配置，删除某份配置，
                                 * > 单独导出某份配置，都是需要搞得
                                 *
                                 * ⚠️ 我上一版把它们全关了（`actionsEnabled = false`），
                                 * 理由是"这一页管分组关系"—— 但用户明确说需要，
                                 * 而且删/复制的语义和配置页完全一样（删了会顺带从组合里移除）。
                                 */
                                onExport = { exporting = config },
                                onDuplicate = {
                                    val copy = viewModel.duplicateConfig(config.id)
                                    /*
                                     * ⚠️ 复制出来的配置要**加进本组合**:
                                     * 用户是在这个组合里点的复制，期望复制品留在这里，
                                     * 而不是莫名其妙回到配置列表。
                                     */
                                    viewModel.addGroupMembers(group.id, listOf(copy.id))
                                    toast("已复制为「${copy.name}」")
                                },
                                onDelete = { deleting = config },
                            )
                        }

                        repeat((columns - rowItems.size).coerceAtLeast(0)) {
                            Spacer(modifier = Modifier.width(cardWidth))
                        }
                    }
                }
            }
        }

        /* ---------------- 配置管理 / 新建配置 ---------------- */
        item {
            /*
             * ⚠️ 两个按钮并列（用户要求加「新建配置」）:
             *
             * > 以及，配置组合的二级页面要不要放一个新建配置的按钮？
             * > 直接创建配置在这个组合里（注意，这里面只加新建配置，
             * > 不加新建配置组合，不然会乱套）
             *
             * ⚠️ **只加新建配置**:组合里再建组合会嵌套，语义和界面都会乱。
             */
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { managing = true },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("配置管理")
                }

                OutlinedButton(
                    onClick = { showCreateConfig = true },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.CreateNewFolder, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("新建配置")
                }
            }
        }

        item {
            SectionHint(
                text = "「配置管理」里可以多选配置加进来；取消勾选会把配置移出本组合" +
                    "（配置本身不会被删除，会回到配置列表）。" +
                    "「新建配置」建完会直接放进本组合。",
            )
        }
    }

    /* ---------------- 弹窗 ---------------- */

    if (renaming) {
        GroupTextEditDialog(
            title = "组合名称",
            initial = group.name,
            label = "名称",
            allowEmpty = false,
            onDismiss = { renaming = false },
            onConfirm = { value ->
                renaming = false
                viewModel.updateGroup(group.id, name = value)
            },
        )
    }

    if (editingDescription) {
        GroupTextEditDialog(
            title = "组合描述",
            initial = group.description,
            label = "描述",
            allowEmpty = true,
            onDismiss = { editingDescription = false },
            onConfirm = { value ->
                editingDescription = false
                viewModel.updateGroup(group.id, description = value)
            },
        )
    }

    if (managing) {
        GroupMemberPickerDialog(
            allConfigs = viewModel.allConfigs(),
            selectedIds = group.memberIds.toSet(),
            currentGroupId = group.id,
            ownerOf = { viewModel.groupOf(it) },
            onDismiss = { managing = false },
            onConfirm = { picked ->
                val current = group.memberIds.toSet()
                val added = picked - current
                val removed = current - picked
                if (added.isNotEmpty()) viewModel.addGroupMembers(group.id, added.toList())
                if (removed.isNotEmpty()) viewModel.removeGroupMembers(group.id, removed.toList())
                managing = false
            },
        )
    }

    /* ---------------- 删除成员配置 ---------------- */
    deleting?.let { target ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除「${target.name}」？") },
            text = {
                Text(
                    "这会**永久删除**这份配置（不只是移出组合），" +
                        "它会先从本组合里移除，然后从配置列表里消失。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleting = null
                        /* ⚠️ `deleteConfig` 会顺带清理组合引用（见 ViewModel） */
                        viewModel.deleteConfig(target.id)
                        toast("已删除「${target.name}」")
                    },
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("取消") }
            },
        )
    }

    /* ---------------- 导出成员配置 ---------------- */
    exporting?.let { target ->
        ExportMethodDialog(
            configName = target.name,
            onDismiss = { exporting = null },
            onPick = { method, remember ->
                exporting = null
                if (remember) {
                    AppPrefs.get(viewModel.getApplication()).exportMethod = method
                }
                exportFlow.start(
                    PendingExport(
                        displayName = target.name,
                        fileName = target.name + ConfigPackageCodec.EXTENSION,
                        write = { uri ->
                            ConfigPackageManager.export(
                                context = viewModel.getApplication(),
                                config = target,
                                target = uri,
                                requestedName = target.name + ConfigPackageCodec.EXTENSION,
                            )
                        },
                    ),
                    method,
                    false,
                )
            },
        )
    }

    /* ---------------- 在组合里新建配置 ---------------- */
    if (showCreateConfig) {
        CreateConfigDialog(
            onDismiss = { showCreateConfig = false },
            onConfirm = { name, description, styleId ->
                val created = viewModel.createConfig(name, description, styleId)
                showCreateConfig = false
                /* ⚠️ 建完直接加进本组合 —— 用户是在这个组合里建它的 */
                viewModel.addGroupMembers(group.id, listOf(created.id))
                toast("已新建「${created.name}」")
            },
        )
    }
}


