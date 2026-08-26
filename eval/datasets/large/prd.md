# 需求 C：内容管理平台（大型样例）

## 背景

面向多租户的内容管理平台（CMS），覆盖租户隔离、内容生命周期、审核流、全文检索与操作审计。

## 功能需求

### FR-1 租户管理

ADMIN 创建/停用租户；租户数据按 tenant_id 行级隔离，任何跨租户读写返回 40300。

### FR-2 内容创建与编辑

作者创建草稿（title 必填 ≤200 字，body ≤100k 字）；编辑保存生成新版本号；版本历史可回滚。

### FR-3 提交审核

草稿提交后进入 PENDING_REVIEW；审核人通过→PUBLISHED，驳回→DRAFT 并附意见；提交人不能自审。

### FR-4 全文检索

按标题 + 正文检索本人租户已发布内容；关键词高亮；分页 20 条/页。

### FR-5 定时发布

内容可设置未来发布时间，到点自动 PUBLISHED；时间早于当前立即发布。

### FR-6 回收站

删除进入回收站保留 30 天，可恢复；彻底删除需 ADMIN 二次确认。

### FR-7 审计日志

登录、发布、删除、回滚四类操作写审计日志（操作者/IP/时间/对象），仅 ADMIN 可查。

### FR-8 并发编辑保护

两人同时编辑同一内容，后保存者收到冲突提示并展示差异，不允许静默覆盖。

## 业务规则

- 免费租户存储上限 1GB，超限禁止新建内容
- 敏感词命中时提交审核被拦截并列出命中的词
- 每租户审核人至少配置 1 名，否则提交审核返回 40020
- 回收站恢复时若原栏目已删除则恢复至"未分类"

## 状态流

draft → pending_review → published；pending_review → draft（驳回）；published → recycled → deleted

## 接口清单

- POST /api/tenants
- PUT /api/tenants/{id}/status
- POST /api/contents
- PUT /api/contents/{id}
- GET /api/contents/{id}/versions
- POST /api/contents/{id}/rollback
- POST /api/contents/{id}/submit
- POST /api/reviews/{contentId}/approve
- POST /api/reviews/{contentId}/reject
- GET /api/search?q=
- POST /api/contents/{id}/schedule
- DELETE /api/contents/{id}
- POST /api/recycle-bin/{id}/restore
- GET /api/audit-logs
