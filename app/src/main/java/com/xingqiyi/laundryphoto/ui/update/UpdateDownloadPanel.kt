package com.xingqiyi.laundryphoto.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xingqiyi.laundryphoto.R
import com.xingqiyi.laundryphoto.update.UpdateContract
import java.text.DecimalFormat

/**
 * 下载面板（非模态底部条）。
 *
 * 设计强调「下载时可以正常干活」（U-COPY-07），因此**不**用模态对话框挡住界面，
 * 只在底部放一条可取消的进度条；浮层只在「允许弹窗」的路由（非拍照/扫码）显示。
 */
@Composable
fun UpdateDownloadPanel(
    state: UpdateContract.UpdateState.Downloading,
    progress: UpdateContract.DownloadProgress?,
    onCancel: () -> Unit
) {
    val target = state.plan.target
    val percent = progress?.percent ?: 0
    val text = if (progress != null && progress.totalBytes > 0) {
        stringResource(
            R.string.update_progress,
            humanBytes(progress.bytesRead),
            humanBytes(progress.totalBytes),
            humanBytes(progress.bytesPerSec)
        )
    } else {
        stringResource(R.string.update_downloading_percent, percent)
    }
    val retryHint = if (state.retryInMs != null) {
        stringResource(R.string.update_retry_soon)
    } else {
        ""
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Card(
            Modifier.fillMaxWidth().padding(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Text(
                    stringResource(R.string.update_downloading_title, target.versionName),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { (percent.coerceIn(0, 100)) / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Text(text + retryHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.update_action_cancel)) }
                }
            }
        }
    }
}

/**
 * 校验中 / 安装中的非模态底部条（不挡操作，安装完成由系统接手）。
 */
@Composable
fun UpdateProgressBanner(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Card(
            Modifier.fillMaxWidth().padding(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Text(text, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), progress = { 1f })
            }
        }
    }
}

/** 文件大小人性化（与 Notifier.humanBytes 同算法，避免跨模块依赖）。 */
internal fun humanBytes(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1) "${DecimalFormat("#.#").format(mb)} MB" else "${bytes / 1024} KB"
}
