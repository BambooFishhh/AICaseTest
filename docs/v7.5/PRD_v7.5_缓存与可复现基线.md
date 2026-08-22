# PRD v7.5 — 缓存与可复现基线

| 项 | 内容 |
|---|---|
| 版本 | v7.5 |
| 日期 | 2026-08-23 |
| 基线 | v7.4 |
| 范围 | 后端（LLM 结果缓存层） |
| 对应风险清单 | A11 / A15 |

## 1. 背景与目标

v7.4 让分析输入可复现（A9 文件排序），但**相同输入仍然重复付费调 LLM**：

1. **A15 PRD 解析无缓存**：每次生成重新调 `prdAgent.analyze`（temp 0.2）。同一 PRD 两次生成 requirements 列表本身漂移——追加生成与首次生成模块口径可能不一致（一致性损耗 + 省钱机会双缺）。
2. **A11 组件 LLM 摘要无缓存**：`VueAnalyzer` 每次分析对每个业务组件完整调 LLM（并发 4）。文件没变也重跑，几十次调用无缓存，分析是高频操作，成本线性放大。

**目标**：LLM 解析结果"同输入同输出、同输入不重复付费"——统一 prompt-hash 缓存层，PRD 解析与组件摘要命中即复用，PRD 未变时两次生成的 requirements 完全一致（可复现基线）。

## 2. 需求清单

### 2.1 统一 LLM 结果缓存层

- 新增 `llm_result_cache` 表（JPA 实体 + MySQL V11 迁移）：
  - `cache_key` VARCHAR(64) PK = SHA-256(模型名 + systemPrompt + 分隔符 + userPrompt)；
  - `cache_kind` VARCHAR(32)：`prd_analysis` / `component_summary`（区分用途）；
  - `result_text` MEDIUMTEXT：LLM 原始响应文本；
  - `created_at` DATETIME。
- 新增 `LlmResultCacheService`：
  - `String get(String kind, String systemPrompt, String userPrompt)`——键 = sha256(model + "|" + systemPrompt + "|" + userPrompt)，命中返回响应文本，未命中返回 null；
  - `void put(String kind, String systemPrompt, String userPrompt, String response)`——upsert，主键冲突（并发竞争）静默忽略；
  - 模型名从 `${llm.model:gpt-4o}` 注入（与 LlmService 同源配置）——换模型自动全量失效。
- **失效语义**：无 TTL，键含模型名+完整 prompt——PRD 内容变 / 组件源码变 / prompt 变 / 模型变 → 新键自然失效，旧键成为无害垃圾（本版不做清理，非目标）。
- **失败不缓存**：LLM 调用失败/响应解析失败不写缓存（只缓存"成功且可解析"的结果）。
- 缓存读写异常（DB 故障）降级为不缓存直调 LLM，不得阻断分析/生成。

### 2.2 A15 — PRD 解析缓存

- **接入点**：`PrdAgent.analyze(prdDocs, contextDocs, supplementary)` 内部（所有调用方自动受益：OrchestratorAgent 生成链路 + McpBridgeController）。
- 缓存键输入 = `buildRequirementPrompt` 输出的 requirementText + systemPrompt——PRD/上下文/补充需求任何变化都会改变键。
- **命中**：反序列化为 `PrdAnalysisResult` 直接返回（与 LLM 输出同构），log.info 记录命中；**未命中**：调 LLM 成功且结果非空后写缓存。
- 缓存内容为 `analyzeByLlm` 的解析产物（modules/requirements/businessRules/stateFlows/entities）；`ragContexts`/`frontendComponents` 等动态检索字段由 OrchestratorAgent 在 analyze 之后注入，不受缓存影响。
- 解析失败/空结果照旧抛 BusinessException，不写缓存。

### 2.3 A11 — 组件 LLM 摘要缓存

- **接入点**：`VueAnalyzer.enhanceComponentSummary`。
- 缓存键输入 = 完整 prompt（含组件名/路由/完整源码）——组件源码任何变化都会改变键。
- **命中**：缓存响应直接走既有 `mergeComponentSummary`（解析+合并路径单一）；**未命中**：LLM 调用后 merge 解析成功才写缓存（解析失败的响应不缓存，防止毒缓存）。
- 并发安全：组件摘要并发 4 路调 LLM，put 的主键冲突静默忽略。

## 3. 非目标（明确不做）

- 缓存清理/TTL/容量上限——旧键无害，后续版本按需加定时清理。
- Spring Cache（@Cacheable）接入——现有 CacheManager 是 10 分钟 TTL 的进程级/Redis 缓存，与"内容未变一直有效"语义不符。
- embeddings/RAG 切片缓存（v6.4 已有 ensureRequirementContexts 增量机制）。
- 缓存命中率的仪表盘展示——log 落地，指标随后续版本。

## 4. 验收标准

1. 同一 PRD 两次生成：第二次 `prdAgent.analyze` 不调 LLM，requirements 与第一次完全一致（temp 0.2 漂移消除）。
2. 同一前端代码两次分析：第二次业务组件摘要不调 LLM，componentSummaries 与第一次一致。
3. PRD 内容修改一个字 → 缓存未命中 → 重新调 LLM。
4. 组件源码修改 → 该组件摘要重新调 LLM，未修改组件仍命中。
5. 缓存表 DB 异常 → 分析/生成正常完成（直调 LLM），仅日志告警。
6. 现有 150 个测试全绿 + 新增缓存层单测（命中/未命中/键漂移/毒响应不缓存/DB 异常降级）。
