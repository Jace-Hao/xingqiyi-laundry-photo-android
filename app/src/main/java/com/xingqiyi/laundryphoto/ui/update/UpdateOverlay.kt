package com.xingqiyi.laundryphoto.ui.update

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    // 注意：以下 state/remember/launcher 必须在 `if (!allowed) return` **之前**初始化，
    // 否则 allowed 随路由变化时 composable 调用结构不一致（Compose 组合规则）。
    val state by vm.state.collectAsState()
    val progress by vm.progress.collectAsState()
    val ctx = LocalContext.current
    // stringResource 只能在 Composable 上下文调用，先取出文案再进回调
    val copiedTip = stringResource(R.string.update_copied)

    // 通知权限（Android 13+）：首次触发下载前申请一次，避免下载进度通知静默不出现。
    // 无论授权与否都不阻断下载，App 内进度面板照常显示。
    var notifyRequested by remember { mutableStateOf(false) }
    // 阻断页「退出应用」二次确认：先置位再真正退出，避免店员误触直接把 App 带回桌面
    var showExitConfirm by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 结果不阻断下载，忽略 */ }
    val ensureNotifyPermission: () -> Unit = {
        if (!notifyRequested && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifyRequested = true
            runCatching { permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
    }

    if (!allowed) return

    // 触发下载前先申请通知权限（同一时刻发起，权限弹窗浮在下载之上，不阻塞）
    val onStartDownload: () -> Unit = { ensureNotifyPermission(); vm.startDownload() }

    when (val s = state) {
        is UpdateState.Idle -> { /* 无操作 */ }
        is UpdateState.Checking -> { /* 自动检查静默；手动检查在设置卡片内展示，浮层不挡 */ }
        is UpdateState.UpdateAvailable -> UpdateAvailableDialog(
            state = s,
            onDownload = onStartDownload,
            onSkip = vm::skipVersion,
            onPostpone = vm::postpone
        )
        is UpdateState.Blocked -> UpdateBlockedDialog(
            plan = s.plan,
            onDownload = onStartDownload,
            onExitApp = { showExitConfirm = true }
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
            onRetry = { ensureNotifyPermission(); vm.retry() },
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

    // 二次确认浮在阻断页之上；点「继续更新」只关掉自己，阻断页仍在
    if (showExitConfirm) {
        UpdateExitConfirmDialog(
            onConfirmExit = { exitApp(ctx) },
            onStay = { showExitConfirm = false }
        )
    }
}

/**
 * 退出应用到桌面：优先 [Activity.finishAffinity]（关闭当前任务栈、回桌面），
 * 不用 `exitProcess`（会杀进程、丢内存上下文）。
 * 若拿不到 Activity（极少见）则退化为拉起系统桌面——同样不杀进程。
 */
private fun exitApp(context: Context) {
    val activity = context.findActivity()
    if (activity != null) {
        activity.finishAffinity()
    } else {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}

/** Compose 的 LocalContext 可能是 ContextWrapper 包裹的 Activity，逐层解包。 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
