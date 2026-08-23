# PRD v7.12 — 复审 P1/P2 修复

> 版本：v7.12
> 日期：2026-08-23
> 基线：v7.11
> 主题：全量复审第二批遗留 P1/P2 七项——评审比例分母、选择器混池、判重门槛、Redis 信号量租约、报告流式生成、熔断半开、SSE 瞬断降级

---

## 一、背景

v7.11 修复了全量复审第二批 33 项中的 7 项关键缺陷（4 P0 + 3 速赢），其余 P1/P2 明确留待后续。本版集中修复其中七项影响准确性/可靠性/资源的问题。原审查明细随会话丢失，本版先对风险清单登记的七个关键词逐一回代码定位确认，全部实锤后才纳入范围。

### 痛点分析

**R15（reject 分母）**：`TestCaseReviewAgent.llmReview` 的 reject 三分带比例 `rejectIndices.size() / cases.size()` 以全量为分母，但 reject 只能来自已评审条目。LLM 输出截断（缺评多）时批量 reject 被稀释：20 条用例只评出 10 条且全部 reject，真实比例 100% 应触发"全保留+告警"，现行算出 50% 落入灰区——保护带在缺评场景系统性失灵。

**G22（bestSelector 混合池）**：`enrichStructuredSteps` 把 DOM 选择器（`{type: css/xpath/text, value: 选择器}`）与表单字段（`{name, type: 输入框类型, label}`）混入同一候选池按文本匹配打分。表单字段胜出时步骤写入 `uiSelector = {type: "text"(这是 input type!), value: null}`——不可执行的废选择器固化进用例资产。

**G23（子串判重门槛 + 判重口径漂移）**：`isDuplicate` 的子串规则是无门槛裸 `contains()`——"登录"(2字) 与 "退出登录后重新登录" 判重、"查询" 与 "高级查询" 判重。且 `TestCaseService` 的落库侧判重副本已与生成侧漂移：标题规则无 type 守卫（v7.1(G1) 修复未同步，追加生成负向用例被正向旧用例误杀）、字符重叠率仍是旧阈值 0.8（生成侧已收紧至 0.9）。

**E15（Redis 信号量双计数/超发）**：`RedisRuntimeStore` 项目并发信号量为计数器 + `EXPIRE 600`：① 长执行（Agent 模式单步 LLM 40-120s，超 10 分钟常见）期间键过期 → 计数清零 → 超发（同批槽位被二次分配）；② Redis 瞬断时 acquire 降级内存、恢复后 release 扣减 Redis——扣了从未加过的计数（偷走他任务槽位），内存信号量永久泄漏。

**R16（报告 base64 内存）**：`generateExecutionReport` 把每步前后两张截图全部 base64 内嵌进单个 StringBuilder。30 步执行 × 2 图 × ~200KB → 单次报告生成在堆里积压数十 MB 字符串；controller 再整体返回，峰值翻倍。

**L15（熔断无半开）**：`LlmCircuitBreaker` 只有开/关两态。开启期（默认 30s）过后全量放行——LLM 单调用 40-120s，几十个请求在首个失败重新打开熔断之前已全部涌入 doomed 执行，用户白等 2 分钟拿错误，且重试风暴打垮刚恢复的 provider。

**E16（SSE 瞬断即报错）**：前端 `streamGenerate` 的 error 事件不区分"后端下发的真实错误（e.data 有值）"与"连接层断开（e.data 为空）"——网络瞬断/代理超时把仍在后台正常进行的生成直接报成"生成失败"，刷新页面却又发现任务在跑。

---

## 二、范围

### In Scope（7 项）

| # | 编号 | 问题 | 修复方向 |
|---|---|---|---|
| 1 | R15 | reject 比例分母用全量而非已评审数 | 分母改 `byIndex.size()`（已评审数） |
| 2 | G22 | 选择器候选池混入表单字段产生废 uiSelector | 池只收 DOM 选择器 |
| 3 | G23 | 子串判重无最短门槛 + 落库侧判重口径漂移 | 子串规则要求较短标题 ≥4 字；TestCaseService 副本对齐生成侧语义（type 守卫 + 0.9） |
| 4 | E15 | Redis 信号量 TTL 过期超发 + 混合降级漂移 | ZSET 租约信号量（permitId=executionId）+ 步骤心跳续租 + 内存授予追踪 |
| 5 | R16 | 报告截图 base64 全量堆积内存 | 报告改为流式写入 Writer，峰值内存 = 单张截图 |
| 6 | L15 | 熔断开启期后无半开探测、全量涌入 | 半开态单探测租约（自愈超时），成功才全量恢复 |
| 7 | E16 | SSE 连接层瞬断误报"生成失败" | 区分断连与后端错误；断连降级为项目状态轮询 |

### Out of Scope（明确不做）

- 复审第二批其余 P2 尾巴（不影响正确性的观感类问题）——后续按需排期
- SSE 断线后从断点续传事件（需后端事件回放机制，收益/成本比低；轮询降级已保证结果可见）
- 报告导出为 zip/外链图片（改变"自包含单文件"交付语义，用户未提出）
- 多实例部署下的跨进程信号量强一致（当前单实例为主，租约模型已覆盖双实例场景）

---

## 三、功能详细设计

### 3.1 R15 reject 分母（TestCaseReviewAgent）

`llmReview` 中比例计算：`int n = cases.size()` 改为 `int reviewed = byIndex.size()`（已评审数），`ratio = reviewed == 0 ? 0 : rejectIndices.size() / reviewed`。语义：三分带回答"LLM 对它评过的东西 reject 了多大比例"，缺评保护由 R4 补评机制负责，两道防线各司其职。

### 3.2 G22 选择器池只收 DOM 选择器（TestGeneratorAgent）

`enrichStructuredSteps` 的候选池去掉表单字段（forms 分支整段删除）。表单字段 `{name, type: 输入框类型, label}` 没有可执行的 value，进入池子只会在文本匹配中抢分，胜出即产生 `{type: input-type, value: null}` 废选择器。DOM 选择器独占池后匹配语义不变（L12 阈值 3 + 唯一最高分）。

### 3.3 G23 判重门槛与口径对齐

两处 `isDuplicate` 同步修改：

1. **子串规则加最短门槛**：`titleA.contains(titleB) || titleB.contains(titleA)` 仅在 `min(lenA, lenB) >= 4` 时生效。2-3 字通用动词（"登录"/"查询"/"下单"）的包含关系不再构成判重证据；4 字及以上同型同模块的包含仍判重（语义去重兜底漏网真重复）。
2. **TestCaseService 落库侧对齐生成侧**（v7.1(G1) 修复同步）：标题完全相同/子串/字符重叠三类规则全部加 `typeA.equals(typeB)` 守卫；字符重叠率 0.8 → 0.9。追加生成的负向/边界用例不再被同标题正向旧用例误杀。

### 3.4 E15 Redis 信号量租约（RedisRuntimeStore + 接口链）

**模型**：计数器改为 ZSET 租约——member=permitId（executionId），score=授予/续租时间戳。

```
ACQUIRE: ZREMRANGEBYSCORE(key, -inf, now-leaseMs)   -- 淘汰过期租约（崩溃自愈）
         if ZCARD(key) < max then ZADD(key, now, permitId); PEXPIRE(key, leaseMs+60s); return 1
         return 0
RELEASE: ZREM(key, permitId)                        -- 幂等，重复释放/释放未持有均无害
RENEW:   if ZSCORE(key, permitId) then ZADD(key, now, permitId); PEXPIRE; return 1
```

- **租约 5 分钟 + 步骤心跳续租**：执行器每步 `touchHeartbeat` 处同步 `renew`——活跃执行的租约永不过期；JVM 崩溃后 5 分钟内槽位自动回收（旧实现 TTL 600s 同等自愈速度，且不会再误伤长执行）。
- **permitId 贯穿接口**：`RuntimeStore.acquire/tryAcquire/release` 增加 permitId 参数，新增 `renewProjectPermit`；`ProjectExecutionLimiter` 透传并暴露 `renew`；`ExecutionService` 以 executionId 为 permitId，4 处 release、2 处 acquire、步骤循环心跳处续租。
- **混合降级对称化**：`RedisRuntimeStore` 内部维护 `memoryGranted` 集合——acquire 降级内存时登记 permitId；release 按授予来源路由（内存授予还内存，Redis 授予走 ZREM）；Redis 释放失败仅告警、依赖租约过期自愈，不再错误扣减。
- **内存实现**：`MemoryRuntimeStore` 忽略 permitId（Semaphore 语义不变），`renew` 为 no-op。

### 3.5 R16 报告流式生成（ReportService + ExecutionController）

`generateExecutionReport(String executionId, Writer out)`：HTML 分段直写 Writer，截图逐张读取-编码-写出即释放（峰值 = 单截图 + base64 缓冲 ≈ 数百 KB）。controller 直接 `response.getWriter()` 传入，Content-Disposition/inline 语义不变。自包含 base64 单文件交付不变（下载/分享行为零变化）。批次报告无截图、体积小，维持现状。

### 3.6 L15 熔断半开（LlmCircuitBreaker）

状态机 CLOSED → OPEN → HALF_OPEN → CLOSED/OPEN：

- `allowRequest`：OPEN 期全拒（不变）；开启期已过进入 HALF_OPEN——仅当无在途探测（`probeLeaseUntil` 过期或为零）时放行当前调用者并授予探测租约（`probe-lease-seconds` 默认 120s，覆盖最长 LLM 调用），其余请求照旧快速失败；
- 探测成功 `onSuccess`：计数清零、全关闭、租约清零；
- 探测失败 `onFailure`：阈值已满直接重开，租约清零（下轮开启期过后再探测）；
- 探测既无成功也无失败（4xx 不计数的路径）：租约 120s 自然过期，下个调用者成为新探测器——无死锁、自愈；
- `allowRequest/onSuccess/onFailure` 对状态对象 synchronized（LLM 调用 40s+，锁开销可忽略）。

### 3.7 E16 SSE 瞬断降级（前端）

`streamGenerate`/`streamGenerateAppend` 的 error 分支区分两种情形：
- `e.data` 有值 → 后端下发的真实错误 → 维持现状（onError + close）；
- `e.data` 为空 → 连接层断开（网络瞬断/代理空闲超时）→ 新增 `onDisconnect` 回调 + close，不报错。

`TestCaseList.vue` 两处调用（重新生成/追加生成）传入 `onDisconnect`：复用 `resumeGenerationIfActive` 的轮询机制（info 提示"连接中断，已切换为轮询进度" + `projectStore.startPolling`，状态离开 generating 时刷新列表并给终态提示）。生成中 UI 状态复位（streaming=false），页面刷新后的既有恢复路径不受影响。

---

## 四、验收标准

1. **R15**：单测——20 条送评仅 10 条有评审输出且全 reject，比例按 10 计算（>70%）触发全保留。
2. **G22**：单测——池中仅有表单字段时步骤 uiSelector 保持为空（不写废选择器）；有 DOM 选择器时匹配行为不变。
3. **G23**：单测——"登录" vs "退出登录后重新登录" 同模块同型不判重；4 字子串仍判重；落库侧 "新增用户-正常" vs "新增用户-异常" 不判重（type 守卫）；两侧重叠率阈值一致为 0.9。
4. **E15**：单测——Redis 信号量 Lua 脚本语义（租约淘汰后可重新授予、重复 RELEASE 幂等、RENEW 仅对持有者生效）；acquire 降级内存后 release 路由回内存。
5. **R16**：报告生成不再返回整段 HTML 字符串（流式接口），生成 30 步报告峰值堆 = 单截图量级（代码结构保证，单测验证 Writer 分段写出与内容完整）。
6. **L15**：单测——OPEN 期全拒；开启期过后首个 allowRequest 放行、并发第二个拒绝；探测成功后全放行；探测失败重开；租约过期后可再探测。
7. **E16**：构建通过 + 代码路径审查——连接层错误走 onDisconnect（不弹错误提示），后端错误仍走 onError。
8. 全量 `mvn test` 通过；前端 `npm run build` 通过。

---

## 五、风险与缓解

| 风险 | 影响 | 缓解 |
|---|---|---|
| 信号量接口签名变更波及调用方/测试 | 编译错误 | 调用方仅 ExecutionService + 2 个测试类，机械适配；编译期全部暴露 |
| 租约续租依赖心跳，若执行长时间无步骤推进（LLM 单步 >5min）租约过期 | 理论上超发 | 单步上限受 LLM 超时/熔断约束（远小于 5min）；租约过期后原持有者 release 幂等无害 |
| 熔断半开探测租约被不计数错误路径占住 | 恢复延迟 ≤120s | 租约自然过期自愈；可配置 |
| 报告流式后 controller 异常半途写出 | 响应不完整 | 现状 String 版本同样存在（异常时 500）；流式版写出前先完成数据装配，步骤数据来自 DB 事务读 |
| 子串门槛 4 字可能漏杀个别真重复 | 去重率略降 | 批内语义去重（G14/v7.1）与步骤指纹判重仍兜底；宁漏勿杀是既定原则 |
| SSE 断连降级轮询后失去逐条用例推送 | 进度粒度变粗 | 终态后列表整体刷新，结果不丢；语义上"进度可见"优先于"进度精细" |

---

## 六、交付物

- [ ] PRD（本文件）
- [ ] 后端技术评审 v7.12
- [ ] 前端技术评审 v7.12
- [ ] 后端实现 + 单测
- [ ] 前端实现（SSE 断连降级）
- [ ] CHANGELOG / README / 风险清单 / 迭代计划更新
- [ ] git commit + push
