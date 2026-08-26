# PRD v8.9.2 — 连接池对齐 + LLM 入口限流

> 版本 v8.9.2，一旦确定尽量不要轻易改动。基线 v8.9.1。范围：计划书「阶段 6」任务 12.1 + 12.2（CR §9.3 C1/C2 承压瓶颈项）。

## 一、背景与痛点

- **C1**：HikariCP(20) 与任务线程池总量(26+)不匹配——高峰期任务线程阻塞在连接获取，connection-timeout 30s 后批量失败，表现为间歇性 500 与任务堆积；
- **C2**：LLM 主链路无全局限流——生成/PRD/评审仅靠线程池槽位限流，VueAnalyzer 4 路并发、dedup embedding 4 线程各自为政，聚合并发可击穿供应商 RPM；多实例水平扩展后配额翻倍失控。

## 二、范围

| # | 任务 | 内容 |
|---|---|---|
| 12.1 | 连接池对齐 | HIKARI_MAX_POOL 20→40、MIN_IDLE 5→10；新增泄漏检测 60s；容量匹配关系写入注释 |
| 12.2 | LLM 入口限流 | LlmRateLimiter 通道信号量（text/stream/embedding/fallback-text 独立配额）；三入口接入；等待/拒绝双指标 |

约束：不引入分布式限流组件（多实例聚合口径=实例配额×实例数≤供应商 RPM，写入配置注释）；embedAllParallel 与 VueAnalyzer 无需改造，统一受入口信号量约束。

## 三、验收标准

1. 限流单测绿：并发超额阻塞、超时拒绝抛 50300、通道独立配额互不影响。
2. `/actuator/prometheus` 可见 `llm_rate_limit_wait_total` / `llm_rate_limit_rejected_total`。
3. 全量回归绿；未达配额时行为与 v8.9.1 一致。

## 四、风险与缓解

| 风险 | 缓解 |
|---|---|
| 主通道 text 配额满误伤正常请求 | 50300 为可重试语义并自动衔接降级路由切 fallback-text 独立配额 |
| MySQL max-connections 容纳 | compose 已是 200，容纳多实例 × 40 |
| 直 new 单测无 registry | Facade no-op 兜底 |

## 五、交付物清单

application-mysql.yml 修改；service/LlmRateLimiter 新增；LlmService/EmbeddingService 接入；application.yml 五键；LlmRateLimiterTest ×4。
