# PRD v1.12 — VueAnalyzer LLM 增强

## 版本信息
- **版本**: v1.12
- **基线**: v1.11
- **日期**: 2026-08-09
- **迭代主题**: 正则先提取 + LLM 补充，提升前端分析覆盖率

## 背景与痛点

v1.11 的 VueAnalyzer 纯正则提取，存在覆盖盲区：

| 正则提不了的场景 | 影响 |
|----------------|------|
| rules 定义在外部 JS 文件 | 校验规则缺失 |
| 用了自定义校验函数 `validator: validatePass` | 校验规则缺失 |
| 非 Element Plus 组件（如 Ant Design Vue / Naive UI） | 表单字段、组件状态全部漏提 |
| Composition API 的响应式状态 `const visible = ref(false)` | 组件交互状态漏提 |
| 动态路由 `router.push({ name: 'xxx' })` | 页面跳转目标漏提 |
| 编程式导航用了变量 `router.push(this.targetUrl)` | 页面跳转目标漏提 |

## 目标

正则负责"确定能提取的"（快、准、免费），LLM 负责"正则提不了的"（理解上下文、补充遗漏）。

```
VueAnalyzer.analyze()
  ├─ 1. 正则提取（现有 4 个方法，不变）
  ├─ 2. 收集 .vue 源码片段（template + 关键 script）
  ├─ 3. 一次 LLM 调用：正则结果 + 源码片段 → 补充 JSON
  └─ 4. 合并：正则结果为主，LLM 补充为辅（去重）
```

## 功能需求

### F1: LLM 补充提取
- 正则提取完成后，收集所有 .vue 文件的源码摘要（component 名 + template 截断 + script 截断）
- 连同正则结果一起发给 LLM，要求 LLM 找出正则遗漏的内容
- LLM 返回结构化 JSON：`{supplementalForms, supplementalStates, supplementalSelectors, supplementalFlows}`
- 合并到正则结果中（按 component + 字段名/选择器值去重）

### F2: 优雅降级
- LLM 调用失败 → 只返回正则结果（v1.11 行为）
- LLM 返回非法 JSON → 解析失败时忽略，只返回正则结果
- 无 LLM 配置（API Key 为空）→ 跳过 LLM 步骤

### F3: Token 控制
- 每个 .vue 文件源码摘要上限 1500 字符（template 800 + script 700）
- 总 token 控制在 ~8000 以内（约 10-15 个 .vue 文件）
- 超过时按文件名排序取前 N 个

## 验收标准
1. AC1: LLM 补充后，forms/componentStates/domSelectors/pageFlows 至少不少于正则结果
2. AC2: LLM 失败时返回正则结果，不报错
3. AC3: 无 API Key 时跳过 LLM，行为同 v1.11
4. AC4: 后端编译 BUILD SUCCESS

## 范围
- In Scope: VueAnalyzer 新增 LLM 补充逻辑
- Out of Scope: 前端 UI 变更（数据模型不变）、非 Vue 框架支持
