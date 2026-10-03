package com.xingqiyi.laundryphoto.update

import com.xingqiyi.laundryphoto.R
import com.xingqiyi.laundryphoto.update.UpdateContract.DownloadFailure
import com.xingqiyi.laundryphoto.update.UpdateContract.InstallFailure
import com.xingqiyi.laundryphoto.update.UpdateContract.VerifyFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [UpdateFailureMapper] 单测。
 *
 * ## 为什么「可重试」必须由单一真源决定
 *
 * 下载器和 UI 都不允许自己判断失败要不要重试。若两处判断不一致，会出现
 * 「下载器已经放弃、UI 还在转圈」或「UI 报失败、下载器偷偷重试到成功」这类分裂状态，
 * 现场极难复现也极难解释。所以这里用测试把分级表钉死。
 *
 * 分级原则：**「改了也没用」的错误（401/403/404/磁盘满/包名不符）一律不可重试**，
 * 因为门店 WiFi 下重试只会白等，浪费的是店员的时间。
 */
class UpdateFailureMapperTest {

    @Test
    fun `网络类失败可重试且不引导清缓存`() {
        val retryable = listOf(
            DownloadFailure.DNS,
            DownloadFailure.CONNECT_TIMEOUT,
            DownloadFailure.READ_TIMEOUT,
            DownloadFailure.CONNECTION_RESET,
            DownloadFailure.IO,
            DownloadFailure.STALLED,
            DownloadFailure.HTTP_5XX,
            DownloadFailure.HTTP_OTHER
        )
        retryable.forEach { f ->
            val copy = UpdateFailureMapper.fromDownload(f)
            assertTrue("$f 应可重试", copy.retryable)
            assertFalse("$f 不应引导清缓存", copy.promoteCleanCache)
        }
    }

    @Test
    fun `低速也归入可重试`() {
        val copy = UpdateFailureMapper.fromDownload(DownloadFailure.TOO_SLOW)
        assertTrue(copy.retryable)
        assertEquals(R.string.update_too_slow, copy.stringRes)
    }

    @Test
    fun `鉴权与缺失不可重试`() {
        listOf(
            DownloadFailure.HTTP_401,
            DownloadFailure.HTTP_403,
            DownloadFailure.HTTP_404
        ).forEach { f ->
            assertFalse("$f 不应自动重试", UpdateFailureMapper.fromDownload(f).retryable)
        }
    }

    @Test
    fun `磁盘满不可重试但引导清缓存`() {
        val copy = UpdateFailureMapper.fromDownload(DownloadFailure.DISK_FULL)
        assertFalse(copy.retryable)
        assertTrue(copy.promoteCleanCache)
    }

    @Test
    fun `自动重试上限按错误类型分级`() {
        assertEquals(3, UpdateFailureMapper.maxAutoRetries(DownloadFailure.DNS))
        assertEquals(3, UpdateFailureMapper.maxAutoRetries(DownloadFailure.READ_TIMEOUT))
        assertEquals(2, UpdateFailureMapper.maxAutoRetries(DownloadFailure.HTTP_5XX))
        assertEquals(2, UpdateFailureMapper.maxAutoRetries(DownloadFailure.HTTP_OTHER))
        assertEquals(0, UpdateFailureMapper.maxAutoRetries(DownloadFailure.HTTP_401))
        assertEquals(0, UpdateFailureMapper.maxAutoRetries(DownloadFailure.HTTP_403))
        assertEquals(0, UpdateFailureMapper.maxAutoRetries(DownloadFailure.HTTP_404))
        assertEquals(0, UpdateFailureMapper.maxAutoRetries(DownloadFailure.DISK_FULL))
    }

    @Test
    fun `校验失败分诊表`() {
        // 读文件失败与哈希不符：重下即可
        assertTrue(UpdateFailureMapper.fromVerify(VerifyFailure.IO).retryable)
        assertTrue(UpdateFailureMapper.fromVerify(VerifyFailure.SIZE_MISMATCH).retryable)
        assertTrue(UpdateFailureMapper.fromVerify(VerifyFailure.HASH_MISMATCH).retryable)
        // 包名不符 / 版本不更新：重下同一个包还是错，必须管理员换文件
        assertFalse(UpdateFailureMapper.fromVerify(VerifyFailure.PACKAGE_MISMATCH).retryable)
        assertFalse(UpdateFailureMapper.fromVerify(VerifyFailure.NOT_AN_APK).retryable)
        assertFalse(UpdateFailureMapper.fromVerify(VerifyFailure.VERSION_NOT_NEWER).retryable)
    }

    @Test
    fun `安装失败只有用户取消与未知可重试`() {
        listOf(
            InstallFailure.BLOCKED,
            InstallFailure.CONFLICT,
            InstallFailure.INCOMPATIBLE,
            InstallFailure.INVALID
        ).forEach {
            assertFalse("$it 不应自动重试", UpdateFailureMapper.fromInstall(it).retryable)
        }
        listOf(InstallFailure.ABORTED, InstallFailure.RECEIPT_LOST, InstallFailure.UNKNOWN).forEach {
            assertTrue("$it 可重试", UpdateFailureMapper.fromInstall(it).retryable)
        }
    }

    @Test
    fun `安装存储不足引导清缓存`() {
        val copy = UpdateFailureMapper.fromInstall(InstallFailure.STORAGE)
        assertFalse(copy.retryable)
        assertTrue(copy.promoteCleanCache)
    }
}
