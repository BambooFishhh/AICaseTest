# k6 压测基线

## 安装

```bash
# 官方安装方式；下载慢时可使用 GitHub 镜像或包管理器
k6 version
```

## 冒烟测试

```bash
k6 run loadtest/k6/smoke.js
```

## 负载测试

```bash
k6 run \
  -e BASE_URL=http://localhost:8000 \
  -e K6_ADMIN_USER=admin \
  -e K6_ADMIN_PASSWORD=<密码> \
  -e VU_COUNT=20 \
  loadtest/k6/load.js
```

默认阈值：

- `http_req_failed`：冒烟 < 1%，负载 < 5%
- `http_req_duration`：冒烟 P95 < 500ms，负载 P95 < 2000ms

## 建议基线

首次建立基线后记录输出，后续版本对比：请求成功率、P95/P99 延迟、吞吐量、队列积压。配合 vP3 的 Prometheus/Grafana 观察后端与中间件指标。
