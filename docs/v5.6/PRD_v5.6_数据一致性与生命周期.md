# v5.6 PRD：数据一致性与生命周期

## 1. 迭代背景与痛点

- 重新生成用例采用"先全删再逐个保存"，不是事务操作，中途失败可能留下半批用例。
- 删除项目只清理了分析/状态机/用例/脑图，执行记录、执行步骤、测试集、用例版本会变成孤儿数据。
- 语义索引只覆盖"生成/追加"，手工编辑、删除、导入、复制后的用例与 Milvus 向量不一致。
- PRD 和代码分析每次更新都会新增 contexts 向量，旧向量无法被检索到正确版本。

## 2. 范围（In / Out of scope）

### In scope

- 重新生成用例事务化落库（含清理旧版本）。
- 删除项目级联清理执行记录/步骤/测试集/用例版本/Milvus 三集合。
- 用例创建/编辑/删除/批量删除/导入/复制与语义索引同步。
- Milvus 支持按 ID 删除、按模块删除；SemanticService 增加 removeCases / reindexCase / replaceContext。
- PRD 与代码分析上下文按模块"先删后写"。

### Out of scope

- 索引与查询性能（v5.7）。
- 数据保留策略与治理 API（v5.8）。

## 3. 功能详情

### 3.1 事务落库

`TestCasePersistenceService.replaceAll(projectId, cases)`：

- `@Transactional`：删除项目旧用例与版本快照 → 写入新用例。
- 生成线程只负责产出与语义重建，落库失败不会残留半批数据。

### 3.2 项目级联清理

`ProjectService.deleteProject` 增加：

- 执行步骤（按项目执行记录 ID 删除）
- 执行记录
- 测试集
- 用例版本
- Milvus `cases` / `contexts` / `failures`

### 3.3 语义同步

| 操作 | 语义动作 |
|---|---|
| 创建用例 | indexCase |
| 编辑用例 | reindexCase（先删旧向量再写入） |
| 删除/批量删除 | removeCases |
| JSON/XMind 导入 | indexCases |
| 跨项目复制 | 目标项目 indexCases |

### 3.4 上下文替换

- PRD 保存/上传/抓取：`replaceContext(projectId, "prd", content)`
- 代码分析完成：`replaceContext(projectId, "backend"/"frontend", json)`

## 4. 验收标准

1. `mvn compile` / `mvn test` 通过。
2. 重新生成用例失败时旧用例保持完整（事务回滚）。
3. 删除项目后无孤儿执行/步骤/测试集/版本，Milvus 三集合清空。
4. 用例编辑/删除/导入/复制后语义索引与数据库一致（Milvus 开启时）。
5. PRD/分析上下文重复写入后同模块只有最新向量。
6. 前端 `npm run build` 成功。

## 5. 风险与规避

| 风险 | 规避 |
|---|---|
| 事务内调用 Milvus 导致长事务 | 语义重建放在事务提交后 |
| 级联删除漏表 | 集中到 ProjectService 并复用仓库删除方法 |
| Milvus 不可用时同步失败 | 所有语义操作 try/catch 静默降级 |

## 6. 交付物清单

- PRD / 后端评审 / 前端评审
- TestCasePersistenceService / SemanticService / MilvusService 增强
- TestCaseService / ProjectService / AnalysisService 生命周期改造
