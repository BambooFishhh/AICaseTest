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
| AI/执行 | OpenAI 兼容协议 + MCP（LLM 对话/embedding/多模态/Playwright 浏览器） |
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
- `RETENTION_EXECUTION_DAYS`（执行数据保留天数，0 关闭）
- `HIKARI_MAX_POOL` / `HIKARI_MIN_IDLE`（MySQL 连接池）

> ⚠️ `.env` 已被 `.gitignore` 排除，不会提交到仓库。

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

当前版本：**v5.8（数据治理与可观测）**，并已完成一次 v5 数据层复查修复（2026-08-14）。

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

详细版本历史见 [docs/迭代历程.md](docs/迭代历程.md)，逐版本变更记录见 [docs/CHANGELOG.md](docs/CHANGELOG.md)。

## API 文档

完整 REST API 概览见 [docs/API概览.md](docs/API概览.md)；后端运行时可访问内嵌 Swagger：`/swagger-ui/index.html`。

## License

MIT
