# PRD v8.9.8 — litemall 实测驱动 12.11~12.14 修复 + 临时移动适配

> 版本 v8.9.8，一旦确定尽量不要轻易改动。基线 v8.9.6。范围：计划书「阶段 6」实测结论对应的 12.11–12.14 四张修复卡 + litemall 全链路实测所需的临时适配。含前端（SSE 事件消费）与后端/工程变更。

## 一、背景与痛点

- **litemall 全链路首测**：用真实手机端商城 litemall（仅商城无管理后台）+ mimo-v2.5/qwen-embedding 打通「导入→范围→PRD→分析→生成→执行」，暴露 4 个真实缺陷（见 §9.6）：
  1. **12.9 回测 large 接口覆盖=0**（口径假阳性）——live 未注入真实接口清单。
  2. **12.8 第 5 项**：SSE 票据跨实例互通 ✅，但生成流**实例绑定 + 断连即取消**，B 实例重连无续传。
  3. **12.7 SSE 50 并发**：全部静默排队 15s 无首事件（生成池 max6×LLM 流 6 饱和）。
  4. **12.14 导航缺口**：UI 用例缺导航首步，执行时停留首页（TC-491/TC-493 全 failed）。
- **临时适配**：litemall 是手机端 UI（需 iPhone 14 视口）且登录为 localStorage token（非 cookie）。

## 二、范围与验收

| # | 内容 | 验收 |
|---|---|---|
| 12.13 | live 回测口径修正：expected.json 接口注入 BackendResult + requirementIdMap 召回 + 闸门分级 | 复测**接口覆盖 0.45→1.0**，结构 1.0 |
| 12.14 | UI 导航缺口：执行器导航分支 + frontendRoutes 注入 + prompt 导航首步 + 存量兜底 | 重生成用例首步导航执行通过（st1 navigate passed） |
| 12.11 | SSE 断连宽限 + 跨实例回放：断连不取消（宽限期）+ 事件持久化 + replay 端点 | 断连照常跑完；`generate-stream-replay` 回放已产生事件 |
| 12.12 | SSE 排队闸门 + 首事件：队列预检 503 + 即时 `started`/`queued` 事件 + 前端监听 | 超限快速 503；首事件即时 |
| 临时 | 浏览器移动模拟（`devices['iPhone 14']`）+ `browser_set_storage` 登录态注入 | 截图 1170×1992（iPhone14 3x）；localStorage 注入生效 |

## 三、功能细节

### 12.13 live 评测口径
- `EvalRunner.liveEval`：从数据集 `expected.json` 的 endpoints 构造 `BackendResult` 注入 `genAgent.generate`（与真实链路同形）；召回改 `requirementIdMap` id 匹配 + 标题包含双口径；闸门分级（`setMaxGeneratedCases`：large 30，其余 15，`-Deval.largeGate=` 覆盖）。

### 12.14 UI 导航
- **执行器**（`ExecutionAgent`）：识别 `ui_action` 且 target 为路由形态（`^/[\w:{}$-]`）或 `uiSelector.type=route` → 走 `browserNavigate`（baseUrl 透传 + hash 路由兜底 + URL 校验），不进点击流水线。
- **生成**（`TestGeneratorAgent`）：prompt 硬规则"UI 用例第一步必须是导航步骤"+ 注入 `frontendRoutes`（真实路由唯一来源）。
- **存量兜底**（`ExecutionService.buildStepNodes`）：含 ui_action 且首步非导航时，按标题/模块匹配前端路由前置导航，无匹配不硬造。

### 12.11 SSE 断连宽限 + 跨实例回放
- **P0**：`onCompletion/onTimeout/onError` 不再立即 `cancel`，改进宽限期（`app.sse.reconnect-grace-seconds:90`）生成照常跑完，期满无重连才取消。
- **P1**：生成期每条用例 `recordGenerationEvent(taskId,"case",...)` 持久化到 `agent_task_events`；新增 `GET /testcases/generate-stream-replay` 端点，非属主实例重连时按 `latestGenerationTask` + `timeline` 回放已产生 `case` 事件 + 提示"任务仍在运行"。

### 12.12 SSE 排队闸门 + 首事件
- 生成入口预检：`generationExecutor` 队列 ≥ `app.generation.stream-max-queued:8` 直接 503"生成繁忙（当前排队 N）"，不静默排队。
- `runGenerateStream` 即时推 `started` 首事件；前端 `testcase.js` 监听 `started`/`queued` 映射进度文案。

### 临时适配（可回退）
- Playwright MCP `browser_launch` 加 `device` 参数（`devices['iPhone 14']`）；后端 `app.execution.browser-device` 可配，默认空=桌面。
- Playwright MCP 新增 `browser_set_storage`；后端 `injectStorage` 导航后注入项目 `executionStorage`（localStorage token 型登录态）。

## 四、验收标准
1. 后端编译 + 全量回归绿（491 tests，1 条计时类 flaky 与改动无关）；前端 Vitest 10/10。
2. 12.13 复测覆盖 0.45→1.0；12.14 导航 st1 passed；12.11 replay 端点探活通过；12.12 队列闸门生效。

## 五、交付物清单
backend：`skill/PlaywrightRecordSkill`、`agent/ExecutionAgent`、`agent/TestGeneratorAgent`、`service/ExecutionService`、`service/TestCaseService`、`service/AgentTaskService`、`controller/ProjectController`、`eval/EvalRunner`、`config/application.yml`；frontend：`src/api/testcase.js`；playwright-mcp-server：`index.js`；docs：`docs/长期迭代计划书.md`、`docs/CHANGELOG.md`、`docs/迭代历程.md`、`docs/v8.9.8/*`。