package com.xingqiyi.laundryphoto.update

import android.content.pm.PackageManager
import android.os.Build
import java.io.File

/**
 * APK 内信息读取（实现层）。
 *
 * ## 为什么抽这一层
 *
 * `PackageManager.getPackageArchiveInfo()` 是 Android 框架 API，单测里调不动。
 * 把它后面「比对规则」抽成纯函数 `VerifyRules`，则**校验逻辑的全部分支都能在 JVM 上单测**
 * （`DefaultUpdateVerifierTest` 用 Fake 的 `ApkInspector` 覆盖包名/版本/大小/哈希各分支），
 * 符合 U-18「纯逻辑单测」与 NFR-M3 的要求。
 */
class PackageManagerApkInspector(
    private val pm: PackageManager
) : UpdateContract.ApkInspector {

    override fun inspect(file: File): UpdateContract.ApkInfo? {
        val info = runCatching {
            pm.getPackageArchiveInfo(file.absolutePath, 0)
        }.getOrNull() ?: return null

        val packageName = info.packageName ?: return null
        // versionCode 在 API 28+ 才升为 long；低版本用 int 并向上转型
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        return UpdateContract.ApkInfo(
            packageName = packageName,
            versionCode = versionCode,
            versionName = info.versionName
        )
    }
}

/**
 * 校验规则（纯函数，无 Android 依赖，JVM 可测）。
 *
 * 只负责**两级强制校验**（NFR-S2，永远不能跳过）：
 * - 包名 == 本机包名（debug 构建额外允许 `RELEASE_APPLICATION_ID`，否则 debug 包
 *   会把自己下发的正式 APK 判成「别人的包」而永远装不上）；
 * - `versionCode` **严格大于**已安装版本——因为服务端按文件名挑最高版本，
 *   与 APK 内部 `versionCode` 可能不一致，这一条同时规避 `INSTALL_FAILED_VERSION_DOWNGRADE`。
 *
 * 大小与 sha256 的「有才校」在 `DefaultUpdateVerifier` 里做（需要读文件本身），
 * 不在这里，以保持本规则只依赖 `ApkInfo` 这个纯结构。
 */
object VerifyRules {

    fun check(
        expected: UpdateContract.RemoteApk,
        actual: UpdateContract.ApkInfo,
        self: UpdateContract.SelfVersion,
        actualFileSize: Long = 0L
    ): UpdateContract.VerifyResult {
        val packageOk = actual.packageName == self.packageName ||
            (self.isDebug && actual.packageName == self.releasePackageName)
        if (!packageOk) {
            return UpdateContract.VerifyResult.Fail(
                UpdateContract.VerifyFailure.PACKAGE_MISMATCH,
                self.packageName,
                actual.packageName
            )
        }
        if (actual.versionCode <= self.versionCode) {
            return UpdateContract.VerifyResult.Fail(
                UpdateContract.VerifyFailure.VERSION_NOT_NEWER,
                "> ${self.versionCode}",
                "${actual.versionCode}"
            )
        }
        return UpdateContract.VerifyResult.Ok(
            packageName = actual.packageName,
            versionCode = actual.versionCode,
            sizeBytes = actualFileSize
        )
    }
}
