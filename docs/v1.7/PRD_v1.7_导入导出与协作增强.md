# PRD v1.7 — 用例导入导出与协作增强

**版本**: v1.7
**基线**: v1.6
**日期**: 2026-08-09
**负责人**: AI 产品经理

---

## 一、迭代背景与痛点

v1.0–v1.6 已完成用例生成、结构化、质量、体验、可视化、高可用建设，用例在系统内可生成/编辑/查看/XMind 导出。但存在协作与迁移痛点：

1. **不可回灌**：XMind 导出仅用于查看，无法把外部编辑结果导回系统
2. **不可离线协作**：团队成员无法用 Excel/文本工具离线评审、批量编辑用例
3. **不可跨项目迁移**：相似项目的用例无法复用，需重新生成
4. **无备份**：用例数据无结构化导出备份手段（仅 H2 数据库文件）

## 二、目标

让测试用例"可导出回灌、可离线编辑、可跨项目迁移"，提升协作与数据可移植性。

## 三、范围

### In Scope
1. **JSON 导出**：全字段导出，可作为备份/迁移载体，支持导出全部或选中用例
2. **JSON 导入**：导入 JSON 文件，校验格式，重新生成 ID 回灌到当前项目
3. **CSV 导出**：导出关键字段，便于 Excel 查看/离线评审
4. **跨项目用例复制**：把选中用例复制到其他项目（重生成 ID）

### Out of Scope
- Excel xlsx 双向导入（CSV 导出已满足查看需求，xlsx 导入复杂度高，留待后续）
- 用例版本历史/快照
- 用例模板库
- AI 执行引擎（v2.0）

## 四、功能详情

### 4.1 JSON 导出
- 接口：`GET /api/projects/{projectId}/testcases/export?format=json&ids=`
- `ids` 可选，不传导出全部，传则导出选中
- 返回 `application/json` 文件下载，文件名 `{projectName}_testcases_{timestamp}.json`
- 内容为 `TestCaseDTO` 数组的 JSON

### 4.2 JSON 导入
- 接口：`POST /api/projects/{projectId}/testcases/import` (multipart/form-data，字段 `file`)
- 解析 JSON 数组，逐条校验必需字段（title/type）
- 重新生成 ID（`TC-001` 起按现有最大编号续编），`projectId` 设为当前项目，`source="imported"`
- 返回 `{imported: N, skipped: M, errors: [...]}`

### 4.3 CSV 导出
- 接口：`GET /api/projects/{projectId}/testcases/export?format=csv&ids=`
- 导出关键字段：id,title,module,type,priority,preconditions(分号分隔),steps(分号),expectedResults(分号)
- 返回 `text/csv` 文件下载，UTF-8 BOM 头确保 Excel 中文不乱码
- 字段含逗号/换行需用双引号包裹（标准 CSV 转义）

### 4.4 跨项目复制
- 接口：`POST /api/projects/{projectId}/testcases/copy-to`，body `{ids: [...], targetProjectId: "..."}`
- 校验目标项目存在
- 复制选中用例到目标项目，重生成 ID，`source="copied"`
- 返回 `{copied: N}`

## 五、验收标准

1. 导出全部用例为 JSON，再导入回同一项目，用例数量翻倍（ID 不冲突）
2. 导出选中用例为 JSON，导入到另一项目，目标项目出现这些用例
3. CSV 导出文件用 Excel 打开中文正常，字段对齐
4. 跨项目复制后，目标项目有用例，源项目用例不受影响
5. 导入格式错误的 JSON 返回清晰错误提示，不部分写入
6. 后端编译通过，前端构建通过

## 六、风险与缓解

| 风险 | 缓解 |
|------|------|
| 导入 JSON 格式错误 | 逐条校验 + try-catch，整批失败回滚不写入 |
| ID 冲突 | 导入/复制时一律重新生成 ID，不使用原 ID |
| 大数据量导出性能 | 当前规模（数百条）直接序列化无压力；预留流式优化方向 |
| CSV 字段含特殊字符 | 标准 CSV 转义 + UTF-8 BOM |

## 七、交付物清单

- [ ] 后端：TestCaseController 新增 export/import/copy-to 接口
- [ ] 后端：TestCaseService 新增导出/导入/复制方法
- [ ] 后端：新增 CsvExporter 工具类
- [ ] 前端：TestCaseList 新增导出(JSON/CSV)/导入/复制到按钮
- [ ] 前端：导入文件上传交互
- [ ] 文档：PRD + 前后端技术评审 + CHANGELOG + README 更新
