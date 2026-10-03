package com.xingqiyi.laundryphoto.update

/**
 * 「可选轨」与「强制轨」的合并规则（纯函数，不依赖任何 Android 类）。
 *
 * ## 这是 U-04 的验收口径，逐条对应 design.md §4.4 规则表
 *
 * 编排器把「这台服务器上有什么（SourceReport）」+「本地状态（跳过/推迟/版本）」交给我，
 * 我给出「这次该怎么提示用户」的结论。所有分支都在这里穷举，便于 `UpdatePlanResolverTest`
 * 用一张用例表覆盖——这是本项目「可测性优先」最典型的落点。
 *
 * ## 关键不变量
 *
 * - **永远以客户端本地版本比较为最终真相**：服务端 `hasUpdate` 只是参考，若本地比较
 *   显示不更新，一律 `UpToDate`（防「明明最新却一直提示」）。
 * - **强制更新只在「强推文件 == 最新可选文件」时成立**；管理员强推了一个比最新版旧的 apk，
 *   仍更新到最新版，但不强制（避免「点了不更新就会被逼着重装旧版」的怪象）。
 * - **强制覆盖跳过**：强推命中了用户已跳过的版本，跳过失效，必须更新。
 */
object UpdatePlanResolver {

    const val GRACE_WINDOW_MS = 24L * 60 * 60 * 1000
    const val MAX_POSTPONE = 3

    data class ResolveInput(
        val report: UpdateContract.SourceReport,
        val self: UpdateContract.SelfVersion,
        val skippedVersion: String,
        val postpone: UpdateContract.ForcePostpone,
        val nowMs: Long,
        /** 手动检查为 true：绕过节流与跳过命中（但仍上报 previouslySkipped，用于提示） */
        val manual: Boolean
    )

    sealed class Resolution {
        data class Offer(val plan: UpdateContract.UpdatePlan, val previouslySkipped: Boolean) : Resolution()
        data class Block(val plan: UpdateContract.UpdatePlan) : Resolution()
        data object UpToDate : Resolution()
        data object NoPackage : Resolution()
        data object Skipped : Resolution()
        data object NotConfigured : Resolution()
    }

    fun resolve(input: ResolveInput): Resolution {
        val (report, self) = input.run { this.report to this.self }
        val target = report.latestApk ?: return Resolution.NoPackage

        // 永远以本地版本比较为真相：服务端说有更新，但本地已经够新 → 不提示
        if (!VersionComparator.isNewer(target.versionName, self.comparableName)) {
            return Resolution.UpToDate
        }

        val force = report.forceTrack
        // 强推成立需**同时**满足：强推轨存在、文件确实还在、且强推的就是当前最新那个包。
        // 少判 fileExists 会造成「管理员强推了一个已被移走的 apk → 所有店员被永久卡在
        // 无法跳过的强推弹窗里，而那个文件根本下载不了」（现场无法自助恢复）。
        val mandatory = force != null && force.fileExists && force.fileName == target.fileName

        // 跳过命中（仅非强制、非手动）：自动检查静默，手动检查仍 offer 并标注 previouslySkipped
        val hitSkip = !mandatory && target.versionName == input.skippedVersion
        if (hitSkip && !input.manual) return Resolution.Skipped

        if (mandatory) {
            val expired = input.postpone.count >= MAX_POSTPONE ||
                input.nowMs > input.postpone.firstPromptAtMs + GRACE_WINDOW_MS
            val plan = buildPlan(target, mandatory, force?.version ?: "", remainingPostpone = MAX_POSTPONE - input.postpone.count)
            return if (expired) Resolution.Block(plan) else Resolution.Offer(plan, previouslySkipped = false)
        }

        // 非强制：命中跳过且在手动检查 → 告知用户「你之前跳过过」
        val previouslySkipped = hitSkip
        val plan = buildPlan(target, mandatory = false, forceVersion = "", remainingPostpone = 0)
        return Resolution.Offer(plan, previouslySkipped = previouslySkipped)
    }

    private fun buildPlan(
        target: UpdateContract.RemoteApk,
        mandatory: Boolean,
        forceVersion: String,
        remainingPostpone: Int
    ): UpdateContract.UpdatePlan {
        val now = System.currentTimeMillis()
        return UpdateContract.UpdatePlan(
            target = target,
            mandatory = mandatory,
            origin = UpdateContract.UpdateOrigin.NONE,
            discoveredAtMs = now,
            forceVersion = forceVersion,
            remainingPostpone = remainingPostpone.coerceAtLeast(0),
            graceEndsAtMs = now + GRACE_WINDOW_MS
        )
    }
}
