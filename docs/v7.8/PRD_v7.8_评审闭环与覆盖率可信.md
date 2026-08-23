# PRD v7.8 — 评审闭环与覆盖率可信

> 版本：v7.8（2026-08-23）
> 依据：代码审查风险清单 R1 / R3 / R7 / G6（B 攻坚区）
> 前置依赖：G2（v7.1 已修，评审报告链路）、R2（v7.2 已修，refs 并集合并）、G20层2（v7.3 已修，uiLanguageViolations 已入 hints）

## 1. 背景与问题

v7.7 完成上下文精准投喂后，生成侧输入质量已达标。但评审与度量侧仍存在四个"闭环断裂/数据失真"问题：

| 编号 | 问题 | 用户可见影响 |
|---|---|---|
| R1 | LLM 评审给出的 suggestedChanges（title/module/type/priority/coverageRefs 修正）从未被自动应用，"fix"与"pass"唯一区别是存了个标签 | 评审发现的问题原样入库，评审沦为"只诊断不治疗"；用户需逐条手动采纳 |
| R3 | endpoint 模糊匹配起始阈值 0.65，兄弟路径 `/api/order/cancel` 对编造路径 `/api/order/delete` 得分 0.867 即匹配成功 | LLM 编造的接口被记为覆盖真实接口，接口覆盖率虚高 |
| R7 | 覆盖矩阵主路径读 coverageRefs 不要求执行，兜底路径要求 isExecuted——两路标准不一致，输出只有一栏 rate | 用户把"计划覆盖 80%"当"验证过 80%"，覆盖度量语义失真 |
| G6 | 质量评分是纯"形式分"（字段填没填），无内容正确性维度；去重"保留高分者" | LLM 编造字段填满 = 高分，编造越全越容易挤掉真实用例 |

## 2. 目标

1. **R1**：高置信评审修正自动生效（coverageRefs/优先级），低置信保留人工确认入口
2. **R3**：endpoint 匹配收紧为"归一化后精确相等 + 高门槛模糊"，编造接口不再被洗白
3. **R7**：覆盖矩阵输出"计划覆盖 / 执行覆盖"双栏，度量语义诚实
4. **G6**：质量评分并入评审结论（ruleReview 通过 + llmReview 修改量），去重依据可信

## 3. 需求详述

### 3.1 R1 评审建议分级采纳

**规则**：

- `confidence ≥ 0.8` 且 suggestedChanges 含 `coverageRefs` → 与既有 refs **取并集**（复用 v7.2 R2 语义）后写回
- `confidence ≥ 0.8` 且 suggestedChanges 含 `priority` → 校验取值 ∈ {P0,P1,P2,P3} 后应用
- `title / module / type` 建议**不自动应用**（LLM 改写正文风险大于收益），保留前端"待人工确认"
- 已自动采纳的字段在 `aiReview.autoApplied` 数组中登记（如 `["coverageRefs","priority"]`），未采纳项留在 `suggestedChanges` 供前端展示

**边界**：

- confidence 缺失按 0.5 处理 → 不自动采纳
- coverageRefs 建议与既有 refs 冲突时不删除既有项（只增不减，与 R2 一致）
- 单用例重评路径（rerun）同样生效

### 3.2 R3 endpoint 匹配收紧

**规则**（matchEndpoint 两级）：

1. **精确匹配**：method 相同 且 归一化路径完全相等 → 匹配（无标记）
2. **高门槛模糊**：method 相同 且 路径 token 相似度 ≥ 0.9 且 双方 token 数一致 → 匹配并标记 `fuzzyMatch=true`
3. 其余一律不匹配

归一化沿用现状：小写、去 query、去尾斜杠、`{var}`/`:var` → `*`。

**产出**：模糊匹配到的 endpoint id 额外记入 `executionHints.fuzzyEndpointIds`，前端用例详情可提示"该接口引用为模糊匹配，请人工确认"。

**预期效果**：`/api/order/delete`（编造）vs `/api/order/cancel`（真实）token 相似度 0.667 < 0.9 → 不再匹配；`/api/order/{id}` vs `/api/order/*` 归一化后相等 → 精确匹配。

### 3.3 R7 计划/执行双栏覆盖率

**规则**（CoverageService.getCoverageMatrix）：

- **计划覆盖**（planned）：任一用例 coverageRefs.transitionIds 引用该转换（不要求执行）
- **执行覆盖**（executed）：isExecuted（passed/failed）的用例 coverageRefs.transitionIds **或** stateMachineRef.transitions 引用该转换
- 兜底路径（smRef）只计入执行覆盖——修复两路标准不一致

**输出结构**（每个 transition）：

- `covered` / `testCaseIds`：保持旧口径（refs 计划 ∪ 已执行 smRef 兜底，向后兼容）
- `planned`（bool）/ `plannedCaseIds`（数组）/ `executed`（bool）/ `executedCaseIds`（数组）

**summary**：

- `coveredTransitions` / `rate`：保持旧口径（向后兼容）
- `plannedCoveredTransitions` / `plannedRate` / `executedCoveredTransitions` / `executedRate`

**前端**（CoverageMatrix.vue）：

- 汇总区双进度条：计划覆盖 X% / 执行覆盖 Y%
- 表格"覆盖"列拆为"计划覆盖 / 执行覆盖"两列，三种状态样式：已执行（绿）、仅计划（黄）、未覆盖（红）

### 3.4 G6 质量评分并入评审结论

**规则**（calculateQualityScore）：

- 形式分（现有 6 项结构检查）× 0.7 → 最高 70 分
- 评审分（最高 30 分）：
  - `pass` → 30
  - `fix` → 30 − issues 数 × 5 − 未采纳建议字段数 × 5（下限 0）
  - 无评审记录（LLM 评审跳过/降级）→ 15（中性，不奖不罚）
  - confidence < 0.5 → 评审分减半（评审本身不可信）
- G20层2 扣分：`uiLanguageViolations` 每项 −3，上限 −9
- 总分钳制 0–100

**生效链路**：评审（L771）先于评分（L778）→ 评分时 aiReview 已在 hints 中；R1 自动采纳后的剩余建议数参与扣分（已采纳的不罚）。

**不做什么**：不接执行通过率回流（长期项，列入后续版本）；不改 SemanticService.deduplicateBatch 的保留逻辑（它读同一 qualityScore，自动受益）。

## 4. 验收标准

1. 构造 coverage + 用例引用编造接口 `/api/order/delete`，checklist 仅有 `/api/order/cancel` → 匹配结果为 null（旧逻辑返回 cancel 的 id）
2. 归一化相等的接口（含 `{id}` → `*`）正常匹配；高相似（token 数一致且相似度 ≥ 0.9）匹配且 fuzzyEndpointIds 有记录
3. confidence 0.85 + coverageRefs 建议 → refs 并集生效 + autoApplied 含 coverageRefs；confidence 0.6 → 不自动应用
4. priority 建议 P0（合法）应用；非法值（如 P9）不应用
5. 覆盖矩阵：未执行用例引用的转换 planned=true/executed=false；执行后（passed/failed）两者皆 true；summary 四个字段口径正确
6. 评分：同形式分的两条用例，pass 者分数 > fix（2 issues）者 > 无评审者；uiLanguageViolations 2 项扣 6 分
7. 全量 `mvn compile` + 相关单测通过；前端 `npm run build` 通过

## 5. 影响范围

| 层 | 文件 | 改动 |
|---|---|---|
| 后端 | TestCaseReviewAgent.java | R1 分级采纳；R3 匹配收紧 |
| 后端 | CoverageService.java | R7 双栏输出 |
| 后端 | TestGeneratorAgent.java | G6 评分改造 |
| 前端 | CoverageMatrix.vue | R7 双栏展示 |
| 前端 | TestCaseCard.vue | R3 fuzzy 提示（轻量） |
| 测试 | 新增 3 个测试类 + CoverageServiceTest | 全部规则回归 |

## 6. 风险与权衡

- **R1 自动采纳风险**：LLM 修正本身可能引入错误 → 只采纳并集型（coverageRefs）与枚举校验型（priority）修正，title/module/type 保持人工；confidence 门槛 0.8
- **R3 收紧副作用**：此前被模糊匹配"救回"的合法引用现在会失联 → 归一化已覆盖路径变量/大小写/尾斜杠，真实漏配属于应暴露的问题（诚实优先）
- **R7 向后兼容**：covered/rate 语义改为 planned（与现状主路径一致，兜底路径变化仅影响"执行过的用例 smRef 补充覆盖"场景）→ 前端同步双栏，无破坏
- **G6 评分口径变化**：所有用例分数整体下移（形式分 ×0.7）→ 去重比较是相对值，不受影响；前端展示分数无阈值逻辑
