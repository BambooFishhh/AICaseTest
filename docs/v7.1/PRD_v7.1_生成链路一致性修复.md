# PRD v7.1 — 生成链路一致性修复

**日期**: 2026-08-22
**基线**: v7.0
**对应风险清单**: G1 / G2 / G3 / G5 / G11 / G14 / G15（生成链路 7 项，含 3 个 P0）
**改动范围**: 后端为主 + 前端小改（SSE 完成提示）

---

## 1. 背景与问题

v7.0 修复了执行链路的"结果不可信"。v7.1 聚焦**生成链路的"结果不一致"**——用户在 SSE 流里看到的用例、最终落库的用例、系统声称的降级状态，三者互相对不上：

| 编号 | 级别 | 问题 | 用户可感知现象 |
|---|---|---|---|
| G1 | P0 | 字符重叠判重不比较 type，阈值 0.8 过松 | "新增用户-正常" vs "新增用户-异常" 重叠 83% → 被判重复，**静默删一条**；high 密度引导正向/异常成对生成 → 系统性丢用例 |
| G2 | P0 | SSE 推送的是去重前/评审前/无选择器版本 | 推送 30 条、落库 20 条，"消失"无解释；complete 事件只有 total |
| G3 | P0 | 选择器补齐时 data 补 `{字段名: ""}` 空占位 | 必填字段被填空字符串 → 表单校验必败 → **正向用例被静默改成必败用例** |
| G5 | P1 | 代码驱动生成链（~700 行）v5.13 后成死代码；markDegraded 判 `rule_based` source 永不触发 | v6.5 的任务降级标记在生成主路径是**死逻辑**；死代码维护负担 |
| G11 | P2 | focusTypes 过滤后为空误报"未生成任何用例" | 实际生成了但被类型过滤删光，误导排查方向 |
| G14 | P2 | 全量生成只有标题规则去重，语义去重能力（Milvus）未启用 | 同语义不同标题的重复用例全保留；追加路径有三层去重，全量路径只有一层 |
| G15 | P3 | settings 解析失败被误报"请先添加 PRD 文档" | 配置损坏被引导去补 PRD，排查方向错误 |

## 2. 目标

1. **不误杀**：成对用例（正向/异常）不再因标题相似被去重删除（G1）
2. **不静默**：推送数与落库数的差异在 complete 事件中给出数量与原因分类（G2）
3. **不注入垃圾**：不再用空字符串占位破坏用例语义（G3）
4. **降级真实**：markDegraded 基于真实降级信号（轮次未收敛/评审 LLM 失败），删除死代码（G5）
5. **报错准确**：区分"未生成"与"类型过滤为空"（G11）、"未配 PRD"与"配置解析失败"（G15）
6. **能力对齐**：全量生成启用批内语义去重，与追加路径同等能力（G14）

## 3. 方案要点

### 3.1 G1 判重修复（TestGeneratorAgent.isDuplicate）
- 标题类判重（完全相同 / 子串包含 / 字符重叠）统一前置 **type 一致检查**
- 字符重叠阈值 0.8 → **0.9**
- 漏网真重复由 G14 批内语义去重兜底（分层防御：标题规则保守、语义规则收网）

### 3.2 G2 推送/落库一致性（新 GenerationReport 贯穿）
- `TestGeneratorAgent` 新增 `GenerationReport`：记录 generated / focusDropped / reviewDropped / dedupDropped / semanticDropped / finalCount / roundsNotConverged / reviewDegraded
- 经 `OrchestratorAgent` 透传到 `TestCaseService`，complete 事件携带 `{total, pushed, droppedTotal, dropped:{focusType,review,dedup,semantic,other}}`
- 前端 complete 提示改为"落库 X 条（流式推送 Y 条草稿，经评审/去重丢弃 Z 条）"，流式期间标题标注"草稿"

### 3.3 G3 空 data 占位移除
- enrichStructuredSteps 不再补 `{字段名: ""}`；分析器目前无默认值/示例值来源，宁缺勿错
- DOM 执行路径使用 inputValue（不受影响）；Agent 模式由 LLM 决定输入值（去掉空串干扰）

### 3.4 G5 死代码删除 + markDegraded 真实信号
- 删除 generateCodeDrivenCases / generateByLlmForStateMachine / generateByRulesForStateMachine / generateByEndpoints / buildPositiveTest / buildNegativeTest / buildBoundaryTest / buildStateMachineRef / buildForbiddenTransitions / matchEndpoints（约 700 行）
- markDegraded 判定改为：`report.roundsNotConverged || report.reviewDegraded`

### 3.5 G11 / G15 报错区分
- focusTypes 过滤后为空 → "已生成 N 条用例，但聚焦类型 [...] 过滤后为 0 条（请调整聚焦类型）"
- settings JSON 解析失败 → "项目配置解析失败：settings 不是合法 JSON..."（OrchestratorAgent 与 TestCaseService.hasPrd 两处）

### 3.6 G14 批内语义去重（SemanticService.deduplicateBatch）
- 全量路径多轮结果做**批内** embedding 余弦相似度去重（不对旧索引判重——旧索引即将整体替换）
- 阈值复用 Milvus duplicateThreshold；type 不同不判重（与 G1 原则一致）；保留 qualityScore 高者
- 成本说明：≤60 次 embed 调用，与既有落库后 indexCases 的全量 embed 同量级

## 4. 验收标准

1. 单测：不同 type 高重叠标题不再判重；同 type 重叠 ≥0.9 仍判重；focusTypes 过滤为空报"聚焦类型"错误；GenerationReport 各计数正确
2. 回归：既有 97 个测试全绿；TestGeneratorAgentTest 两条既有用例语义不变
3. complete 事件含 pushed/droppedTotal/dropped 分类；前端提示展示丢弃明细
4. grep 确认 rule_based 生成路径无残留引用；markDegraded 只在真实降级时触发

## 5. 风险与权衡

- **阈值 0.9 可能漏掉少量真重复** → 由 G14 语义去重兜底；且"误杀"（丢用例）比"漏杀"（多一条重复）对准确率伤害更大，符合"准确率优先"原则
- **批内语义去重增加 embed 成本** → 与既有 indexCases 全量 embed 同量级，无净增放大；Milvus/embedding 未配置时自动跳过（与追加路径降级行为一致）
- **complete 事件结构变化** → 前端 streamGenerate 的 onComplete 从传 total 改为传完整对象，TestCaseList.vue 同步更新（唯一调用方）
