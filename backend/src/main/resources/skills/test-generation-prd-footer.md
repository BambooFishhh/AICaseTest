
## ragContexts / ragFailures（v6.4 补充）
- ragContexts：检索到的相关需求/上下文切片，作为 PRD 之外的补充约束
- ragFailures：历史执行失败经验；生成时避免重复失败路径，必要时增加对应校验与断言

## 本期范围（scope，v8.2，必须严格遵守）
- scope.targets：本期目标集合（endpoints + transitions）——每条用例的断言目标必须来自这里；
  coverageRefs 只允许引用 scope.targets 与 coverageChecklist 中的项
- scope.historicalTransitions / stateMachines 中 role="历史上下文" 的转换：
  禁止作为用例的断言目标或 coverageRefs 引用；只能用于：
  ① 前置条件描述（如"订单处于已支付状态"）
  ② structuredSteps 中 phase=setup 的准备步骤（把系统带到目标转换所需的前置状态）
- scope.setupHints：为每个目标转换推导的"初始态→源状态"最短路径骨架——
  构造该目标的正向/异常用例时，按 hint.steps 物化 setup 步骤（填入真实操作与数据）
- 每条用例步骤必须带 "phase" 字段："setup"=历史流程准备（不产生断言）；"verify"=本期行为验证与断言
  （断言类步骤 state_assert 必须为 verify；无明确区分时可整条省略 phase）

## 人类可读 UI 用例写法（v9.2，必须严格遵守）
本系统是 UI 自动化测试：用例描述"人在页面上做什么、看到什么"，由执行器操作真实页面完成验证。
- action 写人类动作句：动词 + 对象；按钮/链接/入口用【】标注
  （如"点击【登录】按钮"、"进入【我的收藏】页面"、"点击目标商品的【取消收藏】"）
- 输入步骤必须写明真实具体值（如"输入正确密码：Test@123456"），并同步写入 inputValue 与 testData
- 禁止变量占位符与元素标识符：input_username、btn_login、page_login、valid_username、api_login_response_code
  这类 snake_case / 代码标识符不得出现在 steps、action、target、expected 中——写"用户名输入框"、"【登录】按钮"、"页面跳转至首页"
- 禁止接口化步骤：不得出现"调用XX接口/请求XX接口"话术、HTTP 方法+路径（如 POST /wx/collect/delete）、
  type=api_call——接口交互由页面操作自然触发，不需要（也不允许）用例直接调接口
- 接口信息只写在 apiEndpoints 关联字段（标注该用例页面操作触达了哪些接口），不进步骤
- 前置条件 preconditions 写自然语言（如"服务正常运行"、"用户账号已注册且状态正常"），
  不写 backend_service_status == running 这类变量表达式
            - 引号引用的页面文案必须是页面上会原样出现的真实文字，禁止 N/X/xxx 占位符
              （错误示例：页面显示'共 N 件收藏'——执行时页面是"共 1 件收藏"，断言必失败；
              正确写法：引用不含变量的部分，如 页面显示'我的收藏'）；
              括号举例（如：蔓越莓曲奇，￥36）不能替代引号锚点——每条 state_assert 至少一个引号引用
            - module 必须取自 PRD 模块名或页面名，同一页面/功能的用例使用相同的 module
              （禁止同一页面出现"我的收藏"/"前端页面"等多种命名）
            - expected / expectedResults 写页面可感知现象；响应字段表达式（data.count == 5、collected == false）不允许

## 代码信息用于补充（不作为用例来源，只增强可执行性）
- endpoints：仅用于 apiEndpoints 关联字段与 coverageRefs.endpointIds 覆盖引用——禁止进入步骤的 action/target
- stateMachines：用例的 stateMachineRef 引用真实状态流转
- stateMachines[].source（v7.4）："rule" 表示规则兜底提取（仅状态枚举可信，无转换数据）——
  其 stateMachineRef.transitions 可为空数组，禁止为兜底状态机虚构转换；"llm" 来源正常引用
- businessRules：补充为前置条件或异常场景
- frontendForms：输入步骤的 inputValue/testData 填真实字段值（按 frontendForms 的字段名与校验规则）
- frontendSelectors：ui_action / input 步骤可附 uiSelector（{type, value}）
- frontendPageFlows：生成页面跳转验证用例（from→to，验证导航需求）
- frontendComponentStates：生成 UI 交互用例（弹窗打开/关闭、分步流程）
- frontendRoutes：UI 用例导航首步的路由值（path+name）只允许从中选取，禁止虚构路由

## structuredSteps / testData / executionHints 要求（必须严格遵守）
- structuredSteps 必须是非空数组，按真实操作顺序 3-10 步展开：进入页面→定位元素→输入/点击→断言
- v8.2: 涉及前置状态准备的用例，准备步骤标 "phase":"setup"，验证/断言步骤标 "phase":"verify"
  （如：setup=在历史页面把订单支付到已支付状态，verify=本期新发货逻辑的执行与断言）
- v9.2: 步骤 type 只允许 ui_action / input / state_assert 三种，禁止 api_call
- v8.9.7: 每个 UI 用例的**第 1 步必须是"打开目标页面/路由"**的 ui_action
  （target 用真实路由，如 /collect、/footprint、/goods/:id），且**必须携带**
  uiSelector {"type":"route","value":"路由"}（导航是唯一可 100% 确定的选择器），
  后续步骤才能定位/点击该页元素——严禁假设执行器已停留在目标页（否则从首页开始找不到元素）
- v7.15(A): ui_action 的 target 必须是页面元素/区域的人话描述（如"登录按钮"、"商品卡片"），
  严禁出现 HTTP 方法+路径格式（如 "GET /wx/home/index"、"POST /api/order"）
- ui_action 步骤可携带 uiSelector：{type, value}
  - type 白名单（执行器仅支持这些）：id / css / class / data-testid / aria-label / xpath；导航首步用 route
  - 禁止编造 text / path / ref 等执行器不支持的类型
              - value 从 frontendSelectors 中选最匹配的真实选择器；无精确匹配时省略 uiSelector 字段
                （后端会按前端分析结果自动补齐），严禁虚构选择器值——
                **唯一例外：导航首步的 route 选择器不来自 frontendSelectors、不算虚构**，
                必须直接写 {"type":"route","value":"路由"}（见 v8.9.7 条）
- 输入类步骤用 type=input，必带 inputValue（真实具体值）+ uiSelector
- state_assert 的 expected 写页面可感知的可验证断言
- target、expected 都不能为空；testData 含具体字段值

## 预期结果语言规范（v7.3，必须严格遵守）
- expected / expectedResults 必须描述用户在页面上可感知的现象：
  可见文案、toast/消息提示内容、页面跳转目标、元素出现/消失/禁用状态变化
- 禁止写 HTTP 状态码（如"返回401"）、后端字段名/变量名（如 errorMsg、orderId）、
  机器常量（如 status=PENDING_PAYMENT）、响应体键名
- v9.2: state_assert 的 expected 必须引用页面上将出现的具体可见文案（用引号标注，
  如 页面显示'我的收藏'与商品价格）；禁止"页面加载完成/正常加载/不再显示loading/
  至少一个商品项"这类无法用页面文本验证的抽象表述；"等待页面加载"类描述不是断言，
  不生成对应的 state_assert 步骤
- v7.6: 上下文中的 userFeedbackTexts 是从被测系统源码提取的真实提示文案对照表
  （前端 ElMessage / 后端异常消息）——编写 expected 时必须优先使用其中的原文，
  禁止自行编造提示文案
- v9.4: 引号锚点真实性——引号内的文案必须是目标页面上真实存在或将出现的文本：
  ① 禁止虚构页面没有的统计项/汇总文案（如页面只展示待付款/待发货计数时，
  不得断言"订单、收藏、足迹等统计项及其数值"）
  ② 禁止把导航标签/页签等可能不在页面文本快照里的短语当锚点（如个人页不用'我的'，
  改用页面正文可见的昵称/欢迎语/"待付款"等真实文案）
  ③ 数量类断言用占位符 N 写（如 页面显示'共 N 件收藏'），不要写死具体数字——
  执行器按数字语义匹配
  ④ 负向场景的断言写"不显示'X'提示"（执行器按"X 不出现"验证），不要写
  "操作无响应/页面无变化"这类无法验证的表述
- 12.17: 用例独立性——删除/修改/取消类用例必须自带准备步骤（结构化步骤里先执行
  "添加/收藏"再执行"删除/取消"），不得假设系统里已有可操作数据；参数异常类负向
  用例优先断言前端校验拦截（提交前校验的页面提示文案），不真实提交畸形数据——
  畸形提交会在被测系统留下脏数据，污染后续所有用例的执行结果

## coverageRefs 覆盖要求（v5.12）
- 每条用例必须携带 coverageRefs：{"requirementIds":[],"transitionIds":[],"endpointIds":[],"ruleIds":[]}
- id 只能从 coverageChecklist 中选取真实存在的项：
  transitionIds 用 "from->to"；endpointIds 用 "METHOD /path"；ruleIds 用 "rule-N"；requirementIds 原样使用 coverageChecklist.requirements[].id
- 优先覆盖 coverageGaps 列出的缺口；整体用例集必须让每个 transition/endpoint/rule 至少被一条用例引用
- 单次只输出 8-15 条用例，不要尝试一次性输出全部缺口

# 输出格式（同 v1.4）
返回 JSON 数组，字段：title/module/type/priority/preconditions/steps/expectedResults/
structuredSteps/apiEndpoints/testData/executionHints/stateMachineRef/coverageRefs
只返回 JSON 数组，不要包含其他文字。
