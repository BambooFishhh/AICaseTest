
## ragContexts / ragFailures（v6.4 补充）
- ragContexts：检索到的相关需求/上下文切片，作为 PRD 之外的补充约束
- ragFailures：历史执行失败经验；生成时避免重复失败路径，必要时增加对应校验与断言

## 代码信息用于补充（不作为用例来源，只增强可执行性）
- endpoints：用例 structuredSteps 的 target 用真实接口路径（如 POST /api/order/create）
- stateMachines：用例的 stateMachineRef 引用真实状态流转
- businessRules：补充为前置条件或异常场景
- frontendForms：testData 填入真实表单字段名和校验规则（required/min/max）
- frontendSelectors：structuredSteps 的 ui_action 类型步骤可附 uiSelector（{type, value}）
- frontendPageFlows：生成页面跳转验证用例（from→to，验证导航需求）
- frontendComponentStates：生成 UI 交互用例（弹窗打开/关闭、分步流程）

## structuredSteps / testData / executionHints 要求（必须严格遵守）
- structuredSteps 必须是非空数组，按真实操作顺序 3-10 步展开：进入页面→定位元素→输入/点击→断言
- 页面操作优先用 ui_action 类型步骤描述（点哪个按钮、输入什么），不要只写接口调用
- ui_action 步骤必须携带 uiSelector：{type, value}
  - type 取 id / ref / data-testid / aria-label / text / path
  - value 从 frontendSelectors 中选最匹配的真实选择器；无精确匹配时用 {type:"text", value:"可见文案"}
- 输入类步骤 data 必须含具体字段值（按 frontendForms 的字段名）
- state_assert 的 expected 写可验证断言；api_call 的 target 用真实接口路径
- target、expected 都不能为空；testData 含具体字段值

## coverageRefs 覆盖要求（v5.12）
- 每条用例必须携带 coverageRefs：{"requirementIds":[],"transitionIds":[],"endpointIds":[],"ruleIds":[]}
- id 只能从 coverageChecklist 中选取真实存在的项：
  transitionIds 用 "from->to"；endpointIds 用 "METHOD /path"；ruleIds 用 "rule-N"；requirementIds 用 "req-N"
- 优先覆盖 coverageGaps 列出的缺口；整体用例集必须让每个 transition/endpoint/rule 至少被一条用例引用
- 单次只输出 8-15 条用例，不要尝试一次性输出全部缺口

# 输出格式（同 v1.4）
返回 JSON 数组，字段：title/module/type/priority/preconditions/steps/expectedResults/
structuredSteps/apiEndpoints/testData/executionHints/stateMachineRef/coverageRefs
只返回 JSON 数组，不要包含其他文字。
