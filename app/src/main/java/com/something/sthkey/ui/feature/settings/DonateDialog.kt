package com.something.sthkey.ui.feature.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.something.sthkey.R

/**
 * 「捐赠」弹窗。
 *
 * ============================================================
 * ⚠️ 图是**赞赏码**，必须能扫
 * ============================================================
 * - 用 `drawable-nodpi` 放原图（1160×1160），**不让系统按密度缩放**:
 *   放 `drawable-xxhdpi` 之类的目录会被压缩/放大，二维码的像素被插值之后
 *   在某些扫码器上会扫不出来。
 * - `ContentScale.Fit` + `heightIn(max=…)`:保持长宽比，小屏上也不会把
 *   对话框顶出屏幕（比例变形同样会影响识别）。
 * - 图片外面加圆角裁切只是为了和别的卡片观感一致，**不影响识别区域**
 *   （四角的定位块不在裁切范围内）。
 */
@Composable
fun DonateDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("请我喝杯奶茶") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(
                    painter = painterResource(R.drawable.donate_qr),
                    contentDescription = "赞赏码",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .clip(RoundedCornerShape(14.dp)),
                )

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "这个项目由我一个人独立维护，放学回来才有些时间来进行开发。" +
                        "如果你觉得它有用，欢迎请我喝杯奶茶 —— " +
                        "你的每一份支持，都是我继续更新的动力。",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(10.dp))

                /* ⚠️ 签名右对齐，与用户要求一致（"右下角：by sth."） */
                Text(
                    text = "by sth.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    /* Column 作用域里直接用 align(End)，不需要额外包一层 Row */
                    modifier = Modifier.align(Alignment.End),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}
