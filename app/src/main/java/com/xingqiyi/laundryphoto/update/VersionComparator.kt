package com.xingqiyi.laundryphoto.update

import java.util.regex.Pattern

/**
 * 版本号比较器：与桌面端 `store.js:1507 compareVersions` 逐条对齐。
 *
 * ## 为什么要单独做一个比较器，且必须逐条对齐
 *
 * 更新发现与「是否提示更新」的判据全部依赖它。如果移动端与服务器各写一套，
 * 任何一边对「`1.10.0` 是否大于 `1.9.0`」的理解不一致，都会导致「该更新却没提示」
 * 或「明明最新却一直提示」——而且是只在特定版本号组合下才暴露的玄学 bug。
 * 因此本项目铁律（design.md §10.6）：**版本比较只允许走这里**，且语义必须与 JS 一致，
 * 由 `VersionComparatorTest` 一张用例表守护。
 *
 * ## JS compareVersions 的每一条语义（必须复刻）
 *
 * - `String(a || '0')`：null/空白 → `"0"`；
 * - `replace(/^v/i, '')`：只去**开头一个** v/V（大小写不敏感），不做全局替换；
 * - `split('.')`：按 `.` 切，空段保留（`"1..0"` → `["1","","0"]`）；
 * - `parseInt(n, 10)`：**取前导数字**，遇非数字即止，支持前导 `-`；`"007"` → 7、
 *   `"0-debug"` → 0、`"12-hotfix"` → 12、非数字 → 0；
 * - `|| 0`：NaN → 0；
 * - 缺位补 0：循环上界取两边长度的最大值，越界取 0（`"1.2"` == `"1.2.0"`）；
 * - 返回值：1 / -1 / 0。
 */
object VersionComparator : Comparator<String> {

    /** 取前导数字：`^-?\d+`（对应 JS 的 parseInt 取前导整数语义，而非 Kotlin 的 toIntOrNull）。 */
    private val LEADING_INT = Pattern.compile("^-?\\d+")

    /** 去掉开头的 v/V 前缀（对应 JS `replace(/^v/i, '')`）。 */
    private fun stripLeadingV(v: String): String {
        if (v.length >= 2 && (v[0] == 'v' || v[0] == 'V') && v[1].isDigit()) {
            return v.substring(1)
        }
        return v
    }

    /** 把一段版本号字符串解析成非负整数（对应 JS parseInt：前导数字、NaN→0）。 */
    private fun segmentToInt(seg: String): Int {
        if (seg.isEmpty()) return 0
        val m = LEADING_INT.matcher(seg)
        if (!m.find()) return 0
        return runCatching { m.group().toInt() }.getOrDefault(0)
    }

    override fun compare(a: String, b: String): Int {
        val pa = stripLeadingV(a).split('.')
        val pb = stripLeadingV(b).split('.')
        val max = maxOf(pa.size, pb.size)
        for (i in 0 until max) {
            val x = if (i < pa.size) segmentToInt(pa[i]) else 0
            val y = if (i < pb.size) segmentToInt(pb[i]) else 0
            if (x != y) return if (x > y) 1 else -1
        }
        return 0
    }

    fun isNewer(candidate: String, current: String): Boolean = compare(candidate, current) > 0

    /**
     * 规范化（用于 UI 展示 / skippedVersion 落盘）：只去开头的 v/V，
     * **不**处理后缀。后缀（"-debug"）交给 [UpdateContract.SelfVersion.comparableName] 处理，
     * 两者职责分开，避免掩盖与服务端 compareVersions 的语义差异。
     */
    fun normalize(v: String): String = stripLeadingV(v)
}
