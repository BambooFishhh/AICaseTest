# 变更记录 (CHANGELOG)

本项目迭代基于 v1.0 MVP，目标演进为高可用、AI 可执行、高可视化的 AI 用例生成系统。

---

## v1.2 — 用例生成质量增强

**日期**: 2026-08-09
**基线**: v1.1
**迭代主题**: 提升用例生成质量——分模块精准生成、去重、覆盖率度量、质量评分

### 改动清单与目的

#### 后端

| 文件 | 改动 | 目的 |
|------|------|------|
| `agent/TestGeneratorAgent.java` | 重构 `generate()` 为分模块生成：按状态机逐个调用 LLM，单模块失败仅回退该模块；新增 `deduplicate()`（标题相似度去重，保留质量更高者）、`calculateQualityScore()`（结构完整度 0-100 评分） | 单次聚焦提升质量、避免 token 超限、单点失败隔离；消除重复用例；量化用例质量 |
| `entity/TestCase.java` | 新增 `qualityScore` 字段（Integer） | 持久化质量评分 |
| `dto/TestCaseDTO.java` | 新增 `qualityScore` 字段 + `from()` | 向前端透传质量分 |
| `dto/TestCaseListResponse.java` | 新增 `coverage` 字段（Map） | 随列表响应返回覆盖率 |
| `service/TestCaseService.java` | `listTestCases()` 增加覆盖率计算；新增 `calculateCoverage()`（状态转换覆盖率 + 接口覆盖率 + 类型分布） | 让用例质量可度量、可视化 |

#### 前端

| 文件 | 改动 | 目的 |
|------|------|------|
| `views/TestCaseList.vue` | 新增覆盖率面板（状态转换/接口覆盖率进度条）；表格新增"质量"列（进度条） | 质量可视化，用户直观感知覆盖率与用例质量 |
| `components/TestCaseCard.vue` | 元信息区新增"质量评分"进度条 | 详情中展示单用例质量分 |

### 验证
- 后端编译：`mvn compile` BUILD SUCCESS（58 源文件）
- 前端构建：`npm run build` 成功（19.40s）

### 下一步（v1.3 规划）
- AI 用例执行引擎：基于 structuredSteps + executionHints 自动调用 API 执行用例
- 执行结果存储与 executionStatus 状态流转

---

## v1.1 — 结构化可执行用例（Executable Test Case Spec）

**日期**: 2026-08-09
**基线**: v1.0
**迭代主题**: 让测试用例从"自然语言文档"升级为"AI 可读可执行的结构化剧本"

### 改动总览

本次迭代为后续"AI 执行用例"打地基：用例数据模型新增结构化字段，生成逻辑升级以产出结构化步骤、关联 API、真实填充状态机引用，前端升级为结构化步骤卡片展示。**不实现实际执行**（留待 v1.2）。

### 改动清单与目的

#### 后端

| 文件 | 改动 | 目的 |
|------|------|------|
| `entity/TestCase.java` | 新增 5 个字段：`structuredSteps`、`apiEndpoints`、`testData`、`executionHints`、`executionStatus` | 让用例携带 AI 执行所需的操作目标、测试数据、执行方式、执行状态，从"文档"变为"可执行剧本" |
| `dto/TestCaseDTO.java` | 新增对应字段及 `from()` 转换 | 向前端透传结构化数据 |
| `dto/UpdateTestCaseRequest.java` | 新增对应可选字段 | 支持通过 API 更新结构化字段 |
| `service/TestCaseService.java` | `updateTestCase()` 补充新字段更新逻辑 | 编辑能力覆盖新字段 |
| `agent/TestGeneratorAgent.java` | LLM prompt 升级要求生成结构化步骤/API关联/执行提示/状态机引用；规则回退也填充新字段；新增 `buildStateMachineRef`/`matchEndpoints`/`buildForbiddenTransitions` 辅助方法 | 生成的用例结构上具备被 AI 执行的条件，并真实关联状态转换与接口端点 |

**核心设计**：
- `structuredSteps`：每步含 `order/action/target/expected/data/type`，`type` 标注 `api_call|ui_action|state_assert|manual`，AI 可据此选择执行方式
- `executionHints.approach`：标注推荐执行方式，为 v1.2 执行引擎提供决策依据
- `stateMachineRef`：状态机用例真实关联 states/transitions/forbiddenTransitions，支撑覆盖率度量
- 向后兼容：旧字段 `steps/preconditions/expectedResults` 仍填充，XMind 导出与列表展示不受影响

#### 前端

| 文件 | 改动 | 目的 |
|------|------|------|
| `components/TestCaseCard.vue` | 查看模式新增：结构化步骤卡片（步骤-目标-预期-数据配对）、关联接口标签、执行提示 alert、测试数据表格、执行状态标签；含回退兼容（无 structuredSteps 时回退纯文本列表） | 提升人类阅读友好度，"步骤-预期"配对可视化；同时直观呈现 AI 可执行性 |

#### 文档

| 文件 | 说明 |
|------|------|
| `docs/v1.1/PRD_v1.1_结构化可执行用例.md` | 本次迭代产品需求文档 |
| `docs/v1.1/后端技术评审_v1.1.md` | 后端技术评审（标注 v1.1 版本） |
| `docs/v1.1/前端技术评审_v1.1.md` | 前端技术评审（标注 v1.1 版本） |
| `docs/CHANGELOG.md` | 本文件 |

### 向后兼容

- v1.0 已有用例（无新字段）前端正常展示：`structuredSteps` 为空时回退纯文本 `steps` 列表
- H2 ddl-auto=update 自动加列，无需迁移脚本
- API 端点不变，响应体向后兼容扩展

### 验证

- 后端编译通过：`mvn compile`（JDK 17，58 源文件，BUILD SUCCESS）
- 前端构建验证：`npm run build`

### 下一步（v1.2 规划）

- AI 用例执行引擎：基于 `structuredSteps` + `executionHints` 自动调用 API 执行用例
- 执行结果存储与 `executionStatus` 状态流转
- 执行结果报告与可视化
