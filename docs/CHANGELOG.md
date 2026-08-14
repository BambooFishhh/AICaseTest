# 变更记录 (CHANGELOG)

本项目迭代基于 v1.0 MVP，目标演进为高可用、AI 可执行、高可视化的 AI 用例生成系统。

---

## vT9 — 安全扫描与部署加固
**日期**: 2026-08-14
**基线**: vT8
**主题**: .env.example + nginx 加固 + Redis AUTH + CI 安全/镜像 + 运维手册

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| .env.example | 新增 | 全量环境变量模板 |
| docker-compose.yml | Redis AUTH + REDIS_PASSWORD | 运行态安全 |
| .github/workflows/ci.yml | security/docker job | gitleaks + npm audit + 镜像构建 |
| docs/运维手册.md | 新增 | 部署/升级/备份/监控/排障 |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| frontend/nginx.conf | 安全头/登录限流/上传限制/gzip | Nginx 加固 |

### 验证结果

- docker compose config: 校验通过
- 前端构建: ✓ built
- 安全扫描: security check OK

---

## vT8 — 前端测试扩充与覆盖率门禁
**日期**: 2026-08-14
**基线**: vT7
**主题**: auth store 测试 + Vitest/JaCoCo 覆盖率门禁

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| backend/pom.xml | JaCoCo 插件 | LINE/INSTRUCTION ≥ 5% 检查 |
| .github/workflows/ci.yml | mvn -B verify | 触发 JaCoCo 检查 |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| src/stores/auth.test.js | 新增 2 个测试 | 登录持久化/登出清理 |
| vitest.config.js | v8 coverage + 阈值 | 覆盖率门禁 |
| package.json / package-lock.json | @vitest/coverage-v8 + test:coverage | 覆盖率脚本 |

### 验证结果

- 前端测试: Tests 7 passed（npm test --coverage，阈值通过）
- 前端构建: ✓ built
- 后端 verify: BUILD SUCCESS，JaCoCo checks met
- npm ci: 干净安装通过（391 packages）

---

## vT7 — Testcontainers 集成测试
**日期**: 2026-08-14
**基线**: vT6
**主题**: MySQL Flyway / Redis RuntimeStore 真实中间件集成测试

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| backend/pom.xml | testcontainers junit-jupiter/mysql | 集成测试依赖 |
| test/MySqlFlywayIntegrationTest.java | 新增 2 个测试 | MySQL Flyway V1/V2 + JPA 读写 |
| test/runtime/RedisRuntimeStoreIntegrationTest.java | 新增 3 个测试 | Redis 标志/登录计数/信号量 |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| 无 | - | 本版本无前端代码变更 |

### 验证结果

- 后端测试: Tests run 34, Failures 0, Skipped 5（本地无 Docker 自动跳过，CI 有 Docker 时运行）

---

## vT6 — 服务层与接口安全测试
**日期**: 2026-08-14
**基线**: vT5
**主题**: MockMvc API 安全测试 + ProjectService 级联测试 + 401 响应 NPE 修复

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| security/SecurityConfig.java | 修复 401 响应 NPE | Map.of 不允许 null，改用 LinkedHashMap |
| mcp/McpClientManager.java | app.mcp.enabled 开关 | 测试环境不拉起 MCP 子进程 |
| resources/application.yml | mcp.enabled 配置 | 默认 true |
| test/SecurityApiTest.java | 新增 5 个测试 | 健康公开/401/403/429 登录锁定 |
| test/service/ProjectServiceTest.java | 新增 1 个测试 | 项目级联删除 |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| 无 | - | 本版本无前端代码变更 |

### 验证结果

- 后端测试: Tests run 29, Failures 0（mvn test）

---

## vT5 — 安全与全量回归收口
**日期**: 2026-08-14
**基线**: vT4
**主题**: 敏感信息扫描 + 回归入口收口 + CI 锁文件兼容修复

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| scripts/security-check.ps1 | 新增 | .env 跟踪校验 + 密钥/私钥扫描 |
| scripts/verify-v5-stack.ps1 | 集成安全基线 | 回归入口包含安全检查 |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| package.json / package-lock.json | vitest 4.x → 3.x | 修复 CI npm 10 下 `npm ci` 与锁文件不同步问题 |

### 验证结果

- 后端测试: Tests run 23, Failures 0（mvn test）
- 前端测试: Tests 5 passed（npm test）
- 前端构建: ✓ built（npm run build）
- npm ci: 干净安装通过（379 packages）
- docker compose config: 校验通过
- 安全基线: security check OK

---

## vT4 — 运维与可观测基线
**日期**: 2026-08-14
**基线**: vT3
**主题**: Actuator/Prometheus 指标 + 备份脚本

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| backend/pom.xml | actuator + micrometer-prometheus | 标准健康/指标端点 |
| resources/application.yml | management 配置 | health/info/metrics/prometheus 暴露 |
| security/SecurityConfig.java | /actuator/health permitAll | 健康检查免认证 |
| scripts/backup-v5.ps1 | 新增 | data/outputs/MySQL 备份 |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| 无 | - | 本版本无前端代码变更 |

### 验证结果

- 后端测试: Tests run 23, Failures 0（mvn test）
- 前端构建: ✓ built（npm run build）
- docker compose config: 校验通过
- Actuator 冒烟: /actuator/health 免认证 UP；/actuator/prometheus 认证后返回指标

---

## vT3 — 前端测试基线
**日期**: 2026-08-14
**基线**: vT2
**主题**: Vitest + Vue Test Utils 前端测试

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| 无 | - | 本版本后端无代码变更 |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| vitest.config.js | 新增 | jsdom + @ 别名 |
| src/utils/stateLabel.test.js | 新增 3 个测试 | 状态文案翻译 |
| src/components/ProgressTracker.test.js | 新增 2 个测试 | 组件状态渲染 |
| package.json / package-lock.json | 新增 vitest/@vue/test-utils/jsdom | 测试依赖与 test script |
| .github/workflows/ci.yml | frontend job 增加 npm test | CI 测试门禁 |

### 验证结果

- 前端测试: Tests 5 passed（npm test）
- 前端构建: ✓ built（npm run build）

---

## vT2 — 服务层与集成测试
**日期**: 2026-08-14
**基线**: vT1
**主题**: 安全（JWT）、工具类、JPA Repository 测试基线

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| test/security/JwtUtilTest.java | 新增 3 个测试 | 签发/解析/非法与过期 token |
| test/security/JwtAuthFilterTest.java | 新增 3 个测试 | Bearer 认证、缺失/非法 token |
| test/dto/JsonHelperTest.java | 新增 3 个测试 | Map/List 解析与容错 |
| test/repository/TestCaseVersionRepositoryTest.java | 新增 1 个测试 | H2 JPA 保存/查询/删除 |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| 无 | - | 本版本无前端代码变更 |

### 验证结果

- 后端测试: Tests run 23, Failures 0（mvn test）

---

## vT1 — 测试与运维基线
**日期**: 2026-08-14
**基线**: v5.8 + v5 数据层复查修复
**主题**: 建立独立工程基线版本线（vT 系列），补齐测试与运维基线

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| test/runtime/MemoryRuntimeStoreTest.java | 新增 5 个测试 | 取消标志/会话/心跳/登录计数/并发配额 |
| test/queue/MemoryTaskQueueStoreTest.java | 新增 3 个测试 | 队列计数、多队列独立、幂等 |
| test/service/LoginAttemptServiceTest.java | 新增 3 个测试 | 5 次锁定、阈值内不锁、成功后清状态 |
| .github/workflows/ci.yml | 新增 compose job | `docker compose config --quiet` 配置校验 |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| 无 | - | 前端仅作 CI 构建回归 |

### 验证结果

- 后端测试: Tests run 13, Failures 0（mvn test）
- 前端构建: ✓ built（npm run build）
- docker compose config: 校验通过
- MCP 语法: node --check 通过

---

## 修复记录 — v5 数据层复查（v5.6~v5.8）
**日期**: 2026-08-14
**主题**: 重新生成误清语义上下文 / 取消标志残留 / Milvus 已有集合未建索引 / 迁移 dry-run 顺序

### 修复内容

| 文件 | 修复 | 说明 |
|---|---|---|
| service/SemanticService.java | 新增 clearCases，clearProject 仅用于项目删除 | 重新生成不再误删 contexts/failures |
| service/TestCaseService.java | 重新生成改用 clearCases；取消无任务时不再写残留标志；生成前清残留取消标志 | 修复取消标志导致下一次生成被误取消 |
| service/MilvusService.java | 已有集合也补建 ANN 索引并加载 | 修复存量集合仍走暴力扫描 |
| migration/H2ToMysqlMigrator.java | dry-run 提前到备份之前 | dry-run 只统计不产生备份文件 |

### 验证结果

- 后端编译: BUILD SUCCESS（140 源文件）
- 后端测试: Tests run 2, Failures 0（mvn test）
- 前端构建: ✓ built in 16.40s

---

## v5.8 — 数据治理与可观测
**日期**: 2026-08-14
**基线**: v5.7
**主题**: 执行数据保留策略 + 数据健康检查 API + 迁移 dry-run

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| service/DataRetentionService.java | 新增 | 定时清理过期终态执行/步骤/录屏/证据 |
| service/DataHealthService.java | 新增 | 表计数 + 孤儿数据 + Milvus 行数 |
| controller/DataHealthController.java | 新增 | GET /api/admin/data/health |
| security/SecurityConfig.java | /api/admin/** 仅 ADMIN | 数据健康接口权限 |
| config/AsyncConfig.java | @EnableScheduling | 定时任务开关 |
| repository/ExecutionRecordRepository.java | findByEndTimeBeforeAndStatusIn | 保留策略查询 |
| service/MilvusService.java | countCollection | 集合行数统计 |
| migration/H2ToMysqlMigrator.java | dry-run 模式 | 只统计不写库 |
| resources/application.yml | retention 配置 | execution-days / cron |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| 无 | - | 本版本无前端代码变更 |

### 验证结果

- 后端编译: BUILD SUCCESS（140 源文件）
- 后端测试: Tests run 2, Failures 0（mvn test）
- 前端构建: ✓ built in 15.83s
- 数据健康 API 冒烟: 表计数/孤儿/Milvus 结构正确；无 token 返回 401

---

## v5.7 — 数据索引与查询性能
**日期**: 2026-08-14
**基线**: v5.6
**主题**: Flyway 复合索引 + 执行历史分页 + 连接池调优 + Milvus ANN 索引

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| resources/db/migration/mysql/V2__add_performance_indexes.sql | 新增 | 6 组高频查询复合索引 |
| service/ExecutionService.java | 分页 + stats/trend | 执行历史全量统计一次返回 |
| controller/ExecutionController.java | page/pageSize 参数 | 分页接口 |
| resources/application-mysql.yml | Hikari 参数化 | 连接池调优 |
| service/MilvusService.java | createIndex(IVF_FLAT) + loadCollection | ANN 检索 |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| api/execution.js | getExecutions 支持 params | 分页参数 |
| views/ExecutionHistory.vue | 分页器 + 后端 stats/trend | 表格分页，统计保持全量口径 |

### 验证结果

- 后端编译: BUILD SUCCESS（137 源文件）
- 后端测试: Tests run 2, Failures 0（mvn test）
- 前端构建: ✓ built in 14.26s
- MySQL 冒烟: Flyway V2（add performance indexes）success=1
- 分页接口冒烟: `{items,total,page,pageSize,stats,trend}` 结构正确

---

## v5.6 — 数据一致性与生命周期
**日期**: 2026-08-14
**基线**: v5.5
**主题**: 事务落库、项目级联清理、用例增删改/导入/复制同步语义索引、上下文按模块替换

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| service/TestCasePersistenceService.java | 新增 | 重新生成用例事务化落库 |
| service/MilvusService.java | deleteByIds / deleteByModule | 向量按 ID/模块删除 |
| service/SemanticService.java | removeCases / reindexCase / replaceContext / clearProject 三集合 | 语义生命周期同步 |
| service/TestCaseService.java | 增删改/导入/复制同步索引；删除同步清版本 | 数据库与向量一致 |
| service/ProjectService.java | 删除项目级联清理执行/步骤/测试集/版本/向量 | 消除孤儿数据 |
| service/AnalysisService.java | 上下文 replace | 分析结果按模块替换 |
| repository/* | deleteByProjectId / findByExecutionIdIn | 级联删除支撑 |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| 无 | - | 本版本无前端代码变更 |

### 验证结果

- 后端编译: BUILD SUCCESS（137 源文件）
- 后端测试: Tests run 2, Failures 0（mvn test）
- 前端构建: ✓ built in 14.15s

---

## v5.5 — 正式切换 MySQL + Redis + Milvus
**日期**: 2026-08-14
**基线**: v5.4
**主题**: prod 默认全栈切换，H2 保留为开发 profile；全量回归 + 轻量压测

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| resources/application.yml | profile group prod/migrate → mysql；Redis 默认 host 127.0.0.1 | 修复 profile 专用文件 include 非法问题 |
| resources/application-prod.yml | 默认启用 Redis/Milvus | 正式切换全栈 |
| resources/application-migrate.yml | 移除非法 include | 迁移 profile 复用 group |
| controller/HealthController.java / dto/HealthDTO.java | 组件健康状态 | dataSource / redis / milvus |
| docker-compose.yml | 后端依赖 mysql/redis/milvus + 连接环境变量 | 全栈编排 |
| scripts/verify-v5-stack.ps1 | 新增 | 全量回归脚本 |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| 无 | - | 本版本无前端代码变更 |

### 验证结果

- 后端测试: Tests run 2, Failures 0（mvn test）
- 前端构建: ✓ built in 14.91s
- docker compose config 校验通过
- prod + MySQL + Redis 冒烟：`/api/health` dataSource=UP / redis=redis / version=5.5.0
- 登录防爆破实测：5 次失败后 Redis 出现 `rt:login:admin`，第 6 次返回 429
- 轻量压测：50 次健康请求全部成功，耗时 369ms

---

## v5.4 — Milvus 语义检索层
**日期**: 2026-08-14
**基线**: v5.3
**主题**: embedding 管道 + 语义去重 + RAG 上下文检索 + 语义搜索 + 失败经验库

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| mcp-server/index.js | 新增 llm_embedding 工具 | OpenAI 兼容 embeddings |
| service/EmbeddingService.java / MilvusService.java / SemanticService.java | 新增 | 向量化 + Milvus 集合 + 语义能力 |
| dto/PrdAnalysisResult.java | 新增 ragContexts | RAG 上下文透传 |
| agent/OrchestratorAgent.java / TestGeneratorAgent.java | RAG 注入 | 生成前 Top-K 上下文进 prompt |
| service/TestCaseService.java | 语义去重/索引 | 追加生成相似度判重 + 重建索引 |
| service/ProjectService.java / AnalysisService.java | 上下文入库 | PRD/后端/前端分析写入 contexts |
| service/ExecutionService.java | 失败经验库 | 失败步骤写入 failures |
| controller/TestCaseController.java | 语义搜索 API | GET /testcases/semantic-search |
| application.yml / docker-compose.yml | Milvus 配置 | etcd + minio + milvus standalone |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| api/testcase.js | 新增 semanticSearch | 语义搜索 API |
| views/TestCaseList.vue | 新增语义搜索对话框 | 自然语言检索并查看用例 |

### 验证结果

- 后端编译: BUILD SUCCESS（136 源文件）
- 后端测试: Tests run 2, Failures 0（mvn test）
- 前端构建: ✓ built in 15.32s
- MCP 语法: node --check 通过（llm + playwright）

---

## v5.3 — 缓存与任务队列
**日期**: 2026-08-14
**基线**: v5.2
**主题**: Spring Cache（设置/参数/分析结果）+ 生成/执行任务队列统计

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| config/CacheConfig.java | 新增 | Redis/内存 CacheManager 按开关选择 |
| queue/* | 新增 TaskQueueStore 三件套 | 内存/Redis 任务队列计数 |
| service/TaskQueueService.java | 新增 | 生成/执行队列封装与统计 |
| controller/TaskController.java | 新增 | GET /api/tasks/stats |
| service/SettingsService.java / ProjectService.java | 缓存注解 | 设置/参数读缓存、写失效 |
| service/AnalysisService.java / controller/AnalysisController.java | 分析缓存 | 分析/状态机缓存 + 重新分析 evict |
| controller/ProjectController.java / service/TestCaseService.java / ExecutionService.java | 队列接入 | 任务 enqueue/running/done |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| api/task.js | 新增 | 任务统计 API |
| views/Dashboard.vue | 新增任务队列区块 | 展示生成/执行排队与运行计数 |

### 验证结果

- 后端编译: BUILD SUCCESS（133 源文件）
- 后端测试: Tests run 2, Failures 0（mvn test）
- 前端构建: ✓ built in 14.12s

---

## v5.2 — Redis 运行态接入
**日期**: 2026-08-14
**基线**: v5.1
**主题**: 取消标志/心跳/并发配额/登录防爆破迁至 Redis，保留内存降级

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| runtime/* | 新增 RuntimeStore / Memory / Redis / Flag / Config | 运行态存储抽象，Redis 不可用时降级内存 |
| service/TestCaseService.java | 取消标志改 RuntimeFlag | 生成/追加取消支持多实例 |
| service/ExecutionService.java | 取消/会话/心跳改 RuntimeStore | 执行取消与 worker 心跳 Redis 化 |
| service/ProjectExecutionLimiter.java | 配额改 RuntimeStore | Redis Lua 计数信号量 |
| service/LoginAttemptService.java | 防爆破改 RuntimeStore | 登录锁定跨实例生效 |
| agent/TestGeneratorAgent.java / OrchestratorAgent.java | 取消参数改 CancellationSignal | 链路解耦 AtomicBoolean |
| application.yml | 新增 spring.data.redis | 连接配置 |
| docker-compose.yml | 新增 aicasetest-redis | Redis 7 容器 |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| 无 | - | 本版本无前端代码变更 |

### 验证结果

- 后端编译: BUILD SUCCESS（126 源文件）
- 后端测试: Tests run 2, Failures 0（mvn test）
- 前端构建: ✓ built in 13.70s

---

## v5.1 — H2 → MySQL 全量迁移工具
**日期**: 2026-08-14
**基线**: v5.0
**主题**: 全量数据迁移 + 备份/回滚 + 方言回归

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| migration/H2ToMysqlMigrator.java | 新增 | 通用 JDBC 迁移器：H2 备份 → 逐表复制 → 行数校验 → 失败清理 |
| resources/application-migrate.yml | 新增 | migrate profile，复用 mysql profile 作为目标库 |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| 无 | - | 本版本无前端代码变更 |

### 验证结果

- 后端编译: BUILD SUCCESS（120 源文件）
- 后端测试: Tests run 2, Failures 0（mvn test）
- 前端构建: ✓ built in 15.27s

---

## v5.0 — 数据层准备
**日期**: 2026-08-14
**基线**: v4.4
**主题**: Flyway schema 版本管理 + 双数据源 profile（dev=H2 / prod=MySQL）+ MySQL 容器基建

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| pom.xml | 新增 flyway-core + flyway-mysql + mysql-connector-j | MySQL 驱动与 schema 版本管理 |
| resources/application.yml | 新增 `spring.flyway.enabled=false` | H2 开发环境不启用 Flyway |
| resources/application-mysql.yml | 新增 | MySQL profile：Flyway 开启、JPA ddl-auto=none |
| resources/application-prod.yml | 改为 include mysql | 生产走 MySQL |
| resources/db/migration/mysql/V1__init_schema.sql | 新增 | 13 张业务表 MySQL 基线 schema + 索引 |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| 无 | - | 本版本无前端代码变更 |

### 验证结果

- 后端编译: BUILD SUCCESS（mvn compile，119 源文件）
- 前端构建: ✓ built in 27.58s

---

## v4.4 — 分析流式化

**日期**: 2026-08-13
**基线**: v4.3
**主题**: 代码分析改为 SSE 流式推送实时阶段进度

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| service/AnalysisService.java | 进度回调 + 流式分析 | 阶段：扫描结构/后端/前端/状态机/完成；runAnalysisStream 推送 progress/complete/error |
| controller/ProjectController.java | analyze-stream 端点 | GET /api/projects/{id}/analyze-stream（操作权限 + created/failed 守卫） |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| views/ProjectDetail.vue | 开始分析改用 EventSource | 实时显示阶段进度；完成/失败自动刷新；卸载关闭连接 |

### 验证结果

- 后端测试: Tests run 2, Failures 0, BUILD SUCCESS
- 前端构建: ✓ built in 14.21s

---

## v4.3 — 项目组与权限

**日期**: 2026-08-13
**基线**: v4.2
**主题**: 项目组共享 + 成员角色权限 + 复制执行隔离

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| entity/ProjectGroup + GroupMember + 仓库 | 项目组与成员 | VIEWER/OPERATOR 角色 |
| service/GroupService + GroupController + UserController | 组 CRUD + 成员管理 + 用户查询 | 仅组创建者可指派 |
| entity/Project + ProjectDTO | groupId + accessLevel | 项目归属组与当前用户访问级别 |
| service/ProjectAccessService | OWNER/OPERATOR/VIEWER 分级 | 读=view，写/执行=operate |
| service/ExecutionService + controller | 复制执行 | copy-execute 快照执行，不回写原用例状态 |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| views/Groups.vue + api/group.js + api/user.js | 项目组管理页 | 建组/编辑/删除/成员角色管理 |
| router + App.vue | /groups 路由 + 侧边栏入口 | - |
| views/ProjectCreate.vue | 创建项目可选所属组 | - |
| views/ProjectList.vue | 组徽标 + 只读标记 | - |
| views/TestCaseList.vue | 权限门控 + 复制执行 | VIEWER 只读；复制执行独立批次 |
| views/ProjectDetail.vue | 操作区权限门控 | VIEWER 隐藏写操作 + 只读提示 |

### 验证结果

- 后端测试: Tests run 2, Failures 0, BUILD SUCCESS
- 前端构建: ✓ built in 16.17s

---

## v4.2 — 多线程高并发治理

**日期**: 2026-08-13
**基线**: v4.1
**主题**: 线程池参数化 + 独立执行池 + 项目级并发配额 + 批量排队/取消 + 幂等

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| config/AsyncConfig.java + application.yml | 线程池参数化 + executionExecutor | 批量执行不再占用分析池；参数可环境变量调整 |
| service/ProjectExecutionLimiter.java | 项目级并发配额 | 默认每项目同时执行 ≤3，超出排队 |
| service/ExecutionService.java | 排队/取消/幂等 | 批量记录 pending→running；cancelBatch；运行中步骤检查点停止；重复触发拒绝 |
| repository/ExecutionRecordRepository.java | 状态查询 | findByTestCaseIdAndStatus |
| controller/ExecutionController.java | 批次取消端点 | POST /api/batches/{id}/cancel |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| views/BatchResult.vue | 排队/已取消统计 + 取消批次 | 排队中/已取消卡片；取消按钮与确认 |
| views/ExecutionResult.vue / ExecutionHistory.vue | cancelled 状态展示 | 已取消标签 |
| api/execution.js | cancelBatch | - |

### 验证结果

- 后端测试: Tests run 2, Failures 0, BUILD SUCCESS
- 前端构建: ✓ built in 11.94s

---

## v4.1 — 安全校验与权限

**日期**: 2026-08-12
**基线**: v4.0
**主题**: 登录防爆破 + 密码策略与改密 + CORS 收敛 + 审计完善

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| service/LoginAttemptService.java | 新增防爆破 | 5 次失败锁定 5 分钟 |
| dto/ChangePasswordRequest + AuthService/Controller | 修改密码 | 校验旧密码 + 新密码策略 |
| dto/RegisterRequest.java | 密码策略 | ≥8 位含字母和数字 |
| config/WebConfig + application.yml | CORS 白名单 | 默认 localhost:3000/5173/8080 |
| entity/ExecutionRecord + ExecutionService | 操作人 | 执行记录记录登录用户名 |
| service/TestCaseService | 评审人取登录态 | 不再信任前端传参；移除异步生成误报 401 的断言 |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| App.vue + api/auth.js | 修改密码弹窗 | 用户菜单入口 |
| views/Register.vue | 密码规则更新 | 8-64 位含字母数字 |
| views/TestCaseList.vue | 移除评审人输入 | 后端取登录态 |

### 验证结果

- 后端测试: Tests run 2, Failures 0, BUILD SUCCESS
- 前端构建: ✓ built in 12.12s

---

## v4.0 — 账号体系与登录

**日期**: 2026-08-12
**基线**: v3.18
**主题**: 用户注册/登录 + JWT 认证 + 数据归属隔离 + 存量迁移与默认管理员

> ⚠️ 破坏性变更：本版起所有业务接口需登录后携带 `Authorization: Bearer <token>` 访问。

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| entity/User + repository/UserRepository | 新增用户实体 | BCrypt 密码哈希 |
| security/JwtUtil/JwtAuthFilter/SecurityConfig/SecurityUtils | 新增 JWT 认证链 | 无状态；settings/stats 仅 ADMIN |
| service/AuthService + controller/AuthController | 注册/登录/当前用户 | /api/auth/register|login|me |
| service/ProjectAccessService | 项目级越权校验 | 接入全部项目级接口 |
| entity/Project + ProjectService | userId 归属 | 列表/创建/各操作按归属过滤 |
| config/DataInitializer | 初始化与迁移 | 默认管理员 + 存量项目归属迁移 |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| views/Login.vue / Register.vue | 新增登录/注册页 | 居中卡片 + 表单校验 |
| stores/auth.js + api/auth.js | 登录态管理 | localStorage 持久化 |
| api/request.js | Bearer 注入 + 401 跳登录 | - |
| router/index.js | 登录路由 + 守卫 | 未登录重定向 /login |
| App.vue | 用户菜单 + 角色导航 | 仪表盘/设置仅 ADMIN |

### 验证结果

- 后端测试: Tests run 2, Failures 0, BUILD SUCCESS
- 前端构建: ✓ built in 12.88s

---

## v3.18 — 前端体验打磨

**日期**: 2026-08-12
**基线**: v3.17
**主题**: 表格列设置/密度/筛选持久化 + 窄屏适配 + 骨架屏/过渡 + 空状态引导 + 步骤过滤 + 版本号动态化 + 深色主题 + 脑图导出 PNG

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| views/TestCaseList.vue | 显示设置 + 筛选持久化 + 空状态 + 骨架屏 | 列显隐/紧凑密度 localStorage；筛选条件记忆；空列表引导；加载骨架 |
| views/ExecutionResult.vue | 仅显示失败 + 失败高亮 | 步骤过滤与结果色 |
| App.vue | 深色主题 + 版本号动态化 + 窄屏折叠 + 路由过渡 | html.dark 切换；package.json 版本；≤1024px 自动折叠 |
| main.js / styles/index.scss | 深色主题支持 | Element Plus dark css-vars + 令牌覆盖 |
| views/MindMapPreview.vue | 导出 PNG | SVG → canvas 下载 |

### 验证结果

- 后端测试: Tests run 2, Failures 0, BUILD SUCCESS（无后端改动）
- 前端构建: ✓ built in 12.82s

---

## v3.17 — 平台化打磨

**日期**: 2026-08-12
**基线**: v3.16
**主题**: 全局仪表盘 + 系统级默认生成参数 + 内嵌 API 文档 + 用例抽屉 + 项目内导航/面包屑

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| controller/StatsController.java | 新增全局统计 | GET /api/stats/overview |
| service/SettingsService.java + controller | 默认生成参数 | 新建项目自动初始化 |
| pom.xml | springdoc 依赖 | 内嵌 Swagger UI |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| views/Dashboard.vue | 新增仪表盘 | 统计卡 + 类型分布饼图 + 项目覆盖率柱状图 |
| views/Settings.vue | 默认参数 + API 文档入口 | 系统级默认生成参数配置 |
| App.vue + router | 仪表盘导航 + 项目二级导航 + 面包屑 | 层级导航 |
| views/TestCaseList.vue | 用例详情抽屉 | el-dialog → el-drawer |

### 验证结果

- 后端测试: Tests run 2, Failures 0, BUILD SUCCESS
- 前端构建: ✓ built in 12.52s

---

## v3.16 — 数据与协作

**日期**: 2026-08-12
**基线**: v3.15
**主题**: XMind 模板与导入校验明细 + 执行时用例快照 + 评审审计 + 项目导出备份

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| entity/ExecutionRecord.java | 新增 testCaseSnapshot | 执行时用例快照 |
| service/ExecutionService.java | 保存快照 | 启动执行时写入 |
| service/XmindService.java | 模板文件 | 生成含示例用例的模板 |
| service/TestCaseService.java | 导入跳过明细 | skippedDetails（标题为空） |
| controller/TestCaseController.java | 模板端点 | GET /testcases/xmind-template |
| service/BackupService.java + controller | 项目导出备份 | ZIP 含 5 类数据 |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| views/TestCaseList.vue | 模板下载 + 跳过明细 + 评审人录入 | 导入结果可视化、审计留痕 |
| views/ProjectDetail.vue | 导出备份按钮 | 下载项目 ZIP |
| views/ExecutionResult.vue | 执行快照折叠区 | 回溯"当时跑的什么用例" |

### 验证结果

- 后端测试: Tests run 2, Failures 0, BUILD SUCCESS
- 前端构建: ✓ built in 12.13s

---

## v3.15 — 回归与统计

**日期**: 2026-08-12
**基线**: v3.14
**主题**: 测试集/回归集 + 多执行环境 + 通过率趋势

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| entity/TestSuite.java + repository | 新增测试集实体 | caseIds JSON + createdAt |
| service/TestSuiteService.java + controller | 测试集 CRUD + 一键执行 | 执行复用 executeBatch |
| service/ProjectService.java + controller | 多执行环境 | settings.executionEnvironments，激活环境 URL 同步 defaultTargetUrl |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| views/TestCaseList.vue | 测试集 + 执行环境 | 保存当前选中为测试集/管理/一键执行；环境增删与激活切换 |
| api/suite.js + api/project.js | 新增 API 封装 | 测试集 4 接口 + 环境 2 接口 |
| views/ExecutionHistory.vue | 通过率趋势图 | 最近 20 次滚动通过率折线图 |

### 验证结果

- 后端测试: Tests run 2, Failures 0, BUILD SUCCESS
- 前端构建: ✓ built in 12.30s

---

## v3.14 — 冷启动与工程基础

**日期**: 2026-08-12
**基线**: v3.13
**主题**: 内置示例 PRD + 最小单元测试 + CI 构建门禁

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| src/test/.../CsvExporterTest.java | 新增单测 | CSV 表头/内容/BOM/列表拼接 |
| src/test/.../XmindServiceTest.java | 新增单测 | XMind 生成→解析 round-trip |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| assets/samples/order-prd.md | 新增示例 PRD | 电商订单系统完整示例 |
| components/PrdPanel.vue | 新增"使用示例"按钮 | 一键载入示例 PRD |
| .github/workflows/ci.yml | 新增 CI | 后端 mvn test + 前端 npm build 门禁 |

### 验证结果

- 后端测试: Tests run 2, Failures 0, BUILD SUCCESS
- 前端构建: ✓ built in 11.92s

---

## v3.13 — 结果输出与用例资产

**日期**: 2026-08-12
**基线**: v3.12
**主题**: 报告在线预览 + 恢复 JSON/CSV 导入导出与跨项目复制 + 生成聚焦类型真正生效

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| controller/ExecutionController.java | 报告端点支持 inline/download | 默认 inline 预览，download=1 附件下载 |
| agent/TestGeneratorAgent.java | focusTypes 强制过滤 | generate/generateStreaming 均过滤；流式回调与落库一致 |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| views/ExecutionResult.vue | 报告预览/下载 | 新增"预览报告"，下载改为 download=1 |
| views/BatchResult.vue | 批次报告预览/下载 | 同上 |
| views/TestCaseList.vue | 恢复用例资产入口 | 导出 JSON/CSV、导入 JSON、跨项目复制；批量选择过滤模块行；聚焦类型提示更新 |

### 验证结果

- 后端编译: BUILD SUCCESS
- 前端构建: ✓ built in 11.85s

---

## v3.12 — 执行体验增强

**日期**: 2026-08-12
**基线**: v3.11
**主题**: 执行状态可视化与快捷操作 + 默认执行 URL + 生成前置预检 + 批次失败摘要

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| service/TestCaseService.java | listTestCases 新增 executionStatus 筛选 | 未执行/执行中/通过/失败 |
| controller/TestCaseController.java | 参数透传 | - |
| dto/GenerationParams.java | 新增 defaultTargetUrl | 项目默认执行 URL |
| service/ExecutionService.java | getBatchStatus 新增 executions 别名 | 修复批次列表为空的数据映射 bug |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| views/TestCaseList.vue | 执行状态列/筛选 + 快捷执行 | 状态胶囊列；重跑失败/执行已批准一键批量执行；默认 URL 带入执行对话框 |
| components/TestCaseCard.vue | 新增 defaultTargetUrl prop | 单条执行对话框默认带入项目 URL |
| views/ProjectDetail.vue | 生成前置预检 | created 且无 PRD 时禁用"生成用例"并提示 |
| views/BatchResult.vue | 修复列表映射 + 失败摘要 | 读取 executions/id/testCaseTitle；失败用例错误摘要折叠区 |

### 验证结果

- 后端编译: BUILD SUCCESS
- 前端构建: ✓ built in 12.26s

---

## v3.11 — 执行闭环补全

**日期**: 2026-08-12
**基线**: v3.10（含 12f0795 前端界面重构）
**主题**: 执行结果回写用例状态 + 执行历史页 + 覆盖率矩阵跳转修复，打通"生成→执行→结果→回看"闭环

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| service/ExecutionService.java | 执行状态回写 | 执行启动置 running、结束置 passed/failed；单条/批量、agent/程序化模式全覆盖 |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| views/ExecutionHistory.vue | 新增执行历史页 | 统计卡（总计/通过/失败/运行中/通过率）+ 记录表格 + 详情/报告入口 |
| router/index.js | 新增路由 | 注册 `/projects/:id/executions` 执行历史列表页 |
| views/ProjectDetail.vue | 新增"执行历史"按钮 | 查看操作卡入口 |
| views/TestCaseList.vue | 新增入口 + 覆盖率跳转修复 | 页头"执行历史"按钮；覆盖率关联用例改为内存按 ID 筛选 + 清除筛选横幅（替代失效的 keyword 跳转） |

### 验证结果

- 后端编译: BUILD SUCCESS
- 前端构建: ✓ built in 13.30s（ExecutionHistory 5.49 kB）

---

## v3.10 — 前端界面优化（视觉升级）

**日期**: 2026-08-12
**基线**: v3.9
**主题**: 接入全局设计系统 + 顶部导航/项目卡片/项目详情流程条/用例列表信息重组视觉升级

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| （无） | - | 纯前端迭代，后端无改动 |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| styles/index.scss | 接入并升级为全局设计系统 | main.js 引入；主题色/渐变背景/卡片/统计卡/表格统一视觉 |
| main.js | 引入全局样式 | 修复 styles/index.scss 此前未被引用（死代码）的问题 |
| App.vue | 顶部导航重设计 | 渐变头部+光斑、渐变 Logo 图标、图标导航胶囊 active |
| ProjectList.vue | 项目卡片重设计 | 状态色条 + 图标头像 + 信息层级 + 时间/删除区 |
| ProjectDetail.vue | 新增迭代流程步骤条 | el-steps 按项目状态高亮；操作按钮按主线/查看分组 |
| TestCaseList.vue | 工具栏分组 + 统计卡图标化 + 模块行美化 | 生成/批量/用例三组；图标渐变统计卡；模块文件夹图标+数量徽标 |
| index.html + public/favicon.svg | 新增自定义 favicon | 替换默认 vite 图标 |

### 验证结果

- 后端编译: BUILD SUCCESS（无代码改动）
- 前端构建: ✓ built in 10.48s（TestCaseList 31.86 kB）

---

## v3.9 — XMind 导入与脑图查看入口

**日期**: 2026-08-12
**基线**: v3.8
**主题**: 移除 JSON/CSV 导入导出按钮，改为 XMind 导入（逆向解析）+ 生成脑图后查看入口

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| service/XmindService.java | 新增 parseXmind | 逆向解析 XMind ZIP+JSON 树 → List<TestCase> |
| service/TestCaseService.java | 新增 importFromXmind | 从 XMind 导入用例（追加模式，重新编号） |
| controller/TestCaseController.java | 新增 POST 端点 | `POST /api/projects/{id}/testcases/import-xmind` |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| TestCaseList.vue | 移除按钮 | 导出JSON/导出CSV/导入JSON/复制到 + 对应函数 |
| TestCaseList.vue | 新增按钮 | 导入XMind + 查看脑图（生成后显示） |
| TestCaseList.vue | 修改 | handleGenerateMindmap 成功后设置 mindmapGenerated |
| api/testcase.js | 新增 importXmind | 调用 POST import-xmind API |

### 验证结果

- 后端编译: BUILD SUCCESS (89 source files)
- 前端构建: ✓ built in 15.78s (TestCaseList 30.67 kB)

---

## v3.8 — 树状用例列表

**日期**: 2026-08-11
**基线**: v3.7
**主题**: 将扁平用例表格改为树状结构——按模块分组 + 前置条件/步骤/预期结果直接显示在列中（不再隐藏在展开行）

### 改动清单与目的

#### 前端

| 文件 | 类型 | 说明 |
|------|------|------|
| `views/TestCaseList.vue` | 修改 | el-table 改为树状结构（`row-key` + `tree-props`），按模块分组，模块为父节点用例为子节点；新增 `treeData` computed 将 `displayTestCases` 按 module 字段分组 |
| `views/TestCaseList.vue` | 修改 | 移除 `type="expand"` 展开列，新增 3 列直接显示前置条件/步骤/预期结果摘要（第一项 + 计数） |
| `views/TestCaseList.vue` | 修改 | 移除分页组件 + `handlePageChange`/`handleSizeChange` 函数；`loadList` 改为 `pageSize: 9999` 加载全部用例 |
| `views/TestCaseList.vue` | 修改 | 新增 `rowClassName` 区分模块行（加粗+背景色）和用例行（可点击）；`handleRowClick` 跳过模块行 |
| `views/TestCaseList.vue` | 修改 | 移除 v3.6 展开行 CSS（`expand-content`/`expand-section`/`expand-label`/`expand-list`），新增树状样式（`module-row`/`case-row`/`detail-summary`） |

#### 后端

无改动（数据已包含 module/preconditions/steps/expectedResults 字段）

### 验证结果

- 前端构建: ✓ built in 10.98s (TestCaseList chunk 32.47 kB)
- 树状分组: 模块为父节点，用例为子节点，默认展开
- 详情可见: 前置条件/步骤/预期结果直接显示在列中（摘要 + 计数）
- 点击用例行打开详情对话框

### 说明

- **根因**: v3.6 的展开行（`type="expand"`）箭头不明显，用户不知道点击展开；扁平列表看不出模块分组
- **方案**: el-table `tree-props` 树状结构 + 详情列直接显示（不再需要展开）
- **向后兼容**: API 不变，筛选功能不变，TestCaseCard 详情对话框不变

---

## v3.7 — 真正的 LLM 流式输出

**日期**: 2026-08-11
**基线**: v3.6
**主题**: 将"伪流式"升级为"真流式"——MCP Server stream:true + JSON-RPC notification + Java 逐行解析 + 增量 JSON 解析器，首条用例出现时间从 40~120 秒降至 ~5 秒

### 改动清单与目的

#### MCP Server

| 文件 | 类型 | 说明 |
|------|------|------|
| `mcp-server/index.js` | 修改 | 新增 `StreamingServer extends Server` 子类（暴露 protected `notification()` 方法）；`llm_chat` 工具新增 `stream` 参数（默认 false 向后兼容），启用时 OpenAI `stream: true` 逐块通过 JSON-RPC notification 推送，累积完整文本返回；版本号升至 v1.2.0 |

#### 后端

| 文件 | 类型 | 说明 |
|------|------|------|
| `mcp/McpConnection.java` | 修改 | 新增 `callToolStreaming(toolName, args, chunkConsumer)` 方法——发送 JSON-RPC 请求后循环 `readLine()`，`notifications/llm_chunk` 通知 dispatch 到 `chunkConsumer`，匹配 id 的 response 返回完整结果；`synchronized` 保证 stdio 线程安全 |
| `mcp/McpClientManager.java` | 修改 | 新增 `callToolStreaming(serverName, toolName, args, chunkConsumer)` 路由方法 |
| `service/LlmService.java` | 修改 | 新增 `chatStreaming(systemPrompt, userPrompt, temperature, chunkConsumer)` 方法——调用 `callToolStreaming` 传入 `stream: true` 参数，`chunkConsumer` 接收逐块文本，返回完整响应；复用重试机制 |
| `agent/TestGeneratorAgent.java` | 修改 | 新增 `StreamingTestCaseParser` 内部类——积累文本 chunk，跟踪花括号深度检测完整用例对象后立即 `caseCb.onCase` 回调；`generateByLlmWithPrd` / `generateByLlmForStateMachine` 在 `caseCb != null` 时启用流式调用 + 增量解析，完整响应兜底推送未解析的用例 |

#### 前端

| 文件 | 类型 | 说明 |
|------|------|------|
| `views/TestCaseList.vue` | 修改 | 流式面板标题优化——0 条时提示"正在接收 LLM 流式响应..."，有数据后显示"已收到 N 条" |

### 验证结果

- 后端编译: BUILD SUCCESS (89 source files)
- 前端构建: ✓ built in 10.91s (TestCaseList chunk 32.40 kB)
- 向后兼容: `callTool`/`chat`/非流式 `llm_chat` 完全不变

### 说明

- **数据流改造**: MCP Server 逐 token 推送 notification → McpConnection 逐行读取 dispatch → LlmService chatStreaming 回调 → StreamingTestCaseParser 增量解析 → caseCb → SSE 推送
- **增量 JSON 解析**: `StreamingTestCaseParser` 跟踪 `inString`/`escaped`/`braceDepth` 状态机，检测花括号深度从 1→0 时提取完整对象 JSON 解析为 TestCase
- **兜底机制**: 流式结束后用完整响应重新 `parseTestCases(json, null)`，推送流式期间未推送的用例（通过 `parser.getParsedCount()` 对比）
- **向后兼容**: `caseCb == null`（非流式场景）走原 `chat()` 路径；MCP Server `stream` 参数默认 false

---

## v3.6 — 用例列表体验优化

**日期**: 2026-08-11
**基线**: v3.5
**主题**: 追加生成闪烁修复 + 列表展开行 + 手动添加用例

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| dto/CreateTestCaseRequest.java | 新增 | 创建用例请求 DTO |
| controller/TestCaseController.java | 新增 POST 端点 | `POST /api/projects/{projectId}/testcases` |
| service/TestCaseService.java | 新增 createTestCase 方法 | 手动创建用例，自动分配 TC 编号 |
| mcp/McpConnection.java | 修复编码 | InputStreamReader/OutputStreamWriter 指定 UTF-8 |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| TestCaseList.vue | 修复 displayTestCases | 追加模式合并已有用例+新用例，不闪烁 |
| TestCaseList.vue | 新增展开行 | el-table type=expand 显示前置条件/步骤/预期结果 |
| TestCaseList.vue | 新增按钮 | "新增用例"按钮 + 创建对话框 |
| TestCaseList.vue | 覆盖率面板优化 | 移到列表下方，可折叠 |
| TestCaseCard.vue | 新增 mode prop | 支持 mode='create' 创建模式 |
| TestCaseCard.vue | 修改 handleSave | 创建模式 emit('create') |
| TestCaseCard.vue | 修改 cancelEdit | 创建模式取消=关闭对话框 |
| api/testcase.js | 新增 createTestCase | 调用 POST 创建 API |

### 验证结果

- 后端编译: BUILD SUCCESS
- 前端构建: ✓ built in 14.25s
- 追加生成不闪烁: 追加模式 displayTestCases 合并已有+新用例
- 展开行: 显示前置条件、步骤、预期结果
- 手动添加: 新增按钮 → 空表单 → 保存 → 列表刷新

---

## v3.5 — 追加生成模式

**日期**: 2026-08-11
**基线**: v3.4
**迭代主题**: 新增"追加生成"模式——不删除现有用例，按类型追加新用例并跨去重，让用户敢于增量改进而无需担心丢失人工评审成果

### 改动清单与目的

#### 后端

| 文件 | 类型 | 说明 |
|------|------|------|
| `service/TestCaseService.java` | 修改 | 新增 `runGenerateStreamAppend(projectId, type, emitter)` 方法：不删除现有用例、type 非空时过滤、新用例 vs 现有用例跨去重、ID 从现有最大 +1 续号；新增私有 `isDuplicate(a, b)` 判重逻辑（与 TestGeneratorAgent 一致，复制以保持封装职责分离）；complete 事件携带 total/appended/dropped/existingBefore 字段；复用 cancellationFlags + GenerationCancelledException，取消时跳过落库 |
| `controller/ProjectController.java` | 修改 | 新增 `GET /{projectId}/testcases/generate-stream-append?type={type}` SSE 端点；复用 generating 状态机互斥校验；`cancelGenerate` 端点同时适用于追加生成（共用 cancellationFlags） |

#### 前端

| 文件 | 类型 | 说明 |
|------|------|------|
| `api/testcase.js` | 修改 | 新增 `streamGenerateAppend(projectId, type, callbacks)`：基于 EventSource，URL `generate-stream-append?type=`，complete 事件回调接收整个 data 对象（含 appended/dropped/existingBefore） |
| `views/TestCaseList.vue` | 修改 | 工具栏新增"追加生成"按钮（warning 类型，streaming 时 disabled）；新增类型选择 el-dialog（radio: 全部/正向/异常/边界/数据）+ form-tip 说明；新增 `currentGenMode` 状态（'regenerate'/'append'/null）+ `streamingAlertTitle` 计算属性差异化流式面板标题；新增 `handleOpenAppendDialog`/`handleConfirmAppend`/`startAppendStream` 函数；`handleRegenerate` 同步设置/重置 currentGenMode；导入 Plus 图标 + streamGenerateAppend |

### 验证
- 后端编译: `mvn compile` BUILD SUCCESS (88 source files)
- 前端构建: `npm run build` 成功（TestCaseList chunk 30.53 kB）

### 说明
- **追加 vs 重新生成**：追加保留现有用例 + 跨去重 + 续号；重新生成仍为全量覆盖（行为不变）
- **类型过滤策略**：LLM 仍生成全类型用例，落库阶段后置过滤（避免 LLM 不遵守类型约束丢失优质用例）；type 为空时全类型追加
- **跨去重**：新用例与现有同模块用例标题相似度 > 80% 判重（与生成阶段 isDuplicate 标准一致）
- **续号保存**：复用 `nextTestCaseNumber`，新用例 ID 从 TC-{现有最大+1} 开始
- **取消保护**：复用 v3.3 取消机制，追加生成取消时跳过落库，现有用例完整保留
- **状态机互斥**：追加生成与重新生成共用 generating 状态，并发触发推送 error 事件
- **向后兼容**：重新生成端点/前端逻辑完全不变；complete 事件新增字段，前端按存在性读取

---

## v3.4 — 生成参数可配置

**日期**: 2026-08-11
**基线**: v3.3
**迭代主题**: 将硬编码的用例生成参数（temperature 0.4、数量引导、测试类型）提取为项目级可配置项，支持按项目类型调整用例密度与多样性

### 改动清单与目的

#### 后端

| 文件 | 类型 | 说明 |
|------|------|------|
| `dto/GenerationParams.java` | 新建 | 项目级生成参数 DTO（caseDensity/temperature/focusTypes），含 `defaults()` 兜底默认值 |
| `service/ProjectService.java` | 修改 | 新增 `getGenerationParams`/`updateGenerationParams`/`parseGenerationParams`，从 Project.settings JSON 读写 generationParams 字段，解析失败降级默认值 |
| `controller/ProjectController.java` | 修改 | 新增 `GET/PUT /{projectId}/generation-params` 端点 |
| `agent/OrchestratorAgent.java` | 修改 | GenContext record 新增 `params` 字段；`loadGenerationContext` 解析 Project.settings 得到 GenerationParams；`generate`/`generateStreaming` 透传 params 给 TestGeneratorAgent |
| `agent/TestGeneratorAgent.java` | 修改 | 将 `SYSTEM_PROMPT`/`SYSTEM_PROMPT_PRD_DRIVEN` 拆为 HEADER + 动态数量引导段 + FOOTER；新增 `buildQuantityGuide`/`buildPrdQuantityGuide`/`buildSystemPrompt`/`buildPrdDrivenPrompt`/`resolveTemperature`；`generate`/`generateStreaming`/`generateCodeDrivenCases`/`generateByLlmForStateMachine`/`generateByLlmWithPrd` 新增 `params` 参数，LLM 调用改用动态 prompt + 参数化 temperature |

#### 前端

| 文件 | 类型 | 说明 |
|------|------|------|
| `api/project.js` | 修改 | 新增 `getGenerationParams(projectId)` + `updateGenerationParams(projectId, params)` |
| `views/TestCaseList.vue` | 修改 | 工具栏新增"生成参数"按钮 + el-dialog（caseDensity radio 三档 + temperature slider 0.2~0.6 + focusTypes checkbox 四选）+ `handleOpenGenParams`（拉取当前参数）+ `handleSaveGenParams`（保存）+ form-tip 样式 |

### 验证
- 后端编译: `mvn compile` BUILD SUCCESS
- 前端构建: `npm run build` 成功（TestCaseList chunk 27.50 kB）

### 说明
- **存储复用**：参数存入 `Project.settings` JSON 的 `generationParams` 字段，无需新建表
- **向后兼容**：settings 为空 `{}` 时使用默认值（medium/0.4/[]），行为与 v3.3 完全一致；medium 档 system prompt 文本与 v3.3 完全一致
- **density 映射**：low（正向≥1/异常≥1/边界≥1/数据可选）/ medium（当前行为）/ high（正向≥2/异常≥2/边界≥3/数据≥2）
- **temperature 范围**：0.2~0.6，越低越稳定一致，越高越多样发散；越界降级为 0.4
- **focusTypes**：当前版本仅作为生成提示写入 prompt 上下文，不强制过滤解析结果（避免丢弃高质量用例）
- 参数保存后下次"重新生成"时生效（OrchestratorAgent 从 Project.settings 读取）

---

## v3.3 — 流式生成取消与落库保护

**日期**: 2026-08-11
**基线**: v3.2
**迭代主题**: 为流式用例生成增加取消能力 + 落库保护，修补 v3.2 遗留的"生成不可中断 + 先删后存覆盖旧用例"数据安全风险

### 改动清单与目的

#### 后端

| 文件 | 类型 | 说明 |
|------|------|------|
| `common/GenerationCancelledException.java` | 新建 | 取消异常类型，生成线程在检查点抛出，`runGenerateStream` catch 后跳过落库 |
| `service/TestCaseService.java` | 修改 | 新增 `cancellationFlags` 注册表（ConcurrentHashMap）+ `cancelGeneration(projectId)` + `restoreProjectStatus`；`runGenerateStream` 新增 `cancelled` 标志，客户端断开/cancel 端点触发取消，catch `GenerationCancelledException` 跳过 deleteAll+save（落库保护），finally 清理注册表 |
| `agent/OrchestratorAgent.java` | 修改 | `generateStreaming` 新增 `cancelled` 参数，透传给 TestGeneratorAgent |
| `agent/TestGeneratorAgent.java` | 修改 | 新增 `checkCancelled` helper；`generateStreaming`/`generateCodeDrivenCases`/`generateByLlmWithPrd`/`generateByLlmForStateMachine` 新增 `cancelled` 参数，在 LLM 调用前/状态机循环迭代前检查取消标志；`GenerationCancelledException` 向上传播不触发 fallback |
| `controller/ProjectController.java` | 修改 | 新增 `POST /{projectId}/testcases/generate-cancel` 取消端点 |

#### 前端

| 文件 | 类型 | 说明 |
|------|------|------|
| `api/testcase.js` | 修改 | 新增 `cancelGenerate(projectId)`；`streamGenerate` 新增 `cancelled` 事件监听 + `onCancelled` 回调，区分"取消"（warning）与"失败"（error） |
| `views/TestCaseList.vue` | 修改 | 新增 `cancelling` 状态 + 取消生成按钮（el-button danger，流式 alert 内）+ `handleCancelGenerate`（二次确认 + 调 cancelGenerate）+ `onCancelled` 回调（warning 提示 + 刷新列表显示旧用例）+ streaming-alert-body 样式 |

### 验证
- 后端编译: `mvn compile` BUILD SUCCESS (87 source files)
- 前端构建: `npm run build` 成功（TestCaseList chunk 24.98 kB）

### 说明
- **落库保护**：取消时跳过 `deleteAll` + `save`，旧用例（含人工修改）完整保留
- **客户端断开 → 取消**：关闭页面/路由切换/网络断开时，后端自动取消生成并保留旧用例（v3.2 是继续跑完并覆盖）
- **检查点**：PRD 驱动 LLM 调用前、代码驱动状态机循环每次迭代前、分模块 LLM 调用前、落库前
- **向后兼容**：非流式 `runGenerate` 不受影响（无 cancelled 参数）；原有 SSE 事件不变，仅新增 cancelled 事件
- LLM 同步调用本身无法中途 abort，在调用前检查取消标志；LLM 返回后若已取消则丢弃结果不落库

---

## v3.2 — 用例生成流式输出（SSE Stream）

**日期**: 2026-08-11
**基线**: v3.1
**迭代主题**: 将用例生成从"异步轮询 + 终态一次性返回"升级为"SSE 流式推送"，用户每生成一条用例即可实时看到，无需等待全部完成

### 改动清单与目的

#### 后端

| 文件 | 类型 | 说明 |
|------|------|------|
| `agent/TestGeneratorAgent.java` | 修改 | 新增 `CaseCallback` 接口 + `generateStreaming` 重载；`parseTestCases`/`generateByLlmWithPrd`/`generateByLlmForStateMachine`/`generateByRulesForStateMachine`/`generateByEndpoints`/`generateCodeDrivenCases` 增加 caseCb 参数，每条用例解析/构建后立即回调（去重前），用于 SSE 推送 |
| `agent/OrchestratorAgent.java` | 修改 | 抽取 `loadGenerationContext` helper（PRD 解析 + 代码/前端结果加载），`generate` 与新增 `generateStreaming` 共用，避免重复 |
| `service/TestCaseService.java` | 修改 | 新增 `runGenerateStream(projectId, emitter)`（`@Async`）：通过 SseEmitter 推送 progress/case/complete/error 事件，结束时落库；新增 `sendSseEvent`/`safeSseComplete` 私有方法处理客户端断开/超时 |
| `controller/ProjectController.java` | 修改 | 新增 `GET /{projectId}/testcases/generate-stream` SSE 端点（5 分钟超时），generating 状态立即推送 error 事件并关闭 |

#### 前端

| 文件 | 类型 | 说明 |
|------|------|------|
| `api/testcase.js` | 修改 | 新增 `streamGenerate(projectId, callbacks)`，基于浏览器原生 EventSource 消费 SSE，返回事件源供调用方 close() |
| `views/TestCaseList.vue` | 修改 | 新增流式状态（streaming/streamProgress/streamedCases）+ 流式生成面板（绿色 alert 显示进度与计数）+ 表格流式数据源切换 + 编号列显示"生成中" + 流式期间隐藏分页 + onMounted 自动触发（?generate=1）+ onUnmounted 释放 EventSource；`handleRegenerate` 改用 streamGenerate |
| `views/ProjectDetail.vue` | 修改 | "生成用例"改为 `router.push('/projects/:id/testcases?generate=1')` 跳转自动触发流式生成；移除 triggerGenerate import 与轮询逻辑 |

### 验证
- 后端编译: `mvn compile` BUILD SUCCESS (86 source files)
- 前端构建: `npm run build` 成功（TestCaseList chunk 24.18 kB）

### 说明
- 保留旧端点 `POST /api/projects/{id}/generate` 与 `TestCaseService.runGenerate`，作为非流式回退路径，完全向后兼容
- 流式生成与旧端点共用 `generating` 状态机，互斥（任一在 generating 时另一端点拒绝）
- caseCb 在去重前触发，流式推送的用例编号为临时序号，落库后重新编号；前端 complete 事件后刷新列表拿最终编号
- 客户端断开/超时由 `onCompletion`/`onTimeout`/`onError` 置 `clientGone` 标志，后续 send 静默跳过，生成继续完成落库
- LLM 一次返回全部 JSON，Java 侧解析后逐条回调模拟流式体验（首条延迟 ≈ LLM 总耗时）

---

## v3.1 — 目录选择器与界面优化

**日期**: 2026-08-10
**基线**: v3.0
**迭代主题**: 将本地路径从手动输入升级为可视化目录选择器，并优化创建项目表单界面

### 改动清单与目的

#### 后端

| 文件 | 类型 | 说明 |
|------|------|------|
| `dto/DirItem.java` | 新建 | 目录项 DTO（name/path/leaf），用于目录树懒加载节点数据传输 |
| `controller/FilesystemController.java` | 新建 | 提供 `GET /api/filesystem/dirs` 接口：path 为空返回系统根盘符，非空返回子目录列表（过滤文件、仅返回可读目录） |

#### 前端

| 文件 | 类型 | 说明 |
|------|------|------|
| `api/filesystem.js` | 新建 | 封装 `getDirs(path)` 目录列表 API |
| `components/DirSelector.vue` | 新建 | 目录选择器组件：el-popover + el-tree 懒加载，支持返回上级、节点点击选中、确定回调 |
| `views/ProjectCreate.vue` | 修改 | 来源类型从 el-select 改为 el-radio-button（更直观）；本地路径输入框集成 DirSelector（el-input append 插槽）；Git 地址加格式校验 + https:// 前缀；无代码模式加 el-alert 说明；新增重置按钮；表单卡片加 header 提示文案；样式优化 |

### 验证
- 后端编译: `mvn compile` BUILD SUCCESS
- 前端构建: `npm run build` 成功（ProjectCreate chunk 6.05 kB）

### 说明
- 目录选择器仅用于"本地路径"来源类型，Git 地址和无代码模式不展示
- 后端目录列表 API 过滤掉文件和不可读目录，仅返回目录
- DirSelector 通过 el-tree 懒加载按需请求子目录，避免一次性加载大量节点
- 切换来源类型时自动清空路径并清除校验状态

---

## v3.0 — PRD 驱动流程改造

**日期**: 2026-08-10
**基线**: v2.9
**迭代主题**: 将产品流程从"代码驱动"改为"PRD 驱动"——PRD 为用例生成主线（必须），代码路径降级为可选上下文

### 改动清单与目的

#### 后端

| 文件 | 类型 | 说明 |
|------|------|------|
| `dto/CreateProjectRequest.java` | 修改 | 移除 `sourcePath` 的 `@NotBlank`，改为可选（纯 PRD 驱动项目可不填代码路径） |
| `service/ProjectService.java` | 修改 | `createProject` 在 `sourcePath` 为空时跳过路径存在校验 |
| `service/TestCaseService.java` | 修改 | `runGenerate` 前置校验——PRD 和代码分析结果都为空时抛 `IllegalStateException` 阻止生成 |

#### 前端

| 文件 | 类型 | 说明 |
|------|------|------|
| `views/ProjectCreate.vue` | 修改 | 来源类型新增"无代码（纯 PRD）"选项；项目路径条件显示（选"无代码"时隐藏）；`sourcePath` 校验改为动态（仅非"无代码"时必填）；watch sourceType 切换时清空路径 |
| `views/ProjectDetail.vue` | 修改 | PRD 面板上提到操作区上方（作为主线）；"生成用例"按钮提至首位；"开始分析"按钮在无代码路径时禁用并显示"（可选）"标注；`canGenerate` 放宽为非 analyzing/generating 即可（支持 created 状态纯 PRD 生成）；新增 `hasSourcePath` computed |

### 验证
- 后端编译: `mvn compile` BUILD SUCCESS (84 source files)
- 前端构建: `npm run build` 成功（ProjectDetail chunk 9.69 kB）

### 说明
- 后端生成链路（OrchestratorAgent/TestGeneratorAgent）v1.10 已支持纯 PRD 驱动，本迭代仅放开创建校验和生成前置校验
- 历史项目（有 sourcePath）行为完全不变，向后兼容
- "PRD 必填"体现在生成用例环节的校验，创建项目时不强制填 PRD（降低创建门槛，PRD 在详情页 PrdPanel 编辑）

---

## v2.9 — Selenium 清理 + 录屏回放升级为视频播放

**日期**: 2026-08-09
**基线**: v2.8
**迭代主题**: 清理 Selenium 死代码与依赖；前端录屏回放从图片轮播升级为 WebM 视频播放

### 改动清单与目的

#### 后端

| 文件 | 类型 | 说明 |
|------|------|------|
| `skill/BrowserSkill.java` | 删除 | Selenium 浏览器操作 Skill（v2.8 已被 PlaywrightRecordSkill 完全替代，成为死代码） |
| `pom.xml` | 修改 | 移除 `selenium-java` + `webdrivermanager` 两个依赖块 |
| `skill/PlaywrightRecordSkill.java` | 修改 | 类注释更新（去掉"过渡期共存"说明，标注为唯一浏览器操作实现） |

#### 前端

| 文件 | 类型 | 说明 |
|------|------|------|
| `api/execution.js` | 修改 | 新增 `getExecutionVideoUrl(eid)` 辅助函数（返回视频下载 API URL） |
| `views/ExecutionResult.vue` | 修改 | 录屏回放升级为双模式：优先 `<video>` 播放 WebM（v2.8+ 新记录），回退图片轮播（兼容 v2.4~v2.5 历史记录）；新增 `recordingVideoUrl`/`hasRecording` computed + `downloadVideo` 方法 + 视频播放器样式 |

### 验证
- 后端编译: `mvn compile` BUILD SUCCESS (84 source files，较 v2.8 减少 1)
- 前端构建: `npm run build` 成功（ExecutionResult chunk 7.41 kB）
- Grep 确认无 selenium 残留引用（仅注释保留历史说明）

### 说明
- `ExecutionRecord.recordingFrames` 字段保留，兼容历史记录的图片轮播回放
- 视频播放器使用原生 `<video controls autoplay>`，支持播放/暂停/进度条/全屏 + 下载按钮

---

## v2.8 — 执行链路切换（Selenium → Playwright）

**日期**: 2026-08-09
**基线**: v2.7
**迭代主题**: 执行引擎从 Selenium BrowserSkill 切换到 Playwright PlaywrightRecordSkill，录屏从图片序列升级为 WebM 视频

### 改动清单与目的

#### 后端

| 文件 | 类型 | 说明 |
|------|------|------|
| `entity/ExecutionRecord.java` | 修改 | 新增 `recordingVideoPath` 字段，存储 WebM 视频路径（替代 recordingFrames 图片序列） |
| `controller/ExecutionController.java` | 修改 | 新增 `GET /executions/{executionId}/video` 视频下载 API（返回 video/webm 流） |
| `service/ExecutionService.java` | 修改 | 依赖从 `BrowserSkill` 替换为 `PlaywrightRecordSkill`；录屏逻辑改为 `stopRecording(videoPath)` 保存视频；移除周期截图 startRecording 调用（Playwright 在 launch 时自动开始录屏） |
| `agent/ExecutionAgent.java` | 修改 | 依赖从 `BrowserSkill` 替换为 `PlaywrightRecordSkill`；所有 `browserSkill.xxx()` 调用替换为 `playwrightSkill.xxx()`（takeScreenshot/visualClick/domClick/getPageStatus/takeScreenshotWithMarker） |

### 验证
- 后端编译: `mvn compile` BUILD SUCCESS (85 source files)
- 前端: 无改动（前端 API 调用不变，录屏回放仍用旧图片轮播，v2.9 升级为视频播放）

### 说明
- BrowserSkill 与 PlaywrightRecordSkill 过渡期共存，v2.9 将清理 Selenium 依赖
- 截图标注逻辑（红圈+十字准星+坐标文本）在 PlaywrightRecordSkill 中复用 Graphics2D 实现，与 BrowserSkill 行为一致

---

## v2.7 — Playwright MCP Server + PlaywrightRecordSkill

**日期**: 2026-08-09
**基线**: v2.6
**迭代主题**: 自建 Playwright MCP Server（9个工具）+ PlaywrightRecordSkill 封装 + 真正视频录屏

### 改动清单与目的

#### 后端

| 文件 | 类型 | 说明 |
|------|------|------|
| `playwright-mcp-server/package.json` | 新建 | MCP Server 依赖配置（@modelcontextprotocol/sdk + playwright） |
| `playwright-mcp-server/index.js` | 新建 | Playwright MCP Server（9个工具：launch/navigate/screenshot/visualClick/domClick/pageStatus/videoGetPath/videoSave/close） |
| `skill/PlaywrightRecordSkill.java` | 新建 | Java 端封装，方法签名与 BrowserSkill 对齐，截图标注复用 Graphics2D |
| `mcp/McpClientManager.java` | 修改 | 注册 "playwright" Server |
| `resources/application.yml` | 修改 | 添加 playwright Server 配置 |

### 验证
- npm install: 成功（96 packages）
- 后端编译: `mvn compile` BUILD SUCCESS (85 source files)
- 前端: 无改动，跳过构建验证

---

## v2.6 — MCP Client 多 Server 架构

**日期**: 2026-08-09
**基线**: v2.5
**迭代主题**: McpClient 重构为 McpClientManager + McpConnection，支持多 MCP Server 并行管理

### 改动清单与目的

#### 后端

| 文件 | 类型 | 说明 |
|------|------|------|
| `mcp/McpConnection.java` | 新建 | 单 Server 连接封装（进程管理 + JSON-RPC 通信 + synchronized 防并发） |
| `mcp/McpClientManager.java` | 新建 | 多 Server 管理器（Map<String, McpConnection>，替代原 McpClient） |
| `mcp/McpClient.java` | 删除 | 逻辑已拆分到 McpConnection + McpClientManager |
| `service/LlmService.java` | 修改 | McpClient → McpClientManager，callTool 加 serverName 参数 |
| `service/McpBridgeService.java` | 修改 | McpClient → McpClientManager，callTool 加 serverName 参数 |
| `resources/application.yml` | 修改 | `mcp.server.*` → `mcp.servers.llm.*` 多 Server 配置格式 |

### 验证
- 后端编译: `mvn compile` BUILD SUCCESS (84 source files)
- 前端: 无改动，跳过构建验证

---

## v2.5 — 截图标注 + 录屏回放增强

**日期**: 2026-08-09
**基线**: v2.4
**迭代主题**: 截图标注点击位置（红圈+十字准星）+ 录屏帧合并步骤截图

### 改动清单与目的

#### 后端

| 文件 | 类型 | 说明 |
|------|------|------|
| `skill/BrowserSkill.java` | 修改 | 新增 takeScreenshotWithMarker + annotateScreenshot（Graphics2D 绘制红圈+十字+坐标文本） |
| `service/ExecutionService.java` | 修改 | 步骤截图改用带标注版本 + 录屏帧合并步骤截图 |
| `agent/ExecutionAgent.java` | 修改 | Agent 模式截图改用 takeScreenshotWithMarker + clickX/clickY 传递 |

### 标注效果
```
┌──────────────────────────┐
│                          │
│        ⊕ ← 红圈+十字     │
│       登录按钮            │
│  click: (260, 340)       │
│                          │
└──────────────────────────┘
```

### 验证
- 后端编译: `mvn compile` BUILD SUCCESS (83 source files)
- 前端构建: `npm run build` 成功 (11.61s，无改动)

---

## v2.4 — 执行报告 + 录屏

**日期**: 2026-08-09
**基线**: v2.3
**迭代主题**: HTML 执行报告生成 + 浏览器录屏（周期截图+前端播放）

### 改动清单与目的

#### 后端

| 文件 | 类型 | 说明 |
|------|------|------|
| `entity/ExecutionRecord.java` | 修改 | 新增 recordingFrames 字段（JSON 数组，存录屏帧路径） |
| `skill/BrowserSkill.java` | 修改 | 新增 startRecording/stopRecording（每2s截图，最多60帧） |
| `service/ExecutionService.java` | 修改 | 执行流程集成录屏（start→执行→stop→存帧） |
| `service/ReportService.java` | 新建 | HTML 报告生成（单条+批次，内嵌base64截图） |
| `controller/ExecutionController.java` | 修改 | 新增 2 个报告下载 API |
| `repository/ExecutionRecordRepository.java` | 修改 | 新增 findByBatchId 方法 |

#### 前端

| 文件 | 类型 | 说明 |
|------|------|------|
| `views/ExecutionResult.vue` | 修改 | 新增"下载报告"按钮 + 录屏播放器（播放/暂停/进度条） |
| `views/BatchResult.vue` | 修改 | 新增"下载批次报告"按钮 |

### 验证
- 后端编译: `mvn compile` BUILD SUCCESS (9s)
- 前端构建: `npm run build` 成功 (11.71s)

---

## v2.3 — LLM 调用全量拆分到 MCP Server

**日期**: 2026-08-09
**基线**: v2.2
**迭代主题**: 后端所有 LLM 调用从 OkHttp 直调改为通过 MCP 协议调用

### 改动清单与目的

#### MCP Server

| 文件 | 改动 | 目的 |
|------|------|------|
| `mcp-server/index.js` | 修改 | 新增 llm_chat + llm_chat_with_image 工具（共 3 个工具） |

#### 后端

| 文件 | 改动 | 目的 |
|------|------|------|
| `service/LlmService.java` | 重构 | 从 OkHttp 直调 OpenAI API 改为通过 McpClient 调用 MCP Server |

### 架构变更
```
v2.2: LlmService → OkHttp → OpenAI API（直调）
      McpBridgeService → McpClient → MCP Server → OpenAI Vision API

v2.3: LlmService → McpClient → MCP Server → OpenAI API（全部走 MCP）
      McpBridgeService → McpClient → MCP Server → OpenAI API
```

### 关键设计
- LlmService 所有 public 方法签名不变（chat/chatJson/chatWithImage/isConfigured/testConnection）
- 6 个调用方（PrdAgent/TestGeneratorAgent/StateMachineAgent/VueAnalyzer/ExecutionAgent/SettingsService）零改动
- 重试逻辑保留在 Java 侧
- MCP Server 暴露 3 个工具：llm_chat、llm_chat_with_image、multimodal_element_locate

### 验证
- 后端编译: `mvn compile` BUILD SUCCESS (82 source files, 10.5s)
- 前端: 无改动

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
