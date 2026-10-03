package com.xingqiyi.laundryphoto.ui.update

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.xingqiyi.laundryphoto.BuildConfig
import com.xingqiyi.laundryphoto.R

/**
 * 安装未知来源权限引导。
 *
 * Android 8+ 默认禁止「安装不是应用商店下载的 App」，必须用户到系统设置手动开启。
 * 这里只解释「为什么」并给出入口；真正「装没装上」由 [com.xingqiyi.laundryphoto.update.UpdateCoordinatorImpl]
 * 冷启动比对 versionCode 兜底，因此即使用户中途放弃也不会卡死。
 *
 * 注意：用户授予权限后，安装动作由 `onInstall` 触发——[com.xingqiyi.laundryphoto.update.SessionPackageInstaller.canRequestPackageInstalls]
 * 在调用时实时复检，无需刷新状态。
 */
@Composable
fun InstallPermissionGate(onInstall: () -> Unit) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = { },
        confirmButton = {
            TextButton(onClick = {
                runCatching {
                    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                        .setData(Uri.parse("package:${BuildConfig.APPLICATION_ID}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                }
            }) { Text("去设置授权") }
        },
        dismissButton = {
            TextButton(onClick = onInstall) { Text("我已允许，继续安装") }
        },
        title = { Text("需要安装权限") },
        text = {
            Text(
                ctx.getString(R.string.update_perm_install),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    )
}
