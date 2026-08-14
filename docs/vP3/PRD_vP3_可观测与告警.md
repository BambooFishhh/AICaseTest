# vP3 PRD：可观测与告警

> 版本 vP3，一旦确定尽量不要轻易改动。迭代范围：Grafana 面板、告警规则、traceId/access log、SLO。

## 1. 迭代背景与痛点

- 已有 `/actuator/prometheus`，但缺少 Prometheus/Grafana 采集与可视化。
- 日志无 traceId，单次请求难以串起 access log 与业务日志。
- 无请求错误率/延迟 SLO 指标与告警，问题只能事后人工排查。
- 任务队列只有 API 统计，没有接入指标体系。

## 2. 范围（In / Out of scope）

### In scope

- `ObservabilityFilter`：traceId 生成/透传、JSON access log、SLO 指标上报。
- 任务队列 queued/running 指标（`aicasetest_queue_*`）。
- Prometheus 采集配置 + 告警规则（后端宕机、5xx 错误率、P95 延迟、队列积压）。
- Grafana 数据源/仪表盘 provisioning，提供 AICaseTest SLO 面板。
- Compose 增加 prometheus/grafana 服务，端口仅绑定 127.0.0.1。

### Out of scope

- 分布式链路追踪（Jaeger/Tempo）、日志采集（Loki）与告警通知渠道接入。
- 多环境 SLO 报表与容量规划。

## 3. 功能详情

### 3.1 traceId / access log

- 请求透传 `X-Trace-Id`（超长/缺失时生成 16 位 traceId）。
- 响应头返回 `X-Trace-Id`，CORS 暴露该头。
- access log：method、uri、status、durationMs、traceId；`/actuator/*` 与 `/api/health` 降为 DEBUG。
- logback JSON 已通过 MDC `trace_id` 自动带上。

### 3.2 SLO 指标

- `aicasetest_http_requests_total`：按 method/status 计数。
- `aicasetest_http_requests_duration_seconds_bucket`：耗时直方图（P95 可用）。
- `aicasetest_queue_queued` / `aicasetest_queue_running`：任务队列 Gauge。

### 3.3 告警规则

`monitoring/prometheus/alerts.yml`：

| 告警 | 规则 | 级别 |
|---|---|---|
| AICaseTestBackendDown | `up == 0` 持续 2m | critical |
| AICaseTestHighErrorRate | 5xx 占比 > 5% 持续 5m | warning |
| AICaseTestP95LatencyHigh | P95 > 3s 持续 10m | warning |
| AICaseTestQueueBacklogHigh | queued > 20 持续 5m | warning |

### 3.4 Grafana

- `monitoring/grafana/provisioning/` 自动注册 Prometheus 数据源与 AICaseTest SLO 仪表盘。
- 面板：请求速率、5xx 错误率、P95 延迟、队列积压、JVM Heap、运行时长。
- compose 暴露 Grafana `127.0.0.1:3001`，默认账号密码可由 `.env` 覆盖。

## 4. 验收标准

1. `mvn -Dtest=ObservabilityFilterTest test` 通过，access log 带 traceId。
2. `mvn compile` BUILD SUCCESS。
3. `npm run build` 回归通过。
4. `docker compose config` 通过，含 prometheus/grafana 服务。
5. 仪表盘 JSON 可被 Grafana 加载（provisioning 路径正确）。
6. Prometheus 告警规则 YAML 语法正确。

## 5. 风险与规避

| 风险 | 规避 |
|---|---|
| URI 高基数标签 | SLO 指标只按 method/status 分桶，不按 URI |
| access log 刷屏 | 健康检查/指标抓取降为 DEBUG |
| Grafana 默认口令 | 通过 `.env` 配置 `GRAFANA_ADMIN_PASSWORD` |
| 告警无人接收 | 规则先行，通知渠道（Alertmanager/webhook）留待部署接入 |

## 6. 交付物清单

- PRD / 后端评审 / 前端评审
- ObservabilityFilter、QueueMetrics
- Prometheus 配置与告警规则
- Grafana provisioning + SLO 仪表盘
