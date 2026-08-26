# PRD v8.8.2 — 双实例就绪 + 积压可观测 + 混沌演练固化

> 版本 v8.8.2，一旦确定尽量不要轻易改动。基线 v8.8.1。范围：计划书任务 10.4–10.6（v8.8 拆分版下半）。

## 一、背景与痛点

- 10.4 预判"既有任务可能未上锁"——排查确认：HA 租约恢复/TTL 过期/QUEUED 兜底分发/数据保留清理四个任务全部裸奔 @Scheduled，双实例部署即重复执行。
- 任务积压（QUEUED 堆积/RUNNING 泄漏）无指标无告警，只能进任务中心人肉看。
- 10.6 要求的三类故障场景（畸形输出/Milvus 断连/池饱和）散落在零散单测里，没有固化为可独立触发的演练分组。

## 二、范围

| # | 任务 | 内容 |
|---|---|---|
| 10.4 | 双实例就绪排查 | 锁覆盖清单核对 + 缺失补齐 + 状态源核对 + 排查报告落 docs/ |
| 10.5 | 积压可观测 | agent_task 状态计数 Gauge + RUNNING/QUEUED 两条告警 + 看板面板 |
| 10.6 | 演练固化 | 三场景 @Tag("chaos") 测试，默认排除不阻塞日常构建 |

## 三、功能细节

- **补锁**：haRecoverStaleTasks(PT4M)/haExpireTasksByTtl(PT9M)/haDispatchQueuedTasks(PT14S)/dataRetentionClean(PT10M)，lockAtMostFor 与调度周期匹配。
- **积压 Gauge**：TaskBacklogMetrics 每 30s 读 countGroupByStatus 刷新七状态 Gauge（agent_task_queued/running/...）；只读操作多实例并发无害故不上锁；告警两条——RUNNING>5 持续 15m（warning）、QUEUED>20 持续 10m（warning）。
- **chaos 分组**：surefire excludedGroups 属性化（默认 chaos），`mvn test -Dgroups=chaos -Dsurefire.excludedGroups=` 触发；三场景：
  1. 畸形输出对抗集——截断数组/非数组整段上抛重试、标量与嵌套炸弹条目级容错不抛异常
  2. Milvus 断连——gRPC UNAVAILABLE 终败落补偿表 upsert 不抛异常、DNS 故障按 invalidParam 拒绝
  3. 池饱和——快速拒绝 handler 抛 RejectedExecutionException 并计 executor_rejected_total{pool}

## 四、验收标准

1. 全部 @Scheduled 任务有 @SchedulerLock（报告清单核对）。
2. 日常构建排除 chaos 全绿；显式触发 chaos 分组全绿。
3. promtool 校验通过、Gauge 在 /actuator/prometheus 可见。

## 五、交付物清单

HaTaskScheduler/DataRetentionService 补锁；TaskBacklogMetrics 新增 + MetricsFacade.gaugeRaw；alerts.yml +2 条；看板 +1 面板；pom excludedGroups 属性化；chaos 测试 ×3；docs/双实例就绪排查报告.md。
