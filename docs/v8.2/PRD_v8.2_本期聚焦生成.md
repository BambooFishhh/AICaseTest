# PRD v8.2 — 本期聚焦生成

> 版本：v8.2（2026-08-24）
> 前置依赖：v8.1 范围感知基础（scope_definition/scope_item、GitDiffService、确认机制）
> 系列规划：v8.1 范围基础设施 ✅ → **v8.2 本期聚焦生成** → v8.3 覆盖率口径重构

## 1. 背景与问题

v8.1 已能识别并确认"本期范围"，但生成链路仍然：
- 目标集合是全项目接口/转换（checklist 不区分本期与历史）
- LLM 会为历史功能生成用例（稀释本期验收）
- 用例不知道"如何把系统带到所需的前置状态"——本期功能往往依赖历史状态（如发货依赖订单已支付），LLM 只能凭空猜或遗漏
- 执行时无法区分"准备步骤失败"（环境/前置不满足）与"验证失败"（用例本身失败）

## 2. 目标（用户已确认的决策）

1. **生成目标只聚焦本期**：有已确认范围的项目，用例目标（coverageRefs 可引用项）收敛到范围内接口与本期变更转换
2. **历史代码只作为上下文**：仅允许出现在前置条件文本与 setup 准备步骤中，禁止作为断言目标
3. **确定性推导 setup 步骤**：BFS 在状态机图上求"初始态 → 目标转换源状态"的最短路径（优先走历史转换边），LLM 只负责填充数据不再负责找路径
4. **步骤级 phase 标记**：structuredSteps 增加 `"phase": "setup" | "verify"`
5. **blocked 语义**：执行时 setup 失败 → 整条记 blocked（非 failed），跳过后续验证步骤，报告单独统计
6. **生成前置校验升级**（破坏性变更）：代码驱动项目（有 sourcePath 且已完成过代码分析）必须先创建并确认本期范围才能生成；纯 PRD 项目不受影响

## 3. 需求详述

### 3.1 状态机切片（ScopeSlicingService）

对范围内每个 STATE_MACHINE 条目，将其 transitions 二分：

| 分类 | 判定 | 用途 |
|---|---|---|
| sprintTransitions（本期目标） | 该转换存在证据 `{field,from,to,file}` 且 file ∈ changed_files | 每个都必须产出用例（正向+异常+非法转换） |
| historicalTransitions（历史上下文） | 其余 | 仅作为图边参与可达性推导与前置约束说明 |

### 3.2 BFS setup 路径推导

- 图节点：SM 状态码（剥枚举前缀+小写归一）；图边：除目标转换本身外的全部转换（历史边优先标记）
- 起点：初始状态；终点：sprint 转换的 from 状态
- 输出 setupHints：`[{transition: "from->to", stateMachine, steps: ["通过『trigger』将订单置为状态X", ...]}]`
- 不可达时不产出该目标的 hint（LLM 按 preconditions 自行处理）

### 3.3 生成链路注入（TestGeneratorAgent）

- **checklist 过滤**：slice 存在时，coverageChecklist.endpoints 仅含范围目标接口；transitions 仅含 sprint 转换——coverageRefs 对账天然被限制在本期集合内
- **context.scope 注入**：`{name, baselineRef, targets:{endpoints,transitions}, historicalTransitions, setupHints}`
- **stateMachines 上下文改造**：范围内 SM 的每条转换标注 `"role": "本期目标"/"历史上下文"`；范围外 SM 整体不进 prompt（其业务规则仍经 businessRules 注入）
- **prompt 约束新增**：目标必须落在本期范围；历史元素只能出现在 preconditions 或 phase=setup 步骤；coverageRefs 只允许引用本期目标
- **structuredSteps schema**：增加 `"phase": "setup"|"verify"` 字段说明 + 含 setup 步骤的示例

### 3.4 执行 blocked 语义（ExecutionService）

- 两个执行循环（Agent/程序化）解析 `step.phase`
- setup 步骤失败（含异常兜底分支）→ 终止后续步骤，记录终态 `status="blocked"`，summary 标注"前置准备失败"
- blocked 不写入失败经验库（非用例本身缺陷）；统计口径增加 blocked 维度；HTML 报告展示

### 3.5 前置校验升级

`ProjectService.triggerGenerate` 与 `TestCaseService` 三条生成入口（全量/流式/追加）：sourcePath 非空且已有代码分析结果的项目，无 confirmed 范围时报 `请先创建并确认本期范围`。纯 PRD 项目维持原行为。

### 3.6 连带改进（v8.1 补丁）

`ScopeService.createDraft` 对非 Git 仓库不再直接拒绝——创建空草稿并提示手动添加条目（否则纯 PRD 项目永远无法建立范围）。

## 4. 验收标准

1. 有 confirmed 范围的项目生成：checklist.endpoints/transitions 只含本期项；context.scope 出现 setupHints
2. 无 confirmed 范围的代码驱动项目触发生成 → 明确报错；纯 PRD 项目可正常生成
3. 生成的用例 structuredSteps 中历史流程步骤带 `phase:"setup"`，断言步骤为 verify
4. 执行中 setup 步骤失败 → 记录 blocked、后续步骤不执行、失败经验库无污染
5. `mvn compile` + `npm run build` 通过

## 5. 影响范围

| 层 | 文件 |
|---|---|
| 后端新增 | service/ScopeSlicingService.java（含 ScopeSlice record） |
| 后端修改 | OrchestratorAgent、TestGeneratorAgent（overloads/checklist/context/prompt）、ProjectService、TestCaseService、ExecutionService、ReportService、ScopeService |
| 前端修改 | TestCaseCard.vue（phase 徽标）、执行相关视图（blocked 文案） |

## 6. 风险与权衡

- **破坏性变更**：存量代码驱动项目需先建范围——已在方案评审中获得确认
- **切片误判**：证据文件匹配是启发式；错误分类只影响"目标/上下文"角色划分，人工可在范围页调整 STATE_MACHINE 条目增删兜底
- **setup 步骤可执行性**：BFS 给出的是路径骨架（trigger 文案），具体操作由 LLM 结合前端上下文物化；执行失败记 blocked 可观测
