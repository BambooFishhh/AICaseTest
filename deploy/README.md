# vP4 多环境部署

## 环境文件

从 `.env.example` 复制：

```powershell
Copy-Item .env.example .env.dev
Copy-Item .env.example .env.staging
Copy-Item .env.example .env.prod
```

每个环境文件可覆盖 `IMAGE_TAG`、`PULL_POLICY`、端口与密码等变量。

## 部署

```powershell
.\scripts\deploy.ps1 -Environment staging -Tag vP3
```

脚本会从 `ghcr.io/<namespace>/aicasetest-backend|frontend:<tag>` 拉取镜像并启动 compose。

## 回滚

```powershell
.\scripts\rollback.ps1 -Environment staging -PreviousTag vP2
```

数据库结构回滚需按运维手册恢复备份：

```powershell
.\scripts\mysql-restore.ps1 -Password "<密码>" -BackupFile .\backups\mysql\aicasetest_xxxx.sql
```

## Flyway staging 演练

```powershell
.\scripts\flyway-staging-drill.ps1 -Password "<密码>" -MysqlPort 3308
```

脚本会在临时库执行全部迁移并校验 `flyway_schema_history`，结束后删除临时库。
