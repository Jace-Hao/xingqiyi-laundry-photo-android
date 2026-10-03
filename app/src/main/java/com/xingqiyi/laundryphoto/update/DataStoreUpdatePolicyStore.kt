package com.xingqiyi.laundryphoto.update

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 更新策略持久化（实现层，独立 DataStore `xqy_update`）。
 *
 * ## 为什么独立 DataStore、且用 17 个扁平 key（S2 取舍）
 *
 * 冷启动恢复需要跨进程存活的「更新快照」，但**刻意不用 JSON**：
 * 一旦把快照塞进一个被 Gson 反序列化的对象，就得多加一个 `-keep` 包，
 * 而本项目刚在 R8 剥离 DTO 的事故上栽过（见 `ProguardKeepRuleTest` 的事故说明）。
 * 用 DataStore Preferences 的扁平 key，字段名即 store key，混淆动不到它，
 * 既守住 release 不崩，又让「手机关机/进程被杀后还能恢复」成立。
 */
class DataStoreUpdatePolicyStore(
    private val context: Context
) : UpdateContract.UpdatePolicyStore {

    private val store: DataStore<Preferences> = context.updateStore

    // ---- 节流（按服务器 host 分键；换服务器互不干扰，S3 取舍） ----
    private fun lastCheckKey(host: String) = longPreferencesKey("last_check_at__$host")

    override suspend fun lastCheckAt(serverHost: String): Long =
        store.data.first()[lastCheckKey(serverHost)] ?: 0L

    override suspend fun markChecked(serverHost: String, atMs: Long) {
        store.edit { it[lastCheckKey(serverHost)] = atMs }
    }

    // ---- 跳过此版本 ----
    private val skippedKey = stringPreferencesKey("skipped_version")

    override fun skippedVersion(): Flow<String> =
        store.data.map { it[skippedKey].orEmpty() }

    override suspend fun setSkippedVersion(version: String?) {
        store.edit { if (version.isNullOrBlank()) it.remove(skippedKey) else it[skippedKey] = version }
    }

    // ---- 强制更新宽限 ----
    private val postponeVersionKey = stringPreferencesKey("postpone_version")
    private val postponeCountKey = intPreferencesKey("postpone_count")
    private val postponeFirstKey = longPreferencesKey("postpone_first")

    override suspend fun postpone(): UpdateContract.ForcePostpone {
        val p = store.data.first()
        return UpdateContract.ForcePostpone(
            forceVersion = p[postponeVersionKey].orEmpty(),
            count = p[postponeCountKey] ?: 0,
            firstPromptAtMs = p[postponeFirstKey] ?: 0L
        )
    }

    override suspend fun bumpPostpone(forceVersion: String, nowMs: Long): UpdateContract.ForcePostpone {
        store.edit { prefs ->
            val prevVersion = prefs[postponeVersionKey].orEmpty()
            val prevFirst = prefs[postponeFirstKey] ?: 0L
            val prevCount = prefs[postponeCountKey] ?: 0
            // 强推版本变了（或首次）→ 重置计数与起始时间
            if (prevVersion != forceVersion || prevFirst == 0L) {
                prefs[postponeVersionKey] = forceVersion
                prefs[postponeFirstKey] = nowMs
                prefs[postponeCountKey] = 1
            } else {
                prefs[postponeCountKey] = prevCount + 1
            }
        }
        return postpone()
    }

    override suspend fun clearPostpone() {
        store.edit {
            it.remove(postponeVersionKey)
            it.remove(postponeCountKey)
            it.remove(postponeFirstKey)
        }
    }

    // ---- 冷启动恢复快照（11 个扁平 key） ----
    private val snapPhaseKey = stringPreferencesKey("snap_phase")
    private val snapFileKey = stringPreferencesKey("snap_file")
    private val snapVersionKey = stringPreferencesKey("snap_version")
    private val snapSizeKey = longPreferencesKey("snap_size")
    private val snapShaKey = stringPreferencesKey("snap_sha")
    private val snapNotesKey = stringPreferencesKey("snap_notes")
    private val snapNotesSrcKey = stringPreferencesKey("snap_notes_src")
    private val snapMandatoryKey = booleanPreferencesKey("snap_mandatory")
    private val snapForceKey = stringPreferencesKey("snap_force")
    private val snapStartedKey = longPreferencesKey("snap_started")
    private val snapPreCodeKey = intPreferencesKey("snap_pre_code")

    override suspend fun pendingSnapshot(): UpdateContract.PendingUpdateSnapshot? {
        val p = store.data.first()
        val phaseName = p[snapPhaseKey] ?: return null
        val phase = runCatching { UpdateContract.UpdateStage.valueOf(phaseName) }.getOrNull() ?: return null
        return UpdateContract.PendingUpdateSnapshot(
            phase = phase,
            fileName = p[snapFileKey].orEmpty(),
            version = p[snapVersionKey].orEmpty(),
            sizeBytes = p[snapSizeKey] ?: 0L,
            sha256 = p[snapShaKey].orEmpty(),
            notes = p[snapNotesKey].orEmpty(),
            notesSource = p[snapNotesSrcKey].orEmpty(),
            mandatory = p[snapMandatoryKey] ?: false,
            forceVersion = p[snapForceKey].orEmpty(),
            startedAtMs = p[snapStartedKey] ?: 0L,
            preUpdateVersionCode = p[snapPreCodeKey] ?: 0
        )
    }

    override suspend fun savePendingSnapshot(s: UpdateContract.PendingUpdateSnapshot?) {
        store.edit { prefs ->
            if (s == null) {
                prefs.remove(snapPhaseKey)
                prefs.remove(snapFileKey)
                prefs.remove(snapVersionKey)
                prefs.remove(snapSizeKey)
                prefs.remove(snapShaKey)
                prefs.remove(snapNotesKey)
                prefs.remove(snapNotesSrcKey)
                prefs.remove(snapMandatoryKey)
                prefs.remove(snapForceKey)
                prefs.remove(snapStartedKey)
                prefs.remove(snapPreCodeKey)
            } else {
                prefs[snapPhaseKey] = s.phase.name
                prefs[snapFileKey] = s.fileName
                prefs[snapVersionKey] = s.version
                prefs[snapSizeKey] = s.sizeBytes
                prefs[snapShaKey] = s.sha256
                prefs[snapNotesKey] = s.notes
                prefs[snapNotesSrcKey] = s.notesSource
                prefs[snapMandatoryKey] = s.mandatory
                prefs[snapForceKey] = s.forceVersion
                prefs[snapStartedKey] = s.startedAtMs
                prefs[snapPreCodeKey] = s.preUpdateVersionCode
            }
        }
    }

    override suspend fun setSnapshotPhase(phase: UpdateContract.UpdateStage) {
        store.edit { it[snapPhaseKey] = phase.name }
    }

    // ---- 安装后 30 分钟保护窗 ----
    private val protectUntilKey = longPreferencesKey("protect_until")

    override suspend fun protectUntilMs(): Long = store.data.first()[protectUntilKey] ?: 0L

    override suspend fun setProtectUntil(ms: Long) {
        store.edit { it[protectUntilKey] = ms }
    }
}

private val Context.updateStore by preferencesDataStore(name = "xqy_update")
