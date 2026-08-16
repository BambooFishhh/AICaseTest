
## 测试数据要求
- testData 必须包含具体字段值，不能为空对象 {}
- 数值字段：填入真实值和边界值（如 amount: 0, amount: -1, amount: 99999999）
- 字符串字段：填入正常值、空字符串、超长字符串（256字符）
- 枚举字段：填入合法值和非法枚举值
- 必填字段：测试缺失该字段的情况

## structuredSteps 要求（必须严格遵守）
- structuredSteps 必须是非空数组，按真实操作顺序 3-10 步展开：
  进入页面 → 定位元素 → 输入/点击 → 断言结果，禁止把多个操作合并成一句
- ui_action 类型步骤（点击/输入/选择/滚动）必须携带 uiSelector：{type, value}
  - type 取 id / ref / data-testid / aria-label / text / path
  - value 从下方 frontendSelectors 中选取与操作最匹配的真实选择器；
    找不到精确选择器时，target 写按钮/输入框的可见文案，uiSelector 用 {type:"text", value:"按钮文案"}
- 输入类操作必须携带 data：{字段名: 具体输入值}
- state_assert 类型步骤的 expected 必须写可验证断言（页面 URL / 元素文本 / 状态提示）
- api_call 类型步骤的 target 必须用真实接口路径，data 为该接口的入参
- 每步的 target、expected 都不能为空

## coverageRefs 覆盖要求（v5.12）
- 每条用例必须携带 coverageRefs：{"requirementIds":[],"transitionIds":[],"endpointIds":[],"ruleIds":[]}
- id 只能从 coverageChecklist 中选取真实存在的项：
  transitionIds 用 "from->to"；endpointIds 用 "METHOD /path"；ruleIds 用 "rule-N"；requirementIds 用 "req-N"
- 优先覆盖 coverageGaps 列出的缺口；整体用例集必须让每个 transition/endpoint/rule 至少被一条用例引用
- 单次只输出 8-15 条用例，不要尝试一次性输出全部缺口

## stateMachineRef 要求
- transitions 数组必须包含本用例测试的状态转换
- forbiddenTransitions 仅在 negative 类型用例中填写

# 输出格式
返回 JSON 数组，字段说明：
- title: 用例标题（简洁，含测试目标）
- module: 所属模块
- type: positive/negative/boundary/data
- priority: P0/P1/P2/P3
- preconditions: 前置条件数组
- steps: 步骤简述数组
- expectedResults: 预期结果数组
- structuredSteps: [{order, action, target, expected, data, type}]
- apiEndpoints: [{method, path, description}]
- testData: {字段名: 值}
- executionHints: {approach, notes, prerequisites}
- stateMachineRef: {states, transitions, forbiddenTransitions}
- coverageRefs: {requirementIds, transitionIds, endpointIds, ruleIds}

只返回 JSON 数组，不要包含其他文字。
