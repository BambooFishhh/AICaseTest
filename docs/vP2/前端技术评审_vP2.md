# 前端技术评审 vP2：高可用与容灾

> 版本 vP2，一旦确定尽量不要轻易改动。

## 1. 变更点

- 前端 `src/` 无源码变更。
- `docker-compose.yml` 为所有服务增加 `deploy.resources.limits` 与 `stop_grace_period`。
- 新增 `scripts/mysql-backup.ps1`、`scripts/restore-drill.ps1`、`scripts/schedule-backup.ps1`。

## 2. props/emit 变化

无。

## 3. 数据流

无变化。备份/恢复脚本仅与 MySQL 容器交互，不经过应用 API。

## 4. 向后兼容性

- 资源限制仅影响 Docker 运行，不影响本地 `npm run dev`。
- 备份脚本默认读取 compose 容器名，独立部署时可传参覆盖。

## 5. 测试验证方案

- `npm run build` 回归。
- `docker compose config` 校验 deploy 配置语法。
