# PRD v2.5 — 截图标注 + 录屏回放增强

## 版本信息
- **版本**: v2.5
- **基线**: v2.4
- **日期**: 2026-08-09
- **迭代主题**: 截图标注点击位置 + 录屏回放增强

## 背景与痛点

v2.4 实现了录屏（周期截图）和执行报告，但存在两个问题：
1. **截图无标注** — 截图是纯浏览器截图，看不到点击发生在哪个位置。clickX/clickY 存为数据但没画到图上。
2. **录屏回放不完整** — 录屏只有每 2 秒的周期截图，不包含每步操作前后的截图，回放时看不到操作过程的关键帧。

## 目标

### 截图标注
- 每步操作的截图（screenshotAfter）标注红色圆圈 + 十字准星，标明点击位置
- 标注包含：红圈（半径 20px）、十字线（30px）、坐标文本

### 录屏回放增强
- 录屏帧序列合并周期截图 + 步骤截图，形成完整的操作回放
- 步骤截图带标注，周期截图不带标注（中间过渡帧）
- 前端播放器显示时间轴 + 帧类型（步骤帧/过渡帧）

## 功能需求

### F1: 截图标注（后端 BrowserSkill）
- 新增 `takeScreenshotWithMarker(sessionId, clickX, clickY)` 方法
- 用 Java `Graphics2D` 在截图上绘制：
  - 红色圆圈（半径 20px，线宽 3px）
  - 十字准星（横线 + 竖线，30px）
  - 坐标文本（如 "click: (260, 340)"）
- clickX/clickY 为 0 时不标注（DOM 点击无坐标的情况）

### F2: 执行流程集成（后端 ExecutionService）
- 步骤执行后使用 `takeScreenshotWithMarker` 替代 `takeScreenshot`
- 步骤截图路径加入 recordingFrames，录屏回放包含步骤关键帧

### F3: 录屏帧序列增强（后端 ExecutionService）
- recordingFrames 合并：周期截图（过渡帧）+ 步骤截图（关键帧，带标注）
- 按时间顺序排列

### F4: 前端录屏回放（前端 ExecutionResult.vue）
- 播放器增强：显示当前帧类型（步骤帧/过渡帧）
- 步骤帧显示标注，可点击跳转到对应步骤

## 验收标准
1. AC1: screenshotAfter 图片上有红色圆圈+十字标注点击位置
2. AC2: clickX/clickY 为 0 时截图无标注
3. AC3: recordingFrames 包含步骤截图帧
4. AC4: 前端播放器可播放完整回放
5. AC5: 后端编译 BUILD SUCCESS；前端构建成功

## 范围
- In Scope: 截图标注 + 录屏帧序列增强 + 前端播放器优化
- Out of Scope: CDP 视频录制、PDF 报告
