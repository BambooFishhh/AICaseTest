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

> ⚠️ **v8.5 起安全密钥必填**：`APP_JWT_SECRET` / `APP_ADMIN_PASSWORD` / `MILVUS_PASSWORD` / `MCP_BRIDGE_TOKEN` 缺失任一项后端启动失败（错误信息指明缺失变量名）；Docker 全栈另需 `GRAFANA_ADMIN_PASSWORD`（未设置时 `docker compose config` 直接报错）。部署前可用 `docker compose config --quiet` 自查。

可选环境变量：

- `MYSQL_URL` / `MYSQL_USERNAME` / `MYSQL_PASSWORD`
- `REDIS_HOST` / `REDIS_PORT`
- `MILVUS_HOST` / `MILVUS_PORT` / `MILVUS_DIMENSION`
- `APP_REDIS_ENABLED` / `APP_MILVUS_ENABLED`
- `APP_MCP_ALLOWED_REMOTE_ADDRS`（v8.5：/api/mcp/** 额外来源白名单，逗号分隔 IP，默认仅回环）
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

当前版本：**v8.9.5（水平扩容交付 + 双实例演练 + 容量基线首组数据，阶段 6 收官）**，生产线基线为 vP5（压测与容量）。

- v8.9.5 要点：计划书「阶段 6」任务 12.8+12.7——**水平扩容指南**（拓扑/环境清单/LLM 限流聚合公式/Nginx ip_hash 与重连方案对比）；**双实例演练实证**：ShedLock 表分容器持锁实时刷新、JWT 跨实例直用、DB 状态互通（B 创建 A 可见），SSE 重连完整链路待 mock LLM 补做；**容量基线首组**：短请求 300 发 0 失败 Avg=12.4ms/P95=26ms，印证 Web 层非瓶颈。12.9 真实回测遵指示跳过。后端 491 测试全绿。

- v8.9.4 要点：计划书「阶段 6」任务 12.10——**票据 Redis 双实现**（多实例互通，TTL 过期自动失效；未启用 Redis 回落内存）；**ticket 作用域限定**（仅 SSE 流式与媒体端点接受 `?ticket=`，泄露不再等于全 API 泄露）；**媒体短票据切换**（video/file 废弃长期 JWT `?token=` 改走短票据，前端 ExecutionResult 页面加载即取票，废弃期保留旧分支 WARN 一周）；**admin 弱回退清零**（空密码确定性启动失败）。后端 491 测试全绿、前端 10 测试全绿。

- v8.9.3 要点：计划书「阶段 6」任务 12.5+12.6——**对账大项目内存优化**（DB 侧 id 投影查询替代全量实体、Milvus 向量 id 分页拉取每页 1000、缺失补索引分批 ≤500）；**并发残留清理**（compose LLM_MAX_PROMPT_CHARS 300k→500k 对齐 v8.4 + 删除死配置注入；degradedProvider ThreadLocal 入口清残留防池化串台；MetricsFacade/ObservabilityFilter 热路径 meter 缓存）。后端 487 测试全绿。

- v8.9.2 要点：计划书「阶段 6」任务 12.1+12.2（CR §9.3 C1/C2 承压瓶颈项）——**连接池对齐**（Hikari 默认 20→40、最小空闲 10、60s 泄漏检测；容量关系写入注释，MySQL max-connections=200 容纳多实例×40）；**LLM 入口实例级限流**（LlmRateLimiter 四通道信号量：text/stream/embedding/fallback-text 独立配额默认 6/6/4/4，等待超时抛 50300 可重试并衔接既有降级路由——主配额满自动分流备用供应商；等待>5s/拒绝双指标）。后端 483 测试全绿。

- v8.9.1 要点：计划书「阶段 6」任务 12.3+12.4（CR §9.3 C3 上线阻断项；版本号遵指示沿用 v8.9 子序列）——**compose 八处业务凭据 `:?` 必填**（MySQL root/user、Redis、Milvus root、MCP bridge token；MinIO minioadmin 弱默认消灭，MINIO_ACCESS_KEY/SECRET_KEY 必填）；CI compose 校验占位值集合同步扩充；**McpSourceFilter 置于 JwtAuthFilter 前**（回环∪白名单外 403 code 40300，`app.mcp.trust-proxy` 支持 XFF 首跳反代判定），控制器保留第二道防线同口径。后端 479 测试全绿。

- v8.8.2 要点：计划书任务 10.4–10.6——**双实例就绪**（四项既有定时任务补齐 ShedLock；排查报告 docs/双实例就绪排查报告.md：状态权威源 DB/Redis 核对、遗留限制=RuntimeStore 必须 Redis + outputs 共享卷）；**任务积压可观测**（agent_task 七状态 Gauge + RUNNING/QUEUED 两条告警 + 看板面板）；**混沌演练固化**（@Tag("chaos") 三场景：畸形输出对抗集/Milvus 断连补偿/池饱和拒绝——默认排除不阻塞日常构建）。后端 478 测试全绿 + chaos 8 例全绿。

- v8.8.1 要点：计划书任务 10.1–10.3——**多供应商双通道**（llm.models.fallback.* 注册降级供应商，三键齐备启用；ChatClient 懒构建缓存）；**降级路由**（主通道失败/熔断自动切换 fallback 并在 GenerationReport 与 SSE complete 标注 degradedProvider；双败抛 50300；熔断键 text/text:fallback 隔离）；**embedding 独立熔断与降级端点**。后端 478 测试全绿。

- v8.7.2 要点：计划书任务 9.5.5/9.5.7–9.5.10——**两块 Grafana 看板**（生成质量：轮次结果/产出跳过/跳过率健康线/契约违规/RAG 延迟；数据一致性：补偿积压/对账漂移 Gauge/Milvus 失败）+ **三条告警规则**（补偿积压>0 持续 1h、解析跳过率>30%、池饱和拒绝）promtool 校验通过；**评测体系 v1**（黄金数据集小/中/大三档 + EvalRunner mock 回放工具 + compare 基线对比 + 流程固化规则入 eval/README.md）；mock 基线归档全绿（结构 100%/召回 100%/覆盖 100%）；9.5.6 追踪按计划书默认裁剪。后端 469 测试全绿。

- v8.7.1 要点：计划书任务 9.5.1–9.5.4——**MetricsFacade 统一指标入口**（no-op 兜底/gauge 强引用）；13 项新指标落地：gen_parse_skipped_total、gen_retry_reset_total、gen_stream_truncated_total、gen_rounds_total{result}、gen_cases_generated_total、milvus_insert_truncated_total、milvus_op_failed_total{op}、vector_pending_ops_size、reconciliation_drift_ratio、executor_rejected_total{pool}、llm_schema_violation_total{agent}、rag_recall_count/rag_empty_recall_total/rag_latency_seconds；**MDC 标准化**（SSE 提交线程注入 projectId → TaskDecorator 透传异步线程 + Semantic/Milvus 入口直接注入）。后端 469 测试全绿。

- v8.6.2 要点：计划书任务 9.5–9.8——**出参契约化**（json-schema-validator 1.5.9 + 四份 draft-07 schema：用例数组/PRD 解析/状态机/评审结果）；**灰度开关**（llm.schema.mode 默认 observe 仅观测告警，enforce 时 chatJson 附缺失字段清单重试一次仍失败降级；切换判据=violation 率<1% 且可解释）；**括号配平提取**（extractJsonObject 逐段配平+JSON 甄别，说明文字含大括号不再误取）。后端 457 测试全绿。

- v8.6.1 要点：计划书任务 9.1–9.4——**删除补偿**（Milvus 删除终败落 pending_vector_ops 表，同 expr upsert 不堆行）；**ShedLock 重放**（每 5 分钟指数退避重放，超限 DEAD 告警；补偿/对账双任务上锁多实例安全；新增依赖 shedlock 5.16.0）；**周期对账**（每日逐项目比对 DB↔向量 id 集，缺失补索引、孤儿删除，漂移率>2% 记 WARN，查询失败 SKIPPED 防重建风暴；报告经 /api/admin/vector/reconciliation 暴露）；**幽灵兜底过滤**（去重 top-1 与语义检索批量校验存在性，DB 已删向量不再误杀新用例）。后端 441 测试全绿。

- v8.5 要点：《长期迭代计划书》阶段 1 全量落地——**弱默认密钥清零**（SecurityKeyGuard 全 profile 校验 APP_JWT_SECRET/APP_ADMIN_PASSWORD/MILVUS_PASSWORD/MCP_BRIDGE_TOKEN 四键必填，缺失启动失败并指明变量名；prod 强度检查仍归 ProductionGuard）；**MCP 桥接回环限制**（/api/mcp/** 非回环来源 40300，token 降为第二因子，反代可配 APP_MCP_ALLOWED_REMOTE_ADDRS 白名单）；**DNS rebinding 收敛**（SafeDnsResolver 双解析一致性 + 全 A 记录内网判定，Git 克隆与 URL 抓取统一接入）；**Grafana compose 密码必填**；前端消费 retryReset 消除流式重试草稿叠加；安防集成测试 +7 例固化。后端 421 测试全绿。

- v8.4 要点：全面适配 256k context 模型——生成链路全部截断预算参数化并放宽；本地代码审查修复落地——线程池饱和快速失败、流式重试端到端一致、解析逐条容错、向量层转义/字节截断/删除重试、SSRF 与目录越权收敛、prompt 注入防护。本期仅后端改动，无前端变更。后端 407 测试全绿。

- v8.2 要点：生成目标只聚焦本期范围——**状态机切片**（ScopeSlicingService 将范围内 SM 的转换按证据文件 ∈ changed_files 二分为 sprint 目标/historical 上下文，checklist 与 coverageRefs 对账天然收敛到本期集合）；**BFS 确定性推导 setup 路径**（初始态→目标转换源状态最短路径输出 trigger 骨架，LLM 只填数据不找路径）；**phase 步骤标记**（structuredSteps 新增 setup/verify 字段 + prompt 分层注入 + few-shot 分层示例）；**执行 blocked 语义**（setup 失败 → 整条记已阻断而非失败，跳过后续验证步骤，报告单独统计且不入失败经验库）；**生成前置校验升级（破坏性变更）**——代码驱动项目必须先创建并确认本期范围才能生成，纯 PRD 项目不受影响；非 Git 仓库可建空草稿手动标注。后端 405 测试全绿。

- v8.1 要点：引入「本期范围(Scope)」领域概念，解决"无法区分本期需求代码与历史代码"的结构性缺失——**Git 基线 diff 自动识别**（`--name-status` 三点 diff 得变更文件集 → EndpointInfo.file 归一化后缀双向匹配出新增/变更接口；stateTransitions 证据 {field,from,to,file} 关联状态机标"受影响"）；**LLM 辅助补充映射**（PRD↔接口清单语义匹配补齐 diff 遗漏项，失败降级不阻断）；**人工确认锁定**（草稿可增删/重算，confirm 后只读）；配套 Git 克隆策略改造（--depth 1 → --filter=blob:none --no-single-branch partial clone）、项目删除级联清理、数据健康计数、备份 ZIP 含 scope.json。

- v7.15 要点：执行可信与双编号制——**流式重复草稿治理**（后端 wrapPushDedup 跨轮推送去重 + 前端 onCase 按标题 upsert 兜底，同题草稿只出现一张；期间发现生产镜像曾以含未提交改动的源码构建致 standard 档误跑 6 轮，已重建回 3/4 轮）；**用例双编号制**（全局 TC-id 不变防撞号，新增 project_seq 项目内展示序号 #1 起连续、悬浮见全局 id，V12 迁移回填存量，全量重生成归 1 重计，六条创建路径全覆盖）；**未覆盖接口清单**（新端点 /coverage/uncovered-endpoints 与接口覆盖率完全同口径，前端折叠面板列出无用例引用的接口，缺口可操作化）；**覆盖率口径标注**（统计卡 tooltip + 说明行 + 矩阵分母注明，两口径数值不具可比性不再误读）；**执行数据防御三件套**（A prompt 硬约束 ui_action target 严禁 HTTP 形态+uiSelector 类型白名单对齐执行器能力 / B 解析期 sanitizeUiSelectors 清洗非法类型 / C ExecutionAgent 对 `METHOD /path` 形态 target 的 ui_action 自动降级 skip 如实标注）；PrdPanel 保存联动刷新项目状态（保存 PRD 后生成按钮即时解禁）；用户可见文案版本标注泄漏清理。后端 405 测试全绿。

- v7.14 要点：修复真实大项目（220 接口/182 规则）生成 prompt 432KB 触发 300k 保险丝——coverageChecklist 全量详情重复注入治理（G24，旧实现 putAll(toContextMap()) 把接口/规则/依赖完整详情在清单里再灌一遍，159KB 纯冗余；清单只留对账标识字段，消费方核实只读 id/method/path）；context.endpoints/businessRules 容量控制（G25，G17 弱过滤全放行后按相关性降序保留 top-80/top-100 + 截断说明，未入选项仍在清单摘要中可引用）；prd 序列化剥离 ragContexts 原始切片（策展版已单独注入）；embedding 默认端点 404 修复（E17，默认改 DashScope 兼容端点 + text-embedding-v4，docker-compose 空 `:-` 默认值陷阱同步修正——空串环境变量不回落 yml 默认值）；实测场景 432KB→约 200KB；后端 398 测试全绿。
- v7.13 要点：分析器 LLM 增强输入预算配置化并放大至"大项目全覆盖"（Spring 源码总量 16k→120k、单文件 1500→10k≈30-40 个 Java 文件全覆盖；Vue 总量 12k→96k、template/script 800/700→3000/3000≈20-30 个组件全覆盖；总闸 max-prompt-chars 60k→300k，全部 `app.analyzer.*` 环境变量可回调）；规则摘要合法化收敛（`buildRuleSummary` 旧实现 `json.substring(0, 30000)` 会砍出非法 JSON 塞进 prompt，新实现每轮条目 ×0.7 重序列化至 ≤80k、5 轮后兜底 counts-only 骨架，endpointCount 恒为真实总数）；Vue 文件页面优先排序（A9 字典序确定性保留，views/pages/App.vue > components > 其他——纯字典序下 components 会把页面挤出预算，优先级正好反了）；移除死配置 `llm.max-context-chars`（登记后从未被读取）；大项目三层演进方案（分批增强 → map-reduce 摘要 → 按需检索）落盘 `docs/大项目代码分析演进提案.md` 作 v8.x 候选；后端 389 测试全绿。
- v7.12 要点：复审遗留 P1/P2 七项修复——reject 比例分母纠正（R15，分母改为已评审数而非送评总数，截断场景批量 reject 不再被缺评条目稀释，>70% 全保留保护带可靠触发）；选择器池只收 DOM 选择器（G22，表单字段无可执行 value，混池打分胜出会固化废选择器进用例资产）；两侧判重口径对齐（G23，TestCaseService 补 type 一致性要求、重叠率阈值 0.8→0.9、子串规则加 4 字最短门槛——追加生成的负向/边界用例不再被同标题正向旧用例误杀，"登录" vs "退出登录后重新登录" 之类短动词包含不再误杀）；熔断半开探测（L15，开启期过后单探测租约试探 provider 恢复，不再全量放行 doomed 请求风暴，租约超时自愈）；Redis 信号量 ZSET 租约模型（E15，计数器+TTL 在长执行下键过期超发，改为 member=permitId 租约 + 步骤心跳续租 + 按持有者精确释放，JVM 崩溃 5 分钟自愈）；执行报告流式生成（R16，HTML 分段写出、截图逐张"读取→编码→写出→释放"，峰值内存从 2×报告体积降到单截图+base64 缓冲）；SSE 断连不再误报失败（E16，区分后端下发错误与连接层断开，断连降级为进度轮询跟踪）；后端 381 测试全绿。
- v7.11 要点：全量代码审查暴露的 7 项关键缺陷修复——流式 LLM 错误处理（L14，error 信号释放 latch 不再死循环，真实错误优先于取消判定不再谎报"用户取消"）；用例 ID 全局唯一分配器（T1/T2，test_cases.id 是全局主键而历史编号按项目独立分配，跨项目同号经 JPA merge 静默整行覆盖——生成/追加/导入/复制/手动创建五条路径统一走全库 max+1 分配器，批量逐条取号缓存同步推进）；Playwright 多会话隔离（E12，MCP Server 全局单浏览器改为 sessions Map，所有工具带 session_id，Java 侧以 executionId 派生会话，并发执行互不干扰、截图文件名带会话前缀防碰撞）；补测循环收敛（G21，componentIds/dependencyIds 不计入续跑条件，不再无限循环烧 token）；JsonHelper 可变兜底容器（T3，空值/解析失败返回 LinkedHashMap/ArrayList，评审链路不再 UnsupportedOperationException）；排队超时取消保护（E13，cancelled 终态不被翻转为 failed，exec:cancel 残留标志清理）；AgentTask 终态保护（E14，TERMINAL_STATUSES 守卫，succeed/fail/cancel 不再覆盖已终态任务）；后端 353 测试全绿。
- v7.10 要点：缓冲区收尾（风险清单 A/B/C 三区全部完成，剩余仅 D 延后区）——需求 ID 内容 hash 稳定化（G7 补入，`req-` + SHA-256(title+description) 前 10 位，PRD 局部修改不再全量编号漂移，覆盖率历史对比可信）；索引维护移出生成热路径（G19 补入，保存侧四条路径已覆盖，热路径纯读）；流式单解析真源（G8，收集列表为唯一返回值，消除双解析索引错位）；RAG 查询分类配额（G9，requirements 6/modules 3/contextDocs 2/supplementary 1，不再被挤占）；多轮去原文注入（G12，省约 20k token×轮数）；置信度派生（G13，confidence = qualityScore/100）；失败经验库治理（R13+G18，稳定 ID 去重 + 语料补用例标题/页面 URL + 失败专用查询，embedding 调用 12→6）；评审缺评补评（R4，缺失 index 子集二次送评，不再静默）与 reject 三分带（R5，>70% 全保留/40-70% 置信度裁决/≤40% 照删）；thinking 配置诚实化（L3，启动告警 + 注释标注不生效）；选择器匹配收紧（L12，阈值 3 且唯一最高分）；分析器信噪比（A3 多异常全收集/A6 噪音异常过滤/A12 apiCalls 全目录扫描/A18 Integer·String 状态提取）；证据链对账（C2，新鲜度检测 + PRD 状态流 vs 代码状态机冲突时 prompt 显式标注"以代码为准，需人工确认"）；后端 327 测试全绿。
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
| v7.8 | 评审闭环与覆盖率可信（分级采纳/接口匹配收紧/计划执行双栏覆盖率/评分并入评审分） | ✅ 完成 |
| v7.9 | 执行效率与证据存储（生效判断两级化/批量限流/短 ID 加长/复制执行权限开关/证据缺失告警） | ✅ 完成 |
| v7.10 | 缓冲区收尾（需求 ID hash 稳定化/索引维护移出热路径/流式单解析/RAG 分类配额/评审补评与三分带/失败经验治理/证据链对账） | ✅ 完成 |
| v7.11 | 关键缺陷修复（流式错误死循环/用例 ID 全局唯一/Playwright 多会话隔离/补测循环收敛/可变兜底容器/排队取消保护/终态保护） | ✅ 完成 |
| v7.12 | 复审 P1/P2 修复（reject 分母/选择器池纯化/判重口径对齐/熔断半开/Redis 租约信号量/报告流式/SSE 断连降级） | ✅ 完成 |
| v7.13 | 输入截断上限扩容（分析器预算配置化放大/规则摘要合法化/Vue 页面优先排序/死配置清理） | ✅ 完成 |
| v7.14 | 生成 Prompt 重复注入治理（checklist 摘要化/context 容量控制/embedding 404 修复） | ✅ 完成 |
| v7.15 | 执行可信与双编号制（流式跨轮去重/双编号制/未覆盖接口清单/口径标注/执行数据防御三件套） | ✅ 完成 |
| v8.1 | 范围感知基础（Git 基线 diff 识别本期范围/LLM 辅助映射/人工确认锁定/partial clone 改造） | ✅ 完成 |
| v8.2 | 本期聚焦生成（状态机切片/BFS setup 推导/prompt 分层/phase 标记/blocked 语义/生成前置校验升级） | ✅ 完成 |
| v8.3 | 覆盖率口径重构（单一本期口径/全量口径移除/引导态/影响面清单/AI 评审覆盖同步收敛） | ✅ 完成 |
| v8.4 | 256k 上下文扩容与代码审查修复（预算参数化/池快速失败/流式 retryReset/解析容错/向量层加固/SSRF 收敛） | ✅ 完成 |
| v8.5 | 安全闭环（弱默认密钥清零/MCP 回环限制/DNS rebinding 收敛/Grafana 必填/retryReset 前端消费） | ✅ 完成 |
| v8.6.1 | 向量一致性闭环（删除补偿表/ShedLock 重放/周期对账修复/检索幽灵过滤） | ✅ 完成 |
| v8.6.2 | 出参契约化（四 schema 契约/observe-enforce 灰度/括号配平提取） | ✅ 完成 |
| v8.7.1 | 指标埋点+MDC（MetricsFacade/13 项指标/日志链按项目聚合） | ✅ 完成 |
| v8.7.2 | 看板告警+评测体系 v1（两看板三告警/黄金数据集/EvalRunner 回放对比） | ✅ 完成 |
| v8.8.1 | 多供应商双通道+降级路由（fallback 注册/degradedProvider 标注/embedding 独立熔断） | ✅ 完成 |
| v8.8.2 | 双实例就绪+积压可观测+混沌演练（补齐四任务锁/状态 Gauge 两告警/@Tag("chaos") 三场景） | ✅ 完成 |
| v8.9 | 平台化（多租户/协作/OpenAPI/录制编排 CI——按计划书"阶段 5 整体可裁剪"条款裁剪，方向保留待立项） | ⏸ 裁剪 |
| v8.9.1 | 部署层凭据清零+MCP 来源过滤提层（compose 八处 :? 必填/MinIO 必填/McpSourceFilter+trust-proxy 反代适配） | ✅ 完成 |
| v8.9.2 | 连接池对齐+LLM 入口限流（Hikari 40+泄漏检测/LlmRateLimiter 四通道配额+指标） | ✅ 完成 |
| v8.9.3 | 对账内存优化+并发残留清理（id 投影+向量分页/预算对齐 500k/降级标注清残留/meter 缓存） | ✅ 完成 |
| v8.9.4 | 凭据卫生（票据 Redis 双实现+作用域白名单/媒体短票据切换/admin 弱回退清零） | ✅ 完成 |
| v8.9.5 | 水平扩容交付+双实例演练+容量基线首组（扩容指南/三机制实证/短请求 P95=26ms） | ✅ 完成 |
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
