package com.xingqiyi.laundryphoto.ui.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xingqiyi.laundryphoto.update.UpdateContract
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 更新界面桥接 ViewModel。
 *
 * 编排器（[UpdateContract.UpdateCoordinator]）是唯一真源，本类只把它的 `StateFlow` / `SharedFlow`
 * 透传给 Compose，并把 UI 事件转发给编排器——**不持有任何更新状态**，避免与编排器出现第二份真相。
 * 浮层与设置卡片共用同一个编排器单例（在 [com.xingqiyi.laundryphoto.di.AppContainer] 装配）。
 */
class UpdateViewModel(private val coordinator: UpdateContract.UpdateCoordinator) : ViewModel() {

    /** 状态机当前态（UI 只读）。 */
    val state: StateFlow<UpdateContract.UpdateState> = coordinator.state

    /** 下载进度（非下载态为 null）。 */
    val progress: StateFlow<UpdateContract.DownloadProgress?> = coordinator.progress

    /** 轻提示（手动检查结果、安装成功等）。 */
    val toasts: SharedFlow<String> = coordinator.toasts

    /** 进入页面后由 MainActivity 在用户登录时调用，触发自动检查与冷启动恢复。 */
    fun coldStart(loggedIn: Boolean) = viewModelScope.launch { coordinator.onColdStart(loggedIn) }

    fun checkManual() = viewModelScope.launch { coordinator.check(UpdateContract.CheckTrigger.MANUAL_SETTINGS) }
    fun startDownload() = coordinator.startDownload()
    fun cancelDownload() = coordinator.cancelDownload()
    fun skipVersion() = coordinator.skipVersion()
    fun postpone() = coordinator.postpone()
    fun confirmInstall() = coordinator.confirmInstall()
    fun retry() = coordinator.retry()
    fun dismissFailure() = coordinator.dismissFailure()

    /** 清理更新缓存，返回释放的字节数。 */
    fun clearCache(): Long = coordinator.clearCacheBytes()

    /** 复制错误信息文本（排障用）。 */
    fun copyDiagnostics(): String = coordinator.copyDiagnostics()
}
