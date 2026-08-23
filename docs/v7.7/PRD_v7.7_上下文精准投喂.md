# PRD v7.7：上下文精准投喂

> 版本：v7.7（v7.x 系列第 8 版）
> 对应风险清单条目：G16 / G17 / G4 / A13 / L4a / A4a / A5 / G10
> 前置：v7.6（状态机与断言闭环）已完成

## 1. 背景与问题

v7.6 建立了断言与证据闭环，但生成侧的"投喂"仍存在系统性浪费与丢失——LLM 拿到的上下文要么太多（稀释注意力、费 token），要么太少（关键内容被截断丢弃）：

1. **RAG 补在错误的层（G16）**：检索回的需求类切片只贴 prompt 当"附加材料"，永远成不了"考点"——多轮补齐循环绕着 coverageGaps 转却从不瞄准 RAG 找回的内容。长 PRD 尾部需求（被 L4a 截断丢弃的部分）本就在 Milvus 里，却因不进考点清单而永远丢失。
2. **前端精筛、后端全量的不对称（G17）**：前端上下文按 RAG 命中组件过滤，后端 endpoints/businessRules/operationDependencies 却全量注入——大项目几百个无关接口稀释 prompt，费 token + 摊薄 LLM 注意力。
3. **多轮补齐"轮间失忆"（G4）**：第 2+ 轮 LLM 不知道已生成什么（只给 gaps），重复率高靠事后去重删；requirementIds 无兜底推断——LLM 不填则需求缺口永不收敛，白跑满轮次。
4. **大 PRD 解析失败阻断整个生成（A13）**：文档越长输出越易截断 → JSON 不闭合 → 解析异常 → v5.13 后 PRD 必需 → 生成被阻断，报错只有"JSON 解析失败"。
5. **PRD 截断系统性丢失尾部（L4a）**：单文档 12000 / 总量 24000 字符**尾部硬截断**——验收标准、边界条件、异常流通常在 PRD 后部，恰被丢弃。
6. **LLM 补充接口无源码校验（A4a）**：LLM 只见约 10 个文件片段，但 supplementalEndpoints 无源码存在性校验直接入库——看不全还要求"如实补充"，等于鼓励编造。
7. **规则层不提取端点参数（A5）**：@RequestParam/@PathVariable/@RequestBody 均未解析，"关联 API + 测试数据"核心卖点的数据基础缺失。
8. **覆盖缺口与 60 条上限不匹配（G10）**：大项目几百端点全进 prompt（token 膨胀），生成上限 60 条盖不完，用户看到大量"未覆盖"无解释。

## 2. 需求目标

### 2.1 G16 RAG 切片并入考点清单

**FR-1**：buildCoverageChecklist 时，把 `prdResult.getRagContexts()`（需求类切片）解析成候选需求：
- 候选标题 = 切片首个非空行（剥离 markdown 标记，截断 60 字符）
- 与现有 requirements（title+description）做 token 相似度比对（本地字符串匹配，复用关键词包含打分思路）；最高分低于阈值（无重叠）视为"截断丢失的新需求"
- 新候选以 `{id: "rag-req-N", title, description: 切片前 200 字符, source: "rag"}` 并入 checklist.requirements 与 gaps.requirementIds
- 上限 20 条（防噪声），LLM 去重（风险清单的"可选轻量 LLM 去重"）本期不做——本地相似度过滤已覆盖主要场景

### 2.2 G17 后端上下文按需求过滤

**FR-2**：generatePrdRound 注入后端上下文时，用需求关键词（requirements 标题+描述分词 + RAG 切片）过滤：
- endpoints：path 分词 + function + description 与关键词 token 包含打分，得分 > 0 保留
- businessRules：rule 文本 token 打分，同上
- operationDependencies：operation 类名出现在保留 endpoints 的 function 中则保留
- **兜底对称**：过滤后为空 → 全量注入（与前端 hasFrontendUi 兜底一致，宁多勿丢）
- stateMachines 保持全量（数量少、转换是核心考点）

### 2.3 G4 轮间摘要注入 + requirementIds 兜底

**FR-3**：第 2+ 轮 generatePrdRound 注入 `generatedCasesSummary`（已生成用例的 title+type 列表，每条截断 60 字符，上限 60 条），prompt 说明"以下场景已生成，不要重复"。

**FR-4**：remainingGaps 增加 requirementIds 兜底推断：用例 coverageRefs.requirementIds 为空时，用例 title 与考点 requirements（title+description）token 相似度匹配，最高分 ≥ 阈值则视为已覆盖——需求缺口真实收敛，不再依赖 LLM 自觉填写。

### 2.4 A13 大 PRD 解析失败降级重试

**FR-5**：PrdAgent 完整解析（modules+requirements+businessRules+stateFlows+entities）失败时：
- 自动降级重试一次：瘦身后 prompt 只要求 `modules + requirements + businessRules`（stateFlows/entities 是大块且可由代码侧 StateMachineAgent/SpringAnalyzer 提供）
- 降级成功 → 使用核心结果，日志告警"完整解析失败，已降级为核心需求解析"
- 降级也失败 → 抛出含文档长度与截断信息的明确错误（如"需求资料 24000 字符可能超出模型输出上限"）
- 降级结果同样走 v7.5 缓存（systemPrompt 不同自然分键）

### 2.5 L4a 头尾保留截断

**FR-6**：truncateDoc 单文档截断改为**头部一半 + 尾部一半**（各 MAX_PRD_LENGTH/2），中间插 `...(中略)...` 标记；总量截断同理（头尾各 MAX_TOTAL_DOC_LENGTH/2）——验收标准/边界条件在 PRD 后部不再被系统性丢弃。

### 2.6 A4a supplementalEndpoints 源码校验

**FR-7**：mergeSupplementalEndpoints 入库前校验（任一通过即收）：
- function 的类名部分（去掉包名后 `Class.method` 的 Class）与扫描到的 Java 文件类名（文件名去 .java）匹配
- path 以扫描到的控制器类级 @RequestMapping 前缀开头
- 校验失败 → 丢弃 + BackendResult.warnings 记录"LLM 补充接口未通过源码校验已丢弃: METHOD /path"（C1 可观测）

### 2.7 A5 规则层参数提取

**FR-8**：extractEndpoints 用 JavaParser 解析方法参数注解：
- `@RequestParam`：name/value 属性（缺省用参数名）、required（缺省 true）、defaultValue → `in=query`
- `@PathVariable`：name/value（缺省用参数名）→ `in=path`
- `@RequestBody`：参数类型写入 endpoint.requestBody，同时记 `in=body` 参数
- 产出 EndpointInfo.parameters（`[{name, in, type, required, defaultValue}]`），sources 含 "rules"

### 2.8 G10 覆盖缺口截断 + 上限明示

**FR-9**：gaps 各类 ID 列表设上限（requirementIds ≤ 40、endpointIds ≤ 80、其余 ≤ 60），超限截断并在 gaps 加 `truncated: true` 标记；checklist.endpoints 注入 prompt 上限 150 条（超限追加"另有 N 个端点未列出"说明）。

**FR-10**：多轮循环因 `all.size() >= MAX_GENERATED_CASES` 提前退出且仍有缺口时：
- GenerationReport 新增 `coverageCappedByLimit` 真实信号
- progressCallback 明示"已达生成上限(60)，剩余覆盖缺口未补齐"
- complete 事件携带 `coverageCappedByLimit`，前端可见"覆盖率受生成上限影响"

## 3. 非目标

- 不做 A14/L4b 分段解析 + 汇总（G16 已免费覆盖大半：尾部内容在 Milvus 切片里，并入清单即找回）
- 不做 RAG 候选需求的轻量 LLM 去重（本地相似度过滤起步，观察噪声率再决定）
- 不做 collectSourceSnippets 视野扩大（A4b，分批 LLM 调用效率成本大，延后）
- 不动态调整 MAX_GENERATED_CASES（容量规划另行评估，本期只做诚实明示）
- 不改前端界面（coverageCappedByLimit 字段暂在 complete 事件 JSON 中，前端展示随 v7.8）

## 4. 验收标准

1. 长 PRD（>12000 字符）解析时，文档尾部关键内容（如放在末尾的验收标准）出现在 requirementText 中
2. PRD 完整解析失败（模拟截断响应）时自动降级重试，返回核心需求结果而非阻断；两次失败才抛含长度信息的明确错误
3. LLM 返回的 supplementalEndpoints 中，function 类名/路径前缀对不上源码的被丢弃且 warnings 有记录；对得上的正常入库
4. 规则层分析的 endpoint 带 parameters（@RequestParam → query、@PathVariable → path、@RequestBody → requestBody 字段）
5. RAG 检索回的"新需求"切片出现在 coverageChecklist.requirements（id 形如 rag-req-N）与 gaps.requirementIds 中
6. 大项目生成 prompt 中 endpoints/businessRules 按需求关键词过滤（含兜底全量路径）
7. 第 2+ 轮 prompt 含 generatedCasesSummary；LLM 未填 requirementIds 时缺口仍能收敛（title 相似度兜底）
8. gaps 超 40/80 上限截断且带 truncated 标记；达 60 条上限退出时 complete 事件含 coverageCappedByLimit
9. `mvn compile` 与既有测试全部通过，新增单测覆盖以上逻辑

## 5. 影响范围

- 后端：PrdAgent、SpringAnalyzer、TestGeneratorAgent、TestCaseService（complete 事件）、GenerationReport
- 前端：无代码变更（回归构建）
- 数据库：无 schema 变更（gaps/checklist 为 prompt 内存结构；warnings 走既有 C1 字段）
