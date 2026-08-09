# PRD v2.1 — MCP 多模态桥接 + Agent 执行引擎

## 版本信息
- **版本**: v2.1
- **基线**: v2.0
- **日期**: 2026-08-09
- **迭代主题**: 多模态视觉识别 + LLM 驱动的 Agent 执行引擎 + 批量执行

## 背景与痛点

v2.0 实现了 Skill 工具层和程序化执行，但：
1. 执行逻辑是硬编码 if-else（`if type=="ui_action" → domClick`），不是 Agent 驱动
2. 没有多模态视觉识别，无法通过截图找到页面控件
3. 没有兜底逻辑（MCP 失败 → DOM 兜底；点击无效 → DOM 重试）
4. 只支持单条执行，无法批量跑

## 目标

实现用户设计的完整 Agent 执行架构：
- **MCP 桥接服务**：截图 + 自然语言 → 多模态 LLM → 结构化位置 JSON
- **ExecutionAgent**：LLM 驱动的 agentic loop，自主决策调用哪个工具
- **两层兜底**：Agent 根据工具返回结果自主选择路径（非代码 if-else）
- **批量执行**：多条用例顺序执行，独立 session，断点续跑

## 功能需求

### F1: LlmService 多模态扩展
- 新增 `chatWithImage(systemPrompt, userText, imageBase64)` 方法
- 发送 content 数组（text + image_url）到 OpenAI Vision API
- 复用现有重试机制

### F2: McpBridgeService — 多模态视觉识别
- `multimodalElementLocate(imagePath, elementDesc)` → LocateResult
- 读取截图文件 → base64 编码 → 调 chatWithImage
- System Prompt 强制输出 JSON：`{found, bbox, clickCenter{x,y}, elementText, confidence}`
- 不操作浏览器，只做视觉识别

### F3: ExecutionAgent — Agent 执行引擎
- **System Prompt**：用户设计的完整指令（分支判断、兜底触发、禁止行为）
- **Agentic Loop**：
  ```
  每个步骤:
    1. LLM 决策：根据步骤描述 + 用例上下文，生成元素查找描述
    2. 调 Skill take_screenshot
    3. 调 MCP multimodal_element_locate
    4. LLM 决策：根据 MCP 返回结果，选择执行策略
       - found=true → visual_click → get_page_status → LLM 判断是否生效
       - found=false → dom_click 兜底
       - 点击无效 → dom_click 重试
    5. 调 Skill take_screenshot（操作后）
    6. 组装证据，调 save_test_evidence
  ```
- 兜底逻辑由 LLM 决策，不是代码 if-else

### F4: 批量执行
- `executeBatch(projectId, caseIds, targetUrl)` → batchId
- 顺序执行，每条独立浏览器 session
- 单条失败不阻塞后续
- 前端显示批次进度（已完成/总数）

### F5: 前端
- TestCaseList 新增"批量执行"按钮（勾选用例后）
- 批次进度条（已完成 N/M，每条状态）
- 单条执行新增"Agent 模式"选项

## 验收标准
1. AC1: MCP 能识别截图中的控件，返回坐标和置信度
2. AC2: Agent 能自主决策：视觉点击 → 验证 → 兜底（非代码 if-else）
3. AC3: 批量执行多条用例，单条失败不阻塞
4. AC4: 后端编译 BUILD SUCCESS；前端 npm run build 成功

## 范围
- In Scope: LlmService 多模态、McpBridgeService、ExecutionAgent、批量执行、前端批量 UI
- Out of Scope: 录屏（v2.2）、并行执行、API 调用类步骤执行

## 风险
| 风险 | 对策 |
|------|------|
| 多模态 LLM 不支持图片 | 检测 model 是否支持 vision，不支持时降级为纯 DOM |
| LLM 决策延迟 | 单步超时 60s，超时标记 failed |
| MCP 返回非法 JSON | 解析失败 → 降级为 DOM 点击 |
| 批量执行超时 | 每条独立 session + 5min 超时 |
