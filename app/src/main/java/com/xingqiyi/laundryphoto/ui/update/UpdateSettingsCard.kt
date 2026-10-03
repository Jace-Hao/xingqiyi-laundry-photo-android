package com.xingqiyi.laundryphoto.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xingqiyi.laundryphoto.R
import com.xingqiyi.laundryphoto.ui.components.Dimens
import com.xingqiyi.laundryphoto.ui.components.InfoRow
import com.xingqiyi.laundryphoto.update.UpdateContract.UpdateState

/**
 * 设置页「软件更新」卡片。
 *
 * 展示当前版本、是否有可用更新，并提供「检查更新」与「清理更新缓存」两个动作。
 * 所有状态来自 [UpdateViewModel]（亦即编排器单例），与全局浮层共享同一真相。
 */
@Composable
fun UpdateSettingsCard(vm: UpdateViewModel, versionName: String) {
    val state by vm.state.collectAsState()
    val progress by vm.progress.collectAsState()

    val statusText = when (val s = state) {
        is UpdateState.Idle -> "已是最新"
        is UpdateState.Checking -> "正在检查…"
        is UpdateState.UpdateAvailable -> "有可用更新 v${s.plan.target.versionName}"
        is UpdateState.Blocked -> "有可用更新 v${s.plan.target.versionName}"
        is UpdateState.Downloading -> "正在下载 v${s.plan.target.versionName} ${progress?.percent ?: 0}%"
        is UpdateState.Verifying -> "正在校验安装包…"
        is UpdateState.ReadyToInstall -> "v${s.plan.target.versionName} 已下载，可安装"
        is UpdateState.Installing -> "正在安装…"
        is UpdateState.Failed -> "上次更新失败"
    }

    val busy = state is UpdateState.Checking || state is UpdateState.Downloading ||
        state is UpdateState.Verifying || state is UpdateState.Installing

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("软件更新", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            InfoRow("当前版本", "v$versionName")
            Spacer(Modifier.height(4.dp))
            Text(statusText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { vm.checkManual() },
                    enabled = !busy,
                    modifier = Modifier.weight(1f)
                ) { Text("检查更新") }
                OutlinedButton(
                    onClick = { vm.clearCache() },
                    modifier = Modifier.weight(1f)
                ) { Text("清理更新缓存") }
            }
        }
    }
}
