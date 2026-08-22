# PRD v7.2 — 度量与报告诚实化

**日期**: 2026-08-23
**基线**: v7.1
**对应风险清单**: R6 / R10 / R2 / R8 / R9 / R12（评审与报告/覆盖率 6 项，含 1 个 P0）
**改动范围**: 后端为主 + 前端小改（skipped 状态展示）

---

## 1. 背景与问题

v7.0 修复了执行链路的"结果不可信"，v7.1 修复了生成链路的"结果不一致"。v7.2 聚焦**度量与报告层的"数字不可信"**——用户在仪表盘、执行报告、覆盖率统计里看到的数字，要么是从未实现的假数据，要么口径自相矛盾：

| 编号 | 级别 | 问题 | 用户可感知现象 |
|---|---|---|---|
| R6 | P0 | 仪表盘 apiRate 声明后从未赋非零值，avgApiRate 恒 0 | 前端虽未展示，但 API 契约里存在一个假字段，任何调用方拿到 0 都会被误导 |
| R10 | P1 | 全 skipped 用例状态记 passed（skipped 不影响状态判定）+ 报告 passRate 分母含 skipped | 10 步全跳过的 api_call 用例拿"passed"徽章，报告却写"通过 0/失败 0/跳过 10, 通过率 0%"——**同屏自相矛盾**；分母含 skipped 与业界惯例（Allure 不含）不一致 |
| R2 | P1 | mergeCoverageRefs 评审 refs 非空类直接覆盖，是替换不是合并 | 生成阶段正确的 requirementIds 可能被评审 LLM 的不完整返回整体替换丢失 |
| R8 | P1 | getCoverageMatrix 在 transition×testCase 双重内循环里反复反序列化同一条用例的 JSON | 50 状态机×20 转换×500 用例 = 覆盖率页一次 50 万次 JSON parse；仪表盘逐项目调用进一步放大 |
| R9 | P2 | 项目间覆盖率简单平均，2 条转换的小项目与 200 条的大项目同权重 | 平均覆盖率被小项目噪声拉偏，度量失真 |
| R12 | P3 | 报告 footer 硬编码"v2.4 报告"（实际已迭代到 v7.x）；batch 报告 passRate 分母含 running/pending | 版本误导；报告生成瞬间还有任务在跑时通过率被稀释 |

## 2. 目标

1. **不呈现假数据**：删除从未实现的 apiRate/avgApiRate 假字段（R6）
2. **状态与数字同口径**：全 skipped 用例记 `skipped` 状态，不再挂 passed 徽章；passRate 分母统一为"已判定"（passed+failed），跳过/运行中/待执行不计入（R10/R12）
3. **合并不丢数据**：评审 coverageRefs 改为并集合并，生成阶段的正确引用不再被覆盖（R2）
4. **度量提效**：覆盖率矩阵每条用例只解析一次 JSON，双重循环内只做集合查找（R8）
5. **加权平均**：平均状态机覆盖率按转换总数加权（R9）
6. **版本真实**：报告 footer 版本号收敛为单一常量（R12）

## 3. 方案要点

### 3.1 R6 删除假 apiRate 字段（StatsController）
- 前端仪表盘从未展示 apiRate/avgApiRate（grep 确认仅用 avgStateRate/stateRate）
- 真实接口覆盖率的分母（checklist endpoints）仅存在于生成时的 coverage map，未持久化，仪表盘上下文拿不到 → **删字段是诚实的选择**，而非再造一个口径不全的假统计
- 同步删除 per-project `apiRate` 与全局 `avgApiRate`

### 3.2 R10 状态判定与通过率口径
- `ExecutionService` 两处收尾（Agent 执行路径与程序化执行路径）状态判定统一收敛为 `determineStatus(passed, failed, skipped)`：`failed>0 → failed`；`passed==0 && skipped>0 → skipped`（新增）；否则 `passed`
- summary 保持"通过 X, 失败 Y, 跳过 Z"格式，全跳过时状态列直观可见
- `ReportService.generateExecutionReport`：passRate 分母改为 passed+failed；skipped>0 时在通过率单元格追加"（跳过 N 步未计入）"说明
- `ReportService.generateBatchReport`：passRate 分母改为 passed+failed（running/pending/取消不计入）；汇总表新增"已跳过"行；通过率单元格追加说明
- 用例执行状态回写：全跳过执行将 `executionStatus` 回写为 `skipped`，前端展示"已跳过"

### 3.3 R2 mergeCoverageRefs 改并集（TestCaseReviewAgent）
- 四类 id（requirementIds/transitionIds/endpointIds/ruleIds）均改为**保序并集**：existing 在前、review 新增在后，去重
- endpointIds 的合法性过滤不受影响（applyEndpointMatching 在 LLM 评审后仍会按 checklist 重校验）

### 3.4 R8 覆盖率矩阵预解析（CoverageService）
- 循环外预解析：每条用例的 `executionHints.coverageRefs.transitionIds` 与（已执行用例的）`stateMachineRef.transitions` 各解析一次，存入 `Map<caseId, Set<String>>`
- 双重循环内只做集合 `contains` 查找
- 语义不变：兜底路径（isExecuted 才看 stateMachineRef）的判定标准保持，只是从"边循环边解析"变为"预解析后查找"

### 3.5 R9 加权平均（StatsController）
- `avgStateRate = Σ(rate_i × totalTransitions_i) / Σ(totalTransitions_i)`，totalTransitions=0 的项目不参与加权（无转换则无度量意义）
- 数据来源即 coverage matrix summary 已有的 totalTransitions，零新增查询

### 3.6 R12 报告杂项（ReportService）
- footer 版本收敛为单一常量 `APP_VERSION = "v7.2"`，两处 footer 共用（后续迭代只改一处）

## 4. 验收标准

1. 单测：
   - `determineStatus`：全 skipped → skipped；有 failed → failed；有 passed 无 failed → passed；无步骤无错误 → passed（保持既有语义）
   - `passRateOf`：分母为 passed+failed；全 skipped → 0 且调用方展示说明
   - `mergeCoverageRefs`：existing 与 review 的并集、去重、保序；review 为空不覆盖
   - CoverageService：预解析后 covered/testCaseIds 与旧逻辑等价（coverageRefs 命中 + 已执行 stateMachineRef 兜底命中）
   - StatsController：加权平均正确（大项目权重高）；响应中无 apiRate/avgApiRate 字段
2. 回归：既有 100 个测试全绿
3. 前端：执行状态"已跳过"在用例列表/执行历史/执行详情/用例卡片四处正确展示与筛选；npm run build 通过

## 5. 风险与权衡

- **新增 `skipped` 执行记录状态** → 前端五处状态映射同步补齐（TestCaseList/TestCaseCard/ExecutionHistory/ExecutionResult + 筛选下拉）；`agentTaskService` 侧 skipped 视为任务正常完成（succeed），只有 failed/异常才 fail；失败经验入库仍只在 failed 时触发，不受影响
- **passRate 口径变化** → 与 Allure 等业界惯例对齐（跳过不计分母）；旧报告是即时生成的 HTML，无存量数据迁移问题
- **删除 apiRate/avgApiRate 字段** → grep 确认前端无消费方；属于 API 契约收窄，在 CHANGELOG 中明确标注
- **R8 预解析** → 已执行用例的 stateMachineRef 全量预解析（旧逻辑是懒解析），理论上多解析少量"未被兜底命中"的用例 JSON，但换来双重循环内零反序列化，净收益为正
