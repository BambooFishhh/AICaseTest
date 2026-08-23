# PRD v7.13 — 输入截断上限扩容

> 版本：v7.13 | 基线：v7.12
> 类型：轻量迭代（配置化 + 参数放大 + 一处合法性修复）

---

## 1. 背景与痛点

v7.12 收口后用户追问"除了 A4 哪里还有输入截断"。全链路盘点发现分析器层 4 处硬编码截断 + 1 处配置默认值偏小，全部是 v6.1 时代（模型 context 32k）的产物：

| 位置 | 现值 | 问题 |
|---|---|---|
| `SpringAnalyzer.collectSourceSnippets` 源码总量 | 16000 | 中大型项目后端源码只看前 16k 字符，方法体静默丢失 |
| `SpringAnalyzer.collectSourceSnippets` 单文件 | 1500 | 单个 Java 类只看前 1500 字符（约 30 行），Controller 方法体后半段全丢 |
| `SpringAnalyzer.buildRuleSummary` JSON 上限 | 30000 | **`json.substring(0, 30000)` 把 JSON 砍成非法 JSON**——下游靠 LLM 容错，本质是把问题推迟 |
| `VueAnalyzer.collectSourceSnippets` 源码总量 | 12000 | 前端源码只看前 12k 字符 |
| `VueAnalyzer` template/script 片段 | 800/700 | SFC 结构被硬切，交互逻辑静默丢失 |
| `llm.max-prompt-chars`（总闸） | 60000 | 上游放大后必撞此闸——上游头尾保留等设计全部失效 |
| `llm.max-context-chars`（生成侧拼接预算） | 24000 | RAG/文档上下文装入量偏小 |

现代主流模型 context 已到 128k+ tokens（约 25 万字符代码类内容），上述上限浪费了大量模型容量。用户决策：**从扩大字符量入手，简单修法**——不做结构感知截断、不做全局预算制（留待后续版本，大项目结构性方案已落盘至 `docs/大项目代码分析演进提案.md`，v8.x 候选）。

初次方案（48k/36k）经用户质疑修正：单文件 4000 字符只够看半个 Controller，总量 48k 仅覆盖 12-16 个文件——中大型项目仍大量丢源码。修正后按"大项目全覆盖"定参。

## 2. 范围

### In Scope

1. **7 处上限配置化 + 放大默认值**（上述全部，修正后参数见 3.1）
2. **buildRuleSummary 非法 JSON 修复**：超长时先减条目再序列化，保证 JSON 永远合法
3. **VueAnalyzer 文件确定性排序**：收集 .vue 文件后按"页面优先 + 路径排序"固定顺序——修复其注释自认的"两次分析看到的文件子集不同，结果漂移"问题（预算放大后该问题变尖锐，且修复仅需一个 Comparator）
4. 单测覆盖：JSON 合法性 + 默认值生效 + Vue 排序确定性

### Out of Scope（明确不做）

- 结构感知截断（JavaParser 方法签名级裁剪、Vue SFC 语义切分）——下版本攻坚
- VueAnalyzer 文件优先级排序（两次分析文件子集漂移问题）
- 全局 prompt 预算制（类别配额）
- L4b 大 PRD 分块解析
- MCP 60s 超时调整——**分析器文本链路走 Spring AI（read-timeout 900s），与 MCP 无关**；MCP 超时只影响多模态/Playwright 工具链路，本次不动
- PRD 解析链路（L4a/A13）——输入增大不涉输出侧，输出截断防线已有
- TestGeneratorAgent 生成侧 truncateStrings/truncateDocs 调用值——G17 已按相关性过滤，且生成输出大，输入进一步放大会挤输出
- boundPrompt 头部截断逻辑本身——保留作保险丝（触发即 ERROR 日志，说明上游预算失效）

## 3. 功能详情

### 3.1 配置项（application.yml `app.analyzer.*`）

| 配置项 | 默认值 | 原值 | 说明 |
|---|---|---|---|
| `app.analyzer.spring-source-total-chars` | 120000 | 16000 | SpringAnalyzer LLM 增强源码片段总量（≈30-40 个 Java 文件） |
| `app.analyzer.spring-source-per-file-chars` | 10000 | 1500 | SpringAnalyzer 单文件截断（完整覆盖绝大多数 Java 文件） |
| `app.analyzer.rule-summary-max-chars` | 80000 | 30000 | 规则提取结果 JSON 上限（合法化后） |
| `app.analyzer.vue-source-total-chars` | 96000 | 12000 | VueAnalyzer LLM 增强源码片段总量（≈20-30 个组件） |
| `app.analyzer.vue-template-chars` | 3000 | 800 | Vue template 片段 |
| `app.analyzer.vue-script-chars` | 3000 | 700 | Vue script 片段 |

另调整一处既有默认值：
- `llm.max-prompt-chars`：60000 → 300000（总闸 = 2k 指令 + 80k 规则摘要 + 120k 源码 + 余量；300k chars ≈ 90-120k tokens，128k context 模型贴边可用）

实现期发现并清理：`llm.max-context-chars` 为**死配置**——v6.1 登记后从未被任何 @Value 读取，生成侧实际截断由 TestGeneratorAgent.truncateStrings/truncateDocs 承担（G17 相关性过滤管理，Out of Scope）。该配置从 application.yml 移除。

### 3.2 VueAnalyzer 文件优先级排序

实现期核实：`collectVueFiles` 已有 v7.4(A9) 的绝对路径字典序排序（确定性已有），但纯字典序下 `components` 排在 `views` 之前——截断时组件把页面（交互入口、测试价值最高）挤出预算，优先级正好反了。

升级为"页面优先 + 字典序"：views/pages/App.vue > components > 其他，同级路径字典序。确定性保持（A9 语义不变），截断时优先保留页面。

### 3.3 buildRuleSummary JSON 合法化

旧实现：序列化后 `json.substring(0, 30000)` —— 拼接进 prompt 的是非法 JSON。

新实现：**先减条目再序列化**。按 5 轮收敛（每轮条目数 ×0.7），每轮重新序列化并检查长度；5 轮后仍超长则返回 counts-only 骨架（endpointCount 等总量信息保留，明细省略）。`endpointCount` 始终输出真实总数，LLM 可感知"明细被裁剪"。

### 3.4 实现细节约束

- `@Value` 字段**必须带字段初始化默认值**（如 `private int springSourceTotalChars = 120000;`）——现有单测直接 `new SpringAnalyzer()` 不走 Spring 容器，纯 @Value 注解下 int 字段为 0 会把全部源码截没
- 配置项全部走环境变量可覆盖（`APP_ANALYZER_SPRING_SOURCE_TOTAL_CHARS` 等），运维可按模型容量回调

## 4. 验收标准

1. `mvn test` 全量通过，含新增测试：
   - 超量条目下 buildRuleSummary 返回的 JSON 可被 `objectMapper.readTree` 解析且长度 ≤ 上限
   - 直接 `new SpringAnalyzer()` 时默认值生效（非 Spring 容器环境）
   - Vue 文件排序确定性（views 优先、同级路径字典序）
2. `npm run build` 通过（前端无改动，回归）
3. 大项目（>50 个 Java 文件）分析时日志不再出现"prompt 超限截断"（源码片段 120k + 规则摘要 80k + 指令 < 300k 总闸）
4. 手工验收建议（用户执行）：对真实大项目跑一次分析，观察 token 用量与分析质量变化

## 5. 风险与缓解

| 风险 | 评估 | 缓解 |
|---|---|---|
| token 成本上升（分析器增强 prompt ~7x） | 分析低频手动触发；qwen-max 单次约 ¥0.1-0.2 | 配置项可回调 |
| 输入增大 → 输出被 maxTokens 截断概率上升 | 分析增强输出为小 JSON（补充条目），风险低 | L8/A13 截断检测+抢救已有防线 |
| 300k chars 贴 128k 模型 context 上限 | 300k chars ≈ 90-120k tokens，贴边但可用 | boundPrompt 保险丝仍在（触发即 ERROR）；256k context 模型从容 |
| 分析耗时上升（首 token 时间变长） | Spring AI read-timeout 900s 容忍 | 无需动作 |
| 超大项目（500+ 文件）仍装不下 | 结构性问题，非本次范围 | `docs/大项目代码分析演进提案.md` 已落盘（分批增强/map-reduce/按需检索三层） |

## 6. 交付物

- [ ] `docs/v7.13/PRD_v7.13_输入截断上限扩容.md`
- [ ] `docs/v7.13/后端技术评审_v7.13.md`
- [ ] `docs/大项目代码分析演进提案.md`（v8.x 候选主题落盘）
- [ ] 后端实现 + 单测
- [ ] CHANGELOG / README / 风险清单（A4b 相关注记）/ 迭代计划更新
- [ ] git 提交推送
