package com.xingqiyi.laundryphoto.update

/**
 * APK 文件名安全校验（纯函数，无 Android 依赖，可 JVM 单测）。
 *
 * ## 为什么要单独抽出来守住「文件名」
 *
 * 更新包的落盘路径**永远由客户端按服务端返回的 `fileName` 拼接**，而服务端返回的是
 * 一个外部字符串。一旦它带 `../` 或绝对路径分隔符，落盘就可能写到 `filesDir` 之外
 * （覆盖别的文件、写出沙箱），这是典型的路径穿越漏洞。
 *
 * 因此规则必须最严：
 * 1. 不以 `.apk`（忽略大小写）结尾的一律拒绝——移动端只认 APK；
 * 2. 禁 `..`、禁 `\`/`/`（文件名里不允许任何路径分隔符，只认**文件名**本体）；
 * 3. 字符集只允许 `[A-Za-z0-9._-]`，杜绝控制字符与空格等意外字符；
 * 4. `requireSafe()` 在落盘前被 `UpdateFileStore` 调用，非法名直接抛异常，
 *    调用方据此把这次发现静默当「无可用包」处理，绝不猜测或拼接文件名（NFR-S7）。
 */
object ApkFileNameSafety {

    /** 仅允许安全字符且以 .apk 结尾（忽略大小写），禁止任何路径分隔符与 `..`。 */
    private val SAFE_NAME = Regex("""^[A-Za-z0-9._-]+\.apk$""", RegexOption.IGNORE_CASE)

    /** 是否安全。供调用方在「发现结果里挑文件」时做防御性判断。 */
    fun isSafe(name: String): Boolean {
        if (name.isBlank()) return false
        // 显式拒绝路径穿越：任何分隔符或父目录记号都不允许出现在文件名里
        if (name.contains("..")) return false
        if (name.any { it == '/' || it == '\\' }) return false
        return SAFE_NAME.matches(name)
    }

    /**
     * 校验通过返回原值；否则抛 [IllegalArgumentException]，调用方据此把该候选静默丢弃。
     */
    fun requireSafe(name: String): String {
        require(isSafe(name)) { "非法的 APK 文件名（疑似路径穿越或扩展名不符）：$name" }
        return name
    }
}
