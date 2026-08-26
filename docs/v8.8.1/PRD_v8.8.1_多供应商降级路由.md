# PRD v8.8.1 — 多供应商双通道 + 降级路由

> 版本 v8.8.1，一旦确定尽量不要轻易改动。基线 v8.7.2。范围：计划书任务 10.1–10.3（v8.8 拆分版上半），消灭差距 G5。

## 一、背景与痛点

LLM 供应商单点：唯一通道熔断即全平台不可用（生成/分析/评审全瘫）。现有双通道熔断器只隔离 text/multimodal 功能面，没有第二供应商可切；embedding 与 chat 共享配额与故障域。

## 二、范围

| # | 任务 | 内容 |
|---|---|---|
| 10.1 | 多模型注册 | llm.models.fallback.* 注册降级供应商（现有 llm.* 即 primary 兼容）；降级 ChatModel 程序化构建 |
| 10.2 | 降级路由 | 主通道失败/熔断自动切 fallback；GenerationReport 与 SSE complete 标注 degradedProvider；双败抛 50300；预算参数 per-provider |
| 10.3 | embedding 独立熔断与降级 | channel=embedding 独立计数；llm.models.fallback.embedding-* 独立降级端点 |

## 三、功能细节

- **启用条件**：fallback 的 base-url/api-key/model 三键齐备才启用；任一缺失安全禁用，行为与单通道完全一致。
- **路由边界**：chat 同步调用在主通道重试耗尽或熔断打开（BusinessException 出口）后切换 fallback 再走一轮独立重试；流式仅在主通道整体失败后切换（切换前触发 retryResetHook 清已推送草稿），轮内已推送内容的失败仍由主通道既有重试承担——避免跨通道重复推送。
- **双败**：fallback 也失败 → BusinessException(50300, "主/降级 LLM 通道均不可用")。
- **标注链路**：ThreadLocal degradedProvider → TestGeneratorAgent 轮次出口消费写 GenerationReport.degradedProvider（toMap 条件输出）→ TestCaseService complete 事件条件透出。
- **熔断隔离**：channel 键按 provider 派生（text / text:fallback），互不连坐；4xx 不计熔断口径两通道一致。
- **embedding**：embed() 全程走 channel=embedding 独立熔断；主模型异常/熔断打开时切 fallback embedding 端点，未配置维持空向量降级语义（结构判重兜底）。

## 四、验收标准

1. 未配置 fallback：全部行为与 v8.7.2 一致（全量回归证明）。
2. 主败→fallback 成功：返回结果 + degradedProvider="fallback"；双败→50300（单测覆盖）。
3. embedding 熔断不拖垮 chat 链路（通道隔离单测覆盖）。

## 五、风险与缓解

| 风险 | 缓解 |
|---|---|
| Spring AI 程序化构建 API 不匹配 | OpenAiApi/OpenAiChatModel builder 走 1.0.0 GA 形态，容器编译验证 |
| 降级模型上下文小导致 prompt 超限 | fallback max-prompt-chars 独立配置（默认 200k）；boundPrompt per-provider 生效留后续接入点 |
| 降级端点密钥误配静默劣化 | llm_fallback_used_total{channel} 计数 + 构建期 INFO 日志 |

## 六、交付物清单

LlmProviders 新增；LlmService 路由改造；GenerationReport/TestCaseService 标注透出；EmbeddingService 熔断+降级；yml/env 七键；测试 ×9。
