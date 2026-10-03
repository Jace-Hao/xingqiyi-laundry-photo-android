package com.xingqiyi.laundryphoto.update

import com.xingqiyi.laundryphoto.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * 内置在线更新：六大接口与全部值对象（契约层）。
 *
 * ## 模块边界（见 mobile-updater-design.md §1.3 / §10.1）
 *
 * 这一层只定义「角色能做什么」，不写任何实现，也不依赖任何 Android 框架类。
 * 实现分散在同包的其他文件里（Server* / OkHttp* / Default* / DataStore* / Session* / *Impl）。
 * 纯规则（无 Android 依赖）统一用 `object`，命名以 *Resolver / *Mapper / *Comparator / VerifyRules / SpaceRule 结尾。
 *
 * ## 为什么 DTO 不出现在本包（项目铁律）
 *
 * Gson 反序列化依赖字段名反射，而 release 包的 R8 会把无人「直接引用」的字段剥掉
 * （详见 `ProguardKeepRuleTest` 的事故说明）。因此所有会被 Gson 序列化的 model 都只放在
 * `data.model` 包里，由守卫测试 `ProguardKeepRuleTest.dtoPackages` 守住；update 包内
 * 只允许放这些 Kotlin 值对象（全部字段参与编译期类型检查，混淆不会动它们）。
 */
object UpdateContract {

    /** 本 App 自身的版本身份。全部来自 BuildConfig，可注入以便单测。 */
    data class SelfVersion(
        /** BuildConfig.VERSION_NAME；debug 构建形如 "1.0.0-debug" */
        val versionName: String,
        /** BuildConfig.VERSION_CODE（单调正整数，Android 的权威值） */
        val versionCode: Int,
        /** BuildConfig.APPLICATION_ID；debug 构建为 "com.xingqiyi.laundryphoto.debug" */
        val packageName: String,
        /** BuildConfig.RELEASE_APPLICATION_ID（两个 variant 同值的真实包名） */
        val releasePackageName: String,
        val isDebug: Boolean
    ) {
        /** 参与版本比较的形式：去掉第一个 "-debug" 类后缀后的 "1.0.0" */
        val comparableName: String get() = versionName.substringBefore('-')
    }

    /** 服务端下发的候选 APK 描述（与来源是新接口还是老接口无关）。 */
    data class RemoteApk(
        val fileName: String,
        val versionName: String,
        val sizeBytes: Long,
        val sha256: String,
        val notes: String,
        val notesSource: String
    )

    enum class UpdateOrigin { MOBILE_API, LEGACY_CHECK_UPDATE, FORCE_UPDATE, NONE }

    enum class UpdateStage { CHECK, DOWNLOAD, VERIFY, INSTALL }

    /** 版本发现结果。一次性回答「这台服务器上，对我而言有什么」。 */
    data class SourceReport(
        /** 可选轨最新 APK；null 表示这台服务器没有可用的手机版安装包 */
        val latestApk: RemoteApk?,
        /** 管理员强推且强推文件确实是个 APK（.apk 结尾且 fileExists），否则 null */
        val forceTrack: ForceTrack?,
        /** 本次可选轨信息是通过哪个通道拿到的，用于日志与降级判断 */
        val origin: UpdateOrigin,
        /** 服务器 host（含端口），用于节流分键 */
        val serverHost: String,
        /**
         * 服务端是否不支持移动端新接口（命中 `ApiError.Unsupported` 且无任何可用包）。
         * 仅用于手动检查时给出「服务端版本较旧」的明确提示，自动检查一律静默。
         */
        val unsupported: Boolean = false
    )

    data class ForceTrack(val fileName: String, val version: String, val fileExists: Boolean)

    /**
     * 更新发现源：一次性回答「这台服务器上，对我而言有什么」。
     *
     * 实现（[ServerUpdateSource]）负责新接口优先 → 老接口降级的决策，
     * 编排器（[UpdateCoordinatorImpl]）**不做任何 API 选择决策**，只消费这里给出的 [SourceReport]。
     * 该接口刻意只声明一个方法，便于在单测里用 Fake 完全替代网络。
     */
    interface UpdateSource {
        /** 永远成功返回 [SourceReport]；任何网络/解析失败都收敛成「无可用包」，不抛异常。 */
        suspend fun discover(self: SelfVersion): SourceReport
    }

    // ---------- 下载 ----------

    data class DownloadSpec(
        val fileName: String,
        /** 完整下载 URL（不含连接码） */
        val url: String,
        val expectedBytes: Long,
        val sha256: String,
        /** 额外请求头；连接码由调用方以 x-api-token 注入，不由本类决定 */
        val headers: Map<String, String>
    )

    data class DownloadProgress(
        val bytesRead: Long,
        val totalBytes: Long,
        val percent: Int,
        val bytesPerSec: Long,
        val attempt: Int
    )

    enum class DownloadFailure {
        DNS, CONNECT_TIMEOUT, READ_TIMEOUT, CONNECTION_RESET, IO,
        STALLED,
        TOO_SLOW,
        HTTP_401, HTTP_403, HTTP_404, HTTP_5XX, HTTP_OTHER,
        DISK_FULL
    }

    sealed class DownloadOutcome {
        data class Success(val file: File, val bytesRead: Long) : DownloadOutcome()
        data class Failure(
            val kind: DownloadFailure,
            val httpCode: Int?,
            val message: String
        ) : DownloadOutcome()

        data object Cancelled : DownloadOutcome()
    }

    interface UpdateDownloader {
        /**
         * @param dest 最终文件路径；本方法内部先写 dest.part，成功后再原子 rename
         * @param onProgress 已节流（≥400ms 或百分比变化 ≥1%）的回调，可安全直接驱动 Compose
         * @return 永不抛异常；所有失败收敛为 DownloadOutcome.Failure
         */
        suspend fun download(
            spec: DownloadSpec,
            dest: File,
            onProgress: suspend (DownloadProgress) -> Unit
        ): DownloadOutcome

        fun cancel()
    }

    // ---------- 校验 ----------

    enum class VerifyFailure {
        IO, NOT_AN_APK, SIZE_MISMATCH, HASH_MISMATCH, PACKAGE_MISMATCH, VERSION_NOT_NEWER
    }

    sealed class VerifyResult {
        data class Ok(val packageName: String, val versionCode: Long, val sizeBytes: Long) : VerifyResult()

        data class Fail(
            val failure: VerifyFailure,
            /** 期望值（人类可读），直接进「复制错误信息」 */
            val expected: String,
            val actual: String,
            val cause: Throwable? = null
        ) : VerifyResult()
    }

    interface UpdateVerifier {
        /**
         * 四级校验，顺序不可变（先便宜的、先信息量大的）：
         *   1. size       —— 服务端给了且 != 0 才校；否则跳过（不阻断）
         *   2. sha256     —— 服务端给了才校；否则跳过（不阻断）
         *   3. packageName —— 必须等于 SelfVersion.packageName；debug 构建额外允许 releasePackageName
         *   4. versionCode —— 必须**严格大于** SelfVersion.versionCode
         * 1、2 可跳过，3、4 永不跳过。失败时**调用方负责删除文件**。
         */
        suspend fun verify(file: File, spec: RemoteApk, self: SelfVersion): VerifyResult
    }

    // ---------- 安装 ----------

    enum class InstallFailure {
        BLOCKED,
        CONFLICT,
        INCOMPATIBLE,
        INVALID,
        STORAGE,
        ABORTED,
        UNKNOWN,
        RECEIPT_LOST
    }

    sealed class InstallEvent {
        data object Pending : InstallEvent()
        data object Installing : InstallEvent()
        data object Succeeded : InstallEvent()
        data class Failed(val kind: InstallFailure, val rawStatus: Int, val message: String) : InstallEvent()
    }

    /** 一次安装会话的句柄：可取消、可流式观察回执。 */
    class InstallHandle(
        val sessionId: Int?,
        val events: Flow<InstallEvent>,
        private val onCancel: () -> Unit
    ) {
        fun cancel() = onCancel()
    }

    interface UpdateInstaller {
        /** 是否已获得「安装未知应用」授权。false 不代表失败，而是需要走权限引导 */
        fun canRequestPackageInstalls(): Boolean

        /**
         * 递交 APK 给系统。非阻塞：本方法返回时只是「会话已创建并提交」，
         * 真正结果通过 [InstallHandle.events] 异步送达；成功后进程会被系统替换。
         */
        fun install(file: File, spec: RemoteApk, self: SelfVersion): InstallHandle
    }

    // ---------- 策略持久化 ----------

    data class ForcePostpone(
        val forceVersion: String,
        val count: Int,
        val firstPromptAtMs: Long
    )

    /** 冷启动恢复用的轻量快照：刻意用扁平字段而非 JSON（见 UpdateCoordinatorImpl 说明）。 */
    data class PendingUpdateSnapshot(
        val phase: UpdateStage,
        val fileName: String, val version: String, val sizeBytes: Long,
        val sha256: String, val notes: String, val notesSource: String,
        val mandatory: Boolean, val forceVersion: String,
        val startedAtMs: Long,
        val preUpdateVersionCode: Int
    )

    interface UpdatePolicyStore {
        // ---- 冷启动节流（>24h，按服务器 host 分键） ----
        suspend fun lastCheckAt(serverHost: String): Long
        suspend fun markChecked(serverHost: String, atMs: Long)

        // ---- 跳过此版本 ----
        fun skippedVersion(): Flow<String>
        suspend fun setSkippedVersion(version: String?)

        // ---- 强制更新宽限 ----
        suspend fun postpone(): ForcePostpone
        suspend fun bumpPostpone(forceVersion: String, nowMs: Long): ForcePostpone
        suspend fun clearPostpone()

        // ---- 冷启动恢复 ----
        suspend fun pendingSnapshot(): PendingUpdateSnapshot?
        suspend fun savePendingSnapshot(s: PendingUpdateSnapshot?)
        suspend fun setSnapshotPhase(phase: UpdateStage)

        // ---- 安装后 30 分钟保护窗 ----
        suspend fun protectUntilMs(): Long
        suspend fun setProtectUntil(ms: Long)
    }

    // ---------- 编排 ----------

    enum class CheckTrigger { AUTO_BOOT, MANUAL_SETTINGS, RETRY_PROMPT }

    sealed class UpdateState {
        data object Idle : UpdateState()
        data object Checking : UpdateState()

        data class UpdateAvailable(
            val plan: UpdatePlan,
            val mandatory: Boolean,
            val previouslySkipped: Boolean,
            val remainingPostpone: Int,
            val graceEndsAtMs: Long
        ) : UpdateState()

        data class Blocked(val plan: UpdatePlan) : UpdateState()

        data class Downloading(
            val plan: UpdatePlan,
            val attempt: Int,
            val retryInMs: Long?
        ) : UpdateState()

        data class Verifying(val plan: UpdatePlan) : UpdateState()

        data class ReadyToInstall(
            val plan: UpdatePlan,
            val pendingCount: Int,
            val canInstallUnknownSources: Boolean
        ) : UpdateState()

        data class Installing(val plan: UpdatePlan) : UpdateState()

        data class Failed(
            val stage: UpdateStage,
            val copy: FailureCopy,
            val plan: UpdatePlan?
        ) : UpdateState()
    }

    /** 失败文案与重试策略的纯数据，由 UpdateFailureMapper 产生，便于单测。 */
    data class FailureCopy(
        val stringRes: Int,
        val formatArgs: List<String>,
        val retryable: Boolean,
        val promoteCleanCache: Boolean
    )

    data class UpdatePlan(
        val target: RemoteApk,
        val mandatory: Boolean,
        val origin: UpdateOrigin,
        val discoveredAtMs: Long,
        /** 管理员强推的版本号，空串表示本次没有强推轨 */
        val forceVersion: String,
        /** 剩余可推迟次数（0 表示不能再推迟） */
        val remainingPostpone: Int,
        /** 宽限期截止时间戳（firstPromptAt + 24h），仅 mandatory 时有意义 */
        val graceEndsAtMs: Long
    )

    /** 日志条目。 */
    data class UpdateLogEntry(
        val atIso: String,
        val level: Char,
        val tag: String,
        val event: String,
        val message: String,
        val elapsedMs: Long?,
        val bytes: Long?
    )

    interface UpdateCoordinator {
        /** 唯一真源。UI 只做状态 → 界面的纯映射。 */
        val state: StateFlow<UpdateState>
        /** 下载进度（非下载态为 null）；与 state 分开是为了避免满屏重组。 */
        val progress: StateFlow<DownloadProgress?>
        /** 轻提示（手动检查结果、安装成功等），UI 取去展示。 */
        val toasts: SharedFlow<String>

        /** 生命周期 */
        suspend fun onColdStart(loggedIn: Boolean)
        /** UI 告知当前路由是否允许弹窗（拍照/扫码/连拍 = false，实现延后）。 */
        fun setOverlayAllowed(allowed: Boolean)

        /** 用户动作 */
        suspend fun check(trigger: CheckTrigger)
        fun startDownload()
        fun cancelDownload()
        fun retry()
        fun skipVersion()
        fun postpone()
        fun confirmInstall()
        fun dismissFailure()

        /** 运维 */
        fun clearCacheBytes(): Long
        fun copyDiagnostics(): String

        /** 冷启动清理（LaundryApp.onCreate 异步触发）：清掉 .part 与超过保留期的 .apk。 */
        fun performColdStartSweep()
    }

    // ---------- 基础设施协作者（不属于六大角色，但被它们依赖） ----------

    /** 更新包的文件空间。目录策略、StatFs、清理、保护窗全部收在这里。 */
    interface UpdateFileStore {
        val updateDir: File
        fun finalFileFor(fileName: String): File
        fun partFileFor(fileName: String): File
        /** 磁盘预检：可用空间需求 = size * 1.5 + 20MB。 */
        fun requiredBytesFor(sizeBytes: Long): Long
        fun availableBytes(): Long
        /** 原子 rename；失败抛 IOException，调用方据此落 Verifying → Failed。 */
        @Throws(java.io.IOException::class)
        fun commitPart(fileName: String)
        /** 清理：所有 .part + 超过保留期的最终文件；[protectUntilMs] 内跳过忙于保护期的文件。 */
        fun sweep(nowMs: Long, protectUntilMs: Long, retentionMs: Long): Long
    }

    /** APK 内信息读取。抽出来是为了让「包名/versionCode 比对规则」纯 JVM 可测。 */
    interface ApkInspector {
        /** 读不到（不是 APK / IO 失败）返回 null。 */
        fun inspect(file: File): ApkInfo?
    }

    data class ApkInfo(val packageName: String, val versionCode: Long, val versionName: String?)

    interface Clock {
        fun elapsedRealtime(): Long
        fun currentTimeMillis(): Long
    }
}

/** 便捷构造：[UpdateContract.SelfVersion] 从 BuildConfig 取默认值（生产用）。 */
fun selfVersionFromBuildConfig(): UpdateContract.SelfVersion = UpdateContract.SelfVersion(
    versionName = BuildConfig.VERSION_NAME,
    versionCode = BuildConfig.VERSION_CODE,
    packageName = BuildConfig.APPLICATION_ID,
    releasePackageName = BuildConfig.RELEASE_APPLICATION_ID,
    isDebug = BuildConfig.DEBUG
)
