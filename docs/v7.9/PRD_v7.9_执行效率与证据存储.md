# PRD v7.9 — 执行效率与证据存储

> 版本：v7.9 ｜ 日期：2026-08-23 ｜ 范围：后端 + 部署配置
> 对应风险清单：E6 / E7 / E9 / E10 / R11

## 1. 背景与目标

v7.0–v7.8 已完成准确率主线（语义稳定、投喂精准、评审闭环）。v7.9 转向执行链路的效率与可靠性收尾：

- **E6**：生效判断的 LLM 调用在"页面已变化"场景纯冗余（LLM 输入与本地指纹比较完全相同），花钱且慢。
- **E7**：大批量执行可把浏览器自动化挤到 HTTP 请求线程（CallerRunsPolicy），接口挂死、批次丢失。
- **E9**：执行/步骤/批次 ID 均取 UUID 前 8 位（32bit），约 7.7 万条记录 50% 碰撞，JPA save 静默覆盖。
- **E10**：复制执行仅 VIEW 权限即可真实操作目标系统（含删除类用例）。
- **R11**：报告截图读取失败静默返回空串，多实例部署下报告无截图且无任何告警。

**目标**：生效判断省 LLM 调用、批量入口限流防挂死、ID 碰撞消除、复制执行权限可收敛、证据丢失可见。

## 2. 需求明细

### 2.1 E6 — 生效判断"先本地后 LLM"（省调用）

**现状**：`ExecutionAgent.askLlmIfEffective` 每次点击步骤都调 LLM，输入为操作前后 URL+标题+文本快照——与本地 `pageChanged` 三指纹比较的输入完全相同。

**需求**：
1. 点击生效判断改为两级：
   - **第一级（本地零成本）**：三指纹（URL/title/textSnippet）任一变化 → 直接判"生效"，**不调 LLM**。
   - **第二级（LLM 终审）**：三指纹完全相同（SPA 局部更新/无变化存疑场景）→ 调 LLM 判断，prompt 明示"页面文本快照无变化"事实。
2. 无 LLM 配置时保持现有 `pageChanged` 兜底不变。
3. 生效判断结果口径不变：effective → passed；不生效 → DOM 兜底逻辑不变。

**验收**：
- 指纹变化场景不发起 LLM 调用（日志可证）。
- 指纹相同场景 LLM 仍被调用且 prompt 含"无变化"事实。
- 既有执行链路行为（DOM 兜底、重复点击保护）不回退。

### 2.2 E7 — 批量入口限流 + 排队超时

**现状**：
- `executeBatch`/`copyExecute` 对 caseIds 数量无限制；execution 池 queue 满后 CallerRunsPolicy 让浏览器自动化跑在 HTTP 线程（单条数分钟）→ 接口挂死。
- `projectExecutionLimiter.acquire` 无超时阻塞（Semaphore.acquire / Redis while+sleep），项目排队可无限等待。

**需求**：
1. **批量入口限流**：`executeBatch` 与 `copyExecute` 单批用例数 > 100 时拒绝，返回业务错误（HTTP 400，提示分批执行）。
2. **排队超时**：项目并发配额等待引入超时上限（默认 30 分钟，可配置 `app.executor.project-acquire-timeout-minutes`）；超时该条执行记 failed，错误信息"项目执行并发排队超时"。
3. 超时配置零值/负值视为禁用超时（保持旧行为），供排障使用。

**验收**：
- 单批 101 条被拒，错误信息含上限值与建议。
- 单批 100 条正常受理（pending 排队不变）。
- 排队超时的执行记录状态为 failed 且错误信息明确；不产生僵尸 running 记录。

### 2.3 E9 — ID 加长防碰撞

**现状**：执行记录 ID、执行步骤 ID、batchId/copyId 均为 UUID 前 8 位十六进制（32bit）。

**需求**：以上 ID 统一加长为 UUID 前 **16 位**十六进制（64bit）。新记录生效；旧 8 位记录不受影响（String 主键无 schema 变更，新旧共存）。

**验收**：新生成的执行记录/步骤/批次 ID 长度为 16（batch-/copy- 前缀 + 16 位）；存量数据可正常查询展示。

### 2.4 E10 — 复制执行权限收敛（配置开关）

**现状**：`copyExecute` 仅校验 VIEW 权限，只读成员可对目标环境执行删除类用例。

**需求**：新增配置 `app.execution.copy-execute-require-operate`（默认 `false` 保持现有 VIEW 口径）：
- `false`：维持现状（VIEW 即可复制执行）。
- `true`：复制执行要求 OPERATE 权限（与普通执行一致）。

**验收**：默认配置下行为不变；开关开启后 VIEW 用户调用复制执行返回 403。

### 2.5 R11 — 证据丢失可见化（短期方案）

**现状**：`ReportService.imageToBase64` 读文件失败静默返回空串——报告截图丢失无任何提示；多实例部署（v6.5+ 高可用）下截图在另一实例本地盘，报告必然缺图且不可知。

**需求**：
1. 报告中区分三种截图状态：**无截图**（路径为空，不渲染）、**正常**（base64 渲染）、**丢失**（路径非空但读取失败 → 渲染告警占位"截图文件缺失（多实例部署需共享 outputs 卷）"）。
2. 部署文档注明：多实例部署需将 `./outputs` 配置为共享卷/NFS，或升级对象存储（长期方案，不在本版范围）。

**验收**：截图存在时正常渲染；路径非空但文件缺失时报告出现明确告警占位；路径为空时不渲染占位。

## 3. 非目标

- 路由亲和（网关层改造）、对象存储接入 —— R11 长期方案，后续版本。
- 批量执行分片调度、执行任务优先级队列 —— E7 深层优化。
- 旧 8 位 ID 数据迁移 —— 不需要（String 主键兼容共存）。

## 4. 影响面

| 项 | 文件 | 类型 |
|---|---|---|
| E6 | ExecutionAgent.java | 后端 |
| E7 | ExecutionService.java、RuntimeStore.java、MemoryRuntimeStore.java、RedisRuntimeStore.java、ProjectExecutionLimiter.java、application.yml | 后端 |
| E9 | ExecutionAgent.java、ExecutionService.java | 后端 |
| E10 | ExecutionService.java、application.yml | 后端 |
| R11 | ReportService.java、docker-compose 注释/README | 后端+部署 |

## 5. 风险与权衡

- **E6**：指纹相同场景仍依赖 LLM（输入无差异信息，LLM 保守判未生效走 DOM 兜底为既有行为）；收益是常见成功路径每步省一次 LLM 调用。
- **E7**：入口限流是用户可感知行为变化（超限被拒）——计划已明示接受；排队超时默认 30 分钟足够长，正常批次不受影响。
- **E10**：默认不改变行为（开关默认 false），部署方按安全需求开启。
- **R11**：短期只做"可见化"，不解决多实例共享存储本身。
