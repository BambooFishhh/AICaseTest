# PRD v2.0 — Skill 工具层 + 执行数据模型

## 版本信息
- **版本**: v2.0
- **基线**: v1.12
- **日期**: 2026-08-09
- **迭代主题**: AI 用例执行引擎基础设施 — Selenium WebDriver 集成、7 个 Skill 工具、执行数据模型、API、前端触发

## 背景与痛点

v1.0-v1.12 完成了"代码/PRD → 用例生成"链路。但生成的用例**无法自动执行验证**，测试人员仍需手动按步骤操作。

v2.0 开始构建"用例 → 自动执行 → 证据留存"链路。本版本是基础设施层：实现 Skill 工具（浏览器操作原子动作）+ 执行数据模型 + API + 前端触发。

**v2.0 不含 Agent 智能驱动**（v2.1 做），本版本的执行是程序化按步骤顺序执行 Skill 工具，验证工具链可用。

## v2.x 路线
| 版本 | 主题 |
|------|------|
| **v2.0** | Skill 工具层 + 执行数据模型 + API + 前端触发 |
| v2.1 | MCP 多模态桥接 + Agent 执行引擎（LLM 驱动 + 兜底逻辑） |
| v2.2 | 执行报告 + 录屏 |

## 功能需求

### F1: Selenium WebDriver 集成
- pom.xml 新增 Selenium 4 依赖
- WebDriverManager 自动管理 ChromeDriver
- BrowserSkill 组件管理 WebDriver 实例（sessionId → WebDriver 映射）

### F2: 7 个 Skill 工具

| Skill 工具 | 入参 | 出参 | 说明 |
|-----------|------|------|------|
| `browser_launch` | options(headless/width/height) | sessionId | 启动 Chrome，返回会话 ID |
| `browser_navigate` | sessionId, url | ok/url/title | 跳转页面，等待加载 |
| `take_screenshot` | sessionId | filePath | 全页截图，返回本地路径 |
| `visual_click` | sessionId, x, y | ok | 坐标点击 |
| `dom_click` | sessionId, selectorType, selectorValue | ok | DOM 选择器点击 |
| `get_page_status` | sessionId | {url, title, textSnippet} | 页面状态摘要 |
| `save_test_evidence` | evidenceData | filePath | 证据写入文件 |

### F3: 执行数据模型

**ExecutionRecord**（执行记录）
- id, projectId, testCaseId, status(pending/running/passed/failed)
- startTime, endTime, summary, errorMessage

**ExecutionStep**（执行步骤记录）
- id, executionId, stepIndex, action, target
- strategy(visual/dom/manual), result(passed/failed/skipped)
- screenshotBefore, screenshotAfter, coordinates, error

### F4: 执行 API

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/projects/{pid}/testcases/{caseId}/execute` | 触发执行 |
| GET | `/api/executions/{eid}` | 查询执行结果 |
| GET | `/api/projects/{pid}/executions` | 执行历史列表 |
| GET | `/api/executions/{eid}/steps` | 执行步骤详情 |

### F5: ExecutionService — 程序化执行
- 读取 TestCase 的 structuredSteps
- 逐步骤调用 Skill 工具（非 Agent 驱动，顺序执行）
- ui_action 类型 → dom_click（v2.0 暂用 DOM 点击，v2.1 接入多模态）
- api_call 类型 → 跳过（v2.1 接入 HTTP 调用）
- state_assert 类型 → get_page_status 验证
- 每步骤记录截图和结果

### F6: 前端
- TestCaseCard 新增"执行"按钮
- ExecutionResult.vue 展示执行结果 + 步骤详情 + 截图
- 执行历史列表

## 验收标准
1. AC1: 能启动 Chrome 浏览器、导航、截图、点击、获取页面状态
2. AC2: 执行一条测试用例后生成 ExecutionRecord + ExecutionStep 记录
3. AC3: 前端能触发执行并查看结果
4. AC4: 执行失败时不崩溃，记录失败步骤继续后续步骤
5. AC5: 后端编译 BUILD SUCCESS；前端 `npm run build` 成功

## 范围
- In Scope: Selenium 集成、7 个 Skill 工具、ExecutionRecord/ExecutionStep、4 个 API、ExecutionService（程序化）、前端触发+结果
- Out of Scope: Agent LLM 驱动（v2.1）、MCP 多模态（v2.1）、录屏（v2.2）、并行执行

## 风险
| 风险 | 对策 |
|------|------|
| Chrome 未安装 | 启动时检测，未安装则 browser_launch 返回错误 |
| ChromeDriver 版本不匹配 | 用 WebDriverManager 自动管理 |
| 截图文件路径 | 统一存 outputs/screenshots/{executionId}/ |
| 执行超时 | 单步骤 30s 超时，标记 failed 继续 |
