# PRD v8.6.1 — 向量数据一致性闭环（计划书阶段 2 上半）

> 版本 v8.6.1，一旦确定尽量不要轻易改动。基线 v8.5。范围：计划书任务 9.1–9.4，消灭差距 G2。
> v8.6 整体拆分说明：阶段 2 含两个新依赖与两条独立子线，单版过大，按用户授权拆为 v8.6.1（本版，G2 一致性闭环）与 v8.6.2（G3 出参契约，9.5–9.8）。

## 一、背景与痛点

Milvus 与 MySQL 之间无对账机制，向量层故障路径全部止步于日志：

1. **删除失败无补偿**——`MilvusService.deleteWithRetry` 重试后仍失败仅 ERROR 日志，残留幽灵向量累积；
2. **幽灵召回误杀去重**——`isDuplicate` 取 top-1 相似命中即判重，若该 id 已从 DB 删除则新用例被幽灵向量误杀；
3. **无周期对账**——DB 与向量的漂移（漏索引/删失败残留/项目删除部分失败）无人发现也无人修复。

## 二、范围

### In Scope

| # | 任务 | 内容 |
|---|---|---|
| 9.1 | 删除补偿表 | `pending_vector_ops` 表 + 实体/仓库；删除最终失败落表而非仅日志 |
| 9.2 | 补偿重放任务 | 引入 ShedLock（多实例安全）；定时扫描 PENDING 记录指数退避重放，超限 DEAD |
| 9.3 | 周期对账任务 | `reconciliation_reports` 表；按项目对账 DB↔向量，缺失重建/孤儿删除/超阈值告警；管理端点暴露报告 |
| 9.4 | 检索侧兜底过滤 | 召回后按用例 id 批量查 DB 剔除幽灵项（去重链路 + 检索链路），过滤计数告警 |

### Out of Scope

- 出参契约（9.5–9.8 → v8.6.2）
- contexts/failures/components 三集合的对账（仅对账 cases 主集合；上下文类切片可由重建链路自愈）

## 三、功能细节

- **补偿表**：op_type/collection/expr(PENDING 原样保存)/attempts/last_error/status(PENDING/DONE/DEAD)/next_attempt_at；同 collection+expr 的重复失败 upsert 不堆行。
- **重放**：默认每 5 分钟扫 PENDING 到期记录（单批 ≤50）；成功置 DONE；失败 attempts+1 按 60s×2^n 退避；达到 `compensation-max-attempts`(5) 置 DEAD 并 ERROR 告警。
- **ShedLock**：shedlock-spring 5.16.2 + jdbc-template provider；补偿与对账任务均上锁，双实例不重复执行；H2 开发环境启动时幂等建表（Flyway 仅管 MySQL）。
- **对账**：每日 02:00 cron（可配）；逐项目比对 DB 用例 id 集 ↔ Milvus cases 集合 expr 查询 id 集；DB 多→批量补索引；向量多→孤儿删除（走 deleteWithRetry→补偿链）；漂移率 > `reconcile-drift-threshold`(0.02) 记 WARN；报告落表并经 `/api/admin/vector/reconciliation` 暴露最近 20 条。Milvus 查询失败记 SKIPPED 不误触发全量重建风暴。
- **兜底过滤**：`isDuplicate` top-1 命中须存在于 DB 才判重（否则 WARN+放行）；`searchCases` 改批量 `findAllById` 单次往返并按相似度序保留存在项；幽灵召回计数器供后续指标接入。

## 四、验收标准

1. 补偿三场景测试绿：重放成功置 DONE / 失败退避续期 PENDING / 超限置 DEAD。
2. 对账四分支测试绿：正常 / 缺失重建 / 孤儿删除 / 超阈值 WARN；查询失败 SKIPPED。
3. 兜底过滤测试绿：幽灵 id 不再触发判重、不出现在检索结果，其余项保序保留。
4. MySQL 迁移集成测试覆盖 V14–V16；后端全量回归绿。

## 五、风险与缓解

| 风险 | 缓解 |
|---|---|
| Milvus 宕机期间对账误判"全部缺失"引发重建风暴 | query 失败返回 null（区别于合法空集），记 SKIPPED 跳过该项目 |
| 重放任务与在线写入竞争 | 重放走统一 deleteWithRetry 入口；ShedLock 单实例执行 |
| H2 开发环境缺 shedlock 表 | 启动时幂等 CREATE TABLE IF NOT EXISTS 兜底 |
| 新依赖拉取慢 | .mvn-repo 缓存挂载容器下载一次即持久 |

## 六、交付物清单

- [ ] 后端：实体×2 + 仓库×2 + 任务×2 + 配置类×1 + 控制器×1；MilvusService/SemanticService/pom/application.yml 修改
- [ ] 迁移：V14/V15/V16
- [ ] 测试：补偿/重放/对账/过滤四组新增
- [ ] 文档：CHANGELOG / README / 本目录评审文档 / 计划书状态列
