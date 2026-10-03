package com.xingqiyi.laundryphoto.update

import java.io.File
import java.security.MessageDigest

/**
 * 更新包校验（实现层，四级顺序不可变）。
 *
 * 1. **size**：服务端给了且 `>0` 才校；否则跳过（D3「没有就不校验」）；
 * 2. **sha256**：服务端给了（非空串）才流式计算比对；空串跳过，绝不阻断更新；
 * 3. **包名**：交给 `VerifyRules`（含 debug 放宽），永不跳过；
 * 4. **versionCode**：严格大于已安装，永不跳过。
 *
 * 1、2 可跳过（服务端没给信息时），3、4 永不跳过——这是防「装山寨包 / 装更旧版本」的底线。
 * 校验失败由**编排器负责删除残缺文件**（NFR-R3 原子写 + 失败即清）。
 */
class DefaultUpdateVerifier(
    private val inspector: UpdateContract.ApkInspector
) : UpdateContract.UpdateVerifier {

    override suspend fun verify(
        file: File,
        spec: UpdateContract.RemoteApk,
        self: UpdateContract.SelfVersion
    ): UpdateContract.VerifyResult {
        // 1. 大小（服务端给了且 != 0 才校）
        if (spec.sizeBytes > 0) {
            val actualSize = runCatching { file.length() }.getOrDefault(-1L)
            if (actualSize != spec.sizeBytes) {
                return UpdateContract.VerifyResult.Fail(
                    UpdateContract.VerifyFailure.SIZE_MISMATCH,
                    "${spec.sizeBytes}",
                    "$actualSize"
                )
            }
        }

        // 2. 哈希（服务端给了才校；算不出来按 IO 失败处理）
        if (spec.sha256.isNotBlank()) {
            val got = runCatching { sha256Of(file) }.getOrNull()
            if (got == null) {
                return UpdateContract.VerifyResult.Fail(
                    UpdateContract.VerifyFailure.IO,
                    spec.sha256,
                    ""
                )
            }
            if (!got.equals(spec.sha256, ignoreCase = true)) {
                return UpdateContract.VerifyResult.Fail(
                    UpdateContract.VerifyFailure.HASH_MISMATCH,
                    spec.sha256,
                    got
                )
            }
        }

        // 3 + 4. 包名与版本号（永远校）
        val info = inspector.inspect(file)
            ?: return UpdateContract.VerifyResult.Fail(
                UpdateContract.VerifyFailure.NOT_AN_APK,
                self.packageName,
                ""
            )
        val fileSize = runCatching { file.length() }.getOrDefault(0L)
        return VerifyRules.check(spec, info, self, fileSize)
    }

    private fun sha256Of(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { inp ->
            val buf = ByteArray(8 * 1024)
            var n: Int
            while (inp.read(buf).also { n = it } != -1) {
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
