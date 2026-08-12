# PRD v3.13 — 结果输出与用例资产

**版本**：v3.13
**基线**：v3.12
**日期**：2026-08-12
**主题**：报告在线预览 + 恢复 JSON/CSV 导入导出与跨项目复制 + 生成聚焦类型真正生效

## 一、背景与痛点

1. 执行/批次报告只能下载 HTML 本地打开，无法在系统内直接预览
2. v3.9 移除了 JSON/CSV 导入导出与跨项目复制按钮，但后端接口与前端 api 封装都还在——用例资产无法进出系统
3. "聚焦类型"配置了但完全不生效（focusTypes 字段没有任何代码读取），属于"配置了没效果"的信任问题

## 二、范围

### In scope

- 报告在线预览（inline）+ 保留下载（download=1）
- 恢复用例导出（JSON/CSV）、导入 JSON、跨项目复制入口
- focusTypes 真正过滤生成结果（重新生成 + 追加生成 + 非流式生成）

### Out of scope

- 测试集/多环境（v3.15）
- 安全、并发、性能

## 三、功能详情

### 3.1 报告在线预览

- 后端报告接口新增 `download` 参数（默认 false → inline 预览）
- 执行结果页、批次结果页：新增"预览报告"，原下载按钮改为 `?download=1`
- 执行历史页"报告"按钮 → inline 预览

### 3.2 用例导入导出与复制

- 工具栏"导出"下拉：JSON / CSV（选中用例优先，未选中导出全部）
- "导入 JSON"按钮 + 文件选择 → `importTestCases`
- "复制到"按钮 + 目标项目选择对话框 → `copyToProject`
- 复用现有 api 封装，新增 blob 下载工具函数

### 3.3 聚焦类型生效

- `TestGeneratorAgent`：新增 `isFocusTypeAllowed`/`wrapFocusFilter`，generate 与 generateStreaming 均按 focusTypes 过滤结果；流式回调只推送被允许类型（SSE 与落库一致）
- 前端生成参数对话框提示语更新为"勾选后仅生成对应类型"

## 四、验收标准

1. 预览报告在浏览器内直接打开，下载仍可用
2. JSON/CSV 导出、JSON 导入、跨项目复制可用
3. 勾选聚焦类型后生成结果只含对应类型（流式过程与最终列表一致）
4. mvn compile BUILD SUCCESS；npm run build 成功
