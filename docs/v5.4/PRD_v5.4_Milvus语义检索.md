# v5.4 PRD：Milvus 语义检索层

## 1. 迭代背景与痛点

- 用例去重目前依赖标题/字符重叠，无法识别"语义相同但措辞不同"的重复用例。
- 生成前上下文只有 PRD + 静态代码分析，没有"相似资产复用"能力。
- 用例搜索只能按标题/模块关键词匹配，缺少自然语言语义搜索。
- 执行失败后错误经验只留在执行记录里，无法在下一次失败时检索相似历史。

## 2. 范围（In / Out of scope）

### In scope

- MCP `llm_embedding` 工具（OpenAI 兼容 embeddings 接口）。
- 后端 `EmbeddingService` + `MilvusService`（cases / contexts / failures 三个集合）。
- 语义去重：追加生成时按相似度阈值过滤。
- RAG 上下文检索：PRD/分析结果写入 contexts，生成前 Top-K 注入 prompt。
- 语义搜索：`GET /api/projects/{id}/testcases/semantic-search?q=` + 前端搜索对话框。
- 失败经验库：失败步骤写入 failures，检索 API 复用同一检索层。
- docker-compose 增加 etcd / minio / milvus standalone。

### Out of scope

- 正式切换默认数据源/运行态（v5.5）。
- 跨项目资产复用与 XMind 导入去重（规划中的可选场景，本次不做）。

## 3. 功能详情

### 3.1 embedding 管道

```text
文本 → MCP llm_embedding → List<Float> → Milvus insert/search
```

- `mcp-server` 新增 `llm_embedding` 工具，模型可用 `LLM_EMBEDDING_MODEL` 单独指定。
- `EmbeddingService` 未配置 LLM 或调用失败时返回空向量，语义能力自动降级。

### 3.2 Milvus 集合

| 集合 | 内容 | 写入时机 | 检索场景 |
|---|---|---|---|
| cases | 用例标题/模块/步骤/预期 | 生成/追加/导入 | 语义去重、语义搜索 |
| contexts | PRD/后端分析/前端分析 | PRD 保存、代码分析完成 | 生成前 RAG |
| failures | 失败步骤 action + error | 执行失败 | 失败经验检索 |

### 3.3 语义去重

- 追加生成时除标题去重外，对每条新用例做 embedding 检索。
- 与同项目已索引用例的余弦相似度 ≥ `MILVUS_DUPLICATE_THRESHOLD`（默认 0.92）时判重。

### 3.4 RAG 上下文检索

- PRD 保存/抓取/上传时写入 contexts；代码分析完成时写入 backend/frontend 结果。
- `OrchestratorAgent` 生成前检索 Top-5 contexts 写入 `PrdAnalysisResult.ragContexts`，由 `TestGeneratorAgent` 注入 prompt。

### 3.5 失败经验库

- 执行记录最终状态为 failed 时，逐条失败步骤写入 failures。
- `SemanticService.searchFailures` 可检索相似历史，供后续界面/报告接入。

## 4. 验收标准

1. `mvn compile` / `mvn test` 通过。
2. mcp-server / playwright-mcp-server `node --check` 通过。
3. Milvus 未启用时系统完全降级，现有生成/执行/搜索不受影响。
4. 启用 Milvus + embedding 后：追加生成语义去重、生成前 RAG 注入、语义搜索接口可用。
5. 前端 `npm run build` 成功，语义搜索对话框可用。

## 5. 风险与规避

| 风险 | 规避 |
|---|---|
| Milvus/embedding 依赖导致主流程不可用 | 所有调用 try/catch + 开关默认关闭 |
| embedding 模型维度与 Milvus 不一致 | `MILVUS_DIMENSION` 可配置，集合按配置建 |
| 语义索引与 MySQL 数据不一致 | 重新生成先清空 cases 再重建；追加增量写入 |

## 6. 交付物清单

- PRD / 后端评审 / 前端评审
- mcp-server `llm_embedding`
- EmbeddingService / MilvusService / SemanticService
- 生成/分析/执行链路语义接入
- 语义搜索 API + 前端对话框
- compose Milvus standalone
