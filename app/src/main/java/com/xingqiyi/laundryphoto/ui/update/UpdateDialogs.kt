package com.xingqiyi.laundryphoto.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xingqiyi.laundryphoto.R
import com.xingqiyi.laundryphoto.update.UpdateContract
import com.xingqiyi.laundryphoto.ui.theme.Danger

/**
 * 「发现新版本」对话框（可选更新 / 强制更新均可在此展示）。
 *
 * - 非强制：提供「立即更新」与「跳过此版本」；
 * - 强制（宽限内）：额外提供「稍后再说」（剩余可推迟次数 > 0 时）。
 */
@Composable
fun UpdateAvailableDialog(
    state: UpdateContract.UpdateState.UpdateAvailable,
    onDownload: () -> Unit,
    onSkip: () -> Unit,
    onPostpone: () -> Unit
) {
    val plan = state.plan
    val notes = if (plan.target.notes.isBlank()) {
        androidx.compose.ui.platform.LocalContext.current.getString(R.string.update_no_notes)
    } else plan.target.notes

    AlertDialog(
        // PRD §3.6.1 ②：点外框空白**不可**关闭，避免店员随手一点就永久错过这一版。
        // 跳过只能由显式的「跳过此版本」按钮触发（mandatory 时无此按钮）。
        onDismissRequest = { },
        confirmButton = {
            TextButton(onClick = onDownload) { Text("立即更新") }
        },
        dismissButton = {
            Row {
                if (plan.mandatory && state.remainingPostpone > 0) {
                    TextButton(onClick = onPostpone) { Text("稍后再说") }
                }
                if (!plan.mandatory) {
                    TextButton(onClick = onSkip) { Text("跳过此版本") }
                }
            }
        },
        title = { Text(androidx.compose.ui.platform.LocalContext.current.getString(R.string.update_found_title, plan.target.versionName)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (state.previouslySkipped) {
                    Text(
                        androidx.compose.ui.platform.LocalContext.current.getString(R.string.update_skipped_hint, plan.target.versionName),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (plan.mandatory && state.remainingPostpone > 0) {
                    Text(
                        androidx.compose.ui.platform.LocalContext.current.getString(R.string.update_force_postpone, state.remainingPostpone),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    androidx.compose.foundation.layout.Spacer(Modifier.padding(4.dp))
                }
                Text(
                    notes,
                    style = MaterialTheme.typography.bodyMedium,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    )
}

/** 强制阻断对话框（宽限期已过，必须更新才能继续）。 */
@Composable
fun UpdateBlockedDialog(
    plan: UpdateContract.UpdatePlan,
    onDownload: () -> Unit,
    onExitApp: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { },
        confirmButton = {
            TextButton(onClick = onDownload) { Text("立即更新") }
        },
        // 阻断态不能把店员锁死：必须提供「退出应用」退路，否则下载/安装连续失败时界面成死胡同
        dismissButton = {
            TextButton(onClick = onExitApp) { Text("退出应用") }
        },
        title = { Text(androidx.compose.ui.platform.LocalContext.current.getString(R.string.update_force_title)) },
        text = {
            Column {
                Text(
                    androidx.compose.ui.platform.LocalContext.current.getString(
                        R.string.update_block_subtitle, plan.target.versionName, plan.target.fileName
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
                androidx.compose.foundation.layout.Spacer(Modifier.padding(4.dp))
                Text(
                    androidx.compose.ui.platform.LocalContext.current.getString(R.string.update_block_back),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    )
}

/** 「待安装」对话框：未授权走权限引导，已授权则直接「立即安装」。 */
@Composable
fun UpdateReadyToInstallDialog(
    state: UpdateContract.UpdateState.ReadyToInstall,
    onInstall: () -> Unit,
    onLater: () -> Unit
) {
    if (!state.canInstallUnknownSources) {
        InstallPermissionGate(onInstall = onInstall)
        return
    }
    AlertDialog(
        onDismissRequest = onLater,
        confirmButton = {
            TextButton(onClick = onInstall) { Text("立即安装") }
        },
        dismissButton = {
            TextButton(onClick = onLater) { Text("稍后") }
        },
        title = { Text(androidx.compose.ui.platform.LocalContext.current.getString(R.string.update_found_title, state.plan.target.versionName)) },
        text = {
            Column {
                Text(
                    androidx.compose.ui.platform.LocalContext.current.getString(R.string.update_reassure),
                    style = MaterialTheme.typography.bodyMedium
                )
                androidx.compose.foundation.layout.Spacer(Modifier.padding(4.dp))
                Text(
                    androidx.compose.ui.platform.LocalContext.current.getString(R.string.update_guard_title, state.pendingCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    )
}

/**
 * 「退出应用」二次确认对话框。
 *
 * 阻断态对话框每次打开 App 都会弹，店员误触模态按钮很常见；一次误触导致 App
 * 自己退到桌面，在店员眼里就是「APP 自己崩了」，这类报障远比多按一次按钮贵。
 * 因此退出必须二次确认，且否定按钮用「继续更新」而不是「取消」——把用户导回正路。
 */
@Composable
fun UpdateExitConfirmDialog(
    onConfirmExit: () -> Unit,
    onStay: () -> Unit
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    AlertDialog(
        onDismissRequest = onStay,
        confirmButton = {
            TextButton(onClick = onConfirmExit) {
                Text(ctx.getString(R.string.update_exit_confirm_ok), color = Danger)
            }
        },
        dismissButton = {
            TextButton(onClick = onStay) { Text(ctx.getString(R.string.update_exit_confirm_cancel)) }
        },
        title = { Text(ctx.getString(R.string.update_exit_confirm_title)) },
        text = { Text(ctx.getString(R.string.update_exit_confirm_message)) }
    )
}

/** 失败对话框：展示错误文案 + 重试 / 复制错误信息 / 关闭。 */
@Composable
fun UpdateFailedDialog(
    state: UpdateContract.UpdateState.Failed,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onCopy: () -> Unit
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val copy = state.copy
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            if (copy.retryable) {
                TextButton(onClick = onRetry) { Text("重试") }
            } else {
                TextButton(onClick = onDismiss) { Text("知道了") }
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onCopy) { Text("复制错误信息") }
                if (copy.retryable) {
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
            }
        },
        title = { Text("更新失败") },
        text = {
            Text(
                ctx.getString(copy.stringRes, *copy.formatArgs.toTypedArray()),
                style = MaterialTheme.typography.bodyMedium,
                color = if (copy.retryable) MaterialTheme.colorScheme.onSurface else Danger
            )
        }
    )
}
