package com.xingqiyi.laundryphoto.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.xingqiyi.laundryphoto.R
import java.text.DecimalFormat

/**
 * 通知封装。
 *
 * Android 8.0 起通知必须归属渠道，且渠道一旦创建其重要性就由用户掌控，
 * 因此这里只在首次使用时创建、之后复用（重复创建无效但会触发一次系统写入，没必要）。
 *
 * 分三个渠道是有意为之：
 * - 同步进度（xqy_sync）：低打扰，IMPORANCE_LOW；
 * - 账号被顶下线/强制更新（xqy_alert）：必须看见，DEFAULT；
 * - 软件更新（xqy_update）：下载进度/就绪/失败，DEFAULT——与同步进度分开，
 *   避免用户在设置里关掉同步渠道后连「更新就绪」也收不到。
 */
object Notifier {

    const val CHANNEL_SYNC = "xqy_sync"
    const val CHANNEL_ALERT = "xqy_alert"
    const val CHANNEL_UPDATE = "xqy_update"

    private const val ID_SYNC_PROGRESS = 2001
    private const val ID_SYNC_DONE = 2002
    private const val ID_SYNC_FAILED = 2003
    private const val ID_ALERT = 2004
    private const val ID_FORCE_UPDATE = 2005

    // 软件更新通知 ID（与既有 2001–2005 错开，避免互相 cancel）
    private const val ID_UPDATE_PROGRESS = 2101
    private const val ID_UPDATE_READY = 2102
    private const val ID_UPDATE_FAILED = 2103

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_SYNC) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_SYNC,
                    context.getString(R.string.channel_sync_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = context.getString(R.string.channel_sync_desc) }
            )
        }
        if (mgr.getNotificationChannel(CHANNEL_ALERT) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ALERT,
                    context.getString(R.string.channel_alert_name),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply { description = context.getString(R.string.channel_alert_desc) }
            )
        }
        if (mgr.getNotificationChannel(CHANNEL_UPDATE) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_UPDATE,
                    context.getString(R.string.update_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply { description = context.getString(R.string.update_channel_desc) }
            )
        }
    }

    private fun manager(context: Context): NotificationManager? =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    /** 回到 App 的 PendingIntent（点通知回到更新浮层）。 */
    private fun appIntent(context: Context, requestCode: Int): PendingIntent {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getActivity(context, requestCode, launch, flag)
    }

    private fun humanBytes(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1) "${DecimalFormat("#.#").format(mb)} MB" else "${bytes / 1024} KB"
    }

    // ---------- 离线补传（原有） ----------

    fun notifySyncProgress(context: Context, current: Int, total: Int) {
        val n = NotificationCompat.Builder(context, CHANNEL_SYNC)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("正在补传离线照片")
            .setContentText("$current / $total")
            .setProgress(total, current, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        manager(context)?.notify(ID_SYNC_PROGRESS, n)
    }

    fun notifySyncDone(context: Context, count: Int) {
        manager(context)?.cancel(ID_SYNC_PROGRESS)
        if (count <= 0) return
        val n = NotificationCompat.Builder(context, CHANNEL_SYNC)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("离线照片已同步")
            .setContentText("已上传 $count 张离线拍摄的照片到服务器")
            .setAutoCancel(true)
            .build()
        manager(context)?.notify(ID_SYNC_DONE, n)
    }

    fun notifySyncFailed(context: Context, count: Int, reason: String) {
        manager(context)?.cancel(ID_SYNC_PROGRESS)
        val n = NotificationCompat.Builder(context, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("有 $count 张离线照片未同步")
            .setContentText(reason)
            .setAutoCancel(true)
            .build()
        manager(context)?.notify(ID_SYNC_FAILED, n)
    }

    /** 账号被顶下线：属于必须看见的提醒，点开回到 App 即可看到登录页 */
    fun notifySessionRevoked(context: Context, message: String) {
        val n = NotificationCompat.Builder(context, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("账号已退出登录")
            .setContentText(message)
            .setAutoCancel(true)
            .build()
        manager(context)?.notify(ID_ALERT, n)
    }

    // ---------- 软件更新（新增 xqy_update 渠道） ----------

    /** 下载进度：同一 id 反复更新，不刷出一串通知（沿用 notifySyncProgress 写法）。 */
    fun notifyUpdateProgress(
        context: Context,
        version: String,
        percent: Int,
        downloadedBytes: Long,
        totalBytes: Long
    ) {
        val text = if (totalBytes > 0) {
            "已下载 ${humanBytes(downloadedBytes)} / ${humanBytes(totalBytes)} · $percent%"
        } else {
            "下载中 $percent%"
        }
        val n = NotificationCompat.Builder(context, CHANNEL_UPDATE)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("正在下载 v$version")
            .setContentText(text)
            .setProgress(100, percent.coerceIn(0, 100), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(appIntent(context, ID_UPDATE_PROGRESS))
            .build()
        manager(context)?.notify(ID_UPDATE_PROGRESS, n)
    }

    /** 下载完成 / 校验完成：提示「点此安装」，点击回到浮层。 */
    fun notifyUpdateReady(context: Context, version: String) {
        manager(context)?.cancel(ID_UPDATE_PROGRESS)
        val n = NotificationCompat.Builder(context, CHANNEL_UPDATE)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("v$version 已下载完成")
            .setContentText("点此安装")
            .setAutoCancel(true)
            .setContentIntent(appIntent(context, ID_UPDATE_READY))
            .build()
        manager(context)?.notify(ID_UPDATE_READY, n)
    }

    /** 取消更新相关通知（失败或清理时调用）。 */
    fun cancelUpdate(context: Context) {
        manager(context)?.cancel(ID_UPDATE_PROGRESS)
        manager(context)?.cancel(ID_UPDATE_READY)
    }

    /** 安装失败提示（供编排器在 Failed 时调用）。 */
    fun notifyUpdateFailed(context: Context, version: String, message: String) {
        manager(context)?.cancel(ID_UPDATE_PROGRESS)
        manager(context)?.cancel(ID_UPDATE_READY)
        val n = NotificationCompat.Builder(context, CHANNEL_UPDATE)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("v$version 更新失败")
            .setContentText(message)
            .setAutoCancel(true)
            .setContentIntent(appIntent(context, ID_UPDATE_FAILED))
            .build()
        manager(context)?.notify(ID_UPDATE_FAILED, n)
    }
}
