# AICaseTest — AI 驱动的测试用例生成系统

一个基于代码分析 + 状态机提取 + LLM 的智能测试用例生成平台。自动分析项目代码结构，提取业务状态机，生成结构化、AI 可执行的测试用例，并通过 Playwright 自动执行、录屏取证、覆盖率度量、语义检索与数据治理，形成"分析 → 生成 → 执行 → 回归"的完整闭环。

## 技术栈

| 层 | 技术 |
|----|------|
| 后端 | Java 17、Spring Boot 3.2、Spring Data JPA、Spring Security/JWT |
| 数据层 | MySQL 8（Flyway 管理）/ H2（开发 profile） |
| 运行态 | Redis 7（取消标志/心跳/并发配额/防爆破/缓存/任务队列） |
| 语义层 | Milvus 2.4 + LLM Embedding（语义去重/RAG/搜索/失败经验库） |
| 前端 | Vue 3、Vite、Element Plus、Pinia、ECharts、markmap |
| AI/执行 | Spring AI 1.0（LLM 文本/流式/JSON/Embedding，OpenAI starter）+ MCP（Playwright 浏览器/多模态视觉定位/tools 能力桥接） |
| 部署 | Docker、docker-compose（MySQL + Redis + Milvus standalone） |

## 核心能力

1. **项目导入与代码分析**：扫描 Spring Boot 后端（Controller/Entity/Enum/BusinessRule）和 Vue 前端，提取 API 端点、实体字段、业务规则、表单、DOM 选择器与页面跳转
2. **状态机自动提取**：从代码分析结果中推断业务状态机（states + transitions），LLM 提取 + 规则兜底
3. **AI 测试用例生成**：PRD 驱动为主、代码为辅，分模块调用 LLM 生成结构化测试用例（正向/异常/边界/数据），规则回退保证可用性；支持流式输出、追加生成与取消
4. **结构化可执行用例**：每个用例步骤含 action/target/expected/data/type，关联 API 端点，携带测试数据、执行提示与真实 DOM 选择器
5. **覆盖率度量**：状态转换覆盖率 + 接口覆盖率 + 类型分布，矩阵可视化并支持跳转关联用例
6. **质量评分**：每个用例按结构完整度计算 0-100 分
7. **AI 自动执行**：Playwright MCP 驱动真实浏览器，Agent 模式多模态定位 + LLM 决策 + DOM 兜底，输出步骤截图、WebM 录屏、HTML 报告与证据文件
8. **语义检索**：Milvus 语义去重、生成前 RAG 上下文注入、自然语言语义搜索、失败经验知识库
9. **数据治理**：事务落库、项目级联清理、MySQL 复合索引、执行历史分页、保留策略、数据健康检查与 H2→MySQL 迁移工具

## Spring AI 与 MCP 边界

v6.0 起 LLM 文本、流式、JSON 与 Embedding 层由 Spring AI（`spring-ai-starter-model-openai`，兼容 DashScope/MAAS 端点）承载；MCP 继续承担 Playwright 浏览器执行、多模态视觉定位（`multimodal_element_locate`）、`chatWithImage` 与 tools 后端能力桥接。详见 [docs/spring-ai-migration.md](docs/spring-ai-migration.md)。

v6.1 起新增前端 Agentic RAG（逐组件语义摘要落 Milvus，按 requirement 检索命中组件）与后端 SAINT 操作依赖图，生成端到端用例时融合 UI 交互与后端调用链；v6.2 将前后端分析、逐组件 LLM 摘要改为有界并发并把状态机提取合并为单次调用；v6.4 将 PRD/上下文文档/补充需求按章节切片索引，生成前多路检索 + RRF 融合，并把历史失败经验注入 prompt。v6.5 落地高可用 P0：分析/生成写入 `agent_task` 持久化任务，租约心跳 + 启动恢复标记 NEEDS_REVIEW，提供管理端任务列表/重试，并对 LLM 重试做错误分类与抖动。默认 LLM 模型切为 `mimo-v2.5`（`opencode.ai/zen/go/v1`），并统一 prompt 上限与 HTTP 超时。

## 快速开始

> v4.0 起系统需要登录：启动后访问前端，使用默认管理员 `admin` / `admin123`（首次登录后请尽快修改密码）登录，或自行注册新账号。

### 环境要求

- JDK 17+
- Node.js 18+
- Maven 3.8+
- 生产全栈需 Docker（MySQL / Redis / Milvus）

### 本地开发（默认 H2 + 内存运行态）

```bash
cd backend
# 使用项目内置 Maven 仓库（首次会下载依赖）
mvn spring-boot:run -Dmaven.repo.local=../.m2-repo
```

后端默认运行在 `http://localhost:8000`

```bash
cd frontend
npm install
npm run dev
```

前端默认运行在 `http://localhost:5173`

### Docker 一键启动（生产全栈）

```bash
docker-compose up -d
```

compose 会依次启动 MySQL（`aicasetest-mysql`，宿主端口 3308）、Redis、Milvus（etcd + minio + milvus standalone），后端以 `prod` profile 连接 MySQL/Redis/Milvus，前端由 Nginx 提供服务。

### 配置

复制 `.env.example` 为 `.env`，填入 LLM API Key：

```
LLM_API_KEY=your-api-key
LLM_BASE_URL=https://api.example.com/v1
LLM_MODEL=your-model-name
```

可选环境变量：

- `MYSQL_URL` / `MYSQL_USERNAME` / `MYSQL_PASSWORD`
- `REDIS_HOST` / `REDIS_PORT`
- `MILVUS_HOST` / `MILVUS_PORT` / `MILVUS_DIMENSION`
- `APP_REDIS_ENABLED` / `APP_MILVUS_ENABLED`
- `RAG_CHUNK_SIZE` / `RAG_CHUNK_OVERLAP` / `RAG_RRF_K` / `RAG_CONTEXT_TOPK` / `RAG_FAILURE_TOPK` / `RAG_MAX_QUERIES`（v6.4 RAG 切片与多路检索）
- `LLM_RETRY_MAX_ATTEMPTS`（LLM 重试次数，v6.5；4xx 不重试） / `APP_HA_TASK_LEASE_SECONDS`（任务租约秒数，v6.5）
- `APP_MCP_REQUEST_TIMEOUT_SECONDS`（MCP 工具超时秒数，v6.6） / `APP_HA_TASK_TTL_MINUTES`（任务 TTL 分钟数，v6.6）
- `LLM_CIRCUIT_FAILURE_THRESHOLD` / `LLM_CIRCUIT_OPEN_SECONDS`（LLM 熔断，v6.7）
- `RETENTION_EXECUTION_DAYS`（执行数据保留天数，0 关闭）
- `HIKARI_MAX_POOL` / `HIKARI_MIN_IDLE`（MySQL 连接池）
- `EXECUTOR_PROJECT_ACQUIRE_TIMEOUT_MINUTES`（项目执行并发排队超时分钟数，v7.9；超时该条执行记 failed，<=0 禁用恢复无限等待）
- `APP_COPY_EXECUTE_REQUIRE_OPERATE`（复制执行权限收敛，v7.9；默认 false 保持 VIEW 即可，true 要求 OPERATE 权限）

> ⚠️ `.env` 已被 `.gitignore` 排除，不会提交到仓库。

## 用例执行

系统支持对生成/导入的用例执行真实浏览器自动化（执行链路自 v2.x 起已内置），并输出步骤截图、WebM 录屏、HTML 报告与 Markdown 证据。

### 前置条件

- 后端启动时会自动拉起 `playwright-mcp-server` 子进程；首次运行需安装 Playwright Chromium：

```bash
cd playwright-mcp-server
npx playwright install chromium
```

- Docker 镜像已内置 Chromium，`docker-compose up -d` 后即可执行。
- Agent 模式建议配置 `LLM_API_KEY`；未配置时自动降级为"视觉定位 → DOM → 跳过"的默认策略。

### 怎么执行

1. 进入项目的"测试用例"页。
2. 选择用例后点击"执行"（单条）或"批量执行"（多条）。
3. 填入待测页面 URL（项目默认执行 URL 会自动带入）。
4. 选择执行模式：
   - **Agent 模式**：LLM 多模态识别 + DOM 兜底，推荐；
   - **程序化模式**：按结构化步骤中的 `uiSelector` 直接操作 DOM，不依赖 LLM。
5. 执行结束后在"执行历史 / 执行结果 / 批次结果"查看步骤、截图、WebM 录屏、HTML 报告与证据文件。

### 说明

- 用例需包含 `structuredSteps`；纯自然语言步骤在程序化模式下会跳过，Agent 模式下尝试多模态识别。
- 执行失败不会中断后续步骤，单步失败会记录错误与截图。
- 批量执行与复制执行单批上限 100 条（v7.9，防线程池打满把浏览器自动化挤到 HTTP 请求线程），超限返回业务错误 50014，请分批执行。
- **多实例部署注意（v7.9）**：执行截图/录屏等证据文件保存在实例本地 `outputs/` 目录。多实例（后端副本 >1）时需将 `./outputs` 配置为共享卷（NFS/云盘）或后续接入对象存储；未配置共享卷时，报告请求路由到无证据文件的实例会在报告中显式渲染"截图文件缺失"告警占位（含丢失路径），不再静默缺图。
- "复制执行"对选中用例做快照执行，不回写原用例执行状态。

## 测试与运维基线

项目自 **vT1** 起建立独立工程基线版本线，测试与运维基线如下：

- 后端单元/集成测试：34 个（含 Testcontainers MySQL/Redis；本地无 Docker 自动跳过）
- 前端单元测试：7 个（Vitest：状态文案、进度组件、auth store）
- 覆盖率门禁：后端 JaCoCo（verify 阶段）、前端 Vitest v8 阈值
- CI：后端 `mvn test` + 前端 `npm run build` + `docker compose config` 校验
- 回归入口：`scripts/verify-v5-stack.ps1`（后端测试、前端构建、compose 配置、可选健康检查）
- 可观测：`/actuator/health` 免认证健康检查，`/actuator/prometheus` 指标采集
- 备份：`scripts/backup-v5.ps1`（data/outputs + 可选 MySQL dump）
- 安全基线：`scripts/security-check.ps1`（校验 `.env` 未跟踪 + 扫描疑似密钥/私钥）
- CI 安全：gitleaks 密钥扫描 + `npm audit` 生产依赖审计 + Docker 镜像构建校验
- 运维手册：[docs/运维手册.md](docs/运维手册.md)（部署/升级/备份/恢复/监控/排障）

详见 [docs/vT1/PRD_vT1_测试与运维基线.md](docs/vT1/PRD_vT1_测试与运维基线.md)。

## 项目结构

```
AICaseTest/
├── backend/                  # Spring Boot 后端
│   └── src/main/java/com/testagent/
│       ├── analyzer/         # 代码分析器（Spring/Vue 扫描）
│       ├── agent/            # AI 用例生成/状态机/执行 Agent
│       ├── controller/       # REST API
│       ├── dto/              # 数据传输对象
│       ├── entity/           # JPA 实体
│       ├── migration/        # H2 → MySQL 全量迁移工具
│       ├── runtime/          # Redis/内存运行态存储（取消/心跳/配额/防爆破）
│       ├── queue/            # 任务队列存储
│       ├── security/         # JWT 认证与权限
│       ├── service/          # 业务服务
│       └── repository/       # 数据访问
├── frontend/                 # Vue 3 前端
│   └── src/
│       ├── api/              # API 封装
│       ├── components/       # 组件（TestCaseCard 等）
│       ├── views/            # 页面
│       └── stores/           # Pinia 状态管理
├── mcp-server/               # LLM MCP Server（chat / embedding / 多模态定位）
├── playwright-mcp-server/    # Playwright MCP Server（浏览器操作 + 录屏）
├── scripts/                  # 验证脚本（verify-v5-stack.ps1）
├── docs/                     # 文档（PRD + 技术评审 + CHANGELOG + 迭代历程 + API）
├── docker-compose.yml
└── README.md
```

## 版本现状

当前版本：**v7.9（执行效率与证据存储）**，生产线基线为 vP5（压测与容量）。

- v7.9 要点：执行链路效率与可靠性收尾——生效判断两级化（E6，本地三指纹任一变化直接判生效不调 LLM——旧实现无论指纹是否变化都调 LLM 而输入与本地比较完全相同，常见成功路径每步省一次调用；指纹相同才 LLM 终审且 prompt 明示"快照无变化"）；批量执行入口限流（E7，单批上限 100 条防线程池 queue 满触发 CallerRunsPolicy 把浏览器自动化挤到 HTTP 请求线程挂死接口，超限返回 50014）+ 项目并发排队超时（RuntimeStore.tryAcquireProjectPermit 双实现，默认 30 分钟超时记 failed 无僵尸 running）；执行链路 ID 加长（E9，执行记录/步骤/批次 ID 从 UUID 前 8 位加长到 16 位，32bit→64bit 消除 7.7 万条 50% 碰撞的静默覆盖炸弹）；复制执行权限收敛开关（E10，`APP_COPY_EXECUTE_REQUIRE_OPERATE` 默认 false 保持 VIEW 口径，true 要求 OPERATE）；报告证据丢失可见化（R11，截图三态渲染——无截图不渲染/丢失显式告警占位含丢失路径与共享卷提示/正常渲染，多实例部署不再静默缺图）；后端 273 测试全绿。
- v7.8 要点：闭环回写——评审建议分级采纳（R1，confidence ≥ 0.8 时 coverageRefs 建议（并集合并只增不减）与 priority 建议（枚举校验）自动应用并登记 autoApplied）；endpoint 匹配收紧（R3，两级匹配——归一化路径精确相等或 method 严格一致 + 相似度 ≥ 0.9 + token 数一致的高门槛模糊，编造的 CRUD 兄弟路径不再被"洗白"为已覆盖，模糊命中记 fuzzyEndpointIds 前端提示人工确认）；覆盖率计划/执行双栏（R7，每转换 planned（coverageRefs 引用）/executed（isExecuted 用例引用）双口径 + 前端双进度条，"计划覆盖 80%"不再被误读为"验证过 80%"）；质量评分并入评审结论（G6，形式分 × 0.7 + 评审分（pass 30/fix 扣减/无评审 15）- UI 语言违规扣分，去重保留高分者时编造字段填满不再挤掉真实用例）；后端 253 测试全绿。
- v7.7 要点：投喂精准——RAG 切片并入考点清单（G16，检索回的需求类切片标题与既有需求 token 相似度 <3 时作为 `rag-req-N` 进 checklist，长 PRD 尾部需求经 Milvus 全文切片零成本找回，A14 昂贵修法被免费覆盖大半）；后端上下文按需求关键词过滤（G17，endpoints/rules 按 path/function/description/validation 与需求关键词 token 重叠打分，无关接口不进 prompt，命中为空兜底全量）；轮间摘要注入（G4，第 2+ 轮 prompt 附已生成用例标题/类型摘要 + requirementIds 语义兜底匹配，多轮补齐真实收敛不再靠事后去重）；PRD 头尾截断（L4a，超 12000/24000 字符头尾各半保留，后部验收标准不再系统性丢弃）；大 PRD 解析失败瘦身重试（A13，完整解析失败降级只求核心三块，两次均失败明确提示"输出可能被截断，请精简文档或拆分"）；规则层参数提取（A5，@RequestParam/@PathVariable/@RequestBody 注解解析零 LLM 成本入 endpoints.parameters）；LLM 补充接口源码校验（A4a，function 含已知类名或 ≥2 段路径前缀才收，丢弃记 warning）；容量事实明示（G10，gaps 按类截断标 truncated，达 60 条上限仍有缺口时 complete 事件带 `coverageCappedByLimit` 与降级信号区分）；后端 228 测试全绿。

- v7.6 要点：闭环可信——状态机转换 ground truth 校验（A17，JavaParser 扫描源码状态赋值点提取"转换来源→目标"证据，LLM 推断的 transition 与证据比对：匹配标 `verified`、编造的标 `unverified` 且 confidence 压降到 ≤0.4，"状态转换覆盖率"不再建立在无差别信任上）；expected 三层断言（L6，程序化/Agent 两模式共用 `ExecutionAssert`：URL/标题语义 → DOM 文本断言（中文短语剥叙述前后缀 + 3-gram 滑窗匹配，toast 文案"删除成功"可断言）→ 无法验证诚实标 skipped，断言失败步骤记 failed 并带期望/实际差异）；Agent 模式步骤类型分流（E5，`state_assert` 走 getPageStatus+断言+截图留证、`api_call` 明确 skipped，验证步骤不再掉进"找元素→点击"流水线，消除误点生产事故风险）；错误→文案对照表（G20层3，VueAnalyzer 提取 ElMessage 调用字面量 + SpringAnalyzer 提取异常消息，合成 `userFeedbackTexts` 注入生成上下文，prompt 硬规则要求 expected 优先使用真实文案原文禁止编造）；后端 205 测试全绿。
- v7.5 要点：基线可信——统一 LLM 结果缓存层（`llm_result_cache` 表，键 = SHA-256(模型名+systemPrompt+userPrompt)，换模型/prompt 演进/内容变化任一发生自然失效，无 TTL）；PRD 解析缓存（A15，同 PRD 二次生成不调 LLM，requirements 与首次完全一致，temp 0.2 漂移消除，追加生成与首次生成模块口径一致）；组件摘要缓存（A11，源码未变的业务组件二次分析零 LLM 调用，分析高频操作成本不再线性放大）；毒缓存自愈（解析失败自动落回 LLM 路径）+ 缓存 DB 故障降级直调 LLM 绝不阻断分析/生成；后端 164 测试全绿。
- v7.4 要点：证据可信——分析结果干净（A1 排除 src/test 测试代码，不再污染 endpoints/entities）、HTTP 方法正确（A2 解析 @RequestMapping method 属性，POST 不再标 ANY）、校验规则不静默丢（A7 反引号模板串不再截断 rules 块、A8 多表单全部 rules 块合并、A10 LLM 补充字段级合并）、可复现（A9 文件列表按路径字典序排序）、可观测（C1 BackendResult/FrontendResult 新增 warnings 随 JSON 落库，"0 个表单"可解释）、不误导（A20 状态机来源派生 source: rule/llm，兜底状态机禁止虚构转换）、删除死代码约 120 行（A19）；后端 150 测试全绿。
- v7.3 要点：组件可信——流式取消从全局单例改为 per-request 信号（L1，并发生成取消互不误杀）、熔断器按 text/multimodal 通道隔离且 4xx 配置错误不计入（L2，多模态故障/Key 填错不再连坐全系统）、SPA 生效判断改 URL+title+textSnippet 三指纹 + 点击后 800ms 渲染等待（L5，消除"URL 不变→误判未生效→DOM 兜底重复提交"）、流式 JSON 截断检测+局部补全抢救+streamTruncated 告警（L8）、预期结果语言规范入 prompt + few-shot 修正 + UiLanguageLint 静态 lint 打标（G20层1+2，堵增量+标存量）；后端 141 测试全绿。
- v7.2 要点：数字可信——删除仪表盘从未实现的 apiRate/avgApiRate 假字段（R6）、全 skipped 执行记 `skipped` 终态且报告通过率分母改为 passed+failed（R10，消除"passed 徽章 + 0% 通过率"同屏矛盾）、评审 coverageRefs 改保序并集不再覆盖丢失（R2）、覆盖率矩阵预解析消除双重循环内 JSON 反序列化（R8，50 万次 parse → 每用例一次）、平均覆盖率按转换总数加权（R9）、报告 footer 版本收敛单一常量（R12）；后端 117 测试全绿。
- v7.1 要点：生成结果诚实化——判重增加类型一致性防"正向/逆向"误杀（G1）、SSE 推送与落库差异可见（G2 complete 明细）、移除空 data 占位（G3）、删除约 700 行代码驱动死代码并改用真实降级信号（G5）、聚焦类型过滤空结果专有报错（G11）、全量生成批内语义去重（G14）、项目配置解析失败专有报错（G15）；附带 git_url 项目真实克隆、embedding 独立端点、RAG 切片调优（500）与需求上下文模块级增量重建；后端 100 测试全绿。
- v7.0 要点：v7.x 系列首版（基于全链路代码审查 80 项风险清单）；执行链路诚实化——取消后记录不再复活（E1）、高可用调度器不再误伤执行任务（E2）、基础设施故障不再记 passed（E3）、state_assert 真实断言（E4）、Agent 单步内补心跳（E8）、skip 决策规则引导与错误信息诚实化（E12）；后端 97 测试全绿。
- v6.9 要点：任务 timeline 回放（agent_task_events + 管理端/前端）；故障演练与容量基线脚本；运维手册高可用章节收口。
- v6.8 要点：Redis Streams 任务事件总线加速分发；QUEUED 领取改为 CAS 幂等；分析互斥与执行取消迁 Redis 运行态；新增 DLQ/失败率 Prometheus 告警。
- v6.7 要点：分析断点续跑复用已完成结果；规则兜底生成标记 `degraded`；LLM provider 连续失败熔断；`task_telemetry` 关联 task_id/attempt；新增仅 ADMIN 的任务中心页面。
- v6.6 要点：MCP 工具调用加超时并按幂等性自动重试；执行任务接入 `agent_task` 租约与 checkpoint；任务 TTL 与 QUEUED 兜底调度；新增任务/工具 Prometheus 指标。
- v6.5 要点：分析/生成任务写入 `agent_task` 持久化状态机（phase/checkpoint/attempt/lease/heartbeat）；启动与定时恢复将租约过期任务标记 NEEDS_REVIEW；管理端 `/api/admin/tasks` 支持列表/详情/重试；LLM 重试改为错误分类 + 抖动，4xx 不再盲目重试。
- v6.4 要点：PRD/上下文文档/补充需求切片进 Milvus、生成侧去掉整段 PRD 自我检索、多路查询 RRF 融合、历史失败经验注入生成、存量项目首次生成自动重建切片。
- v6.3 要点：本地代码审查整改——首登强制改密、SSE 票据化鉴权（长期 JWT 不再进 URL）、业务组件口径统一（0 分/异常分组件不进覆盖清单）、项目列表 N+1 优化、Telemetry 线程清理、H2 Console 仅 dev 开放、MCP 默认 token 收紧。
- v5.13 要点：语义检索/需求解析/状态机/评审/代码分析拆为 `tools-mcp-server` 工具；Agent Prompt 抽为 Skill 模板；生成强制基于 PRD，代码仅作辅助。
- v5.12 要点：AI 评审历史独立落库；单条重评异步化，前端轮询不再 30s 超时；采纳真正应用建议并同步人工评审状态；覆盖率统一为计划引用 + 实际执行口径。
- v5.11 要点：需求文档支持多篇 PRD/上下文文档与补充需求；生成链路注入覆盖清单并输出 `coverageRefs`；AI 评审 UI、代码分析统计、暗色主题与脑图 PNG 导出。
- v5.10 要点：PRD 上下文支持“其他上下文信息”与多来源上下文文档；执行历史支持按用例过滤查看；Agent 元素定位增加滚动兜底。

| 版本线 | 主题 | 状态 |
|---|---|---|
| v1.x | 结构化用例、PRD 驱动、前端代码分析 | ✅ 完成 |
| v2.x | Skill/MCP、Playwright 执行、录屏报告 | ✅ 完成 |
| v3.x | 流式生成、执行闭环、统计回归、平台化 | ✅ 完成 |
| v4.x | 账号安全、并发治理、项目组权限、分析流式化 | ✅ 完成 |
| v5.0 | 数据层准备（Flyway + MySQL） | ✅ 完成 |
| v5.1 | H2 → MySQL 全量迁移工具 | ✅ 完成 |
| v5.2 | Redis 运行态接入 | ✅ 完成 |
| v5.3 | 缓存与任务队列 | ✅ 完成 |
| v5.4 | Milvus 语义检索层 | ✅ 完成 |
| v5.5 | 正式切换 MySQL + Redis + Milvus | ✅ 完成 |
| v5.6 | 数据一致性与生命周期 | ✅ 完成 |
| v5.7 | 数据索引与查询性能 | ✅ 完成 |
| v5.8 | 数据治理与可观测 | ✅ 完成 |
| v5.9 | 项目上下文与操作体验优化（Cookie 可编辑/PRD 改版/操作区上移） | ✅ 完成 |
| v5.10 | PRD 上下文改版与用例级执行历史（其他上下文信息/多来源文档/按用例查看历史） | ✅ 完成 |
| v5.11 | 生成链路 AI 评审与前端体验（需求文档合并/coverageRefs/评审操作/暗色主题/脑图导出） | ✅ 完成 |
| v5.12 | AI 评审闭环与覆盖引用收口（历史落库/异步重评/采纳语义/覆盖率口径） | ✅ 完成 |
| v5.13 | 能力分层：MCP 工具化与 Prompt Skill 化（tools-mcp-server/桥接接口/Skill 模板/PRD 必需） | ✅ 完成 |
| v6.0 | Spring AI 迁移（文本/流式/JSON/Embedding） | ✅ 完成 |
| v6.1 | 前端 Agentic RAG + 后端 SAINT 操作依赖图 | ✅ 完成 |
| v6.2 | 分析并行化与状态机收口 | ✅ 完成 |
| v6.3 | 本地代码审查整改（安全与工程健壮性补强） | ✅ 完成 |
| v6.4 | RAG 切片化与多源检索增强 | ✅ 完成 |
| v6.5 | 高可用 P0（任务状态持久化/租约恢复/LLM 重试分类） | ✅ 完成 |
| v6.6 | 高可用 P1（执行接入 agent_task/MCP 工具超时/任务 TTL） | ✅ 完成 |
| v6.7 | 高可用 P2（断点续跑/降级标记/LLM 熔断/任务中心） | ✅ 完成 |
| v6.8 | 高可用 P3（Redis Streams 事件总线/CAS 抢占/状态迁 Redis） | ✅ 完成 |
| v6.9 | 高可用收口（timeline 回放/故障演练/容量基线） | ✅ 完成 |
| v7.0 | 执行可信度修复（取消复活/调度器误伤/假通过/skip 引导） | ✅ 完成 |
| v7.1 | 生成链路一致性修复（判重误杀/推送落库差异/空 data 占位/语义去重） | ✅ 完成 |
| v7.2 | 度量与报告诚实化（假字段删除/通过率口径/refs 并集/覆盖率提速加权） | ✅ 完成 |
| v7.3 | LLM 组件稳定与生成质量约束（流取消并发/熔断隔离/SPA生效判断/截断告警/G20层1+2） | ✅ 完成 |
| v7.4 | 分析器规则层加固（测试代码排除/HTTP方法解析/模板串/多rules块/文件排序/字段级合并/warnings/状态机来源标记） | ✅ 完成 |
| v7.5 | 缓存与可复现基线（PRD 解析缓存/组件摘要缓存/prompt-hash 失效/降级直调） | ✅ 完成 |
| v7.6 | 状态机与断言闭环（转换证据校验/expected 三层断言/Agent 步骤分流/错误文案对照表） | ✅ 完成 |
| v7.7 | 上下文精准投喂（RAG 进考点清单/后端上下文过滤/轮间摘要/头尾截断/瘦身重试/参数提取/补充接口校验/容量明示） | ✅ 完成 |
| vT1 | 测试与运维基线（独立工程版本线） | ✅ 完成 |
| vT2 | 服务层与集成测试（JWT/工具类/JPA） | ✅ 完成 |
| vT3 | 前端测试基线（Vitest/Vue Test Utils） | ✅ 完成 |
| vT4 | 运维与可观测基线（Actuator/Prometheus/备份） | ✅ 完成 |
| vT5 | 安全与全量回归收口（密钥扫描/回归入口/CI 兼容） | ✅ 完成 |
| vT6 | 服务层与接口安全测试（MockMvc/401修复/级联测试） | ✅ 完成 |
| vT7 | Testcontainers 集成测试（MySQL Flyway/Redis 运行态） | ✅ 完成 |
| vT8 | 前端测试扩充与覆盖率门禁（auth store/JaCoCo/Vitest coverage） | ✅ 完成 |
| vT9 | 安全扫描与部署加固（.env.example/nginx/Redis AUTH/CI/运维手册） | ✅ 完成 |
| vP1 | 上线安全加固（TLS/密码密钥强制/中间件访问控制/上传与 URL 加固） | ✅ 完成 |
| vP2 | 高可用与容灾（MySQL 备份调度/恢复演练/任务恢复/资源限制/优雅停机） | ✅ 完成 |
| vP3 | 可观测与告警（Grafana 面板/告警规则/traceId/access log/SLO） | ✅ 完成 |
| vP4 | 发布流水线（GHCR 镜像/多环境部署/Flyway staging 演练/回滚） | ✅ 完成 |
| vP5 | 压测与容量（k6 基线/线程池调优/大数据量分页与索引验证） | ✅ 完成 |

详细版本历史见 [docs/迭代历程.md](docs/迭代历程.md)，逐版本变更记录见 [docs/CHANGELOG.md](docs/CHANGELOG.md)。

## API 文档

完整 REST API 概览见 [docs/API概览.md](docs/API概览.md)；后端运行时可访问内嵌 Swagger：`/swagger-ui/index.html`。

## License

MIT
