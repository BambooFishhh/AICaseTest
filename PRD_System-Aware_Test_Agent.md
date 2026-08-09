# System-Aware Test Agent - 系统感知型AI测试用例生成系统

## 产品需求文档 (PRD)

**版本**: v1.0 MVP  
**日期**: 2026-08-01  
**首个验证项目**: litemall B端商城（E:\java_project\litemall）  
**硬件约束**: 32GB RAM / i5-12490F / 100GB 可用磁盘

---

## 一、产品概述

### 1.1 产品定位

**System-Aware Test Agent（系统感知型测试Agent）**

让 AI 理解完整的软件系统（业务逻辑、代码行为、状态机），自动生成以 XMind 脑图为交付物的测试用例。

### 1.2 核心价值主张

```
传统方式：PRD → 测试用例（信息不完整，缺少历史逻辑和状态约束）
本系统：  代码 + PRD → 业务模型 → 状态机 → XMind测试用例
```

### 1.3 首个验证项目：litemall 商城

**项目信息**：
- 技术栈：Vue 2 + Element UI（前端）、Spring Boot + MyBatis（后端）
- 业务模块：商品管理、订单管理、用户管理、优惠券、售后、团购等
- 代码规模：中等（适合 MVP 验证）

**核心状态机（从代码中提取）**：

**订单状态机**（OrderUtil.java）：
```
101(未支付) → 201(已支付) → 301(已发货) → 401(已收货)
    ↓              ↓
  102(用户取消)  202(申请退款) → 203(已退款)
    ↓
  103(系统超时取消)
```

**售后状态机**（AftersaleConstant.java）：
```
0(初始化) → 1(申请) → 2(受理) → 3(退款)
                ↓
              4(拒绝)
                ↓
              5(取消)
```

**优惠券状态机**（CouponConstant.java）：
```
0(正常) → 1(过期)
    ↓
  2(已用完)
```

---

## 二、MVP 功能范围

### 2.1 功能优先级

| 优先级 | 功能模块 | 说明 | MVP必须 |
|--------|----------|------|---------|
| **P0** | **XMind 脑图用例生成** | 核心交付物 | ✅ |
| **P0** | 代码仓库接入 | 支持本地文件夹导入 | ✅ |
| **P0** | 代码分析与状态机提取 | 从代码中提取业务状态机 | ✅ |
| **P0** | Agent 编排引擎 | 协调分析和生成流程 | ✅ |
| **P1** | PRD 文档导入 | 补充业务上下文 | ❌ |
| **P1** | Git 历史分析 | 提取历史变更原因 | ❌ |

### 2.2 XMind 脑图结构设计

```
根节点：[项目名称] 测试用例
├── 订单管理
│   ├── 正向流程
│   │   ├── TC-001: 正常下单流程
│   │   │   ├── 前置条件：用户已登录，购物车有商品
│   │   │   ├── 测试步骤
│   │   │   │   ├── 1. 进入购物车
│   │   │   │   ├── 2. 选择商品
│   │   │   │   ├── 3. 提交订单
│   │   │   │   └── 4. 支付订单
│   │   │   └── 预期结果：订单状态 = 201（已支付）
│   │   └── TC-002: 订单发货流程
│   ├── 异常流程
│   │   ├── TC-010: 未支付订单取消
│   │   ├── TC-011: 已支付订单申请退款
│   │   └── TC-012: 发货后申请售后
│   ├── 状态边界
│   │   ├── TC-020: 101→201 状态转换（支付）
│   │   ├── TC-021: 201→301 状态转换（发货）
│   │   ├── TC-022: 301→401 状态转换（收货）
│   │   ├── TC-023: 101→102 状态转换（用户取消）
│   │   └── TC-024: 非法状态转换（401→201，已收货订单不能支付）
│   └── 数据边界
│       ├── TC-030: 空购物车下单
│       ├── TC-031: 库存不足下单
│       └── TC-032: 优惠券使用边界
├── 售后管理
│   ├── 正向流程
│   │   ├── TC-100: 正常售后申请
│   │   └── TC-101: 售后退款流程
│   ├── 异常流程
│   │   ├── TC-110: 售后申请被拒绝
│   │   └── TC-111: 售后申请取消
│   └── 状态边界
│       ├── TC-120: 0→1→2→3 状态转换
│       └── TC-121: 1→4 状态转换（拒绝）
└── 跨模块场景
    ├── TC-200: 订单+售后完整流程
    └── TC-201: 优惠券+订单组合场景
```

### 2.3 代码分析流程

```
源代码（litemall项目）
  ↓
[1] 项目结构扫描
  ├── 识别技术栈：Vue 2 + Spring Boot
  ├── 识别目录结构：litemall-admin（前端）、litemall-admin-api（后端）
  └── 识别核心模块：订单、商品、用户、优惠券、售后
  ↓
[2] 前端代码分析
  ├── 路由提取：/admin/order/list、/admin/goods/list 等
  ├── API 调用提取：/admin/order/list、/admin/order/detail 等
  └── 页面流程推断：订单列表→订单详情→发货操作
  ↓
[3] 后端代码分析
  ├── API 端点提取：AdminOrderController、AdminGoodsController 等
  ├── 状态机提取：OrderUtil.java（订单状态）、AftersaleConstant.java（售后状态）
  ├── 业务规则提取：OrderHandleOption.java（操作权限）
  └── 数据模型提取：LitemallOrder.java（订单实体）
  ↓
[4] 状态机恢复
  ├── 订单状态机：101→201→301→401（正向）+ 102/103/202/203（异常）
  ├── 售后状态机：0→1→2→3（正向）+ 4/5（异常）
  └── 优惠券状态机：0→1/2（终态）
  ↓
[5] 测试用例生成
  ├── 基于状态机生成正向流程用例
  ├── 基于状态机生成异常流程用例
  ├── 基于状态机生成边界用例（非法状态转换）
  └── 基于业务规则生成数据边界用例
  ↓
[6] XMind 脑图构建
  ├── 按模块组织用例
  ├── 添加优先级、前置条件、步骤、预期结果
  └── 生成 .xmind 文件
```

---

## 三、技术架构（轻量化方案）

### 3.1 硬件资源分配

| 资源 | 规格 | 分配策略 |
|------|------|----------|
| 内存 | 32GB | 应用服务 ≤ 4GB，LLM 使用外部 API |
| CPU | i5-12490F | 代码解析 + Agent 编排，不承担 LLM 推理 |
| 磁盘 | 100GB | 应用代码 ≤ 1GB，项目缓存 ≤ 10GB |

### 3.2 技术栈选型

| 层 | 选型 | 理由 | 内存占用 |
|---|------|------|----------|
| **前端** | Vue 2 + Element UI | 与 litemall 技术栈一致，可复用组件 | ~300MB |
| **后端** | Python FastAPI | AST 解析生态最好（Tree-sitter） | ~500MB |
| **Agent 框架** | LangGraph | 原生状态图 + 多 Agent 编排 | ~500MB |
| **AST 解析** | Tree-sitter | 多语言、速度快 | ~100MB |
| **关系数据库** | SQLite | 零配置，轻量级 | ~10MB |
| **向量数据库** | ChromaDB | 嵌入式运行 | ~200MB |
| **任务队列** | Celery + Redis | Agent 异步任务 | ~500MB |
| **LLM 推理** | 外部 API | DeepSeek / GPT-4o | 0（外部承担） |

**总内存峰值：~2.1GB**（远低于 32GB 限制）

### 3.3 LLM 选择

| 优先级 | 模型 | 用途 | 费用参考 |
|--------|------|------|----------|
| P0 | DeepSeek-V3 | 代码理解、状态机提取、用例生成 | 极低（国内最优） |
| P0 | OpenAI GPT-4o | 备选，复杂推理场景 | 中等 |
| P1 | Claude Sonnet | 代码分析增强 | 中等 |
| P2 | 本地 Qwen2.5-7B | 轻量级任务（可选） | 免费，需 ~6GB |

---

## 四、核心数据模型

### 4.1 项目 (Project)

```json
{
  "id": "uuid",
  "name": "litemall 商城",
  "source_type": "local_path",
  "source_path": "E:\\java_project\\litemall",
  "tech_stack": {
    "frontend": "vue2",
    "backend": "spring-boot",
    "language": "javascript, java"
  },
  "status": "created | analyzing | completed | failed"
}
```

### 4.2 状态机 (StateMachine)

```json
{
  "id": "uuid",
  "project_id": "uuid",
  "name": "订单状态机",
  "states": [
    { "id": "101", "name": "未支付", "is_initial": true },
    { "id": "102", "name": "用户取消", "is_terminal": true },
    { "id": "103", "name": "系统超时取消", "is_terminal": true },
    { "id": "201", "name": "已支付" },
    { "id": "202", "name": "申请退款" },
    { "id": "203", "name": "已退款", "is_terminal": true },
    { "id": "204", "name": "超时团购", "is_terminal": true },
    { "id": "301", "name": "已发货" },
    { "id": "401", "name": "已收货", "is_terminal": true },
    { "id": "402", "name": "系统自动收货", "is_terminal": true }
  ],
  "transitions": [
    { "from": "101", "to": "201", "trigger": "支付", "condition": "订单有效" },
    { "from": "101", "to": "102", "trigger": "用户取消", "condition": "未支付" },
    { "from": "101", "to": "103", "trigger": "系统超时", "condition": "超时未支付" },
    { "from": "201", "to": "301", "trigger": "发货", "condition": "商家操作" },
    { "from": "201", "to": "202", "trigger": "申请退款", "condition": "用户申请" },
    { "from": "202", "to": "203", "trigger": "退款成功", "condition": "管理员确认" },
    { "from": "301", "to": "401", "trigger": "确认收货", "condition": "用户确认" },
    { "from": "301", "to": "402", "trigger": "系统自动收货", "condition": "超时" }
  ]
}
```

### 4.3 测试用例 (TestCase)

```json
{
  "id": "TC-001",
  "title": "正常下单流程",
  "module": "订单管理",
  "type": "positive",
  "priority": "P0",
  "preconditions": ["用户已登录", "购物车有商品"],
  "steps": [
    { "order": 1, "action": "进入购物车" },
    { "order": 2, "action": "选择商品" },
    { "order": 3, "action": "提交订单" },
    { "order": 4, "action": "支付订单" }
  ],
  "expected_results": ["订单状态 = 201（已支付）"],
  "state_machine_ref": { "transition": "101→201" }
}
```

### 4.4 XMind 输出

```json
{
  "id": "uuid",
  "project_id": "uuid",
  "title": "litemall 商城测试用例",
  "file_path": "/outputs/litemall-tests.xmind",
  "statistics": {
    "total_cases": 85,
    "by_type": { "positive": 25, "negative": 20, "boundary": 25, "integration": 15 },
    "state_coverage": "95%",
    "transition_coverage": "90%"
  }
}
```

---

## 五、页面设计

### 5.1 核心页面

| 页面 | 路由 | 功能 |
|------|------|------|
| 项目列表 | `/projects` | 展示所有项目 |
| 创建项目 | `/projects/create` | 输入项目路径，导入代码 |
| 项目详情 | `/projects/:id` | 展示分析状态和结果概览 |
| 代码分析 | `/projects/:id/analysis` | 展示状态机、API、业务规则 |
| 用例生成 | `/projects/:id/generate` | 配置参数，触发生成 |
| 脑图预览 | `/projects/:id/mindmap` | 在线预览 XMind 结构 |
| 系统设置 | `/settings` | LLM API 配置 |

### 5.2 关键交互流程

```
创建项目 → 导入代码 → 自动分析 → 查看状态机 → 生成用例 → 预览/下载XMind
                              ↑
                        人工修正状态机（可选）
```

---

## 六、接口设计

### 6.1 核心 API

```
# 项目管理
POST   /api/projects                    创建项目
GET    /api/projects                    项目列表
GET    /api/projects/:id                项目详情

# 代码分析
POST   /api/projects/:id/analyze        触发代码分析
GET    /api/projects/:id/analysis       获取分析结果
GET    /api/projects/:id/state-machine  获取状态机
PUT    /api/projects/:id/state-machine  修正状态机

# 用例生成
POST   /api/projects/:id/generate       触发用例生成
GET    /api/projects/:id/testcases      获取测试用例

# XMind 输出
POST   /api/projects/:id/mindmap/generate  生成XMind
GET    /api/projects/:id/mindmap/download  下载.xmind文件
```

---

## 七、项目结构

```
test-agent/
├── frontend/                        # 前端（Vue 2 + Element UI）
│   ├── src/
│   │   ├── views/
│   │   │   ├── ProjectList.vue      # 项目列表
│   │   │   ├── ProjectCreate.vue    # 创建项目
│   │   │   ├── ProjectDetail.vue    # 项目详情
│   │   │   ├── CodeAnalysis.vue     # 代码分析结果
│   │   │   ├── TestCaseList.vue     # 测试用例列表
│   │   │   ├── MindMapPreview.vue   # 脑图预览
│   │   │   └── Settings.vue         # 系统设置
│   │   ├── components/
│   │   │   ├── StateMachineViewer.vue   # 状态机可视化
│   │   │   ├── TestCaseCard.vue         # 用例卡片
│   │   │   └── ProgressTracker.vue      # Agent进度追踪
│   │   ├── api/
│   │   └── router/
│   └── package.json
│
├── backend/                         # 后端（Python FastAPI）
│   ├── app/
│   │   ├── main.py                  # FastAPI 入口
│   │   ├── api/
│   │   │   ├── projects.py          # 项目 API
│   │   │   ├── analysis.py          # 分析 API
│   │   │   ├── testcases.py         # 用例 API
│   │   │   └── mindmap.py           # 脑图 API
│   │   ├── agents/
│   │   │   ├── orchestrator.py      # 编排 Agent
│   │   │   ├── code_analyzer.py     # 代码分析 Agent
│   │   │   ├── state_machine.py     # 状态机提取 Agent
│   │   │   ├── test_generator.py    # 用例生成 Agent
│   │   │   └── xmind_builder.py     # 脑图构建 Agent
│   │   ├── analyzers/
│   │   │   ├── vue_analyzer.py      # Vue 项目分析
│   │   │   ├── spring_analyzer.py   # Spring Boot 分析
│   │   │   └── base.py              # 分析器基类
│   │   ├── models/
│   │   ├── services/
│   │   │   ├── llm_service.py       # LLM 调用封装
│   │   │   ├── vector_store.py      # 向量存储
│   │   │   └── xmind_service.py     # XMind 生成
│   │   └── core/
│   │       ├── config.py
│   │       └── database.py
│   └── requirements.txt
│
├── outputs/                         # XMind 文件输出
├── data/                            # SQLite、向量数据
└── README.md
```

---

## 八、开发排期（MVP - 8周）

### Phase 1：基础设施 + 代码分析（2周）

| 任务 | 说明 |
|------|------|
| 项目脚手架 | 前后端项目初始化 |
| 数据库设计 | SQLite 表结构 |
| 项目管理 API | CRUD 接口 |
| Vue 代码分析器 | 提取路由、API 调用、组件 |
| Spring 代码分析器 | 提取 Controller、Service、状态机 |

### Phase 2：Agent + 状态机（2周）

| 任务 | 说明 |
|------|------|
| LLM 服务封装 | DeepSeek/GPT API 适配 |
| 状态机提取 Agent | 从代码构建状态机 |
| 业务规则提取 Agent | 提取操作权限、校验规则 |
| Agent 编排 | LangGraph 流程编排 |

### Phase 3：用例生成 + XMind（2周）

| 任务 | 说明 |
|------|------|
| 测试用例生成 Agent | 基于状态机生成用例 |
| XMind 生成服务 | JSON → .xmind 文件 |
| 脑图预览 | 在线预览脑图结构 |

### Phase 4：前端 UI + 联调（2周）

| 任务 | 说明 |
|------|------|
| 项目管理页面 | 创建、列表、详情 |
| 分析结果页面 | 状态机可视化 |
| 用例管理页面 | 用例列表、编辑 |
| 端到端联调 | 全流程测试 |

---

## 九、成功指标

| 指标 | MVP 目标 |
|------|----------|
| 状态机提取准确率 | ≥ 80% |
| 用例有效率 | ≥ 70% |
| XMind 文件可用率 | 100% |
| 端到端耗时 | ≤ 10分钟/项目 |
| 内存峰值占用 | ≤ 4GB |

---

## 十、MCP/Skill 需求

### 10.1 必须搜索的 MCP

| MCP 工具 | 用途 | 搜索关键词 |
|----------|------|-----------|
| 代码解析 MCP | 解析 Java/JavaScript AST | "AST parser MCP", "tree-sitter MCP" |
| 文件系统 MCP | 读取项目文件结构 | "filesystem MCP server" |
| LLM 调用 MCP | 统一 LLM API 接口 | "openai MCP", "LLM gateway MCP" |

### 10.2 需要自研的 Skill

| Skill | 说明 |
|-------|------|
| 状态机提取 Skill | 从 Java 代码中提取状态常量和转换逻辑 |
| 测试用例生成 Skill | 基于状态机生成结构化测试用例 |
| XMind 构建 Skill | JSON → .xmind 文件 |
| Vue 代码分析 Skill | 提取路由、API 调用、组件结构 |
| Spring 代码分析 Skill | 提取 Controller、Service、业务规则 |

---

## 十一、第三方依赖

| 组件 | 推荐方案 | 端口 |
|------|----------|------|
| LLM API | DeepSeek API | - |
| 关系数据库 | SQLite | - |
| 向量数据库 | ChromaDB | 8000 |
| 任务队列 | Celery + Redis | 6379 |
| 本地模型（可选） | Ollama + Qwen2.5-7B | 11434 |

---

## 十二、后续演进

| 阶段 | 时间 | 新增能力 |
|------|------|----------|
| V1.1 | MVP + 1个月 | Git 历史分析、更多框架支持 |
| V1.2 | MVP + 2个月 | Bug 记录关联、测试用例版本管理 |
| V2.0 | MVP + 6个月 | 自动化测试脚本生成、CI/CD 集成 |
