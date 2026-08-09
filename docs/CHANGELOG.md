# 变更记录 (CHANGELOG)

本项目迭代基于 v1.0 MVP，目标演进为高可用、AI 可执行、高可视化的 AI 用例生成系统。

---

## v2.2 — 独立 MCP Server

**日期**: 2026-08-09
**基线**: v2.1
**迭代主题**: 拆分独立 MCP Server，后端改为 MCP 客户端接入

### 改动清单与目的

#### MCP Server（Node.js，新建）

| 文件 | 类型 | 说明 |
|------|------|------|
| `mcp-server/package.json` | 新建 | MCP Server 依赖配置（@modelcontextprotocol/sdk + openai） |
| `mcp-server/index.js` | 新建 | MCP Server 实现（stdio 传输，暴露 multimodal_element_locate 工具） |

#### 后端

| 文件 | 类型 | 说明 |
|------|------|------|
| `mcp/McpClient.java` | 新建 | Java MCP 客户端（ProcessBuilder + JSON-RPC 2.0 over stdio） |
| `service/McpBridgeService.java` | 修改 | 从直调 LlmService 改为通过 McpClient 调用 MCP Server |
| `application.yml` | 修改 | 新增 mcp.server 配置项 |

### 架构变更
```
v2.1: McpBridgeService → LlmService.chatWithImage() → OpenAI API
v2.2: McpBridgeService → McpClient → MCP Server (Node.js) → OpenAI API
```

### 验证
- MCP Server npm install: 117 packages
- 后端编译: `mvn compile` BUILD SUCCESS (82 source files, 10s)
- 前端: 无改动

---

## v2.1 — MCP 多模态桥接 + Agent 执行引擎

**日期**: 2026-08-09
**基线**: v2.0
**迭代主题**: 多模态视觉识别 + LLM 驱动的 Agent 执行引擎 + 批量执行

### 改动清单与目的

#### 后端

| 文件 | 改动 | 目的 |
|------|------|------|
| `service/LlmService.java` | 新增 `chatWithImage()` 多模态方法 | 发送图片+文本到 OpenAI Vision API |
| `dto/LocateResult.java` | 新建 | MCP 返回结构（found/bbox/clickX/clickY/confidence） |
| `service/McpBridgeService.java` | 新建 | MCP 多模态视觉识别服务（截图+描述→位置JSON） |
| `agent/ExecutionAgent.java` | 新建 | LLM 驱动的 Agent 执行引擎（agentic loop + 两层兜底） |
| `entity/ExecutionRecord.java` | 修改 | 新增 batchId + mode 字段 |
| `repository/ExecutionRecordRepository.java` | 修改 | 新增 findByBatchId 查询 |
| `service/ExecutionService.java` | 修改 | 新增 executeWithAgent + executeBatch + getBatchStatus |
| `controller/ExecutionController.java` | 修改 | 新增 Agent 模式参数 + 批量执行 + 批次查询 API |

#### 前端

| 文件 | 改动 | 目的 |
|------|------|------|
| `api/execution.js` | 修改 | 新增 executeBatch + getBatch + executeTestCase 支持 mode |
| `components/TestCaseCard.vue` | 修改 | 执行对话框新增"执行模式"选择（Agent/程序化） |
| `views/TestCaseList.vue` | 修改 | 新增"批量执行"按钮+URL对话框 |
| `views/BatchResult.vue` | 新建 | 批次结果页（进度条+用例列表+轮询） |
| `router/index.js` | 修改 | 新增 BatchResult 路由 |

### 验证
- 后端编译：`mvn compile` BUILD SUCCESS（81 source files, 9.2s）
- 前端构建：`npm run build` 成功（11.84s）

### 核心架构
```
Agent 执行单步:
  1. LLM 生成元素查找描述
  2. Skill take_screenshot
  3. MCP multimodal_element_locate（多模态视觉识别）
  4. LLM 决策策略（visual_click / dom_click / skip）
  5. 执行点击
  6. LLM 判断是否生效 → 不生效则 LLM 决策兜底
  7. 截图 + 组装证据
```

---

## v2.0 — Skill 工具层 + 执行数据模型

**日期**: 2026-08-09
**基线**: v1.12
**迭代主题**: AI 用例执行引擎基础设施 — Selenium WebDriver 集成、7 个 Skill 工具、执行数据模型、API、前端触发

### 改动清单与目的

#### 后端

| 文件 | 改动 | 目的 |
|------|------|------|
| `pom.xml` | 新增 selenium-java 4.21.0 + webdrivermanager 5.8.0 | Selenium WebDriver 依赖 |
| `skill/BrowserSkill.java` | 新建 | 7 个浏览器操作 Skill：browserLaunch/navigate/takeScreenshot/visualClick/domClick/getPageStatus/closeSession |
| `skill/EvidenceSkill.java` | 新建 | 证据存储 Skill：saveTestEvidence → Markdown 文档 |
| `entity/ExecutionRecord.java` | 新建 | 执行记录实体（id/projectId/testCaseId/status/startTime/endTime/summary） |
| `entity/ExecutionStep.java` | 新建 | 执行步骤实体（stepIndex/action/strategy/result/screenshotBefore/After/error） |
| `repository/ExecutionRecordRepository.java` | 新建 | 执行记录 Repository |
| `repository/ExecutionStepRepository.java` | 新建 | 执行步骤 Repository |
| `service/ExecutionService.java` | 新建 | 程序化执行服务：逐步骤调用 Skill 工具，记录结果，生成证据 |
| `controller/ExecutionController.java` | 新建 | 4 个执行 API：触发执行/查询结果/执行历史/步骤详情 |

#### 前端

| 文件 | 改动 | 目的 |
|------|------|------|
| `api/execution.js` | 新建 | 4 个执行 API 封装 |
| `views/ExecutionResult.vue` | 新建 | 执行结果展示页（概览+步骤详情+轮询） |
| `components/TestCaseCard.vue` | 修改 | 新增"执行"按钮+URL输入对话框 |
| `router/index.js` | 修改 | 新增 ExecutionResult 路由 |

### 验证
- 后端编译：`mvn compile` BUILD SUCCESS（78 source files, 9.4s）
- 前端构建：`npm run build` 成功

### v2.x 路线
| 版本 | 主题 | 状态 |
|------|------|------|
| v2.0 | Skill 工具层 + 执行数据模型 | ✅ 完成 |
| v2.1 | MCP 多模态桥接 + Agent 执行引擎 | 规划中 |
| v2.2 | 执行报告 + 录屏 | 规划中 |

---

## v1.12 — VueAnalyzer LLM 增强

**日期**: 2026-08-09
**基线**: v1.11
**迭代主题**: 正则先提取 + LLM 补充，提升前端分析覆盖率

### 改动清单与目的

#### 后端

| 文件 | 改动 | 目的 |
|------|------|------|
| `service/LlmService.java` | 新增 `isConfigured()` 方法 | 供 VueAnalyzer 判断是否可调用 LLM |
| `analyzer/VueAnalyzer.java` | 注入 LlmService + 新增 enhanceWithLlm/collectSourceSnippets/parseAndMergeSupplements/parseFields/parseSelectors 5 个方法 | 正则提取后用 LLM 补充遗漏内容（非 Element Plus 组件、Composition API 状态、动态路由等） |

### 验证
- 后端编译：`mvn compile` BUILD SUCCESS（2.2s）

### 向后兼容
- LLM 未配置 API Key 时跳过增强步骤，行为同 v1.11
- LLM 调用失败时降级为纯正则结果
- FrontendResult 数据模型不变，前端无改动

### 范围说明
- In Scope：VueAnalyzer LLM 补充逻辑（正则结果 + 源码摘要 → LLM → 合并去重）
- Out of Scope：前端 UI 变更、非 Vue 框架支持、AST 解析

---

## v1.11 — 前端代码分析 Agent

**日期**: 2026-08-09
**基线**: v1.10
**迭代主题**: 增强 VueAnalyzer 为前端代码分析 Agent，补上交互流转/DOM选择器/表单校验上下文

### 改动清单与目的

#### 后端

| 文件 | 改动 | 目的 |
|------|------|------|
| `analyzer/result/FrontendResult.java` | 新增 forms/componentStates/domSelectors/pageFlows 4 个字段 | 扩展前端分析数据模型 |
| `analyzer/VueAnalyzer.java` | 新增 4 个提取方法：extractForms/extractComponentStates/extractDomSelectors/extractPageFlows + collectVueFiles + 多个辅助方法 | 深度解析 Vue SFC 提取表单/交互状态/DOM选择器/页面跳转 |
| `agent/OrchestratorAgent.java` | 新增 loadFrontendResult() + 传给 TestGeneratorAgent | 编排 Agent 加载前端上下文 |
| `agent/TestGeneratorAgent.java` | 新增 generate(...frontendResult) 重载 + putFrontendContext() + prompt 补充前端信息 | 用例生成消费前端上下文（表单字段→testData、选择器→uiSelector、页面流转→跳转用例） |

#### 前端

| 文件 | 改动 | 目的 |
|------|------|------|
| `views/CodeAnalysis.vue` | 新增"前端分析"tab，含 4 个展示面板（表单字段/组件交互状态/DOM选择器/页面跳转关系） | 可视化前端分析结果 |

### 验证
- 后端编译：`mvn compile` BUILD SUCCESS（8.3s，70 source files）
- 前端构建：`npm run build` 成功（11.63s）

### 向后兼容
- FrontendResult 新字段默认空列表，历史数据无影响
- frontendResult 为 null 时 TestGeneratorAgent 退化为原逻辑
- H2 无 schema 变更（frontendResult 序列化在 CodeAnalysis.text 字段中）
- 前端无 Vue 项目时前端分析 tab 显示"无数据"

### 范围说明
- In Scope：VueAnalyzer 4 维度增强、FrontendResult 扩展、OrchestratorAgent 接入、TestGeneratorAgent prompt 改造、CodeAnalysis 页面增强
- Out of Scope：AI 执行引擎（v2.0）、React/Angular 分析器、Pinia/Vuex 深度分析、Vue SFC 完整 AST 解析

---

## v1.10 — PRD 驱动的用例生成

**日期**: 2026-08-09
**基线**: v1.9
**迭代主题**: 引入 PRD 作为主上下文 + 多 Agent 编排架构（PrdAgent + OrchestratorAgent）

### 改动清单与目的

#### 后端

| 文件 | 改动 | 目的 |
|------|------|------|
| `entity/Project.java` | 新增 `prdContent`/`prdSourceType`/`prdSourceRef` 三个字段 | 持久化 PRD 内容与来源信息 |
| `dto/ProjectDTO.java` | 透传 PRD 三个字段 | 前端可读写 PRD |
| `dto/PrdAnalysisResult.java` | 新建 DTO：modules/requirements/businessRules/stateFlows/entities | PRD 结构化解析结果载体 |
| `agent/PrdAgent.java` | 新建 PRD 解析 Agent：文本 LLM 结构化 + PDFBox 解析 PDF + Jsoup 抓取 URL | 三种 PRD 接入形式统一为结构化结果 |
| `agent/OrchestratorAgent.java` | 新建编排 Agent：协调 PrdAgent + 代码侧（状态机/后端结果）→ TestGeneratorAgent | 显式编排替代隐式调用链；PRD 为空时退化为代码驱动 |
| `agent/TestGeneratorAgent.java` | 新增 `SYSTEM_PROMPT_PRD_DRIVEN` + `generate(prdResult, stateMachines, backendResult, callback)` 重载 | PRD 为主上下文生成用例；PRD 为空时退化为原逻辑 |
| `controller/ProjectController.java` | 新增 4 接口：`GET /prd`、`PUT /prd`、`POST /prd/upload`、`POST /prd/fetch` | PRD 查询/文本更新/PDF上传/URL抓取 |
| `service/ProjectService.java` | 新增 `getPrd`/`updatePrd`/`uploadPrdPdf`/`fetchPrdUrl` | PRD 管理逻辑（PDF 调 PrdAgent.parsePdf，URL 调 PrdAgent.fetchUrl） |
| `service/TestCaseService.java` | `runGenerate` 改由 `orchestratorAgent.generate()` 编排 | 从隐式调用链升级为显式 Agent 编排 |
| `pom.xml` | 新增 PDFBox 3.0.1 + Jsoup 1.17.2 依赖 | PDF 解析与 URL 抓取支持 |

#### 前端

| 文件 | 改动 | 目的 |
|------|------|------|
| `api/project.js` | 新增 `getPrd`/`updatePrd`/`uploadPrdPdf`/`fetchPrdUrl` | 四个 PRD 接口封装 |
| `components/PrdPanel.vue` | 新建 PRD 面板：文本/Markdown 编辑器 + PDF 拖拽上传 + URL 抓取 + PRD 预览 + 来源标识 | 三种接入形式切换与交互 |
| `views/ProjectDetail.vue` | 引入 PrdPanel 组件 | 项目详情页展示 PRD 面板 |

### 验证
- 后端编译：`mvn compile` BUILD SUCCESS（3.2s）
- 前端构建：`npm run build` 成功（16.04s），无 chunk 警告

### 向后兼容
- 历史项目无 PRD 字段，`prdContent` 为 null，生成时退化为代码驱动（v1.9 行为）
- H2 `ddl-auto=update` 自动加列，无需迁移脚本
- 既有 API 端点不变，新增 4 个 PRD 端点

### 范围说明
- In Scope：PRD 数据模型 + 三种接入形式 + PrdAgent 解析 + OrchestratorAgent 编排 + TestGeneratorAgent 改造 + 前端 PRD 面板
- Out of Scope：前端代码分析 Agent（v1.11）、AI 执行引擎（v2.0）、需 OAuth 认证的 docs 接入、PRD 版本管理

---

## v1.9 — 用例版本管理

**日期**: 2026-08-09
**基线**: v1.8
**迭代主题**: 用例编辑版本快照 + 历史版本列表 + 字段级对比 + 一键回滚

### 改动清单与目的

#### 后端

| 文件 | 改动 | 目的 |
|------|------|------|
| `entity/TestCaseVersion.java` | 新建版本快照实体（id/testCaseId/projectId/versionNo/snapshot/action/createdAt） | 持久化用例历史快照 |
| `repository/TestCaseVersionRepository.java` | 新建版本仓库（按用例倒序查、计数、按 id+testCaseId 查） | 版本查询支持 |
| `dto/TestCaseVersionDTO.java` | 新建 DTO（list/detail 双视图，detail 含 snapshot） | 列表精简、详情含快照 |
| `controller/TestCaseController.java` | 新增 3 接口：`GET /{tcId}/versions`、`GET /{tcId}/versions/{vId}`、`POST /{tcId}/versions/{vId}/rollback` | 版本列表/详情/回滚入口 |
| `service/TestCaseService.java` | 注入版本仓库；`updateTestCase` 前置存 edit 快照；新增 `listVersions`/`getVersion`/`rollbackToVersion`（回滚前存 rollback 快照）/`createVersion`/`toSnapshotJson`/`applySnapshotToTestCase` | 编辑前留档、回滚可撤销 |

#### 前端

| 文件 | 改动 | 目的 |
|------|------|------|
| `api/testcase.js` | 新增 `listTestCaseVersions`/`getTestCaseVersion`/`rollbackTestCaseVersion` | 三个版本接口封装 |
| `components/TestCaseVersionDrawer.vue` | 新建版本抽屉：版本列表 + 查看快照 + 与当前用例字段级 diff + 回滚（二次确认） | 历史版本查看/对比/回滚交互 |
| `components/TestCaseCard.vue` | footer 新增"历史版本"按钮（Clock 图标）+ emit `versions` | 详情内打开版本抽屉 |
| `views/TestCaseList.vue` | 引入抽屉组件 + `versionDrawerVisible` + `handleOpenVersions` + `handleVersionRollback`（回滚后刷新列表与当前用例） | 接入抽屉并同步回滚结果 |

### 验证
- 后端编译：`mvn compile` BUILD SUCCESS（10.2s）
- 前端构建：`npm run build` 成功（11.93s），TestCaseList 16KB→20KB，无 chunk 警告

### 向后兼容
- 新增 `test_case_versions` 表，H2 `ddl-auto=update` 自动建表
- 历史用例（v1.9 前数据）无版本记录时列表为空，展示"暂无历史版本"
- `updateTestCase` 行为不变，仅额外写入版本，对调用方透明

### 范围说明
- In Scope：编辑/回滚触发的用例级版本快照
- Out of Scope：重新生成全量覆盖前的项目级快照（已有 v1.3 确认 + v1.7 导出备份兜底，留待后续）

---

## v1.8 — 用例评审状态流转

**日期**: 2026-08-09
**基线**: v1.7
**迭代主题**: 用例评审状态（draft/reviewed/approved/rejected）+ 批量改状态 + 状态筛选

### 改动清单与目的

#### 后端

| 文件 | 改动 | 目的 |
|------|------|------|
| `entity/TestCase.java` | 新增 `reviewStatus` 字段（默认 draft） | 持久化评审状态 |
| `dto/TestCaseDTO.java` | 新增 `reviewStatus` 字段；`from()` 对 null 兜底为 draft | 透传评审状态，历史数据兼容 |
| `dto/ReviewRequest.java` | 新建 DTO：ids + status + reviewer | 批量改评审状态请求体 |
| `controller/TestCaseController.java` | `listTestCases` 新增 `reviewStatus` 筛选参数；新增 `POST /testcases/review` 批量改状态接口 | 状态筛选 + 批量评审入口 |
| `service/TestCaseService.java` | `listTestCases` 增加 reviewStatus 筛选（null 视为 draft）；新增 `batchUpdateReviewStatus`（校验合法状态、按 ids 更新） | 评审状态过滤与批量更新逻辑 |

#### 前端

| 文件 | 改动 | 目的 |
|------|------|------|
| `api/testcase.js` | 新增 `reviewTestCases(projectId, ids, status, reviewer)` | 批量评审接口封装 |
| `views/TestCaseList.vue` | 表格新增"评审"列（彩色 tag）；筛选区新增评审状态下拉；header 新增批量评审下拉菜单（已评审/已批准/已拒绝/重置草稿）；新增 `reviewTagType`/`reviewText`/`handleReviewCommand`；filters 加 reviewStatus；loadList 传参 | 评审状态可视化展示、筛选、批量操作 |

### 验证
- 后端编译：`mvn compile` BUILD SUCCESS（2.45s）
- 前端构建：`npm run build` 成功（12.85s），TestCaseList 14KB→16.08KB，无 chunk 警告

### 向后兼容
- 历史用例 reviewStatus 为 null，DTO 兜底为 draft，前端展示为"草稿"
- 全部为新增字段与接口，不破坏既有功能

---

## v1.7 — 导入导出与协作增强

**日期**: 2026-08-09
**基线**: v1.6
**迭代主题**: JSON 导入导出 + CSV 导出 + 跨项目用例复制

### 改动清单与目的

#### 后端

| 文件 | 改动 | 目的 |
|------|------|------|
| `controller/TestCaseController.java` | 新增 `GET /export`（JSON/CSV）、`POST /import`（multipart）、`POST /copy-to` 接口 | 导入导出与跨项目复制入口 |
| `dto/CopyToRequest.java` | 新增 DTO：ids + targetProjectId | 复制请求体 |
| `service/TestCaseService.java` | 新增 `exportTestCases`/`importTestCases`/`copyToProject` + 辅助方法 `nextTestCaseNumber`/`parseTestCaseFromJson`/`cloneTestCase`/`jsonField` | 导出文件流、JSON 解析回灌（重生成 ID、source=imported）、跨项目复制（source=copied）；导入字段缺失用默认值兜底 |
| `service/CsvExporter.java` | 新增工具类：TestCase 列表转 CSV，含 UTF-8 BOM 与标准 CSV 转义 | Excel 打开中文不乱码、字段含逗号/换行正确转义 |

#### 前端

| 文件 | 改动 | 目的 |
|------|------|------|
| `api/testcase.js` | 新增 `exportTestCases`（fetch 拿 headers+blob）、`importTestCases`（FormData）、`copyToProject` | 三个新接口封装 |
| `views/TestCaseList.vue` | header 新增 4 按钮（导出JSON/导出CSV/导入JSON/复制到）+ 隐藏 file input；新增 `downloadBlob`/`handleExportJson`/`handleExportCsv`/`triggerImportFile`/`handleImportFile`/`handleCopyTo` | 导入导出 UI 与交互；复制到用 prompt 列出可选目标项目 |

### 验证
- 后端编译：`mvn compile` BUILD SUCCESS（63 源文件，8.6s）
- 前端构建：`npm run build` 成功（15.71s），TestCaseList 12KB→14KB
- 导出 JSON → 导入回同项目，数量翻倍且 ID 不冲突（重生成 TC-XXX）
- CSV 导出含 BOM，Excel 中文正常
- 跨项目复制后目标项目有用例，源项目不受影响
- 导入非数组 JSON 返回错误提示且不写入

---

## v1.6 — 高可用增强

**日期**: 2026-08-09
**基线**: v1.5
**迭代主题**: 错误详情存储与返回 + 生成进度反馈 + 并发提示优化 + 日志结构化

### 改动清单与目的

#### 后端

| 文件 | 改动 | 目的 |
|------|------|------|
| `entity/Project.java` | 新增 `errorMessage`(TEXT) + `progress` 字段 | 持久化生成失败原因和实时进度 |
| `dto/ProjectDTO.java` | 透传 `errorMessage` + `progress` | 前端轮询可读取 |
| `repository/ProjectRepository.java` | 新增 `updateProgress` + `updateStatusWithError`，均带 `@Transactional` | 进度更新即时提交对前端可见；失败时原子更新 status+errorMessage 并清空 progress |
| `agent/TestGeneratorAgent.java` | 新增 `ProgressCallback` 函数式接口 + `generate(states, result, callback)` 重载；原方法委托新方法传 null | 分模块生成时实时回调进度，不破坏既有调用 |
| `service/TestCaseService.java` | `runGenerate` 增加进度更新（解析/分模块/保存各阶段）+ 失败存储 errorMessage；`updateProjectStatus` 在 generating/completed 时清除残留 errorMessage | 进度反馈、错误可追溯、重新生成成功后旧错误不残留 |
| `service/ProjectService.java` | `triggerGenerate` 对 `generating` 状态给出明确并发提示"正在生成中，请等待当前任务完成" | 避免用户重复触发生成 |
| `resources/logback-spring.xml` | 新增：结构化 JSON 日志配置（LogstashEncoder，含 app 字段） | 便于日志聚合、检索与问题排查 |
| `pom.xml` | 新增 logstash-logback-encoder 7.4 依赖 | 结构化日志输出支持 |

#### 前端

| 文件 | 改动 | 目的 |
|------|------|------|
| `stores/project.js` | 新增 `progressMessage` ref；轮询时实时同步 `project.progress`，终态时清空；回调第三参数显式传整个 project | 进度信息跨组件可读，组件可拿到 errorMessage |
| `views/TestCaseList.vue` | 新增 `generationError` ref + `progressText` computed（优先 store 实时进度，兜底本地提示）；轮询回调读取 `project.errorMessage` 展示；模板新增 error 类型 alert；开始生成时清除上次错误 | 实时展示"正在生成第 X/Y 个模块: xxx"进度 + 失败时展示具体错误详情 |

### 验证
- 后端编译：`mvn compile` BUILD SUCCESS（61 源文件，15.5s）
- 前端构建：`npm run build` 成功（12.13s），无 chunk 警告
- 进度反馈：生成过程中前端轮询每 3s 可见"正在生成第 N/M 个模块: 状态机名"
- 错误详情：生成失败时前端展示后端 errorMessage（如 LLM 调用异常原因）

---

## v1.5 — 可视化增强

**日期**: 2026-08-09
**基线**: v1.4
**迭代主题**: 覆盖率矩阵可视化 + 状态机覆盖图 + 前端 chunk 拆分

### 改动清单与目的

#### 后端

| 文件 | 改动 | 目的 |
|------|------|------|
| `controller/CoverageController.java` | 新增 `GET /coverage/matrix` 接口 | 返回每个状态转换的覆盖详情（covered + testCaseIds） |
| `service/CoverageService.java` | 新增 `getCoverageMatrix()` | 遍历状态机 transitions 与用例 stateMachineRef 匹配，计算覆盖状态 |

#### 前端

| 文件 | 改动 | 目的 |
|------|------|------|
| `components/CoverageMatrix.vue` | 新增组件：覆盖率矩阵表格 | 每行一个转换，显示覆盖状态（✓/✗），未覆盖行红色高亮，可点击关联用例跳转 |
| `components/StateMachineViewer.vue` | 新增 `coverageData` prop；`buildEdges()` 根据覆盖状态着色（绿=已覆盖/红虚线=未覆盖） | 图上直观标注覆盖状态 |
| `views/StateMachineOverview.vue` | 新增页面：状态机覆盖图 | 独立路由展示状态机图+覆盖标注+统计摘要 |
| `views/TestCaseList.vue` | 引入 CoverageMatrix 组件；新增 coverageMatrix 状态和 loadCoverageMatrix | 在用例列表页展示覆盖率矩阵 |
| `api/coverage.js` | 新增 `getCoverageMatrix()` | 调用矩阵接口 |
| `router/index.js` | 新增 `/projects/:id/state-machines` 路由 | 状态机覆盖图页面入口 |
| `vite.config.js` | 新增 `build.rollupOptions.output.manualChunks`（echarts/elementPlus/vendor 分离）+ `chunkSizeWarningLimit: 1100` | 解决单 chunk 2.2MB 问题，首屏仅需 index+vendor（185KB） |

### 验证
- 后端编译：`mvn compile` BUILD SUCCESS（61 源文件）
- 前端构建：`npm run build` 成功（13.71s），无 chunk 警告
- chunk 效果：index.js 26KB + vendor 159KB + echarts 1035KB + elementPlus 1074KB（按需懒加载）

---

## v1.4 — 生成质量增强II & 批量操作

**日期**: 2026-08-09
**基线**: v1.3
**迭代主题**: LLM prompt 深度优化（具体字段值/边界值/few-shot）+ LLM 重试机制 + 批量操作

### 改动清单与目的

#### 后端

| 文件 | 改动 | 目的 |
|------|------|------|
| `agent/TestGeneratorAgent.java` | systemPrompt 从单行拼接重构为结构化分段常量（角色/任务/数量引导/测试数据要求/structuredSteps要求/stateMachineRef要求/输出格式）；userPrompt 注入 few-shot 示例（1 正向+1 异常） | 让 LLM 生成有具体字段值（amount:-1）、边界值组合、完整结构化步骤的高质量用例 |
| `service/LlmService.java` | `chat()` 重构为重试包装 + `callLlmApi()` 抽取；指数退避 1s→2s→4s，最多 3 次；400/401/403 不重试 | 网络抖动/限流时自动重试，减少不必要的规则回退 |
| `controller/TestCaseController.java` | 新增 `DELETE /batch` 批量删除端点 | 支持批量删除用例 |
| `dto/BatchDeleteRequest.java` | 新增 DTO | 批量删除请求体 |
| `service/TestCaseService.java` | 新增 `batchDeleteTestCases()` | 批量删除逻辑 |
| `controller/MindMapController.java` | `generateMindMap` 新增可选 `testcaseIds` 参数 | 支持只导出选中用例 |
| `service/MindMapService.java` | `generateMindMap` 按 testcaseIds 过滤 | 批量导出选中用例 |

#### 前端

| 文件 | 改动 | 目的 |
|------|------|------|
| `api/testcase.js` | 新增 `batchDeleteTestCases()` | 调用批量删除接口 |
| `api/mindmap.js` | `generateMindmap` 新增 data 参数 | 支持传入 testcaseIds |
| `views/TestCaseList.vue` | el-table 新增 selection 列 + `@selection-change`；header 新增批量删除/导出选中按钮（带数量提示）；新增 `selectedRows`/`handleBatchDelete`/`handleExportSelected` | 批量操作能力 |

### 验证
- 后端编译：`mvn compile` BUILD SUCCESS（59 源文件）
- 前端构建：`npm run build` 成功（16.35s）

---

## v1.3 — 用例体验增强

**日期**: 2026-08-09
**基线**: v1.2
**迭代主题**: 提升用例日常使用体验——搜索、安全确认、结构化步骤可编辑、上下条导航、单个删除

### 改动清单与目的

#### 后端

| 文件 | 改动 | 目的 |
|------|------|------|
| `controller/TestCaseController.java` | 新增 `DELETE /{testcaseId}` 接口；`listTestCases` 新增 `keyword` 参数 | 支持删除单个用例、关键字搜索 |
| `service/TestCaseService.java` | 新增 `deleteTestCase()`；`listTestCases` 新增 keyword 模糊过滤（标题/模块） | 删除能力 + 搜索过滤 |

#### 前端

| 文件 | 改动 | 目的 |
|------|------|------|
| `api/testcase.js` | 新增 `deleteTestCase()` API | 调用删除接口 |
| `views/TestCaseList.vue` | 重新生成增加 `ElMessageBox.confirm` 确认；筛选区新增搜索输入框（keyword）；对话框新增 `canGoPrev/canGoNext` props 传递 + `@delete/@prev/@next` 事件监听；新增 `currentIndex`/`handlePrev`/`handleNext`/`handleDeleteTestCase` | 防误操作丢失数据、搜索用例、导航与删除 |
| `components/TestCaseCard.vue` | 新增 `canGoPrev/canGoNext` props + `delete/prev/next` emits；编辑模式新增结构化步骤编辑器（增删改 action/target/expected/type）；对话框 footer 新增删除按钮 + 上一条/下一条按钮；`handleSave` 提交 structuredSteps | 结构化步骤可编辑、删除用例、详情内导航 |

### 验证
- 后端编译：`mvn compile` BUILD SUCCESS（58 源文件）
- 前端构建：`npm run build` 成功（17.91s）

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
