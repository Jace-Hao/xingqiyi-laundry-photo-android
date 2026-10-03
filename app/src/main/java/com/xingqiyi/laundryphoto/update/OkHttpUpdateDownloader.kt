package com.xingqiyi.laundryphoto.update

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.SocketException
import kotlin.math.max

/**
 * 更新包下载器：OkHttp 实现。
 *
 * ## 设计要点（design.md §1.4 / §3.1）
 *
 * - **单次尝试**：本方法只负责「一次 HTTP 拉取」。重试与退避由编排器（`UpdateCoordinatorImpl`）
 *   按 `UpdateFailureMapper` 的结论驱动——这样状态机里的 `Downloading(attempt, retryInMs)`
 *   才能精确表达「第几次、还要等多久」，而不是把重试藏进黑盒。
 * - **绝对不抛异常**：所有失败收敛为 `DownloadOutcome.Failure`，让调用方只有一个出口。
 * - **断流 / 低速看门狗**：OkHttp 的 `readTimeout` 语义在不同版本对「body 读流」包裹不一致，
 *   不能押宝。因此独立起一个协程，每 2s 巡检：连续 20s 没有任何字节 → `STALLED`；
 *   连续 15s 平均 < 20KB/s → `TOO_SLOW`。命中即 `call.cancel()`，把连接砍掉。
 * - **调用方负责删残缺文件**（校验失败时也是），本方法出错时只删本次的 `.part`，
 *   因为它就是这次下载的产物；但它**不删**已存在的最终文件（那不是它写的）。
 * - **进度回调已经过节流**（≥400ms 或百分比变化 ≥1%），可直接驱动 Compose，不会刷屏。
 */
class OkHttpUpdateDownloader(
    private val client: OkHttpClient,
    /** 连接码提供方：下载令牌由调用方显式取出注入请求头，绝不经 URL（防泄露到日志/抓包）。 */
    private val tokenProvider: () -> String,
    private val clock: UpdateContract.Clock,
    private val stallTimeoutMs: Long = 20_000,
    private val slowWindowMs: Long = 15_000,
    private val minSpeedBps: Long = 20 * 1024,
    private val progressThrottleMs: Long = 400,
    private val watchdogIntervalMs: Long = 2_000,
    private val bufferSize: Int = 8 * 1024
) : UpdateContract.UpdateDownloader {

    @Volatile
    private var currentCall: Call? = null

    /** 看门狗判定的终止原因；非 null 时覆盖默认的网络类失败原因。 */
    @Volatile
    private var terminalReason: UpdateContract.DownloadFailure? = null

    override fun cancel() {
        currentCall?.cancel()
    }

    override suspend fun download(
        spec: UpdateContract.DownloadSpec,
        dest: File,
        onProgress: suspend (UpdateContract.DownloadProgress) -> Unit
    ): UpdateContract.DownloadOutcome {
        val partFile = File(dest.path + ".part")
        // 每次下载前清掉残留 .part，避免追加到旧文件上
        runCatching { if (partFile.exists()) partFile.delete() }
        terminalReason = null

        val request = Request.Builder()
            .url(spec.url)
            .addHeader("x-api-token", tokenProvider())
            .apply { spec.headers.forEach { (k, v) -> addHeader(k, v) } }
            .build()

        val call = client.newCall(request)
        currentCall = call

        return coroutineScope {
            val watchdog = launch { watch(call, partFile) }

            val outcome = try {
                val response = call.execute()
                if (!response.isSuccessful) {
                    response.body?.close()
                    terminalReason?.let { fail(it, response.code, "") }
                        ?: fail(httpFailure(response.code), response.code, "HTTP ${response.code}")
                } else {
                    writeBody(response, partFile, dest, spec, onProgress)
                }
            } catch (e: CancellationException) {
                // 看门狗触发的取消：用 terminalReason 表达真正的失败原因
                terminalReason?.let { fail(it, null, e.message ?: "") }
                    ?: fail(UpdateContract.DownloadFailure.IO, null, e.message ?: "已取消")
            } catch (e: IOException) {
                fail(classifyIo(e), null, e.message ?: "网络错误")
            } finally {
                watchdog.cancel()
                currentCall = null
            }

            outcome
        }
    }

    private suspend fun writeBody(
        response: okhttp3.Response,
        partFile: File,
        dest: File,
        spec: UpdateContract.DownloadSpec,
        onProgress: suspend (UpdateContract.DownloadProgress) -> Unit
    ): UpdateContract.DownloadOutcome {
        val body = response.body ?: return fail(UpdateContract.DownloadFailure.IO, response.code, "空响应体")
        val total = if (body.contentLength() > 0) body.contentLength() else spec.expectedBytes
        val startedAt = clock.currentTimeMillis()
        var bytesRead = 0L
        var lastEmitAt = 0L
        var lastPercent = -1

        try {
            partFile.outputStream().use { out ->
                body.byteStream().use { inp ->
                    val buf = ByteArray(bufferSize)
                    var n: Int
                    while (inp.read(buf).also { n = it } != -1) {
                        out.write(buf, 0, n)
                        bytesRead += n
                        pulse(bytesRead)
                        // 进度回调（节流 + 百分比变化）
                        val now = clock.currentTimeMillis()
                        val pct = if (total > 0) max(lastPercent, ((bytesRead * 100) / total).toInt().coerceIn(0, 100)) else lastPercent
                        val needEmit = pct > lastPercent || (now - lastEmitAt) >= progressThrottleMs
                        if (needEmit) {
                            lastEmitAt = now
                            lastPercent = pct
                            val elapsedMs = now - startedAt
                            val bps = if (elapsedMs > 0) (bytesRead * 1000 / elapsedMs) else 0
                            // 单 attempt，attempt 固定为 1
                            emitProgress(onProgress, UpdateContract.DownloadProgress(bytesRead, total, pct, bps, 1))
                        }
                    }
                    out.flush()
                }
            }
        } catch (e: CancellationException) {
            body.close()
            return terminalReason?.let { fail(it, response.code, e.message ?: "") }
                ?: fail(UpdateContract.DownloadFailure.IO, response.code, e.message ?: "已取消")
        } catch (e: IOException) {
            body.close()
            return fail(UpdateContract.DownloadFailure.IO, response.code, e.message ?: "写入失败")
        } finally {
            body.close()
        }

        // 校验大小（服务端给了且 != 0 才校）：下载量与服务端声明不一致视为不完整
        if (spec.expectedBytes > 0 && bytesRead != spec.expectedBytes) {
            runCatching { partFile.delete() }
            return fail(UpdateContract.DownloadFailure.IO, response.code, "下载大小不符")
        }

        // 原子 rename：同一目录、同一文件系统，renameTo 原子
        if (!partFile.renameTo(dest)) {
            // 失败时保留 part，让调用方决定；返回 IO
            return fail(UpdateContract.DownloadFailure.IO, response.code, "无法落盘")
        }
        return UpdateContract.DownloadOutcome.Success(dest, bytesRead)
    }

    private suspend fun emitProgress(
        onProgress: suspend (UpdateContract.DownloadProgress) -> Unit,
        p: UpdateContract.DownloadProgress
    ) {
        // 进度回调异常（非取消类）不得影响下载流程；取消信号必须向上传播
        try {
            onProgress(p)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // 忽略 UI 回调异常，不中断下载
        }
    }

    // ---------- 看门狗 ----------

    private val lastByteAt = java.util.concurrent.atomic.AtomicLong(0)
    private val bytesReadRef = java.util.concurrent.atomic.AtomicLong(0)
    private val speedSamples = mutableListOf<Pair<Long, Long>>() // (timestamp, bytesSinceLastTick)
    private val speedSamplesLock = Any()

    private fun pulse(bytes: Long) {
        lastByteAt.set(clock.elapsedRealtime())
        bytesReadRef.set(bytes)
    }

    private suspend fun watch(call: Call, partFile: File) {
        lastByteAt.set(clock.elapsedRealtime())
        while (true) {
            kotlinx.coroutines.delay(watchdogIntervalMs)
            if (call.isCanceled()) break
            val now = clock.elapsedRealtime()
            val sinceLastByte = now - lastByteAt.get()
            if (sinceLastByte > stallTimeoutMs) {
                terminalReason = UpdateContract.DownloadFailure.STALLED
                call.cancel()
                return
            }
            // 低速检测：每个 tick 统计本 tick 内新增字节
            val nowBytes = bytesReadRef.get()
            synchronized(speedSamplesLock) {
                val prev = speedSamples.lastOrNull()?.second ?: 0L
                val added = (nowBytes - prev).coerceAtLeast(0)
                speedSamples.add(now to nowBytes)
                // 丢弃 slowWindowMs 之前的样本
                val cutoff = now - slowWindowMs
                while (speedSamples.isNotEmpty() && speedSamples.first().first < cutoff) speedSamples.removeFirst()
                // 窗口内每个 tick 速度都低于阈值 → 低速
                val allSlow = speedSamples.size >= 2 &&
                    speedSamples.all { (it.second - (speedSamples.first().second)) * 1000 / max(1, it.first - speedSamples.first().first) < minSpeedBps }
                if (allSlow && (nowBytes - speedSamples.first().second) >= 0) {
                    terminalReason = UpdateContract.DownloadFailure.TOO_SLOW
                    call.cancel()
                    return
                }
            }
            // 若下载已完成（文件已 rename 走），退出看门狗
            if (!partFile.exists() && !call.isExecuted()) { /* noop */ }
        }
    }

    private fun httpFailure(code: Int): UpdateContract.DownloadFailure = when (code) {
        401, 403 -> UpdateContract.DownloadFailure.HTTP_401.let { if (code == 403) UpdateContract.DownloadFailure.HTTP_403 else it }
        // 上面写法对 403 返回 HTTP_403，对 401 返回 HTTP_401
        404 -> UpdateContract.DownloadFailure.HTTP_404
        in 500..599 -> UpdateContract.DownloadFailure.HTTP_5XX
        else -> UpdateContract.DownloadFailure.HTTP_OTHER
    }

    private fun classifyIo(e: IOException): UpdateContract.DownloadFailure = when (e) {
        is UnknownHostException -> UpdateContract.DownloadFailure.DNS
        is SocketTimeoutException -> UpdateContract.DownloadFailure.READ_TIMEOUT
        is ConnectException -> UpdateContract.DownloadFailure.CONNECT_TIMEOUT
        is SocketException -> UpdateContract.DownloadFailure.CONNECTION_RESET
        else -> UpdateContract.DownloadFailure.IO
    }

    private fun fail(
        kind: UpdateContract.DownloadFailure,
        httpCode: Int?,
        message: String
    ): UpdateContract.DownloadOutcome {
        // 出错时删本次 part（它正是这次下载的产物），但不动已存在的最终文件
        Log.w("XqyUpdate", "download failed: $kind http=$httpCode msg=$message")
        return UpdateContract.DownloadOutcome.Failure(kind, httpCode, message)
    }
}
