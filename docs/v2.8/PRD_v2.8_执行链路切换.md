# PRD v2.8 — 执行链路切换 + 录屏升级为视频

> 版本：v2.8 | 主题：ExecutionService/ExecutionAgent 从 Selenium 切换到 Playwright | 基线：v2.7

## 一、背景

v2.7 完成了 Playwright MCP Server 和 PlaywrightRecordSkill，但执行链路仍使用 BrowserSkill（Selenium）。本版本将执行链路切换到 PlaywrightRecordSkill，录屏从图片序列升级为真正的 WebM 视频。

## 二、范围

### In Scope

1. ExecutionService 依赖从 BrowserSkill 切换到 PlaywrightRecordSkill
2. ExecutionAgent 依赖从 BrowserSkill 切换到 PlaywrightRecordSkill
3. ExecutionRecord 新增 recordingVideoPath 字段
4. ExecutionController 新增视频下载 API
5. 录屏逻辑：周期截图+步骤帧合并 → Playwright recordVideo WebM

### Out of Scope

- 前端视频播放（v2.9）
- 移除 Selenium 依赖和 BrowserSkill（v2.9）

## 三、验收标准

1. `mvn compile` 编译通过
2. 触发执行后，ExecutionRecord.recordingVideoPath 指向 .webm 文件
3. `GET /api/executions/{id}/video` 返回视频文件流

## 四、交付物

- [ ] ExecutionService.java — 切换到 PlaywrightRecordSkill
- [ ] ExecutionAgent.java — 切换到 PlaywrightRecordSkill
- [ ] ExecutionRecord.java — 新增 recordingVideoPath
- [ ] ExecutionController.java — 新增视频下载 API
- [ ] PRD + 技术评审 + CHANGELOG + README
