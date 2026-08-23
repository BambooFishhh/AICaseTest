# PRD v7.6：状态机与断言闭环

> 版本：v7.6（v7.x 系列第 7 版）
> 对应风险清单条目：A17 / L6 / E5 / G20层3
> 前置：v7.5（缓存与可复现基线）已完成

## 1. 背景与问题

v7.0–v7.5 完成了"诚实化 + 缓存基线"，但两条核心闭环仍未建立：

1. **状态机转换无 ground truth（A17）**：状态（枚举值）是确定的，但转换关系完全由 LLM 猜测——编造的 `CREATED→CANCELLED` 只要 code 合法就入库。"状态转换覆盖率"这一核心卖点建立在不验证的猜测之上。
2. **expected 从未被验证（L6）**：生成侧有 expected 字段，执行侧无断言机制。"用例通过"≠"预期结果成立"，执行结果可信度被系统性高估。
3. **Agent 模式把验证步骤当点击执行（E5）**：state_assert/api_call 掉进"找元素→截图→定位→点击"流水线，验证步骤可能随机点中页面元素——描述撞上删除按钮即生产事故。
4. **错误码→用户文案无对照（G20层3）**：expected 里的"返回 401"“提示 errorMsg"不是用户可感知现象；前端 ElMessage.error 文案与后端异常消息从未被采集，LLM 无对照表可翻译。

## 2. 需求目标

### 2.1 A17 状态机转换 ground truth 校验（规则层，零 LLM 成本）

**FR-1**：SpringAnalyzer 规则层扫描后端源码中状态字段的赋值点：
- `setStatus(X)` / `setStatus(EnumClass.X)` 方法调用
- `status = X` 直接赋值
- 从赋值方法体上下文提取"转换来源→目标"证据：方法内 `getStatus() == Y` / `Y.equals(getStatus())` / `status == Y` 条件判断作为 from；无条件判断时 from 标记为 `*`（任意状态可达）

**FR-2**：证据随分析结果落库：BackendResult 新增 `stateTransitions` 字段（`[{field, from, to, method, file}]`）。

**FR-3**：StateMachineAgent 校验 LLM transitions：
- 与证据匹配（from→to 相等，或证据 from=`*` 且 to 相等，忽略大小写与枚举前缀）→ `verified: true`
- 无证据匹配 → `unverified: true`，状态机 confidence 降为 0.3×原值与 0.4 的较小者
- 保留 transition 不删除（证据扫描覆盖面有限，只降信任度，不误杀）

### 2.2 L6 expected 断言闭环

**FR-4**：断言逻辑提取为共享工具类 `ExecutionAssert`（程序化/Agent 两模式共用）。

**FR-5**：断言分层（按可验证性从强到弱）：
1. URL/标题语义（v7.0 E4 现有能力）：expected 含 URL/标题触发词时与 pageState.url/title 比较
2. **DOM 文本断言（新增）**：expected 中的关键词（中文短语 ≥2 字符、英文标识符 ≥3 字符）在 pageState.textSnippet（页面 body 文本快照）中做包含比较；全部命中 → passed，任一未命中 → failed
3. 无触发词且提取不到关键词 → skipped（诚实标记未验证）

**FR-6**：断言结果落 ExecutionStep：verdict 进 result，期望/实际差异进 error，textSnippet 截断摘要进 coordinates。

### 2.3 E5 Agent 模式分类型处理

**FR-7**：ExecutionAgent.executeStep 按步骤 type 分流：
- `state_assert` → getPageStatus + ExecutionAssert 断言（与程序化模式一致），**不进**"找元素→截图→定位→点击"流水线
- `api_call` → 明确 skipped，错误信息"Agent 模式暂不支持 API 调用步骤"
- `input` / `ui_action`（默认）→ 现有 agentic loop 不变

### 2.4 G20层3 错误→文案对照表

**FR-8**：VueAnalyzer 提取前端用户反馈文案：`ElMessage.error/success/warning("...")`、`Message.error(...)`、`$message.error(...)` 调用的字符串字面量 → FrontendResult 新增 `userFeedbackTexts` 字段（`[{type, text, file}]`，去重，上限 100 条）。

**FR-9**：SpringAnalyzer 提取后端异常用户消息：`throw new XxxException("中文消息")` 的 message 字面量 → BackendResult 新增 `errorMessages` 字段（`[{exception, message, file}]`，去重，上限 100 条）。

**FR-10**：生成上下文注入对照表：TestGeneratorAgent 构建 prompt 时合并两侧文案为 `userFeedbackTexts`（前端文案 + 后端异常消息），并在 system prompt 中说明"expected 必须使用页面实际提示文案（见 userFeedbackTexts 对照表），禁 HTTP 码/字段名"。

## 3. 非目标

- 不做证据扫描的分批 LLM 二次确认（A17 只做规则层，零 LLM 成本）
- 不实现 api_call 步骤的真正执行（与程序化模式一致保持 skipped，E9 后续版本处理）
- 不做存量状态机数据的 unverified 回溯标记（下次分析自动生效）
- 不改前端界面（unverified/verified 字段暂只在 JSON 数据中，前端展示随 v7.8 评审闭环）

## 4. 验收标准

1. 含 `setStatus` 模式的测试项目分析后，BackendResult.stateTransitions 有证据记录
2. LLM transition 与证据匹配时带 `verified: true`；编造转换带 `unverified: true` 且状态机 confidence 降低
3. 程序化模式 state_assert 的 DOM 文本断言生效：页面文本含预期关键词 → passed；不含 → failed
4. Agent 模式 state_assert 步骤不再触发元素定位/点击调用（无 multimodalElementLocate 调用）
5. Agent 模式 api_call 步骤 skipped 且错误信息明确
6. Vue 项目含 ElMessage.error 的分析结果有 userFeedbackTexts；Spring 项目含异常消息的有 errorMessages
7. 生成 prompt 上下文包含 userFeedbackTexts 对照表
8. `mvn compile` 与既有测试全部通过，新增单测覆盖以上逻辑

## 5. 影响范围

- 后端：SpringAnalyzer、VueAnalyzer、StateMachineAgent、ExecutionAgent、ExecutionService、TestGeneratorAgent、BackendResult、FrontendResult、新增 ExecutionAssert
- 前端：无代码变更（回归构建）
- 数据库：无 schema 变更（transitions JSON 内加字段；新分析字段存 code_analysis JSON）
