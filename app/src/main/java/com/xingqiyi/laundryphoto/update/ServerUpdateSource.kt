package com.xingqiyi.laundryphoto.update

import com.xingqiyi.laundryphoto.data.model.MobileUpdateInfoDto
import com.xingqiyi.laundryphoto.data.model.ForceUpdateDto
import com.xingqiyi.laundryphoto.data.remote.ApiClient
import com.xingqiyi.laundryphoto.data.remote.ApiError
import java.net.URL

/**
 * 更新发现源：服务端实现。
 *
 * ## 职责边界（design.md §2.2）
 *
 * 一次性回答「这台服务器上，对我而言有什么」，内部封装两件事：
 * 1. **新接口优先 → 老接口降级**：优先调 `system/checkMobileUpdate`（只扫 APK，返回大小/sha256/说明）；
 *    老服务端没有该接口会返回「接口不存在」→ 抛 `ApiError.Unsupported` → 静默降级到
 *    `system/checkUpdate`（扫全部文件、取版本最高的那个，可能连 APK 都不是）。
 * 2. **强推的是 .exe 就当没看见**：`forceUpdate.fileName` 不以 `.apk` 结尾、或文件已不在，
 *    一律把 `forceTrack` 置 null，移动端完全无感——这是防「管理员误推桌面端安装包」的最后一道闸。
 *
 * 编排器（`UpdateCoordinatorImpl`）**不做任何 API 选择决策**，只消费本类给出的 `SourceReport`。
 */
class ServerUpdateSource(
    private val api: ApiClient
) : UpdateContract.UpdateSource {

    /**
     * @param self 本 App 的版本身份（来自 BuildConfig）
     * @return 永远成功返回 `SourceReport`；任何网络/解析失败都收敛成「无可用包」，不抛异常。
     *         理由：启动检查不应因为一次网络抖动就让用户看到错误。
     */
    override suspend fun discover(self: UpdateContract.SelfVersion): UpdateContract.SourceReport {
        val host = hostOf(api.baseUrl)
        var unsupported = false

        // 优先：移动端专用接口（桌面端 v1.2.4 起）。
        // 老服务端没有该接口会返回「接口不存在」→ 抛 ApiError.Unsupported，这里捕获并标记，
        // 但**不阻断**——后面还会降级到老接口。
        val mobile = runCatching {
            api.request { it.checkMobileUpdate(buildBody(self)) }
        }.onFailure { e ->
            if (e is ApiError.Unsupported) unsupported = true
        }.getOrNull()

        val mobileApk = mobile?.takeIf { it.hasPackageValue }?.toRemoteApk()

        // 新接口没拿到包 → 降级老接口（老服务端无 checkMobileUpdate 时也会落到这里）
        val legacyApk = if (mobileApk == null) legacyApk(self) else null

        val finalApk = mobileApk ?: legacyApk
        val origin = when {
            mobileApk != null -> UpdateContract.UpdateOrigin.MOBILE_API
            legacyApk != null -> UpdateContract.UpdateOrigin.LEGACY_CHECK_UPDATE
            else -> UpdateContract.UpdateOrigin.NONE
        }

        val forceTrack = forceTrack()

        // 「服务端不支持」只在「确实没有任何可用包」时置位（手动检查据此明确提示版本较旧）。
        return UpdateContract.SourceReport(
            latestApk = finalApk,
            forceTrack = forceTrack,
            origin = origin,
            serverHost = host,
            unsupported = if (finalApk == null) unsupported else false
        )
    }

    private suspend fun legacyApk(self: UpdateContract.SelfVersion): UpdateContract.RemoteApk? {
        val info = runCatching {
            api.request { it.checkUpdate(emptyMap()) }
        }.getOrNull() ?: return null
        val file = info.latestFileText
        // 老接口返回的是「版本号最高的文件名」，可能是 .exe；移动端只认 .apk
        if (!file.endsWith(".apk", ignoreCase = true)) return null
        if (!VersionComparator.isNewer(info.latestVersion ?: "", self.comparableName)) return null
        return UpdateContract.RemoteApk(
            fileName = file,
            versionName = info.latestVersion ?: "",
            sizeBytes = 0,   // 老接口不提供大小，校验时按 0 跳过大小校验
            sha256 = "",
            notes = "",
            notesSource = ""
        )
    }

    private suspend fun forceTrack(): UpdateContract.ForceTrack? {
        val fu = runCatching {
            api.request { it.forceUpdate(emptyMap()) }
        }.getOrNull() ?: return null
        if (!fu.enabledValue) return null
        val name = fu.fileNameText
        // 管理员误推 .exe/.zip 时，移动端必须无感：只有以 .apk 结尾且文件还在才成立
        if (!name.endsWith(".apk", ignoreCase = true) || fu.fileExists != true) return null
        return UpdateContract.ForceTrack(
            fileName = name,
            version = fu.versionText,
            fileExists = true
        )
    }

    private fun MobileUpdateInfoDto.toRemoteApk(): UpdateContract.RemoteApk = UpdateContract.RemoteApk(
        fileName = fileNameText,
        versionName = versionText,
        sizeBytes = sizeValue,
        sha256 = sha256Text,
        notes = notesText,
        notesSource = notesSourceText
    )

    private fun buildBody(self: UpdateContract.SelfVersion): Map<String, Any?> = mapOf(
        "platform" to "android",
        "appId" to self.releasePackageName,
        "currentVersion" to self.comparableName,
        "currentCode" to self.versionCode
    )

    companion object {
        /** 从 baseUrl 取 `host:port`，用于节流分键。解析失败返回原始串，不让 discover 崩。 */
        fun hostOf(baseUrl: String): String = runCatching {
            val u = URL(baseUrl)
            "${u.host}:${u.port}".takeIf { u.port > 0 } ?: u.host
        }.getOrDefault(baseUrl)
    }
}
