# v5.5 PRD：正式切换 MySQL + Redis + Milvus 与全量回归

## 1. 迭代背景与痛点

- v5.0 ~ v5.4 已分别完成 MySQL 基建、迁移工具、Redis 运行态、缓存/队列、Milvus 语义层，但生产默认仍是 H2 单机形态。
- 需要把 prod 形态正式切换为 MySQL + Redis + Milvus 全栈，H2 仅保留为开发 profile。
- 切换涉及 compose 编排、后端默认配置、健康观测与回归脚本，需要一次完整收口。

## 2. 范围（In / Out of scope）

### In scope

- `prod` profile 默认启用 Redis 运行态与 Milvus 语义层，并通过 profile group 自动带入 MySQL。
- docker-compose 后端依赖 MySQL / Redis / Milvus，注入对应连接环境变量。
- 健康检查扩展：`/api/health` 返回数据源、Redis、Milvus 状态。
- 新增 `scripts/verify-v5-stack.ps1` 全量回归脚本（后端测试 + 前端构建 + compose 校验 + 可选健康检查）。
- 全量回归与轻量压测：50 并发健康请求验证。

### Out of scope

- 新业务功能（各组件在 v5.0~v5.4 已完成）。
- Milvus 实机压测（受本机无 Milvus 容器限制，语义层保持开关降级）。

## 3. 功能详情

### 3.1 正式切换

```yaml
spring:
  profiles:
    group:
      prod: mysql
      migrate: mysql
```

- `application-prod.yml`：`app.redis.enabled=true`、`app.milvus.enabled=true`。
- 本地默认（无 profile）仍为 H2 开发模式。

### 3.2 compose 编排

- 后端 `depends_on`：mysql / redis / milvus 均等待健康。
- 环境变量：`MYSQL_URL`、`REDIS_HOST=redis`、`MILVUS_HOST=milvus`、`APP_REDIS_ENABLED=true`、`APP_MILVUS_ENABLED=true`。

### 3.3 健康检查

```json
{
  "status": "UP",
  "version": "5.5.0",
  "dataSource": "UP",
  "redis": "redis",
  "milvus": "enabled|disabled"
}
```

### 3.4 回归脚本

`scripts/verify-v5-stack.ps1`：

- 后端 `mvn test`。
- 前端 `npm run build`。
- `docker compose config --quiet`。
- 可选 `-HealthUrl` 健康检查。

## 4. 验收标准

1. `mvn test` BUILD SUCCESS。
2. `npm run build` 成功。
3. `docker compose config` 校验通过。
4. prod 启动后 `/api/health` 显示 `dataSource=UP`、`redis=redis`。
5. 登录失败 5 次后 Redis 中出现 `rt:login:{username}` 锁定键，第 6 次返回 429。
6. 50 次健康请求全部成功。

## 5. 风险与规避

| 风险 | 规避 |
|---|---|
| prod 误连 H2 | profile group 强制 mysql，docker 注入 MYSQL_URL |
| Redis/Milvus 不可用导致启动失败 | 各组件降级设计 + 健康检查可观测 |
| 本机无 Milvus | Milvus 开关保持可配置，compose 已提供 standalone |

## 6. 交付物清单

- PRD / 后端评审 / 前端评审
- profile 切换、compose 编排、健康检查扩展
- `scripts/verify-v5-stack.ps1`
- CHANGELOG / README 更新
