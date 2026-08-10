# PRD v3.2 — 用例生成流式输出（SSE Stream）

**版本**: v3.2
**基线**: v3.1
**日期**: 2026-08-11
**主题**: 将用例生成从"异步轮询 + 终态一次性返回"升级为"SSE 流式推送"，用户每生成一条用例即可实时看到，无需等待全部完成

---

## 1. 背景与痛点

v1.6 引入的进度反馈机制为"后端写 progress 字段 → 前端每 3 秒轮询 project 详情读取"，v3.0/v3.1 沿用。痛点：

1. **无内容反馈**：用户只能看到"正在生成第 2/5 个模块"这类文字进度，看不到任何用例内容，必须等全部生成完成才能在列表看到结果
2. **等待感强**：PRD 驱动模式下 LLM 一次生成全部用例耗时较长（30s~2min），用户面对一个静态 loading 无任何渐进反馈
3. **轮询开销**：前端每 3 秒一次 HTTP 请求，服务端为每次轮询查库 + 序列化整个 project 对象
4. **失败延迟感知**：若生成中途出错，前端要等下一次轮询才能感知

## 2. 范围

### In Scope

- 后端：新增 SSE 端点 `GET /api/projects/{projectId}/testcases/generate-stream`，以 Server-Sent Events 形式推送 `progress`/`case`/`complete`/`error` 四类事件
- 后端：`TestGeneratorAgent` 新增 `CaseCallback` 接口 + `generateStreaming` 重载，每解析出一条用例立即回调
- 后端：`OrchestratorAgent` 新增 `generateStreaming` 方法，将 case 回调透传给 `TestGeneratorAgent`
- 后端：`TestCaseService` 新增 `runGenerateStream(projectId, emitter)` 方法（`@Async`），在 SSE 请求线程外执行生成，通过 emitter 推送事件，结束时落库
- 前端：`api/testcase.js` 新增 `streamGenerate(projectId, callbacks)` 封装 EventSource
- 前端：`TestCaseList.vue` "重新生成"改为流式模式，新增流式生成面板（进度文本 + 已生成计数 + 实时入表）
- 前端：`ProjectDetail.vue` "生成用例"跳转 TestCaseList 并带 `?generate=1` 自动触发流式生成

### Out of Scope

- 不改造 `POST /api/projects/{id}/generate` 旧端点（保留向后兼容，作为非流式回退路径）
- 不改造代码分析的进度反馈（分析阶段耗时较短，暂不需要流式）
- 不引入 WebSocket（SSE 单向推送已满足需求，且实现更轻）
- 不改造 MCP Server 的 LLM 调用为流式（MCP stdio 协议本身不支持流式 token，本次只在 Java 侧按"每条用例"粒度推送）

## 3. 功能详情

### 3.1 SSE 端点契约

`GET /api/projects/{projectId}/testcases/generate-stream`

**响应头**: `Content-Type: text/event-stream; charset=UTF-8`，`Cache-Control: no-cache`，`Connection: keep-alive`

**事件格式**（标准 SSE，每事件两行 `event:` + `data:`，事件间空行分隔）：

| event | data 结构 | 时机 |
|-------|----------|------|
| `progress` | `{"message":"正在解析 PRD..."}` | 各阶段开始时 |
| `case` | `{"testCase":{...TestCaseDTO}}` | 每生成一条用例 |
| `complete` | `{"total":15}` | 全部生成并落库完成 |
| `error` | `{"message":"..."}` | 生成失败 |

**生命周期**：
- 客户端断开 → emitter 触发 `onCompletion`，后端记录日志但不再 send
- 超时（默认 5 分钟）→ emitter 自动 complete，后端捕获 `IllegalStateException` 并停止 send
- 生成完成 → 后端 `emitter.send(complete)` + `emitter.complete()` 关闭流

**并发控制**：进入流式生成前校验 project.status，若已为 `generating` 则推送 `error` 事件并立即关闭，避免重复触发。

### 3.2 后端流式生成链路

```
Controller generateStream()
  ├─ 创建 SseEmitter(timeout=5min)
  ├─ 异步调用 TestCaseService.runGenerateStream(projectId, emitter)
  └─ return emitter（Spring MVC 保持连接）

TestCaseService.runGenerateStream(@Async)
  ├─ 校验 PRD/分析至少一项；设 status=generating
  ├─ OrchestratorAgent.generateStreaming(projectId, progressCb, caseCb)
  │    ├─ PrdAgent.analyze（推送 progress）
  │    ├─ 加载代码/前端上下文（推送 progress）
  │    └─ TestGeneratorAgent.generateStreaming(prd, sm, be, fe, progressCb, caseCb)
  │         ├─ PRD 驱动分支：LLM 一次返回全部 → 逐条解析 → 每条 caseCb.onCase(tc)
  │         └─ 代码驱动分支：按状态机分模块 LLM → 每模块解析后逐条 caseCb.onCase(tc)
  ├─ 去重 + 质量评分（推送 progress）
  ├─ 落库（先删后存）
  ├─ 推送 complete 事件
  └─ 设 status=completed；emitter.complete()
  catch → 推送 error 事件；设 status=failed + errorMessage；emitter.completeWithError()
```

**关键点**：
- `caseCb` 在 `TestGeneratorAgent` 内部于"每条用例解析完成后"立即触发，不等去重
- 落库仍发生在最后（先删后存），因此流式推送的用例 ID 在落库前为临时序号，落库后重新编号；前端 `complete` 事件后需刷新列表获取最终编号
- `progressCb` 复用现有 `ProgressCallback`，推送 progress 事件

### 3.3 前端流式消费

**`api/testcase.js`**：

```js
export function streamGenerate(projectId, { onProgress, onCase, onComplete, onError }) {
  const es = new EventSource(`/api/projects/${projectId}/testcases/generate-stream`)
  es.addEventListener('progress', e => onProgress?.(JSON.parse(e.data).message))
  es.addEventListener('case', e => onCase?.(JSON.parse(e.data).testCase))
  es.addEventListener('complete', e => { onComplete?.(JSON.parse(e.data).total); es.close() })
  es.addEventListener('error', e => {
    // SSE 原生 error 事件：data 可能为空（网络断开）或携带后端 error 事件 data
    let msg = '生成连接异常'
    if (e.data) { try { msg = JSON.parse(e.data).message } catch {} }
    onError?.(msg); es.close()
  })
  return es
}
```

**`TestCaseList.vue` 流式面板**：

- 新增 `streaming` ref，true 时显示流式生成面板：
  - 进度文本（`streamProgress`）
  - 已生成计数（`streamedCases.length`）
  - 流式用例列表：将 `onCase` 收到的用例 unshift 到 `streamedCases`，同时在表格 `displayTestCases` 顶部插入（覆盖分页，流式期间隐藏分页器）
- "重新生成"按钮 click → 调 `streamGenerate` → 进入流式模式
- `onComplete` → 退出流式模式 → 刷新列表（拿最终编号 + 覆盖率）
- `onError` → 退出流式模式 → 显示错误 alert

**`ProjectDetail.vue`**：

- "生成用例"按钮 click → `router.push(`/projects/${projectId}/testcases?generate=1`)`
- TestCaseList `onMounted` 检测 `route.query.generate === '1'` 自动触发流式生成

### 3.4 向后兼容

- 保留 `POST /api/projects/{id}/generate` 端点与 `TestCaseService.runGenerate` `@Async` 方法不变，作为非流式回退（如 SSE 不可用场景）
- 保留 `projectStore.startPolling` 轮询机制不变，分析阶段仍用轮询
- 流式生成与旧端点共用 `generating` 状态机，互斥（任一在 generating 时另一端点拒绝）

## 4. 验收标准

- [ ] 点击"生成用例"/"重新生成"，1~3 秒内看到首条进度事件
- [ ] PRD 驱动模式下，用例解析完成后逐条出现在表格顶部（非一次性出现）
- [ ] 代码驱动模式下，分模块生成时每模块完成即推送该模块所有用例
- [ ] 生成完成显示"已生成 N 条"并刷新列表（拿到最终编号）
- [ ] 生成失败显示具体错误信息
- [ ] 中途关闭页面/刷新，后端不崩溃（emitter 触发 onCompletion，日志记录）
- [ ] 后端 `mvn compile` + 前端 `npm run build` 通过
- [ ] 旧端点 `POST /generate` 仍可用（curl 验证 202 返回）

## 5. 风险与缓解

| 风险 | 缓解 |
|------|------|
| LLM 一次返回全部 JSON，无法逐条推送 | 在 Java 侧解析 JSON 数组后逐条回调，模拟流式体验（首条延迟 ≈ LLM 总耗时，但解析后逐条渲染比一次性渲染更顺滑） |
| 客户端断开后后端继续 send 抛异常 | `emitter.onCompletion` 设置标志位，send 前检查；catch `IllegalStateException` 忽略 |
| SSE 连接被代理/网关超时切断（默认 nginx 60s） | 后端每 15 秒推送一次 `progress` 心跳（仅当无 case 事件时）保持连接；超时设 5 分钟 |
| 流式期间用户重复点击 | 按钮置 `loading` + `streaming` ref 互斥；后端 status=generating 兜底拒绝 |
| 落库前用例 ID 为临时序号 | 前端流式期间显示"生成中"标识，`complete` 后刷新列表拿最终编号 |

## 6. 交付物清单

- [ ] `docs/v3.2/PRD_v3.2_用例生成流式输出.md`
- [ ] `docs/v3.2/后端技术评审_v3.2.md`
- [ ] `docs/v3.2/前端技术评审_v3.2.md`
- [ ] 后端 `TestGeneratorAgent` 新增 CaseCallback + generateStreaming
- [ ] 后端 `OrchestratorAgent` 新增 generateStreaming
- [ ] 后端 `TestCaseService` 新增 runGenerateStream
- [ ] 后端 `ProjectController` 新增 SSE 端点
- [ ] 前端 `api/testcase.js` 新增 streamGenerate
- [ ] 前端 `TestCaseList.vue` 流式生成面板
- [ ] 前端 `ProjectDetail.vue` 跳转 + 自动触发
- [ ] CHANGELOG + README 更新
