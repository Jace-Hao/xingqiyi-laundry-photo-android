package com.xingqiyi.laundryphoto.update

/**
 * 「复制错误信息」文本组装（纯函数）。
 *
 * ## 现场排障只需要三件事（U-15 / NFR-L4）
 *
 * 1. **错误类型**：当前处于哪个状态（下载/校验/安装失败）；
 * 2. **HTTP 状态**（若有）：下载失败时的状态码，便于判断是「连码失效」还是「文件没了」；
 * 3. **日志末尾 20 行**：时间线 + 关键节点，技术同事一眼能看出卡在哪一步。
 *
 * 不含连接码/会话令牌：日志行经 `UpdateLogger.redact` 已脱敏，这里只做拼接。
 */
object UpdateDiagnostics {

    fun build(
        state: UpdateContract.UpdateState,
        self: UpdateContract.SelfVersion,
        serverHost: String,
        logLines: List<String>
    ): String {
        val sb = StringBuilder()
        sb.appendLine("【星期衣更新诊断】")
        sb.appendLine("本机版本：v${self.comparableName}（code ${self.versionCode}）")
        sb.appendLine("服务器：${if (serverHost.isBlank()) "（未配置）" else serverHost}")
        sb.appendLine("当前状态：${stateName(state)}")
        if (state is UpdateContract.UpdateState.Failed) {
            sb.appendLine("失败阶段：${state.stage}")
            sb.appendLine("失败文案资源：@string/${state.copy.stringRes}")
            if (state.copy.formatArgs.isNotEmpty()) {
                sb.appendLine("文案参数：${state.copy.formatArgs.joinToString(", ")}")
            }
            sb.appendLine("可重试：${state.copy.retryable}")
        }
        sb.appendLine("—— 最近日志（${logLines.size} 行）——")
        logLines.forEach { sb.appendLine(it) }
        return sb.toString().trimEnd()
    }

    private fun stateName(state: UpdateContract.UpdateState): String = when (state) {
        is UpdateContract.UpdateState.Idle -> "空闲"
        is UpdateContract.UpdateState.Checking -> "检查中"
        is UpdateContract.UpdateState.UpdateAvailable -> "有可用更新"
        is UpdateContract.UpdateState.Blocked -> "强制阻断"
        is UpdateContract.UpdateState.Downloading -> "下载中"
        is UpdateContract.UpdateState.Verifying -> "校验中"
        is UpdateContract.UpdateState.ReadyToInstall -> "待安装"
        is UpdateContract.UpdateState.Installing -> "安装中"
        is UpdateContract.UpdateState.Failed -> "失败"
    }
}
