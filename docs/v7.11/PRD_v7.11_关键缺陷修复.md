# PRD v7.11 — 关键缺陷修复

> 版本：v7.11
> 日期：2026-08-23
> 基线：v7.10
> 主题：全量复审 P0 四项 + 速赢三项——流式 LLM 错误处理、用例全局唯一编号、Playwright 多会话隔离、覆盖缺口收敛

---

## 一、背景

v7.10 完成缓冲区收尾（风险清单 A/B/C 三区全部关闭）后，对项目做了一次全量代码复审（生成链路 / 执行链路 / 服务层 / 分析器·MCP·前端四路并行深审），共登记 33 项新风险。其中 4 项为 P0 级（数据丢失、线程死循环、并发互踩、生成空转），另有 3 项改动极小但触发面广的速赢项。本版集中修复这 7 项，其余 P1/P2 留待 v7.12+。

### 痛点分析

**P0-1（L14）流式 LLM 调用出错时线程死循环 + 错误伪装成"用户取消"**
`LlmService.chatStreamingWithUsage` 中 Reactor 的 error 信号只触发 `doOnError`，不触发 `doOnComplete/doOnCancel`——CountDownLatch 永不归零，外层 `while(!done.await(200ms))` 无限轮询。流式传输中途断连/5xx 时：generationExecutor 线程被逐个耗尽（后续生成任务排队饿死）；即使靠取消信号跳出，取消检查先于 errorRef 检查，真实异常被丢弃并谎报为 `GenerationCancelledException("用户取消生成")`——真实错误既不重试也不进熔断计数。

**P0-2（T1/T2）test_cases 全局主键 + 各项目独立 TC-xxx 编号 → 跨项目静默互相覆盖**
`TestCase.id` 是无项目维度的全局主键，而编号处处按项目分配（生成从 TC-001 重编、追加/导入取项目内 max+1）。JPA 对非 null id 的 save 走 merge——项目 B 的 TC-001 会整行 UPDATE 项目 A 的 TC-001（projectId 一并改写），A 的用例"消失"。另外手动创建用 `size()+1` 编号（全库其他 4 处都是 max+1），删掉 TC-005 后新建得 TC-010，单项目内即可静默覆盖现存 TC-010。

**P0-3（E12）Playwright 单浏览器模型 × 项目并发配额 3 = 并发任务必然互踩**
playwright-mcp-server（自研 Node 子进程）全局单例 `browser/context/page`，所有 MCP 调用不传会话标识。同项目并发执行（配额默认 3）时：后启动的 browser_launch 重置浏览器，前一任务的页面丢失；取消任务 A 的 `markRunningCancelled` 全局 stopRecording + browser_close——把任务 B 正在录的视频存进 A 的目录并直接杀死 B 的浏览器；截图文件名 `System.currentTimeMillis() + ".png"` 并发毫秒级碰撞互相覆盖。

**P0-4（G21）覆盖缺口 componentIds/dependencyIds 永不消减 → 多轮生成永不收敛**
`buildCoverageChecklist` 把 componentIds（前端命中业务组件）和 dependencyIds（后端操作依赖）放入初始 gaps，但 `remainingGaps` 只对 4 类（requirement/transition/endpoint/rule）removeCovered。用例侧 coverageRefs 按约定只有 4 个 key——组件/依赖缺口在轮间状态传递中没有任何消减通道。前端 RAG 命中组件或存在依赖图时（常见配置），缺口恒存在 → 每次生成必烧满 3-4 轮 LLM 空转，且 `roundsNotConverged` 恒误报 → 正常生成被 `markDegraded` 标记降级。

**速赢三项（T3/E13/E14）**
- T3：`JsonHelper.parseMap` 对 null/非法 JSON 返回不可变 `Collections.emptyMap()`，TestCaseReviewAgent 4 处直接 `hints.put(...)` 无捕获——executionHints 为 null 的旧/手工用例触发评审 rerun 时整个请求抛 500。
- E13：排队超时收尾不检查已取消状态直接覆盖成 failed，且全程不清 `exec:cancel:` 取消标志（内存版永久残留）。
- E14：agent_task 状态机无翻转保护——排队超时 `fail()`、worker 迟到 `succeed()` 都能把 CANCELLED 任意覆盖。

---

## 二、范围

### In Scope（7 项）

| # | 编号 | 问题 | 修复方向 |
|---|---|---|---|
| 1 | L14 | 流式 LLM 错误死循环 + 错误伪装取消 | doOnError 补 countDown；errorRef 检查提前；GenerationCancelledException 原样透传 |
| 2 | T1+T2 | 用例主键全局撞号 + size()+1 编号冲突 | 全局唯一取号器（跨项目 max+1，JVM 内串行化）；createTestCase/生成/追加/导入/复制统一走分配器 |
| 3 | E12 | Playwright 单浏览器并发互踩 | MCP Server 会话 Map（按 session_id 隔离 browser/context/page）；Java 端全链路传 sessionId；取消路径按会话收尾 |
| 4 | G21 | componentIds/dependencyIds 缺口永不消减 | 该两类从终止判定中排除（保留为参考上下文） |
| 5 | T3 | JsonHelper 不可变空 map 导致 500 | parseMap/parseListMap 返回可变容器 |
| 6 | E13 | 排队超时覆盖 cancelled + 标志残留 | 覆盖前检查终态；收尾 clearFlag |
| 7 | E14 | agent_task 终态无保护 | succeed/fail/cancel 等遇 CANCELLED 终态跳过 |

### Out of Scope（明确不做，留后续版本）

- 审查报告其余 P1/P2 项（reject 分母、bestSelector 混合池、子串判重门槛、Redis 信号量双计数、SSE 瞬断回退轮询、报告 base64 内存、熔断半开等）——v7.12+ 按优先级排期
- LLM 熔断器半开恢复（L2）——独立专项
- 前端 SSE 断线重连/轮询回退——独立专项
- 旧版 v6.4 前存量数据修复——T1 的历史覆盖已发生且不可恢复，无迁移意义

---

## 三、功能详细设计

### 3.1 L14 流式错误处理（LlmService）

`chatStreamingWithUsage` 三处修改：
1. `doOnError` 回调中同时 `done.countDown()`（当前只 set errorRef）；
2. 循环结束后 errorRef 检查提前到 cancelSignal 检查之前；
3. errorRef 中的异常若是 `GenerationCancelledException` 则原样抛出（保持取消语义——doOnNext 里抛出的取消异常会经 error 信号回流 errorRef）。

语义变化对照：
- 流正常完成 + 用户取消：报取消（不变）；
- 流因错误终止 + 未取消：抛真实 RuntimeException → 进入重试/熔断（修复核心）；
- 流因错误终止 + 用户恰好取消：抛真实错误（原为谎报取消）；
- doOnNext 内取消异常回流：抛 GenerationCancelledException（不变）。

### 3.2 T1+T2 用例全局唯一编号

新增 `TestCaseIdAllocator`（@Component，依赖 TestCaseRepository）：
- `synchronized nextId()`：查询全库 `id LIKE 'TC-%'` 的最大数字后缀（JPQL 取 id 列表 + Java 侧解析，方言无关），返回 `String.format("TC-%03d", max+1)`；
- JVM 内 synchronized 串行取号（本项目单实例部署，进程内互斥足够；跨实例场景不在本期范围）。

改造 5 个分配点，全部改走分配器：
1. `TestCaseService.createTestCase`：弃用 `size()+1`（T2 修复）；
2. `TestCaseService.nextTestCaseNumber`（追加生成/JSON 导入/XMind 导入/跨项目复制共用）：改为委托分配器（跨项目 max+1，T1 修复）；
3. `TestGeneratorAgent` 生成收尾（原批内 TC-001 起）：改为逐条调用分配器——流式推送的用例 ID 即最终落库 ID；
4. `TestCasePersistenceService.replaceAll` 不重分配（上游已全局唯一）。

存量数据：已发生的跨项目覆盖不可恢复，无迁移；新分配从全局现有 max+1 起步，天然不与存量撞号。项目内编号会跳跃（重新生成=全新一批全局号），语义合理。

### 3.3 E12 Playwright 多会话隔离

**playwright-mcp-server/index.js**：
- 模块级 `browser/context/page` 三个全局变量改为 `sessions = new Map()`（key=sessionId，value={browser, context, page}）；
- 全部 13 个工具的 inputSchema 增加可选 `session_id` 参数（缺省 `"default"`）；
- 所有 handler 通过 `getSession(args.session_id)` 路由到对应会话；`browser_launch` 按 session_id 创建并注册会话（同 id 重复 launch 时先关闭旧会话）；
- `browser_close` 只关指定会话并从 Map 移除；
- `browser_video_save` 操作指定会话的 context/page（关闭该会话 context 落盘视频）。

**PlaywrightRecordSkill.java**：
- `browserLaunch` 增加 sessionId 参数（executionId 传入），MCP 调用带 `session_id`；
- 全部方法（navigate/screenshot/visualClick/domClick/fillInput/pressKey/scroll/addCookies/getPageStatus/stopRecording/closeSession）的 MCP 调用统一附 `session_id`；
- `stopRecording(String filename)` 增加 sessionId 参数（新签名 `stopRecording(String sessionId, String filename)`）。

**ExecutionService.java**：
- `browserLaunch` 调用改传 executionId 作为会话标识（sessionId=executionId）；
- `markRunningCancelled` 的 stopRecording 带 executionId——只保存该任务自己的视频，不再误杀并发任务；
- 正常收尾路径的 stopRecording 同步改签名。

向后兼容：MCP Server 不传 session_id 时落到 "default" 会话——任何未改造的调用方行为不变。

### 3.4 G21 覆盖缺口收敛

`TestGeneratorAgent`：
- `hasRemainingGaps` 只检查 4 类可消减缺口（requirementIds/transitionIds/endpointIds/ruleIds）；
- `remainingGaps` 返回值中 componentIds/dependencyIds 保留原样（供下一轮 prompt 参考提示"尚有组件/依赖未被覆盖"），但不影响循环终止；
- 多轮循环正常按 4 类收敛——组件/依赖类从"可验证覆盖项"降级为"参考清单"（用例侧本就无结构化 refs 可回填，LLM 无法自证覆盖）。

### 3.5 T3 JsonHelper 可变容器

`parseMap` 的三处 `Collections.emptyMap()` 返回改为 `new LinkedHashMap<>()`；`parseListMap` 同理改 `new ArrayList<>()`。调用方（TestCaseReviewAgent 4 处 put、其他读方）零改动——不可变空 map 无任何调用方依赖其不可变性。

### 3.6 E13 排队超时收尾

`acquireProjectPermitOrTimeout` 超时分支：
- 覆盖状态前检查 `!"cancelled".equals(record.getStatus())`——已取消的任务保持 cancelled，不再翻转成 failed；
- 收尾补 `runtimeStore.clearFlag("exec:cancel:" + executionId)` 清除取消标志（配合 E14，agent_task 侧 fail 对已 CANCELLED 任务跳过）。

### 3.7 E14 agent_task 终态保护

`AgentTaskService`：
- 新增 `TERMINAL_STATUSES = Set.of(STATUS_SUCCEEDED, STATUS_FAILED, STATUS_CANCELLED, STATUS_DLQ)`；
- `succeed/fail/cancel/markNeedsReview/markDlq` 的 mutator 开头检查：当前状态已是终态且与目标状态不同则跳过（log.warn）——CANCELLED 不再被 fail/succeed 覆盖；
- `checkpoint/markDegraded` 等非终态操作不限制（运行中打点不受影响）；
- `requeue`（人工重试入口）不受终态保护限制——管理员显式重试允许翻转。

---

## 四、验收标准

1. **L14**：单测模拟流 error 信号——latch 归零、抛出真实异常（非 GenerationCancelledException）、熔断 onFailure 被调用；模拟取消异常回流——抛 GenerationCancelledException。
2. **T1/T2**：单测验证——两个项目各建 3 条用例得到 6 个互不相同的全局 ID；删中间用例后新建不与现存撞号；生成链路产出的 ID 全局唯一。
3. **E12**：单测验证 PlaywrightRecordSkill 全部 MCP 调用携带 session_id；MCP Server（Node）手工验证两个会话并行操作互不影响、close 只关指定会话。
4. **G21**：单测验证——gaps 含 componentIds 缺口时 `hasRemainingGaps` 返回 false（4 类空时），多轮循环正常终止，roundsNotConverged 不误报。
5. **T3**：单测验证 parseMap(null) 返回的 map 可 put 不抛异常。
6. **E13**：单测验证已取消记录排队超时后状态保持 cancelled、取消标志被清除。
7. **E14**：单测验证 CANCELLED 任务调用 fail/succeed 后状态不变。
8. 全量 `mvn test` 通过；前端 `npm run build` 回归通过（本版无前端代码变更）。

---

## 五、风险与缓解

| 风险 | 影响 | 缓解 |
|---|---|---|
| 全局取号在生成大批量用例时逐条查库（60 条=60 次查询） | 生成耗时增加（H2 本地查询 <1ms/次，可忽略） | 分配器内做 JVM 缓存（AtomicInteger 预取区间，启动时从 DB max 初始化） |
| Playwright 多会话=多浏览器进程，并发 3 时内存占用 ×3 | 资源压力 | 配额本身限制并发 ≤3；无头 Chromium 单实例 ~300MB，3 并发可接受；会话关闭即释放 |
| MCP Server 会话 Map 泄漏（会话异常退出未 close） | Node 进程内存增长 | browser_launch 同 id 重复 launch 先关旧会话；ExecutionService finally 必关；后续版本可加会话 TTL 巡检 |
| 生成用例 ID 从"项目内连续"变"全局跳跃" | 用户观感变化 | TC 编号语义本应是全局唯一标识（对齐 TestRail 等工具惯例）；README/CHANGELOG 明示 |
| 存量已互相覆盖的数据 | 历史丢失不可恢复 | 明示 out of scope；新机制止损 |

---

## 六、交付物

- [x] PRD（本文件）
- [x] 后端技术评审 v7.11
- [x] 前端技术评审 v7.11（无代码变更，回归构建）
- [x] 后端实现 + 单测（353 用例全绿）
- [x] CHANGELOG / README / 风险清单 / 迭代计划更新
- [x] git commit + push
