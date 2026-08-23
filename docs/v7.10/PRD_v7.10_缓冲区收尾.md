# PRD v7.10 — 缓冲区收尾

> 基线：v7.9（c62a76e）｜风险清单 80 项中 A 速赢区 / B 攻坚区已全部完成
> 本版范围：C 缓冲区 17 项 + **补入 G7/G19 两项计划遗漏项**，共 19 项
> 原则不变：准确率优先；效率损失大于准确率收益者不做（延后区 L7 L9 L10 L11 A4b A16 仍不做）

## 一、背景与痛点

v7.0–v7.9 完成了风险清单的诚实化（指标/状态/可观测）、闭环（断言/回写/证据）与精准投喂（过滤/清单/缓存）三波修复。剩余问题集中在缓冲区：**单项准确率收益中等、但分布广、多为"质量信噪比/经验库/一致性"类长尾**。另有复盘发现两项原排 v7.5 的条目（G7/G19）实际未落地，本版补入收口。

### 补入项（复盘发现）

- **G7**：req-N 编号仍是解析顺序临时编号（`"req-" + i++`）。A15 缓存落地后"同一 PRD 两次生成"已稳定，但 **PRD 一变更全量编号漂移**——追加生成时旧用例 `coverageRefs.req-3` 与新 checklist 的 `req-3` 可能指向不同需求，覆盖率历史对比失真。
- **G19**：`ensureRequirementContexts`（Milvus 索引维护）仍在生成热路径 `loadGenerationContext` 内。保存侧四条路径（updatePrd/uploadPrdPdf/fetchPrdUrl/updateProjectContext）已全部触发重建，热路径这次调用属于"读路径藏写操作"的架构卫生问题。

### 缓冲区遗留问题分组

| 组 | 编号 | 共性痛点 |
|---|---|---|
| 生成链路 | G8 G9 G12 G13 | 流式双解析索引错位（重复/漏推）、RAG 配额挤占、多轮重复注入大上下文（纯浪费 token）、置信度硬编码 0.8 无信息量 |
| 评审 | R4 R5 | 评审输出截断时部分用例无结果且无告警、reject 半数保护"恰好一半"时真垃圾也全留 |
| 失败经验 | G18 R13 | 需求形查询 vs 动作形语料向量天然弱（错题本形同虚设）、失败记录无去重（同失败 10 次占满 topK）且文本贫瘠 |
| LLM 配置 | L3 | thinking 开关是"幻觉配置"——打开无效果但用户以为已生效（未标记的降级） |
| 生成质量 | L12 | 选择器匹配阈值过宽（score≥2），"删除"匹配到"批量删除"，错误被固化进用例资产 |
| 分析器 | A3 A6 A12 A18 | 异常只记第一个、业务规则信噪比低（空指针防御全算）、apiCalls 只扫 src/api、Integer/魔法数状态提不出 |
| 一致性 | C2 | PRD 与代码两条证据链无新鲜度/一致性校验，静默分叉 |
| 已覆盖关闭 | A14 C3 | 分别已由 G16（v7.7）/A9+A15（v7.4/v7.5）覆盖大半，本版正式关闭并注明 |

## 二、范围

### In Scope（19 项）

| 编号 | 修复内容 | 改动位置 |
|---|---|---|
| **G7** | requirement id 改内容 hash 稳定化（title+description → `req-<hash10>`；rag-req 同理）；prompt 说明同步 | TestGeneratorAgent + skill 文件 ×3 + TestCaseReviewAgent |
| **G19** | 生成热路径删除 `ensureRequirementContexts` 调用，索引维护只在保存侧（四条路径已覆盖） | OrchestratorAgent |
| **G8** | 流式解析结果为唯一返回值（parser 收集用例列表），消除"流式/全量双解析索引错位"；保留 0 解析兜底全量重解析 | TestGeneratorAgent.StreamingTestCaseParser |
| **G9** | RAG 查询分类别配额（modules 3 + requirements 6 + contextDocs 2 + supplementary 1），取代顺序截断 | OrchestratorAgent.buildRagQueries |
| **G12** | 第 2+ 轮不再注入 prdDocs/contextDocs/补充需求原文（保留结构化 prd 摘要 + gaps + 已生成摘要），纯提效率 | TestGeneratorAgent.generatePrdRound |
| **G13** | confidence 不再硬编码 0.8——calculateQualityScores 时置为 qualityScore/100（评分已含评审结论） | TestGeneratorAgent |
| **G18** | 失败经验查询侧拼入操作/页面类关键词（取前 6 条查询 + " 页面 操作 点击 提交"），并向 R13 语料富化靠拢 | OrchestratorAgent |
| **R4** | 评审 index 去重保留首个（putIfAbsent）；检测输出缺 index 时对缺失子集补评一轮；仍缺则 warn 告警（不再静默） | TestCaseReviewAgent.llmReview |
| **R5** | reject 保护比例渐进：>70% 全保留+告警；40%–70% 按置信度逐条裁决（≥0.75 才删）；≤40% 照删 | TestCaseReviewAgent.llmReview |
| **R13** | 失败经验入库按 (projectId+action+error 归一化) hash 稳定 ID 去重（delete+insert）；语料补用例标题与页面 URL | SemanticService.recordFailure + ExecutionService 调用点 |
| **L3** | thinking 配置诚实化：启动时 warn 告知不生效 + application.yml/.env.example 注释标注咨询性配置 | LlmService + 配置文件 |
| **L12** | 选择器匹配阈值 2→3 且要求唯一最高分（并列最高宁留空，由 Agent 模式执行时自定位） | TestGeneratorAgent.bestSelector |
| **A3** | endpoint 异常提取收集方法内全部 throw（原只看第一个 throw 的第一个 new），上限 5 | SpringAnalyzer.extractEndpoints |
| **A6** | 业务规则只收录业务语义异常（过滤 JDK/Spring 通用异常：空指针防御、参数断言），过滤量进 warnings | SpringAnalyzer.extractBusinessRules |
| **A12** | apiCalls 扫描范围从 src/api 扩到全部 .vue/.js/.ts 的 axios/fetch 调用（状态机前端证据变全），设上限 | VueAnalyzer.extractApiCalls |
| **A18** | 状态启发式扩展：字段名 status/state/type 且 Integer/String 时查注释与同类常量；constants 启发式加类名语义过滤 | SpringAnalyzer + StateMachineAgent |
| **C2** | 生成前证据链对账：①时间戳新鲜度（需求资料 vs 代码分析/状态机，过期侧 SSE 提示+prompt 标注）②PRD 状态流 vs 代码状态机状态相似度匹配，冲突项 prompt 显式标注"以代码为准，需人工确认" | OrchestratorAgent + TestGeneratorAgent（注入 evidenceConsistency） |
| **A14/C3** | 风险清单正式关闭：A14 大半已由 G16 覆盖（剩余随 L4b 延后）；C3 不确定性已由 A9+A15 消除（剩余 temp 属设计值） | 仅文档 |

### Out of Scope

- 延后区全部：L7 L9 L10 L11 A4b A16（效率成本 > 准确率收益，维持用户既定原则）
- E11 杂项（P3）、R11 长期方案（路由亲和/对象存储）、L4b（PRD 分段解析）、L3 长期项（PRD 解析重评估 thinking）
- rule-N 编号的同款稳定化（G7 范围仅 requirement，rules 顺序随分析结果持久化已相对稳定，需要时后续版本处理）

## 三、功能细节

### 3.1 G7 需求 ID 稳定化

- 新 id 格式：`req-` + SHA-256(title + '\u0001' + description) 前 10 位十六进制；RAG 并入项 `rag-` + 同款 hash。
- 同一需求内容在任意解析顺序/轮次/任务中 id 一致；PRD 局部修改只影响变更项的 id。
- 存量数据：旧 `req-N` refs 不迁移，与新 checklist 不匹配（与现状"顺序漂移即失配"一致，无回退风险），CHANGELOG 注明。
- prompt 中 `requirementIds 用 "req-N"` 的表述（内联常量 + test-generation-prd-footer.md / ai-review.md / test-generation-code-footer.md）改为"原样使用 coverageChecklist.requirements[].id"。

### 3.2 G19 索引维护移出热路径

- 删除 OrchestratorAgent.loadGenerationContext 中的 `ensureRequirementContexts` 调用（约 1 行 + 注释）。
- 保存侧已有四条路径触发重建（调研确认）：updatePrd / uploadPrdPdf / fetchPrdUrl / updateProjectContext。
- 已知行为变化：v6.4 之前保存且从未重新保存过需求资料的存量项目，首次生成不再自动补建索引——检索返回空走既有优雅降级（不影响生成主流程），重新保存一次需求资料即重建。CHANGELOG 注明。

### 3.3 G8 流式单解析真源

- StreamingTestCaseParser 新增用例收集列表；`generatePrdRound` 流式分支直接返回 parser 收集结果，删除"完整响应重解析 + parsedCount 索引补推"路径。
- 保留兜底：parser 解析数为 0 但全量重解析有结果时（数组起点检测失败等边角），回退全量解析并推送全部。
- L8 截断抢救（finish/局部补全）行为不变。

### 3.4 C2 证据链对账

- 新鲜度：project.updatedAt 晚于最新 code_analysis/state_machines createdAt → SSE 进度提示"需求资料在代码分析后有更新，代码上下文可能过期，建议重新分析" + prompt 标注。project.updatedAt 是项目任意编辑时间，存在误报可能——提示语义为"建议"非"阻断"，可接受。
- 一致性：PRD stateFlows 各流程的 states 与代码状态机 states（code/name）做归一化包含匹配；某 PRD 状态流在所有代码状态机中无任何状态命中 → prompt 注入 `evidenceConsistency` 备注："PRD 状态流「X」在代码状态机中无对应状态（PRD: A/B/C），以代码为准，需人工确认"。
- 注入位置：generatePrdRound 的 context map（每轮都带，成本低）。

### 3.5 R4/R5 评审语义

- R4：首轮评审 LLM 返回后，`byIndex` 用 putIfAbsent 收录（重复 index 保留首个）；缺失 index 的用例子集二次送评（子集规模减半，截断概率大幅下降）；补评后仍缺 → log.warn + 保留该用例（维持安全默认：未评审≠删除）。
- R5：reject 比例三分带（>70% 全保留+告警 / 40–70% 置信度≥0.75 才删 / ≤40% 照删），消灭"恰好一半真垃圾全留"的盲区。

### 3.6 R13+G18 失败经验库治理

- 入库：稳定 ID = `fail-` + SHA-256(projectId + 归一化 action + 归一化 error) 前 16 位；写入前 deleteByIds 同 ID（同源失败覆盖不堆积）。
- 语料：`[用例标题 | 页面URL |] action -> error`（标题/URL 缺省跳过），向量相似度从"需求 vs 动作"改善为"需求 vs 标题+动作"。
- 查询：retrieveFailures 改用失败专用查询——前 6 条需求查询 + 操作/页面关键词后缀；embedding 调用数从最多 12 降到 6（顺带提效）。

## 四、验收标准

1. `mvn compile` BUILD SUCCESS；`npm run build` 成功（前端无代码变更，回归构建）。
2. 单测新增 ≥ 6 个文件，覆盖：G7 id 稳定性、G8 流式单解析、G13 置信度派生、R4 缺评补评/去重、R5 三分带、L12 唯一最高分、A3 多异常、A6 过滤、A18 启发式、C2 对账、R13 语料/ID。全量 `mvn test` 通过。
3. 风险清单：19 项全部标记 ✅（A14/C3 注明关闭依据），v7.10 计划行补入 G7/G19。
4. CHANGELOG/README 按 4.x 惯例更新。

## 五、风险与对策

| 风险 | 对策 |
|---|---|
| G7 旧 req-N refs 与新 hash id 不匹配（覆盖率口径断代） | 与现状"顺序漂移即失配"等价，无回退；CHANGELOG 明示口径切换 |
| G19 存量项目首生成无索引 | 检索空走优雅降级；重新保存需求资料即重建；CHANGELOG 注明 |
| G12 第 2+ 轮去掉原文后生成质量下降 | 保留结构化 prd 摘要/checklist/gaps/已生成摘要，LLM 所需信息完整；轮次上限不变 |
| R5 灰区置信度裁决依赖 LLM 自报 confidence | confidence 缺省 0.5（低），宁保守保留；与 R1 分级采纳同一信任模型 |
| A6 过滤激进（IllegalArgumentException 类规则被滤） | 风险清单既定口径（参数断言=噪音）；过滤量进 warnings 可观测；LLM 增强仍可补规则 |
| A12 全量扫描解析时间上升 | 纯规则层正则；设条目上限 + 排除 node_modules；换取状态机证据完整性 |

## 六、交付物清单

- [x] PRD（本文件）
- [x] 后端技术评审 v7.10
- [x] 前端技术评审 v7.10（无代码变更，回归构建）
- [x] 后端实现 + 单测（新增 10 个测试类 54 用例）
- [x] CHANGELOG / README / 风险清单 / 迭代计划更新
- [x] git commit + push
