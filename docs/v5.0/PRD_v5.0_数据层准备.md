# v5.0 PRD：数据层准备（Flyway + 双数据源 + MySQL 基建）

## 1. 迭代背景与痛点

- 当前系统使用 H2 文件库作为唯一持久化方案，`ddl-auto: update` 由 Hibernate 隐式建表，缺少 schema 版本追踪，无法支撑生产级 MySQL 切换。
- docker-compose 只有 backend / frontend，没有独立数据库容器，生产部署数据与代码耦合在容器卷里。
- 实体字段中大量 JSON 结构以 TEXT 列存储，但缺少统一的字段/ID 约定梳理，迁移前需要建立基线。
- v5 系列目标是把主数据迁到 MySQL、运行态迁到 Redis、语义资产迁到 Milvus，v5.0 必须先铺好数据层基础设施。

## 2. 范围（In / Out of scope）

### In scope

- 引入 Flyway 作为 schema 版本管理。
- 建立双数据源 profile：默认 dev=H2，prod/mysql=MySQL。
- 编写 MySQL 初始 schema（V1 基线迁移），覆盖全部业务实体。
- docker-compose 新增 `aicasetest-mysql` 服务，避开 3306/3307，使用 3308。
- 字段与 ID 梳理：整理 13 张表的主键/外键/JSON 列/状态字段口径。

### Out of scope

- H2 → MySQL 数据迁移工具（v5.1）。
- Redis / Milvus 接入（v5.2 ~ v5.4）。
- 正式切换默认数据源（v5.5）。
- 前端功能改动（本版本无 UI 需求）。

## 3. 功能详情

### 3.1 Flyway 版本管理

- 后端新增 `flyway-core` + `flyway-mysql` 依赖。
- MySQL 迁移脚本目录：`backend/src/main/resources/db/migration/mysql/`。
- H2 开发环境不启用 Flyway，继续由 JPA `ddl-auto: update` 管理，避免影响存量本地数据。
- MySQL profile 设置 `ddl-auto: none`，schema 完全由 Flyway 脚本控制，保证可追踪、可回放。

### 3.2 双数据源 profile

| profile | 数据源 | ddl-auto | Flyway | 用途 |
|---|---|---|---|---|
| 默认（dev） | H2 文件库 | update | 关闭 | 本地开发 |
| mysql | MySQL | none | 开启 | 本地/容器连 MySQL |
| prod | 继承 mysql | none | 开启 | Docker 生产部署 |

### 3.3 MySQL 服务

- `docker-compose.yml` 新增 `aicasetest-mysql`（mysql:8.0）。
- 端口映射 `3308:3306`，库名 `aicasetest`，独立 volume 持久化。
- 配置 healthcheck，后端在 v5.5 正式切换前保持 H2，但基础设施先行就绪。

### 3.4 字段与 ID 梳理基线

| 表 | 主键策略 | 关键 JSON/长文本列 | 说明 |
|---|---|---|---|
| projects | 8 位短 UUID | settings / techStack / prdContent | userId/groupId 归属 |
| test_cases | TC-序号 | preconditions / steps / structuredSteps / apiEndpoints / testData / executionHints / stateMachineRef | 执行/评审状态 |
| test_case_versions | UUID | snapshot | 版本快照 |
| execution_record | 8 位短 UUID | testCaseSnapshot / recordingFrames | batchId 批次 |
| execution_step | 8 位短 UUID | - | 步骤级证据 |
| state_machines | 8 位短 UUID | states / transitions / forbiddenTransitions | 置信度 |
| code_analysis | 8 位短 UUID | frontendResult / backendResult | 每项目保留最新 |
| mindmaps | 8 位短 UUID | statistics | 每项目保留最新 |
| test_suites | UUID | caseIds | 测试集 |
| users / project_groups / group_members | UUID | - | 账号与组织 |
| system_settings | setting_key | settingValue | KV 配置 |

## 4. 验收标准

1. `mvn compile` 在新增依赖后 BUILD SUCCESS。
2. 默认 H2 启动方式不变，本地存量数据可继续使用。
3. `--spring.profiles.active=mysql` 可通过 Flyway 在空 MySQL 上完成建表。
4. MySQL V1 schema 覆盖全部 JPA 实体表，JPA `ddl-auto: none` 下启动无表缺失。
5. `docker-compose up mysql` 能拉起 MySQL 8 并完成健康检查。
6. 前端 `npm run build` 成功（无前端改动，仅回归）。

## 5. 风险与规避

| 风险 | 规避 |
|---|---|
| Flyway 与 JPA 双管理导致 schema 冲突 | H2 关闭 Flyway；MySQL 关闭 ddl-auto |
| MySQL 版本/驱动兼容 | 使用 Spring Boot BOM 管理的 connector + flyway-mysql |
| 3306/3307 与本机已有 MySQL 冲突 | 固定映射 3308 |
| 存量 H2 数据在切换前被破坏 | v5.0 不改默认 profile，H2 数据不动 |

## 6. 交付物清单

- `docs/v5.0/PRD_v5.0_数据层准备.md`
- `docs/v5.0/后端技术评审_v5.0.md`
- `docs/v5.0/前端技术评审_v5.0.md`
- 后端：pom 依赖、application-prod/application-mysql、Flyway V1 脚本
- 部署：docker-compose 增加 MySQL 服务
- 文档：CHANGELOG / README 更新
