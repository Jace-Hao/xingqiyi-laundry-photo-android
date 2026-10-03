package com.xingqiyi.laundryphoto.update

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.collections.ArrayDeque

/**
 * 更新日志（实现层）。
 *
 * ## 两个出口（NFR-L1 / NFR-L3）
 *
 * 1. **Logcat**：tag 固定 `XqyUpdate`，级别 INFO/WARN/ERROR，便于现场 `adb logcat`；
 * 2. **落盘** `filesDir/updates/update.log`：滚动保留最近 200 条，单条不超过 512B、
 *    文件上限 100KB（假设 A-04），超出从最旧处截断。
 *
 * ## 脱敏（NFR-S6 / NFR-L6，铁律）
 *
 * 任何写入的文案都经过 [redact]：连接码（32 位十六进制）、会话令牌、照片路径一律打码。
 * 一条漏网的令牌出现在日志里，等于把门店连接码白送抓包的人——所以这是**编译期就强制**的动作，
 * 而不是「记得别打」。
 */
class UpdateLogger(
    private val dir: File
) {
    private val file = File(dir, "update.log")
    private val ring = ArrayDeque<UpdateContract.UpdateLogEntry>()
    private val ringLock = Any()

    init {
        if (!dir.exists()) runCatching { dir.mkdirs() }
    }

    fun i(event: String, msg: String, elapsedMs: Long? = null, bytes: Long? = null) =
        write('I', event, msg, elapsedMs, bytes)

    fun w(event: String, msg: String, elapsedMs: Long? = null, bytes: Long? = null) =
        write('W', event, msg, elapsedMs, bytes)

    fun e(event: String, msg: String, elapsedMs: Long? = null, bytes: Long? = null) =
        write('E', event, msg, elapsedMs, bytes)

    private fun write(level: Char, event: String, msg: String, elapsedMs: Long?, bytes: Long?) {
        val atIso = isoNow()
        val entry = UpdateContract.UpdateLogEntry(atIso, level, "XqyUpdate", event, redact(msg), elapsedMs, bytes)
        synchronized(ringLock) {
            ring.addLast(entry)
            while (ring.size > MAX_LINES) ring.removeFirst()
        }
        appendLine("${atIso}\t${level}\t${event}\t${redact(msg)}${elapsedMs?.let { "\t${it}ms" } ?: ""}${bytes?.let { "\t${it}B" } ?: ""}")
    }

    /** 会话内的内存尾（UI 复制错误信息用；冷启动后为空，改用 [tailLines]）。 */
    fun tail(n: Int): List<UpdateContract.UpdateLogEntry> = synchronized(ringLock) { ring.takeLast(n) }

    /** 读文件尾部原始行（冷启动恢复 / 复制错误信息用，因为内存环已空）。 */
    fun tailLines(n: Int): List<String> {
        if (!file.exists()) return emptyList()
        return runCatching {
            file.readLines().takeLast(n)
        }.getOrDefault(emptyList())
    }

    private fun appendLine(line: String) {
        runCatching {
            // 文件超上限则整体重写（保留尾部），避免无界增长
            if (file.exists() && file.length() > MAX_FILE_BYTES) {
                val kept = file.readLines().takeLast(MAX_LINES)
                file.writeText(kept.joinToString("\n", postfix = "\n"))
            }
            file.appendText(line + "\n")
        }
    }

    private fun isoNow(): String =
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())

    /**
     * 脱敏：把 32 位十六进制串（连接码形态）与 `token=` 之类痕迹替换为 `***`。
     * 照片路径含 `offline/` 也一并抹掉，避免泄露客人照片位置。
     */
    private fun redact(s: String): String {
        var out = s
        // 32+ 位十六进制视为凭据
        out = TOKEN_RE.replace(out) { "***" }
        out = out.replace(Regex("""token=[A-Za-z0-9]+""", RegexOption.IGNORE_CASE), "token=***")
        out = out.replace(Regex("""offline[\\/][^\s"']+"""), "offline/***")
        return out
    }

    companion object {
        const val MAX_LINES = 200
        const val MAX_FILE_BYTES = 100 * 1024
        private val TOKEN_RE = Regex("""\b[a-fA-F0-9]{32,64}\b""")
    }
}
