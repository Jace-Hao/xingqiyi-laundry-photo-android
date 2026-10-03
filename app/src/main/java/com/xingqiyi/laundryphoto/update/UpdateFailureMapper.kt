package com.xingqiyi.laundryphoto.update

import com.xingqiyi.laundryphoto.R
import com.xingqiyi.laundryphoto.update.UpdateContract.DownloadFailure
import com.xingqiyi.laundryphoto.update.UpdateContract.InstallFailure
import com.xingqiyi.laundryphoto.update.UpdateContract.VerifyFailure

/**
 * 失败文案与重试策略映射（纯函数，无 Android 依赖）。
 *
 * ## 它是「是否自动重试」的唯一真源
 *
 * `OkHttpUpdateDownloader` 与 UI 都**不得**自行判断某次失败要不要重试——
 * 一旦两处逻辑不一致，就会出现「下载器还在退避、UI 却已经提示失败」的分裂状态。
 * 因此所有分级结论集中在这里，由 `FailureCopy.retryable` 一锤定音。
 *
 * ## 自动重试上限
 *
 * 网络类（DNS/连接超时/读超时/连接重置/IO/断流）上限 3 次；5xx/其它服务端错误 2 次；
 * 鉴权/404/磁盘满 0 次（这些是「改了也没用」的错误，重试只会浪费用户时间和门店 WiFi）。
 * `maxAutoRetries` 供下载器取用。
 */
object UpdateFailureMapper {

    /** 下载失败 → 文案 + 是否可重试 + 是否引导清理缓存。 */
    fun fromDownload(f: DownloadFailure): UpdateContract.FailureCopy = when (f) {
        DownloadFailure.DNS,
        DownloadFailure.CONNECT_TIMEOUT,
        DownloadFailure.READ_TIMEOUT,
        DownloadFailure.CONNECTION_RESET,
        DownloadFailure.IO,
        DownloadFailure.STALLED,
        DownloadFailure.HTTP_5XX,
        DownloadFailure.HTTP_OTHER ->
            UpdateContract.FailureCopy(R.string.update_fail_network, emptyList(), true, false)

        DownloadFailure.TOO_SLOW ->
            UpdateContract.FailureCopy(R.string.update_too_slow, emptyList(), true, false)

        // 鉴权/文件不存在：改了也没用，重试只是白等
        DownloadFailure.HTTP_401, DownloadFailure.HTTP_403 ->
            UpdateContract.FailureCopy(R.string.update_fail_auth, emptyList(), false, false)

        DownloadFailure.HTTP_404 ->
            UpdateContract.FailureCopy(R.string.update_fail_missing, emptyList(), false, false)

        // 磁盘满：重试多少次都不会变好，但值得引导用户清理下载缓存
        DownloadFailure.DISK_FULL ->
            UpdateContract.FailureCopy(R.string.update_fail_space, emptyList(), false, true)
    }

    /** 安装失败 → 文案 + 是否可重试。 */
    fun fromInstall(f: InstallFailure): UpdateContract.FailureCopy = when (f) {
        InstallFailure.BLOCKED ->
            UpdateContract.FailureCopy(R.string.update_perm_blocked, emptyList(), false, false)
        InstallFailure.CONFLICT ->
            UpdateContract.FailureCopy(R.string.update_install_conflict, emptyList(), false, false)
        InstallFailure.INCOMPATIBLE ->
            UpdateContract.FailureCopy(R.string.update_install_incompatible, emptyList(), false, false)
        InstallFailure.INVALID ->
            UpdateContract.FailureCopy(R.string.update_verify_version, emptyList(), false, false)
        // 存储空间不足：引导清缓存
        InstallFailure.STORAGE ->
            UpdateContract.FailureCopy(R.string.update_fail_space, emptyList(), false, true)
        // 用户取消 / 回执丢失 / 未知：都可重试
        InstallFailure.ABORTED, InstallFailure.RECEIPT_LOST, InstallFailure.UNKNOWN ->
            UpdateContract.FailureCopy(R.string.update_fail_network, emptyList(), true, false)
    }

    /** 校验失败 → 文案 + 是否可重试（U-23 分诊表）。 */
    fun fromVerify(f: VerifyFailure): UpdateContract.FailureCopy = when (f) {
        // 读文件失败：重下即可，可重试
        VerifyFailure.IO ->
            UpdateContract.FailureCopy(R.string.update_fail_network, emptyList(), true, false)
        // 大小/哈希不符：文件不完整或被改，重下即可
        VerifyFailure.SIZE_MISMATCH, VerifyFailure.HASH_MISMATCH ->
            UpdateContract.FailureCopy(R.string.update_verify_hash, emptyList(), true, false)
        // 包名不符 / 不是 APK：绝不重试（重下同一个包还是错），必须管理员换文件
        VerifyFailure.PACKAGE_MISMATCH, VerifyFailure.NOT_AN_APK ->
            UpdateContract.FailureCopy(R.string.update_verify_package, emptyList(), false, false)
        VerifyFailure.VERSION_NOT_NEWER ->
            UpdateContract.FailureCopy(R.string.update_verify_version, emptyList(), false, false)
    }

    /** 网络/服务端类失败自动重试上限（手动「重试」按钮不受此限）。 */
    fun maxAutoRetries(f: DownloadFailure): Int = when (f) {
        DownloadFailure.HTTP_5XX, DownloadFailure.HTTP_OTHER -> 2
        DownloadFailure.HTTP_401, DownloadFailure.HTTP_403,
        DownloadFailure.HTTP_404, DownloadFailure.DISK_FULL -> 0
        else -> 3
    }
}
