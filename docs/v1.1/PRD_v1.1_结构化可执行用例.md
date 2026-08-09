# PRD v1.1 — 结构化可执行用例（Executable Test Case Spec）

**版本**: v1.1（迭代版本）
**基线**: v1.0 MVP
**日期**: 2026-08-09
**迭代主题**: 让测试用例从"自然语言文档"升级为"AI 可读可执行的结构化剧本"

---

## 一、迭代背景与问题分析

### 1.1 最终产品愿景

打造高可用、对人类理解友好、UI 友好、**AI 可执行**、高可视化性能的 AI 用例生成系统。最终让 AI 能够直接执行生成的测试用例。

### 1.2 v1.0 现状回顾

v1.0 已实现：项目导入 → 代码分析 → 状态机提取 → LLM/规则生成用例 → XMind 导出 → 前端列表与详情查看。

### 1.3 v1.0 核心局限（阻碍"AI 可执行"目标）

| 编号 | 局限 | 现状 | 影响 |
|------|------|------|------|
| L1 | 步骤为纯自然语言字符串 | `steps: ["进入购物车", "提交订单"]` | AI 无法解析"操作对象、动作、期望"，无法执行 |
| L2 | 用例未关联 API 端点 | 无 `apiEndpoints` 字段 | 无法驱动接口级自动化测试 |
| L3 | 缺少测试数据 | 无 `testData` 字段 | AI 执行时无具体输入值 |
| L4 | `stateMachineRef` 形同虚设 | 生成时写死 `{}` | 用例与状态转换无关联，覆盖率无法度量 |
| L5 | 缺少执行提示 | 无 `executionHints` | AI 不知该用 API 调用、浏览器操作还是人工 |
| L6 | 缺少执行状态 | 无 `executionStatus` | 无法追踪用例是否被执行、通过/失败 |
| L7 | 前端步骤展示为纯文本列表 | `<li>{{ step }}</li>` | 人类阅读时"步骤-预期"无配对，可读性差 |

### 1.4 v1.1 在愿景路线中的位置

```
v1.0 用例生成（自然语言）   ✅ 已完成
v1.1 结构化可执行用例模型    ◀── 本次迭代（打基础）
v1.2 AI 用例执行引擎         （未来）
v1.3 执行结果可视化与报告    （未来）
v2.0 高可视化 + 高可用       （未来）
```

**v1.1 的定位**：不实现"AI 执行"，但让生成的用例**结构上具备被 AI 执行的条件**。这是迈向执行能力的必要地基。

---

## 二、v1.1 范围

### 2.1 本迭代做什么（In Scope）

| 模块 | 改动 | 优先级 |
|------|------|--------|
| 后端-数据模型 | TestCase 增加结构化字段（structuredSteps / apiEndpoints / testData / executionHints / executionStatus） | P0 |
| 后端-生成 | TestGeneratorAgent 升级 prompt，生成结构化步骤 + 关联 API + 真实填充 stateMachineRef | P0 |
| 后端-API | TestCaseDTO / Controller 适配新字段，向后兼容旧字段 | P0 |
| 前端-展示 | TestCaseCard 查看模式升级为结构化步骤卡片 + API 关联 + 执行提示 | P0 |
| 文档 | PRD + 前后端技术评审 + CHANGELOG | P0 |

### 2.2 本迭代不做什么（Out of Scope）

- ❌ AI 实际执行用例（v1.2）
- ❌ 执行结果存储与报告（v1.2）
- ❌ 用例版本管理（v1.3）
- ❌ 状态机可视化重构（v1.3）
- ❌ 新增页面路由（仅增强现有 TestCaseCard/List）
- ❌ 数据库迁移脚本（依赖 JPA ddl-auto=update 自动加列）

### 2.3 向后兼容策略

- 保留 `steps` / `preconditions` / `expectedResults` 旧字段（string[]）
- 新增 `structuredSteps` 字段（结构化对象数组）
- 生成时同时填充新旧字段：旧字段供 XMind 导出与列表展示，新字段供 AI 执行
- 前端优先展示 `structuredSteps`，为空时回退到旧 `steps`

---

## 三、数据模型变更

### 3.1 TestCase 实体新增字段

| 字段 | 类型 | 说明 | 示例 |
|------|------|------|------|
| `structuredSteps` | TEXT(JSON) | 结构化步骤数组 | 见 3.2 |
| `apiEndpoints` | TEXT(JSON) | 关联的 API 端点数组 | `[{"method":"POST","path":"/admin/order/list","description":"查询订单列表"}]` |
| `testData` | TEXT(JSON) | 测试数据键值对 | `{"keyword":"手机","page":1}` |
| `executionHints` | TEXT(JSON) | 执行提示 | `{"approach":"api_call","notes":"需先登录获取token"}` |
| `executionStatus` | VARCHAR | 执行状态 | `not_executed`（默认） |

### 3.2 structuredSteps 结构定义

```json
[
  {
    "order": 1,
    "action": "提交订单",
    "target": "POST /admin/order/create",
    "expected": "返回订单号，订单状态=101(未支付)",
    "data": {"addressId": 1, "cartItemIds": [1,2]},
    "type": "api_call"
  },
  {
    "order": 2,
    "action": "支付订单",
    "target": "POST /admin/order/pay",
    "expected": "订单状态 101→201(已支付)",
    "data": {"orderId": "${prev.orderId}", "payType": 1},
    "type": "api_call"
  }
]
```

**字段说明**：
- `order`：步骤序号
- `action`：人类可读的动作描述
- `target`：操作目标（API 路径 / UI 元素 / 状态转换）
- `expected`：该步骤的预期结果
- `data`：该步骤的输入数据（支持 `${prev.xxx}` 引用前序步骤输出）
- `type`：步骤类型 `api_call` | `ui_action` | `state_assert` | `manual`

### 3.3 executionHints 结构

```json
{
  "approach": "api_call",
  "notes": "需先调用 /admin/auth/login 获取 token，后续请求携带 Authorization 头",
  "prerequisites": ["服务正常运行", "测试账号已存在"]
}
```

`approach` 枚举：`api_call`（接口调用）| `browser`（浏览器操作）| `manual`（人工执行）

### 3.4 executionStatus 枚举

`not_executed` | `running` | `passed` | `failed` | `blocked`

默认 `not_executed`，v1.1 仅持久化默认值，执行流转在 v1.2 实现。

---

## 四、生成能力升级

### 4.1 LLM Prompt 升级要点

v1.0 prompt 仅要求返回 `steps` 字符串数组。v1.1 prompt 升级要求：

1. 生成 `structuredSteps`：每步含 action/target/expected/data/type
2. 关联 `apiEndpoints`：从 backendResult.endpoints 中匹配该用例涉及的接口
3. 填充 `testData`：提供具体输入数据示例
4. 填充 `executionHints`：标注推荐执行方式
5. 真实填充 `stateMachineRef`：关联状态转换 `{from, to, trigger}`

### 4.2 规则回退增强

当 LLM 失败回退到规则生成时，`generateByRules` 也应：
- 基于状态转换生成 structuredSteps（每条 transition 一个断言步骤）
- 关联 endpoint（如果能从 endpoint.function 简单匹配）
- executionHints.approach 默认 `manual`

### 4.3 stateMachineRef 真实填充

状态机相关用例（type=boundary/negative）应填充：
```json
{
  "states": [{"id":"101","name":"未支付"}],
  "transitions": [{"from":"101","to":"201","trigger":"支付"}],
  "forbiddenTransitions": [{"from":"401","to":"201","reason":"已收货不能支付"}]
}
```

---

## 五、前端展示升级

### 5.1 TestCaseCard 查看模式升级

**结构化步骤卡片**（替代纯文本列表）：
- 每个步骤为一个卡片行：序号 + 动作 + 目标标签 + 类型标签
- 展开后显示 expected + data
- API 类型步骤显示 method+path 高亮
- 状态断言步骤显示 from→to 流转

**新增展示区块**：
- API 关联：关联的端点列表（method 标签 + path）
- 执行提示：approach 标签 + notes
- 测试数据：data 键值对表格

### 5.2 回退策略

- `structuredSteps` 为空时，回退展示旧 `steps`（保证 v1.0 已有用例不破坏）
- 编辑模式 v1.1 暂不支持结构化步骤编辑（仅查看），保持旧编辑能力

---

## 六、验收标准

| 编号 | 验收项 | 验证方式 |
|------|--------|----------|
| AC1 | 新生成用例包含 structuredSteps 且每步有 action/target/expected | 查看用例详情 |
| AC2 | 状态机相关用例的 stateMachineRef 非空且关联真实转换 | 查看用例详情 |
| AC3 | API 相关用例关联 apiEndpoints | 查看用例详情 |
| AC4 | 旧字段 steps/preconditions/expectedResults 仍被填充 | 列表与 XMind 导出正常 |
| AC5 | v1.0 已有旧用例（无 structuredSteps）前端正常展示（回退） | 打开旧用例详情 |
| AC6 | 前端结构化步骤卡片正确渲染 | 视觉验证 |
| AC7 | 生成流程不报错，LLM 失败时规则回退正常 | 重新生成 |
| AC8 | 后端编译通过，无启动错误 | mvn 编译启动 |

---

## 七、风险与对策

| 风险 | 影响 | 对策 |
|------|------|------|
| LLM 不稳定返回结构化 JSON | 生成失败 | 增强解析容错 + 规则回退（已有机制） |
| LLM token 超限 | 生成截断 | v1.1 保持单次生成，v1.2 再分模块 |
| 旧用例无新字段导致前端异常 | 展示空白 | 回退策略 + 字段判空 |
| H2 ddl-auto 加列 | schema 变更 | 依赖 JPA 自动处理，无需迁移脚本 |

---

## 八、交付物清单

- [ ] `docs/v1.1/PRD_v1.1_结构化可执行用例.md`（本文档）
- [ ] `docs/v1.1/后端技术评审_v1.1.md`
- [ ] `docs/v1.1/前端技术评审_v1.1.md`
- [ ] 后端代码：TestCase / TestGeneratorAgent / TestCaseDTO / JsonHelper
- [ ] 前端代码：TestCaseCard.vue
- [ ] `docs/CHANGELOG.md`
- [ ] GitHub 提交记录
