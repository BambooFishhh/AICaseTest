# PRD v8.9.3 — 对账内存优化 + 并发残留清理

> 版本 v8.9.3，一旦确定尽量不要轻易改动。基线 v8.9.2。范围：计划书「阶段 6」任务 12.5 + 12.6（CR §9.3 C4/C5/C6/C7；C8 可选暂缓）。

## 一、背景与痛点

- **C4**：对账 `findByProjectId` 拉全量实体 + `queryIdsByProject` 一次拉全部向量 id——万级项目内存与 gRPC 报文双重压力（OOM 尾部风险）；
- **C5**：compose `LLM_MAX_PROMPT_CHARS=300000` 与 v8.4 代码默认 500000 不一致——**生产容器扩容从未生效**；
- **C6**：degradedProvider ThreadLocal 异常路径未消费，池化线程下个任务读到旧值串台；
- **C7**：MetricsFacade/ObservabilityFilter 热路径每次重建 Builder+registry 查找。

## 二、范围与验收

| # | 内容 | 验收 |
|---|---|---|
| 12.5 | 对账集合比对改 id 投影；Milvus 向量 id 分页拉取（每页 1000）；缺失补索引分批 ≤500 | 投影/分批/分页单测绿；"失败≠空集"语义保持 |
| 12.6① | compose LLM_MAX_PROMPT_CHARS → 500000；删除死配置 LLM_MAX_CONTEXT_CHARS 注入行 | compose config 通过 |
| 12.6② | degradedProvider 路由入口先清残留 | 池化残留单测：入口清除后 consume 返回 null |
| 12.6③ | MetricsFacade Counter/Timer 缓存；ObservabilityFilter method\|status 本地缓存 | 既有指标单测全绿 |
| C8 | VueAnalyzer 共享池 | 暂缓（可选子项，现有池量级小收益有限） |

## 三、交付物清单

TestCaseRepository/MilvusService/VectorReconciliationService/LlmService/MetricsFacade/ObservabilityFilter/docker-compose.yml 修改；MilvusQueryPaginationTest 新增 ×3；对账测试桩迁移 +1；路由测试 +1。
