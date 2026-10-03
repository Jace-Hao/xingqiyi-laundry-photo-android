package com.xingqiyi.laundryphoto.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File

/**
 * 安装器（实现层）：`PackageInstaller.Session` 主路径 + `ACTION_VIEW` 降级。
 *
 * ## 主路径：结构化回执
 *
 * `Session` 提交时把回执交给一个**动态注册的广播接收器**（`UpdateInstallReceiver`，
 * `RECEIVER_NOT_EXPORTED`），系统安装完成后回发 `EXTRA_STATUS`——
 * 这让我们能拿到 `SUCCESS / BLOCKED / CONFLICT / INCOMPATIBLE / INVALID / STORAGE` 做精细化分诊，
 * 而不是只拿到「装没装上」（NFR-C4 / U-10 分诊表）。
 *
 * ## 回执丢失（NFR-R1）
 *
 * 安装会替换本进程：用户点「安装」后系统杀掉我们、拉起新 App。若回执广播在进程死亡前没送达，
 * 走 `UpdateCoordinatorImpl` 的冷启动恢复——比对已安装 `versionCode` 与 `preUpdateVersionCode`
 * 判定成功。因此这里只需把回执投递到进程内的 `InstallResultBus`，进程没了也没关系。
 *
 * ## 降级（U-10）
 *
 * Session 打开/写入抛异常（部分 ROM 的 Session 实现有坑）时，回退到
 * `ACTION_VIEW` + FileProvider `content://` + `FLAG_GRANT_READ_URI_PERMISSION`，
 * 把安装交给系统安装器；这条路径没有结构化回执，成功与否同样交给冷启动恢复判定。
 */
class SessionPackageInstaller(
    private val context: Context
) : UpdateContract.UpdateInstaller {

    private val pm = context.packageManager
    private val packageInstaller = pm.packageInstaller

    override fun canRequestPackageInstalls(): Boolean = pm.canRequestPackageInstalls()

    override fun install(
        file: File,
        spec: UpdateContract.RemoteApk,
        self: UpdateContract.SelfVersion
    ): UpdateContract.InstallHandle {
        return try {
            startSession(file, spec, self)
        } catch (e: Throwable) {
            // Session 主路径失败：降级到 ACTION_VIEW（部分 ROM 的 Session 实现不稳定）
            IntentInstaller.install(context, file)
        }
    }

    private fun startSession(
        file: File,
        spec: UpdateContract.RemoteApk,
        self: UpdateContract.SelfVersion
    ): UpdateContract.InstallHandle {
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = packageInstaller.createSession(params)
        val session = packageInstaller.openSession(sessionId)

        session.openWrite("xqy_update", 0, file.length()).use { out ->
            file.inputStream().use { inp ->
                val buf = ByteArray(8 * 1024)
                var n: Int
                while (inp.read(buf).also { n = it } != -1) {
                    out.write(buf, 0, n)
                }
            }
            session.fsync(out)
        }

        val intent = Intent(context, UpdateInstallReceiver::class.java).apply {
            action = ACTION_UPDATE_INSTALL
            `package` = self.packageName
        }
        val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pending = PendingIntent.getBroadcast(context, sessionId, intent, flag)
        @Suppress("MissingPermission")
        session.commit(pending.intentSender)
        // commit 后系统接管会话，关闭本地句柄释放资源；已提交后再 abort 会抛异常，忽略即可
        runCatching { session.close() }

        return UpdateContract.InstallHandle(
            sessionId = sessionId,
            events = InstallResultBus.events,
            // 取消 = 放弃会话。commit 之后系统已接管，再 abandon 会抛异常，
            // 因此这里按 sessionId 走 PackageInstaller.abandonSession（对已提交的会话是安全的 no-op）。
            onCancel = { runCatching { packageInstaller.abandonSession(sessionId) } }
        )
    }

    companion object {
        const val ACTION_UPDATE_INSTALL = "com.xingqiyi.laundryphoto.action.UPDATE_INSTALL"
    }
}

/**
 * 安装结果总线：接收器把系统回执投递到这里，安装器会话持有它的 Flow。
 * 进程内单例即可——进程被安装替换后由冷启动恢复兜底，无需跨进程。
 */
internal object InstallResultBus {
    private val _events = MutableSharedFlow<UpdateContract.InstallEvent>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<UpdateContract.InstallEvent> = _events.asSharedFlow()

    /** 供接收器投递回执；满了丢最旧的，绝不阻塞 BroadcastReceiver 主线程。 */
    fun tryEmit(event: UpdateContract.InstallEvent): Boolean = _events.tryEmit(event)
}

/** 系统安装回执接收器（Manifest 注册，exported=false）。 */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context?, intent: Intent?) {
        if (intent?.action != SessionPackageInstaller.ACTION_UPDATE_INSTALL) return
        val code = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE
        )
        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        val event = when (code) {
            PackageInstaller.STATUS_SUCCESS -> UpdateContract.InstallEvent.Succeeded
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // 系统要求先走一次「未知来源安装」确认（Android 8+，Android 14 尤甚）：
                // 拉起系统确认页，真正的安装由系统在其后继续，并再次回发 SUCCESS/FAILURE。
                @Suppress("DEPRECATION")
                val confirm: Intent? = intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { ctx?.startActivity(confirm) }
                UpdateContract.InstallEvent.Installing
            }
            else -> UpdateContract.InstallEvent.Failed(
                kind = mapStatus(code),
                rawStatus = code,
                message = msg
            )
        }
        InstallResultBus.tryEmit(event)
    }
}

/** 系统 `PackageInstaller` 状态码 → 我们的分诊枚举（U-10 分诊表）。 */
internal fun mapStatus(code: Int): UpdateContract.InstallFailure = when (code) {
    PackageInstaller.STATUS_FAILURE_BLOCKED -> UpdateContract.InstallFailure.BLOCKED
    PackageInstaller.STATUS_FAILURE_CONFLICT -> UpdateContract.InstallFailure.CONFLICT
    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> UpdateContract.InstallFailure.INCOMPATIBLE
    PackageInstaller.STATUS_FAILURE_INVALID -> UpdateContract.InstallFailure.INVALID
    PackageInstaller.STATUS_FAILURE_STORAGE -> UpdateContract.InstallFailure.STORAGE
    PackageInstaller.STATUS_FAILURE_ABORTED -> UpdateContract.InstallFailure.ABORTED
    else -> UpdateContract.InstallFailure.UNKNOWN
}

/**
 * 安装器降级路径：把 APK 以 `content://`（FileProvider）交给系统安装器。
 *
 * 这条路径拿不到结构化回执，所以只发一个 `Installing` 事件占位；
 * 真正「装没装上」由 `UpdateCoordinatorImpl` 冷启动比对 `versionCode` 判定（NFR-R1）。
 */
object IntentInstaller {
    fun install(context: Context, file: File): UpdateContract.InstallHandle {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
        val events = MutableSharedFlow<UpdateContract.InstallEvent>(extraBufferCapacity = 2)
        events.tryEmit(UpdateContract.InstallEvent.Installing)
        return UpdateContract.InstallHandle(sessionId = null, events = events.asSharedFlow()) { }
    }
}
