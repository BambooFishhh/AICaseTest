# PRD v7.14 — 生成 Prompt 重复注入治理

> 版本：v7.14 | 基线：v7.13 | 类型：缺陷修复（P1）
> 一旦确定尽量不要轻易改动

---

## 1. 背景

v7.13 扩容后用户在真实大项目（220 接口 / 182 规则 / 63 依赖）生成时命中 300k 保险丝：

```
[LLM] prompt 超限 432497 → 截断到 300000
[G17] backend context filtered by requirement keywords: endpoints 220/220, rules 182/182
```

432KB 的构成（用户实测拆解）：

| Prompt 块 | 实际大小 |
|---|---|
| context.endpoints（220 个完整接口详情） | 128,562 |
| context.businessRules（182 条完整规则） | 50,341 |
| context.operationDependencies（63 条依赖） | 19,364 |
| coverageChecklist.endpoints（**150 个完整接口再注入一遍**） | 89,259 |
| coverageChecklist.businessRules（**182 条规则再注入一遍**） | 50,341 |
| coverageChecklist.operationDependencies（**依赖再注入一遍**） | 19,364 |
| 前端 forms/selectors/states/flows | 27,131 |
| PRD/需求文档/状态机/覆盖缺口等 | 其余约 48k |

### 根因（已核实代码）

1. **G24 coverageChecklist 全量详情重复注入**：`buildCoverageChecklist` 对每个接口执行 `item.putAll(ep.toContextMap())`，把 requestBody/responseBody/businessLogic/参数/异常完整字段塞进覆盖清单；同一内容已在 `context.endpoints`/`context.businessRules`/`context.operationDependencies` 完整出现过一次。**约 159KB 纯冗余**。checklist 的三个消费方（remainingGaps、TestCaseReviewAgent、prompt 注入）实际只用 `id/method/path` 级摘要字段。
2. **G25 context.endpoints 无容量控制**：G17 过滤阈值为 `scoreTextOverlap > 0`（任一 ≥2 字符 token 重叠即通过），大项目需求词汇覆盖广 → 220/220 全放行后**全量完整详情**进 prompt。G17 定位是"挡明显无关"的弱过滤层（宁多勿丢），但没有第二层总量控制。
3. **E17 embedding HTTP 404**：embedding 默认端点复用 chat 网关（`opencode.ai/zen/go/v1`）+ 模型 `qwen3.7-text-embedding`——该网关不提供此 embedding 模型，必然 404。RAG 检索持续降级为空，前端组件/需求相关上下文质量下降。
4. 附带发现：`context.prd` 序列化整个 PrdAnalysisResult，其中 `ragContexts` 原始切片全量携带，而策展版（truncateStrings 6×1200）已单独注入——又一处重复。

## 2. 范围

### In Scope

1. **G24 checklist 摘要化**：endpoints 项只留 `{id, method, path, function}`；rules 项只留 `{id, ruleType, rule(截80)}`；dependencies 项只留 `{id}`；components 项只留 `{id, component, summary(截80)}`。requirements/transitions 保持原样（无重复注入且 gap 计算依赖 title/description）。
2. **G25 context.endpoints/businessRules 容量控制**：G17 过滤后超过上限（endpoints 80 / rules 100，`app.generation.*` 可配）时按相关性分数排序保留 top-N + 追加截断说明条目；keywordText 为空时保序截断。
3. **E17 embedding 默认端点修正**：默认 base-url 改为 DashScope 兼容端点（不再回落 chat 网关），默认模型改 `text-embedding-v4`（1024 维与 Milvus 默认一致）；失败日志带模型名诊断信息。
4. **prd map 剥离 ragContexts**：策展版已单独注入，原始切片不再随 prd 序列化重复携带。
5. 单测：checklist 摘要字段断言 + 容量控制排序/截断说明/空关键词保序。

### Out of Scope

- G17 阈值调整（保持 > 0 弱过滤语义，总量由 G25 分层控制——两层各司其职）
- boundPrompt 保险丝逻辑（保持现状，触发即 ERROR 日志）
- prd map 内 requirements/frontendComponents 的重复（语义有差异，48K 量级可接受）
- 大项目分批增强/map-reduce（`docs/大项目代码分析演进提案.md` v8.x 候选）

## 3. 功能详情

### 3.1 checklist 摘要化（G24）

```java
// endpoints：{id, method, path, function}——覆盖对账与评审只需要标识字段，
// 完整详情已在 context.endpoints（top-N 全量详情）+ G24 前的注入路径存在
// rules：{id, ruleType, rule 截 80}——rule 文本截断保识别度
// dependencies：{id}（= operation 全名，依赖对账键）
// components：{id, component, summary 截 80}
```

消费方影响分析（已逐一核实）：
- `remainingGaps`：只用 id（requirements 例外，用 title/description 做 G4 兜底匹配——保持原样）✓
- `TestCaseReviewAgent.review`：readEndpoints 只读 `id/method/path`；评审 payload 同步瘦身（顺带收益：评审 prompt 从同样的膨胀中解放）✓
- prompt 注入（capChecklistForPrompt）：150 条上限保留为最后防线，作用于摘要项 ✓

### 3.2 context 容量控制（G25）

- G17 过滤后 `relevantEps.size() > 80`：按 `scoreTextOverlap(endpointText(ep), keywordText)` 降序稳定排序，保留前 80，尾部追加说明条目 `{"note": "..."}`；keywordText 空白时按原序截断。
- rules 同构，上限 100。
- 配置：`app.generation.endpoints-context-max`（默认 80）、`app.generation.rules-context-max`（默认 100），@Value 字段初始化默认值兜底（单测直接 new）。
- 未入选接口仍以摘要形式存在于 coverageChecklist（全量 id/method/path/function），LLM 可引用其 id 生成用例，只是拿不到 request schema 详情。

### 3.3 embedding 端点修正（E17）

```yaml
spring.ai.openai.embedding:
  base-url: ${LLM_EMBEDDING_BASE_URL:https://dashscope.aliyuncs.com/compatible-mode/v1}
  options.model: ${LLM_EMBEDDING_MODEL:text-embedding-v4}
```

- 不再回落 `LLM_BASE_URL`（chat 网关无 embeddings 路由，回落=404）
- `text-embedding-v4` 默认 1024 维 = Milvus 集合默认维度 ✓
- `EmbeddingService` 失败日志注入模型名（`@Value` spring.ai.openai.embedding.options.model），404/401 一眼可判是端点还是密钥问题
- 同步：application-prod.yml、docker-compose.yml 默认值、.env.example

### 3.4 prd map 剥离 ragContexts

```java
Map<String, Object> prdMap = objectMapper.convertValue(prdResult, Map.class);
prdMap.remove("ragContexts");   // 策展版 context.ragContexts 已单独注入（6×1200 上限）
context.put("prd", prdMap);
```

prompt 模板已核实只引用顶层 `ragContexts` 键，无 `prd.ragContexts` 路径引用。

## 4. 验收标准

1. `mvn test` 全量通过，新增测试：
   - checklist endpoints 项不含 requestBody/responseBody/businessLogic 等详情字段，含 id/method/path/function
   - checklist rules 项 rule 文本超 80 字符截断；dependencies 项仅 id
   - 220 个 endpoint 时 context.endpoints 保留 80 条（相关性降序）+ 说明条目；关键词空白时保序截断
   - checklist requirements/transitions 结构不变（回归保护）
2. 用户实测场景复算：432KB → 约 200KB（159K 冗余消除 + endpoints/rules 容量控制），300k 保险丝不再触发
3. embedding 配置默认值生效路径可解析（配置项单测或启动验证）
4. `npm run build` 通过（前端零改动回归）

## 5. 风险与缓解

| 风险 | 评估 | 缓解 |
|---|---|---|
| 未入选 top-80 的接口缺 request schema 详情，相关用例步骤精度下降 | 接口仍在 checklist 摘要中可引用；生成上限本就约束用例数 | 上限可配（调大 endpoints-context-max）；G21 补测循环以 checklist 全量 id 对账 |
| checklist 摘要化后 LLM 无法从 checklist 拿规则全文 | 规则全文在 context.businessRules（top-100 完整）；checklist rule 截 80 字保识别 | 评审/gap 计算不受影响（只用 id） |
| embedding 默认端点变更影响已部署实例 | 显式设置 LLM_EMBEDDING_BASE_URL 的实例不受影响；未设置的现状就是 404（本来不可用） | .env.example 给出配置指引 |
| 评审 payload 同步瘦身改变评审行为 | 评审对账逻辑只用 id/method/path（已核实），瘦身纯减负 | 评审相关既有测试回归 |

## 6. 交付物

- [ ] `docs/v7.14/PRD_v7.14_生成Prompt重复注入治理.md`
- [ ] `docs/v7.14/后端技术评审_v7.14.md`
- [ ] 后端实现 + 单测
- [ ] CHANGELOG / README / 风险清单（G24/G25/E17）/ 迭代计划更新
- [ ] git 提交推送
