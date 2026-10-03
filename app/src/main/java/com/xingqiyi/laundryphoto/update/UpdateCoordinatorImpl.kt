package com.xingqiyi.laundryphoto.update

import android.content.Context
import com.xingqiyi.laundryphoto.R
import com.xingqiyi.laundryphoto.sync.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random
import java.io.File
import java.text.DecimalFormat

/**
 * 更新编排器（唯一真源）。
 *
 * ## 职责（design.md §3.3 / §10.2）
 *
 * 把六大角色（发现/下载/校验/安装/策略/文件空间）编排成一条状态机，
 * **唯一**对外暴露 `StateFlow<UpdateState>`——UI 只做「状态 → 界面」的纯映射，
 * 任何 Failed 都不自动回 `Idle`（避免「失败 → 弹窗 → 又失败」的死循环，设计铁律）。
 *
 * ## 关键不变量
 *
 * - 重试与退避**只在这里**：`OkHttpUpdateDownloader` 只负责单次 HTTP 拉取，
 *   因此 `Downloading(attempt, retryInMs)` 能精确表达「第几次、还要等多久」；
 * - 下载不用 WorkManager，走**应用级协程** `ioScope`（D10：进程存活期间即可，进程被安装替换即结束）；
 * - 校验失败 / 安装回执丢失，靠**冷启动比对 `versionCode`** 兜底（NFR-R1）；
 * - 任何中间产物（.part / 残缺 .apk）的清理都收口在状态机，绝不遗留坏文件。
 */
class UpdateCoordinatorImpl(
    private val self: UpdateContract.SelfVersion,
    private val source: UpdateContract.UpdateSource,
    private val downloader: UpdateContract.UpdateDownloader,
    private val verifier: UpdateContract.UpdateVerifier,
    private val installer: UpdateContract.UpdateInstaller,
    private val policyStore: UpdateContract.UpdatePolicyStore,
    private val fileStore: UpdateContract.UpdateFileStore,
    private val logger: UpdateLogger,
    private val notifier: Notifier,
    private val clock: UpdateContract.Clock,
    private val ioScope: CoroutineScope,
    private val appContext: Context,
    /** 下载地址拼装（连接码不进 URL，由 downloader 注入请求头）。 */
    private val fileUrlOf: (String) -> String
) : UpdateContract.UpdateCoordinator {

    companion object {
        /** 自动检查节流窗口：>24h 才再发起一次（按服务器 host 分键）。 */
        private const val AUTO_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
        /** 安装后 30 分钟保护窗：期间不清理刚下载的 APK，便于回退/二次安装。 */
        private const val PROTECT_MS = 30L * 60 * 1000
        /** 冷启动清理保留期：超过 7 天的 .apk 直接清掉。 */
        private const val CLEAN_RETENTION_MS = 7L * 24 * 60 * 60 * 1000
        /** 等待安装回执的上限；超时（多为降级到 ACTION_VIEW 路径）回退到 ReadyToInstall 让用户重试。 */
        private const val INSTALL_BUS_MS = 90_000L
    }

    private val _state = MutableStateFlow<UpdateContract.UpdateState>(UpdateContract.UpdateState.Idle)
    override val state: StateFlow<UpdateContract.UpdateState> = _state.asStateFlow()

    private val _progress = MutableStateFlow<UpdateContract.DownloadProgress?>(null)
    override val progress: StateFlow<UpdateContract.DownloadProgress?> = _progress.asStateFlow()

    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    override val toasts: SharedFlow<String> = _toasts.asSharedFlow()

    private val _overlayAllowed = MutableStateFlow(false)

    /** 最近一次检查的服务器 host（诊断用）。 */
    @Volatile
    private var lastServerHost: String = ""

    /** 防止并发下载。 */
    private var downloadJob: Job? = null

    // ===================== 生命周期 =====================

    override suspend fun onColdStart(loggedIn: Boolean) {
        if (!loggedIn) return
        // 1. 冷启动恢复（比对已安装 versionCode + 残留快照）
        recoverIfNeeded()
        // 恢复已经还原出可继续的界面（ReadyToInstall）→ 不再自动检查，避免覆盖
        if (_state.value !is UpdateContract.UpdateState.Idle) return
        // 2. 自动检查（受 24h 节流约束，静默）
        val report = safeDiscover() ?: return
        val now = clock.currentTimeMillis()
        if (now - policyStore.lastCheckAt(report.serverHost) < AUTO_CHECK_INTERVAL_MS) return
        policyStore.markChecked(report.serverHost, now)
        resolveAndShow(report, UpdateContract.CheckTrigger.AUTO_BOOT)
    }

    override fun setOverlayAllowed(allowed: Boolean) {
        _overlayAllowed.value = allowed
    }

    // ===================== 用户动作 =====================

    override suspend fun check(trigger: UpdateContract.CheckTrigger) {
        val report = safeDiscover() ?: return
        lastServerHost = report.serverHost
        if (trigger != UpdateContract.CheckTrigger.MANUAL_SETTINGS) {
            policyStore.markChecked(report.serverHost, clock.currentTimeMillis())
        }
        resolveAndShow(report, trigger)
    }

    override fun startDownload() {
        val cur = _state.value
        val plan = when (cur) {
            is UpdateContract.UpdateState.UpdateAvailable -> cur.plan
            is UpdateContract.UpdateState.Blocked -> cur.plan
            else -> return
        }
        // 磁盘预检（D11「没空间一个字节都不下」）
        val required = fileStore.requiredBytesFor(plan.target.sizeBytes)
        if (fileStore.availableBytes() < required) {
            logger.w("download", "磁盘空间不足：需要 ${required}，可用 ${fileStore.availableBytes()}")
            ioScope.launch { policyStore.savePendingSnapshot(null) }
            notifier.cancelUpdate(appContext)
            _state.value = UpdateContract.UpdateState.Failed(
                UpdateContract.UpdateStage.DOWNLOAD,
                diskFullCopy(required - fileStore.availableBytes()),
                plan
            )
            return
        }
        if (downloadJob?.isActive == true) return
        _state.value = UpdateContract.UpdateState.Downloading(plan, attempt = 1, retryInMs = null)
        _progress.value = UpdateContract.DownloadProgress(0, plan.target.sizeBytes, 0, 0, 1)
        downloadJob = ioScope.launch { runDownload(plan) }
    }

    override fun cancelDownload() {
        downloader.cancel()
        // 实际的 Cancelled 收敛在 runDownload 的循环里完成；这里仅触发取消信号。
    }

    override fun retry() {
        val cur = _state.value
        if (cur !is UpdateContract.UpdateState.Failed) return
        when (cur.stage) {
            UpdateContract.UpdateStage.DOWNLOAD, UpdateContract.UpdateStage.VERIFY -> startDownload()
            UpdateContract.UpdateStage.INSTALL -> confirmInstall()
            else -> dismissFailure()
        }
    }

    override fun skipVersion() {
        val cur = _state.value
        val plan = (cur as? UpdateContract.UpdateState.UpdateAvailable)?.plan ?: return
        if (plan.mandatory) return
        ioScope.launch { policyStore.setSkippedVersion(plan.target.versionName) }
        logger.i("skip", "用户跳过 v${plan.target.versionName}")
        ioScope.launch { policyStore.savePendingSnapshot(null) }
        _state.value = UpdateContract.UpdateState.Idle
    }

    override fun postpone() {
        val cur = _state.value
        val plan = (cur as? UpdateContract.UpdateState.UpdateAvailable)?.plan
            ?: (cur as? UpdateContract.UpdateState.Blocked)?.plan
            ?: return
        if (!plan.mandatory) return
        ioScope.launch { policyStore.bumpPostpone(plan.forceVersion, clock.currentTimeMillis()) }
        logger.i("postpone", "用户推迟 v${plan.target.versionName}")
        _state.value = UpdateContract.UpdateState.Idle
    }

    override fun confirmInstall() {
        val cur = _state.value
        val plan = (cur as? UpdateContract.UpdateState.ReadyToInstall)?.plan ?: return
        if (!installer.canRequestPackageInstalls()) {
            // 未授权：UI 应展示权限引导（InstallPermissionGate），这里直接返回，不做任何变更
            return
        }
        val target = plan.target
        val file = fileStore.finalFileFor(target.fileName)
        ioScope.launch {
            policyStore.setSnapshotPhase(UpdateContract.UpdateStage.INSTALL)
            policyStore.setProtectUntil(clock.currentTimeMillis() + PROTECT_MS)
            _state.value = UpdateContract.UpdateState.Installing(plan)
            logger.i("install", "开始安装 v${target.versionName}")
            val handle = installer.install(file, target, self)
            try {
                // 主路径（Session）会有结构化回执；降级路径（ACTION_VIEW）拿不到，超时后回退让用户重试
                val ev = withTimeoutOrNull(INSTALL_BUS_MS) {
                    handle.events.first { it is UpdateContract.InstallEvent.Succeeded || it is UpdateContract.InstallEvent.Failed }
                } ?: UpdateContract.InstallEvent.Failed(UpdateContract.InstallFailure.UNKNOWN, -1, "安装回执超时")
                when (ev) {
                    is UpdateContract.InstallEvent.Succeeded -> {
                        policyStore.clearPostpone()
                        policyStore.savePendingSnapshot(null)
                        logger.i("install", "安装成功")
                        _toasts.tryEmit(appContext.getString(R.string.update_installed))
                        _state.value = UpdateContract.UpdateState.Idle
                    }
                    is UpdateContract.InstallEvent.Failed -> {
                        policyStore.savePendingSnapshot(null)
                        val copy = UpdateFailureMapper.fromInstall(ev.kind)
                        logger.e("install", "安装失败 ${ev.kind} msg=${ev.message}")
                        _state.value = UpdateContract.UpdateState.Failed(
                            UpdateContract.UpdateStage.INSTALL, copy, plan
                        )
                        notifier.notifyUpdateFailed(
                            appContext, target.versionName,
                            appContext.getString(copy.stringRes, *copy.formatArgs.toTypedArray())
                        )
                    }
                    else -> { /* 不会发生 */ }
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
                throw kotlinx.coroutines.CancellationException()
            }
        }
    }

    override fun dismissFailure() {
        _progress.value = null
        _state.value = UpdateContract.UpdateState.Idle
    }

    // ===================== 运维 =====================

    override fun clearCacheBytes(): Long {
        // clearAll 是 DefaultUpdateFileStore 的扩展方法（不在契约里，因为只有「一键清理」用得上），
        // 这里做一次安全的向下转型：拿不到实现就返回 0，绝不让设置页的清理按钮把更新流程搞崩。
        val freed = (fileStore as? DefaultUpdateFileStore)?.clearAll(clock.currentTimeMillis()) ?: 0L
        logger.i("cache", "清理更新缓存 ${freed}B")
        if (freed > 0) {
            _toasts.tryEmit(appContext.getString(R.string.update_cache_cleared, humanBytes(freed)))
        }
        return freed
    }

    override fun copyDiagnostics(): String {
        val lines = logger.tailLines(20)
        return UpdateDiagnostics.build(_state.value, self, lastServerHost, lines)
    }

    /** 冷启动清理（LaundryApp.onCreate 异步触发）：清掉 .part 与超过保留期的 .apk，保护窗内的不动。 */
    override fun performColdStartSweep() {
        ioScope.launch {
            val protect = policyStore.protectUntilMs()
            fileStore.sweep(clock.currentTimeMillis(), protect, CLEAN_RETENTION_MS)
        }
    }

    // ===================== 内部：发现 + 解析 =====================

    private suspend fun safeDiscover(): UpdateContract.SourceReport? = runCatching {
        source.discover(self)
    }.getOrNull()

    private suspend fun resolveAndShow(report: UpdateContract.SourceReport, trigger: UpdateContract.CheckTrigger) {
        lastServerHost = report.serverHost
        val manual = trigger == UpdateContract.CheckTrigger.MANUAL_SETTINGS
        val skipped = policyStore.skippedVersion().first()
        val postpone = policyStore.postpone()
        val res = UpdatePlanResolver.resolve(
            UpdatePlanResolver.ResolveInput(
                report = report,
                self = self,
                skippedVersion = skipped,
                postpone = postpone,
                nowMs = clock.currentTimeMillis(),
                manual = manual
            )
        )
        when (res) {
            is UpdatePlanResolver.Resolution.Offer -> {
                logger.i("resolve", "可更新 v${res.plan.target.versionName} mandatory=${res.plan.mandatory}")
                _state.value = UpdateContract.UpdateState.UpdateAvailable(
                    plan = res.plan,
                    mandatory = res.plan.mandatory,
                    previouslySkipped = res.previouslySkipped,
                    remainingPostpone = res.plan.remainingPostpone,
                    graceEndsAtMs = res.plan.graceEndsAtMs
                )
            }
            is UpdatePlanResolver.Resolution.Block -> {
                logger.i("resolve", "强制阻断 v${res.plan.target.versionName}")
                _state.value = UpdateContract.UpdateState.Blocked(res.plan)
            }
            is UpdatePlanResolver.Resolution.UpToDate -> {
                if (manual) _toasts.tryEmit(appContext.getString(R.string.update_uptodate, self.comparableName))
                _state.value = UpdateContract.UpdateState.Idle
            }
            is UpdatePlanResolver.Resolution.NoPackage -> {
                if (manual) {
                    if (report.unsupported) {
                        _toasts.tryEmit(appContext.getString(R.string.update_unsupported))
                    } else {
                        _toasts.tryEmit(appContext.getString(R.string.update_no_package))
                    }
                }
                _state.value = UpdateContract.UpdateState.Idle
            }
            is UpdatePlanResolver.Resolution.Skipped -> {
                _state.value = UpdateContract.UpdateState.Idle
            }
            is UpdatePlanResolver.Resolution.NotConfigured -> {
                if (manual) _toasts.tryEmit("尚未配置服务器地址，无法检查更新")
                _state.value = UpdateContract.UpdateState.Idle
            }
        }
    }

    // ===================== 内部：下载 + 校验 =====================

    private suspend fun runDownload(plan: UpdateContract.UpdatePlan) {
        val target = plan.target
        val url = fileUrlOf(target.fileName)
        val dest = fileStore.finalFileFor(target.fileName)
        // 先落快照（DOWNLOAD 阶段），便于冷启动恢复
        policyStore.savePendingSnapshot(
            UpdateContract.PendingUpdateSnapshot(
                phase = UpdateContract.UpdateStage.DOWNLOAD,
                fileName = target.fileName,
                version = target.versionName,
                sizeBytes = target.sizeBytes,
                sha256 = target.sha256,
                notes = target.notes,
                notesSource = target.notesSource,
                mandatory = plan.mandatory,
                forceVersion = plan.forceVersion,
                startedAtMs = clock.currentTimeMillis(),
                preUpdateVersionCode = self.versionCode
            )
        )
        var attempt = 1
        while (true) {
            val spec = UpdateContract.DownloadSpec(
                fileName = target.fileName,
                url = url,
                expectedBytes = target.sizeBytes,
                sha256 = target.sha256,
                headers = emptyMap()
            )
            logger.i("download", "第 $attempt 次下载 v${target.versionName} <- $url")
            val outcome = downloader.download(spec, dest) { p ->
                _progress.value = p
                notifier.notifyUpdateProgress(appContext, target.versionName, p.percent, p.bytesRead, p.totalBytes)
            }
            when (outcome) {
                is UpdateContract.DownloadOutcome.Success -> {
                    policyStore.setSnapshotPhase(UpdateContract.UpdateStage.VERIFY)
                    _state.value = UpdateContract.UpdateState.Verifying(plan)
                    verifyAndInstall(plan, dest)
                    return
                }
                is UpdateContract.DownloadOutcome.Cancelled -> {
                    policyStore.savePendingSnapshot(null)
                    _progress.value = null
                    logger.i("download", "下载被取消")
                    _state.value = UpdateContract.UpdateState.Idle
                    return
                }
                is UpdateContract.DownloadOutcome.Failure -> {
                    val copy = UpdateFailureMapper.fromDownload(outcome.kind)
                    val max = UpdateFailureMapper.maxAutoRetries(outcome.kind)
                    if (copy.retryable && attempt < max) {
                        val wait = RetryBackoff.delayMs(attempt, jitter())
                        logger.w("download", "失败 ${outcome.kind}，第 $attempt 次重试，等待 ${wait}ms")
                        _state.value = UpdateContract.UpdateState.Downloading(plan, attempt + 1, wait)
                        delay(wait)
                        attempt++
                        continue
                    }
                    // 硬失败：清理残缺文件
                    runCatching { dest.delete() }
                    runCatching { fileStore.partFileFor(target.fileName).delete() }
                    policyStore.savePendingSnapshot(null)
                    _progress.value = null
                    logger.e("download", "下载失败 ${outcome.kind} msg=${outcome.message}")
                    _state.value = UpdateContract.UpdateState.Failed(UpdateContract.UpdateStage.DOWNLOAD, copy, plan)
                    notifier.cancelUpdate(appContext)
                    return
                }
            }
        }
    }

    private suspend fun verifyAndInstall(plan: UpdateContract.UpdatePlan, dest: File) {
        val target = plan.target
        val vr = verifier.verify(dest, target, self)
        when (vr) {
            is UpdateContract.VerifyResult.Ok -> {
                policyStore.setSnapshotPhase(UpdateContract.UpdateStage.INSTALL)
                notifier.notifyUpdateReady(appContext, target.versionName)
                logger.i("verify", "校验通过 v${target.versionName}")
                _state.value = UpdateContract.UpdateState.ReadyToInstall(
                    plan = plan,
                    pendingCount = 0,
                    canInstallUnknownSources = installer.canRequestPackageInstalls()
                )
            }
            is UpdateContract.VerifyResult.Fail -> {
                runCatching { dest.delete() }
                policyStore.savePendingSnapshot(null)
                logger.e("verify", "校验失败 ${vr.failure} expected=${vr.expected} actual=${vr.actual}")
                _state.value = UpdateContract.UpdateState.Failed(
                    UpdateContract.UpdateStage.VERIFY, UpdateFailureMapper.fromVerify(vr.failure), plan
                )
                notifier.cancelUpdate(appContext)
            }
        }
    }

    // ===================== 内部：冷启动恢复 =====================

    private suspend fun recoverIfNeeded() {
        val snap = policyStore.pendingSnapshot() ?: return
        // 已更新到更新的版本（versionCode 严格变大）→ 安装成功
        if (self.versionCode > snap.preUpdateVersionCode) {
            policyStore.clearPostpone()
            policyStore.savePendingSnapshot(null)
            policyStore.setProtectUntil(clock.currentTimeMillis() + PROTECT_MS)
            logger.i("recover", "检测到已更新到 v${snap.version}")
            _toasts.tryEmit(appContext.getString(R.string.update_installed))
            return
        }
        // 尚未更新，且安装包仍在 → 恢复到「待安装」，让用户继续
        val file = fileStore.finalFileFor(snap.fileName)
        if (file.exists() && snap.phase in setOf(
                UpdateContract.UpdateStage.DOWNLOAD,
                UpdateContract.UpdateStage.VERIFY,
                UpdateContract.UpdateStage.INSTALL
            )
        ) {
            val plan = UpdateContract.UpdatePlan(
                target = UpdateContract.RemoteApk(
                    snap.fileName, snap.version, snap.sizeBytes, snap.sha256, snap.notes, snap.notesSource
                ),
                mandatory = snap.mandatory,
                origin = UpdateContract.UpdateOrigin.NONE,
                discoveredAtMs = snap.startedAtMs,
                forceVersion = snap.forceVersion,
                remainingPostpone = 0,
                graceEndsAtMs = snap.startedAtMs + UpdatePlanResolver.GRACE_WINDOW_MS
            )
            logger.i("recover", "恢复待安装 v${snap.version}")
            _state.value = UpdateContract.UpdateState.ReadyToInstall(
                plan = plan,
                pendingCount = 0,
                canInstallUnknownSources = installer.canRequestPackageInstalls()
            )
            return
        }
        // 没有可恢复的 → 清掉残留快照
        policyStore.savePendingSnapshot(null)
    }

    // ===================== 工具 =====================

    /** ±20% 随机抖动，避免十几台设备同时重试打满门店 WiFi。 */
    private fun jitter(): Double = 0.8 + Random.nextDouble() * 0.4

    private fun diskFullCopy(needed: Long): UpdateContract.FailureCopy {
        val copy = UpdateFailureMapper.fromDownload(UpdateContract.DownloadFailure.DISK_FULL)
        // update_fail_space 含 %1$s（所需空间），补上格式参数
        return copy.copy(formatArgs = listOf(humanBytes(needed.coerceAtLeast(0L))))
    }

    private fun humanBytes(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1) "${DecimalFormat("#.#").format(mb)} MB" else "${bytes / 1024} KB"
    }
}
