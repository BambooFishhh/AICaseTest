# PRD v5.12 — AI 评审闭环与覆盖引用收口

**版本**: v5.12
**日期**: 2026-08-16
**基线**: v5.11（ac5a0ad + 57f2175）

## 一、背景与痛点

v5.11 已引入生成链路 AI 评审、`coverageRefs` 与前端评审操作，但复查后仍有以下遗留问题：

1. AI 评审结果只写入 `executionHints.aiReview`，没有独立表；重评会覆盖旧结果，无法审计，且当前存量数据 38/38 缺少 `suggestedChanges`，点击“采纳”实际只改状态。
2. 单条“重新评审”是同步 LLM 调用，前端 axios 只有 30s 超时，而当前模型单次评审可能数分钟，用户操作大概率超时且无恢复入口。
3. 单条重评构造的覆盖清单缺少需求/业务规则，且 LLM 返回部分 `coverageRefs` 时会整体覆盖，可能清掉原有需求、规则、状态转换引用。
4. 接口引用只做相似度追加、不校验真实性，LLM 仍可能保留代码清单中不存在的路径。
5. 覆盖率口径不一致：状态机矩阵支持“计划覆盖”，但用例列表的覆盖率统计仍只统计已执行用例。

## 二、范围

### In Scope

- 新增 `test_case_ai_reviews` 独立表，生成与重评都写审计记录。
- `suggestedChanges` 归一化为固定对象，前端采纳时真正应用非空修改，并同步 `reviewStatus`。
- 单条重评改为异步任务 + 前端轮询，不再依赖长连接同步等待。
- 单条重评覆盖清单补业务规则；评审引用改为“合并而非整体覆盖”，并过滤不存在的接口引用。
- 用例列表覆盖率统计与状态机矩阵统一为“计划引用 + 实际执行”并集口径。
- 补录 v5.11 的 CHANGELOG/README 版本记录，并更新 v5.12 全部文档。

### Out of Scope

- 评审历史的前端可视化列表（本轮先落库，UI 后续版本再做）。
- 需求-代码点映射的完整实现（保留 token 相似度兜底，只保证不丢/不越界）。
- 流式生成“先推送后评审”导致的展示与最终结果不一致问题。
- 批量重新评审、评审任务取消与恢复。
- AI 评审结果写入 Milvus/语义索引。

## 三、功能详情

### 3.1 AI 评审历史独立表

- 新增 `test_case_ai_reviews` 表，字段：`id / project_id / test_case_id / status / issues / suggested_changes / coverage_refs / confidence / source / created_at`。
- `TestCaseReviewAgent` 在每次 LLM 评审落结果时写入一条历史；来源区分 `generation` 与 `rerun`。
- 删除用例、批量删除、删除项目时级联清理评审历史。

### 3.2 suggestedChanges 归一化与采纳语义

- 无论 LLM 是否返回 `suggestedChanges`，`executionHints.aiReview.suggestedChanges` 始终保存 `{title, module, type, priority, coverageRefs}` 五个键，值为 `null` 或真实建议。
- 前端“采纳”只提交非空建议字段，并同时把用例 `reviewStatus` 更新为 `reviewed`；“忽略”只改变 AI 评审状态。
- 采纳/忽略时同步更新该用例最近一条评审历史的状态，保留审计痕迹。

### 3.3 单条重评异步化

- `POST /api/projects/{pid}/testcases/{tcId}/review` 改为立即返回 `{status: "reviewing"}`。
- 后端先把 `aiReview.status` 置为 `reviewing` 落库，再提交到 `generationExecutor` 执行评审。
- 前端提交后轮询 `GET /api/projects/{pid}/testcases/{tcId}`，直到 `aiReview.status` 不再是 `reviewing`。
- LLM 失败时把状态置为 `failed` 并保留规则兜底结果，避免一直停留在 `reviewing`。

### 3.4 引用合并与接口过滤

- 评审返回的 `coverageRefs` 与既有引用合并：某类 id 列表为空时保留原值，非空时才更新，避免丢失需求/规则/状态转换引用。
- 接口引用在存在代码清单时只保留清单中真实存在的 id，并继续对 `apiEndpoints` 做 token 相似度匹配补全。
- 单条重评的覆盖清单补入后端分析结果中的 `businessRules`；需求清单本轮不重建，依赖合并逻辑保护既有引用。

### 3.5 覆盖率口径统一

- 用例列表接口的 `coverage.stateTransition` 与 `coverage.apiEndpoint` 改为同时统计 `coverageRefs` 计划引用和已执行用例的实际引用。
- 与状态机矩阵一致：只要用例声明覆盖某转换/接口，即使未执行也计入覆盖。

## 四、验收标准

1. `test_case_ai_reviews` 表通过 Flyway 迁移创建，生成与重评都会写入历史。
2. 所有新增/重评结果的 `aiReview.suggestedChanges` 都包含五个固定键。
3. 有建议时点击“采纳”会真正修改标题/模块/类型/优先级或 `coverageRefs`，并把 `reviewStatus` 置为 `reviewed`。
4. 点击“重新评审”后接口立即返回，前端轮询到完成或失败，不再出现 30s 超时假失败。
5. 单条重评后既有 `requirementIds / ruleIds / transitionIds` 不被空结果清空。
6. `coverageRefs.endpointIds` 中不存在于代码清单的引用被过滤，`apiEndpoints` 匹配到的真实接口被补全。
7. 用例列表覆盖率与状态机矩阵口径一致。
8. 后端 `mvn compile` 与前端 `npm run build` 均通过。

## 五、风险与规避

| 风险 | 影响 | 规避 |
|---|---|---|
| 异步重评期间服务重启 | 状态停留在 `reviewing` | 本轮接受；前端轮询超时后提示稍后刷新，后续版本加任务恢复 |
| 需求清单无法在单条重评重建 | LLM 可能返回空需求引用 | 使用合并策略保留既有引用，不整体覆盖 |
| 覆盖率口径从“已执行”变为“计划+执行” | 统计值可能上升 | 文档与 UI 提示统一为计划覆盖，验收按新口径 |
| 评审历史表增长 | 数据量累积 | 删除用例/项目级联清理，后续可复用保留策略 |

## 六、交付物清单

- [ ] PRD v5.12
- [ ] 后端技术评审 v5.12
- [ ] 前端技术评审 v5.12
- [ ] Flyway V5 迁移与评审历史实体/仓储
- [ ] AI 评审异步化后端与前端轮询
- [ ] 采纳/忽略语义修正
- [ ] 引用合并与接口过滤
- [ ] 覆盖率口径统一
- [ ] CHANGELOG / README / API 概览更新
- [ ] 提交并推送 origin/main
