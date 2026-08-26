# PRD v8.7.1 — 指标埋点 + MDC 标准化（计划书阶段 3 上半）

> 版本 v8.7.1，一旦确定尽量不要轻易改动。基线 v8.6.2。范围：计划书任务 9.5.1–9.5.4（9.5.6 追踪按计划书默认跳过）。
> v8.7 拆分说明：阶段 3 含指标/看板/评测三块，拆为 v8.7.1（本版，指标+MDC）与 v8.7.2（看板告警 9.5.5 + 评测体系 9.5.7–9.5.10）。

## 一、背景

计划书 G4：降级点只进日志不进指标，劣化无法提前发现。v8.4/v8.5/v8.6 埋下的降级与一致性钩子（解析跳过、流式重试、截断、Milvus 失败、补偿积压、对账漂移、schema 违规、池拒绝）目前全部不可观测。

## 二、范围

| # | 任务 | 内容 |
|---|---|---|
| 9.5.1 | MetricsFacade | 统一指标入口（registry 未注入时全 no-op），命名前缀 gen_/milvus_/executor_/llm_/rag_，Counter 以 _total 结尾 |
| 9.5.2 | 生成与向量链路埋点 | 计划书清单九项指标全量落地 |
| 9.5.3 | 资源与 RAG 埋点 | executor_rejected_total{pool} / llm_schema_violation_total{agent} / rag_recall_count·rag_empty_recall_total·rag_latency_seconds |
| 9.5.4 | MDC 标准化 | taskId/projectId 进 MDC；异步任务 TaskDecorator 传递；logstash-encoder 自动携带 |

## 三、指标清单与埋点位置

| 指标 | 类型 | 位置 |
|---|---|---|
| gen_parse_skipped_total | Counter | TestGeneratorAgent.parseTestCases 单条畸形跳过分支 |
| gen_retry_reset_total | Counter | LlmService 流式重试前 retryResetHook 触发处 |
| gen_stream_truncated_total | Counter | LlmService 流级看门狗超时中断处 |
| gen_rounds_total{result} | Counter | 轮次出口：completed/not_converged/capped_by_limit |
| gen_cases_generated_total | Counter | parseTestCases 成功返回条数累计 |
| milvus_insert_truncated_total | Counter | MilvusService.insert 字段截断告警分支 |
| milvus_op_failed_total{op} | Counter | MilvusService insert/delete/search/count/query 异常捕获 |
| vector_pending_ops_size | Gauge | 补偿表 PENDING 行数（VectorOpCompensationTask 注册） |
| reconciliation_drift_ratio | Gauge | 最近一轮对账最大漂移率（ReconciliationService 注册） |
| executor_rejected_total{pool} | Counter | AsyncConfig 快速拒绝 handler |
| llm_schema_violation_total{agent} | Counter | LlmSchemaValidator.validateStructured + chatJson 违规分支 |
| rag_recall_count / rag_empty_recall_total | Counter | SemanticService.retrieveContexts 出口 |
| rag_latency_seconds | Timer | retrieveContexts 全程 |

## 四、验收标准

1. `/actuator/prometheus` 可见上述全部指标（部署后人工核验）。
2. MetricsFacade 单测（SimpleMeterRegistry）：counter/timer/gauge 强引用防 GC。
3. MdcTaskDecorator 单测：上下文传递 + 还原。
4. llm_schema_violation_total 随违规递增单测。
5. 默认行为零变化：全量回归绿。

## 五、风险与缓解

- 直 new 单测无 registry → Facade no-op 兜底（字段默认实例 + required=false setter 注入）。
- Gauge 弱引用被回收 → Facade 内 ConcurrentHashMap 持强引用。
- 高频 Timer 标签基数失控 → RAG Timer 无标签。

## 六、交付物

observability/MetricsFacade + MdcTaskDecorator + ObservabilityMdc 新增；AsyncConfig/TestGeneratorAgent/LlmService/MilvusService/SemanticService/LlmSchemaValidator/PendingVectorOpRepository(VectorOpCompensationTask)/ReconciliationService 接线；测试 ×4。
