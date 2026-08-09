# System-Aware Test Agent - 系统感知型AI测试用例生成系统（完整版PRD）

## 产品需求文档 (PRD)

**版本**: v2.0 完整版
**日期**: 2026-08-01
**首个验证项目**: litemall B端商城（E:\java_project\litemall）
**硬件约束**: 32GB RAM / i5-12490F / 100GB 可用磁盘
**关联文档**: PRD_System-Aware_Test_Agent.md（MVP版）

---

## 一、产品概述

### 1.1 产品定位

**System-Aware Test Agent（系统感知型测试Agent）**

让 AI 理解完整的软件系统（业务逻辑、代码行为、历史演进、运行状态、缺陷记录），自动生成以 XMind 脑图为交付物的测试用例。

**核心理念**：从"文档驱动测试"走向"系统智能测试"

```
传统方式：PRD → 测试用例（信息不完整，缺少历史逻辑和状态约束）
MVP方式：  代码 + PRD → 业务模型 → 状态机 → XMind测试用例
完整方式：  代码 + PRD + Git历史 + Bug记录 + 数据库 + 测试记录 → 产品知识模型 → 业务状态机 → 智能测试用例 → XMind
```

### 1.2 产品演进路线

```
V1.0 MVP（8周）
  ├── 核心：代码 → 状态机 → XMind 用例
  └── 验证：litemall 商城
      ↓
V1.5 增强版（+6周）
  ├── 新增：Git 历史分析、数据库 Schema 分析、PRD 导入
  └── 新增：更多框架支持（React、Node.js、Python）
      ↓
V2.0 完整版（+12周）
  ├── 新增：Bug 记录关联、测试记录分析、产品知识模型
  ├── 新增：自动化测试脚本生成、CI/CD 集成
  └── 新增：多项目知识复用、团队协作
      ↓
V3.0 智能版（+24周）
  ├── 新增：自学习能力（从测试反馈中优化）
  ├── 新增：跨系统理解（微服务架构）
  └── 新增：测试价值排序、风险预测
```

### 1.3 问题诊断（完整版视角）

**问题1：状态完整性缺失**
- GUI/截图只能展现静态界面，无法表达状态机、状态转换条件和约束
- 例如商城系统：下单→支付→发货→收货，每个状态都有合法/非法转换

**问题2：历史逻辑断层**
- 产品迭代中，人知道"当前功能是历史版本的改进"，AI缺乏这种演进认知
- 大量历史迭代逻辑混杂在历史PRD中，难以完整补充给AI

**问题3：知识碎片化**
- 业务逻辑分散在：PRD + 代码 + 数据库 + 接口定义 + Git历史 + Bug记录 + 测试记录
- 代码是最终且最完整的业务逻辑载体——**代码是活文档，PRD是历史快照**

**问题4：测试价值不可衡量**
- 传统工具生成的测试用例缺乏业务价值排序
- 无法区分"高风险场景"和"低风险场景"

### 1.4 目标用户

| 角色 | 使用场景 | 完整版新增价值 |
|------|----------|---------------|
| 测试工程师 | 从代码快速生成测试用例 | 历史Bug关联，避免重复缺陷 |
| 测试负责人 | 评估测试覆盖率 | 测试价值排序，资源优化分配 |
| 产品经理 | 验证需求实现 | 产品知识模型，理解历史设计决策 |
| 开发工程师 | 理解业务逻辑 | 代码变更影响分析 |
| 项目经理 | 风险评估 | 测试覆盖率+缺陷趋势=风险预测 |

---

## 二、硬件约束与部署架构

### 2.1 硬件规格

| 资源 | 规格 | 分配策略 |
|------|------|----------|
| 内存 | 32GB | 应用服务 ≤ 4GB，本地模型（可选）≤ 8GB，LLM 推理使用外部 API |
| CPU | i5-12490F (6核12线程) | 代码解析 + Agent 编排，不承担 LLM 推理 |
| 磁盘 | 100GB 可用 | 应用代码 ≤ 1GB，向量数据 ≤ 10GB，项目缓存 ≤ 20GB |

### 2.2 LLM 使用外部 API（推荐）

| 优先级 | 模型 | 用途 | 费用参考 |
|--------|------|------|----------|
| P0 | DeepSeek-V3 / DeepSeek-R1 | 代码理解、状态机提取、用例生成 | 极低（国内最优性价比） |
| P0 | OpenAI GPT-4o | 备选，复杂推理场景 | 中等 |
| P1 | Claude Sonnet | 代码分析增强 | 中等 |
| P2 | 本地 Qwen2.5-7B-Q4 | 轻量级任务（分类、摘要） | 免费，需 ~6GB 内存 |
| P2 | 本地 CodeQwen1.5-7B | 代码理解辅助 | 免费，需 ~6GB 内存 |

### 2.3 资源占用预估

| 组件 | 内存峰值 | 磁盘占用 | 阶段 |
|------|----------|----------|------|
| FastAPI 应用 | ~500MB | ~200MB | MVP |
| LangGraph Agent | ~1GB | ~100MB | MVP |
| ChromaDB | ~500MB | ~5GB（大型项目） | MVP |
| Redis (Celery) | ~200MB | ~500MB | MVP |
| Tree-sitter | ~100MB | ~100MB | MVP |
| Vue 前端 | ~300MB | ~500MB | MVP |
| Git 分析引擎 | ~200MB | ~1GB | V1.5 |
| 数据库 Schema 分析 | ~100MB | ~500MB | V1.5 |
| Bug 系统集成 | ~100MB | ~200MB | V2.0 |
| 产品知识模型 | ~500MB | ~3GB | V2.0 |
| 测试记录分析 | ~200MB | ~500MB | V2.0 |
| 自动化脚本生成 | ~200MB | ~500MB | V2.0 |
| **合计** | **~4GB** | **~12.5GB** | - |
| **剩余给系统** | **~28GB** | **~87.5GB** | - |

---

## 三、功能模块（全版本）

### 3.1 功能全景图

```
┌─────────────────────────────────────────────────────────────────┐
│                    System-Aware Test Agent                       │
├─────────────────────────────────────────────────────────────────┤
│  V1.0 MVP 核心                                                  │
│  ├── [P0] XMind 脑图用例生成                                    │
│  ├── [P0] 代码仓库接入（本地文件夹）                              │
│  ├── [P0] 前端代码分析（Vue/React AST解析）                      │
│  ├── [P0] 后端代码分析（Spring Boot AST解析）                    │
│  ├── [P0] 状态机自动提取                                        │
│  ├── [P0] Agent 编排引擎（LangGraph）                           │
│  └── [P0] LLM 增强理解（DeepSeek/GPT）                         │
├─────────────────────────────────────────────────────────────────┤
│  V1.5 增强版                                                    │
│  ├── [P1] Git 仓库克隆（Git URL → 本地）                       │
│  ├── [P1] Git 历史分析（commit message + blame）               │
│  ├── [P1] PRD 文档导入（Markdown/Word）                         │
│  ├── [P1] 数据库 Schema 分析（DDL/ORM 模型）                   │
│  ├── [P1] 接口文档解析（Swagger/OpenAPI）                       │
│  └── [P1] 更多框架适配（React/Node.js/Python）                 │
├─────────────────────────────────────────────────────────────────┤
│  V2.0 完整版                                                    │
│  ├── [P2] Bug 记录关联（Jira/禅道/GitHub Issues）              │
│  ├── [P2] 测试记录分析（历史测试用例复用）                       │
│  ├── [P2] 产品知识模型（跨项目知识积累）                         │
│  ├── [P2] 测试用例版本管理                                      │
│  ├── [P2] 测试价值排序（风险优先）                              │
│  ├── [P2] 自动化测试脚本生成                                    │
│  └── [P2] CI/CD 集成                                           │
├─────────────────────────────────────────────────────────────────┤
│  V3.0 智能版                                                    │
│  ├── [P3] 自学习能力（测试反馈优化）                            │
│  ├── [P3] 跨系统理解（微服务架构）                              │
│  ├── [P3] 代码变更影响分析                                      │
│  └── [P3] 测试风险预测                                          │
└─────────────────────────────────────────────────────────────────┘
```

### 3.2 V1.0 MVP 功能（详细）

| 功能 | 说明 | 优先级 |
|------|------|--------|
| XMind 脑图用例生成 | 核心交付物，所有功能的最终输出 | P0 |
| 代码仓库接入 | 支持本地文件夹导入 | P0 |
| 前端代码分析 | Vue/React 项目的路由、API、状态变量提取 | P0 |
| 后端代码分析 | Spring Boot 的 Controller、Service、状态机提取 | P0 |
| 状态机自动提取 | 从枚举类、条件判断中恢复业务状态机 | P0 |
| Agent 编排引擎 | LangGraph 多 Agent 协作流程 | P0 |
| LLM 增强理解 | DeepSeek/GPT 辅助代码语义理解 | P0 |

### 3.3 V1.5 增强版功能（详细）

| 功能 | 说明 | 优先级 |
|------|------|--------|
| Git 仓库克隆 | 输入 Git URL，自动克隆到本地 | P1 |
| Git 历史分析 | 分析 commit message，提取业务变更原因 | P1 |
| Git Blame 分析 | 识别关键业务逻辑的修改历史和作者 | P1 |
| PRD 文档导入 | 支持 Markdown/Word 格式 PRD 导入和解析 | P1 |
| 数据库 Schema 分析 | 从 DDL 或 ORM 模型中提取数据模型和关系 | P1 |
| 接口文档解析 | 解析 Swagger/OpenAPI，补充 API 上下文 | P1 |
| React 代码分析 | 支持 React 项目的组件、状态、路由分析 | P1 |
| Node.js 代码分析 | 支持 Express/NestJS 后端项目分析 | P1 |
| Python 代码分析 | 支持 Flask/FastAPI/Django 后端项目分析 | P1 |

### 3.4 V2.0 完整版功能（详细）

| 功能 | 说明 | 优先级 |
|------|------|--------|
| Bug 记录关联 | 从 Jira/禅道/GitHub Issues 导入历史缺陷 | P2 |
| 历史 Bug 模式识别 | 识别高频缺陷类型，生成针对性测试用例 | P2 |
| 测试记录分析 | 导入历史测试用例，识别覆盖盲区 | P2 |
| 测试用例复用 | 自动匹配可复用的历史用例 | P2 |
| 产品知识模型 | 跨项目、跨迭代的业务知识积累 | P2 |
| 知识图谱构建 | 业务概念、状态、规则的关联图谱 | P2 |
| 测试用例版本管理 | 用例变更追踪和版本对比 | P2 |
| 测试价值排序 | 基于风险、变更频率、历史缺陷排序 | P2 |
| 自动化测试脚本生成 | 从用例生成可执行的测试脚本（Selenium/Cypress） | P2 |
| CI/CD 集成 | 集成 Jenkins/GitLab CI，代码变更自动触发 | P2 |
| 团队协作 | 多用户、权限管理、用例评审流程 | P2 |

### 3.5 V3.0 智能版功能（详细）

| 功能 | 说明 | 优先级 |
|------|------|--------|
| 自学习能力 | 从测试执行反馈中优化用例生成策略 | P3 |
| 跨系统理解 | 微服务架构下的跨服务状态机关联 | P3 |
| 代码变更影响分析 | 识别代码变更影响的业务逻辑和测试用例 | P3 |
| 测试风险预测 | 基于代码质量+变更频率+历史缺陷预测风险 | P3 |
| 智能回归测试 | 自动识别需要回归的测试用例子集 | P3 |
| 测试报告生成 | 自动生成测试覆盖率和风险分析报告 | P3 |

---

## 四、系统架构（完整版）

### 4.1 整体架构图

```
┌─────────────────────────────────────────────────────────────────────┐
│                         前端层 (Vue 2/3 + Element UI)                │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐  │
│  │ 项目管理  │ │ 分析任务  │ │ 结果预览  │ │ 脑图下载  │ │ 知识库   │  │
│  └──────────┘ └──────────┘ └──────────┘ └──────────┘ └──────────┘  │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐               │
│  │ 测试记录  │ │ Bug关联   │ │ 版本管理  │ │ 系统设置  │               │
│  └──────────┘ └──────────┘ └──────────┘ └──────────┘               │
└──────────────────────┬──────────────────────────────────────────────┘
                       │ HTTP / WebSocket
┌──────────────────────┴──────────────────────────────────────────────┐
│                    后端服务层 (Python FastAPI)                        │
│  ┌────────────────────────────────────────────────────────────────┐  │
│  │                    API Gateway (FastAPI)                        │  │
│  └──────┬──────────┬──────────┬──────────┬──────────┬─────────────┘  │
│         │          │          │          │          │                 │
│  ┌──────┴────┐ ┌───┴────┐ ┌──┴───────┐ ┌┴────────┐ ┌┴──────────┐   │
│  │ Agent     │ │ Code   │ │ XMind    │ │ Git     │ │ Bug/Test  │   │
│  │ Engine    │ │ Parser │ │ Builder  │ │ Analyzer│ │ Analyzer  │   │
│  │ (LangGraph)│ │(Tree-  │ │          │ │         │ │           │   │
│  └──────┬────┘ │ sitter)│ └──────────┘ └─────────┘ └───────────┘   │
│         │      └───┬────┘                                           │
│  ┌──────┴────┐ ┌───┴────┐ ┌──────────┐ ┌─────────┐                 │
│  │ LLM       │ │ AST    │ │ DB Schema│ │ API Doc │                 │
│  │ Service   │ │ Parser │ │ Analyzer │ │ Parser  │                 │
│  └───────────┘ └────────┘ └──────────┘ └─────────┘                 │
└──────────────────────┬──────────────────────────────────────────────┘
                       │
┌──────────────────────┴──────────────────────────────────────────────┐
│                         数据层                                       │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐            │
│  │ SQLite   │  │ ChromaDB │  │ Redis    │  │ 文件系统  │            │
│  │ (元数据)  │  │ (向量)   │  │ (缓存/   │  │ (项目缓存 │            │
│  │          │  │          │  │  队列)   │  │  输出)   │            │
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘            │
│  ┌──────────┐                                                      │
│  │ Neo4j    │  ← V2.0: 知识图谱（可选）                             │
│  └──────────┘                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### 4.2 Agent 架构（完整版）

```
用户输入（Git仓库/本地路径 + PRD + Bug系统 + 配置）
  ↓
[Orchestrator Agent] 编排Agent
  ├── 分析输入源，制定执行计划
  ├── 分配任务给子Agent（支持并行）
  ├── 汇总结果，质量评估
  └── 触发后续流程
  ↓
  ├─→ [Code Analyzer Agent] 代码分析Agent
  │     ├── 前端代码分析（Vue/React/其他）
  │     ├── 后端代码分析（Spring Boot/Node.js/其他）
  │     ├── 数据库 Schema 分析
  │     └── 接口文档解析
  │
  ├─→ [Git Analyzer Agent] Git历史分析Agent（V1.5）
  │     ├── Commit Message 分析
  │     ├── Blame 分析（关键逻辑修改历史）
  │     ├── 变更趋势分析
  │     └── 历史 Bug Fix 关联
  │
  ├─→ [Bug Analyzer Agent] 缺陷分析Agent（V2.0）
  │     ├── Bug 记录导入和解析
  │     ├── 高频缺陷模式识别
  │     ├── 缺陷-代码关联分析
  │     └── 测试覆盖盲区识别
  │
  ├─→ [State Machine Agent] 状态机提取Agent
  │     ├── 输入：代码分析报告 + PRD + Git历史
  │     ├── LLM推理：恢复业务状态机
  │     ├── 置信度评估
  │     └── 输出：状态机定义（JSON）
  │
  ├─→ [Knowledge Model Agent] 知识模型Agent（V2.0）
  │     ├── 业务概念提取
  │     ├── 规则关联分析
  │     ├── 知识图谱构建
  │     └── 跨项目知识复用
  │
  ├─→ [Test Generator Agent] 用例生成Agent
  │     ├── 输入：状态机 + 业务规则 + Bug模式 + 知识模型
  │     ├── 策略：正向/异常/边界/跨模块/回归/安全
  │     ├── 价值排序：风险优先
  │     └── 输出：测试用例列表（JSON）
  │
  ├─→ [Script Generator Agent] 脚本生成Agent（V2.0）
  │     ├── 输入：测试用例 + 页面结构
  │     ├── 输出：Selenium/Cypress 测试脚本
  │     └── 支持：API 测试脚本（Postman/Python）
  │
  └─→ [XMind Builder] 脑图构建器
        ├── 输入：测试用例列表
        ├── 构建脑图结构（模块/类型/优先级）
        └── 输出：.xmind 文件
```

### 4.3 Agent 状态图（LangGraph）

```python
# 完整版 Agent 状态图
workflow = StateGraph(AgentState)

# === V1.0 MVP 节点 ===
workflow.add_node("analyze_frontend", analyze_frontend_code)
workflow.add_node("analyze_backend", analyze_backend_code)
workflow.add_node("build_business_model", build_business_model)
workflow.add_node("extract_state_machine", extract_state_machine)
workflow.add_node("generate_test_cases", generate_test_cases)
workflow.add_node("build_xmind", build_xmind_file)

# === V1.5 增强节点 ===
workflow.add_node("clone_git_repo", clone_git_repo)
workflow.add_node("analyze_git_history", analyze_git_history)
workflow.add_node("parse_prd", parse_prd_document)
workflow.add_node("analyze_db_schema", analyze_db_schema)
workflow.add_node("parse_api_docs", parse_api_docs)

# === V2.0 完整节点 ===
workflow.add_node("import_bug_records", import_bug_records)
workflow.add_node("analyze_bug_patterns", analyze_bug_patterns)
workflow.add_node("import_test_records", import_test_records)
workflow.add_node("build_knowledge_model", build_knowledge_model)
workflow.add_node("rank_test_value", rank_test_value)
workflow.add_node("generate_test_scripts", generate_test_scripts)

# === 流程编排 ===
# MVP 流程
workflow.add_edge("analyze_frontend", "build_business_model")
workflow.add_edge("analyze_backend", "build_business_model")
workflow.add_edge("build_business_model", "extract_state_machine")
workflow.add_edge("extract_state_machine", "generate_test_cases")
workflow.add_edge("generate_test_cases", "build_xmind")

# 增强流程（V1.5）
workflow.add_edge("clone_git_repo", "analyze_git_history")
workflow.add_edge("analyze_git_history", "build_business_model")
workflow.add_edge("parse_prd", "build_business_model")
workflow.add_edge("analyze_db_schema", "build_business_model")

# 完整流程（V2.0）
workflow.add_edge("import_bug_records", "analyze_bug_patterns")
workflow.add_edge("analyze_bug_patterns", "generate_test_cases")
workflow.add_edge("import_test_records", "generate_test_cases")
workflow.add_edge("build_knowledge_model", "generate_test_cases")
workflow.add_edge("generate_test_cases", "rank_test_value")
workflow.add_edge("rank_test_value", "build_xmind")
```

---

## 五、数据模型（完整版）

### 5.1 核心实体关系

```
Project (项目)
  ├── CodeAnalysisResult (代码分析结果)
  │     ├── FrontendAnalysis (前端分析)
  │     │     ├── Routes (路由)
  │     │     ├── Components (组件)
  │     │     ├── StateVariables (状态变量)
  │     │     └── ApiCalls (API调用)
  │     └── BackendAnalysis (后端分析)
  │           ├── Endpoints (API端点)
  │           ├── BusinessRules (业务规则)
  │           ├── Entities (数据实体)
  │           └── Enums (枚举/状态)
  ├── StateMachine (状态机)
  │     ├── States (状态)
  │     └── Transitions (转换)
  ├── GitAnalysis (Git分析) ← V1.5
  │     ├── CommitHistory (提交历史)
  │     ├── BlameResult (Blame结果)
  │     └── ChangeTrend (变更趋势)
  ├── DbSchema (数据库Schema) ← V1.5
  │     ├── Tables (表)
  │     └── Relations (关系)
  ├── BugAnalysis (缺陷分析) ← V2.0
  │     ├── BugRecords (Bug记录)
  │     └── BugPatterns (缺陷模式)
  ├── KnowledgeModel (知识模型) ← V2.0
  │     ├── Concepts (业务概念)
  │     ├── Rules (业务规则)
  │     └── Relations (关联关系)
  ├── TestCase (测试用例)
  │     ├── Steps (步骤)
  │     ├── ExpectedResults (预期结果)
  │     ├── StateMachineRef (状态机引用)
  │     ├── BugRef (Bug引用) ← V2.0
  │     └── ValueScore (价值评分) ← V2.0
  ├── TestScript (测试脚本) ← V2.0
  └── MindMap (XMind输出)
```

### 5.2 项目 (Project) - 完整版

```json
{
  "id": "uuid",
  "name": "litemall 商城",
  "description": "B端商城系统，含商品、订单、用户、优惠券、售后等模块",
  "source_type": "local_path | git_url | zip_upload",
  "source_path": "E:\\java_project\\litemall",
  "git_url": null,
  "tech_stack": {
    "frontend": "vue2",
    "backend": "spring-boot",
    "language": "javascript, java",
    "frameworks": ["element-ui", "mybatis", "shiro"]
  },
  "status": "created | cloning | analyzing | generating | completed | failed",
  "version": "1.0",
  "created_at": "2026-08-01T10:00:00Z",
  "updated_at": "2026-08-01T10:30:00Z",
  "settings": {
    "llm_provider": "deepseek",
    "llm_model": "deepseek-v3",
    "analysis_depth": "full | quick",
    "test_types": ["positive", "negative", "boundary", "integration"],
    "max_cases_per_module": 50
  }
}
```

### 5.3 状态机 (StateMachine) - 完整版

```json
{
  "id": "uuid",
  "project_id": "uuid",
  "name": "订单状态机",
  "description": "商城订单的完整状态流转，包含正向流程和异常处理",
  "version": "1.0",
  "confidence": 0.92,
  "sources": [
    { "type": "code", "file": "OrderUtil.java", "lines": [15, 30] },
    { "type": "code", "file": "OrderHandleOption.java", "lines": [1, 76] },
    { "type": "code", "file": "AdminOrderService.java", "lines": [50, 120] },
    { "type": "prd", "file": "order-prd.md", "section": "订单状态说明" },
    { "type": "git", "commit": "abc123", "message": "feat: 添加订单超时自动取消" },
    { "type": "bug", "id": "BUG-456", "title": "已收货订单状态异常" }
  ],
  "states": [
    {
      "id": "101",
      "name": "未支付",
      "description": "订单已创建，等待用户支付",
      "is_initial": true,
      "is_terminal": false,
      "allowed_operations": ["pay", "cancel"],
      "ui_indicators": ["订单状态显示'待付款'"],
      "source": { "type": "code", "file": "OrderUtil.java", "line": 20 }
    },
    {
      "id": "102",
      "name": "用户取消",
      "description": "用户主动取消未支付订单",
      "is_initial": false,
      "is_terminal": true,
      "allowed_operations": ["delete"],
      "source": { "type": "code", "file": "OrderUtil.java", "line": 24 }
    },
    {
      "id": "103",
      "name": "系统超时取消",
      "description": "订单超时未支付，系统自动取消",
      "is_initial": false,
      "is_terminal": true,
      "allowed_operations": ["delete"],
      "source": { "type": "code", "file": "OrderUtil.java", "line": 28 }
    },
    {
      "id": "201",
      "name": "已支付",
      "description": "用户已完成支付，等待商家发货",
      "is_initial": false,
      "is_terminal": false,
      "allowed_operations": ["ship", "refund"],
      "source": { "type": "code", "file": "OrderUtil.java", "line": 32 }
    },
    {
      "id": "202",
      "name": "申请退款",
      "description": "用户申请退款，等待管理员处理",
      "is_initial": false,
      "is_terminal": false,
      "allowed_operations": [],
      "source": { "type": "code", "file": "OrderUtil.java", "line": 36 }
    },
    {
      "id": "203",
      "name": "已退款",
      "description": "退款申请已通过，款项已退回",
      "is_initial": false,
      "is_terminal": true,
      "allowed_operations": ["delete"],
      "source": { "type": "code", "file": "OrderUtil.java", "line": 40 }
    },
    {
      "id": "301",
      "name": "已发货",
      "description": "商家已发货，等待用户确认收货",
      "is_initial": false,
      "is_terminal": false,
      "allowed_operations": ["confirm"],
      "source": { "type": "code", "file": "OrderUtil.java", "line": 44 }
    },
    {
      "id": "401",
      "name": "已收货",
      "description": "用户确认收货，订单完成",
      "is_initial": false,
      "is_terminal": true,
      "allowed_operations": ["delete", "comment", "rebuy", "aftersale"],
      "source": { "type": "code", "file": "OrderUtil.java", "line": 48 }
    },
    {
      "id": "402",
      "name": "系统自动收货",
      "description": "超时未确认收货，系统自动确认",
      "is_initial": false,
      "is_terminal": true,
      "allowed_operations": ["delete", "comment", "rebuy", "aftersale"],
      "source": { "type": "code", "file": "OrderUtil.java", "line": 52 }
    }
  ],
  "transitions": [
    {
      "id": "T-001",
      "from": "101",
      "to": "201",
      "trigger": "支付操作",
      "condition": "订单有效，支付金额正确",
      "actor": "user",
      "source": { "type": "code", "file": "AdminOrderService.java", "line": 88 },
      "confidence": 0.95,
      "bug_history": [
        { "id": "BUG-123", "title": "重复支付导致订单状态异常", "status": "fixed" }
      ]
    },
    {
      "id": "T-002",
      "from": "101",
      "to": "102",
      "trigger": "用户取消",
      "condition": "订单未支付",
      "actor": "user",
      "source": { "type": "code", "file": "OrderUtil.java", "line": 24 },
      "confidence": 0.95
    },
    {
      "id": "T-003",
      "from": "101",
      "to": "103",
      "trigger": "系统超时",
      "condition": "超过支付时限",
      "actor": "system",
      "source": { "type": "code", "file": "OrderJob.java", "line": 45 },
      "confidence": 0.9
    },
    {
      "id": "T-004",
      "from": "201",
      "to": "301",
      "trigger": "商家发货",
      "condition": "已填写物流信息",
      "actor": "admin",
      "source": { "type": "code", "file": "AdminOrderController.java", "line": 120 },
      "confidence": 0.95
    },
    {
      "id": "T-005",
      "from": "201",
      "to": "202",
      "trigger": "申请退款",
      "condition": "用户申请",
      "actor": "user",
      "source": { "type": "code", "file": "WxOrderController.java", "line": 200 },
      "confidence": 0.9
    },
    {
      "id": "T-006",
      "from": "202",
      "to": "203",
      "trigger": "退款成功",
      "condition": "管理员确认退款",
      "actor": "admin",
      "source": { "type": "code", "file": "AdminOrderService.java", "line": 150 },
      "confidence": 0.9
    },
    {
      "id": "T-007",
      "from": "301",
      "to": "401",
      "trigger": "确认收货",
      "condition": "用户确认",
      "actor": "user",
      "source": { "type": "code", "file": "WxOrderController.java", "line": 250 },
      "confidence": 0.95
    },
    {
      "id": "T-008",
      "from": "301",
      "to": "402",
      "trigger": "系统自动收货",
      "condition": "超时未确认",
      "actor": "system",
      "source": { "type": "code", "file": "OrderJob.java", "line": 80 },
      "confidence": 0.9
    }
  ],
  "forbidden_transitions": [
    {
      "from": "401",
      "to": "201",
      "reason": "已收货订单不能重新支付",
      "source": { "type": "inferred", "method": "状态机完整性分析" }
    },
    {
      "from": "102",
      "to": "201",
      "reason": "已取消订单不能支付",
      "source": { "type": "inferred", "method": "状态机完整性分析" }
    },
    {
      "from": "203",
      "to": "301",
      "reason": "已退款订单不能发货",
      "source": { "type": "inferred", "method": "状态机完整性分析" }
    }
  ]
}
```

### 5.4 测试用例 (TestCase) - 完整版

```json
{
  "id": "TC-001",
  "title": "正常下单并支付流程",
  "module": "订单管理",
  "type": "positive",
  "priority": "P0",
  "value_score": 95,
  "preconditions": [
    "用户已登录",
    "购物车有商品",
    "商品库存充足"
  ],
  "steps": [
    { "order": 1, "action": "进入购物车", "data": null },
    { "order": 2, "action": "选择商品", "data": "商品A x2" },
    { "order": 3, "action": "点击结算", "data": null },
    { "order": 4, "action": "确认收货地址", "data": "默认地址" },
    { "order": 5, "action": "提交订单", "data": null },
    { "order": 6, "action": "支付订单", "data": "微信支付" }
  ],
  "expected_results": [
    "订单状态变为 201（已支付）",
    "订单详情页显示'已付款'",
    "库存减少2件"
  ],
  "state_machine_ref": {
    "transition_id": "T-001",
    "from_state": "101",
    "to_state": "201"
  },
  "bug_refs": [
    { "id": "BUG-123", "title": "重复支付导致订单状态异常", "relevance": "high" }
  ],
  "git_refs": [
    { "commit": "abc123", "message": "feat: 订单支付流程优化" }
  ],
  "source": "code_extraction",
  "confidence": 0.95,
  "created_at": "2026-08-01T10:30:00Z",
  "version": 1
}
```

### 5.5 产品知识模型 (KnowledgeModel) - V2.0

```json
{
  "id": "uuid",
  "project_id": "uuid",
  "concepts": [
    {
      "id": "C-001",
      "name": "订单",
      "description": "用户购买商品的交易记录",
      "related_entities": ["LitemallOrder", "LitemallOrderGoods"],
      "related_apis": ["/admin/order/list", "/admin/order/detail"],
      "state_machine": "SM-ORDER",
      "business_rules": ["BR-001", "BR-002"],
      "git_history": {
        "first_introduced": "2024-01-15",
        "last_modified": "2026-07-20",
        "major_changes": 12,
        "bug_count": 8
      }
    },
    {
      "id": "C-002",
      "name": "优惠券",
      "description": "营销优惠工具，支持多种类型",
      "related_entities": ["LitemallCoupon", "LitemallCouponUser"],
      "state_machine": "SM-COUPON",
      "business_rules": ["BR-010", "BR-011"],
      "git_history": {
        "first_introduced": "2024-03-10",
        "last_modified": "2026-06-15",
        "major_changes": 6,
        "bug_count": 3
      }
    }
  ],
  "rules": [
    {
      "id": "BR-001",
      "name": "订单支付规则",
      "description": "订单必须处于未支付状态才能进行支付",
      "code_location": "OrderUtil.java:120",
      "confidence": 0.95
    },
    {
      "id": "BR-002",
      "name": "库存扣减规则",
      "description": "支付成功后扣减商品库存",
      "code_location": "AdminOrderService.java:100",
      "confidence": 0.9
    }
  ],
  "relations": [
    {
      "from": "C-001",
      "to": "C-002",
      "type": "uses",
      "description": "订单可使用优惠券"
    }
  ]
}
```

### 5.6 测试价值排序 (ValueScore) - V2.0

```json
{
  "test_case_id": "TC-001",
  "value_score": 95,
  "factors": {
    "risk_level": {
      "score": 90,
      "reason": "订单支付是核心业务流程"
    },
    "change_frequency": {
      "score": 85,
      "reason": "近6个月修改了8次"
    },
    "bug_history": {
      "score": 95,
      "reason": "历史Bug数量8个，包含严重缺陷"
    },
    "coverage_gap": {
      "score": 80,
      "reason": "历史测试用例未覆盖异常支付场景"
    },
    "business_impact": {
      "score": 100,
      "reason": "直接影响用户支付和资金"
    }
  }
}
```

---

## 六、XMind 脑图输出（完整版）

### 6.1 完整版脑图结构

```
根节点：[项目名称] 系统感知型测试用例 v2.0
├── 模块1：订单管理
│   ├── 1.1 正向流程
│   │   ├── TC-001: 正常下单并支付
│   │   │   ├── 优先级：P0
│   │   │   ├── 价值评分：95
│   │   │   ├── 前置条件
│   │   │   ├── 测试步骤
│   │   │   ├── 预期结果
│   │   │   ├── 关联状态机：101→201
│   │   │   ├── 关联Bug：BUG-123（重复支付）
│   │   │   └── 来源：代码提取 + Bug关联
│   │   ├── TC-002: 订单发货流程
│   │   └── TC-003: 订单收货流程
│   ├── 1.2 异常流程
│   │   ├── TC-010: 未支付订单取消
│   │   ├── TC-011: 已支付订单申请退款
│   │   ├── TC-012: 发货后申请售后
│   │   ├── TC-013: 退款申请被拒绝
│   │   └── TC-014: 超时自动取消
│   ├── 1.3 状态边界
│   │   ├── TC-020: 101→201 状态转换（支付）
│   │   ├── TC-021: 201→301 状态转换（发货）
│   │   ├── TC-022: 301→401 状态转换（收货）
│   │   ├── TC-023: 101→102 状态转换（用户取消）
│   │   ├── TC-024: 非法转换 401→201（已收货不能支付）
│   │   ├── TC-025: 非法转换 102→201（已取消不能支付）
│   │   └── TC-026: 非法转换 203→301（已退款不能发货）
│   ├── 1.4 数据边界
│   │   ├── TC-030: 空购物车下单
│   │   ├── TC-031: 库存不足下单
│   │   ├── TC-032: 超大金额订单
│   │   └── TC-033: 并发下单（同一商品）
│   ├── 1.5 历史Bug回归（V2.0）
│   │   ├── TC-040: 重复支付场景（BUG-123）
│   │   ├── TC-041: 订单状态异常（BUG-456）
│   │   └── TC-042: 退款金额错误（BUG-789）
│   └── 1.6 变更影响测试（V2.0）
│       ├── TC-050: 最近代码变更影响的用例
│       └── TC-051: 高频修改模块的回归用例
├── 模块2：商品管理
│   ├── 2.1 正向流程
│   │   ├── TC-100: 商品上架
│   │   ├── TC-101: 商品编辑
│   │   └── TC-102: 商品下架
│   ├── 2.2 异常流程
│   │   ├── TC-110: 无图片商品上架
│   │   └── TC-111: 价格为0的商品
│   ├── 2.3 状态边界
│   │   ├── TC-120: 上架→下架状态转换
│   │   └── TC-121: 有订单的商品下架
│   └── 2.4 数据边界
│       ├── TC-130: 超长商品名称
│       └── TC-131: 特殊字符商品描述
├── 模块3：用户管理
│   ├── 3.1 正向流程
│   ├── 3.2 异常流程
│   ├── 3.3 状态边界
│   └── 3.4 数据边界
├── 模块4：优惠券管理
│   ├── 4.1 正向流程
│   ├── 4.2 异常流程
│   ├── 4.3 状态边界
│   └── 4.4 数据边界
├── 模块5：售后管理
│   ├── 5.1 正向流程
│   ├── 5.2 异常流程
│   ├── 5.3 状态边界
│   └── 5.4 数据边界
└── 跨模块场景
    ├── TC-500: 订单+优惠券+支付 完整流程
    ├── TC-501: 订单+售后+退款 完整流程
    ├── TC-502: 用户注册+领券+下单 完整流程
    └── TC-503: 商品下架对进行中订单的影响
```

### 6.2 脑图节点属性

| 属性 | MVP | V1.5 | V2.0 |
|------|-----|------|------|
| 用例编号 | ✅ | ✅ | ✅ |
| 用例标题 | ✅ | ✅ | ✅ |
| 优先级 | ✅ | ✅ | ✅ |
| 前置条件 | ✅ | ✅ | ✅ |
| 测试步骤 | ✅ | ✅ | ✅ |
| 预期结果 | ✅ | ✅ | ✅ |
| 关联状态机 | ✅ | ✅ | ✅ |
| 来源标记 | ✅ | ✅ | ✅ |
| 置信度 | ✅ | ✅ | ✅ |
| PRD关联 | ❌ | ✅ | ✅ |
| Git关联 | ❌ | ✅ | ✅ |
| Bug关联 | ❌ | ❌ | ✅ |
| 价值评分 | ❌ | ❌ | ✅ |
| 变更影响 | ❌ | ❌ | ✅ |
| 测试脚本链接 | ❌ | ❌ | ✅ |

---

## 七、页面设计（完整版）

### 7.1 核心页面

| 页面 | 路由 | MVP | V1.5 | V2.0 | 功能 |
|------|------|-----|------|------|------|
| 项目列表 | `/projects` | ✅ | ✅ | ✅ | 展示所有项目 |
| 创建项目 | `/projects/create` | ✅ | ✅ | ✅ | 导入代码/配置 |
| 项目详情 | `/projects/:id` | ✅ | ✅ | ✅ | 分析状态和概览 |
| 代码分析 | `/projects/:id/analysis` | ✅ | ✅ | ✅ | 状态机、API、规则 |
| 用例生成 | `/projects/:id/generate` | ✅ | ✅ | ✅ | 配置和触发生成 |
| 脑图预览 | `/projects/:id/mindmap` | ✅ | ✅ | ✅ | 在线预览 |
| 测试用例列表 | `/projects/:id/testcases` | ✅ | ✅ | ✅ | 用例管理 |
| 系统设置 | `/settings` | ✅ | ✅ | ✅ | LLM/系统配置 |
| Git分析 | `/projects/:id/git` | ❌ | ✅ | ✅ | Git历史分析结果 |
| 数据库Schema | `/projects/:id/schema` | ❌ | ✅ | ✅ | 数据模型展示 |
| PRD管理 | `/projects/:id/prd` | ❌ | ✅ | ✅ | PRD导入和关联 |
| Bug关联 | `/projects/:id/bugs` | ❌ | ❌ | ✅ | Bug记录和关联 |
| 知识模型 | `/projects/:id/knowledge` | ❌ | ❌ | ✅ | 业务知识图谱 |
| 测试记录 | `/projects/:id/test-records` | ❌ | ❌ | ✅ | 历史测试记录 |
| 版本对比 | `/projects/:id/versions` | ❌ | ❌ | ✅ | 用例版本对比 |
| 报告中心 | `/reports` | ❌ | ❌ | ✅ | 测试覆盖率报告 |

### 7.2 关键交互流程（完整版）

```
创建项目
  ↓
导入代码（本地/Git URL/ZIP）
  ↓
[可选] 导入PRD文档
[可选] 配置Git仓库
[可选] 配置Bug系统
[可选] 配置数据库连接
  ↓
自动分析（Agent编排）
  ├── 代码分析（前后端）
  ├── Git历史分析（V1.5）
  ├── 数据库Schema分析（V1.5）
  ├── Bug记录导入（V2.0）
  └── 知识模型构建（V2.0）
  ↓
查看分析结果
  ├── 状态机可视化
  ├── 业务规则列表
  ├── API关系图
  └── 知识图谱（V2.0）
  ↓
[可选] 人工修正状态机
[可选] 补充业务规则
  ↓
配置生成参数
  ├── 测试类型选择
  ├── 深度级别
  ├── 模块范围
  └── 优先级过滤
  ↓
生成测试用例
  ├── 状态机驱动生成
  ├── Bug模式驱动生成（V2.0）
  ├── 价值排序（V2.0）
  └── 去重和合并
  ↓
查看和编辑用例
  ↓
生成XMind脑图
  ↓
下载.xmind文件
[可选] 生成测试脚本（V2.0）
[可选] 推送到CI/CD（V2.0）
```

---

## 八、接口设计（完整版）

### 8.1 项目管理

```
POST   /api/projects                    创建项目
GET    /api/projects                    项目列表
GET    /api/projects/:id                项目详情
PUT    /api/projects/:id                更新项目
DELETE /api/projects/:id                删除项目
```

### 8.2 代码分析

```
POST   /api/projects/:id/analyze        触发代码分析
GET    /api/projects/:id/analysis       获取分析结果
GET    /api/projects/:id/state-machine  获取状态机
PUT    /api/projects/:id/state-machine  修正状态机
GET    /api/projects/:id/api-map        获取API关系图
GET    /api/projects/:id/business-rules 获取业务规则
```

### 8.3 Git分析（V1.5）

```
POST   /api/projects/:id/git/analyze    触发Git分析
GET    /api/projects/:id/git/history    获取提交历史
GET    /api/projects/:id/git/blame      获取Blame结果
GET    /api/projects/:id/git/trends     获取变更趋势
```

### 8.4 数据库分析（V1.5）

```
POST   /api/projects/:id/schema/analyze 触发Schema分析
GET    /api/projects/:id/schema         获取数据库Schema
GET    /api/projects/:id/schema/tables  获取表列表
GET    /api/projects/:id/schema/relations 获取表关系
```

### 8.5 PRD管理（V1.5）

```
POST   /api/projects/:id/prd/upload     上传PRD文档
GET    /api/projects/:id/prd            获取PRD列表
GET    /api/projects/:id/prd/:prdId     获取PRD内容
DELETE /api/projects/:id/prd/:prdId     删除PRD
```

### 8.6 Bug管理（V2.0）

```
POST   /api/projects/:id/bugs/import    导入Bug记录
GET    /api/projects/:id/bugs           获取Bug列表
GET    /api/projects/:id/bugs/patterns  获取缺陷模式
GET    /api/projects/:id/bugs/coverage  获取缺陷覆盖情况
```

### 8.7 知识模型（V2.0）

```
GET    /api/projects/:id/knowledge      获取知识模型
GET    /api/projects/:id/knowledge/graph 获取知识图谱
PUT    /api/projects/:id/knowledge      更新知识模型
```

### 8.8 用例管理

```
POST   /api/projects/:id/generate       触发用例生成
GET    /api/projects/:id/testcases      获取测试用例列表
GET    /api/projects/:id/testcases/:tcId 获取单个用例
PUT    /api/projects/:id/testcases/:tcId 编辑用例
DELETE /api/projects/:id/testcases/:tcId 删除用例
GET    /api/projects/:id/testcases/stats 用例统计
POST   /api/projects/:id/testcases/import 导入历史用例（V2.0）
```

### 8.9 XMind输出

```
POST   /api/projects/:id/mindmap/generate  生成XMind
GET    /api/projects/:id/mindmap/preview   预览脑图结构
GET    /api/projects/:id/mindmap/download  下载.xmind文件
```

### 8.10 测试脚本（V2.0）

```
POST   /api/projects/:id/scripts/generate  生成测试脚本
GET    /api/projects/:id/scripts           获取脚本列表
GET    /api/projects/:id/scripts/:scriptId 下载脚本
POST   /api/projects/:id/scripts/push      推送到CI/CD
```

### 8.11 报告（V2.0）

```
GET    /api/projects/:id/reports/coverage  覆盖率报告
GET    /api/projects/:id/reports/risk      风险分析报告
GET    /api/projects/:id/reports/trend     趋势报告
POST   /api/projects/:id/reports/generate  生成报告
```

### 8.12 系统配置

```
GET    /api/settings/llm                   获取LLM配置
PUT    /api/settings/llm                   更新LLM配置
POST   /api/settings/llm/test             测试LLM连接
GET    /api/settings/integrations          获取集成配置
PUT    /api/settings/integrations/:type    更新集成配置
```

---

## 九、技术架构（完整版）

### 9.1 前端技术栈

| 组件 | MVP | V1.5 | V2.0 | 选型 |
|------|-----|------|------|------|
| 框架 | ✅ | ✅ | ✅ | Vue 2 + TypeScript |
| UI库 | ✅ | ✅ | ✅ | Element UI |
| 状态管理 | ✅ | ✅ | ✅ | Vuex |
| HTTP | ✅ | ✅ | ✅ | Axios |
| WebSocket | ✅ | ✅ | ✅ | 原生 WebSocket |
| 构建工具 | ✅ | ✅ | ✅ | Webpack (vue-cli) |
| 脑图预览 | ✅ | ✅ | ✅ | markmap / 自定义SVG |
| 状态机可视化 | ❌ | ✅ | ✅ | D3.js / vis.js |
| 知识图谱可视化 | ❌ | ❌ | ✅ | D3.js force graph |
| 代码高亮 | ❌ | ✅ | ✅ | highlight.js |
| 图表 | ❌ | ✅ | ✅ | ECharts |

### 9.2 后端技术栈

| 组件 | MVP | V1.5 | V2.0 | 选型 | 内存 |
|------|-----|------|------|------|------|
| Web框架 | ✅ | ✅ | ✅ | FastAPI | ~500MB |
| Agent框架 | ✅ | ✅ | ✅ | LangGraph | ~1GB |
| LLM调用 | ✅ | ✅ | ✅ | LangChain | ~100MB |
| AST解析 | ✅ | ✅ | ✅ | Tree-sitter | ~100MB |
| 向量数据库 | ✅ | ✅ | ✅ | ChromaDB | ~500MB |
| 关系数据库 | ✅ | ✅ | ✅ | SQLite | ~10MB |
| 任务队列 | ✅ | ✅ | ✅ | Celery + Redis | ~500MB |
| Git操作 | ❌ | ✅ | ✅ | GitPython | ~50MB |
| Word解析 | ❌ | ✅ | ✅ | python-docx | ~20MB |
| XMind生成 | ✅ | ✅ | ✅ | xmind-sdk | ~10MB |
| Bug系统集成 | ❌ | ❌ | ✅ | Jira/禅道 SDK | ~100MB |
| 知识图谱 | ❌ | ❌ | ✅ | Neo4j (可选) | ~500MB |
| 测试脚本生成 | ❌ | ❌ | ✅ | Jinja2 模板 | ~20MB |
| CI/CD集成 | ❌ | ❌ | ✅ | Jenkins/GitLab API | ~50MB |

### 9.3 支持的技术栈（代码分析）

| 阶段 | 前端框架 | 后端框架 | 数据库 |
|------|----------|----------|--------|
| MVP | Vue 2/3 | Spring Boot (Java) | MySQL (通过ORM) |
| V1.5 | + React | + Express/NestJS (Node.js) | + PostgreSQL |
| V1.5 | | + Flask/FastAPI/Django (Python) | + MongoDB |
| V2.0 | + Angular | + .NET Core | + SQL Server |
| V2.0 | + Svelte | + Go (Gin) | |

---

## 十、项目结构（完整版）

```
test-agent/
├── frontend/                            # 前端（Vue 2 + Element UI）
│   ├── src/
│   │   ├── views/
│   │   │   ├── project/
│   │   │   │   ├── ProjectList.vue      # 项目列表
│   │   │   │   ├── ProjectCreate.vue    # 创建项目
│   │   │   │   └── ProjectDetail.vue    # 项目详情
│   │   │   ├── analysis/
│   │   │   │   ├── CodeAnalysis.vue     # 代码分析结果
│   │   │   │   ├── StateMachine.vue     # 状态机可视化
│   │   │   │   ├── ApiMap.vue           # API关系图
│   │   │   │   └── BusinessRules.vue    # 业务规则
│   │   │   ├── test/
│   │   │   │   ├── TestCaseList.vue     # 测试用例列表
│   │   │   │   ├── TestCaseDetail.vue   # 用例详情
│   │   │   │   ├── GenerateConfig.vue   # 生成配置
│   │   │   │   └── TestScript.vue       # 测试脚本（V2.0）
│   │   │   ├── mindmap/
│   │   │   │   ├── MindMapPreview.vue   # 脑图预览
│   │   │   │   └── MindMapCompare.vue   # 版本对比（V2.0）
│   │   │   ├── git/                     # V1.5
│   │   │   │   ├── GitHistory.vue       # Git历史
│   │   │   │   └── GitBlame.vue         # Blame分析
│   │   │   ├── schema/                  # V1.5
│   │   │   │   └── DbSchema.vue         # 数据库Schema
│   │   │   ├── knowledge/               # V2.0
│   │   │   │   ├── KnowledgeModel.vue   # 知识模型
│   │   │   │   └── KnowledgeGraph.vue   # 知识图谱
│   │   │   ├── bug/                     # V2.0
│   │   │   │   ├── BugList.vue          # Bug列表
│   │   │   │   └── BugPatterns.vue      # 缺陷模式
│   │   │   ├── reports/                 # V2.0
│   │   │   │   ├── CoverageReport.vue   # 覆盖率报告
│   │   │   │   └── RiskReport.vue       # 风险报告
│   │   │   └── settings/
│   │   │       └── Settings.vue         # 系统设置
│   │   ├── components/
│   │   │   ├── StateMachineViewer.vue   # 状态机组件
│   │   │   ├── TestCaseCard.vue         # 用例卡片
│   │   │   ├── ProgressTracker.vue      # 进度追踪
│   │   │   ├── CodeViewer.vue           # 代码查看器
│   │   │   └── GraphViewer.vue          # 图谱查看器（V2.0）
│   │   ├── stores/
│   │   ├── api/
│   │   └── router/
│   └── package.json
│
├── backend/                             # 后端（Python FastAPI）
│   ├── app/
│   │   ├── main.py                      # FastAPI 入口
│   │   ├── api/
│   │   │   ├── projects.py              # 项目 API
│   │   │   ├── analysis.py              # 分析 API
│   │   │   ├── testcases.py             # 用例 API
│   │   │   ├── mindmap.py               # 脑图 API
│   │   │   ├── git.py                   # Git API（V1.5）
│   │   │   ├── schema.py               # Schema API（V1.5）
│   │   │   ├── prd.py                   # PRD API（V1.5）
│   │   │   ├── bugs.py                  # Bug API（V2.0）
│   │   │   ├── knowledge.py             # 知识模型 API（V2.0）
│   │   │   ├── scripts.py               # 脚本 API（V2.0）
│   │   │   ├── reports.py               # 报告 API（V2.0）
│   │   │   └── settings.py              # 配置 API
│   │   ├── agents/
│   │   │   ├── orchestrator.py          # 编排 Agent
│   │   │   ├── code_analyzer.py         # 代码分析 Agent
│   │   │   ├── state_machine.py         # 状态机提取 Agent
│   │   │   ├── test_generator.py        # 用例生成 Agent
│   │   │   ├── xmind_builder.py         # 脑图构建 Agent
│   │   │   ├── git_analyzer.py          # Git分析 Agent（V1.5）
│   │   │   ├── bug_analyzer.py          # Bug分析 Agent（V2.0）
│   │   │   ├── knowledge_builder.py     # 知识模型 Agent（V2.0）
│   │   │   ├── value_ranker.py          # 价值排序 Agent（V2.0）
│   │   │   └── script_generator.py      # 脚本生成 Agent（V2.0）
│   │   ├── analyzers/
│   │   │   ├── base.py                  # 分析器基类
│   │   │   ├── frontend/
│   │   │   │   ├── vue_analyzer.py      # Vue 分析器
│   │   │   │   ├── react_analyzer.py    # React 分析器（V1.5）
│   │   │   │   └── angular_analyzer.py  # Angular 分析器（V2.0）
│   │   │   └── backend/
│   │   │       ├── spring_analyzer.py   # Spring Boot 分析器
│   │   │       ├── node_analyzer.py     # Node.js 分析器（V1.5）
│   │   │       ├── python_analyzer.py   # Python 分析器（V1.5）
│   │   │       └── dotnet_analyzer.py   # .NET 分析器（V2.0）
│   │   ├── integrations/               # V2.0
│   │   │   ├── jira_client.py           # Jira 集成
│   │   │   ├── chandao_client.py        # 禅道集成
│   │   │   ├── github_client.py         # GitHub Issues 集成
│   │   │   ├── jenkins_client.py        # Jenkins 集成
│   │   │   └── gitlab_client.py         # GitLab CI 集成
│   │   ├── models/
│   │   │   ├── project.py
│   │   │   ├── code_analysis.py
│   │   │   ├── state_machine.py
│   │   │   ├── test_case.py
│   │   │   ├── mindmap.py
│   │   │   ├── git_analysis.py          # V1.5
│   │   │   ├── db_schema.py             # V1.5
│   │   │   ├── bug_record.py            # V2.0
│   │   │   ├── knowledge.py             # V2.0
│   │   │   ├── test_script.py           # V2.0
│   │   │   └── report.py                # V2.0
│   │   ├── services/
│   │   │   ├── llm_service.py           # LLM 调用封装
│   │   │   ├── vector_store.py          # 向量存储
│   │   │   ├── xmind_service.py         # XMind 生成
│   │   │   ├── git_service.py           # Git 操作（V1.5）
│   │   │   ├── schema_service.py        # Schema 分析（V1.5）
│   │   │   ├── prd_service.py           # PRD 解析（V1.5）
│   │   │   ├── bug_service.py           # Bug 管理（V2.0）
│   │   │   ├── knowledge_service.py     # 知识模型（V2.0）
│   │   │   └── script_service.py        # 脚本生成（V2.0）
│   │   ├── core/
│   │   │   ├── config.py                # 配置管理
│   │   │   ├── database.py              # SQLite 连接
│   │   │   └── celery_app.py            # Celery 配置
│   │   └── utils/
│   │       ├── ast_parser.py            # AST 解析工具
│   │       ├── file_scanner.py          # 文件扫描工具
│   │       └── template_engine.py       # 脚本模板引擎（V2.0）
│   └── requirements.txt
│
├── outputs/                             # XMind 文件输出
├── data/                                # SQLite、向量数据
├── templates/                           # V2.0
│   ├── selenium/                        # Selenium 脚本模板
│   ├── cypress/                         # Cypress 脚本模板
│   └── pytest/                          # pytest 脚本模板
├── docker-compose.yml                   # 可选容器化部署
└── README.md
```

---

## 十一、开发排期（完整版）

### Phase 1：MVP 核心（8周）

| 周 | 任务 | 产出 |
|----|------|------|
| W1-2 | 项目脚手架 + 数据库设计 + 项目管理API | 可运行的空项目 |
| W3-4 | Vue代码分析器 + Spring代码分析器 | 代码分析结果JSON |
| W5-6 | LLM服务 + 状态机提取Agent + 用例生成Agent | Agent流程 |
| W7-8 | XMind生成 + 前端UI + 端到端联调 | 可交付MVP |

### Phase 2：V1.5 增强版（6周）

| 周 | 任务 | 产出 |
|----|------|------|
| W9-10 | Git仓库克隆 + Git历史分析 | Git分析功能 |
| W11 | PRD文档导入 + 解析 | PRD关联功能 |
| W12 | 数据库Schema分析 | 数据模型展示 |
| W13-14 | 更多框架适配（React/Node.js/Python） | 多技术栈支持 |

### Phase 3：V2.0 完整版（12周）

| 周 | 任务 | 产出 |
|----|------|------|
| W15-16 | Bug系统集成（Jira/禅道/GitHub） | Bug导入功能 |
| W17-18 | 历史Bug模式识别 + 测试记录分析 | 缺陷驱动测试 |
| W19-20 | 产品知识模型 + 知识图谱 | 知识管理功能 |
| W21-22 | 测试用例版本管理 + 价值排序 | 用例管理增强 |
| W23-24 | 自动化测试脚本生成 | Selenium/Cypress脚本 |
| W25-26 | CI/CD集成 + 团队协作 | 完整版交付 |

### Phase 4：V3.0 智能版（12周）

| 周 | 任务 | 产出 |
|----|------|------|
| W27-28 | 自学习能力（测试反馈优化） | 反馈循环 |
| W29-30 | 跨系统理解（微服务架构） | 微服务支持 |
| W31-32 | 代码变更影响分析 | 变更分析 |
| W33-34 | 测试风险预测 + 智能回归 | 智能推荐 |
| W35-36 | 测试报告生成 + 性能优化 | 智能版交付 |

**总计：36周（约9个月）完成全版本**

---

## 十二、MCP / Skill 需求（完整版）

### 12.1 必须的 MCP 工具

| MCP 工具 | 用途 | 阶段 | 搜索关键词 |
|----------|------|------|-----------|
| **代码解析 MCP** | 解析 Java/JS/Python AST | MVP | "AST parser MCP", "tree-sitter MCP", "code analysis MCP" |
| **文件系统 MCP** | 安全读取和扫描项目文件 | MVP | "filesystem MCP server" |
| **LLM 调用 MCP** | 统一 LLM API 调用接口 | MVP | "openai MCP", "deepseek MCP", "LLM gateway MCP" |
| **Git 操作 MCP** | 克隆仓库、读取commit历史 | V1.5 | "git MCP server", "github MCP", "gitlab MCP" |
| **数据库 MCP** | 读取数据库表结构 | V1.5 | "database schema MCP", "SQL parser MCP", "mysql MCP" |
| **Jira/缺陷管理 MCP** | 关联历史Bug记录 | V2.0 | "jira MCP server", "bug tracking MCP", "chandao MCP" |
| **Confluence MCP** | 读取历史文档和PRD | V2.0 | "confluence MCP server", "wiki MCP" |
| **Jenkins/CI MCP** | CI/CD集成 | V2.0 | "jenkins MCP", "gitlab CI MCP", "github actions MCP" |

### 12.2 需要自研的 Skill

| Skill | 说明 | 阶段 |
|-------|------|------|
| **状态机提取 Skill** | 从代码分析结果中提取和构建状态机 | MVP |
| **测试用例生成 Skill** | 基于状态机和业务规则生成结构化测试用例 | MVP |
| **XMind 构建 Skill** | 将测试用例 JSON 转换为 .xmind 文件 | MVP |
| **Vue 代码分析 Skill** | Vue 2/3 项目专用的代码分析逻辑 | MVP |
| **Spring 代码分析 Skill** | Spring Boot 专用的代码分析逻辑 | MVP |
| **业务规则推断 Skill** | 使用 LLM 从代码片段推断业务规则 | MVP |
| **React 代码分析 Skill** | React 项目的代码分析逻辑 | V1.5 |
| **Node.js 代码分析 Skill** | Express/NestJS 的代码分析逻辑 | V1.5 |
| **Python 代码分析 Skill** | Flask/FastAPI/Django 的代码分析逻辑 | V1.5 |
| **Git 历史分析 Skill** | 从 Git 历史中提取业务变更信息 | V1.5 |
| **PRD 解析 Skill** | 解析 Markdown/Word 格式 PRD | V1.5 |
| **DB Schema 解析 Skill** | 从 DDL 或 ORM 提取数据模型 | V1.5 |
| **Bug 模式识别 Skill** | 从历史Bug中识别高频缺陷模式 | V2.0 |
| **测试价值排序 Skill** | 基于多维度因素对用例进行价值排序 | V2.0 |
| **知识图谱构建 Skill** | 构建业务概念、规则、状态的关联图谱 | V2.0 |
| **测试脚本生成 Skill** | 从测试用例生成可执行测试脚本 | V2.0 |
| **风险预测 Skill** | 基于代码质量+变更+缺陷预测测试风险 | V3.0 |

---

## 十三、第三方依赖（完整版）

### 13.1 必须的第三方服务

| 服务 | 用途 | 推荐方案 | 阶段 |
|------|------|----------|------|
| **LLM API** | 核心推理能力 | DeepSeek API | MVP |
| **Embedding API** | 向量化代码和文档 | DeepSeek Embedding | MVP |

### 13.2 本地运行的组件

| 组件 | 用途 | 推荐方案 | 端口 | 阶段 |
|------|------|----------|------|------|
| **关系数据库** | 结构化数据存储 | SQLite | - | MVP |
| **向量数据库** | 代码片段语义检索 | ChromaDB | 8000 | MVP |
| **任务队列** | Agent异步任务调度 | Celery + Redis | 6379 | MVP |
| **缓存** | 会话和结果缓存 | Redis（共用） | 6379 | MVP |
| **知识图谱** | 业务知识关联存储 | Neo4j（可选） | 7474 | V2.0 |
| **本地小模型** | 轻量级NLP任务 | Ollama + Qwen2.5-7B | 11434 | V1.5 |

### 13.3 可集成的外部系统

| 系统 | 用途 | 阶段 |
|------|------|------|
| **Jira** | Bug记录导入 | V2.0 |
| **禅道** | Bug记录导入（国内常用） | V2.0 |
| **GitHub Issues** | Issue导入 | V2.0 |
| **GitLab** | CI/CD集成 | V2.0 |
| **Jenkins** | CI/CD集成 | V2.0 |
| **Confluence** | 文档导入 | V2.0 |

---

## 十四、成功指标（完整版）

| 指标 | MVP | V1.5 | V2.0 | V3.0 |
|------|-----|------|------|------|
| 状态机提取准确率 | ≥ 80% | ≥ 85% | ≥ 90% | ≥ 95% |
| 用例有效率 | ≥ 70% | ≥ 80% | ≥ 85% | ≥ 90% |
| XMind 文件可用率 | 100% | 100% | 100% | 100% |
| 端到端耗时 | ≤ 10min | ≤ 15min | ≤ 20min | ≤ 25min |
| 内存峰值占用 | ≤ 4GB | ≤ 5GB | ≤ 6GB | ≤ 8GB |
| 支持技术栈数 | 2 | 6 | 10 | 12 |
| Bug回归覆盖率 | - | - | ≥ 80% | ≥ 90% |
| 测试价值排序准确率 | - | - | ≥ 75% | ≥ 85% |
| 脚本可执行率 | - | - | ≥ 70% | ≥ 80% |

---

## 十五、风险与缓解（完整版）

| 风险 | 影响 | 概率 | 缓解措施 | 阶段 |
|------|------|------|----------|------|
| 代码结构复杂，AST解析困难 | 分析准确率低 | 中 | 初期聚焦Vue+Spring Boot，逐步扩展 | MVP |
| LLM推断的状态机不准确 | 用例质量差 | 中高 | 人工审核机制+置信度标记 | MVP |
| XMind格式兼容性 | 文件无法打开 | 低 | 使用成熟的python-xmind库 | MVP |
| LLM API调用成本 | 费用超预期 | 低 | DeepSeek极低成本+token限制 | MVP |
| 大型项目代码量过大 | 分析超时 | 中 | 分模块分析+增量分析+文件过滤 | MVP |
| Git历史信息噪音大 | 分析结果不准确 | 中 | 结合代码变更和commit message过滤 | V1.5 |
| Bug系统API差异大 | 集成困难 | 中 | 抽象统一接口，逐个适配 | V2.0 |
| 知识模型准确性 | 推荐质量差 | 中高 | 人工审核+持续学习优化 | V2.0 |
| 跨系统理解复杂度 | 微服务支持困难 | 高 | 逐步支持，先单体后微服务 | V3.0 |

---

## 十六、后续演进路线

```
V1.0 MVP（8周）→ 验证核心价值：代码→状态机→XMind
      ↓
V1.5 增强版（+6周）→ 扩展信息源：Git + 数据库 + PRD
      ↓
V2.0 完整版（+12周）→ 系统感知：Bug + 知识模型 + 测试脚本 + CI/CD
      ↓
V3.0 智能版（+12周）→ 智能进化：自学习 + 风险预测 + 微服务
      ↓
V4.0 平台版（未来）→ 多租户 + SaaS化 + 市场化
```