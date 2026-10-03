package com.xingqiyi.laundryphoto.ui.update

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.xingqiyi.laundryphoto.R
import com.xingqiyi.laundryphoto.sync.SyncManager
import com.xingqiyi.laundryphoto.update.UpdateContract.UpdateState

/**
 * 更新浮层：把编排器状态映射到具体 UI 的**唯一**入口。
 *
 * 规则（design.md §3.3 / §10.5）：
 * - 仅在 `allowed`（非拍照/扫码/连拍路由）时渲染，避免遮挡相机操作；
 * - 所有 UI 都是「状态 → 界面」的纯映射，**不持有任何更新状态**；
 * - 下载/校验/安装走非模态底部条（用户可继续干活），其余走模态对话框。
 */
@Composable
fun UpdateOverlay(vm: UpdateViewModel, allowed: Boolean) {
    if (!allowed) return
    val state by vm.state.collectAsState()
    val progress by vm.progress.collectAsState()
    val ctx = LocalContext.current
    // stringResource 只能在 Composable 上下文调用，先取出文案再进回调
    val copiedTip = stringResource(R.string.update_copied)

    when (val s = state) {
        is UpdateState.Idle -> { /* 无操作 */ }
        is UpdateState.Checking -> { /* 自动检查静默；手动检查在设置卡片内展示，浮层不挡 */ }
        is UpdateState.UpdateAvailable -> UpdateAvailableDialog(
            state = s,
            onDownload = vm::startDownload,
            onSkip = vm::skipVersion,
            onPostpone = vm::postpone
        )
        is UpdateState.Blocked -> UpdateBlockedDialog(
            plan = s.plan,
            onDownload = vm::startDownload
        )
        is UpdateState.Downloading -> UpdateDownloadPanel(
            state = s,
            progress = progress,
            onCancel = vm::cancelDownload
        )
        is UpdateState.Verifying -> UpdateProgressBanner(stringResource(R.string.update_verifying))
        is UpdateState.ReadyToInstall -> {
            if (s.pendingCount > 0) {
                UpdateDataGuard(
                    pendingCount = s.pendingCount,
                    onUpload = { SyncManager.enqueueImmediate(ctx) },
                    onBackup = { Toast.makeText(ctx, "备份到相册将在正式版提供", Toast.LENGTH_SHORT).show() },
                    onContinue = vm::confirmInstall
                )
            } else {
                UpdateReadyToInstallDialog(
                    state = s,
                    onInstall = vm::confirmInstall,
                    onLater = vm::dismissFailure
                )
            }
        }
        is UpdateState.Installing -> UpdateProgressBanner(stringResource(R.string.update_installing))
        is UpdateState.Failed -> UpdateFailedDialog(
            state = s,
            onRetry = vm::retry,
            onDismiss = vm::dismissFailure,
            onCopy = {
                val text = vm.copyDiagnostics()
                runCatching {
                    val cm = ctx.getSystemService(ClipboardManager::class.java)
                    cm?.setPrimaryClip(ClipData.newPlainText("update-diagnostics", text))
                }
                Toast.makeText(ctx, copiedTip, Toast.LENGTH_SHORT).show()
            }
        )
    }
}
