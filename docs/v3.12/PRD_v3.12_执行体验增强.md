# PRD v3.12 — 执行体验增强

**版本**：v3.12
**基线**：v3.11
**日期**：2026-08-12
**主题**：用例执行状态可视化与快捷操作 + 默认执行 URL + 生成前置预检 + 批次失败摘要

## 一、背景与痛点

v3.11 打通了执行历史闭环，但高频执行路径仍有体验缺口：

1. 用例列表看不到每条用例的执行状态（需点开详情），且无法按状态筛选、失败无法一键重跑
2. 评审通过（approved）的用例没有"批量执行"承接入口
3. 每次执行都要手填 URL，容易填错；项目没有默认执行地址
4. 无 PRD 且未分析的项目，点了"生成用例"才报错
5. 批次结果页失败原因要逐个点开详情；且后端返回 `records` 而前端读 `executions`，批次列表实际为空（数据映射 bug）

## 二、范围

### In scope

- 用例列表新增"执行状态"列 + 状态筛选（后端 list 接口加 executionStatus 参数）
- "重跑失败"、"执行已批准用例"两个快捷批量执行入口
- 项目级默认执行 URL（存入生成参数 settings，执行对话框自动带入）
- 生成前置预检：无 PRD 且未分析时禁用"生成用例"并提示
- 批次结果页：修复列表数据映射 bug + 新增失败用例错误摘要折叠区

### Out of scope

- 报告预览、导入导出恢复（v3.13）
- 安全、并发、性能

## 三、功能详情

### 3.1 执行状态列与筛选

- 后端 `listTestCases` 新增 `executionStatus` 过滤参数（not_executed/running/passed/failed）
- 前端筛选区新增"执行状态"下拉；表格新增"执行状态"列（状态胶囊，与执行历史一致）

### 3.2 快捷批量执行

- 工具栏新增"重跑失败（N）"：按 executionStatus=failed 预勾选并打开批量执行对话框
- 工具栏新增"执行已批准（N）"：按 reviewStatus=approved 预勾选并打开批量执行对话框

### 3.3 默认执行 URL

- 后端 `GenerationParams` 新增 `defaultTargetUrl` 字段（随项目 settings 存储）
- 生成参数对话框新增"默认执行 URL"输入
- 批量执行对话框与单条执行对话框默认带入项目默认 URL，可覆盖

### 3.4 生成前置预检

- `ProjectDetail`：`canGenerate` 增加上下文校验——`created` 状态下必须已有 PRD 内容才允许生成；不满足时禁用并 tooltip 提示"请先提供 PRD 或完成代码分析"

### 3.5 批次失败摘要

- 后端 `getBatchStatus` 同时返回 `executions` 别名（修复字段映射）
- 批次页列表改用 `executions`/`id`/`testCaseTitle`
- 新增"失败用例（N）"折叠区：标题 + 错误摘要 + 跳转详情

## 四、验收标准

1. 列表展示执行状态并可筛选；重跑失败/执行已批准按钮数量与选中一致
2. 默认 URL 保存后，单条与批量执行对话框自动带入
3. created 且无 PRD 时"生成用例"禁用并提示
4. 批次页列表正常展示数据，失败折叠区显示错误摘要
5. mvn compile BUILD SUCCESS；npm run build 成功

## 五、交付物清单

- [x] PRD + 前后端评审
- [ ] 后端：executionStatus 筛选、defaultTargetUrl、批次 executions 别名
- [ ] 前端：状态列/筛选/快捷执行/默认URL/预检/批次摘要
- [ ] CHANGELOG / README + 提交推送
