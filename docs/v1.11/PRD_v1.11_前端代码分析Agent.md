# PRD v1.11 — 前端代码分析 Agent

## 版本信息
- **版本**: v1.11
- **基线**: v1.10
- **日期**: 2026-08-09
- **迭代主题**: 增强 VueAnalyzer 为前端代码分析 Agent，补上交互流转/DOM选择器/表单校验上下文

## 背景与痛点

### 当前问题
v1.10 引入 PRD 驱动后，用例生成有了"需求主线"。但 OrchestratorAgent 只加载后端代码上下文（stateMachines + backendResult），**前端分析结果完全未被消费**。

当前 VueAnalyzer 是一个浅层正则扫描器，只提取：
- 路由路径（`path: '/xxx'`）
- API 调用（axios `.get()/.post()`）
- 技术栈（package.json）

**缺失的关键维度**：

| 缺失维度 | 对用例生成的影响 | 对 v2.0 执行 Agent 的影响 |
|----------|----------------|------------------------|
| 表单字段与校验规则 | testData 缺真实字段名、边界值靠猜 | 无法填写表单 |
| 组件交互状态（弹窗/抽屉/分步） | 缺 UI 交互状态机用例 | 无 UI 状态流转参考 |
| DOM 选择器 | 无法生成 ui_action 类型步骤 | dom_click 兜底无选择器可用 |
| 页面跳转关系 | 无法验证 PRD 导航需求 | browser_navigate 无目标 URL |

### 目标
1. 增强 VueAnalyzer 为真正的"前端代码分析 Agent"
2. 扩展 FrontendResult 数据模型，新增 4 个维度
3. OrchestratorAgent 加载 FrontendResult 并传给 TestGeneratorAgent
4. TestGeneratorAgent 的 prompt 补充前端交互上下文
5. 前端代码分析页面展示增强后的分析结果

## 功能需求

### F1: 扩展 FrontendResult 数据模型
**描述**: FrontendResult 新增 4 个字段

**新增字段**:
- `forms` (List): 表单字段与校验规则
  ```json
  [{"component":"LoginForm","fields":[{"name":"username","type":"input","label":"用户名","required":true,"rules":["required","min:3"]},{"name":"password","type":"password","label":"密码","required":true,"rules":["required","min:6"]}],"file":"Login.vue"}]
  ```
- `componentStates` (List): 组件交互状态（弹窗/抽屉/分步/标签页）
  ```json
  [{"component":"OrderDetail","type":"dialog","stateVar":"dialogVisible","trigger":"@click","file":"OrderDetail.vue"}]
  ```
- `domSelectors` (List): DOM 选择器（id/class/data-testid/ref/aria-label）
  ```json
  [{"component":"LoginForm","selectors":[{"type":"data-testid","value":"btn-login","element":"button"},{"type":"id","value":"username","element":"input"}],"file":"Login.vue"}]
  ```
- `pageFlows` (List): 页面跳转关系
  ```json
  [{"from":"/login","to":"/dashboard","trigger":"登录成功","component":"Login.vue"}]
  ```

### F2: 增强 VueAnalyzer — 路由与页面流转
**描述**: 深化路由解析，提取页面跳转关系

**实现**:
- 解析 `router/index.js`（或 `.ts`）路由配置，提取 path + name + component 映射
- 扫描 Vue SFC 中的 `router.push()` / `<router-link to="">` 调用，构建页面跳转关系
- 输出 `pageFlows` 列表

### F3: 增强 VueAnalyzer — 表单字段与校验
**描述**: 解析 Vue SFC 的 `<template>` 和 `<script>` 提取表单结构

**实现**:
- 正则匹配 `<el-form-item prop="xxx">` + `:rules` 绑定
- 正则匹配 `<el-input v-model="form.xxx">` / `<el-select>` / `<el-date-picker>` 等
- 从 `<script>` 中提取 `rules` 对象定义（required/min/max/pattern）
- 输出 `forms` 列表，每个表单含组件名、字段列表、校验规则

### F4: 增强 VueAnalyzer — 组件交互状态
**描述**: 提取弹窗/抽屉/分步/标签页等交互状态

**实现**:
- 正则匹配 `v-model="xxxVisible"` / `v-if="dialogVisible"` 等
- 识别 `el-dialog` / `el-drawer` / `el-steps` / `el-tabs` 组件
- 提取触发条件（`@click="dialogVisible = true"`）
- 输出 `componentStates` 列表

### F5: 增强 VueAnalyzer — DOM 选择器
**描述**: 提取可操作元素的 DOM 选择器，为 v2.0 执行 Agent 兜底提供数据

**实现**:
- 正则匹配 `data-testid="xxx"` / `id="xxx"` / `ref="xxx"` / `aria-label="xxx"`
- 识别元素类型（button/input/select/link）
- 关联到所在组件
- 输出 `domSelectors` 列表

### F6: OrchestratorAgent 接入前端上下文
**描述**: OrchestratorAgent 加载 FrontendResult 并传给 TestGeneratorAgent

**编排流程变更**:
```
1. 读 Project.prdContent → PrdAgent.analyze (现有)
2. 读 stateMachines + backendResult (现有)
3. 读 frontendResult ← 新增
4. testGeneratorAgent.generate(prdResult, stateMachines, backendResult, frontendResult, callback)
```

### F7: TestGeneratorAgent 补充前端上下文
**描述**: generate 方法新增 frontendResult 参数，prompt 补充前端信息

**prompt 变更**:
- 新增【前端交互上下文（辅助）】段落
- 前端表单字段 → 补充 testData 真实字段名和校验规则
- 前端 DOM 选择器 → structuredSteps 新增 `uiSelector` 字段
- 前端页面流转 → 生成页面跳转验证类用例
- 前端组件状态 → 生成 UI 交互状态机用例

**structuredSteps 扩展**:
```json
{"order":1,"action":"点击登录按钮","target":"POST /api/login","expected":"跳转到 /dashboard","data":{"username":"","password":""},"type":"ui_action","uiSelector":{"type":"data-testid","value":"btn-login"}}
```

### F8: 前端代码分析页面增强
**描述**: CodeAnalysis.vue 展示增强后的前端分析结果

**展示内容**:
- 路由列表（path + name + component）— 现有
- API 调用列表 — 现有
- 表单字段列表（组件名 + 字段名 + 类型 + 校验规则）— 新增
- 组件交互状态列表（组件名 + 类型 + 状态变量）— 新增
- DOM 选择器列表（组件名 + 选择器类型 + 值 + 元素）— 新增

## 验收标准

1. **AC1**: 分析 Vue 项目后，FrontendResult 包含 forms/componentStates/domSelectors/pageFlows 四个新维度
2. **AC2**: 表单字段含真实字段名和校验规则（从 el-form-item + rules 提取）
3. **AC3**: DOM 选择器含 data-testid/id/ref/aria-label 四种类型
4. **AC4**: 页面跳转关系从 router.push / router-link 提取
5. **AC5**: 生成用例时，前端上下文被消费：testData 含真实字段名、structuredSteps 可含 uiSelector
6. **AC6**: 前端无 Vue 项目时，frontendResult 为空，生成退化为原逻辑（向后兼容）
7. **AC7**: 前端代码分析页面展示 4 个新维度的分析结果
8. **AC8**: 后端编译 BUILD SUCCESS；前端 `npm run build` 成功

## 风险与对策

| 风险 | 对策 |
|------|------|
| Vue SFC 正则解析覆盖不全（多种写法） | 聚焦 Element Plus 常见模式，非标写法跳过不报错 |
| 路由配置格式多样（嵌套路由/动态路由） | 先支持平铺式路由，嵌套路由递归提取 path |
| 表单 rules 在外部文件定义（非内联） | 优先解析 SFC 内联 rules，外部文件留后续 |
| DOM 选择器噪音（CSS class 过多） | 只提取 data-testid/id/ref/aria-label，不提取 class |
| LLM token 超限（前端上下文过大） | frontendResult 序列化后截断，保留前 N 字符 |

## 交付物清单

- [ ] `docs/v1.11/PRD_v1.11_前端代码分析Agent.md`
- [ ] `docs/v1.11/后端技术评审_v1.11.md`
- [ ] `docs/v1.11/前端技术评审_v1.11.md`
- [ ] 后端: FrontendResult 扩展 / VueAnalyzer 增强 4 个维度 / OrchestratorAgent 接入 / TestGeneratorAgent 改造
- [ ] 前端: CodeAnalysis.vue 展示增强
- [ ] CHANGELOG + README 更新
- [ ] Git 提交推送

## 范围说明

### In Scope
- VueAnalyzer 增强：表单/组件状态/DOM选择器/页面流转
- FrontendResult 数据模型扩展
- OrchestratorAgent 加载前端上下文
- TestGeneratorAgent prompt 补充前端信息
- 前端代码分析页面展示新维度

### Out of Scope
- AI 执行引擎（v2.0）
- React/Angular 分析器（后续）
- Pinia/Vuex 状态管理深度分析（后续）
- Vue SFC 完整 AST 解析（当前用正则，后续可升级为 AST）
