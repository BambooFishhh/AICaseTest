# PRD v3.3 — 流式生成取消与落库保护

> 版本：v3.3 | 主题：流式生成取消 + 落库保护
> 基线：v3.2（用例生成流式输出 SSE Stream）
> 日期：2026-08-11

## 一、背景与痛点

### 1.1 现状

v3.2 实现了用例生成的 SSE 流式输出，用户每生成一条用例即可实时看到。但流式生成过程**不可中断**：

- 前端关闭页面 / 组件卸载时仅 `EventSource.close()` 关闭客户端连接
- 后端 `SseEmitter.onCompletion` 置 `clientGone=true`，仅让后续 `send` 静默跳过
- **后端生成线程继续执行**：LLM 调用（30s~2min）照常阻塞、用例解析照常进行
- **落库逻辑照常执行**：`deleteAll` + `save` 先删后存，覆盖旧用例

### 1.2 痛点

| 场景 | 痛点 | 影响 |
|------|------|------|
| 用户误触生成后想取消 | 无取消按钮，必须等待 LLM 跑完 | 浪费 LLM token + 时间 |
| 用户关闭页面/切换路由 | 后端继续跑完 LLM 并先删后存 | **数据丢失风险**：旧用例（含人工修改）被覆盖 |
| LLM 长耗时（2min+） | 用户无法中途放弃 | 体验差，只能干等 |
| 网络抖动断开 | 后端不知道用户已走，继续生成 | 浪费资源 |

### 1.3 v3.2 PRD 遗留

v3.2 PRD 第 5 节"风险与缓解"仅解决了"send 抛异常"的表层问题（`clientGone` 静默跳过），未解决底层的"生成不可中断 + 落库覆盖"问题。v3.3 补全此缺口。

## 二、范围

### In Scope（本期做）

1. **后端取消机制**：`ConcurrentHashMap<projectId, AtomicBoolean>` 取消标志注册表
2. **生成检查点**：在 LLM 调用前、状态机循环迭代前、落库前检查取消标志，抛 `GenerationCancelledException`
3. **落库保护**：取消时跳过 `deleteAll` + `save`，保留旧用例
4. **客户端断开 → 取消**：`emitter.onCompletion/onTimeout/onError` 触发取消标志（不只跳过 send）
5. **取消端点**：`POST /api/projects/{projectId}/testcases/generate-cancel`
6. **前端取消按钮**：流式生成期间显示"取消生成"按钮，调用取消端点 + 关闭 EventSource
7. **SSE cancelled 事件**：取消后推送 `cancelled` 事件，前端区分"取消"与"失败"

### Out of Scope（本期不做）

- LLM HTTP 调用本身的 abort（OkHttp `Call.cancel()`）— 结构改动大，留待后续
- 生成参数可配置（temperature/用例数量/类型分布）— 留待 v3.4
- 真·token 级流式（LlmService.chatStream）— 留待 v4.0
- 取消后部分用例的保存（取消即丢弃本次全部生成结果）

## 三、功能详情

### 3.1 取消标志注册表

`TestCaseService` 新增 `ConcurrentHashMap<String, AtomicBoolean> cancellationFlags`：

- **注册**：`runGenerateStream` 方法开头创建 `AtomicBoolean cancelled`，存入 map（key=projectId）
- **触发**：cancel 端点 或 emitter 断开回调 置 `cancelled=true`
- **清理**：`runGenerateStream` finally 块移除 map 条目

### 3.2 生成检查点

取消标志透传链路：`TestCaseService.runGenerateStream` → `OrchestratorAgent.generateStreaming` → `TestGeneratorAgent.generateStreaming` → `generateByLlmWithPrd` / `generateCodeDrivenCases` → `generateByLlmForStateMachine`

检查点位置（`checkCancelled(cancelled)` → 抛 `GenerationCancelledException`）：

| 位置 | 文件 | 说明 |
|------|------|------|
| PRD 驱动 LLM 调用前 | TestGeneratorAgent.generateByLlmWithPrd | 单次 LLM 调用前 |
| 代码驱动状态机循环每次迭代前 | TestGeneratorAgent.generateCodeDrivenCases | 跳过后续模块 |
| 分模块 LLM 调用前 | TestGeneratorAgent.generateByLlmForStateMachine | 每模块 LLM 调用前 |
| 落库前 | TestCaseService.runGenerateStream | deleteAll 前最终检查 |

### 3.3 落库保护

`runGenerateStream` catch `GenerationCancelledException`：
- **跳过** `testCaseRepository.deleteAll(...)` + `save` 循环 — 保留旧用例
- **恢复项目状态**：有旧用例 → `completed`；无旧用例 → `created`
- **推送 cancelled 事件**：`sendSseEvent(emitter, clientGone, "cancelled", ...)`（clientGone 时自动跳过）
- **完成 emitter**：`safeSseComplete`

### 3.4 客户端断开 → 取消

`emitter.onCompletion/onTimeout/onError` 回调同时置 `clientGone=true` 和 `cancelled=true`：
- `clientGone` 控制 SSE send 行为（跳过发送）
- `cancelled` 控制生成行为（停止生成 + 跳过落库）

### 3.5 取消端点

```
POST /api/projects/{projectId}/testcases/generate-cancel
→ { "cancelled": true/false }
```

- 调用 `testCaseService.cancelGeneration(projectId)` 置 `cancelled=true`
- 无生成任务时返回 `cancelled: false`

### 3.6 前端取消按钮

- 流式生成期间（`streaming=true`），在流式进度 alert 内显示"取消生成"按钮
- 点击后：调用 `cancelGenerate(projectId)` + `streamEs.close()`
- `streamGenerate` 新增 `cancelled` 事件监听
- 取消后：`streaming=false`，提示"生成已取消，旧用例已保留"，刷新列表

## 四、验收标准

| # | 验收点 | 验证方式 |
|---|--------|----------|
| 1 | 流式生成期间显示"取消生成"按钮 | UI 检查 |
| 2 | 点击取消 → 生成在下一个检查点停止 | 日志 + 旧用例保留 |
| 3 | 取消后旧用例不被覆盖（落库保护） | 取消后列表仍显示旧用例 |
| 4 | 取消后项目状态恢复（非 generating/failed） | 项目详情状态检查 |
| 5 | 关闭页面 → 后端停止生成 + 旧用例保留 | 关闭页面后查 DB |
| 6 | 无生成任务时调 cancel 端点返回 cancelled:false | API 调用 |
| 7 | 正常生成完成不受影响 | 完整生成一遍验证 |
| 8 | 前端区分"取消"（黄色提示）与"失败"（红色提示） | UI 检查 |
| 9 | 后端编译通过 | mvn compile |
| 10 | 前端构建通过 | npm run build |

## 五、风险与缓解

| 风险 | 缓解 |
|------|------|
| LLM 同步调用无法中途 abort | 在调用前检查取消标志；LLM 返回后若已取消则丢弃结果不落库 |
| 网络抖动误触发取消 | 可接受——用户重新点击"重新生成"即可；优先保障数据安全 |
| 取消标志清理不及时导致内存泄漏 | `runGenerateStream` finally 块确保移除 map 条目 |
| 非流式 runGenerate 无取消能力 | 本期仅支持流式取消；非流式保持原行为（向后兼容） |

## 六、交付物清单

- [ ] 后端：`GenerationCancelledException` 异常类
- [ ] 后端：`TestCaseService` 取消注册表 + `cancelGeneration` + `runGenerateStream` 取消逻辑
- [ ] 后端：`OrchestratorAgent.generateStreaming` 透传 cancelled
- [ ] 后端：`TestGeneratorAgent` 检查点 + 方法签名
- [ ] 后端：`ProjectController` cancel 端点
- [ ] 前端：`api/testcase.js` `cancelGenerate` + `cancelled` 事件
- [ ] 前端：`TestCaseList.vue` 取消按钮 + 状态处理
- [ ] 文档：CHANGELOG + README 更新
