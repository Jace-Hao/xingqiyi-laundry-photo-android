# CI 工作流（待激活）

本目录存放 GitHub Actions 工作流文件的**源稿**。它们放在这里而不是
`.github/workflows/`，是因为推送工作流文件需要 PAT 具备 `workflow` 作用域，
当前发布用的令牌只有 `repo` 作用域，GitHub 会直接拒绝：

```
! [remote rejected] main -> main
  (refusing to allow a Personal Access Token to create or update workflow
   `.github/workflows/android-release.yml` without `workflow` scope)
```

## 激活方式（任选其一）

**A. 给令牌加 `workflow` 作用域**（推荐，一次到位）

1. GitHub → Settings → Developer settings → Personal access tokens → 选该令牌
2. 勾选 `workflow`，保存
3. 把文件放到正式位置并推送：

   ```bash
   mkdir -p .github/workflows
   git mv docs/ci/android-release.yml .github/workflows/android-release.yml
   git commit -m "ci: 启用移动端发布工作流"
   git push origin main
   ```

**B. 在网页端直接新建**

打开仓库的 Actions 页 → New workflow → set up a workflow yourself，
把 `android-release.yml` 的内容粘进去（网页端创建不受令牌作用域限制）。

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
