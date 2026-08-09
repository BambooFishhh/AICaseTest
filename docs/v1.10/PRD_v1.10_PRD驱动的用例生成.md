# PRD v1.10 - PRD 驱动的用例生成

## 版本信息
- **版本**: v1.10
- **基线**: v1.9
- **日期**: 2026-08-09
- **迭代主题**: PRD 驱动的用例生成 + 多 Agent 编排架构

## 背景与目标

### 当前问题
v1.0-v1.9 已完成用例形态、质量、版本管理、评审状态等打磨，但**从未触及用例的输入源**。当前 TestGeneratorAgent 只消费代码分析产物（stateMachines + backendResult），是"代码反推用例"范式，存在根本性缺陷：

1. **违背测试初衷**: 测试应验证"需求是否被正确实现"，现在变成"验证代码做了什么"
2. **漏测**: PRD 要求但代码未实现的功能测不到
3. **无法发现偏离**: 代码实现偏离 PRD 的缺陷测不出
4. **用例缺业务语义**: 难以让人类理解"为什么要测这个"

### 目标
1. 把 PRD 确立为用例生成的**主上下文**，代码分析降为**辅助上下文**
2. 建立**多 Agent 编排架构**: OrchestratorAgent（编排）+ PrdAgent（解析 PRD）+ TestGeneratorAgent（生成用例）
3. 支持三种 PRD 接入形式: 纯文本/Markdown、PDF 上传、在线文档链接

## 功能需求

### F1: PRD 数据模型
**描述**: Project 实体新增 PRD 相关字段

**字段定义**:
- `prdContent` (TEXT): PRD 文本内容（所有形式最终都解析为纯文本存这里）
- `prdSourceType` (VARCHAR(32)): 来源类型（text/pdf/link）
- `prdSourceRef` (VARCHAR(512)): 来源引用（文件名/URL）

### F2: PRD 接入形式
**描述**: 支持三种 PRD 接入方式

**接入方式**:
1. **纯文本/Markdown**: 前端编辑器直接存 prdContent
2. **PDF 上传**: 后端 PDFBox 解析为文本存 prdContent，sourceRef 存文件名
3. **在线文档链接**: 后端 Jsoup 抓取公开 URL 正文存 prdContent，sourceRef 存 URL

### F3: PrdAgent - PRD 解析
**描述**: 把 PRD 纯文本解析为结构化 PrdAnalysisResult

**PrdAnalysisResult 结构**:
```json
{
  "modules": [{"name": "模块名", "description": "描述"}],
  "requirements": [
    {
      "title": "需求标题",
      "description": "描述",
      "acceptanceCriteria": ["验收标准1", "验收标准2"],
      "priority": "P0"
    }
  ],
  "businessRules": [
    {"rule": "规则描述", "ruleType": "validation|constraint|workflow"}
  ],
  "stateFlows": [
    {
      "name": "状态机名",
      "states": ["状态1", "状态2"],
      "transitions": [{"from": "状态1", "to": "状态2", "trigger": "触发条件"}]
    }
  ],
  "entities": ["实体1", "实体2"]
}
```

**实现**: 用 LLM 解析（system prompt 约束输出 JSON），失败时返回空结果（不阻断生成）

### F4: OrchestratorAgent - 编排 Agent
**描述**: 显式编排 PrdAgent + 代码侧分析 → TestGeneratorAgent

**编排流程**:
1. 读 Project.prdContent
2. 若 prdContent 非空: PrdAgent.analyze(prdContent) → PrdAnalysisResult
3. 读 stateMachines + backendResult（现有代码侧逻辑移入）
4. testGeneratorAgent.generate(prdResult, stateMachines, backendResult, progressCallback)
5. 返回 testCases

### F5: TestGeneratorAgent 改造
**描述**: 接收双上下文（PRD 为主 + 代码为辅）

**签名变更**:
```java
// 旧: generate(stateMachines, backendResult, progressCallback)
// 新: generate(prdResult, stateMachines, backendResult, progressCallback)
```

**system prompt 重构**:
- 角色: 以需求为源生成测试用例的资深测试工程师
- 主上下文: PRD 的 requirements（每条需求生成正向/异常/边界用例）
- 辅助上下文: 代码侧的 endpoints/stateMachines（补充接口路径、状态字段、前置条件）
- 若 prdResult 为空: 退化为原代码驱动逻辑

**user prompt 结构**:
```
【需求上下文（主）】
{prdResult}

【代码上下文（辅）】
状态机: {stateMachines}
接口: {endpoints}
业务规则: {businessRules}

请以需求为纲生成测试用例，代码信息用于补充接口路径与前置状态。
```

### F6: PRD 管理接口
**描述**: 提供 PRD 的查询、更新、上传、抓取接口

**接口列表**:
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/projects/{id}/prd` | 查询 PRD |
| PUT | `/api/projects/{id}/prd` | 更新文本 PRD |
| POST | `/api/projects/{id}/prd/upload` | 上传 PDF |
| POST | `/api/projects/{id}/prd/fetch` | 抓取在线链接 |

### F7: 前端 PRD 面板
**描述**: 项目详情页新增 PRD 面板，支持三种接入形式切换

**功能**:
- 文本/Markdown 编辑器（直接编辑保存）
- PDF 上传（拖拽/点击上传，后端解析）
- 在线链接抓取（输入 URL，后端抓取正文）
- PRD 预览（查看当前 PRD 内容）
- 来源标识（显示当前 PRD 来源类型）

## 验收标准

1. **AC1**: 项目详情页可编辑 PRD 文本并保存，生成用例时 PRD 作为主上下文
2. **AC2**: 上传 PDF → 后端解析为文本存库 → 生成用例基于该文本
3. **AC3**: 填入公开 URL → 后端抓取正文存库 → 生成用例基于该文本
4. **AC4**: 生成时若 PRD 为空，退化为原代码驱动逻辑，不报错（向后兼容）
5. **AC5**: PrdAgent 解析失败时返回空结果，生成仍可进行（代码驱动降级）
6. **AC6**: 生成的用例 title/module 能反映 PRD 需求项（而非只反映状态机名）
7. **AC7**: 后端编译 BUILD SUCCESS；前端 `npm run build` 成功

## 风险与对策

| 风险 | 对策 |
|------|------|
| PRD 过长导致 LLM token 超限 | PrdAgent 解析时若超长先截断（保留前 N 字符），标注截断 |
| PDF 解析为乱码（扫描件/图片型 PDF） | 检测解析结果为空或过短时提示"PDF 无可提取文本（可能是扫描件）" |
| docs 链接抓不到（SPA/需认证） | Jsoup 抓取失败时返回明确错误，提示用户改用文本粘贴 |
| LLM 解析 PRD 输出非 JSON | 复用现有 extractJsonObject 容错；失败返回空 PrdAnalysisResult |
| PDFBox/Jsoup 依赖未在本地 m2 仓库 | 编译时联网下载；离线环境需预置 |

## 交付物清单

- [ ] `docs/v1.10/PRD_v1.10_PRD驱动的用例生成.md`
- [ ] `docs/v1.10/后端技术评审_v1.10.md`
- [ ] `docs/v1.10/前端技术评审_v1.10.md`
- [ ] 后端: Project 字段 / PrdAnalysisResult / PrdAgent / OrchestratorAgent / TestGeneratorAgent 改造 / ProjectController+Service PRD 接口 / pom 依赖
- [ ] 前端: api/project.js PRD 接口 / PrdPanel.vue / ProjectDetail.vue 接入
- [ ] CHANGELOG + README 更新
- [ ] Git 提交推送

## 范围说明

### In Scope
- PRD 数据模型与三种接入形式
- PrdAgent 解析 PRD 为结构化结果
- OrchestratorAgent 编排多 Agent
- TestGeneratorAgent 改造为 PRD 驱动
- 前端 PRD 面板与交互

### Out of Scope（留待后续版本）
- **前端代码分析 Agent（v1.11）**: 解析 Vue 路由/组件/表单，产出 DOM selector，为 v2.0 执行 Agent 的 dom_click 兜底提供可靠输入
- **AI 执行引擎（v2.0）**: Agent 自主调用 Skill + MCP 执行 Web 测试
- 飞书/腾讯文档等需 OAuth 认证的 docs 接入: 本期只做公开 URL 抓取
- PRD 版本管理: 本期 PRD 覆盖更新，不做历史版本（用例已有版本管理，PRD 侧后续再加）
