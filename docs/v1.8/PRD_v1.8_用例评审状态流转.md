# PRD v1.8 — 用例评审状态流转

**版本**: v1.8
**基线**: v1.7
**日期**: 2026-08-09
**负责人**: AI 产品经理

---

## 一、迭代背景与痛点

v1.0–v1.7 已完成用例生成、结构化、质量、体验、可视化、高可用、导入导出。但用例缺乏生命周期管理：

1. **无评审状态**：所有用例混在一起，无法区分"草稿/已评审/已批准/已拒绝"
2. **团队协作无序**：评审人不知哪些用例已评审、哪些待处理
3. **执行无准入**：未来 AI 执行（v2.0）无法基于状态筛选"只跑已批准用例"

## 二、目标

为用例引入评审状态流转，让用例有明确生命周期，支持批量评审操作与状态筛选，为 v2.0 AI 执行提供"只执行已批准用例"的准入基础。

## 三、范围

### In Scope
1. **reviewStatus 字段**：TestCase 新增字段，默认 `draft`，可选 `draft`/`reviewed`/`approved`/`rejected`
2. **批量改状态**：接口支持批量设置评审状态，可选记录评审人
3. **列表筛选**：listTestCases 支持按 reviewStatus 筛选
4. **前端展示与操作**：表格新增状态列（彩色 tag）、筛选下拉、批量改状态按钮

### Out of Scope
- 评审评论/讨论流（复杂度高，留待后续）
- 评审人权限控制（系统无认证，不做权限校验）
- 状态流转规则强校验（如 draft 不能直接 approved）——允许任意流转，保持简单
- 状态变更历史审计

## 四、功能详情

### 4.1 reviewStatus 字段
- TestCase entity 新增 `review_status` 列，默认 `draft`
- 可选值：`draft`(草稿) / `reviewed`(已评审) / `approved`(已批准) / `rejected`(已拒绝)
- 重新生成用例时新用例默认 `draft`（需重新评审）
- 历史用例（字段为 null）按 `draft` 兼容

### 4.2 批量改状态接口
- `POST /api/projects/{projectId}/testcases/review`
- body: `{ ids: [...], status: "approved", reviewer: "张三"(可选) }`
- 校验 status 合法性，更新选中用例的 reviewStatus
- 返回 `{ updated: N }`

### 4.3 列表筛选
- `GET /testcases` 新增 `reviewStatus` 查询参数
- DTO 透传 reviewStatus

### 4.4 前端
- 表格新增"评审状态"列：draft=info灰 / reviewed=warning黄 / approved=success绿 / rejected=danger红
- 筛选区新增状态下拉
- header 新增批量操作下拉菜单（标记为已评审/已批准/已拒绝），依赖选中行

## 五、验收标准

1. 新生成用例 reviewStatus=draft
2. 选中多条用例批量改为 approved，状态列变绿
3. 按状态筛选能正确过滤
4. 历史用例（无字段）默认显示 draft
5. 重新生成后新用例为 draft
6. 后端编译通过，前端构建通过

## 六、风险与缓解

| 风险 | 缓解 |
|------|------|
| 历史数据 reviewStatus 为 null | DTO 兜底返回 draft；筛选 null 视为 draft |
| 重新生成覆盖状态 | 设计如此：新用例需重新评审；确认提示已有（v1.3） |
| 任意流转可能误操作 | 批量操作有数量提示，状态可再次修改 |

## 七、交付物清单

- [ ] 后端：TestCase entity 新增 reviewStatus
- [ ] 后端：TestCaseDTO 透传
- [ ] 后端：TestCaseController 新增 /review 接口
- [ ] 后端：TestCaseService 新增 batchUpdateReviewStatus + listTestCases 筛选
- [ ] 后端：新增 ReviewRequest DTO
- [ ] 前端：TestCaseList 状态列 + 筛选 + 批量操作下拉
- [ ] 文档：PRD + 前后端技术评审 + CHANGELOG + README
