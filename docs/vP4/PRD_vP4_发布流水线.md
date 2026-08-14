# vP4 PRD：发布流水线

> 版本 vP4，一旦确定尽量不要轻易改动。迭代范围：GHCR 镜像推送、多环境部署、Flyway staging 演练、回滚。

## 1. 迭代背景与痛点

- 镜像只在 CI 构建校验，未推送到统一仓库，无法按 tag 部署。
- 缺少 dev/staging/prod 多环境部署入口与版本化镜像约定。
- Flyway 迁移只随后端启动执行，缺少发布前独立演练。
- 回滚只有手工步骤，没有脚本化入口。

## 2. 范围（In / Out of scope）

### In scope

- GitHub Actions `publish.yml`：tag 推送/手动触发，构建并推送 backend/frontend 到 GHCR。
- compose 支持 `IMAGE_BACKEND/IMAGE_FRONTEND/IMAGE_TAG/PULL_POLICY`，本地构建与 GHCR 部署可切换。
- `deploy.ps1`：按环境（dev/staging/prod）读取 `.env.<env>` 并从 GHCR 部署。
- `rollback.ps1` + `mysql-restore.ps1`：应用镜像回滚与数据库恢复。
- `flyway-staging-drill.ps1`：临时库完整执行迁移并校验 schema history。

### Out of scope

- 具体服务器 SSH/ArgoCD/K8s 编排。
- 数据库结构自动降级回滚（Flyway 无反向迁移，依赖备份恢复）。

## 3. 功能详情

### 3.1 GHCR 镜像推送

- 推送 `v*` tag 或手动触发时构建 `aicasetest-backend` / `aicasetest-frontend`。
- 镜像 tag：`<release-tag>` 与 `latest`，命名空间转为小写。

### 3.2 多环境部署

- `deploy.ps1 -Environment staging -Tag vP4`。
- 要求仓库根存在 `.env` 与 `.env.<env>`，后者可覆盖端口/密码/镜像 tag。
- 部署前先 `docker compose config --quiet` 校验。

### 3.3 Flyway staging 演练

- `flyway-staging-drill.ps1` 创建临时库 `aicasetest_flyway_drill`。
- 使用 `flyway/flyway` 镜像执行 `backend/src/main/resources/db/migration/mysql` 全部迁移。
- 校验 `flyway_schema_history` 非空后删除临时库。

### 3.4 回滚

- `rollback.ps1` 用上一个镜像 tag 重新部署。
- `mysql-restore.ps1` 从备份恢复 MySQL 数据，配合应用回滚。

## 4. 验收标准

1. compose 支持镜像变量，`docker compose config` 通过。
2. publish workflow 语法正确，推 tag 可触发 GHCR 推送。
3. PowerShell 脚本可解析（不要求本次实际部署）。
4. `npm run build` 与 `mvn compile` 回归通过。
5. Flyway 演练脚本逻辑完整：建临时库 → migrate → 校验 → 清理。

## 5. 风险与规避

| 风险 | 规避 |
|---|---|
| GHCR 权限不足 | workflow 使用 GITHUB_TOKEN + packages: write |
| 部署脚本误用生产环境 | ValidateSet 限制环境名，且需要显式环境文件 |
| 回滚期间数据库结构不匹配 | 先恢复备份再回滚应用，文档明示顺序 |
| Flyway 镜像拉取慢 | 使用国内 Docker registry mirror 或预拉取镜像 |

## 6. 交付物清单

- PRD / 后端评审 / 前端评审
- publish.yml、deploy/rollback/restore/flyway 脚本
- compose 镜像变量与 deploy README
