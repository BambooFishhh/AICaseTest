# vT4 PRD：运维与可观测基线

## 1. 迭代背景与痛点

- 后端缺少标准健康/指标端点，无法接入 Prometheus/Grafana。
- 数据目录、输出目录与 MySQL 没有一键备份入口。
- 运维可观测性只有自定义 `/api/health`，缺少 Spring Boot 标准能力。

## 2. 范围（In / Out of scope）

### In scope

- Spring Boot Actuator：`/actuator/health` 公开，`info/metrics/prometheus` 开放。
- Prometheus 指标注册（micrometer-registry-prometheus）。
- 备份脚本 `scripts/backup-v5.ps1`（data/outputs + 可选 MySQL dump）。
- 安全：`/actuator/health` permitAll，其余保持认证。

### Out of scope

- Grafana 面板与告警（后续）。
- 前端变化。

## 3. 功能详情

### 3.1 Actuator

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  endpoint:
    health:
      show-details: always
    prometheus:
      enabled: true
```

- `/actuator/health` 免认证，供 LB/健康检查。
- `/actuator/prometheus` 供采集器抓取。

### 3.2 备份

```powershell
.\scripts\backup-v5.ps1 -MysqlContainer aicasetest-mysql -MysqlPassword aicasetest123
```

输出 `backups/app-backup-{时间戳}/`，包含 `data/`、`outputs/` 与可选的 `mysql.sql`。

## 4. 验收标准

1. `mvn test` 通过（23 个测试）。
2. `/actuator/health` 无认证返回 UP。
3. `/actuator/prometheus` 返回指标文本（认证后）。
4. 备份脚本可执行并生成目录。

## 5. 风险与规避

| 风险 | 规避 |
|---|---|
| 指标端点暴露敏感信息 | 仅 health 公开，其余需认证 |
| mysqldump 密码出现在命令行 | 脚本内使用 MYSQL_PWD 环境变量 |

## 6. 交付物清单

- PRD / 后端评审 / 前端评审
- Actuator/Prometheus 依赖与配置
- `scripts/backup-v5.ps1`
