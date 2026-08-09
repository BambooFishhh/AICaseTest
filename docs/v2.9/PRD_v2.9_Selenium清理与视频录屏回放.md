# PRD v2.9 — Selenium 清理 + 录屏回放升级为视频播放

**版本**: v2.9
**基线**: v2.8
**日期**: 2026-08-09
**主题**: 清理 Selenium 死代码与依赖；前端录屏回放从图片轮播升级为 WebM 视频播放

---

## 1. 背景与痛点

v2.8 已将执行链路从 Selenium `BrowserSkill` 完整切换到 `PlaywrightRecordSkill`，录屏也从周期截图升级为 Playwright `recordVideo` 生成的 WebM 视频。但存在两个遗留问题：

1. **死代码与冗余依赖**：`BrowserSkill.java` 不再被任何执行链路引用，但代码与 `selenium-java`、`webdrivermanager` 依赖仍保留在工程中，增加包体积与维护噪音。
2. **前端回放体验割裂**：`ExecutionResult.vue` 的录屏回放仍是 v2.4 的"图片帧轮播"方案（依赖 `recordingFrames`），而 v2.8 后新执行记录只产出 `recordingVideoPath`（WebM 视频），导致新记录无法在前端回放。

## 2. 范围

### In Scope（本迭代做）

- 后端：删除 `BrowserSkill.java`，移除 `pom.xml` 中 `selenium-java` 与 `webdrivermanager` 依赖
- 后端：更新 `PlaywrightRecordSkill` 注释，去掉"过渡期共存"说明
- 前端：`ExecutionResult.vue` 录屏回放升级为 `<video>` 标签播放 WebM
- 前端：优先使用 `recordingVideoPath`，无视频时回退到 `recordingFrames` 图片轮播（兼容历史记录）
- 前端：`api/execution.js` 新增视频 URL 辅助函数

### Out of Scope（本迭代不做）

- 不删除 `ExecutionRecord.recordingFrames` 字段（保留以兼容历史数据）
- 不改动截图标注逻辑（已在 PlaywrightRecordSkill 中复用）
- 不改动 Playwright MCP Server 本身

## 3. 功能详情

### 3.1 后端清理

| 项 | 操作 |
|----|------|
| `skill/BrowserSkill.java` | 删除整个文件 |
| `pom.xml` | 移除 `org.seleniumhq.selenium:selenium-java` 依赖块 |
| `pom.xml` | 移除 `io.github.bonigarcia:webdrivermanager` 依赖块 |
| `PlaywrightRecordSkill.java` | 类注释去掉"过渡期与 BrowserSkill 共存，v2.9 清理 Selenium"，更新为已替代说明 |

### 3.2 前端录屏回放升级

`ExecutionResult.vue` 录屏回放区域改为双模式：

- **视频模式**（优先）：当 `execution.recordingVideoPath` 存在时，渲染 `<video controls>` 播放 WebM，源为 `GET /api/executions/{eid}/video`
- **图片轮播模式**（回退）：当无视频但有 `recordingFrames` 时，保留原轮播逻辑（兼容 v2.4~v2.5 历史记录）

视频播放器特性：
- 原生 `<video>` 控件（播放/暂停/进度条/全屏）
- 支持下载（`Download` 按钮触发 `<a download>`）
- 显示视频格式标识（WebM）

## 4. 验收标准

- [x] 后端 `mvn compile` BUILD SUCCESS，且 jar 中无 selenium 相关 class
- [x] 前端 `npm run build` 成功
- [x] 前端录屏回放区域：有 `recordingVideoPath` 时显示 `<video>` 播放器
- [x] 前端录屏回放区域：无视频但有 `recordingFrames` 时回退图片轮播
- [x] 全代码库无 `BrowserSkill` 引用（注释中的历史说明除外）

## 5. 风险与缓解

| 风险 | 缓解 |
|------|------|
| 历史 ExecutionRecord 无 videoPath 无法回放 | 保留 recordingFrames 字段与图片轮播回退 |
| 删除 Selenium 后遗漏引用导致编译失败 | 编译验证 + Grep 全量确认无 BrowserSkill 引用 |

## 6. 交付物清单

- [ ] `docs/v2.9/PRD_v2.9_Selenium清理与视频录屏回放.md`
- [ ] `docs/v2.9/后端技术评审_v2.9.md`
- [ ] `docs/v2.9/前端技术评审_v2.9.md`
- [ ] 后端代码改动（删除 + pom + 注释）
- [ ] 前端代码改动（ExecutionResult.vue + api/execution.js）
- [ ] CHANGELOG + README 更新
