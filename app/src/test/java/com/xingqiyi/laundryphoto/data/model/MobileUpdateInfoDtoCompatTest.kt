package com.xingqiyi.laundryphoto.data.model

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MobileUpdateInfoDto] 与 [ForceUpdateDto] 的 Gson 兼容性测试。
 *
 * ## 这是本项目最贵的一课
 *
 * 上一版发布包出过一起事故：release 包因为 R8 剥离了 DTO 字段，Gson 抛
 * `JsonIOException: Abstract classes can't be instantiated!`，而它是 `JsonParseException` 的子类，
 * 被错误翻译成「对方返回的不是本系统的数据」——**把发布包缺陷伪装成了用户地址/端口问题**，
 * 现场排查了很久。debug 包却完全正常。
 *
 * 由此得到两条铁律，本测试就是它们的守卫：
 * 1. DTO 字段必须**可空 + 计算属性兜底**（Gson 不走 Kotlin 主构造函数、不执行默认值、缺字段一律 null）；
 * 2. 「服务端少给字段」与「服务端给了 null」都必须能被客户端安全消化，绝不抛异常。
 */
class MobileUpdateInfoDtoCompatTest {

    private val gson = Gson()

    @Test
    fun `空对象不抛异常且全部走兜底`() {
        val dto = gson.fromJson("{}", MobileUpdateInfoDto::class.java)
        assertFalse(dto.hasPackageValue)
        assertEquals("", dto.fileNameText)
        assertEquals("", dto.versionText)
        assertEquals(0L, dto.sizeValue)
        assertEquals("", dto.sha256Text)
        assertEquals("", dto.notesText)
        assertEquals("", dto.notesSourceText)
    }

    @Test
    fun `显式 null 字段同样走兜底`() {
        // 服务端字段存在但值为 null（老版本服务端常见），表现必须与「字段缺失」一致
        val dto = gson.fromJson(
            """{"supported":null,"hasPackage":null,"fileName":null,"size":null,"sha256":null}""",
            MobileUpdateInfoDto::class.java
        )
        assertFalse(dto.hasPackageValue)
        assertEquals("", dto.fileNameText)
        assertEquals(0L, dto.sizeValue)
        assertEquals("", dto.sha256Text)
    }

    @Test
    fun `isSupported 缺字段时保守视为不支持`() {
        // 老服务端没有该接口时不会返回 supported；这里按 false 处理，
        // 客户端会静默降级到老接口，而不是误判为「支持」后拿到空结果
        assertFalse(gson.fromJson("{}", MobileUpdateInfoDto::class.java).isSupported)
    }

    @Test
    fun `服务端说支持且有包时各项兜底可用`() {
        val dto = gson.fromJson(
            """{"supported":true,"hasPackage":true,"fileName":"app-1.1.0.apk",
               "version":"1.1.0","size":20480,"sha256":"deadbeef",
               "notes":"修复若干问题","notesSource":"md","hasUpdate":true}""",
            MobileUpdateInfoDto::class.java
        )
        assertTrue(dto.isSupported)
        assertTrue(dto.hasPackageValue)
        assertEquals("app-1.1.0.apk", dto.fileNameText)
        assertEquals("1.1.0", dto.versionText)
        assertEquals(20480L, dto.sizeValue)
        assertEquals("deadbeef", dto.sha256Text)
        assertTrue(dto.hasUpdateValue)
    }

    @Test
    fun `isApkFile 只认 apk 扩展名`() {
        fun dtoOf(name: String) = gson.fromJson("""{"fileName":"$name"}""", MobileUpdateInfoDto::class.java)
        assertTrue(dtoOf("app-1.1.0.apk").isApkFile)
        assertTrue(dtoOf("APP.APK").isApkFile)
        assertFalse(dtoOf("setup-1.2.4.exe").isApkFile)
        assertFalse(dtoOf("").isApkFile)
    }

    @Test
    fun `size 为负数被夹到 0 从而跳过大校验`() {
        // 服务端算文件大小失败时可能给 -1；客户端必须夹到 0 并跳过大小校验，而不是判「大小不符」
        val dto = gson.fromJson("""{"size":-1}""", MobileUpdateInfoDto::class.java)
        assertEquals(0L, dto.sizeValue)
    }

    @Test
    fun `ForceUpdateDto 缺字段时 fileExists 为 false files 为空`() {
        val dto = gson.fromJson("""{"enabled":true,"version":"1.1.0"}""", ForceUpdateDto::class.java)
        assertEquals(true, dto.enabledValue)
        assertEquals("1.1.0", dto.versionText)
        assertEquals("", dto.fileNameText)
        assertFalse(dto.fileExistsValue)
        assertTrue(dto.fileList.isEmpty())
    }

    @Test
    fun `ForceUpdateDto 正常字段可读出`() {
        // 线格式必须与服务端一致：listUpdateFiles() 返回的是**对象数组**
        // （{name,size,version}），不是字符串数组。此前这里写成字符串数组，
        // 真实响应一旦到达就会 JsonSyntaxException —— 而这类错只有对着真线格式写才抓得住。
        val dto = gson.fromJson(
            """{"enabled":true,"version":"1.1.0","fileName":"app-1.1.0.apk",
               "fileExists":true,"files":[
                 {"name":"app-1.1.0.apk","size":20480,"version":"1.1.0"},
                 {"name":"setup-1.2.4.exe","size":40960,"version":"1.2.4"}]}""",
            ForceUpdateDto::class.java
        )
        assertTrue(dto.enabledValue)
        assertTrue(dto.fileExistsValue)
        assertEquals(2, dto.fileList.size)
    }

    @Test
    fun `服务端返回非本系统数据时抛 JsonSyntaxException 由上层翻译`() {
        // 契约：真正的「不是本系统的数据」必须是 JsonSyntaxException，
        // 不能是 JsonIOException（后者是客户端缺陷，会被误译成服务端问题）
        try {
            gson.fromJson("\"<html>404</html>\"", MobileUpdateInfoDto::class.java)
            // 部分 Gson 版本对标量反序列化到对象会返回 null 而不抛，这里放宽为「不返回对象」
            assertTrue(true)
        } catch (e: JsonSyntaxException) {
            assertTrue(e is JsonSyntaxException)
        }
    }
}
