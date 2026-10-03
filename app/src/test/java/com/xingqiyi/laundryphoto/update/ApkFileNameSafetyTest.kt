package com.xingqiyi.laundryphoto.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ApkFileNameSafety] 单测。
 *
 * ## 这是全模块最该有单测的一处
 *
 * 文件名来自服务端响应，是**外部输入**。如果直接把它拼进落盘路径，
 * 一个名为 `../../databases/xingqiyi.db` 的响应就能把 APK 写到数据库目录旁边，
 * 覆盖用户数据（PRD 决策 D1 要求「禁止客户端猜测/拼接文件名」，服务端给什么就用什么，
 * 但「用」之前必须先验一把）。
 *
 * 真实事故参照：扫码连拍功能里，条码是外部输入，拼目录名时必须过 `BurstPhotoStore.safeName()`，
 * 否则同一个坑在另一个模块等着。
 */
class ApkFileNameSafetyTest {

    @Test
    fun `正常 APK 文件名通过`() {
        assertTrue(ApkFileNameSafety.isSafe("xingqiyi-laundry-photo-android-1.1.0.apk"))
        assertTrue(ApkFileNameSafety.isSafe("app.1.1.0.apk"))
        assertTrue(ApkFileNameSafety.isSafe("APP.APK"))
        assertTrue(ApkFileNameSafety.isSafe("a_b-c.1.0.apk"))
    }

    @Test
    fun `拒绝路径穿越`() {
        assertFalse(ApkFileNameSafety.isSafe("../evil.apk"))
        assertFalse(ApkFileNameSafety.isSafe("..\\evil.apk"))
        assertFalse(ApkFileNameSafety.isSafe("a/b.apk"))
        assertFalse(ApkFileNameSafety.isSafe("a\\b.apk"))
        assertFalse(ApkFileNameSafety.isSafe("/etc/passwd.apk"))
    }

    @Test
    fun `拒绝非 apk 扩展名`() {
        // 服务端误把桌面端安装包下发过来时，必须在客户端就挡住
        assertFalse(ApkFileNameSafety.isSafe("xingqiyi-laundry-photo-setup-1.2.4.exe"))
        assertFalse(ApkFileNameSafety.isSafe("notes.md"))
        assertFalse(ApkFileNameSafety.isSafe("apk"))
    }

    @Test
    fun `拒绝空串与空白`() {
        assertFalse(ApkFileNameSafety.isSafe(""))
        assertFalse(ApkFileNameSafety.isSafe("   "))
    }

    @Test
    fun `拒绝含空格与中文等非白名单字符`() {
        // 中文名在 GitHub Release 上会被剥离（文件名只允许 ASCII），这里提前拒绝更省事
        assertFalse(ApkFileNameSafety.isSafe("星期衣 1.1.0.apk"))
        assertFalse(ApkFileNameSafety.isSafe("app 1.1.0.apk"))
        assertFalse(ApkFileNameSafety.isSafe("app;rm.apk"))
    }

    @Test
    fun `requireSafe 合法时原样返回`() {
        assertEquals(
            "xingqiyi-laundry-photo-android-1.1.0.apk",
            ApkFileNameSafety.requireSafe("xingqiyi-laundry-photo-android-1.1.0.apk")
        )
    }

    @Test
    fun `requireSafe 非法时抛 IllegalArgumentException`() {
        // 调用方靠这个异常把该候选静默丢弃，所以类型必须是 IllegalArgumentException
        assertThrows(IllegalArgumentException::class.java) {
            ApkFileNameSafety.requireSafe("../evil.apk")
        }
    }
}
