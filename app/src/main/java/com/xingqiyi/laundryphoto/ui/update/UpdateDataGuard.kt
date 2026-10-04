package com.xingqiyi.laundryphoto.ui.update

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.xingqiyi.laundryphoto.R

/**
 * 离线照片数据保护闸门（U-COPY-12/13）。
 *
 * 安装会重启 App，若还有照片没传到服务器，重启后这些离线照片仍在本地（不丢），
 * 但店员可能误以为「更新把照片弄丢了」。因此在**确认安装前**先提醒一次：
 * - 引导「先传给服务器」（[onUpload]）；
 * - 「继续安装」才真正走安装流程。
 *
 * 注：原「备份到相册」按钮本期不实现（`BurstPhotoStore.writeToGallery` 未接入），
 * 属点了没用的死入口，已按 QA 走查移除——宁可少一个按钮，也不给店员「以为已备份」的错觉。
 */
@Composable
fun UpdateDataGuard(
    pendingCount: Int,
    onUpload: () -> Unit,
    onContinue: () -> Unit
) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = { },
        confirmButton = {
            TextButton(onClick = onContinue) { Text(stringResource(R.string.update_action_continue_install)) }
        },
        dismissButton = {
            TextButton(onClick = onUpload) { Text(ctx.getString(R.string.update_guard_upload)) }
        },
        title = { Text(ctx.getString(R.string.update_guard_title, pendingCount)) },
        text = {
            Text(
                stringResource(R.string.update_guard_body),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    )
}
