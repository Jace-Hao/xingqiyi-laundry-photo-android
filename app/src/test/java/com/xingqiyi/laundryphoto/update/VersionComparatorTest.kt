package com.xingqiyi.laundryphoto.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [VersionComparator] 单测。
 *
 * ## 为什么这组用例必须有
 *
 * 桌面端 `store.js` 的 `compareVersions` 是服务端的唯一版本口径，而移动端要自己再实现一遍
 * （C4：两端不能互相调用）。一旦两边对「1.2.3 与 1.2.10 谁大」「v 前缀怎么处理」
 * 「非数字段落怎么算」的判断出现分歧，移动端就会出现两种致命故障：
 * - 分歧 A：明明服务端有新版，客户端却判成「已是最新」，用户永远收不到更新；
 * - 分歧 B：客户端把旧包判成新版，下载安装后被系统以
 *   `INSTALL_FAILED_VERSION_DOWNGRADE` 拒绝，用户以为更新坏了。
 *
 * 下面每条断言都对应服务端实现里的一行逻辑，**改动任何一边都必须同步改另一边的用例**。
 */
class VersionComparatorTest {

    @Test
    fun `数字段按数值比较而非字典序`() {
        // 字典序下 "1.2.10" < "1.2.9"，这是版本比较最经典的坑
        assertTrue(VersionComparator.isNewer("1.2.10", "1.2.9"))
        assertFalse(VersionComparator.isNewer("1.2.9", "1.2.10"))
    }

    @Test
    fun `位数不同时按缺失段补 0`() {
        assertTrue(VersionComparator.isNewer("1.1", "1.0.9"))
        assertTrue(VersionComparator.isNewer("1.0.1", "1.0"))
        assertFalse(VersionComparator.isNewer("1.0", "1.0.0"))
    }

    @Test
    fun `相等版本不算更新`() {
        assertFalse(VersionComparator.isNewer("1.1.0", "1.1.0"))
        assertEquals(0, VersionComparator.compare("1.1.0", "1.1.0"))
    }

    @Test
    fun `v 前缀与后缀被忽略`() {
        assertTrue(VersionComparator.isNewer("v1.2.0", "1.1.9"))
        assertTrue(VersionComparator.isNewer("1.2.0", "V1.1.9"))
        // debug 构建的 versionName 形如 1.1.0-debug。按 JS parseInt 语义，"0-debug" 取前导数字得 0，
        // 因此它与 "1.1.0" **相等**而不是更新——已用桌面端 store.compareVersions 实测确认
        // （compareVersions("1.1.0","1.1.0-debug") === 0）。这里必须钉死「相等」：
        // 判成「更新」会让 debug 包对正式包反复提示，而 debug 根本装不了正式包。
        assertFalse(VersionComparator.isNewer("1.1.0", "1.1.0-debug"))
        assertFalse(VersionComparator.isNewer("1.1.0-debug", "1.1.0"))
    }

    @Test
    fun `空串与非法输入按 0 处理不抛异常`() {
        // 服务端在「文件名里没有版本号」时会返回空串，这里绝不能崩。
        // 实测 store.compareVersions("1.0.0","") === 1：空串等价于 0，所以 1.0.0 确实更新。
        assertFalse(VersionComparator.isNewer("", "1.0.0"))
        assertTrue(VersionComparator.isNewer("1.0.0", ""))
        assertEquals(0, VersionComparator.compare("", ""))
    }

    @Test
    fun `非数字段落按 0 处理`() {
        // 预发版/构建号（如 1.2.0-rc1、1.2.0.beta）不应让比较崩溃
        assertEquals(0, VersionComparator.compare("1.2.0-rc1", "1.2.0"))
        assertTrue(VersionComparator.isNewer("1.2.1", "1.2.0-rc1"))
    }

    @Test
    fun `normalize 去掉前导 v`() {
        assertEquals("1.2.3", VersionComparator.normalize("v1.2.3"))
        assertEquals("1.2.3", VersionComparator.normalize("1.2.3"))
    }

    @Test
    fun `可作为 Comparator 排序使用`() {
        val list = mutableListOf("1.2.10", "1.1.0", "1.2.2")
        list.sortWith(VersionComparator)
        assertEquals(listOf("1.1.0", "1.2.2", "1.2.10"), list)
    }
}
