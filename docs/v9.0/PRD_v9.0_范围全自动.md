# PRD v9.0 — 本期范围全自动

> 版本 v9.0，一旦确定尽量不要轻易改动。基线 v8.9.8。范围：范围链路自动化（对应计划书 12.15 范围链路补全的落地收口）。后端为主 + 前端 ScopeDrawer 交互调整。

## 一、背景与痛点

- 范围（Scope）创建依赖用户手动选基线、手动确认，流程分散往返成本高；
- 常规迭代的基线就是默认主干，每次手选是重复劳动；
- 范围接口清单被 G17 关键词过滤误杀，激活范围后进生成上下文的接口不全（"接口覆盖不全"的根因之一）。

## 二、范围与验收

| # | 内容 | 验收 |
|---|---|---|
| 1 | 分析完成自动锁定范围：`ScopeService.autoSyncAfterAnalysis`，分析双路径挂钩，重新分析=重建范围 | ScopeServiceAutoSyncTest 全绿 |
| 2 | 默认主干解析：`GitDiffService.detectDefaultBaseline`（origin/HEAD → master → main 三级探测） | GitDiffServiceTest 含克隆仓库优先级用例 |
| 3 | 范围接口全量进上下文：范围激活时绕过 G17 关键词过滤 | ScopeServiceFrontendMappingTest |
| 4 | 已确认范围放开条目增删；前端「本期范围」降级为查看入口 | ScopeDrawer 交互 |

## 三、功能细节

- **autoSyncAfterAnalysis**：分析完成（成功路径）后自动按默认主干 diff 识别并锁定范围；重新分析=重建范围（先清旧草稿），用户无感知获得最新范围。
- **detectDefaultBaseline**：`origin/HEAD` → `master` → `main` 顺序探测，不写死字面量；探测不到才回落手动选基线。
- **G17 顺序修复**：范围激活时 endpoint context 直接取范围目标集合（全量），不再经 G17 关键词过滤——此前 11/11 接口被误杀至只剩少数。
- **前端**：范围确认后「本期范围」抽屉降级为查看入口（放开增删），空范围禁用「确认锁定」并提示重算/手动添加。

## 四、验收标准

1. 后端新增单测全绿（GitDiffServiceTest 4 例 + ScopeServiceAutoSyncTest + ScopeServiceFrontendMappingTest 3 例）。
2. litemall 项目实测：分析完成即自动锁定 11/11 接口范围，生成上下文含全量接口。

## 五、交付物清单

ScopeService、GitDiffService、AnalysisService、ScopeSlicingService、ScopeItem 修改；ScopeDrawer.vue 调整；新增 GitDiffServiceTest、ScopeServiceAutoSyncTest、ScopeServiceFrontendMappingTest。
