# PRD v8.6.2 — LLM 出参契约化（计划书阶段 2 下半）

> 版本 v8.6.2，一旦确定尽量不要轻易改动。基线 v8.6.1。范围：计划书任务 9.5–9.8，消灭差距 G3。

## 一、背景与痛点

四个 LLM 结构化出参点（用例数组 / PRD 解析 / 状态机 / 评审结果）均无 schema 契约，模型格式漂移（缺字段、类型错、枚举脏值）是共性故障源：解析期才发现、报错笼统、无观测数据判断漂移率。

## 二、范围

| # | 任务 | 内容 |
|---|---|---|
| 9.5 | 校验引擎 | 引入 `com.networknt:json-schema-validator` 1.5.9（1.x 线最终版，draft-07；计划书所写"1.4.x+"满足）；`LlmSchemaValidator` 组件：classpath 加载 schema、编译缓存、返回含路径的错误列表 |
| 9.6 | 契约定义 | `resources/llm-schemas/` 四份 draft-07 schema（test-cases/prd-analysis/state-machine/review-result），`$comment` 标注契约来源 |
| 9.7 | 统一出口校验 + 灰度 | chatJson 新增 schemaName 重载；`llm.schema.mode: observe\|enforce` 默认 observe 仅计数告警；enforce 附缺失字段清单重试一次仍失败抛降级异常；extractJsonObject 改括号配平扫描 |
| 9.8 | 逐 Agent 接入 | 四个出参点全部接入（仅传 schemaName 不改业务逻辑） |

## 三、功能细节

- **灰度语义**：observe=校验失败仅 WARN+计数放行（上线默认，行为零变化）；enforce=chatJson 路径附缺失字段清单重试 1 次→仍失败抛 BusinessException(50002)；数组类出参点（用例/状态机/评审）enforce 失败直接抛给调用方既有重试机制（与"整段非 JSON 上抛重试"同语义，不单独拼二次提示词——偏差记录于技术评审）。
- **接入点与 schema**：TestGeneratorAgent.parseTestCases→test-cases；PrdAgent.analyzeByLlm→prd-analysis；StateMachineAgent.extract→state-machine；TestCaseReviewAgent 评审解析→review-result。
- **枚举口径**：type∈positive/negative/boundary/data、priority∈P0–P3 与 v8.4 白名单一致；review status∈pass/fix/reject。
- **括号配平**：从首个 `{` 起做字符串感知的深度扫描取配平段，说明文字含大括号时不再误取；失败回落旧首尾截取。

## 四、验收标准

1. 校验器单测：合法通过 / 缺必填报错含字段路径 / 枚举违规 / 非法 JSON / 未知 schema 拒绝。
2. 四份 schema 各跑"真实样本通过 + 缺字段失败"双向测试。
3. observe/enforce × 合法/非法矩阵测试；enforce 重试提示词含缺失字段清单。
4. 括号配平对"说明文字含大括号"样本提取正确。
5. 默认 observe 全量回归零影响（441+ 全绿）。

## 五、风险与缓解

| 风险 | 缓解 |
|---|---|
| schema 过严导致 enforce 期误重试 | 默认 observe 观察；切换判据=v8.7 观测 violation 率 <1% 且可解释 |
| 直 new 单测绕过校验器 | 注入为 null 时跳过校验，保持既有单测零改动 |
| 中文别名等可归一值被 enum 拦截 | 归一化(normalizeCaseType)保留在校验之后作为兜底；observe 数据验证误拦率 |

## 六、交付物清单

- [ ] pom 新依赖 ×1；LlmSchemaValidator 新增；schema ×4；LlmService 重载+配平；Agent 接入 ×4
- [ ] 测试：validator/契约双向/chatJson 矩阵/parseTestCases 接入
- [ ] 文档：CHANGELOG / README / 评审文档 / 计划书状态列
