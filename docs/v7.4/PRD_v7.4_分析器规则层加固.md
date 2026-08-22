# PRD v7.4 — 分析器规则层加固

| 项 | 内容 |
|---|---|
| 版本 | v7.4 |
| 日期 | 2026-08-23 |
| 基线 | v7.3 |
| 范围 | 后端（分析器规则层 + 生成 prompt 信任度） |
| 对应风险清单 | A1 / A2 / A7 / A8 / A9 / A10 / A19 / A20 / C1 |

## 1. 背景与目标

v7.0–v7.3 修复了执行可信度、生成链路一致性、度量诚实化与 LLM 组件稳定性。v7.4 聚焦**分析器规则层**——分析结果是整个系统"代码证据链"的数据源，当前存在三类问题：

1. **数据污染**：测试 fixture 混入正式分析结果（A1）、方法级 `@RequestMapping` 的 HTTP 方法恒为 ANY 污染接口覆盖率分母（A2）。
2. **静默丢失**：模板字符串导致 rules 块括号计数错位、校验规则静默丢失（A7）；多 form 只取第一个 rules 块（A8）；LLM 补充被组件级整条丢弃（A10）；所有提取失败一律静默返回空列表，用户无法区分"真没有"和"解析失败"（C1）。
3. **不可复现与误导**：文件遍历顺序依赖操作系统，两次分析 LLM 看到的文件集合不同（A9）；规则兜底状态机无降级标记，生成侧不知道 transitions 不可信（A20）；另有约 120 行死代码（A19）。

**目标**：让分析结果"干净（无测试污染）、完整（规则不静默丢）、可复现（同输入同输出）、可观测（失败有告警）、不误导（兜底有标记）"。

## 2. 需求清单

### 2.1 A1 — SpringAnalyzer 排除 src/test 测试代码

- **现状**：主循环 `findJavaFiles` 只排除 target/build/node_modules/.git，`src/test/java` 下的测试 Controller/实体/断言规则混入 endpoints/enums/entities/businessRules；而依赖图（L215）与 LLM 增强源码收集（L585）单独排除了 test——三处口径不一致。
- **需求**：主循环统一排除 `src/test/` 路径下的文件，三处口径一致；排除数量计入 warnings（可观测）。
- **验收**：测试目录下的 Controller 不出现在 endpoints；warnings 含排除计数。

### 2.2 A2 — 方法级 @RequestMapping 解析 HTTP 方法

- **现状**：`mapHttpMethod("RequestMapping")` 恒返回 "ANY"，不解析 `method = RequestMethod.POST` 属性——老项目常见写法路径对但方法错，接口覆盖率分母被污染。
- **需求**：解析注解 `method` 属性（`RequestMethod.POST` / `{RequestMethod.GET, RequestMethod.POST}` 多值取第一个）；无 method 属性时保持 ANY。
- **验收**：`@RequestMapping(value = "/x", method = RequestMethod.POST)` 提取为 POST；多值取第一个；无属性仍为 ANY。

### 2.3 A7 — extractBalanced 识别模板字符串

- **现状**：Vue rules 常见反引号模板串（含 `${}`），花括号计数错位 → rules 块静默提取失败/截半，表单校验规则丢失无告警。
- **需求**：字符串状态机增加反引号分支（支持嵌套 `${}` 表达式、表达式内字符串/嵌套反引号、转义）。
- **验收**：rules 块内含模板串消息（如 `` message: `长度需在 ${min}~${max} 之间` ``）时整块完整提取。

### 2.4 A8 — 多 form 场景收集全部 rules 块

- **现状**：`rs.find()` 只取第一个 rules 块，一个 .vue 多表单/多 rules 对象时后续字段校验全部丢失或配错。
- **需求**：收集文件内全部 rules 块并合并（字段查找跨全部块）；多于一个块时计 warning（信息性提示）。
- **验收**：两个 rules 块分属两个字段，两个字段的校验规则均被提取。

### 2.5 A9 — 分析结果可复现（文件列表排序）

- **现状**：`collectVueFiles`/`collectJavaFiles` 依赖 `File.listFiles()` 顺序（OS 相关），12k/16k 截断按此顺序取子集——同一项目两次分析 LLM 看到的文件集合不同，叠加 temp 0.3 导致结果漂移。
- **需求**：文件列表按绝对路径字典序排序后再供所有下游（提取循环、源码截断、组件摘要）使用。
- **验收**：任意创建顺序下，forms/componentSummaries 等输出顺序稳定为路径字典序。

### 2.6 A10 — LLM 补充按字段级合并

- **现状**：`parseAndMergeSupplements` 中组件已存在即整条丢弃 LLM 补充的 form（含正则漏掉的字段）/selectors。
- **需求**：forms 改字段级合并（按字段 name 去重，正则已有字段保留、LLM 新字段追加）；domSelectors 改选择器级合并（按 type+value 去重）；componentStates（component+type）与 pageFlows（from+to）粒度已足够，维持现状。
- **验收**：正则已提取组件 A 的 1 个字段，LLM 补充组件 A 的 2 个字段（1 重 1 新）→ 最终 2 个字段，新增字段有日志。

### 2.7 A19 — 删除死代码

- **现状**：`StateMachineAgent.enhanceWithFrontend` / `mergeFrontendEnhancements`（约 120 行）v6.2 合并提取后无调用方；`toMap` 仅被死代码引用。
- **需求**：直接删除；`readStateCodeMap`/`normalizeState`/`text`/`firstNonBlank` 仍被存活代码（validateTransitions）使用，保留。
- **验收**：编译通过，现有 StateMachineAgentTest 全绿。

### 2.8 A20 — 规则兜底状态机降级标记传导到生成侧

- **现状**：LLM 失败时兜底状态机 transitions 恒为空、confidence 0.5，但无降级标记传递到用例生成层——生成 prompt 仍要求"transitions 数组必须包含本用例测试的状态转换"，等于诱导 LLM 对空数据虚构转换。
- **需求**：
  - 生成上下文中每个状态机附带 `source` 字段（从现有 `sources` JSON 派生：含 `rule_based` 且不含 `llm` → `rule`，否则 `llm`；**不新增数据库列**，避免 Flyway 迁移）；
  - system prompt 增加信任度规则：`source: rule` 的状态机仅状态枚举可信、transitions 为空，stateMachineRef.transitions 可为空数组，禁止虚构转换。
- **验收**：`sources=["rule_based"]` 的状态机在上下文中标记 `source: "rule"`；`["llm"]`/`["backend","frontend","llm"]` 标记 `llm`；prompt 含对应信任度指令。

### 2.9 C1 — 分析质量可观测（warnings）

- **现状**：VueAnalyzer 所有 extract 方法 `catch(Exception) return 空列表`，SpringAnalyzer 单文件失败静默 skip——用户看到"0 个表单/规则"无从知晓是真没有还是解析失败。
- **需求**：
  - `BackendResult`/`FrontendResult` 新增 `warnings: List<String>` 字段（随既有 JSON 序列化自动落库 code_analysis）；
  - SpringAnalyzer：单文件解析失败（文件名+数量）、src/test 排除计数、LLM 增强失败 写入 warnings；
  - VueAnalyzer：文件读取失败、rules 块括号不配对（A7 失败路径）、多 rules 块合并提示（A8）、各提取器整体异常、LLM 补充解析失败、组件摘要 LLM 失败计数 写入 warnings；
  - 前端展示本版不做（数据先落库，展示随后续版本）。
- **验收**：构造解析失败场景后 `result.getWarnings()` 非空且人可读；正常分析 warnings 为空列表（非 null）。

## 3. 非目标（明确不做）

- A4a（supplementalEndpoints 源码存在性校验）、A5（参数提取）、A17（状态机 ground truth）——第二批攻坚项。
- A11/A15（缓存）——v7.5。
- warnings 前端展示页——数据先落库，展示后置。
- VueAnalyzer `collectScriptFiles`（api 目录）排序——影响面小，随 v7.5 缓存统一处理。

## 4. 影响面评估

| 维度 | 评估 |
|---|---|
| API 契约 | 无 REST 变更；code_analysis JSON 新增 `warnings` 数组（前端未知字段自动忽略） |
| 数据库 | 无 schema 变更（A20 从现有 sources 派生，不加列） |
| LLM 成本 | 无新增调用；A9 排序使相同输入的 prompt 稳定 |
| 回归风险 | 低——均为规则层确定性改动，有单测覆盖；A7 状态机重写保留原有纯引号行为 |
