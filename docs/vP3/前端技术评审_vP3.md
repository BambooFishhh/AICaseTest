# 前端技术评审 vP3：可观测与告警

> 版本 vP3，一旦确定尽量不要轻易改动。

## 1. 变更点

- 前端 `src/` 无源码变更。
- `docker-compose.yml` 新增 `prometheus`、`grafana` 服务，端口绑定 127.0.0.1。
- 新增 `monitoring/prometheus/` 与 `monitoring/grafana/` 配置与仪表盘。

## 2. props/emit 变化

无。

## 3. 数据流

Prometheus 抓取 `backend:8000/actuator/prometheus`；Grafana 从 Prometheus 查询并渲染 AICaseTest SLO 面板。前端应用不参与。

## 4. 向后兼容性

- 新服务不占用 80/443，Grafana 使用独立 3001 端口。
- 未启动 compose 监控服务时，前端/后端功能不受影响。

## 5. 测试验证方案

- `npm run build` 回归。
- `docker compose config`。
- 仪表盘 JSON 解析校验。
