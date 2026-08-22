# PRD v7.0：执行可信度修复

> 版本：v7.0 ｜ 主题：执行链路诚实化（对应风险清单 E1 E2 E3 E4 E8 E12）
> 基线：v6.9（高可用收口）｜ 日期：2026-08-22

## 一、背景与痛点

全链路代码审查（见《代码审查风险清单.md》E 系列）发现执行链路存在 4 个 P0 级"结果不可信"问题与 2 个 P1 级体验问题，共同特征是**"看起来在验证，实际上没验证"**：

1. **E1 取消复活**：取消"排队中"的执行后 worker 照跑，结束时把 cancelled 状态覆盖成 passed/failed；单条执行取消时若 worker 正阻塞在并发等位（无心跳），系统误判 worker 已死并清掉取消标志，worker 醒来照跑。
2. **E2 高可用调度器误伤**（v6.6 回归）：HaTaskScheduler 每 15 秒无差别扫描 QUEUED 任务，执行任务在"创建 QUEUED"与"worker start() RUNNING"之间的窗口内被 CAS 抢占并标记 NEEDS_REVIEW("UNSUPPORTED_RETRY")，随后又被覆盖回 RUNNING——管理端误报刷屏，破坏高可用观测性。
3. **E3 基础设施故障记 passed**：浏览器启动失败、导航异常、无结构化步骤等场景下 errorMessage 不参与最终状态判定，记录显示 passed、用例回写"通过"，仪表盘通过率虚高。
4. **E4 state_assert 假通过**：程序化模式的状态断言步骤只读页面状态就无条件 passed，断言从不比较——"状态流转验证"这一核心卖点在程序化模式下全是假数据。
5. **E8 心跳粒度粗**：心跳按步骤粒度打（每步一次），Agent 模式单步内部最多 5 次定位 + 4 次 LLM 调用，常超 30 秒；慢步骤期间用户取消会被误判为"worker 已死"，触发 E1 的复活竞态。
6. **E12 skip 决策无引导 + 错误信息栽赃**：策略决策 prompt 只给三个选项不给决策规则，LLM 见"未找到"倾向保守 skip（即使备用 DOM 选择器存在）；所有 skip 的 error 一律写"LLM 决策跳过该步骤"，LLM 未配置/异常时的 default 兜底也这么写——用户观察到的"大量 skip 而非 dom 点击"排查方向被带偏。

## 二、范围

**In scope（本版做）**：
- E1：worker 收尾前复查记录状态；批量取消 pending 补设运行时取消标志
- E2：HaTaskScheduler 分发前过滤执行类型任务
- E3：errorMessage 参与最终状态判定（两处：程序化 + Agent 模式）
- E4：state_assert 最小诚实断言（expected 与 URL/标题包含比较；无法验证时如实标注）
- E8：ExecutionAgent 单步内部关键耗时点补心跳
- E12：决策 prompt 增加规则引导 + skip 错误信息按来源区分

**Out of scope（后续版本）**：
- R10 全 skipped 的状态语义（v7.2 度量诚实化统一处理）
- E5 agent 模式 state_assert/api_call 分流（v7.6 断言闭环）
- E6 生效判断喂截图证据（v7.9）
- E7 线程池策略与批量限流（v7.9）

## 三、功能点与验收标准

### 3.1 取消不再复活（E1）
- 验收：批量取消 pending 任务后，被取消的任务不会被 worker 覆盖为 running/passed/failed；单条取消阻塞等位中的任务后，worker 醒来检查 DB 状态发现 cancelled，直接收尾不开浏览器。
- 实现：worker 收尾写终态前复查 DB 记录状态，已 cancelled 则不覆盖；批量取消 pending 分支补 `runtimeStore.setFlag("exec:cancel:{id}")`。

### 3.2 调度器不再误伤执行任务（E2）
- 验收：执行任务从创建到结束，agent_task 状态轨迹不再出现 NEEDS_REVIEW("UNSUPPORTED_RETRY") 翻转；分析/生成任务的兜底分发不受影响。
- 实现：`HaTaskScheduler.dispatchQueuedTasks` 对 `findQueued()` 结果按任务类型过滤，TYPE_EXECUTION 跳过（执行由 executionExecutor 专属路径驱动）。

### 3.3 基础设施故障不再记 passed（E3）
- 验收：浏览器启动失败/导航异常/无结构化步骤的执行记录 status=failed，summary 明示原因，用例回写 failed。
- 实现：最终状态判定改为 `errorMessage != null → failed`；summary 追加 errorMessage 摘要。

### 3.4 state_assert 诚实断言（E4）
- 验收：expected 含 URL/标题关键词且页面匹配 → passed（coordinates 记录实际 url）；不匹配 → failed；expected 为 API 形态（如 status=XXX）或无法比较 → skipped + "UI 层暂无法验证，原因"。不再出现无条件 passed。
- 实现：程序化模式 state_assert 分支按 expected 文本与 pageStatus 的 url/title 做包含比较。

### 3.5 单步内补心跳（E8）
- 验收：Agent 模式单步执行超过 30 秒时取消，isWorkerAlive 仍为 true，走正常"置标志→worker 自行停止"路径，结果记 cancelled 而非 failed。
- 实现：ExecutionAgent 注入 RuntimeStore，在每次 LLM 调用与多模态定位前后 touch 心跳。

### 3.6 skip 决策有规则、错误信息说实话（E12）
- 验收：LLM 配置正常时，found=false 但存在备用选择器的步骤以 dom_click 执行而非 skip；error 字段能区分"LLM 决策跳过（含理由）"与"MCP 未找到且无 DOM 选择器"。
- 实现：决策 prompt 增加决策规则；skip 分支 error 优先取 decision.reason。

## 四、风险与缓解

| 风险 | 缓解 |
|---|---|
| E4 断言误判（expected 表述含糊） | 保守策略：无法提取可比较关键词时不判 failed，判 skipped 并说明——宁可"未验证"不可"误报失败" |
| E1 收尾复查引入 DB 二次读 | 单次 findById，代价可忽略 |
| E3 使部分存量"通过 0/跳过 0"的记录变 failed | 预期内（度量校准），CHANGELOG 说明 |
| E2 过滤可能漏掉真正需要兜底的执行任务 | 执行任务本就有 executionExecutor 直跑路径与租约恢复（recoverStaleTasks），不依赖通用分发 |

## 五、交付清单

- [ ] PRD / 后端技术评审 / 前端技术评审
- [ ] 后端 6 项修复 + 单元测试（ExecutionServiceTest / HaTaskSchedulerTest / ExecutionAgentTest）
- [ ] mvn compile + 全量测试通过
- [ ] CHANGELOG / README 更新
- [ ] git commit + push
