# PRD v8.1 — 范围感知基础（Scope 基础设施与识别流水线）

> 版本：v8.1（2026-08-24）
> 背景：v8.x 系列「Scope-Aware 本期聚焦测试生成」三期迭代的第 1 期
> 系列规划：v8.1 范围基础设施 → v8.2 本期聚焦生成（切片/setup/block） → v8.3 覆盖率口径重构

## 1. 背景与问题

当前系统的用例生成与覆盖率均以"整个项目"为口径：

| 问题 | 影响 |
|---|---|
| 无法区分"本期需求代码"与"历史代码" | 生成的用例大量覆盖历史功能，稀释本期验收价值 |
| 覆盖率分母是全量接口/转换 | 数字好看但不能回答"本期需求测完了吗" |
| 历史逻辑只能靠 RAG 语义召回 | 召回不准即遗漏；范围圈定应是精确集合运算而非相似度问题 |

核心设计理念：**本期需求定义"测什么"（精确集合），历史代码定义"怎么测才不破坏"（上下文）**。

本期（v8.1）先落地范围的数据基础与识别流水线，不改生成行为（向后兼容），为 v8.2/v8.3 提供分母来源。

## 2. 目标

1. 新增「范围(Scope)」领域实体：一个项目可定义多期迭代范围，每期包含基线引用与范围内元素清单
2. **Git diff 自动识别**：基于基线分支/tag 计算变更文件集，映射到受影响接口（ADDED/MODIFIED）与受影响状态机（AFFECTED）
3. **LLM 辅助补充映射**：PRD ↔ 接口清单语义匹配，补齐 diff 遗漏项
4. **人工确认兜底**：自动结果必须经用户 confirm 后才成为有效范围；支持手动增删条目
5. Git 克隆策略改造：保留跨分支历史以支持 diff

## 3. 需求详述

### 3.1 数据模型

```
scope_definition（一期范围定义）
├── id(8位), project_id, name（如"2026-S35 迭代"）
├── baseline_ref        ← 分支/tag/commit（diff 起点）
├── head_ref            ← 默认 HEAD（预留）
├── status              ← draft / confirmed
├── changed_files       ← TEXT：diff 文件清单 JSON（追溯用）
└── created_at / updated_at

scope_item（范围内的元素）
├── id(8位), definition_id
├── item_type           ← ENDPOINT / STATE_MACHINE
├── item_ref            ← "GET /admin/order/list" 或状态机 id
├── change_kind         ← ADDED / MODIFIED / AFFECTED
├── origin              ← AUTO_DIFF / LLM_MAPPED / MANUAL
├── note                ← 来源说明（命中文件 / LLM 理由）
└── created_at
```

### 3.2 识别流水线（创建草稿时执行）

```
[1] 校验：sourcePath 为 Git 仓库（存在 .git），否则报错引导手动模式
[2] git diff --name-status <baseline>...HEAD（三点 diff = 自基线分叉后的变更）
    ├── 失败回退两点 diff；A→ADDED，M/T/R/C→MODIFIED，D 忽略
    └── 文件清单存入 definition.changed_files
[3] 文件→接口映射：CodeAnalysis.backendResult.endpoints[].file 与变更集做
    归一化后缀匹配（兼容子目录差异）；命中的文件在变更集内且分析结果存在该文件
    → ENDPOINT 条目（change_kind 按 git 状态映射）
[4] 状态机影响面：复用 applyEvidence 同款 from/to 匹配逻辑，
    将 backendResult.stateTransitions 证据 {field,from,to,method,file} 关联到各
    状态机的转换；证据文件 ∈ 变更集 → 该状态机标 AFFECTED
[5] LLM 补充映射（ScopeMappingAgent）：PRD 全文（截断）+ 接口清单（截断）
    → 返回 [{method,path,reason}]，去重后以 LLM_MAPPED/AFFECTED 入库；
    LLM 失败仅告警跳过，不阻断草稿创建
```

### 3.3 API 设计

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/projects/{projectId}/scope` | 定义列表（含条目计数） |
| POST | `/api/projects/{projectId}/scope` | 创建草稿 `{name, baselineRef}` 并执行识别流水线 |
| GET | `/api/projects/{projectId}/scope/git-refs` | 可用基线候选（本地分支/远端分支/tag/HEAD） |
| GET | `/.../scope/{definitionId}/items` | 条目列表 |
| POST | `/.../scope/{definitionId}/items` | 手动添加 `{itemType,itemRef,changeKind}` |
| DELETE | `/.../scope/{definitionId}/items/{itemId}` | 删除条目 |
| POST | `/.../scope/{definitionId}/recompute` | 重跑识别（保留 MANUAL 项，重建其余） |
| POST | `/.../scope/{definitionId}/confirm` | 确认锁定（≥1 条目） |
| DELETE | `/.../scope/{definitionId}` | 删除定义及条目 |

### 3.4 连带改造

- **GitCloneService**：`--depth 1` → `--filter=blob:none --no-single-branch`（partial clone：保留全部远端分支引用、不取文件内容，体积远小于完整克隆但支持跨基线 diff）
- **项目删除级联**：删除项目时清理 scope 两表
- **数据健康**：tableCounts 增加 scope 两表
- **备份导出**：ZIP 增加 scope.json

### 3.5 不做什么（后续版本）

- 不改生成链路（prompt/前置校验/phase 字段）——v8.2
- 不改覆盖率口径——v8.3
- OpenAPI 两版 diff 来源、方法级粒度——后续评估
- 未确认 scope 时生成行为与现状完全一致（向后兼容）

## 4. 验收标准

1. 对含 Git 仓库的项目创建草稿：返回 draft 定义 + ENDPOINT/STATE_MACHINE 条目，changed_files 非空
2. 非 Git 目录项目：创建报业务错误（提示手动模式），手动添加条目可用
3. confirm 后 status=confirmed；空条目 confirm 被拒绝
4. recompute 后 AUTO/LLM 条目重建、MANUAL 条目保留
5. 删除项目级联清理两表；DataHealth 计数正确；备份 ZIP 含 scope.json
6. `mvn compile` 通过、`npm run build` 通过

## 5. 影响范围

| 层 | 文件 | 改动 |
|---|---|---|
| 后端 | db/migration/mysql/V13__add_scope_tables.sql | 新表 |
| 后端 | entity/ScopeDefinition、ScopeItem + 两个 Repository | 新增 |
| 后端 | service/GitDiffService、ScopeService；agent/ScopeMappingAgent | 新增 |
| 后端 | controller/ScopeController | 新增 |
| 后端 | GitCloneService（克隆策略）、ProjectService（级联）、DataHealthService、BackupService | 修改 |
| 前端 | api/scope.js、views/ScopeReview.vue、router | 新增 |
| 前端 | views/ProjectDetail.vue | 加入口 |

## 6. 风险与权衡

- **LLM 误映射**：所有 auto/llm 结果仅入草稿，必须 confirm 才生效；条目可逐条删除
- **大仓库 diff 慢**：blob:none partial clone 控制；diff 仅文件级（方法级留后续）
- **file 字段子目录错位**（sourcePath 指向仓库子目录）：归一化后缀双向匹配兜底
- **非 Git 项目**：明确报错引导 + 手动标注路径完整可用，不做半自动猜测
