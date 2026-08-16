# API 概览

> 完整 REST API 概览（由 README「API 概览」章节拆分而来，内容保持原样）。

## API 概览

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/auth/register` | 注册（v4.0） |
| POST | `/api/auth/login` | 登录，返回 token + user（v4.0） |
| GET | `/api/auth/me` | 当前用户（v4.0） |
| POST | `/api/auth/change-password` | 修改密码（v4.1） |
| POST | `/api/batches/{batchId}/cancel` | 取消批次执行（v4.2） |
| POST | `/api/projects/{id}/testcases/copy-execute` | 复制执行（快照，不影响原用例，v4.3） |
| GET/POST/PUT/DELETE | `/api/groups`、`/api/groups/{id}/members` | 项目组与成员管理（v4.3） |
| GET | `/api/users` | 用户查询（成员候选，v4.3） |
| GET | `/api/projects` | 项目列表 |
| POST | `/api/projects` | 创建项目 |
| GET | `/api/projects/{id}` | 项目详情 |
| DELETE | `/api/projects/{id}` | 删除项目 |
| POST | `/api/projects/{id}/analyze` | 触发代码分析 |
| GET | `/api/projects/{id}/analysis` | 获取分析结果 |
| POST | `/api/projects/{id}/testcases/generate` | 触发用例生成 |
| GET | `/api/projects/{id}/testcases/generate-stream` | 流式生成用例（SSE，推送 progress/case/complete/cancelled/error，v3.2） |
| GET | `/api/projects/{id}/analyze-stream` | 流式分析（SSE，推送 progress/complete/error，v4.4） |
| GET | `/api/projects/{id}/testcases/generate-stream-append?type={type}` | 流式追加生成用例（SSE，不删除现有用例 + 类型过滤 + 跨去重，v3.5） |
| POST | `/api/projects/{id}/testcases/generate-cancel` | 取消流式生成（v3.3，v3.5 同时适用于追加生成） |
| POST | `/api/projects/{id}/testcases` | 手动创建测试用例（v3.6） |
| GET | `/api/projects/{id}/prd` | 查询 PRD 内容 |
| PUT | `/api/projects/{id}/prd` | 更新文本 PRD |
| POST | `/api/projects/{id}/prd/upload` | 上传 PDF（PDFBox 解析） |
| POST | `/api/projects/{id}/prd/fetch` | 抓取在线链接 PRD（Jsoup） |
| GET | `/api/projects/{id}/context` | 获取项目上下文（v5.9：PRD + 额外 Prompt + 上下文文档） |
| PUT | `/api/projects/{id}/context` | 更新项目上下文（v5.9：额外 Prompt + 上下文文档） |
| GET | `/api/projects/{id}/execution-cookies` | 获取执行 Cookie（v5.9） |
| PUT | `/api/projects/{id}/execution-cookies` | 更新执行 Cookie（v5.9） |
| GET | `/api/projects/{id}/generation-params` | 获取生成参数（v3.4，v3.12 含 defaultTargetUrl） |
| PUT | `/api/projects/{id}/generation-params` | 更新生成参数（v3.4，v3.12 含 defaultTargetUrl） |
| GET | `/api/projects/{id}/testcases` | 用例列表（分页+筛选+覆盖率，v3.12 支持 executionStatus 筛选） |
| GET | `/api/projects/{id}/coverage/matrix` | 覆盖率矩阵（每转换覆盖详情） |
| GET | `/api/projects/{id}/testcases/{tcId}` | 用例详情 |
| PUT | `/api/projects/{id}/testcases/{tcId}` | 更新用例 |
| DELETE | `/api/projects/{id}/testcases/{tcId}` | 删除用例 |
| DELETE | `/api/projects/{id}/testcases/batch` | 批量删除用例 |
| GET | `/api/projects/{id}/testcases/export?format=json\|csv&ids=` | 导出用例（JSON/CSV 文件下载） |
| POST | `/api/projects/{id}/testcases/import` | 导入 JSON 用例文件（multipart） |
| POST | `/api/projects/{id}/testcases/import-xmind` | 导入 XMind 文件（v3.9） |
| POST | `/api/projects/{id}/testcases/copy-to` | 复制选中用例到其他项目 |
| POST | `/api/projects/{id}/testcases/review` | 批量改评审状态 |
| GET | `/api/projects/{id}/testcases/{tcId}/versions` | 用例历史版本列表 |
| GET | `/api/projects/{id}/testcases/{tcId}/versions/{vId}` | 用例版本详情（含快照） |
| POST | `/api/projects/{id}/testcases/{tcId}/versions/{vId}/rollback` | 回滚到指定版本 |
| GET | `/api/projects/{id}/statemachines` | 状态机列表 |
| POST | `/api/projects/{id}/mindmap` | 生成脑图 |
| GET | `/api/settings` | 获取设置 |
| PUT | `/api/settings` | 更新设置 |
| POST | `/api/projects/{pid}/testcases/{caseId}/execute?mode=agent` | 触发用例执行（v2.0，v2.1 加 Agent 模式） |
| GET | `/api/executions/{eid}` | 查询执行结果（v2.0） |
| GET | `/api/projects/{pid}/executions` | 执行历史列表（v2.0，v3.11 前端接入执行历史页） |
| GET | `/api/executions/{eid}/steps` | 执行步骤详情（v2.0） |
| GET | `/api/executions/{eid}/video` | 下载执行录屏视频 WebM（v2.8） |
| GET | `/api/executions/{eid}/report` | 执行报告 HTML（v2.4，v3.13 默认 inline 预览，`?download=1` 下载） |
| GET | `/api/batches/{batchId}/report` | 批次报告 HTML（v2.4，v3.13 默认 inline 预览，`?download=1` 下载） |
| POST | `/api/projects/{pid}/testcases/batch-execute` | 批量执行（v2.1） |
| GET | `/api/filesystem/dirs?path=` | 目录列表（path 为空返回根盘符，v3.1） |
| GET | `/api/batches/{batchId}` | 查询批次状态（v2.1，v3.12 增加 executions 别名） |
| GET | `/api/health` | 健康检查 |

