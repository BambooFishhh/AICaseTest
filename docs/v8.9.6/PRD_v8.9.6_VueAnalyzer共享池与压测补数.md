# PRD v8.9.6 — VueAnalyzer 共享池 + 并发压测补数

> 版本 v8.9.6，一旦确定尽量不要轻易改动。基线 v8.9.5。范围：计划书「阶段 6」可选尾项 C8（12.6④）+ 任务 12.7 并发数据补齐。纯后端/工程版本。

## 一、背景与痛点

- **C8**（12.6 可选子项）：VueAnalyzer 每次组件摘要分析新建 `Executors.newFixedThreadPool(workers)` 后 shutdown——频繁创建销毁开销、线程无命名前缀难排查、无 MDC 装饰靠手工传播 telemetryCtx；
- **12.7 尾巴**：容量基线首版只有顺序冒烟数据（RPS 为客户端下界），缺并发口径数字；k6 镜像在本机拉取超时不可用。

## 二、范围与验收

| # | 内容 | 验收 |
|---|---|---|
| C8 | 新增共享受管池 `vueLlmExecutor`（core=max=llm-concurrency，vue-llm- 线程前缀，MdcTaskDecorator）；VueAnalyzer 改注入复用 | 全量回归绿；直 new 单测行为不变 |
| 12.7 | PS 多进程并发压测工具 + 实测数字入 docs/容量基线报告.md | 报告含并发 RPS/P50/P95 与零失败证明 |

## 三、功能细节

- **vueLlmExecutor Bean**（AsyncConfig）：ThreadPoolTaskExecutor core=max=llm-concurrency（app.executor.llm-concurrency 默认 4），threadNamePrefix=vue-llm-，taskDecorator=MdcTaskDecorator（补齐 MDC 装饰缺口），destroyMethod=shutdown 生命周期归容器。
- **VueAnalyzer 接线**：字段默认 null + @Autowired(required=false) setter——直 new 单测回落"临时池+shutdown"原路径；共享池借用模式不 shutdown（生命周期归容器）。telemetryCtx/bindPhase 手工传播保留（埋点上下文非 MDC 范畴）。
- **并发压测**：`perf/load-shortrequests.ps1`——Start-Job N worker 进程各自顺序请求固定次数，父进程聚合 P50/P95/RPS/失败数；本次 8 worker × 150。

## 四、验收标准

1. 全量回归绿（491 tests）。
2. 并发压测零失败且数字入容量报告（实测：1200 请求 RPS=202.4 / P95=82ms / Max=103ms）。

## 五、交付物清单

config/AsyncConfig.java、analyzer/VueAnalyzer.java 修改；perf/load-shortrequests.ps1 新增；docs/容量基线报告.md 回填。
