# PRD v3.14 — 冷启动与工程基础

**版本**：v3.14
**基线**：v3.13
**日期**：2026-08-12
**主题**：内置示例 PRD 降低上手门槛 + 最小单元测试与 CI 构建门禁

## 一、背景与痛点

1. 新用户创建项目后没有 PRD 就没有产出，且不知道 PRD 怎么写，冷启动门槛高
2. 前后端零自动化测试、无 CI，v3.x 已迭代 13 版全靠手测，回归风险累积

## 二、范围

### In scope

- 内置示例 PRD（电商订单系统），PRD 面板"使用示例"一键载入
- 后端最小单元测试：CSV 导出、XMind 生成/解析 round-trip
- GitHub Actions CI：后端 mvn test + 前端 npm build 门禁

### Out of scope

- 示例项目（一键创建带数据项目）留待后续
- 安全、并发、性能

## 三、功能详情

### 3.1 示例 PRD

- `frontend/src/assets/samples/order-prd.md`：完整示例（背景/范围/模块状态/接口/业务规则/验收标准）
- PrdPanel 文本编辑区新增"使用示例"按钮：载入内容到编辑器，用户确认后保存

### 3.2 单元测试

- `CsvExporterTest`：表头、行内容、UTF-8 BOM、列表拼接
- `XmindServiceTest`：generateXmind → parseXmind round-trip 标题保留

### 3.3 CI

- `.github/workflows/ci.yml`：main 分支 push/PR 触发，后端测试 + 前端构建

## 四、验收标准

1. "使用示例"可载入示例 PRD 并可保存、生成
2. `mvn test` 全部通过
3. CI 配置对 push/PR 生效（语法正确）
4. npm run build 成功
