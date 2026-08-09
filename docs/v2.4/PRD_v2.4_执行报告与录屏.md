# PRD v2.4 — 执行报告 + 录屏

## 版本信息
- **版本**: v2.4
- **基线**: v2.3
- **日期**: 2026-08-09
- **迭代主题**: 执行报告生成（HTML）+ 浏览器录屏（周期截图+前端播放）

## 背景与痛点

v2.1 的执行结果页只展示基础信息（步骤列表+截图），缺少：
1. **可下载报告** — 测试完成后无法生成独立报告文件供分享/存档
2. **录屏回放** — 只有前后截图，无法看到操作过程的动态变化
3. **批量报告** — 批量执行后无汇总报告（通过率、失败分布）
4. **统计指标** — 缺少执行时长、通过率、平均步骤耗时等量化指标

## 目标

### 执行报告
- 生成自包含 HTML 报告（内嵌截图 base64），可离线打开
- 单条执行报告 + 批量执行汇总报告
- 报告内容：概览统计 + 步骤详情 + 截图 + 失败分析

### 浏览器录屏
- 执行期间每 2 秒自动截图，保存为帧序列
- ExecutionRecord 新增 recordingFrames 字段
- 前端播放器：播放/暂停/拖拽进度条/逐帧查看

## 功能需求

### F1: 报告生成服务（后端）
- 新建 `ReportService.java`
- `generateExecutionReport(executionId)` → HTML 字符串
- `generateBatchReport(batchId)` → HTML 字符串
- HTML 内嵌截图（base64），可离线打开
- API：`GET /api/executions/{id}/report` → 文件下载
- API：`GET /api/executions/batch/{batchId}/report` → 文件下载

### F2: 录屏 Skill（后端）
- `BrowserSkill.java` 新增：
  - `startRecording(sessionId)` → 启动定时截图（每 2s）
  - `stopRecording(sessionId)` → 停止，返回帧路径列表
- `ExecutionRecord.java` 新增 `recordingFrames` 字段（JSON 数组）

### F3: 执行流程集成（后端）
- `ExecutionService.java` 在执行开始时启动录屏，结束时停止
- 录屏帧路径存入 ExecutionRecord.recordingFrames

### F4: 前端报告页
- `ExecutionResult.vue` 新增：
  - "下载报告"按钮 → 调用 report API
  - 录屏播放器（帧序列轮播 + 进度条）
- `BatchResult.vue` 新增：
  - "下载批次报告"按钮

### F5: 执行统计
- 单条报告统计：总步骤数、通过/失败数、通过率、总耗时、平均步骤耗时
- 批量报告统计：总用例数、通过/失败数、通过率、总耗时、失败用例列表

## 验收标准
1. AC1: `GET /api/executions/{id}/report` 返回可下载的 HTML 文件
2. AC2: HTML 报告内嵌截图，可离线打开
3. AC3: 执行过程中自动录屏，recordingFrames 非空
4. AC4: 前端播放器可播放/暂停/拖拽
5. AC5: 后端编译 BUILD SUCCESS；前端构建成功

## 范围
- In Scope: HTML 报告生成 + 周期截图录屏 + 前端播放器
- Out of Scope: CDP 原生视频录制（后续迭代）、PDF 报告

## 风险
| 风险 | 对策 |
|------|------|
| 周期截图影响执行性能 | 间隔 2s，异步线程截图 |
| 录屏帧过多导致存储膨胀 | 限制最多 60 帧（2分钟），超过停止 |
