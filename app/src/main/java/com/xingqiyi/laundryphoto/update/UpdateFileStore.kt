package com.xingqiyi.laundryphoto.update

import android.os.StatFs
import java.io.File
import java.io.IOException

/**
 * 更新包文件空间（实现层）。
 *
 * ## 目录策略（design.md §1.3 / NFR-S3）
 *
 * APK 只落应用私有目录 `filesDir/updates/`，**不**放 `cacheDir`——
 * 国内清理类 App 会扫 cacheDir 乱删；用 `backup_rules.xml` / `data_extraction_rules.xml`
 * 把该目录排除出 Auto Backup，避免把约 20MB 的 APK 计入 25MB 备份配额挤掉 Room 数据库。
 *
 * ## 写盘契约（NFR-R3）
 *
 * 下载器永远先写 `<name>.part`，成功后才由本类的 `commitPart()` 做**同目录原子 rename**
 * （同一文件系统内 `renameTo` 是原子的）。任何中断都会留下 `.part`，由 `sweep()` 清理，
 * 绝不会产生「看起来完整其实是半截」的坏 APK。
 *
 * ## 磁盘预检（U-17）
 *
 * `requiredBytesFor()` = `size * 1.5 + 20MB`：给下载过程中的临时膨胀与系统保留空间留余量，
 * 不足时下载器**一个字节都不下**（Design 共享约定 11）。
 */
class DefaultUpdateFileStore(
    private val rootDir: File
) : UpdateContract.UpdateFileStore {

    init {
        if (!rootDir.exists()) runCatching { rootDir.mkdirs() }
    }

    override val updateDir: File get() = rootDir

    override fun finalFileFor(fileName: String): File =
        File(rootDir, ApkFileNameSafety.requireSafe(fileName))

    override fun partFileFor(fileName: String): File =
        File(rootDir, ApkFileNameSafety.requireSafe(fileName) + ".part")

    override fun requiredBytesFor(sizeBytes: Long): Long =
        (sizeBytes * 3 / 2) + 20L * 1024 * 1024

    override fun availableBytes(): Long = runCatching {
        val st = StatFs(rootDir.absolutePath)
        st.availableBlocksLong * st.blockSizeLong
    }.getOrDefault(0L)

    @Throws(IOException::class)
    override fun commitPart(fileName: String) {
        val part = partFileFor(fileName)
        val final = finalFileFor(fileName)
        if (!part.exists()) throw IOException("更新包临时文件不存在，无法提交：$part")
        // 同名最终文件若存在先删，避免 renameTo 在某些 ROM 上失败
        if (final.exists()) runCatching { final.delete() }
        if (!part.renameTo(final)) throw IOException("更新包落盘失败：$final")
    }

    /**
     * 清理：删除全部 `.part`，以及超过保留期且不在保护窗内的 `.apk`。
     *
     * @param nowMs        当前墙上时间
     * @param protectUntilMs 安装会话保护窗截止时间；若 `nowMs < protectUntilMs`，当次安装的 APK 不被删
     * @param retentionMs  最终文件最大保留时长（冷启动清理用 7 天；「清理更新缓存」按钮传 0 表示全清）
     * @return 释放的字节数
     */
    override fun sweep(nowMs: Long, protectUntilMs: Long, retentionMs: Long): Long {
        val files = rootDir.listFiles() ?: return 0L
        var freed = 0L
        for (f in files) {
            if (f.isDirectory) continue
            when {
                f.name.endsWith(".part") -> {
                    freed += f.length()
                    runCatching { f.delete() }
                }
                f.name.endsWith(".apk", ignoreCase = true) -> {
                    val protectedNow = nowMs < protectUntilMs
                    val tooOld = nowMs - f.lastModified() > retentionMs
                    if (!protectedNow && tooOld) {
                        freed += f.length()
                        runCatching { f.delete() }
                    }
                }
                // update.log 等其它文件一律保留
            }
        }
        return freed
    }

    /**
     * 「清理更新缓存」按钮用：无视保留期与保护窗，清空所有 `.part` 与 `.apk`。
     *
     * ## 为什么不用 `sweep(now, 0, 0)` 顺手实现
     *
     * `sweep` 的「是否过期」判据是 `nowMs - lastModified > retentionMs`。`retentionMs=0`
     * 只在 `nowMs` **不小于**文件 mtime 时才成立；一旦调用方传入的 `nowMs` 小于文件
     * mtime（时钟回拨、测试里传 0、或未来时间戳文件），差值为负，「清空」就会静默变成
     * 「什么都没删」——而用户看到的是「清理后空间没释放」。
     *
     * 因此这里显式走 `Long.MAX_VALUE` 作为参考时间：差值必然为正，任何 `.apk` 都会被删。
     * 保护窗（30 分钟）只在**安装会话进行中**才有意义，而用户主动点「清理」时
     * 安装会话不可能正在等待回执（那时界面停在安装页），所以这里不查保护窗是安全的。
     */
    fun clearAll(nowMs: Long): Long = sweep(
        nowMs = maxOf(nowMs, Long.MAX_VALUE),
        protectUntilMs = 0L,
        retentionMs = 0L
    )
}
