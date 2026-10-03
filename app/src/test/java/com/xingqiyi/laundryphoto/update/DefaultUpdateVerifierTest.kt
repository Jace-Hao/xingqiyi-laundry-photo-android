package com.xingqiyi.laundryphoto.update

import com.xingqiyi.laundryphoto.update.UpdateContract.ApkInfo
import com.xingqiyi.laundryphoto.update.UpdateContract.ApkInspector
import com.xingqiyi.laundryphoto.update.UpdateContract.RemoteApk
import com.xingqiyi.laundryphoto.update.UpdateContract.SelfVersion
import com.xingqiyi.laundryphoto.update.UpdateContract.VerifyFailure
import com.xingqiyi.laundryphoto.update.UpdateContract.VerifyResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * [DefaultUpdateVerifier] + [VerifyRules] 单测。
 *
 * ## 分级校验是防「装错包」的最后一道闸
 *
 * 顺序固定为 size → sha256 → 包名 → versionCode，其中：
 * - **前两级「有才校」**：服务端没给就不校，且**绝不阻断**（D3「没有就不校验」是铁律，
 *   否则老服务端只回一个文件名就会让所有门店永远更新不了）；
 * - **后两级永不跳过**：包名不符 = 装了别人的包；versionCode 不更大 = 系统会以
 *   `INSTALL_FAILED_VERSION_DOWNGRADE` 拒绝覆盖，用户白忙一场还以为是 bug。
 *
 * 用 Fake [ApkInspector] 替换 `PackageManager.getPackageArchiveInfo()`（框架 API，单测调不动），
 * 仿照 `camera/BarcodeDecoder` 把平台依赖推到边界的做法。
 */
class DefaultUpdateVerifierTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val releaseSelf = SelfVersion(
        versionName = "1.1.0",
        versionCode = 10501,
        packageName = "com.xingqiyi.laundryphoto",
        releasePackageName = "com.xingqiyi.laundryphoto",
        isDebug = false
    )

    private val debugSelf = SelfVersion(
        versionName = "1.1.0-debug",
        versionCode = 10501,
        packageName = "com.xingqiyi.laundryphoto.debug",
        releasePackageName = "com.xingqiyi.laundryphoto",
        isDebug = true
    )

    /** 可编排的假 APK 读取器。 */
    private class FakeApkInspector(var info: ApkInfo?) : ApkInspector {
        override fun inspect(file: File): ApkInfo? = info
    }

    private fun apkFile(content: String): File =
        tmp.newFile("app.apk").apply { writeText(content) }

    private fun sha256Of(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    }

    private fun spec(
        file: File,
        size: Long = file.length(),
        sha: String = "",
        fileName: String = "xingqiyi-laundry-photo-android-1.2.0.apk"
    ) = RemoteApk(fileName, "1.2.0", size, sha, "", "")

    // ---------- 第 1 级：大小（有才校） ----------

    @Test
    fun `大小不符时判 SIZE_MISMATCH`() = runTest {
        val f = apkFile("hello")
        val r = DefaultUpdateVerifier(FakeApkInspector(ApkInfo("com.xingqiyi.laundryphoto", 10600, "1.2.0")))
            .verify(f, spec(f, size = 999L), releaseSelf)
        assertEquals(VerifyFailure.SIZE_MISMATCH, (r as VerifyResult.Fail).failure)
    }

    @Test
    fun `服务端未给大小（0）时跳过该级校验`() = runTest {
        // 老服务端只回文件名，不给 size；此时绝不能判失败
        val f = apkFile("hello")
        val r = DefaultUpdateVerifier(FakeApkInspector(ApkInfo("com.xingqiyi.laundryphoto", 10600, "1.2.0")))
            .verify(f, spec(f, size = 0L), releaseSelf)
        assertTrue("size=0 应跳过大校验并走完流程", r is VerifyResult.Ok)
    }

    // ---------- 第 2 级：sha256（有才校） ----------

    @Test
    fun `哈希不符时判 HASH_MISMATCH`() = runTest {
        val f = apkFile("hello")
        val r = DefaultUpdateVerifier(FakeApkInspector(ApkInfo("com.xingqiyi.laundryphoto", 10600, "1.2.0")))
            .verify(f, spec(f, sha = "deadbeef"), releaseSelf)
        assertEquals(VerifyFailure.HASH_MISMATCH, (r as VerifyResult.Fail).failure)
    }

    @Test
    fun `哈希为空串时跳过该级校验`() = runTest {
        val f = apkFile("hello")
        val r = DefaultUpdateVerifier(FakeApkInspector(ApkInfo("com.xingqiyi.laundryphoto", 10600, "1.2.0")))
            .verify(f, spec(f, sha = ""), releaseSelf)
        assertTrue(r is VerifyResult.Ok)
    }

    @Test
    fun `哈希正确时通过`() = runTest {
        val f = apkFile("hello")
        val r = DefaultUpdateVerifier(FakeApkInspector(ApkInfo("com.xingqiyi.laundryphoto", 10600, "1.2.0")))
            .verify(f, spec(f, sha = sha256Of(f)), releaseSelf)
        assertTrue(r is VerifyResult.Ok)
    }

    // ---------- 第 3 级：包名（永不跳过） ----------

    @Test
    fun `包名不符时判 PACKAGE_MISMATCH`() = runTest {
        val f = apkFile("hello")
        val r = DefaultUpdateVerifier(FakeApkInspector(ApkInfo("com.evil.app", 10600, "1.2.0")))
            .verify(f, spec(f), releaseSelf)
        assertEquals(VerifyFailure.PACKAGE_MISMATCH, (r as VerifyResult.Fail).failure)
    }

    @Test
    fun `debug 包允许安装正式包名`() = runTest {
        // 否则 debug 包会把自己下发的正式 APK 判成「别人的包」而永远装不上
        val f = apkFile("hello")
        val r = DefaultUpdateVerifier(FakeApkInspector(ApkInfo("com.xingqiyi.laundryphoto", 10600, "1.2.0")))
            .verify(f, spec(f), debugSelf)
        assertTrue(r is VerifyResult.Ok)
    }

    @Test
    fun `正式包不接受 debug 包名`() = runTest {
        val f = apkFile("hello")
        val r = DefaultUpdateVerifier(FakeApkInspector(ApkInfo("com.xingqiyi.laundryphoto.debug", 10600, "1.2.0")))
            .verify(f, spec(f), releaseSelf)
        assertEquals(VerifyFailure.PACKAGE_MISMATCH, (r as VerifyResult.Fail).failure)
    }

    @Test
    fun `读不出 APK 信息时判 NOT_AN_APK`() = runTest {
        val f = apkFile("not a real apk")
        val r = DefaultUpdateVerifier(FakeApkInspector(null)).verify(f, spec(f), releaseSelf)
        assertEquals(VerifyFailure.NOT_AN_APK, (r as VerifyResult.Fail).failure)
    }

    // ---------- 第 4 级：versionCode（永不跳过） ----------

    @Test
    fun `versionCode 不更大时判 VERSION_NOT_NEWER`() = runTest {
        val f = apkFile("hello")
        val r = DefaultUpdateVerifier(FakeApkInspector(ApkInfo("com.xingqiyi.laundryphoto", 10501, "1.1.0")))
            .verify(f, spec(f), releaseSelf)
        assertEquals(VerifyFailure.VERSION_NOT_NEWER, (r as VerifyResult.Fail).failure)
    }

    @Test
    fun `versionCode 更小时同样判 VERSION_NOT_NEWER`() = runTest {
        // 服务端按文件名挑最高版本，可能挑到一个 versionCode 更小的包——
        // 这一级就是专门挡这种情况的，否则安装时系统会直接拒绝
        val f = apkFile("hello")
        val r = DefaultUpdateVerifier(FakeApkInspector(ApkInfo("com.xingqiyi.laundryphoto", 10000, "0.9.0")))
            .verify(f, spec(f), releaseSelf)
        assertEquals(VerifyFailure.VERSION_NOT_NEWER, (r as VerifyResult.Fail).failure)
    }

    @Test
    fun `全部通过时返回 Ok 并带出包信息`() = runTest {
        val f = apkFile("hello")
        val r = DefaultUpdateVerifier(FakeApkInspector(ApkInfo("com.xingqiyi.laundryphoto", 10600, "1.2.0")))
            .verify(f, spec(f, sha = sha256Of(f)), releaseSelf)
        r as VerifyResult.Ok
        assertEquals("com.xingqiyi.laundryphoto", r.packageName)
        assertEquals(10600L, r.versionCode)
        assertEquals(f.length(), r.sizeBytes)
    }
}
