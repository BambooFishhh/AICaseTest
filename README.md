# AICaseTest — AI 驱动的测试用例生成系统

![version](https://img.shields.io/badge/version-v9.3-blue) ![backend-tests](https://img.shields.io/badge/backend%20tests-542%20passing-brightgreen) ![java](https://img.shields.io/badge/Java-17-orange) ![springboot](https://img.shields.io/badge/Spring%20Boot-3.4-6DB33F) ![vue](https://img.shields.io/badge/Vue-3-42B883) ![license](https://img.shields.io/badge/license-MIT-lightgrey)

基于代码分析 + 状态机提取 + LLM 的智能测试用例生成平台：自动分析 Spring Boot / Vue 项目，提取 API 端点与业务状态机，按"本期范围"生成结构化、**AI 可执行**的测试用例，由 Playwright 驱动真实浏览器执行并产出截图 / WebM 录屏 / HTML 报告，形成「分析 → 范围 → 生成 → 执行 → 回归」完整闭环。

## 核心能力

- **代码分析**：扫描 Spring Boot 后端（Controller/Entity/Enum/业务规则）与 Vue 前端，提取端点、表单、校验规则、DOM 选择器与页面路由，附证据与告警可解释
- **状态机提取**：LLM 推断 + 源码赋值点证据校验（verified/unverified 标注），覆盖率不建立在编造之上
- **本期范围（Scope）**：Git 基线 diff 自动识别本次迭代变更的接口/页面，分析完成自动锁定范围，生成只聚焦本期
- **用例生成**：PRD 驱动 + RAG 多路检索，分模块流式生成结构化用例（步骤含 action/target/expected，全 UI 人话化话术），评审 + Linter + 确定性后处理三道质量闸
- **自动执行**：Playwright MCP 真实浏览器，Agent 模式（多模态定位 + LLM 决策）与程序化模式（结构化 uiSelector）双轨；hash 路由感知导航、三层断言（URL/标题 → DOM 文本 → 诚实 skipped）
- **覆盖率与报告**：状态转换 / 接口覆盖率双口径、未覆盖接口清单、执行结果截图/录屏/HTML 报告全证据链
- **语义层**：Milvus 语义去重、生成前 RAG 上下文注入、自然语言语义搜索、失败经验库
- **工程化**：MySQL/Redis/Milvus 全栈、任务持久化与租约恢复、LLM 熔断与多供应商降级、Prometheus 指标 + Grafana 看板告警、双实例水平扩容

## 架构总览

```mermaid
flowchart LR
    subgraph client["浏览器"]
        FE["Vue 3 + Element Plus"]
    end
    subgraph backend["Spring Boot 后端"]
        API["REST API / SSE"]
        GEN["生成 Agent 链路"]
        EXEC["执行 Agent 链路"]
    end
    LLM["LLM<br/>文本 / 流式 / Embedding"]
    MILVUS["Milvus 2.4<br/>语义去重 / RAG"]
    DB[("MySQL 8<br/>Flyway")]
    REDIS[("Redis 7<br/>运行态 / 队列")]
    PW["Playwright MCP<br/>真实浏览器 + 录屏"]

    FE -->|REST + SSE| API
    API --> GEN
    API --> EXEC
    GEN --> LLM
    GEN --> MILVUS
    EXEC --> PW
    PW -->|截图 / WebM 录屏| EXEC
    API --> DB
    API --> REDIS
    EXEC --> DB
```

## 技术栈

| 层 | 技术 |
|----|------|
| 后端 | Java 17、Spring Boot 3.4、Spring Data JPA、Spring Security/JWT、Spring AI 1.0（OpenAI starter） |
| 数据层 | MySQL 8（Flyway 管理）/ H2（开发 profile） |
| 运行态 | Redis 7（取消标志/心跳/并发配额/防爆破/任务队列） |
| 语义层 | Milvus 2.4 + LLM Embedding（语义去重/RAG/搜索/失败经验库） |
| 前端 | Vue 3、Vite、Element Plus、Pinia、ECharts、markmap |
| AI/执行 | Spring AI 承载 LLM 调用；MCP 桥接 Playwright 浏览器执行与多模态视觉定位（详见 [docs/spring-ai-migration.md](docs/spring-ai-migration.md)） |
| 部署 | Docker、docker-compose（MySQL + Redis + Milvus + Grafana） |

## 快速开始

### 环境要求

- JDK 17+、Node.js 18+、Maven 3.8+
- 生产全栈需 Docker（MySQL / Redis / Milvus）

### 配置（必需）

```bash
cp .env.example .env
```

编辑 `.env` 至少填入以下变量——**任何 profile 下四把安全密钥缺失任一项后端拒绝启动**（本地开发允许弱值，生产另有强度校验）：

```
APP_JWT_SECRET=...
APP_ADMIN_PASSWORD=...        # admin 初始密码，首次登录强制修改
MILVUS_PASSWORD=...
MCP_BRIDGE_TOKEN=...
LLM_API_KEY=...               # Agent 模式执行与生成必需
LLM_BASE_URL=... / LLM_MODEL=...
```

其余可选项（连接池、RAG、限流、执行超时等）见 [.env.example](.env.example) 内注释。

### Docker 一键启动（推荐）

```bash
docker compose up -d
```

依次启动 MySQL（宿主端口 3308）、Redis、Milvus、后端（8000，内置 Chromium 可直接执行）与前端（Nginx，80/443）。部署前可用 `docker compose config --quiet` 自查密钥是否齐全。

### 本地开发

```bash
cd backend && mvn spring-boot:run -Dmaven.repo.local=../.m2-repo   # http://localhost:8000
cd frontend && npm install && npm run dev                          # http://localhost:5173
```

启动后访问前端，使用 `admin` + `APP_ADMIN_PASSWORD` 登录（首次登录强制改密），或注册新账号。

## 用例执行

1. 测试用例页选择用例 →「执行 / 批量执行」，填入待测页面 URL
2. 选模式：**Agent 模式**（LLM 多模态识别 + DOM 兜底，推荐）或**程序化模式**（按结构化 uiSelector 直接操作）
3. 执行历史 / 执行结果页查看步骤、截图、WebM 录屏、HTML 报告与证据文件

要点：单批上限 100 条（超限报 50014）；用例需含结构化步骤，纯自然语言步骤在程序化模式下跳过；多实例部署需将 `outputs/` 配置为共享卷（证据文件在实例本地）；本地裸跑首次需 `cd playwright-mcp-server && npx playwright install chromium`。

## 测试与运维

- 后端 542 个单元/集成测试（含 Testcontainers MySQL/Redis）+ JaCoCo 门禁；前端 Vitest 10 例 + 覆盖率阈值
- 回归入口 `scripts/verify-v5-stack.ps1`；安全自查 `scripts/security-check.ps1`；备份 `scripts/backup-v5.ps1`
- 可观测：`/actuator/health`、`/actuator/prometheus` + Grafana 看板与告警；运维手册见 [docs/运维手册.md](docs/运维手册.md)

## 项目结构

```
AICaseTest/
├── backend/                  # Spring Boot 后端
│   └── src/main/java/com/testagent/
│       ├── analyzer/         # 代码分析器（Spring/Vue 扫描）
│       ├── agent/            # AI 用例生成/状态机/执行 Agent
│       ├── controller/       # REST API
│       ├── service/          # 业务服务
│       ├── entity/ dto/ repository/   # JPA 实体与数据访问
│       ├── security/ runtime/ queue/  # JWT 认证/Redis 运行态/任务队列
│       └── migration/        # H2 → MySQL 迁移工具
├── frontend/                 # Vue 3 前端（views/components/stores/api）
├── mcp-server/               # LLM MCP Server（chat / embedding / 多模态定位）
├── tools-mcp-server/         # 语义检索/需求解析/状态机/评审工具 MCP
├── playwright-mcp-server/    # Playwright MCP Server（浏览器操作 + 录屏）
├── scripts/                  # 验证/备份/安全自查/压测脚本
├── docs/                     # 版本三件套（PRD+前后端评审）/CHANGELOG/运维手册/API
└── docker-compose.yml
```

## 文档

每个功能版本在 `docs/vX.Y.Z/` 维护**三件套**（PRD + 后端技术评审 + 前端技术评审），最新：

- [v9.3](docs/v9.3/) litemall 实测回归修复 · [v9.2](docs/v9.2/) 用例人话化+执行器修复 · [v9.1](docs/v9.1/) 生成与 SSE 解耦 · [v9.0](docs/v9.0/) 范围全自动

其它：[CHANGELOG](docs/CHANGELOG.md) · [迭代历程](docs/迭代历程.md) · [API 概览](docs/API概览.md) · [运维手册](docs/运维手册.md) · [水平扩容指南](docs/水平扩容指南.md) · [容量基线报告](docs/容量基线报告.md) · [长期迭代计划书](docs/长期迭代计划书.md)

## 版本

当前 **v9.3**：litemall 实测回归修复（断言占位符元描述剔除 / 编号模块归组重编 / 面包屑口径）。近期版本：

- v9.2 用例人话化 + 执行器导航与断言修复（skill 根因重写 / Linter 五项 / hash 路由感知 / 流式重影修复）
- v9.1 生成与 SSE 解耦（断连不取消 / 事件持久化 / attach 无缝续播）
- v9.0 本期范围全自动（分析完成自动锁定 / G17 顺序修复接口全量进上下文）

<details>
<summary>完整版本线（v1.0 → v9.3）</summary>

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
| v5.9 | 项目上下文与操作体验优化 | ✅ 完成 |
| v5.10 | PRD 上下文改版与用例级执行历史 | ✅ 完成 |
| v5.11 | 生成链路 AI 评审与前端体验 | ✅ 完成 |
| v5.12 | AI 评审闭环与覆盖引用收口 | ✅ 完成 |
| v5.13 | 能力分层：MCP 工具化与 Prompt Skill 化 | ✅ 完成 |
| v6.0 | Spring AI 迁移（文本/流式/JSON/Embedding） | ✅ 完成 |
| v6.1 | 前端 Agentic RAG + 后端 SAINT 操作依赖图 | ✅ 完成 |
| v6.2 | 分析并行化与状态机收口 | ✅ 完成 |
| v6.3 | 本地代码审查整改 | ✅ 完成 |
| v6.4 | RAG 切片化与多源检索增强 | ✅ 完成 |
| v6.5 | 高可用 P0（任务持久化/租约恢复/重试分类） | ✅ 完成 |
| v6.6 | 高可用 P1（执行接入 agent_task/MCP 超时/TTL） | ✅ 完成 |
| v6.7 | 高可用 P2（断点续跑/降级标记/熔断/任务中心） | ✅ 完成 |
| v6.8 | 高可用 P3（Redis Streams 事件总线/CAS 抢占） | ✅ 完成 |
| v6.9 | 高可用收口（timeline 回放/故障演练/容量基线） | ✅ 完成 |
| v7.0 | 执行可信度修复（取消复活/假通过/状态断言） | ✅ 完成 |
| v7.1 | 生成链路一致性修复（判重误杀/推送落库差异） | ✅ 完成 |
| v7.2 | 度量与报告诚实化（假字段删除/通过率口径） | ✅ 完成 |
| v7.3 | LLM 组件稳定与生成质量约束 | ✅ 完成 |
| v7.4 | 分析器规则层加固 | ✅ 完成 |
| v7.5 | 缓存与可复现基线（PRD/组件摘要缓存） | ✅ 完成 |
| v7.6 | 状态机与断言闭环（转换证据/三层断言） | ✅ 完成 |
| v7.7 | 上下文精准投喂（RAG 进清单/关键词过滤） | ✅ 完成 |
| v7.8 | 评审闭环与覆盖率可信（分级采纳/双栏口径） | ✅ 完成 |
| v7.9 | 执行效率与证据存储（批量限流/短 ID 加长） | ✅ 完成 |
| v7.10 | 缓冲区收尾（hash 稳定化/流式单解析/评审补评） | ✅ 完成 |
| v7.11 | 关键缺陷修复（流式死循环/ID 全局唯一/多会话隔离） | ✅ 完成 |
| v7.12 | 复审 P1/P2 修复（判重口径/熔断半开/租约信号量） | ✅ 完成 |
| v7.13 | 输入截断上限扩容（分析器预算配置化） | ✅ 完成 |
| v7.14 | 生成 Prompt 重复注入治理 | ✅ 完成 |
| v7.15 | 执行可信与双编号制（跨轮去重/双编号/未覆盖清单） | ✅ 完成 |
| v8.1 | 范围感知基础（Git diff 识别/LLM 辅助映射/人工确认） | ✅ 完成 |
| v8.2 | 本期聚焦生成（状态机切片/BFS setup/phase 标记） | ✅ 完成 |
| v8.3 | 覆盖率口径重构（单一本期口径/影响面清单） | ✅ 完成 |
| v8.4 | 256k 上下文扩容与代码审查修复 | ✅ 完成 |
| v8.5 | 安全闭环（弱默认密钥清零/MCP 回环/DNS rebinding） | ✅ 完成 |
| v8.6.1 | 向量一致性闭环（删除补偿/周期对账/幽灵过滤） | ✅ 完成 |
| v8.6.2 | 出参契约化（四 schema/灰度开关/括号配平） | ✅ 完成 |
| v8.7.1 | 指标埋点 + MDC（MetricsFacade/13 项指标） | ✅ 完成 |
| v8.7.2 | 看板告警 + 评测体系 v1（黄金数据集/回放对比） | ✅ 完成 |
| v8.8.1 | 多供应商双通道 + 降级路由 | ✅ 完成 |
| v8.8.2 | 双实例就绪 + 积压可观测 + 混沌演练 | ✅ 完成 |
| v8.9 | 平台化方向（按计划书裁剪条款裁剪，待立项） | ⏸ 裁剪 |
| v8.9.1 | 部署层凭据清零 + MCP 来源过滤提层 | ✅ 完成 |
| v8.9.2 | 连接池对齐 + LLM 入口限流 | ✅ 完成 |
| v8.9.3 | 对账内存优化 + 并发残留清理 | ✅ 完成 |
| v8.9.4 | 凭据卫生（票据 Redis 双实现/媒体短票据） | ✅ 完成 |
| v8.9.5 | 水平扩容交付 + 双实例演练 + 容量基线首组 | ✅ 完成 |
| v8.9.6 | VueAnalyzer 共享池 + 并发压测补数 | ✅ 完成 |
| v8.9.8 | litemall 实测四卡修复（移动模拟/登录态注入/导航） | ✅ 完成 |
| v9.0 | 范围全自动（自动锁定/默认主干探测/G17 顺序修复） | ✅ 完成 |
| v9.1 | 生成与 SSE 解耦（断连不取消/attach 续播） | ✅ 完成 |
| v9.2 | 用例人话化 + 执行器修复（skill 重写/hash 导航） | ✅ 完成 |
| v9.3 | 实测回归修复（断言元描述/编号重编/面包屑） | ✅ 完成 |
| vT1–vT9 | 测试与运维基线（单测/集成/前端/安全/回归收口） | ✅ 完成 |
| vP1–vP5 | 上线加固（TLS/容灾/可观测/发布流水线/压测容量） | ✅ 完成 |

逐版本详细变更见 [docs/CHANGELOG.md](docs/CHANGELOG.md) 与 [docs/迭代历程.md](docs/迭代历程.md)。

</details>

## API 文档

REST API 概览见 [docs/API概览.md](docs/API概览.md)；后端运行时 Swagger：`/swagger-ui/index.html`。

## License

MIT
