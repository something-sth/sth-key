package com.something.sthkey.ui.feature.custom

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.something.sthkey.domain.custom.MediaComponent
import com.something.sthkey.domain.custom.MediaFit
import com.something.sthkey.domain.custom.MediaKind
import com.something.sthkey.domain.media.CustomMediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 媒体组件的「内容」栏:选资源 + 铺满方式 + 不透明度 + 圆角 + 静音。
 *
 * ============================================================
 * ⚠️ 为什么选资源走相册（SAF `OpenDocument`）
 * ============================================================
 * 用户的原话:"选资源调用相册应该就可以了吧"。
 *
 * ⚠️ MIME 按组件类型分流:
 * - 图片 / 动图 → `image/…`（相册里的 gif 也在这一栏）
 * - 视频 → `video/…`
 * 不写死单一类型是因为有些文件管理器对 gif 的 MIME 判断不一致，
 * 写太窄会让文件在相册里灰掉选不中。
 *
 * ⚠️ 导入是**磁盘操作**，走 IO，结束后刷新一个版本号让面板重读资源名。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MediaSection(
    component: MediaComponent,
    onComponentChange: (MediaComponent, Boolean) -> Unit,
    onBeginContinuous: () -> Unit,
    onEndContinuous: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    /** 版本号:导入/清理资源后自增，用来重读资源名 */
    var revision by remember { mutableIntStateOf(0) }

    val currentName = remember(component.assetId, revision) {
        if (component.assetId.isBlank()) "（未选择资源）" else CustomMediaStore.displayNameOf(component.assetId)
    }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                CustomMediaStore.import(context, uri, component.kind)
            }
            result.onSuccess { entry ->
                revision++
                onComponentChange(component.copy(assetId = entry.id), true)
            }
        }
    }

    MediaHint("资源：$currentName")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        OutlinedButton(
            onClick = {
                picker.launch(
                    when (component.kind) {
                        MediaKind.VIDEO -> arrayOf("video/*")
                        /* 图片与动图都在相册的图片一栏；gif 各家 MIME 判断不一，给宽一点 */
                        MediaKind.IMAGE, MediaKind.GIF -> arrayOf("image/*")
                    },
                )
            },
            modifier = Modifier.weight(1f),
        ) {
            Text(if (component.assetId.isBlank()) "从相册选择" else "更换资源")
        }
        if (component.assetId.isNotBlank()) {
            OutlinedButton(onClick = { onComponentChange(component.copy(assetId = ""), true) }) {
                Text("清除")
            }
        }
    }

    MediaHint("铺满方式（比例不符时怎么办）")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        MediaFit.entries.forEach { fit ->
            AssistChip(
                onClick = { onComponentChange(component.copy(fit = fit), true) },
                label = {
                    Text(
                        text = fit.label,
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
                enabled = true,
            )
        }
    }
    MediaHint(
        when (component.fit) {
            MediaFit.FIT -> "完整显示：留边，不裁切"
            MediaFit.COVER -> "铺满裁剪：铺满边框，超出部分被裁掉"
            MediaFit.STRETCH -> "拉伸铺满：宽高各自拉满，画面会变形（进度条/色块用这个）"
        },
    )

    SliderRow(
        label = "不透明度",
        value = component.opacityPercent.toFloat(),
        range = 0f..100f,
        display = "${component.opacityPercent}%",
        onBegin = onBeginContinuous,
        onEnd = onEndContinuous,
        onChange = { value ->
            onComponentChange(component.copy(opacityPercent = value.toInt()), false)
        },
    )

    SliderRow(
        label = "圆角",
        value = component.cornerRadiusPercent.toFloat(),
        range = 0f..50f,
        display = "${component.cornerRadiusPercent}%",
        onBegin = onBeginContinuous,
        onEnd = onEndContinuous,
        onChange = { value ->
            onComponentChange(component.copy(cornerRadiusPercent = value.toInt()), false)
        },
    )

    /*
     * ⚠️ 视频**没有静音开关** —— 一律静音。
     *
     * 用户的原话:
     * > 把视频组件静音开关删了吧，改为默认静音，因为我测试这个有声音的 bug 很多，
     * > 不好搞，干脆删了，用户应该也不会用这玩意听歌吧
     *
     * 所以播放那一侧固定 `setVolume(0,0)`，这里不再提供任何开关。
     */
}

/** 面板里的一行开关（与其它栏的写法对齐） */
@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        com.something.sthkey.ui.component.ExpressiveSwitch(
            checked = checked,
            onCheckedChange = onChange,
        )
    }
}

/**
 * 面板里的小灰字提示。
 *
 * ⚠️ 不复用 `ComponentProperties` 里那个 `HintText` —— 它是 **private**，
 * 只有同文件可见。为了几行提示把两个文件合成一个不值当，这里各留一份。
 */
@Composable
private fun MediaHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
    )
}