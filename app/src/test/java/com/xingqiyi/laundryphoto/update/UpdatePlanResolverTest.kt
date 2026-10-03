package com.xingqiyi.laundryphoto.update

import com.xingqiyi.laundryphoto.update.UpdateContract.ForcePostpone
import com.xingqiyi.laundryphoto.update.UpdateContract.ForceTrack
import com.xingqiyi.laundryphoto.update.UpdateContract.RemoteApk
import com.xingqiyi.laundryphoto.update.UpdateContract.SelfVersion
import com.xingqiyi.laundryphoto.update.UpdateContract.SourceReport
import com.xingqiyi.laundryphoto.update.UpdateContract.UpdateOrigin
import com.xingqiyi.laundryphoto.update.UpdatePlanResolver.Resolution
import com.xingqiyi.laundryphoto.update.UpdatePlanResolver.ResolveInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [UpdatePlanResolver] 单测 —— 更新流程的「总决策」都在这里，错一次就是错一整屏。
 *
 * 覆盖的关键分支：无可用包 / 本地已够新 / 跳过命中（自动 vs 手动）/
 * 强制更新在宽限期内（Offer）与超期后（Block）。
 */
class UpdatePlanResolverTest {

    private val self = SelfVersion(
        versionName = "1.1.0",
        versionCode = 10501,
        packageName = "com.xingqiyi.laundryphoto.debug",
        releasePackageName = "com.xingqiyi.laundryphoto",
        isDebug = true
    )

    private fun apk(version: String, file: String = "xingqiyi-$version.apk") = RemoteApk(
        fileName = file,
        versionName = version,
        sizeBytes = 1024L,
        sha256 = "abc",
        notes = "",
        notesSource = ""
    )

    private fun input(
        apk: RemoteApk?,
        force: ForceTrack? = null,
        skipped: String = "",
        postpone: ForcePostpone = ForcePostpone("", 0, 0L),
        now: Long = 1_000_000L,
        manual: Boolean = false
    ) = ResolveInput(
        report = SourceReport(apk, force, UpdateOrigin.MOBILE_API, "192.168.1.10:17521"),
        self = self,
        skippedVersion = skipped,
        postpone = postpone,
        nowMs = now,
        manual = manual
    )

    @Test
    fun `服务端没有可用包时不提示`() {
        assertEquals(Resolution.NoPackage, UpdatePlanResolver.resolve(input(apk = null)))
    }

    @Test
    fun `本地版本已够新时不提示——以本地比较为最终真相`() {
        // 服务端说 1.1.0 有更新，但客户端已经是 1.2.0（管理员手工装了新包）→ 必须判为已是最新
        assertEquals(Resolution.UpToDate, UpdatePlanResolver.resolve(input(apk = apk("1.1.0"))))
    }

    @Test
    fun `同版本不提示`() {
        assertEquals(Resolution.UpToDate, UpdatePlanResolver.resolve(input(apk = apk("1.1.0"))))
    }

    @Test
    fun `有更高版本时给出可选更新`() {
        val r = UpdatePlanResolver.resolve(input(apk = apk("1.2.0")))
        assertTrue(r is Resolution.Offer)
        r as Resolution.Offer
        assertEquals("1.2.0", r.plan.target.versionName)
        assertEquals(false, r.plan.mandatory)
        assertEquals(false, r.previouslySkipped)
    }

    @Test
    fun `自动检查命中跳过版本时静默`() {
        val r = UpdatePlanResolver.resolve(input(apk = apk("1.2.0"), skipped = "1.2.0"))
        assertEquals(Resolution.Skipped, r)
    }

    @Test
    fun `手动检查命中跳过版本时仍提示并标注previouslySkipped`() {
        // 用户主动点「检查更新」还看到「你之前跳过过」，否则会以为检查功能坏了
        val r = UpdatePlanResolver.resolve(input(apk = apk("1.2.0"), skipped = "1.2.0", manual = true))
        assertTrue(r is Resolution.Offer)
        assertTrue((r as Resolution.Offer).previouslySkipped)
    }

    @Test
    fun `强制更新在宽限期内仍可稍后再说`() {
        val target = apk("1.2.0")
        val r = UpdatePlanResolver.resolve(
            input(
                apk = target,
                force = ForceTrack(target.fileName, "1.2.0", true),
                postpone = ForcePostpone("1.2.0", 1, 1_000_000L)
            )
        )
        assertTrue(r is Resolution.Offer)
        r as Resolution.Offer
        assertTrue(r.plan.mandatory)
        assertEquals(UpdatePlanResolver.MAX_POSTPONE - 1, r.plan.remainingPostpone)
    }

    @Test
    fun `推迟次数用尽后转为阻断`() {
        val target = apk("1.2.0")
        val r = UpdatePlanResolver.resolve(
            input(
                apk = target,
                force = ForceTrack(target.fileName, "1.2.0", true),
                postpone = ForcePostpone("1.2.0", UpdatePlanResolver.MAX_POSTPONE, 1_000_000L)
            )
        )
        assertTrue("推迟次数用尽必须阻断", r is Resolution.Block)
    }

    @Test
    fun `超过 24 小时宽限期后转为阻断`() {
        val target = apk("1.2.0")
        val r = UpdatePlanResolver.resolve(
            input(
                apk = target,
                force = ForceTrack(target.fileName, "1.2.0", true),
                postpone = ForcePostpone("1.2.0", 0, 0L),
                now = UpdatePlanResolver.GRACE_WINDOW_MS + 1L
            )
        )
        assertTrue("超过 24h 必须阻断", r is Resolution.Block)
    }

    @Test
    fun `强推的是别的文件时不视为强制`() {
        // 管理员强推了另一个文件（文件名对不上）→ 本次更新不是强制的，用户可以跳过
        val r = UpdatePlanResolver.resolve(
            input(apk = apk("1.2.0"), force = ForceTrack("other.apk", "1.2.0", true))
        )
        assertTrue(r is Resolution.Offer)
        assertEquals(false, (r as Resolution.Offer).plan.mandatory)
    }

    @Test
    fun `强推文件不存在时不视为强制`() {
        val target = apk("1.2.0")
        val r = UpdatePlanResolver.resolve(
            input(apk = target, force = ForceTrack(target.fileName, "1.2.0", false))
        )
        assertEquals(false, (r as Resolution.Offer).plan.mandatory)
    }
}
