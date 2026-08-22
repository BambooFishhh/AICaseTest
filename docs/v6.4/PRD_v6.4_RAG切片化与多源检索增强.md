# PRD v6.4 — RAG 切片化与多源检索增强

## 1. 迭代背景与痛点分析

v5.4 引入了基于 Milvus + Embedding 的语义检索，目前已经支撑生成前 RAG、前端组件 RAG、语义去重、用例语义搜索和失败经验库。经过 v6.2/v6.3 的业务验证，当前 RAG 存在以下问题：

1. **索引侧没有切片**：`indexContext` 对每篇文档只打一个向量，超过 8000 字符直接 `substring` 截断，长 PRD 的尾部信息对 RAG 完全不可见。
2. **索引源缺失**：`contextDocs` 与补充需求没有进入 Milvus，但 RAG 查询段却来自“全部 reqDocs + supplementary”，存在“用未索引内容检索”的浪费。
3. **自我检索**：整段 PRD 会作为查询段，检索命中的大概率是 prompt 中已经存在的整篇 PRD/后端分析 JSON，信息增益低。
4. **召回后只有排序没有重排**：多路查询结果只按“保留最高 cosine 分”合并，没有使用 RRF 等融合重排策略。
5. **失败经验库只写不读**：`recordFailure` 持续写入，但 `searchFailures` 没有任何调用方，历史失败经验没有回流到生成阶段。

## 2. 范围

### In Scope

- 新增 `RagTextChunker`：对 PRD、上下文文档、补充需求按 Markdown 标题 + 段落切片，支持重叠窗口和标题元数据。
- 索引源补齐：PRD 切片、上下文文档切片、补充需求切片分别写入 Milvus `contexts` 集合。
- 查询段改造：去掉整段 PRD 检索，查询段改为“模块 + 需求 + 上下文文档片段 + 补充需求”。
- 多路召回重排：`retrieveContexts` 改为 RRF 融合，并支持 module 过滤，生成阶段只消费需求类上下文。
- 失败经验闭环：生成前检索相似失败经验并注入 prompt。
- 配置化：chunk 大小、重叠、RRF K 值、各检索 topK、最大查询段数均支持环境变量覆盖。

### Out of Scope

- 引入独立 rerank API（cross-encoder / LLM reranker）。
- 历史相似用例注入生成 prompt。
- 前端大版本 UI 改造。

## 3. 功能详情

### 3.1 RagTextChunker

新增 `backend/src/main/java/com/testagent/service/RagTextChunker.java`：

- 输入整篇文本，按 `# / ## / ###` 标题与空行段落切分为语义块。
- 每块记录 `title`（当前标题）与 `text`（切片正文）。
- 单块超过 chunk size 时按中文/英文标点边界二次切分，并保留 overlap 窗口，避免切在句子中间。
- 默认 chunk size 500、overlap 150，可通过 `RAG_CHUNK_SIZE` / `RAG_CHUNK_OVERLAP` 覆盖。

### 3.2 需求上下文索引

`SemanticService` 新增：

```java
replaceRequirementContexts(projectId, prdDocs, contextDocs, supplementary)
```

- `prd`：PRD 文档切片，module 固定为 `prd`。
- `context`：上下文文档切片，module 固定为 `context`，title 带文档名与章节标题。
- `supplementary`：补充需求切片，module 固定为 `supplementary`。
- 每个 module 先按项目删除旧向量再写入，保证存量数据可被重新索引覆盖。
- 生成前通过 `ensureRequirementContexts` 按文档指纹判断是否已切片：存量项目首次生成自动重建，内容未变化时跳过，避免重复 embedding。
- 生成前通过 `ensureRequirementContexts` 按文档指纹判断是否已切片：存量项目首次生成自动重建，内容未变化时跳过，避免重复 embedding。

`ProjectService` 中所有 PRD 保存/更新路径统一调用该方法，不再只写一条整篇 PRD 向量。

### 3.3 多路查询 + RRF 重排

`buildRagQueries` 不再加入整段 `ragText`，而是生成：

- 各 module 的 `name + description`
- 各 requirement 的 `title + description`
- 各上下文文档的 `title + 内容片段`
- 补充需求片段

`retrieveContexts(projectId, queries, topK, modules)`：

1. 每个查询段分别 `embedding + Milvus search`（`topK * 3` 候选）。
2. 多路结果按 RRF 融合：`score = Σ 1/(rrfK + rank)`。
3. 同一切片 id 合并后按 RRF 分降序取 topK。
4. 生成阶段仅检索 `prd / context / supplementary`，避免再召回 backend/frontend 整段 JSON。

### 3.4 失败经验闭环

`SemanticService.retrieveFailures(projectId, queries, topK)` 复用 RRF 融合，从 `failures` 集合检索历史失败步骤。

`OrchestratorAgent` 在生成上下文加载阶段检索 `ragFailures` 并写入 `PrdAnalysisResult`；`TestGeneratorAgent` 将 `ragFailures` 注入 prompt，并提示 LLM 避免重复失败路径。

### 3.5 配置项

| 配置 | 环境变量 | 默认值 | 说明 |
|---|---|---|---|
| `app.rag.chunk-size` | `RAG_CHUNK_SIZE` | 500 | 切片目标字符数 |
| `app.rag.chunk-overlap` | `RAG_CHUNK_OVERLAP` | 150 | 切片重叠字符数 |
| `app.rag.rrf-k` | `RAG_RRF_K` | 60 | RRF 平滑系数 |
| `app.rag.context-topk` | `RAG_CONTEXT_TOPK` | 6 | 需求上下文最终 topK |
| `app.rag.failure-topk` | `RAG_FAILURE_TOPK` | 3 | 失败经验最终 topK |
| `app.rag.max-queries` | `RAG_MAX_QUERIES` | 12 | 最大查询段数 |

## 4. 验收标准

1. 长 PRD（超过 8000 字符）尾部内容不再被截断丢失，可通过对应 module/requirement 查询命中切片。
2. 上下文文档与补充需求保存后进入 Milvus `contexts` 集合。
3. RAG 检索结果为多路查询 RRF 融合，日志可看到命中的 context/failure 数量。
4. 生成 prompt 中包含 `ragContexts` 与 `ragFailures`，且总数受预算控制。
5. Milvus 未启用时仍与 v6.3 一致静默降级，不阻塞生成。
6. 后端 `mvn compile` BUILD SUCCESS；新增切片与 RRF 单测通过；前端 `npm run build` 成功。
7. Docker 栈重新部署后，backend/frontend healthy，prod profile 生效。

## 5. 风险与规避

| 风险 | 影响 | 规避 |
|---|---|---|
| Milvus 残留旧整篇向量 | 检索结果混入旧数据 | 每个 module 重建时先 `deleteByModule` |
| 切片数量增多导致 prompt 超限 | 生成超时 | 控制 context/failure topK，注入前截断 |
| Embedding 调用次数上升 | 保存需求文档变慢 | 切片后按块 embedding，块数受文档长度限制 |
| 测试/构建环境无 Milvus | 集成测试不稳定 | RAG 逻辑按单元测试验证，编译/部署走 Docker 栈 |

## 6. 交付物

- `RagTextChunker` 切片工具
- `SemanticService` 切片索引、RRF 检索、失败经验检索
- `MilvusService` module 过滤与 module 返回
- `OrchestratorAgent` / `TestGeneratorAgent` 查询与 prompt 增强
- `docs/v6.4/后端技术评审_v6.4.md`
- `docs/v6.4/前端技术评审_v6.4.md`
