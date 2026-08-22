# PRD v7.3 — LLM 组件稳定与生成质量约束

> 版本：v7.3 ｜ 日期：2026-08-23 ｜ 基线：v7.2（60a21fc）
> 对应《代码审查风险清单》：L1 / L2 / L5 / L8 / G20(层1+2)

## 1. 背景与痛点

v7.0–v7.2 完成执行与度量诚实化后，剩余的"说谎"集中在 **LLM 组件层**与**生成质量约束层**：

| 编号 | 痛点 | 用户可感知后果 |
|---|---|---|
| L1 | 流式取消是全局单例（`activeStream`/`streamCancelled`） | 两个项目并发生成时，A 取消会误杀 B 的流（B 抛"用户取消"但其实没人取消它） |
| L2 | 熔断器全局共享 + 4xx 配置错误也计入 | 多模态（图片定位）故障连坐全部文本生成；API Key 填错 5 次后全系统 LLM 503 半分钟 |
| L5 | SPA 生效判断退化为 URL 比较 | 单页应用 URL 不变 → 几乎所有点击判"未生效" → DOM 兜底再点一次 → **重复下单/重复提交** |
| L8 | 流式 JSON 截断静默丢最后一条 | 长批次生成尾部用例丢失，无日志无提示，用户只看到"比预期少几条" |
| G20 | expected 写"返回 401""errorMsg"等 API 语言 | 用例预期不是用户可感知现象，后续断言（L6）无从落地 |

## 2. 范围

### In Scope
1. **L1 流取消并发修复**：取消信号从全局字段改为 per-request 传递（`BooleanSupplier`），删除全局单流假设。
2. **L2 熔断通道隔离**：熔断器按通道（text / multimodal）拆分状态；不可重试错误（4xx 配置类）不计入熔断计数。
3. **L5 SPA 生效判断（最小版）**：无 LLM 时用 URL+title+textSnippet 三指纹比较（零新增调用）；有 LLM 时把前后 textSnippet 注入 prompt 作为证据；点击后补短暂等待覆盖异步渲染。
4. **L8 截断告警与抢救**：流式解析器结束时检测未闭合对象 → warning 日志 + GenerationReport 暴露 `streamTruncated`；尝试局部补全闭合抢救最后一条；`maxTokens` 硬编码改配置。
5. **G20 层1 prompt 约束**：system prompt 增加预期结果语言规范（用户可感知现象，禁 HTTP 码/字段名/变量名）；修正 few-shot 示例中的 API 语言示范。
6. **G20 层2 本地 lint**：正则扫描 expectedResults / UI 类步骤 expected，打 `uiLanguageViolations` 标记入 hints（零 LLM 成本，前端展示后续版本）。

### Out of Scope
- G20 层3（错误→文案对照表，随 v7.6/v7.7 分析器迭代）
- L6 expected 断言闭环（v7.6）
- E6 生效判断喂完整证据+省调用（v7.9）
- 前端展示 lint 标记（数据本版已埋，UI 后续版本）
- 多模态 `multimodal_element_locate` 的熔断（走 McpBridgeService，不经过 LlmService，本版不动）

## 3. 需求详述

### 3.1 L1：per-request 取消上下文
- `LlmService.chatStreaming*` 系列方法增加 `BooleanSupplier cancelSignal` 参数；流式订阅 `doOnNext` 与等待循环只检查该信号。
- 删除 `activeStream` / `streamCancelled` 全局字段与 `cancelStreaming()` 方法。
- 调用方 `TestGeneratorAgent` 把已有的 `CancellationSignal cancelled`（每项目独立 RuntimeFlag）传入；`TestCaseService.cancelGeneration` 不再调用全局取消。

### 3.2 L2：按通道熔断 + 不可重试不计入
- `LlmCircuitBreaker` 内部按 channel（`text` / `multimodal`）维护独立失败计数与开启时间。
- 文本链路（chat/chatStreaming/chatJson）与多模态链路（chatWithImage）互不连坐。
- 失败仅在 `LlmRetryPolicy.isRetryable(lastException)` 为 true 时计入熔断（4xx 配置类错误直接失败不计数，避免 Key 填错打满熔断）。

### 3.3 L5：SPA 生效判断最小版
- 无 LLM 兜底：URL、title、textSnippet（body 文本前 500 字符快照）任一变化即生效。
- 有 LLM：userPrompt 注入操作前后 textSnippet，让 LLM 有证据判断（此前只有 URL+标题，SPA 场景 LLM 也只能瞎猜）。
- 点击后补 ~800ms 等待再取 statusAfter，覆盖 SPA 异步渲染窗口。

### 3.4 L8：截断告警与抢救
- 流式解析器新增 `finish()`：流结束时若 braceDepth 未归零（存在未闭合对象）→ 置截断标志 + 尝试截到最后一个安全逗号、补齐闭合括号后重试解析（抢救部分字段完整的最后一条）。
- `GenerationReport` 新增 `streamTruncated`（截断发生）与 `truncatedRecovered`（抢救成功数），随 complete 事件/任务系统暴露；同时打 warning 日志。
- `maxTokens` 由 16384 硬编码改为 `llm.max-tokens` 配置（默认不变）。

### 3.5 G20 层1：prompt 硬约束
- 状态机驱动与 PRD 驱动两套 system prompt 均加"预期结果语言规范"段：
  - expected/expectedResults 描述页面可感知现象（可见文案、toast、跳转、元素出现/消失/禁用）；
  - 禁止 HTTP 状态码、后端字段名/变量名、响应体键名；
  - 仅 api_call 步骤允许描述接口行为，但用例最终断言必须回到页面现象。
- few-shot 示例 2/3 修正：expectedResults 从"接口返回201/400"改为页面现象 + 接口语义并存；UI 断言步骤禁止机器常量（`status=PENDING_PAYMENT`）。

### 3.6 G20 层2：本地 lint
- 新增 `UiLanguageLinter`：扫描每条用例的 expectedResults 与 structuredSteps 中 ui_action/state_assert 步骤的 expected。
- 命中规则（保守，宁可漏报不刷屏）：
  1. HTTP 码模式：`(返回|响应|状态码|HTTP)[^0-9]{0,4}[45]\d{2}` 或 `[45]\d{2}\s*(错误|状态码)`；
  2. 机器常量：全大写下划线词（`PENDING_PAYMENT`、`ERROR_CODE`，≥2 段）；
  3. 后端字段赋值：`errorMsg=`/`errMsg=`/`status=`/`code=` 赋值形态。
- 结果写入 `executionHints.uiLanguageViolations`（字符串数组，人可读），只标记不删改数据。

## 4. 验收标准

1. 两个并发生成流，取消其中一个不影响另一个（代码审查级验证：取消信号仅来自调用方传入的 flag）。
2. 多模态连续失败 5 次后，文本链路 `allowRequest()` 仍为 true；4xx 不可重试错误连续失败不触发熔断。
3. 无 LLM 配置时，SPA 页面点击后 textSnippet 变化 → 判定生效（不触发 DOM 兜底重复点击）。
4. 构造截断的流式 JSON（最后一条半截）→ finish() 抢救出部分字段完整的一条 + `streamTruncated=true` + warning 日志。
5. few-shot 与 system prompt 含语言规范段；lint 对"接口返回400""status=PENDING_PAYMENT""errorMsg=参数缺失"命中，对"页面提示金额非法""跳转到首页"不误报。
6. `mvn clean test` 全绿；前端无改动无需构建（构建仅作回归确认）。

## 5. 风险与缓解

| 风险 | 缓解 |
|---|---|
| 取消信号传递漏改导致取消失效 | 全链路仅一个流式调用方（TestGeneratorAgent）；TestCaseService.cancelGeneration 仍有 flag.cancel() 兜底（轮询检查点仍生效） |
| 截断补全解析出畸形用例 | 补全后 readTree 失败即放弃（保持原丢弃行为），仅多打日志与标记 |
| lint 误报干扰用户 | 标记只进 hints 不进评审 reject/fix 决策，不删不改数据；规则保守设计 |
| textSnippet 前 500 字符不含变化区域 | 最小版接受；比纯 URL 比较已大幅减少误判（E6 完整版后续补截图对比） |
| maxTokens 配置化后用户配错 | 默认值与原硬编码一致，行为不变 |

## 6. 交付物清单

- [x] PRD（本文档）
- [x] 后端技术评审
- [x] LlmService / LlmCircuitBreaker / TestGeneratorAgent / TestCaseService / ExecutionAgent / TestCaseReviewAgent 修改
- [x] 新增 UiLanguageLinter
- [x] 单测：熔断通道隔离、截断抢救、lint 规则、生效判断指纹
- [x] CHANGELOG / README / 风险清单状态更新
