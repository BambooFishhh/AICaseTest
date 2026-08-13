# PRD v4.4 — 分析流式化

**版本**：v4.4
**基线**：v4.3
**日期**：2026-08-13
**主题**：代码分析改为 SSE 流式推送实时进度，替代"只显示一句等待文案 + 3 秒轮询"

## 一、背景与痛点

1. 点击"开始分析"后，前端只显示"正在分析代码结构，请稍候..."，期间没有阶段进度，大项目要等几分钟
2. 后端分析其实分多阶段（扫描结构→解析后端→解析前端→提取状态机），但进度没有暴露
3. 轮询（3 秒）只能拿到终态，体验差

## 二、范围

### In scope

- 后端：分析流程增加进度回调，按阶段推送 progress 事件；新增 SSE 端点 `GET /api/projects/{id}/analyze-stream`
- 前端：项目详情"开始分析"改用 EventSource 消费流式进度，实时展示阶段信息

### Out of scope

- 分析结果增量流式推送（扫描到一条展示一条，后续再做）
- 并发/性能（v4.2 已做）

## 三、功能详情

### 3.1 后端

- `AnalysisService.runAnalysis` 重构为支持 `ProgressCallback`，阶段更新：
  正在扫描项目结构 → 扫描完成 → 正在解析后端代码 → 正在解析前端代码 → 正在提取状态机 → 分析完成
- 新增 `runAnalysisStream(projectId, emitter)`：progress/complete/error 事件 + 客户端断开处理
- `ProjectController` 新增 `GET /{projectId}/analyze-stream`（操作权限校验 + created/failed 状态守卫）

### 3.2 前端

- ProjectDetail `handleAnalyze` 改用 `EventSource('/api/projects/{id}/analyze-stream')`
- progress → 更新提示横幅；complete → 刷新项目 + 提示成功；error → 提示失败 + 刷新
- 组件卸载时关闭 EventSource

## 四、验收标准

1. 点击"开始分析"后提示横幅实时显示阶段进度（扫描/后端/前端/状态机）
2. 分析完成自动刷新项目状态为"已分析"，失败显示错误
3. 页面切换/卸载后 EventSource 正确关闭，无连接泄漏
4. mvn test + npm run build 通过
