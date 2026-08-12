# PRD v3.15 — 回归与统计

**版本**：v3.15
**基线**：v3.14
**日期**：2026-08-12
**主题**：测试集/回归集 + 多执行环境 + 通过率趋势

## 一、背景与痛点

1. 批量执行每次都要临时勾选用例，无法保存"回归集"复用
2. 项目在 dev/staging/prod 多环境验证时，需要反复切换 URL
3. 执行历史只有静态统计，看不到通过率变化趋势

## 二、范围

### In scope

- 测试集（回归集）：新建/列表/删除/一键执行
- 多执行环境：环境列表（名称+URL）+ 默认环境切换，默认 URL 随激活环境同步
- 执行历史页通过率趋势图（最近 20 次滚动通过率）

### Out of scope

- 仪表盘（v3.17）、安全/并发/性能

## 三、功能详情

### 3.1 测试集

- 后端：TestSuite 实体（id/projectId/name/caseIds JSON/createdAt）+ 4 个接口（创建/列表/删除/执行）
- 执行复用 `ExecutionService.executeBatch`，返回 batchId 跳转批次页
- 前端：用例列表"保存为测试集"（基于当前选中）+"测试集"管理对话框（列表/执行/删除/新建）

### 3.2 多执行环境

- 后端：Project.settings 新增 `executionEnvironments`（environments 数组 + active）；保存时同步 `generationParams.defaultTargetUrl` 为激活环境 URL
- 前端：用例列表"执行环境"对话框（增删环境、选择激活环境）

### 3.3 通过率趋势

- 执行历史页新增 ECharts 折线图：最近 20 次执行的滚动通过率

## 四、验收标准

1. 测试集可保存/执行/删除，执行跳转批次页
2. 多环境可增删切换，默认执行 URL 跟随激活环境
3. 趋势图渲染正常
4. mvn compile + mvn test + npm run build 通过
