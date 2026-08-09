# PRD v1.4 — 生成质量增强II & 批量操作

**版本**: v1.4（迭代版本）
**基线**: v1.3（用例体验增强）
**日期**: 2026-08-09
**迭代主题**: LLM prompt 深度优化（具体字段值/边界值/few-shot）+ LLM 重试机制 + 批量操作

---

## 一、迭代背景

### 1.1 痛点分析

v1.3 补齐了用例体验短板，但生成质量本身仍有核心问题：

| 编号 | 痛点 | 现状 | 影响 |
|------|------|------|------|
| P1 | LLM prompt 太粗糙 | systemPrompt 是一长串字段说明拼接，无示例、无具体测试数据指导 | 生成内容泛化，缺少具体字段值（如 `orderAmount: -1`）和边界值组合 |
| P2 | 无 few-shot 引导 | LLM 不知道"好用例"长什么样 | 输出质量不稳定，结构化步骤经常缺少 target/expected |
| P3 | LLM 零重试 | `LlmService.chat()` 任何异常直接抛出 | 网络抖动/限流即触发规则回退，生成质量骤降 |
| P4 | 无 case 数量引导 | prompt 未说明每类应生成几条 | LLM 自行决定，经常只生成 1-2 条就停 |
| P5 | 无批量操作 | 前端表格无多选、无批量删除 | 用户逐条操作效率低 |
| P6 | 无批量导出选中 | 只能全量导出 XMind | 无法只导出关注的用例子集 |

### 1.2 v1.4 目标

1. **Prompt 深度优化**：让 LLM 生成有具体字段值、边界值组合、完整结构化步骤的高质量用例
2. **LLM 重试**：网络抖动/限流时自动重试，减少不必要的规则回退
3. **批量操作**：多选删除、批量导出选中用例

### 1.3 路线位置

```
v1.0 用例生成（自然语言）       ✅
v1.1 结构化可执行用例模型        ✅
v1.2 用例生成质量增强            ✅
v1.3 用例体验增强                ✅
v1.4 生成质量增强II & 批量操作    ◀── 本次迭代
v1.5 可视化增强                  （未来）
v2.0 AI 用例执行引擎             （未来）
```

---

## 二、范围

### 2.1 In Scope

| 编号 | 改动 | 优先级 |
|------|------|--------|
| F1 | LLM prompt 深度优化（结构化分段 + 具体字段值指导 + case 数量引导） | P0 |
| F2 | Few-shot 示例注入（1 个正向 + 1 个异常示例） | P0 |
| F3 | LLM 重试机制（指数退避，最多 3 次） | P0 |
| F4 | 前端表格多选 + 批量删除 | P0 |
| F5 | 批量导出选中用例为 XMind | P1 |
| F6 | 文档：PRD + 前后端技术评审 + CHANGELOG + README | P0 |

### 2.2 Out of Scope

- ❌ 状态机图谱可视化（v1.5）
- ❌ 前端 chunk 拆分（v1.5）
- ❌ AI 执行（v2.0）

---

## 三、功能详述

### 3.1 LLM Prompt 深度优化（F1）

**现状**：systemPrompt 是单行拼接字符串：

```
你是测试用例生成专家。根据提供的状态机信息和后端接口信息，生成全面的、AI可执行的测试用例。
请返回JSON数组，每个测试用例包含：title...（一长串字段说明）
```

**v1.4**：重构为结构化分段 prompt：

```
# 角色
你是资深测试工程师，擅长生成结构化、AI 可执行的测试用例。

# 任务
根据以下状态机和接口信息，为每个状态转换生成测试用例。

# 生成要求
## 数量引导
- 正向用例（positive）：每个合法状态转换至少 1 条
- 异常用例（negative）：每个状态转换至少 1 条非法输入/非法转换
- 边界值用例（boundary）：每个涉及数值/长度字段的至少 2 条（上界+下界）
- 数据驱动用例（data）：如有多参数组合场景至少 1 条

## 测试数据要求
- testData 必须包含具体字段值，不能为空对象 {}
- 数值字段：填入真实值和边界值（如 amount: 0, amount: -1, amount: 99999999）
- 字符串字段：填入正常值、空字符串、超长字符串（256字符）
- 枚举字段：填入合法值和非法枚举值
- 必填字段：测试缺失该字段的情况

## structuredSteps 要求
- 每步的 target 必须是具体操作目标（如 "POST /api/order/create"），不能为空
- 每步的 expected 必须是可验证的具体结果，不能为空
- api_call 类型步骤的 data 必须包含该步骤的输入参数

## stateMachineRef 要求
- transitions 数组必须包含本用例测试的状态转换
- forbiddenTransitions 仅在 negative 类型用例中填写

# 输出格式
返回 JSON 数组，字段说明：
- title: 用例标题（简洁，含测试目标）
- module: 所属模块
- type: positive/negative/boundary/data
- priority: P0/P1/P2/P3
- preconditions: 前置条件数组
- steps: 步骤简述数组
- expectedResults: 预期结果数组
- structuredSteps: [{order, action, target, expected, data, type}]
- apiEndpoints: [{method, path, description}]
- testData: {字段名: 值}
- executionHints: {approach, notes, prerequisites}
- stateMachineRef: {states, transitions, forbiddenTransitions}

只返回 JSON 数组，不要包含其他文字。
```

### 3.2 Few-shot 示例注入（F2）

在 userPrompt 中追加 1 个正向 + 1 个异常示例：

```json
[
  {
    "title": "创建订单-正常流程",
    "module": "订单管理",
    "type": "positive",
    "priority": "P0",
    "preconditions": ["用户已登录", "购物车有商品"],
    "steps": ["调用创建订单接口", "验证返回订单号", "验证订单状态为待支付"],
    "expectedResults": ["接口返回200和订单号", "订单状态=PENDING_PAYMENT"],
    "structuredSteps": [
      {"order":1,"action":"创建订单","target":"POST /api/order/create","expected":"返回201和orderId","data":{"userId":"U001","items":[{"skuId":"SKU001","quantity":2}],"amount":99.90},"type":"api_call"},
      {"order":2,"action":"验证订单状态","target":"GET /api/order/{orderId}","expected":"status=PENDING_PAYMENT","data":{},"type":"state_assert"}
    ],
    "apiEndpoints": [{"method":"POST","path":"/api/order/create","description":"创建订单"}],
    "testData": {"userId":"U001","items":[{"skuId":"SKU001","quantity":2}],"amount":99.90},
    "executionHints": {"approach":"api_call","notes":"先创建再查询验证状态","prerequisites":["用户已登录"]},
    "stateMachineRef": {"states":[],"transitions":[{"from":"NONE","to":"PENDING_PAYMENT","trigger":"create"}],"forbiddenTransitions":[]}
  },
  {
    "title": "创建订单-金额为负数",
    "module": "订单管理",
    "type": "negative",
    "priority": "P1",
    "preconditions": ["用户已登录"],
    "steps": ["传入负数金额创建订单", "验证接口拒绝"],
    "expectedResults": ["接口返回400","错误消息提示金额非法"],
    "structuredSteps": [
      {"order":1,"action":"传入负数金额创建订单","target":"POST /api/order/create","expected":"返回400错误","data":{"userId":"U001","items":[{"skuId":"SKU001","quantity":2}],"amount":-1},"type":"api_call"}
    ],
    "apiEndpoints": [{"method":"POST","path":"/api/order/create","description":"创建订单"}],
    "testData": {"userId":"U001","amount":-1},
    "executionHints": {"approach":"api_call","notes":"验证金额校验逻辑","prerequisites":["用户已登录"]},
    "stateMachineRef": {"states":[],"transitions":[],"forbiddenTransitions":[{"from":"PENDING_PAYMENT","to":"NONE","reason":"金额非法不可创建"}]}
  }
]
```

### 3.3 LLM 重试机制（F3）

**现状**：`LlmService.chat()` 任何异常直接抛 `BusinessException`。

**v1.4**：在 `chat()` 方法中增加重试逻辑：

- 最多重试 3 次（含首次共 3 次调用）
- 指数退避：间隔 1s → 2s → 4s
- 仅对可重试异常重试（网络超时、429 限流、500 服务端错误）
- 400/401 等客户端错误不重试
- 重试日志记录

### 3.4 批量删除（F4）

**现状**：前端表格无多选，只能逐条删除。

**v1.4**：
- el-table 新增 `selection` 列（checkbox 多选）
- 表格上方新增"批量删除"按钮，仅当选中行 > 0 时启用
- 点击后弹确认框（显示选中数量），确认后调用后端批量删除接口
- 后端新增 `DELETE /api/projects/{projectId}/testcases/batch` 接口，接收 `{"ids": ["TC-001", "TC-002"]}`

### 3.5 批量导出选中（F5）

**现状**：只能全量导出 XMind。

**v1.4**：
- 表格上方新增"导出选中"按钮
- 选中行时导出选中用例，未选中时导出全部
- 后端 mindmap 接口新增可选 `testcaseIds` 参数，只导出指定用例

---

## 四、验收标准

| 编号 | 验收项 | 验证方式 |
|------|--------|----------|
| AC1 | LLM 生成的 testData 含具体字段值（非空 {}） | 检查生成结果 |
| AC2 | LLM 生成的 structuredSteps 每步 target 非空 | 检查生成结果 |
| AC3 | 每个 stateMachine 至少生成 4 条用例 | 检查生成数量 |
| AC4 | LLM 网络异常时自动重试，不立即回退规则 | 日志验证 |
| AC5 | 表格可多选行，批量删除后列表刷新 | 操作验证 |
| AC6 | 选中行后可导出选中用例 | 操作验证 |
| AC7 | 后端编译通过，前端构建通过 | 构建 |

---

## 五、风险与对策

| 风险 | 对策 |
|------|------|
| Few-shot 示例增加 token 消耗 | 示例精简，控制在 800 token 以内 |
| 重试增加生成耗时 | 指数退避 + 最大 3 次，总耗时可控 |
| 批量删除误操作 | 二次确认 + 显示选中数量 |
| prompt 变更导致输出格式不稳定 | 后端 parse 逻辑保持容错（已有默认值） |

---

## 六、交付物清单

- [ ] `docs/v1.4/PRD_v1.4_生成质量增强II.md`（本文档）
- [ ] `docs/v1.4/后端技术评审_v1.4.md`
- [ ] `docs/v1.4/前端技术评审_v1.4.md`
- [ ] 后端：TestGeneratorAgent prompt 重构 / LlmService 重试 / 批量删除+导出接口
- [ ] 前端：TestCaseList 表格多选 + 批量删除 + 批量导出按钮
- [ ] `docs/CHANGELOG.md` 更新
- [ ] `README.md` 更新
