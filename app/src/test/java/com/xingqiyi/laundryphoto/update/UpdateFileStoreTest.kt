package com.xingqiyi.laundryphoto.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * [DefaultUpdateFileStore] 单测。
 *
 * 关注三件事：
 * 1. **路径拼接必须先过 [ApkFileNameSafety]**——文件名来自服务端，落到磁盘前必须验；
 * 2. **`.part` 原子提交**——下载中断留下的半截文件绝不能被当成安装包；
 * 3. **清理不能误删**——`sweep` 必须尊重「安装保护窗」，否则正在安装时会把自己脚下的 APK 删掉。
 *
 * 不测 `availableBytes()`：它依赖 `android.os.StatFs`，属于框架 API，需 Robolectric 或真机，
 * 留待 androidTest。
 */
class UpdateFileStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = DefaultUpdateFileStore(tmp.newFolder("updates"))

    private fun write(name: String, bytes: Int): File =
        File(tmp.root, "updates/$name").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(bytes) { 1 })
        }

    @Test
    fun `最终文件与临时文件同目录`() {
        val s = store()
        val name = "app-1.2.0.apk"
        assertEquals("app-1.2.0.apk", s.finalFileFor(name).name)
        assertEquals("app-1.2.0.apk.part", s.partFileFor(name).name)
        assertEquals(s.updateDir.absolutePath, s.finalFileFor(name).parentFile.absolutePath)
    }

    @Test
    fun `路径穿越文件名被拒`() {
        val s = store()
        assertThrows(IllegalArgumentException::class.java) { s.finalFileFor("../evil.apk") }
        assertThrows(IllegalArgumentException::class.java) { s.partFileFor("a/b.apk") }
        assertThrows(IllegalArgumentException::class.java) { s.finalFileFor("setup.exe") }
    }

    @Test
    fun `磁盘需求按 1_5 倍加 20MB 冗余`() {
        val s = store()
        // 30MB 的包 → 45MB + 20MB = 65MB
        assertEquals(65L * 1024 * 1024, s.requiredBytesFor(30L * 1024 * 1024))
        // 服务端没给大小（0）时也要留出基础冗余，否则会「一个字节都不下」地卡死
        assertEquals(20L * 1024 * 1024, s.requiredBytesFor(0L))
    }

    @Test
    fun `commitPart 把临时文件原子改名为最终文件`() {
        val s = store()
        val name = "app-1.2.0.apk"
        write("$name.part", 128)
        s.commitPart(name)
        assertTrue(s.finalFileFor(name).exists())
        assertFalse(s.partFileFor(name).exists())
        assertEquals(128L, s.finalFileFor(name).length())
    }

    @Test
    fun `commitPart 覆盖已存在的最终文件`() {
        // 冷启动恢复时可能已有一个同名的旧包，renameTo 在某些 ROM 上会因此失败
        val s = store()
        val name = "app-1.2.0.apk"
        write(name, 10)
        write("$name.part", 99)
        s.commitPart(name)
        assertEquals(99L, s.finalFileFor(name).length())
    }

    @Test
    fun `临时文件不存在时抛 IOException`() {
        val s = store()
        try {
            s.commitPart("app-1.2.0.apk")
            throw AssertionError("应当抛 IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("app-1.2.0.apk"))
        }
    }

    @Test
    fun `sweep 删除全部 part 文件并计入释放字节`() {
        val s = store()
        write("a.apk.part", 100)
        write("b.apk.part", 200)
        val freed = s.sweep(nowMs = 0L, protectUntilMs = 0L, retentionMs = 0L)
        assertEquals(300L, freed)
        assertFalse(File(tmp.root, "updates/a.apk.part").exists())
        assertFalse(File(tmp.root, "updates/b.apk.part").exists())
    }

    @Test
    fun `sweep 保留保护窗内的安装包`() {
        // 正在走 PackageInstaller 安装时把 APK 删掉，系统会直接失败
        val s = store()
        val apk = write("app-1.2.0.apk", 500)
        apk.setLastModified(1_000L)
        val freed = s.sweep(nowMs = 2_000L, protectUntilMs = 5_000L, retentionMs = 0L)
        assertEquals(0L, freed)
        assertTrue(apk.exists())
    }

    @Test
    fun `sweep 超过保留期后删除旧包`() {
        val s = store()
        val apk = write("app-1.0.0.apk", 500)
        apk.setLastModified(1_000L)
        val freed = s.sweep(nowMs = 1_000L + 8L * 24 * 3600 * 1000, protectUntilMs = 0L, retentionMs = 7L * 24 * 3600 * 1000)
        assertEquals(500L, freed)
        assertFalse(apk.exists())
    }

    @Test
    fun `sweep 保留其它文件（如 update_log）`() {
        // 误删日志会让现场排障失去依据
        val s = store()
        val log = write("update.log", 30)
        s.sweep(nowMs = Long.MAX_VALUE, protectUntilMs = 0L, retentionMs = 0L)
        assertTrue(log.exists())
    }

    @Test
    fun `clearAll 清空所有包与临时文件`() {
        val s = store()
        write("a.apk", 10)
        write("b.apk.part", 20)
        val freed = s.clearAll(0L)
        assertEquals(30L, freed)
        assertTrue(s.updateDir.listFiles()!!.isEmpty())
    }
}
