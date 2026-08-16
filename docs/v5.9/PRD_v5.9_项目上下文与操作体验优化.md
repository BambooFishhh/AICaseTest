# PRD v5.9 — 项目上下文与操作体验优化

**版本**: v5.9
**基线**: v5.8 / vP5
**日期**: 2026-08-16
**主题**: 创建后 Cookie 可编辑、项目详情操作上移、PRD 面板改版并支持额外 Prompt 与多上下文文档

---

## 一、迭代背景与痛点

1. 项目创建时可以在"更多"里配置执行 Cookie，但创建后没有任何入口修改；执行环境变化后只能重建项目或改数据库。
2. 项目详情页的操作区位于页面最底部，PRD 文档又很高，用户每次都要滚到底部才能执行"开始分析 / 生成用例"等高频操作。
3. PRD 是生成用例的必填主上下文，但用户可能还想补充额外的生成提示词、接口文档、业务说明等上下文；目前只能把额外内容拼进 PRD，或改代码。
4. PRD 面板默认展开一个大编辑区/大预览，占用大量首屏空间，影响其他信息查看。

## 二、范围

### In scope

- 项目执行 Cookie 创建后仍可查看和修改：
  - 后端新增 `GET / PUT /api/projects/{projectId}/execution-cookies`
  - 前端项目详情页新增"Cookie 配置"弹窗
- 项目详情"操作"区上移到 PRD 需求文档上方，只读提示同步上移
- PRD 面板改版为紧凑模式：
  - 有 PRD 时默认只展示摘要，编辑时再展开编辑器
  - 编辑器高度降低
  - 新增"额外 Prompt"文本框
  - 新增"上下文文档"列表，支持多篇文档（标题 + 内容）
- 生成链路注入额外上下文：
  - 额外 Prompt 作为生成提示词补充
  - 上下文文档以文档数组形式注入 PRD 驱动上下文

### Out of scope

- 多个 PRD 作为独立实体（本版为 1 个主 PRD + N 个额外上下文文档）
- 上下文文档的语义索引 / RAG 召回（保留给后续版本）
- v6 主题规划
- 执行目标按用例模块自动切换

## 三、功能详情

### 3.1 执行 Cookie 可编辑

#### 后端

- 新增 `GET /api/projects/{projectId}/execution-cookies`
  - 返回 `{ cookies: [...] }`，从 `Project.settings.executionCookies` 读取
  - 只读成员可查看，写操作需要 OPERATE/OWNER
- 新增 `PUT /api/projects/{projectId}/execution-cookies`
  - Body：`{ cookies: [{ name, value, url|domain }] }`
  - 覆盖保存到 `settings.executionCookies`

#### 前端

- 项目详情页操作区新增"Cookie 配置"按钮（仅可操作成员可见）
- 弹窗内以行编辑表单维护 Cookie：名称、值、域名/URL，支持添加、删除、保存
- 保存后刷新项目，执行链路立即使用新 Cookie

### 3.2 项目详情操作区上移

- 页面顺序调整为：
  1. 页头
  2. 流程步骤条
  3. 基本信息
  4. 操作区（主线操作 + 查看 + Cookie 配置）
  5. 轮询状态提示
  6. PRD 需求文档
  7. 其余内容
- 只读提示跟随操作区放在 PRD 上方，避免只读用户找不到提示

### 3.3 PRD 面板改版

#### 紧凑展示

- 有 PRD 内容时，默认显示：
  - 来源标签、字数、来源文件/URL
  - PRD 摘要（截断渲染）
  - "编辑 PRD"按钮展开编辑器
- 无 PRD 时保持原来源切换和编辑器，引导用户先填写必填 PRD

#### 额外 Prompt

- 新增"额外 Prompt"文本域，2-4 行可伸缩
- 保存后写入项目上下文，生成时作为补充指令注入 LLM prompt

#### 上下文文档

- 新增"上下文文档"列表：
  - 每篇文档包含 `id`、`title`、`content`
  - 支持新增、编辑、删除
- 文档内容作为 `contextDocs` 数组注入生成上下文

### 3.4 生成链路注入

- `OrchestratorAgent` 从 `Project.settings` 读取 `extraPrompt`、`contextDocs`
- 注入到 `PrdAnalysisResult` 新增字段
- `TestGeneratorAgent.generateByLlmWithPrd` 将两字段写入 LLM context
- 后端保留旧 `GET/PUT /prd` 接口，`PrdPanel` 改走新的 `/context` 聚合接口

## 四、验收标准

1. 创建项目后，在项目详情操作区可打开 Cookie 配置弹窗，保存后重新进入仍能看到
2. 后端执行时注入的是修改后的 Cookie
3. 项目详情操作区位于 PRD 上方，页面首屏不再需要滚动到底部
4. PRD 有内容时默认只显示摘要，点击编辑才展开
5. 额外 Prompt 与多篇上下文文档可保存并重新加载
6. 生成用例时，LLM 上下文包含额外 Prompt 与上下文文档内容
7. `mvn compile` BUILD SUCCESS；`npm run build` 成功

## 五、风险与规避

| 风险 | 影响 | 规避 |
|---|---|---|
| Cookie 修改后未刷新执行链路 | 仍用旧 Cookie | 保存后刷新项目对象，后端每次执行实时读取 settings |
| PRD 面板改版影响已有项目 | 旧项目无法编辑 PRD | 保留原 PRD 接口与字段，前端兼容空/旧数据 |
| 上下文文档过大撑爆 prompt | 生成失败或 token 超限 | 前端提示长度，后端按文档数组传入并截断标题/内容 |
| 操作区上移导致布局拥挤 | 视觉不佳 | 操作区保持两列卡片，内容紧凑 |

## 六、交付物清单

- [x] PRD v5.9
- [x] 后端技术评审 v5.9
- [x] 前端技术评审 v5.9
- [ ] 后端：Cookie 接口 + Context 接口 + 生成注入
- [ ] 前端：项目详情布局 + Cookie 弹窗 + PRD 面板改版
- [ ] CHANGELOG / README 更新
- [ ] 编译/构建验证 + 提交推送
