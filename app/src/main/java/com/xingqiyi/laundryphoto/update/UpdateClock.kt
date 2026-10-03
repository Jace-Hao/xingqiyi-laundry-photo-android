package com.xingqiyi.laundryphoto.update

import android.os.SystemClock
import java.util.concurrent.TimeUnit

/**
 * 真实时钟实现（生产用）。
 *
 * 同时提供两类时间，且**不能混用**（design.md §10.4 共享约定 13 / 踩坑记录）：
 * - [elapsedRealtime]：单调时钟，用于「断流 / 低速」的速度计算，不受用户改系统时间影响；
 *   若用 `currentTimeMillis` 算速度，店员改了手机时间会把「正常下载」误判成「卡死」。
 * - [currentTimeMillis]：墙上时间，用于持久化时间戳（节流、宽限、日志纪元）。
 */
object RealClock : UpdateContract.Clock {
    override fun elapsedRealtime(): Long = SystemClock.elapsedRealtime()
    override fun currentTimeMillis(): Long = System.currentTimeMillis()
}

/** 退避序列与抖动的纯计算（便于单测断言，不含随机源）。 */
object RetryBackoff {
    /** 退避基准序列（秒）：2s → 6s → 15s，对应 D11 / 共享约定 11。 */
    private val BASE_SECONDS = longArrayOf(2, 6, 15)

    /** 第 `attempt` 次失败后的等待毫秒（attempt 从 1 起）。超过上限回退到最后一档。 */
    fun delayMs(attempt: Int, jitter: Double = 1.0): Long {
        val idx = (attempt - 1).coerceIn(0, BASE_SECONDS.lastIndex)
        val base = TimeUnit.SECONDS.toMillis(BASE_SECONDS[idx])
        // jitter ∈ [0.8, 1.2]：±20% 防重试风暴；调用方传入随机因子
        return (base * jitter).toLong().coerceAtLeast(500L)
    }
}
