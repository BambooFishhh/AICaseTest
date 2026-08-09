# PRD v1.2 — 用例生成质量增强

**版本**: v1.2（迭代版本）
**基线**: v1.1（结构化可执行用例）
**日期**: 2026-08-09
**迭代主题**: 提升用例生成质量——分模块精准生成、去重、覆盖率度量、质量评分

---

## 一、迭代背景

### 1.1 v1.1 遗留问题

v1.1 让用例具备了 AI 可执行的结构，但生成质量本身仍有不足：

| 编号 | 问题 | 现状 | 影响 |
|------|------|------|------|
| Q1 | 一次性生成所有用例 | `generate()` 把全部 stateMachines + endpoints 一次性丢给 LLM | token 易超限、单次聚焦度低、单个失败导致整体回退规则生成 |
| Q2 | 用例重复 | 无去重逻辑 | 分模块生成后可能出现标题/场景重复的用例 |
| Q3 | 缺少覆盖率度量 | 无覆盖率统计 | 用户无法知道"状态转换覆盖了多少""接口覆盖了多少"，质量不可见 |
| Q4 | 缺少质量评分 | 用例无质量分 | 无法区分"高质量用例"和"低质量空壳用例" |

### 1.2 v1.2 目标

在保持 v1.1 结构化能力的基础上，提升生成内容质量并让质量**可度量、可视化**。

### 1.3 路线位置

```
v1.0 用例生成（自然语言）       ✅
v1.1 结构化可执行用例模型        ✅
v1.2 用例生成质量增强            ◀── 本次迭代
v1.3 AI 用例执行引擎             （未来）
v2.0 高可视化 + 高可用           （未来）
```

---

## 二、范围

### 2.1 In Scope

| 模块 | 改动 | 优先级 |
|------|------|--------|
| 后端-生成策略 | 分模块生成：按 stateMachine 逐个调用 LLM，合并结果 | P0 |
| 后端-去重 | 生成后基于标题相似度去重 | P0 |
| 后端-覆盖率 | 计算状态转换覆盖率、接口覆盖率，随列表响应返回 | P0 |
| 后端-质量评分 | 每个用例计算 qualityScore（结构完整度 0-100） | P0 |
| 前端-覆盖率面板 | 列表页展示覆盖率进度条（状态/接口） | P0 |
| 前端-质量评分 | 列表表格 + 详情卡展示质量分 | P0 |
| 文档 | PRD + 前后端技术评审 + CHANGELOG | P0 |

### 2.2 Out of Scope

- ❌ AI 实际执行用例（v1.3）
- ❌ 人工反馈循环/采纳率统计（v1.3，需持久化用户行为）
- ❌ 高可用（重试/监控）（v2.0）
- ❌ 状态机图谱可视化重构（v2.0）

---

## 三、功能详述

### 3.1 分模块生成策略

**现状**：`TestGeneratorAgent.generate(stateMachines, backendResult)` 一次性把所有状态机传给 LLM。

**v1.2 改进**：
- 遍历每个 stateMachine，单独调用 LLM 生成该模块的用例
- 每次只传入 1 个状态机 + 按模块匹配的相关 endpoints + 相关 businessRules
- 单个模块 LLM 失败时，仅该模块回退规则生成，不影响其他模块
- 最后合并所有模块的用例

**收益**：
- 单次 prompt 更聚焦，生成质量更高
- 避免 token 超限
- 单点失败不影响整体，容错性更好

### 3.2 用例去重

**策略**：生成合并后，按标题相似度去重
- 标题完全相同 → 保留首个
- 标题相似度 > 80%（基于字符重叠率）且模块相同 → 视为重复，保留结构更完整者（qualityScore 更高）

**实现**：在 `generate()` 合并结果后执行 `deduplicate(List<TestCase>)`

### 3.3 覆盖率度量

**指标定义**：

| 指标 | 计算方式 |
|------|----------|
| 状态转换覆盖率 | `已覆盖 transitions 数 / 总 transitions 数` |
| 接口覆盖率 | `已关联 endpoints 数 / 总 endpoints 数` |
| 用例类型分布 | positive/negative/boundary/data 计数（已有） |

**数据来源**：
- 总 transitions：该项目所有 StateMachine 的 transitions 汇总
- 已覆盖 transitions：用例 stateMachineRef.transitions 中出现的 from→to 对
- 总 endpoints：CodeAnalysis.backendResult.endpoints
- 已关联 endpoints：用例 apiEndpoints 中出现的 method+path

**返回方式**：扩展 `TestCaseListResponse` 增加 `coverage` 字段

```json
{
  "total": 24,
  "testCases": [...],
  "coverage": {
    "stateTransition": {"covered": 8, "total": 12, "rate": 0.67},
    "apiEndpoint": {"covered": 5, "total": 10, "rate": 0.5},
    "typeDistribution": {"positive": 10, "negative": 8, "boundary": 4, "data": 2}
  }
}
```

### 3.4 用例质量评分

**评分维度**（满分 100）：

| 维度 | 分值 | 判定 |
|------|------|------|
| 结构化步骤完整 | 30 | structuredSteps 非空且每步含 action+target+expected |
| 关联接口 | 20 | apiEndpoints 非空 |
| 测试数据 | 15 | testData 非空 |
| 执行提示 | 15 | executionHints.approach 存在 |
| 步骤数量 | 10 | structuredSteps 步骤数 >= 2 |
| 预期结果 | 10 | expectedResults 非空 |

**存储**：TestCase 实体新增 `qualityScore` 字段（Integer），生成时计算并持久化。

**展示**：
- 列表表格新增"质量"列（进度条/数值）
- 详情卡展示质量分

---

## 四、验收标准

| 编号 | 验收项 | 验证方式 |
|------|--------|----------|
| AC1 | 生成时按状态机逐个调用 LLM，单模块失败不影响其他 | 触发生成，检查日志与结果 |
| AC2 | 无标题重复的用例 | 检查生成结果 |
| AC3 | 列表响应包含 coverage 字段，数值正确 | 接口调用 |
| AC4 | 每个用例有 qualityScore（0-100） | 查看用例详情 |
| AC5 | 前端列表页展示覆盖率进度条 | 视觉验证 |
| AC6 | 前端列表表格展示质量分列 | 视觉验证 |
| AC7 | v1.1 已有用例（无 qualityScore）不报错 | 兼容验证 |
| AC8 | 后端编译通过，前端构建通过 | 构建 |

---

## 五、风险与对策

| 风星 | 对策 |
|------|------|
| 分模块生成增加 LLM 调用次数（耗时增加） | 异步生成已有，用户可接受；单次更聚焦反而可能更快返回 |
| 标题相似度计算性能 | 用例数量有限（百级），简单字符重叠率足够 |
| 覆盖率计算复杂 | 复用已有 stateMachines + codeAnalysis 数据 |

---

## 六、交付物清单

- [ ] `docs/v1.2/PRD_v1.2_用例生成质量增强.md`（本文档）
- [ ] `docs/v1.2/后端技术评审_v1.2.md`
- [ ] `docs/v1.2/前端技术评审_v1.2.md`
- [ ] 后端代码：TestGeneratorAgent / TestCaseService / TestCaseListResponse / TestCase / TestCaseDTO
- [ ] 前端代码：TestCaseList.vue / TestCaseCard.vue
- [ ] `docs/CHANGELOG.md` 更新
- [ ] GitHub 提交记录
