# PRD v8.3 — 覆盖率口径重构

> 版本：v8.3（2026-08-24）
> 前置依赖：v8.2 本期聚焦生成（切片服务、生成收敛、blocked 语义）
> 系列定位：Scope-Aware 三期收官——覆盖率全面切换本期分母，全量口径彻底移除

## 1. 背景与问题

用户已明确决策：**生成的用例只聚焦本期代码，全量覆盖率视图彻底移除**。当前覆盖率仍是全项目口径：

- 分母 = 全部状态机转换 + 全部分析出的接口，"本期测完了吗"无法回答
- 数字虚高：历史功能覆盖撑起百分比，稀释本期验收信号
- 未确认范围的项目显示误导性数字而非引导

## 2. 目标

1. **单一口径=本期范围**：状态转换覆盖率分母 = 已确认范围内各状态机的本期目标转换；接口覆盖率分母 = 范围内目标接口
2. **无已确认范围不再出数字**：返回 `scoped:false` 引导态，前端提示先建范围
3. **影响面可见**：范围内受波及（AFFECTED）条目清单透出，作为回归关注点参考
4. **历史上下文不进分子分母**：范围内状态机的历史转换仅展示（inScope=false），不参与统计
5. 全量口径相关代码路径移除

## 3. 需求详述

### 3.1 覆盖矩阵（GET /coverage/matrix）

```
无 confirmed 范围 → { scoped:false, message:"请先创建并确认本期范围" }
有 confirmed 范围 →
{
  scoped: true,
  scope: { definitionId, name, baselineRef },
  stateMachines: [ 仅范围内 SM；每个 transition 附 inScope(bool)；
                   统计只对 inScope=true 的转换计算 planned/executed（沿用 v7.8 双栏逻辑）],
  endpoints:     [ 分母=scope 目标接口；covered 口径不变 ],
  summary: { ...现有双栏字段, 分母均为本期集合 },
  affectedItems: [ origin/change_kind=AFFECTED 的范围条目清单 ]
}
```

### 3.2 未覆盖接口清单（GET /coverage/uncovered-endpoints）

分母切换为 scope 目标接口；无范围时同样返回引导态。

### 3.3 用例列表内联覆盖率（TestCaseService.calculateCoverage / buildCoverageForReview）

同口径收敛；无范围时 rates 置 0 并带 scoped=false（前端显示"—"+提示）。

### 3.4 Dashboard 项目覆盖率（StatsController）

逐项目按新口径计算；未建范围的项目 rate 显示"—"（前端处理 null）。

## 4. 验收标准

1. 有 confirmed 范围：矩阵仅含范围内 SM 与接口，summary 分母正确，affectedItems 非空时可见
2. 无 confirmed 范围：matrix/uncovered-endpoints 返回引导态；用例列表统计卡显示占位
3. 历史转换（inScope=false）不改变任何 rate
4. `mvn test` 全绿、`npm run build` 通过

## 5. 影响范围

| 层 | 文件 |
|---|---|
| 后端 | CoverageService、TestCaseService、StatsController |
| 前端 | CoverageMatrix.vue、StateMachineOverview.vue、TestCaseList.vue、Dashboard.vue |

## 6. 风险与权衡

- **破坏性变更**：全量口径数字消失——用户已在方案阶段明确选择"彻底移除"
- **评审链路依赖 buildCoverageForReview**：同步切口径后 AI 评审的覆盖建议也只针对本期项，语义一致
