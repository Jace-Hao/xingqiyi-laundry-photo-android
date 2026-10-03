package com.xingqiyi.laundryphoto.ui.update

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.xingqiyi.laundryphoto.R

/**
 * 离线照片数据保护闸门（U-COPY-12/13/14）。
 *
 * 安装会重启 App，若还有照片没传到服务器，重启后这些离线照片仍在本地（不丢），
 * 但店员可能误以为「更新把照片弄丢了」。因此在**确认安装前**先提醒一次：
 * - 优先引导「先传给服务器」（[onUpload]）；
 * - 「备份到相册」为 P1 留位（正式版另行实现，这里仅作占位出口）；
 * - 「继续安装」才真正走安装流程。
 */
@Composable
fun UpdateDataGuard(
    pendingCount: Int,
    onUpload: () -> Unit,
    onBackup: () -> Unit,
    onContinue: () -> Unit
) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = { },
        confirmButton = {
            TextButton(onClick = onContinue) { Text("继续安装") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onBackup) { Text(ctx.getString(R.string.update_guard_backup)) }
                TextButton(onClick = onUpload) { Text(ctx.getString(R.string.update_guard_upload)) }
            }
        },
        title = { Text(ctx.getString(R.string.update_guard_title, pendingCount)) },
        text = {
            Text(
                "安装会重启 App。未上传的照片不会丢失（仍在本地），但建议先传给服务器，避免误以为丢失。",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    )
}
