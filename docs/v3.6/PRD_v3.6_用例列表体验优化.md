# PRD v3.6 — 用例列表体验优化

> 基线: v3.5 | 主题: 追加生成闪烁修复 + 列表信息增强 + 手动添加用例

## 一、迭代背景

用户在实际使用中反馈三个问题：

1. **追加生成时原有用例先消失再出现**：追加生成期间，`displayTestCases` 仅返回 `streamedCases`（新用例），已有用例被隐藏；生成完成后 `loadList()` 重新加载，造成视觉闪烁。
2. **用例列表只有标题**：el-table 仅展示 id、title、module、type、priority、quality、review 列，缺少前置条件、步骤、预期结果等核心内容，用户必须点击详情才能看到完整用例。
3. **不支持手动添加用例**：无"新增用例"按钮，无 POST 创建端点，TestCaseCard 不支持创建模式。

## 二、范围

### In Scope

| 编号 | 功能 | 说明 |
|---|---|---|
| F1 | 追加生成不闪烁 | 流式期间合并展示已有用例 + 新用例 |
| F2 | 列表展示前置条件/步骤/预期结果 | el-table 可展开行，展示完整用例内容 |
| F3 | 手动添加用例 | 新增按钮 + 创建模式 + POST API |

### Out of Scope

- 重新生成的闪烁问题（重新生成会清空旧用例，闪烁是预期行为）
- 列表列的自定义配置（后续迭代）
- 批量导入用例的 UI 优化

## 三、功能详情

### F1: 追加生成不闪烁

**现状**：`displayTestCases` 在 `streaming=true` 时仅返回 `streamedCases.value`，已有用例消失。

**方案**：追加生成模式下，`displayTestCases` 合并 `testCases.value`（已有）+ `streamedCases.value`（新增），新旧用例同时可见。新用例插入顶部，已有用例保持原位。

**判断逻辑**：
- `streaming=true` && `currentGenMode='append'` → 返回 `[...streamedCases, ...testCases]`
- `streaming=true` && `currentGenMode='regenerate'` → 返回 `streamedCases`（重新生成时旧用例应被替换）
- `streaming=false` → 返回 `testCases`（正常分页）

### F2: 列表展示前置条件/步骤/预期结果

**现状**：el-table 仅 7 列（id、title、module、type、priority、quality、review），无展开行。

**方案**：使用 el-table 的 `type="expand"` 展开行，点击行展开显示：
- 前置条件（有序列表）
- 测试步骤（有序列表）
- 预期结果（有序列表）

展开行内容紧凑展示，最多显示 3 条，超出显示"查看全部"按钮跳转详情。

### F3: 手动添加用例

**现状**：无创建入口，TestCaseCard 仅支持查看/编辑模式。

**方案**：
1. **后端**：新增 `POST /api/projects/{projectId}/testcases` 端点，接收 `CreateTestCaseRequest`，自动分配 TC 编号
2. **前端 API**：新增 `createTestCase(projectId, data)` 函数
3. **前端 UI**：工具栏新增"新增用例"按钮，点击后打开 TestCaseCard 创建模式（空表单 + 编辑模式）
4. **TestCaseCard**：新增 `mode` prop，`mode='create'` 时直接进入编辑模式，标题显示"新增用例"，保存时调用 create API

## 四、验收标准

| 编号 | 验收项 |
|---|---|
| AC1 | 追加生成时已有用例不消失，新用例实时插入顶部 |
| AC2 | 重新生成时旧用例被替换（行为不变） |
| AC3 | 列表行可展开，显示前置条件、步骤、预期结果 |
| AC4 | 展开行内容为空时显示"无" |
| AC5 | 工具栏有"新增用例"按钮 |
| AC6 | 点击新增按钮打开空表单对话框 |
| AC7 | 填写后保存成功，新用例出现在列表中 |
| AC8 | 新用例自动分配 TC-XXX 编号 |

## 五、风险与缓解

| 风险 | 缓解 |
|---|---|
| 展开行影响表格性能 | 前端分页已限制每页最多 100 条 |
| 创建模式与编辑模式代码复用 | TestCaseCard 已有完整编辑表单，仅需初始化空数据 |

## 六、交付物清单

- [ ] 后端: TestCaseController 新增 POST 端点
- [ ] 后端: TestCaseService 新增 createTestCase 方法
- [ ] 后端: CreateTestCaseRequest DTO
- [ ] 前端: testcase.js 新增 createTestCase API
- [ ] 前端: TestCaseList.vue 修复 displayTestCases + 展开行 + 新增按钮
- [ ] 前端: TestCaseCard.vue 支持创建模式
- [ ] 文档: CHANGELOG + README 更新
