package com.xingqiyi.laundryphoto.data.model

/**
 * 服务端数据模型。
 *
 * 字段名与桌面端 store.js 返回的 JSON 键名严格一致，Gson 直接按字段名映射，
 * 因此不需要 @SerializedName。所有字段都给默认值：服务端偶发缺字段时
 * 反序列化不会得到 null，界面不会出现 NPE（与桌面端的宽松解析风格保持一致）。
 */

/** 统一响应信封：服务端无论成功失败都返回 HTTP 200，业务成败看 ok */
data class ApiEnvelope<T>(
    val ok: Boolean = false,
    val data: T? = null,
    val message: String? = null,
    /** 唯一登录被顶下线标记：true 时必须强制退回登录页 */
    val revoked: Boolean? = null
)

/** 分页结果 */
data class Paged<T>(
    val items: List<T> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    val pageSize: Int = 20
)

data class LoginResult(
    val user: UserDto = UserDto(),
    val sessionToken: String = ""
)

data class PermissionsDto(
    val capture: Boolean = false,
    val query: Boolean = true
)

data class UserDto(
    val id: String = "",
    val username: String = "",
    val name: String = "",
    val role: String = Role.QUERY.key,
    val store: String = "",
    val active: Boolean = true,
    val createdAt: String = "",
    val lastLoginAt: String? = null,
    val createdBy: String = "",
    val roleLabel: String = "",
    val permissions: PermissionsDto? = null
) {
    /** 角色枚举视图；界面一律用它判断权限，避免散落的字符串比较 */
    val roleEnum: Role get() = Role.of(role)
    /** 显示名：姓名为空时回退用户名（与桌面端 user-chip 一致） */
    val displayName: String get() = name.ifBlank { username }
}

/**
 * 衣物照片存档。
 * photoUrl/thumbUrl 是桌面端协议地址（xqy-photo://…），移动端不可用，
 * 统一改用 [photoFile] 自行拼 HTTP 地址（见 util/PhotoUrl.kt）。
 */
data class RecordDto(
    val id: String = "",
    val barcode: String = "",
    /** 同一条码下的第几张 */
    val seq: Int = 0,
    val userId: String = "",
    val username: String = "",
    val storeName: String = "",
    val note: String = "",
    /** 相对照片根目录的路径，形如「条码目录/202601011230.jpg」 */
    val photoFile: String = "",
    val createdAt: String = "",
    /** 离线暂存未同步标记（本机离线队列中的记录） */
    val pendingSync: Boolean = false
) {
    /** 是否本人存档：删除/改码按钮的可见性判断 */
    fun isOwn(userIdNow: String): Boolean = userId == userIdNow
}

data class LogEntryDto(
    val id: String = "",
    val time: String = "",
    val ip: String = "",
    val userId: String = "",
    val username: String = "",
    val role: String = "",
    val store: String = "",
    val module: String = "",
    val action: String = "",
    val detail: String = "",
    val result: String = ""
)

data class OverviewDto(
    val userCount: Int = 0,
    val activeUserCount: Int = 0,
    val adminCount: Int = 0,
    val roleCount: Map<String, Int> = emptyMap(),
    val recordCount: Int = 0,
    val todayRecordCount: Int = 0,
    val logCount: Int = 0,
    val recentRecords: List<RecordDto> = emptyList(),
    val recentLogs: List<LogEntryDto> = emptyList()
)

data class SystemInfoDto(
    val mode: String = "",
    val port: Int = 0,
    val token: String = "",
    val photoDir: String = "",
    val serverUrl: String = "",
    val updateSourceUrl: String = "",
    val remoteClient: Boolean = false,
    /** 服务端标记：系统设置为本机专用，移动端只读 */
    val localOnly: Boolean = true
)

/**
 * 服务端能力集：移动端据此降级；老服务端没有该接口时按最低能力集处理。
 *
 * ## 为什么这里全部是可空类型
 *
 * 现场实测踩过的坑：用户桌面端是旧构建，`/ping` 只返回
 * `{"ok":true,"data":{"app":"xingqiyi"}}`，**没有** `apiVersion` /
 * `serverVersion` / `features`。
 *
 * Gson 反序列化**不走 Kotlin 主构造函数，也不会执行这里的默认值兜底**——
 * 它用 Unsafe 直接分配对象并只填充 JSON 里存在的 key，缺失字段一律为 `null`。
 * 所以若把 `serverVersion` 声明成非空 `String`，编译器不报错（默认值给了 ""），
 * 运行时却拿到 `null`，调用方一句 `.isBlank()` 就是 NPE，
 * 最终被翻译成一句「对方返回的不是本系统的数据」，把新旧版本不兼容
 * 伪装成了「你地址填错了」，用户白查防火墙和 IP。
 *
 * 因此：**凡是要接收服务端可能缺字段的 DTO，字段一律可空**，
 * 对外语义通过下面的计算属性兜底，调用方拿到的始终是非空值。
 */
data class CapabilitiesDto(
    val app: String? = null,
    val apiVersion: Int? = null,
    val serverVersion: String? = null,
    val features: FeaturesDto? = null
) {
    /** 兜底后的服务标识，调用方无需判空 */
    val appName: String get() = app.orEmpty()

    /** 兜底后的服务端版本号，老服务端缺字段时为空串 */
    val serverVersionText: String get() = serverVersion.orEmpty()

    /**
     * 能力集 API 版本。老服务端无此字段时按 1 处理（仅基础能力）。
     * 配套能力（原始上传、改备注）依赖 apiVersion >= 2。
     */
    val apiVersionValue: Int get() = apiVersion ?: 1

    /** 服务端是否为支持增量接口（apiVersion >= 2）的新版 */
    val supportsMobileAddons: Boolean get() = apiVersionValue >= MIN_API_VERSION

    val uploadRaw: Boolean get() = features?.uploadRaw == true
    val setNote: Boolean get() = features?.setNote == true
    val thumb: Boolean get() = features?.thumb == true
    val photoTokenQuery: Boolean get() = features?.photoTokenQuery == true

    companion object {
        /**
         * 首个支持增量接口（原始上传、改备注）的协议版本号。
         * 低于此值（含缺失）即视为旧版服务端：原始上传与「改备注」不可用，走 base64 兼容通道。
         */
        const val MIN_API_VERSION = 2

        /**
         * 建议用户升级到的**桌面端**版本号，供升级指引文案使用。
         *
         * 注意这是桌面端仓库（xingqiyi-laundry-photo）的版本号，不是本仓库的版本号。
         * 桌面端版本线为 1.2.x：v1.2.2 之前的版本不带能力集接口，
         * 首批带该接口的版本原以 1.3.0 发布、后修正为 **v1.2.3**（见仓库拆分说明）。
         * 因此这里必须是 1.2.3 —— 若仍写 1.3.0，桌面端报上来的 serverVersion 永远
         * 小于它，用户会被无限提示「请升级到一个并不存在的版本」。
         */
        const val MIN_SERVER_VERSION = "1.2.3"
    }
}

data class FeaturesDto(
    val uploadRaw: Boolean = false,
    val setNote: Boolean = false,
    val thumb: Boolean = false,
    val photoTokenQuery: Boolean = false
)

data class UploadResult(
    val photoFile: String = ""
)

data class DeleteBatchResult(
    val deleted: Int = 0,
    val skipped: Int = 0
)

data class RenameBatchResult(
    val renamed: Int = 0,
    val denied: Int = 0,
    val newBarcode: String = "",
    val records: List<RecordDto> = emptyList()
)

data class ForceUpdateDto(
    /** 是否开启了强制推送。注意：Gson 缺失字段一律填 null，非空类型挡不住，这里必须可空 */
    val enabled: Boolean? = null,
    val version: String? = "",
    val fileName: String? = "",
    val at: String? = "",
    val by: String? = "",
    /**
     * 推送文件是否还存在于更新文件夹。服务端在文件被移走时会置 false，
     * 客户端据此避免「下载一个已经不存在的安装包」。
     */
    val fileExists: Boolean? = null,
    /**
     * 更新文件夹里的全部安装包清单（桌面端返回 .exe/.zip/.msi，新版本含 .apk）。
     * 用 List 而非数组：Gson 缺失时不填 null，配合 below 兜底返回空列表。
     */
    val files: List<UpdateFileEntryDto>? = null
) {
    /** 兜底后的「是否开启」：缺字段按 false 处理，避免 NPE */
    val enabledValue: Boolean get() = enabled == true
    /** 兜底后的文件名：缺字段按空串 */
    val fileNameText: String get() = fileName.orEmpty()
    /** 兜底后的 version */
    val versionText: String get() = version.orEmpty()
    /**
     * 兜底后的「推送文件是否还在」：缺字段按 false。
     * 老服务端没有该字段，此时移动端会因为拿不到 true 而判定强推无效——这是安全的一侧。
     */
    val fileExistsValue: Boolean get() = fileExists == true
    /** 兜底后的安装包清单：缺字段按空列表（配套 fileList.filter 只在非空时用） */
    val fileList: List<UpdateFileEntryDto> get() = files.orEmpty()
}

/** 更新文件夹里的安装包条目（forceUpdate.files / checkMobileUpdate 共用） */
data class UpdateFileEntryDto(
    val name: String? = "",
    val size: Long? = 0,
    val version: String? = ""
) {
    val nameText: String get() = name.orEmpty()
    val versionText: String get() = version.orEmpty()
}

/**
 * 桌面端旧接口的更新信息（POST api/system/checkUpdate）。
 *
 * 注意：服务端实际返回的是 `latestFile`（更新文件夹里版本号最高的文件名，**不一定是 APK**），
 * 而不是 `url` / `notes`。旧接口只用于「新接口不可用时的降级」，移动端必须自己过滤 `latestFile`
 * 是否以 .apk 结尾，且以本地版本比较为唯一真相。
 */
data class UpdateInfoDto(
    val currentVersion: String? = "",
    val latestVersion: String? = "",
    val latestFile: String? = "",
    val hasUpdate: Boolean? = null
) {
    /** 兜底后的「是否有更新」：缺字段按 false */
    val hasUpdateValue: Boolean get() = hasUpdate == true
    /** 兜底后的最新文件名（可能为 .exe，需要调用方自行过滤扩展名） */
    val latestFileText: String get() = latestFile.orEmpty()
}

/**
 * 移动端专用更新查询（POST api/system/checkMobileUpdate，桌面端 v1.2.4 起）。
 *
 * 与 `UpdateInfoDto` 不同，这里只扫 .apk，并把安装包的关键元信息（大小、sha256、说明）一并下发。
 * 所有字段可空 + 兜底：服务端缺字段（老版本、文件异常）时绝不能 NPE——
 * 本项目在 R8 剥离 DTO 事故上的教训是「Gson 不参与 Kotlin 主构造、缺字段即 null」。
 */
data class MobileUpdateInfoDto(
    /** 服务端是否支持新接口。值为 false 时调用方应静默降级到老接口 */
    val supported: Boolean? = null,
    /** 更新文件夹里是否真的有可用的手机版 APK */
    val hasPackage: Boolean? = null,
    /** 候选 APK 文件名，如 xingqiyi-laundry-photo-android-1.1.0.apk；无包时为空串 */
    val fileName: String? = "",
    /** APK 版本号（取文件名里的 \d+\.\d+\.\d+） */
    val version: String? = "",
    /** 文件字节数；取不到为 0，客户端遇到 0 跳过大小校验 */
    val size: Long? = 0,
    /** 64 位小写 hex；服务端算不出来为空串，客户端据此跳过哈希校验（绝不阻断） */
    val sha256: String? = "",
    /** 更新说明：同名 .md → 同名 .json 的 notes 字段 → 空串（三级降级） */
    val notes: String? = "",
    /** 说明来源：md / json / 空串，仅用于日志 */
    val notesSource: String? = "",
    /** 是否比客户端上报的当前版本更新（以服务端比较为准，但客户端仍以本地比较为最终真相） */
    val hasUpdate: Boolean? = null,
    /** 回显的客户端当前版本，便于日志与排障 */
    val currentVersion: String? = ""
) {
    /** 兜底：服务端是否支持新接口。缺字段按 false——老服务端要静默降级，不能误判为支持 */
    val isSupported: Boolean get() = supported == true
    /** 兜底：是否有可用包 */
    val hasPackageValue: Boolean get() = hasPackage == true
    /** 兜底：文件名（空串表示无包） */
    val fileNameText: String get() = fileName.orEmpty()
    /** 兜底：版本号 */
    val versionText: String get() = version.orEmpty()
    /**
     * 兜底：文件大小，缺字段按 0（客户端跳过大小校验）。
     * 负数同样夹到 0：服务端 stat 失败时可能给 -1，夹住之后校验层 `sizeBytes > 0`
     * 的判断才会正确地「跳过」而不是拿 -1 去比长度。
     */
    val sizeValue: Long get() = (size ?: 0L).coerceAtLeast(0L)
    /** 兜底：sha256，缺字段按空串（客户端跳过哈希校验） */
    val sha256Text: String get() = sha256.orEmpty()
    /** 兜底：更新说明 */
    val notesText: String get() = notes.orEmpty()
    /** 兜底：说明来源 */
    val notesSourceText: String get() = notesSource.orEmpty()
    /** 兜底：是否有更新 */
    val hasUpdateValue: Boolean get() = hasUpdate == true
    /**
     * 只认 `.apk`：服务端更新文件夹里桌面端 `.exe` 与手机版 `.apk` 共存，
     * 客户端必须自己再挡一次（决策 D1「客户端同时做防御」）。
     */
    val isApkFile: Boolean get() = fileNameText.endsWith(".apk", ignoreCase = true)
}

/** 会话快照：登录成功后持久化，冷启动免登录 */
data class SessionSnapshot(
    val token: String = "",
    val user: UserDto = UserDto()
)
