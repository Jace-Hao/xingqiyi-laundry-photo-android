# CI 工作流

工作流源文件在 `.github/workflows/android-release.yml`（本目录只留说明文档）。

> 历史备注：推送工作流文件需要 PAT 具备 `workflow` 作用域。若你的令牌只有 `repo`
> 作用域，GitHub 会直接拒绝推送：
>
> ```
> ! [remote rejected] main -> main
>   (refusing to allow a Personal Access Token to create or update workflow
>    `.github/workflows/android-release.yml` without `workflow` scope)
> ```
>
> 解决办法二选一：给令牌补勾 `workflow`，或在仓库 Actions 页用网页端新建（网页端不受令牌作用域限制）。

## 工作流说明

`android-release.yml` —— 移动端发布链路，**只作用于本仓库**：

- 触发：push `v*.*.*` tag，或手动 `workflow_dispatch`
- 先跑 `testDebugUnitTest`，失败即止
- 从 `app/build.gradle.kts` 读取 `versionName`，校验其与 tag 一致
  （不一致直接报错，避免 tag 与包内版本对不上）
- 构建 release / debug 两个 APK，以纯英文名整理附件 + 生成 SHA256SUMS
- 上传到**本仓库**的 Release（草稿状态，人工确认后发布）

桌面端有自己仓库里的同构工作流，两端互不干涉。

## 需要的仓库密钥

| Secret | 用途 |
|---|---|
| `RELEASE_STORE_FILE_B64` | 签名密钥库的 base64（可选，未配则退回调试签名） |
| `RELEASE_STORE_PASSWORD` | 密钥库口令 |
| `RELEASE_KEY_ALIAS` | 签名别名 |
| `RELEASE_KEY_PASSWORD` | 别名口令 |

`GITHUB_TOKEN` 由 Actions 自动注入，无需配置。
