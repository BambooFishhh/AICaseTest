# vT1 PRD：测试与运维基线

## 1. 迭代背景与痛点

- 项目已迭代至 v5.8，业务代码约 3 万行，但单元测试只有 2 个，测试基线缺失。
- CI 只做后端 `mvn test` 与前端构建，未校验 compose 编排，运维基线缺失。
- 需要建立独立工程基线版本线（vT 系列），先把"测试能跑、配置能校验、验证可重复"固定下来。

## 2. 范围（In / Out of scope）

### In scope

- 新增核心基础组件单元测试：内存运行态、任务队列、登录防爆破。
- 测试基线：`mvn test` 全量通过（13 个测试）。
- CI 增强：新增 docker compose 配置校验 job。
- 运维验证：`scripts/verify-v5-stack.ps1` 覆盖后端测试、前端构建、compose 配置、可选健康检查。
- README / CHANGELOG 增加 vT1 版本线与测试运维说明。

### Out of scope

- 集成测试与端到端浏览器测试（后续 vT2 规划）。
- 业务功能改动。

## 3. 功能详情

### 3.1 测试基线

| 测试 | 覆盖点 |
|---|---|
| MemoryRuntimeStoreTest（5） | 取消标志、会话、心跳、登录计数/锁定、并发配额 |
| MemoryTaskQueueStoreTest（3） | 入队/运行/完成计数、多队列独立、幂等 |
| LoginAttemptServiceTest（3） | 5 次锁定、阈值内不锁、成功后清状态 |
| CsvExporterTest（1） | CSV 导出 BOM/表头/字段 |
| XmindServiceTest（1） | XMind 生成与逆向解析 round-trip |

### 3.2 CI 基线

`.github/workflows/ci.yml`：

- backend job：JDK 17 + `mvn -B test`
- frontend job：Node 22 + `npm ci` + `npm run build`
- compose job：`docker compose config --quiet`

### 3.3 运维验证

`scripts/verify-v5-stack.ps1` 已是完整回归入口，本次同步纳入 vT1 基线说明。

## 4. 验收标准

1. `mvn test` 13 个测试全部通过。
2. `npm run build` 成功。
3. `docker compose config --quiet` 通过。
4. CI 三个 job 均可执行。
5. README / CHANGELOG 已建立 vT1 版本线。

## 5. 风险与规避

| 风险 | 规避 |
|---|---|
| 测试覆盖不足继续扩大 | vT1 先补基础层，vT2 规划服务层/集成测试 |
| compose 校验在 CI 无 Docker | GitHub Actions ubuntu 自带 docker compose v2 |

## 6. 交付物清单

- PRD / 后端评审 / 前端评审
- 3 个新增测试类（11 个测试）
- CI compose job
- README / CHANGELOG 更新
