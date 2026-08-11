# AICaseTest — AI 驱动的测试用例生成系统

一个基于代码分析 + 状态机提取 + LLM 的智能测试用例生成平台。自动分析项目代码结构，提取业务状态机，生成结构化、AI 可执行的测试用例，并支持覆盖率度量与质量评分。

## 技术栈

| 层 | 技术 |
|----|------|
| 后端 | Java 17、Spring Boot 3.2、Spring Data JPA、H2 |
| 前端 | Vue 3、Vite、Element Plus、Pinia |
| LLM | OpenAI 兼容协议（OkHttp） |
| 部署 | Docker、docker-compose |

## 核心能力

1. **项目导入与代码分析**：扫描 Spring Boot 后端（Controller/Entity/Enum/BusinessRule）和 Vue 前端，提取 API 端点、实体字段、业务规则
2. **状态机自动提取**：从代码分析结果中推断业务状态机（states + transitions）
3. **AI 测试用例生成**：分模块调用 LLM 生成结构化测试用例（正向/异常/边界/数据），规则回退保证可用性
4. **结构化可执行用例**：每个用例步骤含 action/target/expected/data/type，关联 API 端点，携带测试数据与执行提示
5. **覆盖率度量**：状态转换覆盖率 + 接口覆盖率 + 类型分布，进度条可视化
6. **质量评分**：每个用例按结构完整度计算 0-100 分
7. **XMind 脑图导出**：将用例导出为 XMind 格式

## 快速开始

### 环境要求

- JDK 17+
- Node.js 18+
- Maven 3.8+

### 后端启动

```bash
cd backend
# 使用项目内置 Maven 仓库（首次会下载依赖）
mvn spring-boot:run -Dmaven.repo.local=../.m2-repo
```

后端默认运行在 `http://localhost:8080`

### 前端启动

```bash
cd frontend
npm install
npm run dev
```

前端默认运行在 `http://localhost:5173`

### Docker 一键启动

```bash
docker-compose up -d
```

### 配置

复制 `.env.example` 为 `.env`，填入 LLM API Key：

```
LLM_API_KEY=your-api-key
LLM_BASE_URL=https://api.example.com/v1
LLM_MODEL=your-model-name
```

> ⚠️ `.env` 已被 `.gitignore` 排除，不会提交到仓库。

## 项目结构

```
AICaseTest/
├── backend/                  # Spring Boot 后端
│   └── src/main/java/com/testagent/
│       ├── analyzer/         # 代码分析器（Spring/Vue 扫描）
│       ├── agent/            # AI 用例生成 Agent
│       ├── controller/       # REST API
│       ├── dto/              # 数据传输对象
│       ├── entity/           # JPA 实体
│       ├── service/          # 业务服务
│       └── repository/       # 数据访问
├── frontend/                 # Vue 3 前端
│   └── src/
│       ├── api/              # API 封装
│       ├── components/       # 组件（TestCaseCard 等）
│       ├── views/            # 页面
│       └── stores/           # Pinia 状态管理
├── docs/                     # 文档（PRD + 技术评审 + CHANGELOG）
│   ├── v1.1/
│   ├── v1.2/
│   └── CHANGELOG.md
├── docker-compose.yml
└── README.md
```

## 迭代历程

每个迭代都有完整的 PRD + 前后端技术评审文档，位于 `docs/` 目录。

### v1.0 — MVP（初始版本）

项目导入 → 代码分析 → 状态机提取 → LLM/规则生成用例 → XMind 导出 → 前端列表与详情查看。

**局限**：步骤为纯自然语言、用例未关联 API、stateMachineRef 形同虚设、前端步骤展示为纯文本列表。

### v1.1 — 结构化可执行用例

让测试用例从"自然语言文档"升级为"AI 可读可执行的结构化剧本"。

- 后端：TestCase 新增 `structuredSteps`/`apiEndpoints`/`testData`/`executionHints`/`executionStatus` 字段
- 后端：TestGeneratorAgent 升级 LLM prompt，生成结构化步骤 + 关联 API + 真实填充 stateMachineRef
- 前端：TestCaseCard 升级为结构化步骤卡片展示（含回退兼容旧用例）

详见：[PRD v1.1](docs/v1.1/PRD_v1.1_结构化可执行用例.md)

### v1.2 — 用例生成质量增强

提升生成内容质量并让质量可度量、可视化。

- 后端：分模块生成（按状态机逐个调用 LLM，单模块失败隔离）
- 后端：用例去重（标题相似度 > 80% 判重，保留质量更高者）
- 后端：质量评分（结构完整度 0-100 分）
- 后端：覆盖率计算（状态转换覆盖率 + 接口覆盖率）
- 前端：覆盖率面板（进度条可视化）
- 前端：用例表格质量列 + 详情质量评分

详见：[PRD v1.2](docs/v1.2/PRD_v1.2_用例生成质量增强.md)

### v1.3 — 用例体验增强

提升用例日常使用体验——搜索、安全确认、结构化步骤可编辑、上下条导航、单个删除。

- 后端：TestCaseController 新增 DELETE 接口 / TestCaseService 新增 deleteTestCase + keyword 搜索
- 前端：重新生成增加确认提示（防误操作丢失数据）
- 前端：筛选区新增关键字搜索（标题/模块模糊匹配）
- 前端：编辑模式新增结构化步骤编辑器（增删改 action/target/expected/type）
- 前端：详情对话框新增上一条/下一条导航（当前页内）
- 前端：单个用例删除（二次确认 + 刷新列表）

详见：[PRD v1.3](docs/v1.3/PRD_v1.3_用例体验增强.md)

### v1.4 — 生成质量增强II & 批量操作

LLM prompt 深度优化 + LLM 重试机制 + 批量操作。

- 后端：systemPrompt 从单行拼接重构为结构化分段（数量引导/测试数据要求/structuredSteps要求/stateMachineRef要求）+ few-shot 示例注入（1正向+1异常）
- 后端：LLM 调用增加指数退避重试（1s→2s→4s，最多3次），400/401/403 不重试
- 后端：新增 `DELETE /testcases/batch` 批量删除接口
- 后端：mindmap 生成接口新增可选 `testcaseIds` 参数，支持只导出选中用例
- 前端：表格新增多选列 + 批量删除按钮（带数量提示 + 二次确认）
- 前端：新增"导出选中"按钮，只导出选中用例的脑图

详见：[PRD v1.4](docs/v1.4/PRD_v1.4_生成质量增强II.md)

### v1.5 — 可视化增强

覆盖率矩阵可视化 + 状态机覆盖图 + 前端 chunk 拆分。

- 后端：新增 `GET /coverage/matrix` 接口，返回每个状态转换的覆盖详情（covered + testCaseIds）
- 前端：新增覆盖率矩阵组件（表格展示，未覆盖行红色高亮，可点击关联用例跳转）
- 前端：新增状态机覆盖图页面（`/projects/:id/state-machines`），ECharts 图中边按覆盖状态着色（绿=已覆盖/红虚线=未覆盖）
- 前端：vite chunk 拆分（echarts/elementPlus/vendor 分离），首屏仅需 index+vendor（185KB）

详见：[PRD v1.5](docs/v1.5/PRD_v1.5_可视化增强.md)

### v1.6 — 高可用增强

错误详情存储与返回 + 生成进度反馈 + 并发提示优化 + 日志结构化。

- 后端：Project 实体新增 `errorMessage` + `progress` 字段，持久化失败原因与实时进度
- 后端：ProjectRepository 新增 `updateProgress` / `updateStatusWithError`（自带 @Transactional，进度即时提交对前端可见）
- 后端：TestGeneratorAgent 新增 `ProgressCallback` 回调接口 + `generate()` 重载，分模块生成时实时回调进度
- 后端：TestCaseService.runGenerate 在解析/分模块/保存各阶段更新进度，失败时存储 errorMessage
- 后端：ProjectService.triggerGenerate 对 `generating` 状态给出明确并发提示，避免重复触发
- 后端：新增 `logback-spring.xml` 结构化 JSON 日志配置 + logstash-logback-encoder 依赖
- 前端：project store 新增 `progressMessage`，轮询实时同步进度
- 前端：TestCaseList 展示实时进度（"正在生成第 X/Y 个模块: xxx"）+ 失败错误详情 alert

详见：[PRD v1.6](docs/v1.6/PRD_v1.6_高可用增强.md)

### v1.7 — 导入导出与协作增强

JSON 导入导出 + CSV 导出 + 跨项目用例复制。

- 后端：TestCaseController 新增 `GET /testcases/export`（JSON/CSV）、`POST /testcases/import`（multipart）、`POST /testcases/copy-to`
- 后端：TestCaseService 新增导出（文件流）/导入（JSON 解析回灌，重生成 ID，source=imported）/跨项目复制（source=copied）
- 后端：新增 CsvExporter 工具类（UTF-8 BOM + 标准 CSV 转义，Excel 中文不乱码）
- 后端：新增 CopyToRequest DTO
- 前端：api/testcase 新增三个接口封装（导出用 fetch 拿文件名+blob）
- 前端：TestCaseList 新增 4 个按钮（导出JSON/导出CSV/导入JSON/复制到）+ 隐藏 file input + blob 下载工具

详见：[PRD v1.7](docs/v1.7/PRD_v1.7_导入导出与协作增强.md)

### v1.8 — 用例评审状态流转

为用例引入评审状态（draft/reviewed/approved/rejected），支持批量改状态和按状态筛选，便于团队评审与用例生命周期管理。

- 后端：TestCase 新增 `reviewStatus` 字段（默认 draft），DTO 透传并对 null 兜底
- 后端：listTestCases 新增 reviewStatus 筛选参数；新增 `POST /testcases/review` 批量改状态接口
- 后端：新增 ReviewRequest DTO；batchUpdateReviewStatus 校验合法状态并按 ids 更新
- 前端：表格新增"评审"列（彩色 tag：草稿灰/已评审黄/已批准绿/已拒绝红）
- 前端：筛选区新增评审状态下拉；header 新增批量评审下拉菜单（二次确认）

详见：[PRD v1.8](docs/v1.8/PRD_v1.8_用例评审状态流转.md)

### v1.9 — 用例版本管理

为用例编辑引入版本快照机制：每次保存编辑前自动留档，支持查看历史版本、与当前对比差异、一键回滚。

- 后端：新增 TestCaseVersion 实体 + 仓库 + DTO（list/detail 双视图）
- 后端：updateTestCase 前置存 edit 快照；rollbackToVersion 回滚前存 rollback 快照（使回滚可撤销）
- 后端：新增 3 接口（版本列表/详情/回滚）
- 前端：新建 TestCaseVersionDrawer 抽屉（版本列表 + 查看快照 + 字段级 diff + 回滚二次确认）
- 前端：TestCaseCard footer 新增"历史版本"按钮

详见：[PRD v1.9](docs/v1.9/PRD_v1.9_用例版本管理.md)

### v1.10 — PRD 驱动的用例生成

引入 PRD 作为用例生成的主上下文，建立多 Agent 编排架构。

- 后端：Project 新增 `prdContent`/`prdSourceType`/`prdSourceRef` 字段
- 后端：新建 `PrdAgent`（3 种 PRD 接入：文本/Markdown、PDF 上传 PDFBox 解析、URL 抓取 Jsoup）
- 后端：新建 `OrchestratorAgent`（编排 PrdAgent + 代码侧 → TestGeneratorAgent）
- 后端：`TestGeneratorAgent` 新增 PRD 驱动 prompt（PRD 为主、代码为辅，PRD 为空退化为代码驱动）
- 后端：`TestCaseService.runGenerate` 改由 OrchestratorAgent 编排
- 后端：新增 4 个 PRD 接口（查询/更新/上传PDF/抓取URL）
- 后端：新增 PDFBox 3.0.1 + Jsoup 1.17.2 依赖
- 前端：新建 `PrdPanel.vue`（文本编辑/PDF拖拽上传/URL抓取/预览/来源标识）
- 前端：`ProjectDetail.vue` 接入 PRD 面板

详见：[PRD v1.10](docs/v1.10/PRD_v1.10_PRD驱动的用例生成.md)

### v1.11 — 前端代码分析 Agent

增强 VueAnalyzer 为真正的"前端代码分析 Agent"，补上执行 Agent 兜底所需的前端上下文。

- 后端：FrontendResult 新增 forms/componentStates/domSelectors/pageFlows 4 个维度
- 后端：VueAnalyzer 新增 4 个提取方法（表单字段+校验规则、组件交互状态、DOM选择器、页面跳转关系）
- 后端：OrchestratorAgent 新增 loadFrontendResult() 加载前端上下文
- 后端：TestGeneratorAgent 新增 frontendResult 重载 + putFrontendContext() + prompt 补充前端信息
- 前端：CodeAnalysis.vue 新增"前端分析"tab（表单字段/交互状态/DOM选择器/页面跳转 4 个面板）

详见：[PRD v1.11](docs/v1.11/PRD_v1.11_前端代码分析Agent.md)

### v1.12 — VueAnalyzer LLM 增强

正则先提取 + LLM 补充，提升前端分析覆盖率。

- 后端：LlmService 新增 `isConfigured()` 方法
- 后端：VueAnalyzer 注入 LlmService，正则提取后调 LLM 补充遗漏内容
- 后端：LLM 接收正则结果 + .vue 源码摘要，返回补充的 forms/states/selectors/flows
- 后端：合并去重（按 component/类型/路径去重），LLM 失败时降级为纯正则结果

详见：[PRD v1.12](docs/v1.12/PRD_v1.12_VueAnalyzerLLM增强.md)

### v2.0 — Skill 工具层 + 执行数据模型

AI 用例执行引擎基础设施：Selenium WebDriver 集成 + 7 个 Skill 工具 + 执行数据模型 + API + 前端触发。

- 后端：Selenium 4 + WebDriverManager 集成
- 后端：BrowserSkill（7 个浏览器操作：launch/navigate/screenshot/visualClick/domClick/getPageStatus/close）
- 后端：EvidenceSkill（证据存储为 Markdown）
- 后端：ExecutionRecord + ExecutionStep 实体 + Repository
- 后端：ExecutionService 程序化执行（逐步骤调 Skill，记录截图和结果）
- 后端：4 个执行 API（触发/查询/历史/步骤）
- 前端：执行按钮 + URL 输入 + ExecutionResult 结果页（轮询+步骤详情）

详见：[PRD v2.0](docs/v2.0/PRD_v2.0_Skill工具层与执行数据模型.md)

### v2.6 — MCP Client 多 Server 架构

MCP Client 从单 Server 重构为多 Server 架构，为接入 Playwright MCP 做基础设施准备。

- 后端：新建 McpConnection（单 Server 连接封装，synchronized 防并发）
- 后端：新建 McpClientManager（多 Server 管理器，替代 McpClient）
- 后端：LlmService + McpBridgeService 适配 `callTool(serverName, toolName, args)` 接口
- 配置：`mcp.server.*` → `mcp.servers.llm.*` 多 Server 格式

详见：[PRD v2.6](docs/v2.6/PRD_v2.6_MCP多Server架构.md)

### v2.7 — Playwright MCP Server + PlaywrightRecordSkill

自建 Playwright MCP Server（9个工具），实现浏览器操作 + 真正视频录屏（WebM），为 v2.8 执行链路切换做准备。

- 新建 `playwright-mcp-server/`：独立 MCP Server，基于 Playwright（launch/navigate/screenshot/visualClick/domClick/pageStatus/videoSave/close）
- 后端：新建 PlaywrightRecordSkill，方法签名与 BrowserSkill 对齐，截图标注复用 Graphics2D
- 后端：McpClientManager 注册 "playwright" Server
- 录屏升级：Playwright recordVideo → WebM 视频（替代周期截图）

详见：[PRD v2.7](docs/v2.7/PRD_v2.7_PlaywrightMCP与Skill实现.md)

### v2.8 — 执行链路切换（Selenium → Playwright）

将执行引擎从 Selenium BrowserSkill 切换到 Playwright PlaywrightRecordSkill，录屏从图片序列升级为 WebM 视频。

- 后端：ExecutionRecord 新增 `recordingVideoPath` 字段（存储 WebM 视频路径）
- 后端：ExecutionController 新增 `GET /executions/{executionId}/video` 视频下载 API
- 后端：ExecutionService 依赖从 BrowserSkill 替换为 PlaywrightRecordSkill，录屏逻辑改为 stopRecording 保存视频
- 后端：ExecutionAgent 依赖从 BrowserSkill 替换为 PlaywrightRecordSkill（所有浏览器操作调用切换）
- 录屏升级：Playwright recordVideo → WebM 视频（替代周期截图图片序列）

详见：[PRD v2.8](docs/v2.8/PRD_v2.8_执行链路切换.md)

### v2.9 — Selenium 清理 + 录屏回放升级为视频播放

清理 Selenium 死代码与依赖；前端录屏回放从图片轮播升级为 WebM 视频播放。

- 后端：删除 `BrowserSkill.java`（v2.8 已被 PlaywrightRecordSkill 完全替代）
- 后端：pom.xml 移除 `selenium-java` + `webdrivermanager` 依赖
- 后端：PlaywrightRecordSkill 注释更新（标注为唯一浏览器操作实现）
- 前端：api/execution.js 新增 `getExecutionVideoUrl` 辅助函数
- 前端：ExecutionResult.vue 录屏回放升级为 `<video>` 播放 WebM（优先），回退图片轮播（兼容历史记录）

详见：[PRD v2.9](docs/v2.9/PRD_v2.9_Selenium清理与视频录屏回放.md)

### v3.0 — PRD 驱动流程改造

将产品流程从"代码驱动"改为"PRD 驱动"——PRD 为用例生成主线（必须），代码路径降级为可选上下文。

- 后端: `CreateProjectRequest` 移除 `sourcePath` 的 `@NotBlank`，改为可选
- 后端: `ProjectService.createProject` 在 `sourcePath` 为空时跳过路径存在校验
- 后端: `TestCaseService.runGenerate` 前置校验——PRD 和代码分析结果都为空时抛异常阻止生成
- 前端: `ProjectCreate.vue` 来源类型新增"无代码（纯 PRD）"选项；项目路径条件显示
- 前端: `ProjectDetail.vue` PRD 面板上提到操作区上方；"生成用例"提至首位；"开始分析"无代码路径时禁用
- 前端: `canGenerate` 放宽——created 状态也可生成（纯 PRD 驱动）

详见：[PRD v3.0](docs/v3.0/PRD_v3.0_PRD驱动流程改造.md)

### v3.1 — 目录选择器与界面优化

将本地路径从手动输入升级为可视化目录选择器，并优化创建项目表单界面。

- 后端: 新建 `DirItem` DTO + `FilesystemController`，提供 `GET /api/filesystem/dirs` 目录列表接口（path 为空返回根盘符，非空返回子目录）
- 前端: 新建 `DirSelector.vue` 目录选择器组件（el-popover + el-tree 懒加载，支持返回上级/节点选中/确定回调）
- 前端: `ProjectCreate.vue` 来源类型改为 el-radio-button；本地路径输入框集成目录选择器（el-input append 插槽）；Git 地址加格式校验；无代码模式加 el-alert 说明；新增重置按钮

详见：[PRD v3.1](docs/v3.1/PRD_v3.1_目录选择器与界面优化.md)

### v3.2 — 用例生成流式输出（SSE Stream）

将用例生成从"异步轮询 + 终态一次性返回"升级为"SSE 流式推送"，用户每生成一条用例即可实时看到。

- 后端: `TestGeneratorAgent` 新增 `CaseCallback` 接口 + `generateStreaming` 重载，每条用例解析后立即回调（去重前）
- 后端: `OrchestratorAgent` 抽取 `loadGenerationContext` helper + 新增 `generateStreaming` 透传 caseCb
- 后端: `TestCaseService` 新增 `runGenerateStream(projectId, emitter)`（`@Async`），通过 SseEmitter 推送 progress/case/complete/error 事件
- 后端: `ProjectController` 新增 `GET /api/projects/{id}/testcases/generate-stream` SSE 端点（5 分钟超时，generating 状态拒绝）
- 前端: `api/testcase.js` 新增 `streamGenerate`（基于浏览器原生 EventSource）
- 前端: `TestCaseList.vue` 流式生成面板（绿色 alert 进度+计数 + 表格逐条入表 + 编号列"生成中" + 流式期间隐藏分页 + ?generate=1 自动触发）
- 前端: `ProjectDetail.vue` "生成用例"跳转 TestCaseList 自动触发流式生成

详见：[PRD v3.2](docs/v3.2/PRD_v3.2_用例生成流式输出.md)

### v3.3 — 流式生成取消与落库保护

为流式用例生成增加取消能力 + 落库保护，修补 v3.2 遗留的"生成不可中断 + 先删后存覆盖旧用例"数据安全风险。

- 后端: 新建 `GenerationCancelledException` 异常类
- 后端: `TestCaseService` 新增 `cancellationFlags` 注册表 + `cancelGeneration` + `restoreProjectStatus`；`runGenerateStream` 新增 `cancelled` 标志，catch 取消异常后跳过 deleteAll+save（落库保护）
- 后端: `OrchestratorAgent.generateStreaming` 透传 `cancelled` 标志
- 后端: `TestGeneratorAgent` 新增 `checkCancelled` helper，在 LLM 调用前/状态机循环迭代前检查取消标志
- 后端: `ProjectController` 新增 `POST /api/projects/{id}/testcases/generate-cancel` 取消端点
- 后端: 客户端断开（onCompletion/onTimeout/onError）同时触发取消，不只跳过 send
- 前端: `api/testcase.js` 新增 `cancelGenerate` + `cancelled` 事件监听 + `onCancelled` 回调
- 前端: `TestCaseList.vue` 取消生成按钮（二次确认）+ `cancelling` 状态 + warning 提示区分取消与失败

详见：[PRD v3.3](docs/v3.3/PRD_v3.3_流式生成取消与落库保护.md)

### v3.4 — 生成参数可配置

将硬编码的用例生成参数（temperature 0.4、数量引导、测试类型）提取为项目级可配置项，支持按项目类型调整用例密度与多样性。

- 后端: 新建 `GenerationParams` DTO（caseDensity/temperature/focusTypes），存储于 Project.settings JSON
- 后端: `ProjectService` 新增 `getGenerationParams`/`updateGenerationParams`，从 settings JSON 读写，解析失败降级默认值
- 后端: `ProjectController` 新增 `GET/PUT /api/projects/{id}/generation-params` 端点
- 后端: `OrchestratorAgent` 解析 Project.settings 得到 GenerationParams，透传给 TestGeneratorAgent
- 后端: `TestGeneratorAgent` 将 SYSTEM_PROMPT 拆为 HEADER + 动态数量引导段 + FOOTER，根据 caseDensity 动态拼接；LLM temperature 参数化（从 params 读取）
- 前端: `api/project.js` 新增 `getGenerationParams`/`updateGenerationParams`
- 前端: `TestCaseList.vue` 新增"生成参数"按钮 + 对话框（用例密度 radio 三档 + 创造性 slider 0.2~0.6 + 聚焦类型 checkbox 四选）

详见：[PRD v3.4](docs/v3.4/PRD_v3.4_生成参数可配置.md)

### v3.5 — 追加生成模式

新增"追加生成"模式——不删除现有用例，按类型追加新用例并跨去重，让用户敢于增量改进而无需担心丢失人工评审成果。

- 后端: `TestCaseService` 新增 `runGenerateStreamAppend(projectId, type, emitter)`——不删除现有用例、type 非空时过滤、新用例 vs 现有用例跨去重、ID 从现有最大 +1 续号
- 后端: `TestCaseService` 新增私有 `isDuplicate(a, b)` 判重逻辑（与 TestGeneratorAgent 一致，复制以保持封装职责分离）
- 后端: `ProjectController` 新增 `GET /api/projects/{id}/testcases/generate-stream-append?type={type}` SSE 端点
- 后端: complete 事件携带 total/appended/dropped/existingBefore 字段；复用 cancellationFlags + 取消端点
- 前端: `api/testcase.js` 新增 `streamGenerateAppend`（基于 EventSource，complete 回调接收整个 data 对象）
- 前端: `TestCaseList.vue` 新增"追加生成"按钮 + 类型选择对话框（radio: 全部/正向/异常/边界/数据）+ `currentGenMode` 状态差异化流式面板标题

详见：[PRD v3.5](docs/v3.5/PRD_v3.5_追加生成模式.md)

### v3.6 — 用例列表体验优化

修复追加生成闪烁、列表信息不足、不支持手动添加三大体验问题。

- 修复: 追加生成时已有用例不消失（displayTestCases 合并已有+新用例）
- 修复: McpConnection 指定 UTF-8 编码，解决中文 LLM 响应解析失败
- 新增: el-table 展开行显示前置条件、步骤、预期结果
- 新增: "新增用例"按钮 + TestCaseCard 创建模式
- 新增: `POST /api/projects/{id}/testcases` 手动创建用例端点
- 优化: 覆盖率面板移到列表下方，可折叠

详见：[PRD v3.6](docs/v3.6/PRD_v3.6_用例列表体验优化.md)

### v3.7 — 真正的 LLM 流式输出

将"伪流式"升级为"真流式"——LLM 逐 token 生成 → MCP Server 逐块推送 → Java 逐行解析 → 增量 JSON 解析 → SSE 逐条推送，首条用例出现时间从 40~120 秒降至 ~5 秒。

- MCP Server: `llm_chat` 工具新增 `stream` 参数（默认 false），启用时 OpenAI `stream: true` + 逐块 JSON-RPC notification 推送
- 后端: `McpConnection.callToolStreaming` 循环读取 stdout，dispatch `notifications/llm_chunk` 到回调
- 后端: `LlmService.chatStreaming` 流式调用方法（带 chunk 回调）
- 后端: `StreamingTestCaseParser` 增量 JSON 解析器——跟踪花括号深度检测完整用例对象后立即回调
- 前端: 流式面板标题优化（0 条时提示"正在接收 LLM 流式响应..."）

详见：[PRD v3.7](docs/v3.7/PRD_v3.7_真正LLM流式输出.md)

### 路线规划

| 版本 | 主题 | 状态 |
|------|------|------|
| v1.0 | MVP 用例生成 | ✅ 完成 |
| v1.1 | 结构化可执行用例 | ✅ 完成 |
| v1.2 | 用例生成质量增强 | ✅ 完成 |
| v1.3 | 用例体验增强（搜索/编辑/导航/删除） | ✅ 完成 |
| v1.4 | 生成质量增强II & 批量操作 | ✅ 完成 |
| v1.5 | 可视化增强（覆盖率矩阵/状态机覆盖图/chunk 拆分） | ✅ 完成 |
| v1.6 | 高可用增强（错误详情/进度反馈/日志结构化/并发提示） | ✅ 完成 |
| v1.7 | 导入导出与协作增强（JSON/CSV 导入导出 + 跨项目复制） | ✅ 完成 |
| v1.8 | 用例评审状态流转（draft/reviewed/approved/rejected + 批量改状态 + 筛选） | ✅ 完成 |
| v1.9 | 用例版本管理（编辑快照 + 历史列表 + 字段对比 + 回滚） | ✅ 完成 |
| v1.10 | PRD 驱动的用例生成（PrdAgent + OrchestratorAgent + 三种 PRD 接入） | ✅ 完成 |
| v1.11 | 前端代码分析 Agent（Vue 路由/组件/表单/DOM 选择器） | ✅ 完成 |
| v1.12 | VueAnalyzer LLM 增强（正则+LLM 双重提取） | ✅ 完成 |
| v2.0 | Skill 工具层 + 执行数据模型（Selenium+7个Skill+执行API+前端触发） | ✅ 完成 |
| v2.1 | MCP 多模态桥接 + Agent 执行引擎（多模态视觉识别+LLM驱动+两层兜底+批量执行） | ✅ 完成 |
| v2.2 | 独立 MCP Server（Node.js MCP Server+Java MCP Client+stdio JSON-RPC 2.0） | ✅ 完成 |
| v2.3 | LLM 调用全量拆分到 MCP Server（LlmService→McpClient→MCP Server→OpenAI） | ✅ 完成 |
| v2.4 | 执行报告 + 录屏（HTML报告生成/周期截图录屏/前端播放器） | ✅ 完成 |
| v2.5 | 截图标注 + 录屏回放增强（红圈十字标注点击位置/录屏帧合并步骤截图） | ✅ 完成 |
| v2.6 | MCP Client 多 Server 架构（McpClientManager+McpConnection/多Server并行管理） | ✅ 完成 |
| v2.7 | Playwright MCP Server + PlaywrightRecordSkill（9个工具/真正视频录屏/方法签名对齐） | ✅ 完成 |
| v2.8 | 执行链路切换（Selenium→Playwright/录屏升级WebM视频/视频下载API） | ✅ 完成 |
| v2.9 | Selenium 清理 + 录屏回放升级（删除BrowserSkill/移除selenium依赖/前端video播放WebM） | ✅ 完成 |
| v3.0 | PRD 驱动流程改造（sourcePath 改可选/PRD 面板上提/生成前置校验） | ✅ 完成 |
| v3.1 | 目录选择器与界面优化（DirSelector 组件/FilesystemController/表单优化） | ✅ 完成 |
| v3.2 | 用例生成流式输出（SSE Stream/逐条推送/实时入表/EventSource 消费） | ✅ 完成 |
| v3.3 | 流式生成取消与落库保护（取消端点/检查点/落库保护/客户端断开自动取消） | ✅ 完成 |
| v3.4 | 生成参数可配置（caseDensity/temperature/focusTypes 项目级配置 + 动态 prompt） | ✅ 完成 |
| v3.5 | 追加生成模式（不删除现有用例 + 类型过滤 + 跨去重 + 续号保存） | ✅ 完成 |
| v3.6 | 用例列表体验优化（追加不闪烁/展开行/手动添加用例/UTF-8修复） | ✅ 完成 |
| v3.7 | 真正的 LLM 流式输出（MCP stream:true + JSON-RPC notification + 增量 JSON 解析器） | ✅ 完成 |

## API 概览

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/projects` | 项目列表 |
| POST | `/api/projects` | 创建项目 |
| GET | `/api/projects/{id}` | 项目详情 |
| DELETE | `/api/projects/{id}` | 删除项目 |
| POST | `/api/projects/{id}/analyze` | 触发代码分析 |
| GET | `/api/projects/{id}/analysis` | 获取分析结果 |
| POST | `/api/projects/{id}/testcases/generate` | 触发用例生成 |
| GET | `/api/projects/{id}/testcases/generate-stream` | 流式生成用例（SSE，推送 progress/case/complete/cancelled/error，v3.2） |
| GET | `/api/projects/{id}/testcases/generate-stream-append?type={type}` | 流式追加生成用例（SSE，不删除现有用例 + 类型过滤 + 跨去重，v3.5） |
| POST | `/api/projects/{id}/testcases/generate-cancel` | 取消流式生成（v3.3，v3.5 同时适用于追加生成） |
| POST | `/api/projects/{id}/testcases` | 手动创建测试用例（v3.6） |
| GET | `/api/projects/{id}/prd` | 查询 PRD 内容 |
| PUT | `/api/projects/{id}/prd` | 更新文本 PRD |
| POST | `/api/projects/{id}/prd/upload` | 上传 PDF（PDFBox 解析） |
| POST | `/api/projects/{id}/prd/fetch` | 抓取在线链接 PRD（Jsoup） |
| GET | `/api/projects/{id}/generation-params` | 获取生成参数（v3.4） |
| PUT | `/api/projects/{id}/generation-params` | 更新生成参数（v3.4） |
| GET | `/api/projects/{id}/testcases` | 用例列表（分页+筛选+覆盖率） |
| GET | `/api/projects/{id}/coverage/matrix` | 覆盖率矩阵（每转换覆盖详情） |
| GET | `/api/projects/{id}/testcases/{tcId}` | 用例详情 |
| PUT | `/api/projects/{id}/testcases/{tcId}` | 更新用例 |
| DELETE | `/api/projects/{id}/testcases/{tcId}` | 删除用例 |
| DELETE | `/api/projects/{id}/testcases/batch` | 批量删除用例 |
| GET | `/api/projects/{id}/testcases/export?format=json\|csv&ids=` | 导出用例（JSON/CSV 文件下载） |
| POST | `/api/projects/{id}/testcases/import` | 导入 JSON 用例文件（multipart） |
| POST | `/api/projects/{id}/testcases/copy-to` | 复制选中用例到其他项目 |
| POST | `/api/projects/{id}/testcases/review` | 批量改评审状态 |
| GET | `/api/projects/{id}/testcases/{tcId}/versions` | 用例历史版本列表 |
| GET | `/api/projects/{id}/testcases/{tcId}/versions/{vId}` | 用例版本详情（含快照） |
| POST | `/api/projects/{id}/testcases/{tcId}/versions/{vId}/rollback` | 回滚到指定版本 |
| GET | `/api/projects/{id}/statemachines` | 状态机列表 |
| POST | `/api/projects/{id}/mindmap` | 生成脑图 |
| GET | `/api/settings` | 获取设置 |
| PUT | `/api/settings` | 更新设置 |
| POST | `/api/projects/{pid}/testcases/{caseId}/execute?mode=agent` | 触发用例执行（v2.0，v2.1 加 Agent 模式） |
| GET | `/api/executions/{eid}` | 查询执行结果（v2.0） |
| GET | `/api/projects/{pid}/executions` | 执行历史列表（v2.0） |
| GET | `/api/executions/{eid}/steps` | 执行步骤详情（v2.0） |
| GET | `/api/executions/{eid}/video` | 下载执行录屏视频 WebM（v2.8） |
| POST | `/api/projects/{pid}/testcases/batch-execute` | 批量执行（v2.1） |
| GET | `/api/filesystem/dirs?path=` | 目录列表（path 为空返回根盘符，v3.1） |
| GET | `/api/batches/{batchId}` | 查询批次状态（v2.1） |
| GET | `/api/health` | 健康检查 |

## License

MIT
