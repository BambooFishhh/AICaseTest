# PRD v2.7 — Playwright MCP Server + PlaywrightRecordSkill

> 版本：v2.7 | 主题：自建 Playwright MCP Server + PlaywrightRecordSkill 实现 | 基线：v2.6

## 一、迭代背景与痛点分析

### 背景

v2.6 完成了 McpClientManager 多 Server 架构，为接入 Playwright MCP 做好了基础设施。v2.5 的录屏是"每2秒截图"的简化方案，不是真正的视频录屏。

通过分析 @playwright/record-mcp 源码，确认其 `--cdp-endpoint` 模式不支持录屏（CDP 连接走 `connectOverCDP` 分支，跳过 `recordVideo` 配置）。因此决定自建 Playwright MCP Server，实现浏览器操作 + 真正视频录屏。

### 痛点

| 痛点 | 影响 |
|------|------|
| 当前录屏是 0.5fps 截图序列 | 快速操作丢失，回放不流畅 |
| @playwright/record-mcp 缺少 screenshot/visual_click 工具 | 截图标注和坐标点击无法保留 |
| BrowserSkill 仍依赖 Selenium | v2.8 需要可替代的 Skill 层 |

## 二、范围

### In Scope

1. 新建 `playwright-mcp-server/` — 独立 MCP Server，基于 Playwright 实现 9 个浏览器工具
2. 新建 `PlaywrightRecordSkill.java` — Java 端封装，方法签名与 BrowserSkill 对齐
3. McpClientManager 注册 "playwright" Server
4. application.yml 添加 playwright Server 配置

### Out of Scope

- ExecutionService/ExecutionAgent 切换到 PlaywrightRecordSkill（v2.8）
- 前端视频播放（v2.9）
- 移除 Selenium 依赖（v2.9）
- BrowserSkill 保留不动（过渡期共存）

## 三、功能详情

### 3.1 Playwright MCP Server 工具清单

| 工具 | 参数 | 返回 | 对应 BrowserSkill 方法 |
|------|------|------|----------------------|
| `browser_launch` | headless, width, height, video_dir | "ok" | browserLaunch |
| `browser_navigate` | url | page url | browserNavigate |
| `browser_take_screenshot` | path | 文件路径 | takeScreenshot |
| `browser_visual_click` | x, y | "clicked" | visualClick |
| `browser_dom_click` | selector | "clicked" | domClick |
| `browser_get_page_status` | 无 | JSON{url,title,textSnippet} | getPageStatus |
| `browser_video_get_path` | 无 | 视频文件路径 | — |
| `browser_video_save` | filename | 保存路径 | stopRecording |
| `browser_close` | 无 | "closed" | closeSession |

### 3.2 PlaywrightRecordSkill 方法映射

```
browserLaunch(headless,w,h) → callTool("playwright","browser_launch",{headless,width,height,video_dir})
browserNavigate(url)        → callTool("playwright","browser_navigate",{url})
takeScreenshot()            → callTool("playwright","browser_take_screenshot",{path})
takeScreenshotWithMarker()  → takeScreenshot + annotateScreenshot（Graphics2D 不变）
visualClick(x,y)            → callTool("playwright","browser_visual_click",{x,y})
domClick(type,value)        → callTool("playwright","browser_dom_click",{selector})
getPageStatus()             → callTool("playwright","browser_get_page_status",{}) → JSON解析
startRecording(dir)         → 无操作（launch 时已通过 video_dir 启动录屏）
stopRecording()             → callTool("playwright","browser_video_save",{filename}) → mp4路径
closeSession()              → callTool("playwright","browser_close",{})
```

### 3.3 录屏机制

Playwright 的 video 录制在 `browser.newContext({ recordVideo: { dir } })` 时启动。
- `browser_launch` 接受 `video_dir` 参数，创建 context 时设置 recordVideo
- 执行过程中 Playwright 自动录制 WebM 视频
- `browser_video_save` 调用 `page.video().saveAs(path)` 保存为 `.webm` 文件
- 不再需要周期截图

## 四、验收标准

1. `playwright-mcp-server/` npm install 成功
2. `mvn compile` 编译通过
3. PlaywrightRecordSkill 所有方法可调用（通过 McpClientManager 路由到 playwright Server）
4. 手动验证：browser_launch → browser_navigate → browser_take_screenshot → browser_close 链路通畅

## 五、风险与缓解

| 风险 | 缓解 |
|------|------|
| Playwright 浏览器二进制未安装 | `npx playwright install chromium` 预装 |
| MCP Server 启动失败影响后端 | McpClientManager 单 Server 失败不影响其他 |
| video 录制在 headless 下不工作 | Playwright 官方支持 headless 录屏 |

## 六、交付物清单

- [ ] `playwright-mcp-server/package.json` — 依赖配置
- [ ] `playwright-mcp-server/index.js` — MCP Server 实现（9个工具）
- [ ] `skill/PlaywrightRecordSkill.java` — Java 端封装
- [ ] `mcp/McpClientManager.java` — 注册 playwright Server
- [ ] `application.yml` — playwright Server 配置
- [ ] PRD + 后端技术评审 + 前端技术评审
- [ ] CHANGELOG + README 更新
