# 后端技术评审 vP4：发布流水线

> 版本 vP4，一旦确定尽量不要轻易改动。

## 1. 变更点

- 无 Java 源码变更。
- `docker-compose.yml` backend/frontend 增加：

```yaml
image: ${IMAGE_BACKEND:-aicasetest-backend}:${IMAGE_TAG:-local}
pull_policy: ${PULL_POLICY:-missing}
```

本地 `docker compose up --build` 行为不变；`deploy.ps1` 设置 `IMAGE_BACKEND/IMAGE_FRONTEND/IMAGE_TAG/PULL_POLICY=always` 后从 GHCR 拉取。

## 2. 文件变更清单

| 文件 | 变更 |
|---|---|
| docker-compose.yml | image/pull_policy 变量化 |
| .github/workflows/publish.yml | 新增 GHCR 推送 |
| scripts/deploy.ps1 | 新增多环境部署 |
| scripts/rollback.ps1 | 新增应用回滚 |
| scripts/mysql-restore.ps1 | 新增数据库恢复 |
| scripts/flyway-staging-drill.ps1 | 新增 Flyway 演练 |
| deploy/README.md | 新增部署说明 |

## 3. API 契约变化

无。

## 4. 向后兼容性

- compose 默认 tag `local`，未设置环境变量时完全兼容原流程。
- 脚本不修改既有 `.env`，只读取 `.env.<env>`。

## 5. 测试验证方案

- `docker compose config`。
- `mvn compile` 回归。
- PowerShell 脚本语法解析检查。
