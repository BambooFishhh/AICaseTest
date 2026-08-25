# 变更记录 (CHANGELOG)

本项目迭代基于 v1.0 MVP，目标演进为高可用、AI 可执行、高可视化的 AI 用例生成系统。

---

## v8.4 — 256k 上下文扩容与代码审查修复（容量参数化 + 可靠性/安防加固）
**日期**: 2026-08-26
**基线**: v8.3
**主题**: 全面适配 256k context 模型——生成链路全部截断预算参数化并放宽；本地代码审查修复落地——线程池饱和快速失败、流式重试端到端一致、解析逐条容错、向量层转义/字节截断/删除重试、SSRF 与目录越权收敛、prompt 注入防护。本期仅后端改动，无前端变更

### 背景

v8.3 收官后做本地全量代码审查，产出 13 项修复清单（见 `docs/长期迭代计划书.md` 基线说明）；同时切换 256k 模型后，历史硬编码截断阈值成为覆盖率与用例密度新瓶颈。本期合并处理。

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| `agent/PrdAgent.java` | 修改 | PRD 解析入口截断预算参数化：单文档 12000→40000、总量 24000→96000（`app.prd.*`），头尾各半保留策略不变——此处截太狠直接丢需求条目 |
| `agent/TestGeneratorAgent.java` | 修改 | **预算参数化**：endpoints 详情 80→160、rules 100→150、RAG 切片 1200×6→2000×8、失败经验 800×3→1200×5、文档原文 3000×3→12000×5、checklist.endpoints cap 150→250、gaps 六类 id cap 统一 150、生成上限 60→120（全部 `app.generation.*` 可回调）；**流式可靠性**：StreamingTestCaseParser.reset() + CaseCallback.onRetryReset() 钩子，LLM 重试前清空解析缓冲并通知消费端，防半截+全量重复推送；parseTestCases 逐条容错（单条畸形跳过告警不丢整批，整段非 JSON 数组才上抛重试）；extractJsonArray 遍历全部代码围栏取第一个可解析块（模型分段输出说明+JSON 时旧逻辑取错块）；type/priority 白名单归一 normalizeCaseType/normalizePriority（中文别名映射 positive/negative/boundary/data、P0~P3），防脏值入库污染统计筛选；**prompt 注入防护**：系统提示追加输入安全约定 + 用户上下文 `<context>` 定界隔离 |
| `service/LlmService.java` | 修改 | maxTokens 16384→32768（减少单轮高密度顶满输出致流式 JSON 截断）、maxPromptChars 300000→500000；**流级看门狗** `llm.stream-total-timeout-ms`（默认 900s）——网关心跳保活时底层 read-timeout 不触发，超时主动中断按可重试分类；chatStreaming 新增 retryResetHook 重载，已推送内容后失败重试先通知调用方清态；boundPrompt 超限裁剪改保头 3/4 + 保尾 1/4（旧纯头部截断丢尾部任务指令与 gaps 清单），触发即 ERROR（保险丝语义） |
| `config/AsyncConfig.java` | 修改 | 分析/生成池改快速拒绝 handler（log.error + 抛 RejectedExecutionException）——旧统一 CallerRunsPolicy 让分钟级 AI 任务回落 HTTP 线程阻塞全部普通接口；执行/语义短任务池保留 CallerRuns |
| `controller/ProjectController.java` | 修改 | 三个 SSE 流式接口 catch RejectedExecutionException → 推送 `error` 事件"服务器繁忙，任务队列已满"+ complete，替代静默挂死 |
| `service/ProjectService.java` | 修改 | startAnalysis/startGenerate catch 拒绝异常 → 项目状态回滚（analyzing/generating → 原状态）+ 503"服务器繁忙"，不卡状态机 |
| `agent/TestCaseReviewRunner.java` | 修改 | AI 评审提交被拒绝时 markReviewFailed("评审任务队列已满")，用例不再卡 reviewing |
| `service/TestCaseService.java` | 修改 | 两个流式入口 CaseCallback 实现 onRetryReset → SSE 推送 `retryReset` 事件，前端清空已渲染草稿后接收重推，消除界面重复卡片 |
| `service/MilvusService.java` | 修改 | **写入前字节截断** truncateToBytes：按 schema VarChar 上限（UTF-8 字节，多字节字符边界安全）预截断 id/project/title/module/text 并告警——中文超限插入失败仅 warn 曾致向量静默丢失（该条无法检索/去重）；**expr 转义** escapeExpr（反斜杠+双引号）：buildSearchExpr/deleteByProject/deleteByIds/deleteByModule 全收敛，module 含引号曾致 expr 语法错误检索静默空召回；**删除统一入口** deleteWithRetry：2 次短重试 + 终败升级 ERROR（残留向量=幽灵用例/误判重复，可接告警对账） |
| `service/SemanticService.java` | 修改 | 去重 embedding 全批并行预计算（4 线程 + CompletableFuture.allOf）替代逐条串行 HTTP——60 条用例省数十秒；单条失败降级空向量由结构判重兜底 |
| `controller/FilesystemController.java` | 修改 | **安防**：目录浏览收敛到 `app.filesystem.browse-roots` 白名单（默认 projects + git 克隆目录），根节点不再暴露系统盘符；子路径 toAbsolutePath().normalize() 后必须落在白名单内（旧 contains("..") 检查在 normalize 后实际无效） |
| `controller/McpBridgeController.java` | 修改 | **安防**：analyzeBackend/analyzeFrontend sourcePath 白名单校验（仅项目目录/git 克隆目录内），越界抛 40300；bridge token 改 MessageDigest.isEqual 常量时间比较防时序侧信道，空/弱 token 直接拒绝 |
| `service/GitCloneService.java` | 修改 | **安防 SSRF**：克隆前解析 http(s)/git 协议主机全部 A 记录，任一落在回环/私网/链路本地即拒绝；域名不可解析报 invalidParam；ssh/git@ 无法本地预解析不拦截仅日志告知（企业堡垒机场景）；已知残留 DNS rebinding 风险记录在案 |
| Controller ×17 | 修改 | 全部移除散落 @CrossOrigin，CORS 单轨化走 WebConfig.addCorsMappings（app.cors.allowed-origins）+ SecurityConfig cors |
| `application.yml` | 修改 | 新增配置族：`app.prd.*`、`app.generation.*`（11 键）、`llm.max-tokens`/`llm.stream-total-timeout-ms`、`app.filesystem.browse-roots`，全部 `${ENV:default}` 可环境变量回调 |
| 测试 ×3 | 修改 | PrdAgentTruncateTest/TestGeneratorAgentContextCapTest/TestGeneratorAgentContextFeedTest 改 ReflectionTestUtils 显式钉住旧阈值，验证截断机制本身不随生产默认值漂移 |

### API 契约变化

- **无破坏性变更**；SSE 新增两类事件：`error`（队列繁忙）、`retryReset`（流式重试清草稿）
- 新增配置键见 application.yml；128k 模型环境可将 `app.prd.*`/`app.generation.*` 回退旧值

### 验证结果

- 后端全量 `mvn test`（容器 maven:3.9-eclipse-temurin-17）：407 tests, 0 failures, 0 errors

---

## v8.3 — 覆盖率口径重构（Scope-Aware 第 3 期收官）
**日期**: 2026-08-24
**基线**: v8.2
**主题**: 覆盖率单一"本期范围"口径——全量口径彻底移除（用户决策）；分母收敛为已确认范围内的目标接口与本期目标转换；历史转换仅展示不参与统计；无已确认范围时返回引导态而非误导性数字；影响面（AFFECTED）清单透出

### 背景

v8.1/v8.2 已完成范围识别与生成收敛，但覆盖率仍以全项目为分母——历史功能撑起百分比，稀释本期验收信号。本期按既定决策将覆盖度量全面切换到本期口径。

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| `service/CoverageService.java` | 重构 | getCoverageMatrix/uncoveredEndpoints 切片化——无 confirmed 范围返回 `{scoped:false}` 引导态；SM 循环仅遍历范围内状态机，每条转换附 `inScope` 标记，planned/executed 双栏仅对本期转换统计；接口分母=slice.targetEndpointIds；新增 `affectedItems`（AFFECTED 条目清单）与 `scope` 元信息；transition 归一化键复用 ScopeSlicingService.sprintTransitionKeys 与切片分类同源防口径漂移 |
| `service/ScopeSlicingService.java` | 修改 | 新增公共静态 sprintTransitionKeys（转换集合→归一化键集） |
| `service/TestCaseService.java` | 修改 | calculateCoverage/buildCoverageForReview 同口径收敛：calculateCoverage 输出 scoped 字段、状态转换 refs 经 normalizeTransitionRefs 归一后匹配切片键；buildCoverageForReview 转换/接口清单仅含本期项（AI 评审的覆盖建议随之只针对本期） |
| `controller/StatsController.java` | 修改 | projectCoverage 未 scoped 项目 stateRate=null 不计入加权平均 |
| 测试 | 修改 | CoverageServicePlannedExecutedTest/CoverageServicePreparseTest 注入切片 mock（全部转换视为本期目标）；StatsControllerOverviewTest coverage mock 补 scoped=true |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| `views/TestCaseList.vue` | 修改 | 统计卡 scoped=false 显示 '—' + 口径 tooltip 更新为"本期范围"；矩阵区替换为引导 alert（链接本期范围页） |
| `components/CoverageMatrix.vue` | 修改 | 描述改"分母=已确认本期范围"；新增「范围」列（本期/历史 tag）；历史行覆盖单元格显示 '—' |
| `views/StateMachineOverview.vue` | 修改 | 未建范围顶部引导 alert；覆盖徽标/卡片仅统计 inScope 转换 |
| `views/Dashboard.vue` | 修改 | stateRate=null 的项目不进覆盖率柱状图（避免误导性 0 柱） |
| `views/ProjectDetail.vue` | 修改 | **本期范围主流程化**——入口从「查看」区提升到主线操作卡（开始分析↔生成用例之间），代码驱动项目未确认范围时按钮 warning 态+「未确认」标记；点生成弹窗引导直达范围页（替代裸报错）；SSE 分析完成与轮询恢复两处终态钩子在未确认范围时发 ElNotification 下一步引导 |
| `service/ScopeService.java` | 修改 | **范围单例约束**——一个项目同一时间仅允许一个本期范围（草稿或已确认），重复创建报 40901 并提示"刷新用重算/换期先删除"，消除多范围并存时分母取最新的静默歧义 |
| `components/ScopeDrawer.vue` | **新增** | 本期范围改为**项目内全高抽屉（72%）**，删除独立路由 `/projects/:id/scope` 与 ScopeReview 页面：无范围时内联创建表单（三步向导条）替代长等待弹窗，提交即收起、进度横幅接管（识别耗时 1-2 分钟不再扣住用户）；统计徽章条（总数/接口/状态机/Git识别/LLM映射/手动）+ 类型筛选 + 关键字搜索 + 变更药丸/来源圆点视觉重做；确认/删除加二次确认 |
| `views/TestCaseList.vue`、`views/StateMachineOverview.vue` | 修改 | 未建范围引导链接改 `?scope=1` 跳项目详情自动展开抽屉（ProjectDetail 监听该参数） |

### API 契约变化

- `/coverage/matrix`、`/coverage/uncovered-endpoints`、用例列表内联 coverage 均新增顶层 `scoped`(bool)；scoped=false 时不再输出全量数字（破坏性变更，前端已同步）
- matrix 每个 transition 新增 `inScope`(bool)；响应新增 `scope` 元信息与 `affectedItems`

### 验证结果

- 后端全量 `mvn test`：405 tests, 0 failures, 0 errors
- 前端 `npm run build` 通过
- **Docker 生产部署实测**（v8.3 收尾补充）：镜像重建后 Flyway V13 在 MySQL 迁移成功；litemall 夹具全链路冒烟通过——git-refs 容器内真实执行、范围草稿识别出 70 条 AUTO_DIFF 接口 + 4 个受影响状态机、生成前置拦截精确命中、确认后覆盖矩阵 scoped=true 且分母=范围集合（接口 31/85 与条目数精确吻合）
- **修复**：GitDiffService 输出截断 bug——`tryRunGit` 的 4000 字符通用上限被 diff 复用后，变更集超过 ~60 个文件即被静默砍尾（实测 v1.7.0...HEAD 完整输出 31,983 字符/591 文件/120 java 只留到 doc/ 目录，AUTO_DIFF 全丢）。diff 调用改传 2MB 上限并新增文件数/java 数/原始长度 INFO 日志
- **修复②**：端点 id 归一化大小写错位——`ScopeSlicingService.normalizeEndpointId` 整串 toUpperCase（含 path），与全部消费方"METHOD 大写+path 原样"口径永不相等，导致 **scope 模式接口覆盖恒 0% 且生成注入的 context.endpoints/checklist.endpoints 为空**。改为仅 METHOD 大写；TestCaseService.calculateCoverage 分母同步改用 targetEndpointsDetail 与 CoverageService 同源（实测两路径接口覆盖 31/85=36.5% 完全一致）
- **修复③**：流式同题草稿堆卡——去重键仅 trim+lowercase，放行 LLM 标题的**不可见空白变体**（中间空格/NBSP \u00A0/零宽 \u200B-\u200D/全角 \u3000），后端 wrapPushDedup 与前端 upsert 双双失效致流式重复卡片（落库侧强指纹去重正常，最终库 26 行 26 唯一标题）。新增 `dedupTitleKey`（剥离全部空白字符+小写）并同步前端 streamedTitleKey，两层口径统一；新增 2 条单测（407 全绿）

---

## v8.2 — 本期聚焦生成（Scope-Aware 第 2 期）
**日期**: 2026-08-24
**基线**: v8.1
**主题**: 生成目标只聚焦本期范围——状态机切片（sprint 目标/历史上下文二分）、BFS 确定性推导 setup 路径（LLM 只填数据不找路径）、prompt 分层注入与 phase 步骤标记（setup/verify）、执行 blocked 语义（前置失败 ≠ 用例失败）、生成前置校验升级（代码驱动项目必须先确认范围，破坏性变更）

### 背景

v8.1 已能确认"本期范围"，但生成链路目标集合仍是全项目接口/转换；且本期功能常依赖历史状态（如发货依赖订单已支付），LLM 只能凭空猜前置路径。核心原则：**本期需求定义"测什么"，历史代码只出现在前置条件与准备步骤中**。

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| `service/ScopeSlicingService.java` | **新增** | 切片服务——latestConfirmed 范围 + ENDPOINT/STATE_MACHINE 条目 → ScopeSlice{targetEndpointIds, targetEndpointsDetail, sprint/historicalTransitionsBySmId, setupHints}；转换分类复用证据匹配（normalizeStateCode 归一 + file ∈ changed_files）；BFS 求"初始态→目标转换源状态"最短路径输出 trigger 文案骨架（排除目标转换自身，无初始态标记时以未被 to 引用的状态兜底）；requireConfirmedScopeIfCodeDriven 共用校验 |
| `agent/TestGeneratorAgent.java` | 修改 | **新 overloads**（generate/generateStreaming 带 slice 参数，既有签名不动保单测兼容）逐层透传 runPrdPipeline→generateByLlmWithPrd→generatePrdRound→buildCoverageChecklist；**checklist 收敛**——slice 非空时 endpoints/transitions 清单仅含本期项（coverageRefs 对账天然受限）；**context.scope 注入**——targets（接口完整详情+sprint 转换）/historicalTransitions/setupHints 确定性注入不走检索；SM 上下文仅保留范围内 SM 且每条转换标 role=本期目标/历史上下文；epList 先按 scope 过滤再走 G25 容量；**prompt**——HEADER 任务段加范围约束句、FOOTER 新增「本期范围」段（目标/历史角色规则+setupHints 用法+phase 字段规范）、structuredSteps 要求加 phase 说明、few-shot 增补 setup+verify 分层示例 |
| `agent/OrchestratorAgent.java` | 修改 | GenContext 扩展 slice 字段；loadGenerationContext 尾部加载切片（EMPTY 时纯 PRD 行为不变）；generate/streaming 切换到带 slice 重载；进度提示"已加载本期范围" |
| `service/ProjectService.java` | 修改 | triggerGenerate 在 PRD 校验后追加 requireConfirmedScopeIfCodeDriven（sourcePath 非空且有分析结果的项目强制） |
| `service/TestCaseService.java` | 修改 | runGenerate/runGenerateStream/runGenerateStreamAppend 三入口同款校验 |
| `service/ExecutionService.java` | 修改 | **blocked 语义**——Agent/程序化两循环解析 step.phase，setup 步骤 failed（含异常分支）即 break 并置 errorMessage="前置准备失败:…"；终态判定 blocked 分支（summary 携带原因），不进 determineStatus；blocked 不写失败经验库（failed 守卫天然排除）；getStats 增加 blocked 计数 |
| `service/ReportService.java` | 修改 | 批次 HTML 报告汇总增加 blocked 行（含处置建议文案） |
| `service/ScopeService.java` | 修改 | createDraft 对非 Git 仓库不再拒绝——创建空草稿（autoIdentified:false 提示手动添加条目），保证纯 PRD 项目也能建立范围通过 v8.2 校验 |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| `components/TestCaseCard.vue` | 修改 | structuredSteps 渲染：step.phase==='setup' 显示灰色「准备」徽标；执行状态 blocked 文案统一为"已阻断" |
| `views/ExecutionResult.vue`、`ExecutionHistory.vue`、`BatchResult.vue` | 修改 | status 映射表增加 blocked:'已阻断'（warning） |
| `views/ScopeReview.vue` | 修改 | createDraft 响应 autoIdentified:false 时提示"非 Git 仓库已建空草稿，请手动添加条目" |

### API 契约变化

- 无新增端点；createScope 响应增加可选字段 `autoIdentified`
- executionStatus/status 新取值 `blocked`（此前仅手动标记可达，现由执行引擎自动写入）

### 验证结果

- 后端全量 `mvn test`：405 tests, 0 failures, 0 errors
- 前端 `npm run build` 通过

---

## v8.1 — 范围感知基础（Scope-Aware 第 1 期）
**日期**: 2026-08-24
**基线**: v7.15
**主题**: 引入「本期范围(Scope)」领域概念——Git 基线 diff 自动识别本期变更接口与受影响状态机 + LLM 辅助补充映射 + 人工确认锁定；为 v8.2（本期聚焦生成）与 v8.3（覆盖率口径重构）提供分母来源。本期不改生成/覆盖率行为，完全向后兼容

### 背景

系统此前无法区分"本期需求代码"与"历史代码"：生成与覆盖率均以全项目为口径，用例大量覆盖历史功能，稀释本期验收价值。核心设计理念：**本期需求定义"测什么"（精确集合），历史代码定义"怎么测才不破坏"（上下文）**。范围圈定应是精确集合运算（Git diff）而非语义检索问题。

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| `db/migration/mysql/V13__add_scope_tables.sql` | **新增** | scope_definition（name/baseline_ref/status/changed_files）+ scope_item（item_type/item_ref/change_kind/origin/note）；H2 dev 由 JPA ddl-auto 建表 |
| `entity/ScopeDefinition.java`、`ScopeItem.java` | **新增** | 范围定义/条目实体（含类型/变更/来源常量） |
| `repository/ScopeDefinitionRepository.java`、`ScopeItemRepository.java` | **新增** | JPA 仓储 |
| `service/GitDiffService.java` | **新增** | 本地 Git 只读操作——isGitRepo/listRefs（本地分支+远端分支+tag+HEAD）/diffFiles（--name-status 三点 diff 失败回退两点；D 忽略、R100 取新路径；引用名防注入校验） |
| `agent/ScopeMappingAgent.java` | **新增** | 需求↔接口 LLM 辅助映射（PRD≤8000 字符 + 接口≤120 条 → [{method,path,reason}]；解析失败/LLM 异常降级空表不阻断） |
| `service/ScopeService.java` | **新增** | 识别流水线——diff 文件集→EndpointInfo.file 归一化后缀双向匹配出 ADDED/MODIFIED 接口→stateTransitions 证据 {field,from,to,file} 关联 SM 标 AFFECTED→LLM 补充去重合并；recompute 保留 MANUAL 重建其余；confirm 要求非空且仅 draft 可写 |
| `controller/ScopeController.java` | **新增** | /api/projects/{projectId}/scope 全套 CRUD + git-refs/items/recompute/confirm |
| `service/GitCloneService.java` | 修改 | clone 参数 `--depth 1` → `--filter=blob:none --no-single-branch`（partial clone：保留全分支引用支持跨基线 diff，体积可控） |
| `service/ProjectService.java` | 修改 | deleteProject 级联清理 scope 两表 |
| `service/DataHealthService.java` | 修改 | tableCounts 增加 scope_definition/scope_item |
| `service/BackupService.java` | 修改 | 导出 ZIP 增加 scope.json（定义+各自 items） |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| `api/scope.js` | **新增** | Scope API 封装 |
| `views/ScopeReview.vue` | **新增** | 定义列表（展开行显示条目表格）+ 新建对话框（基线按 heads/remotes/tags 分组下拉、allow-create 兜底）+ 手动添加条目对话框 + confirm/recompute/delete 操作；confirmed 只读 |
| `router/index.js` | 修改 | 注册 `/projects/:id/scope` |
| `views/ProjectDetail.vue` | 修改 | 「查看」卡片新增"本期范围"入口 |

### API 契约变化

全部新增端点：GET/POST `/api/projects/{projectId}/scope`、GET `.../git-refs`、GET/POST `.../{definitionId}/items`、DELETE `.../{definitionId}/items/{itemId}`、POST `.../{definitionId}/recompute`、POST `.../{definitionId}/confirm`、DELETE `.../{definitionId}`

### 验证结果

- 后端全量 `mvn test`：405 tests, 0 failures, 0 errors
- 前端 `npm run build` 通过

---

## v7.15 — 执行可信与双编号制
**日期**: 2026-08-23
**基线**: v7.14
**主题**: 流式重复草稿治理（跨轮推送去重+前端 upsert 兜底）、用例双编号制（全局 TC-id + 项目内 project_seq）、未覆盖接口清单（缺口可操作化）、覆盖率口径标注、执行数据防御三件套（HTTP 形态 target / 非法 uiSelector 类型）、PrdPanel 保存联动刷新

### 背景

一次真实使用暴露三类可信度问题：①追加生成时同题草稿卡在界面堆叠 N 次——运行镜像为回退前源码构建（standard 档误为 6 轮）放大了多轮补齐对未收敛缺口的反复再生成，而 SSE 推送发生在落库去重之前；②v7.11 全局编号修复后新项目首条用例直接是 TC-171，用户失去项目内序号感知；③接口覆盖与状态机矩阵口径不同却无数值外说明，28/86 未覆盖接口不可见不可操作。另有 TC-171 六步全败的执行案例：LLM 把 `GET /wx/home/index` 塞进 ui_action 的 target、uiSelector 使用执行器不支持的 `ref` 类型、期望文本叙述化不可断言——页面本身正常，脏数据导致全败。

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| `agent/TestGeneratorAgent.java` | 修改 | **wrapPushDedup 跨轮推送去重**——推送链改为 wrapPushDedup(wrapFocusFilter(caseCb))，按标题（忽略大小写/空白）只推首见，仅收敛 SSE 展示，轮次收集与落库 deduplicate() 不变；**A prompt 硬约束**——ui_action target 严禁 HTTP 方法+路径格式、uiSelector.type 白名单对齐执行器真实能力（id/css/class/data-testid/aria-label/xpath，删除执行器不支持的 text/path/ref）、无精确匹配时省略 uiSelector 禁止虚构；**B sanitizeUiSelectors**——解析两处 LLM 输出与 enrichStructuredSteps 写回统一清洗非法类型 uiSelector |
| `agent/ExecutionAgent.java` | 修改 | **C 脏数据防御分流**——ui_action 的 target 匹配 `^(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)\s+/\S+$` 时自动降级 skip 并如实标注"接口引用而非页面元素"，不再进入找元素→截图→定位→点击流水线 |
| `service/ProjectSeqAllocator.java` | **新增** | 项目内展示序号分配器——per-project AtomicInteger 缓存 + 冷启动加载 max(project_seq)；reset() 供全量重生成清缓存 |
| `service/TestCasePersistenceService.java` | 修改 | replaceAll 清库后 reset 分配器并逐条分配序号（全量重生成序号从 1 重计） |
| `service/TestCaseService.java` | 修改 | 追加/JSON 导入/XMind 导入/复制/手动创建五条路径接入 project_seq 分配 |
| `entity/TestCase.java`、`dto/TestCaseDTO.java` | 修改 | 新增 projectSeq 字段并透出列表/详情响应 |
| `repository/TestCaseRepository.java` | 修改 | 新增 findMaxProjectSeq 查询 |
| `db/migration/mysql/V12__add_project_seq.sql` | **新增** | 加列 project_seq + ROW_NUMBER 物化派生表 JOIN 回填存量（规避 MySQL 1093）+ (project_id, project_seq) 索引；仅 mysql profile Flyway 执行，H2 dev 由 JPA ddl-auto 建列 |
| `service/CoverageService.java` | 修改 | **3b uncoveredEndpoints**——分母取最新分析接口全集，covered=计划引用∪已执行调用（与接口覆盖率完全同口径），返回差集清单 |
| `controller/CoverageController.java` | 修改 | 新端点 GET /api/projects/{projectId}/coverage/uncovered-endpoints |
| `test/.../TestGeneratorAgentExecutionGuardTest.java` 等 | **新增** | 跨轮去重 3 用例、uiSelector 白名单 3 用例、ExecutionAgentStepTypeTest 增 C 防御 1 用例 |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| `views/TestCaseList.vue` | 修改 | **upsertStreamedCase** 两处 onCase 统一按标题 upsert（兜底防堆卡）；编号列改显 #projectSeq（悬浮见 TC-id，空值回退）；新增未覆盖接口折叠面板（收起显 N/M+提示，展开列 method/path/描述）；统计卡口径 tooltip + 说明行；移除用户可见版本标注 |
| `components/CoverageMatrix.vue` | 修改 | 矩阵描述补"分母为状态机全部转换"；移除版本标注泄漏 |
| `components/PrdPanel.vue` | 修改 | 新增 saved emit（persistDocs 成功后触发） |
| `views/ProjectDetail.vue` | 修改 | 监听 PrdPanel @saved → refreshProject()，保存 PRD 后生成按钮即时解禁 |
| `api/coverage.js` | 修改 | 新增 getUncoveredEndpoints |

### API 契约变化

- 新增：`GET /api/projects/{projectId}/coverage/uncovered-endpoints`
- 兼容扩展：testcases 响应新增 `projectSeq`

### 其他

- docker-compose 待分析项目只读挂载改名：`/app/projects/litemall` → `/app/projects/litemall-mall`（DB 存量 source_path 同步更新）
- 生产镜像治理：v7.15 开发期间曾以含未提交改动（6/8 轮）的源码构建后端镜像并上线运行，已重建回提交版（3/4 轮）——镜像构建必须以干净基线为准

### 验证结果

- 后端全量 `mvn test`：405 tests, 0 failures
- 前端 vitest 7/7 通过；`npm run build` 通过
- 生产实测：Flyway v12 应用成功；列表接口 projectSeq 连续正确（TC-171→#1…）；
  uncovered-endpoints 返回 total=86 / covered=58 / uncoveredCount=28 与统计卡一致

---

## v7.14 — 生成 Prompt 重复注入治理
**日期**: 2026-08-23
**基线**: v7.13
**主题**: 修复真实大项目（220 接口/182 规则）生成 prompt 432KB 触发 300k 保险丝的根因——coverageChecklist 全量详情重复注入（159KB 冗余，G24）+ context.endpoints 无容量控制（G25）+ embedding 默认端点 404 致 RAG 持续降级（E17）

### 背景

用户实测日志：`[LLM] prompt 超限 432497 → 截断到 300000`，`[G17] endpoints 220/220, rules 182/182`。拆解发现 432KB 中约 159KB 是纯重复：`buildCoverageChecklist` 对每个接口 `putAll(ep.toContextMap())` 把 requestBody/responseBody/businessLogic 完整字段塞进覆盖清单，而同一内容已在 context.endpoints/businessRules/operationDependencies 完整出现过一次；G17 过滤阈值 `>0`（任一 token 重叠即过）在需求词汇覆盖广的大项目全放行且无总量控制；embedding 默认端点回落 chat 网关（无 embeddings 路由，404），RAG 检索持续降级为空。

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| `agent/TestGeneratorAgent.java` | 修改 | **G24 覆盖清单摘要化**——endpoints 项只留 `{id, method, path, function}`、rules 项 `{id, ruleType, rule 截80}`、dependencies 项仅 `{id}`、components 项 `{id, component, summary 截80}`（消费方核实：remainingGaps/TestCaseReviewAgent 只读 id/method/path；requirements/transitions 不动——G4 兜底匹配依赖 title/description）；**G25 容量控制**——G17 过滤后超上限（endpoints 80/rules 100）按相关性降序稳定排序保留 top-N + 尾部追加截断说明条目，关键词空白保序截断；**prd map 剥离 ragContexts**（策展版 context.ragContexts 已单独注入，模板无 prd.ragContexts 路径引用已核实） |
| `service/EmbeddingService.java` | 修改 | E17 失败日志带模型名诊断（404=模型不存在/端点错配，401=密钥问题一眼可判） |
| `application.yml` | 修改 | E17：embedding 默认端点改 DashScope 兼容模式（`https://dashscope.aliyuncs.com/compatible-mode/v1`，不再回落 chat 网关）、默认模型 `qwen3.7-text-embedding`→`text-embedding-v4`（1024 维=Milvus 默认）；新增 `app.generation.endpoints-context-max:80`/`rules-context-max:100` |
| `application-prod.yml` | 修改 | embedding 段同款默认值修正 |
| `docker-compose.yml` | 修改 | E17：`LLM_EMBEDDING_BASE_URL` 空 `:-` 默认改 DashScope 端点——原空默认把变量设为空串，Spring 占位符对"已设置但为空"不回落 yml 默认值，空 base-url 落 api.openai.com + 不存在模型名 → 404；模型默认值同步 text-embedding-v4 |
| `.env.example` | 修改 | embedding 配置默认值与注释指引同步 |
| `test/.../TestGeneratorAgentChecklistSummaryTest.java` | 新增 | 4 用例：endpoint 项无详情字段、rule 截 80、dependency 仅 id、requirements 结构回归保护 |
| `test/.../TestGeneratorAgentContextCapTest.java` | 新增 | 5 用例：top-80 高相关必入选、未超限同实例、空白关键词保序、同分稳定排序确定性、rules top-100 |

### 前端变更

无（零改动回归）。

### API 契约变化

无。

### 验证结果

- 后端全量 `mvn test`：全部通过（v7.14 新增 2 个测试类 9 用例）
- 前端 `npm run build`：通过

### 预期影响（用户实测场景复算）

- 432KB → 约 200KB：checklist 重复注入 159KB→约 12KB（摘要化）；context.endpoints 128KB→约 47KB（top-80）；context.businessRules 50KB→约 28KB（top-100）；prd 剥离 ragContexts 原始切片；300k 保险丝不再触发
- 未入选 top-80 的接口仍在 checklist 摘要中可引用（id/method/path/function），coverageRefs 对账协议零变化
- embedding 修复后 RAG 检索恢复——需求/组件相关上下文质量回升，前端组件语义摘要不再降级为空
- 评审 prompt 同步瘦身（评审 payload 携带同一 checklist）

### 配置项速查

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `app.generation.endpoints-context-max` | 80 | context.endpoints 完整详情上限（top-N 相关性） |
| `app.generation.rules-context-max` | 100 | context.businessRules 完整详情上限 |
| `LLM_EMBEDDING_BASE_URL` | DashScope 兼容端点 | 不再回落 chat 网关 |
| `LLM_EMBEDDING_MODEL` | text-embedding-v4 | 1024 维=Milvus 默认 |

---

## v7.13 — 输入截断上限扩容
**日期**: 2026-08-23
**基线**: v7.12
**主题**: 分析器 LLM 增强输入预算配置化并放大至"大项目全覆盖"（Spring 120k/10k、Vue 96k/3k+3k、总闸 300k）+ 规则摘要非法 JSON 修复 + Vue 文件页面优先排序 + 死配置清理；大项目结构性方案（分批增强/map-reduce/按需检索）落盘为 v8.x 候选提案

### 背景

v7.12 收口后盘点全链路输入截断点：分析器层 4 处硬编码（Spring 源码总量 16k/单文件 1500、Vue 源码总量 12k/template 800/script 700、规则摘要 30k）+ 总闸 60k，全部是 v6.1 时代按 32k context 模型定的保守值，现代模型 128k+ tokens 容量大量闲置。其中 `buildRuleSummary` 的 `json.substring(0, 30000)` 会把 JSON 砍成**非法 JSON** 塞进 prompt。用户决策从扩大字符量入手（简单修法），结构感知截断/全局预算制/大项目分批方案留后续——大项目三层演进方案（分批增强 → map-reduce 摘要 → 按需检索）已落盘 `docs/大项目代码分析演进提案.md`。

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| `application.yml` | 修改 | 新增 `app.analyzer.*` 6 项配置（全部环境变量可覆盖）；`llm.max-prompt-chars` 默认 60000→300000；**移除死配置 `llm.max-context-chars`**（登记后从未被任何 @Value 读取，生成侧实际截断由 truncateStrings/truncateDocs 承担） |
| `analyzer/SpringAnalyzer.java` | 修改 | 源码总量 16000→120000、单文件 1500→10000（≈30-40 个 Java 文件全覆盖）；**buildRuleSummary 合法化收敛**——旧实现 substring 砍出非法 JSON，新实现每轮条目 ×0.7 重新序列化直至 ≤80k，5 轮后兜底 counts-only 骨架，endpointCount 恒为真实总数 |
| `analyzer/VueAnalyzer.java` | 修改 | 源码总量 12000→96000、template 800→3000、script 700→3000（≈20-30 个组件全覆盖）；**collectVueFiles 排序升级**——A9 字典序确定性保留，新增页面优先级（views/pages/App.vue > components > 其他），修复纯字典序下 components 把页面挤出预算的优先级反转 |
| `service/LlmService.java` | 修改 | maxPromptChars 默认值同步 300000（@Value 兜底默认值与 yml 一致；boundPrompt 保险丝语义不变） |
| `test/analyzer/SpringAnalyzerRuleSummaryTest.java` | 新增 | 4 用例：超量条目仍合法 JSON 且 ≤80k、endpointCount 保真（500 条截断后计数仍 500）、少量直通不降采样、极限小上限兜底 counts 骨架 |
| `test/analyzer/VueAnalyzerFileOrderTest.java` | 新增 | 4 用例：views 排 components 前、pages/App.vue 同级优先、同级字典序 + 两次收集一致（A9 保持）、node_modules 跳过 |

### 前端变更

无（零改动回归）。

### API 契约变化

无。

### 验证结果

- 后端全量 `mvn test`：全部通过（v7.13 新增 2 个测试类 8 用例）
- 前端 `npm run build`：BUILD SUCCESS

### 预期影响（输入容量扩容）

- 分析器 LLM 视野：Spring 16k→120k（约 12-16 文件 → 30-40 文件）、Vue 12k→96k（约 9-10 组件 → 20-30 组件）——中大型项目源码覆盖大幅提升，A4b（LLM 看不全源码）影响面收窄
- 规则摘要永远合法 JSON（旧实现截断时下游靠 LLM 容错）
- Vue 截断时页面优先保留（此前 components 字典序挤占页面）
- token 成本：分析增强调用 prompt ~7x（qwen-max 单次约 ¥0.1-0.2，低频手动触发可接受；配置可回调）
- 超大项目（500+ 文件）仍有天花板——结构性方案已落盘提案，v8.x 按需启动

### 配置项速查

| 配置项 | 默认值 | 原值 |
|---|---|---|
| `app.analyzer.spring-source-total-chars` | 120000 | 16000（硬编码） |
| `app.analyzer.spring-source-per-file-chars` | 10000 | 1500（硬编码） |
| `app.analyzer.rule-summary-max-chars` | 80000 | 30000（硬编码 substring） |
| `app.analyzer.vue-source-total-chars` | 96000 | 12000（硬编码） |
| `app.analyzer.vue-template-chars` | 3000 | 800（硬编码） |
| `app.analyzer.vue-script-chars` | 3000 | 700（硬编码） |
| `llm.max-prompt-chars` | 300000 | 60000 |
| ~~`llm.max-context-chars`~~ | 已移除 | 24000（死配置） |

---

## v7.12 — 复审 P1/P2 修复
**日期**: 2026-08-23
**基线**: v7.11
**主题**: 复审遗留 P1/P2 七项修复——reject 比例分母、选择器池混入表单字段、判重门槛与口径对齐、熔断半开探测、Redis 信号量租约模型、执行报告流式生成、SSE 瞬断误报失败（对应风险清单 R15/G22/G23/L15/E15/R16/E16）

### 背景

v7.11 收口后对全项目复审，确认 P1/P2 缺陷清单：**R15** LLM 评审 reject 比例分母用送评总数——reject 只能来自已评审条目，缺评条目计入分母稀释比例（20 条仅 10 条有输出且全 reject：真实 100% 被算成 50% 灰区，>70% 全保留保护带在截断场景失灵）；**G22** 选择器池混入表单字段（{name, type: 输入框类型, label} 无可执行 value）——文本匹配胜出后写入 `uiSelector = {type: "text", value: null}` 的废选择器固化进用例资产；**G23** 存储侧判重与生成侧口径不一致——TestCaseService 不比较 type（追加生成的负向/边界用例被同标题正向旧用例误杀）、重叠率阈值仍是 0.8、子串规则无最短门槛（"登录" vs "退出登录后重新登录" 误杀）；**L15** 熔断开启期过后全量放行——LLM 单调用 40-120s，几十个 doomed 请求会在首个失败重新打开熔断之前全部涌入；**E15** Redis 信号量计数器+TTL 模型——长执行（>10min，Agent 模式常见）下键过期导致计数清零超发，且释放无持有者语义（acquire 降级内存后 release 扣减 Redis 双向漂移偷槽位）；**R16** 执行报告整串装配——全部截图 base64 聚在 StringBuilder 再 toString()，峰值 = 2×报告体积（百步级 Agent 执行含双截图可达数百 MB）；**E16** SSE 断连误报失败——连接层断开（网络瞬断/代理空闲超时）与后端下发错误共用 error 分支，后端任务仍在跑却报"生成失败"。

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| `agent/TestCaseReviewAgent.java` | 修改 | **R15**：reject 比例分母从 `cases.size()` 改为 `byIndex.size()`（已评审数）——缺评保护由 R4 补评机制负责，两道防线各司其职 |
| `agent/TestGeneratorAgent.java` | 修改 | **G22**：选择器池只收 DOM 选择器，删除表单字段分支——表单字段无可执行 value，宁留空由 Agent 模式执行时 LLM 自定位（与 L12"宁留空不赌错"原则一致）；**G23**：子串判重加最短门槛——短标题 4 字及以上才构成包含判重证据 |
| `service/TestCaseService.java` | 修改 | **G23**：isDuplicate 对齐生成侧 v7.1(G1) 语义——标题类判重必须 type 一致；重叠率阈值 0.8→0.9；子串规则加最短门槛（与 TestGeneratorAgent 同步） |
| `common/LlmCircuitBreaker.java` | 修改 | **L15**：补半开状态机——开启期过后单探测租约（probeLeaseUntil），仅一个请求试探 provider 恢复，成功才全量放行；探测失败重新打开；租约超时自愈（探测无回调不卡死半开）；新增 `llm.circuit.probe-lease-seconds` 配置（默认 120s 覆盖最长 LLM 调用） |
| `runtime/RuntimeStore.java` | 修改 | **E15**：acquire/tryAcquire/release 携带 permitId（=executionId）；新增 renewProjectPermit 续租接口（内存实现 no-op） |
| `runtime/MemoryRuntimeStore.java` | 修改 | **E15**：适配新签名——内存 Semaphore 语义不变，忽略 permitId |
| `runtime/RedisRuntimeStore.java` | 修改 | **E15**：计数器+TTL 改 ZSET 租约模型——member=permitId、score=授予/续租时刻，Lua 脚本原子化 acquire（清理过期租约→检查 ZCARD 上限→ZADD 登记）/release（ZREM 按持有者精确移除，幂等）/renew（ZSCORE 校验持有者后刷新 score）；租约 5 分钟 + 步骤心跳续租防长执行过期，JVM 崩溃后 5 分钟自愈；acquire 降级内存时登记 permitId，release 按授予来源路由（修复双向漂移） |
| `service/ProjectExecutionLimiter.java` | 修改 | **E15**：acquire/tryAcquire/release 透传 permitId；新增 renew 续租入口 |
| `service/ExecutionService.java` | 修改 | **E15**：全部 acquire/release 调用携带 executionId；touchHeartbeat 同时续租配额（活跃执行租约不过期） |
| `service/ReportService.java` | 修改 | **R16**：新增 `generateExecutionReport(executionId, Writer)` 流式版——HTML 分段写出即时 flush、截图逐张"读取→编码→写出→释放"（峰值 = 单截图 + base64 缓冲）；String 版委托 StringWriter（交付语义不变）；批次报告无截图维持 String 返回 |
| `controller/ExecutionController.java` | 修改 | **R16**：单次执行报告端点改流式响应——`response.getWriter()` 直写，报告不再整串驻留内存；自包含 base64 单文件交付语义不变（下载/分享行为零变化） |
| `test/agent/TestCaseReviewAgentRejectRatioTest.java` | 新增 | R15 3 用例：截断场景缺评不稀释（10/20 全 reject → 全保留）、全量评审全 reject 同样全保留、低比例照删 |
| `test/agent/TestGeneratorAgentSelectorPoolTest.java` | 新增 | G22 3 用例：仅表单字段的池不写 uiSelector、DOM 选择器命中写入可执行 uiSelector、已有选择器不覆盖 |
| `test/agent/TestGeneratorAgentDedupTest.java` | 修改 | G23 4 用例：2 字/3 字子串不判重、4 字子串判重、子串规则要求 type 一致 |
| `test/service/TestCaseServiceDedupTest.java` | 新增 | G23 7 用例：同标题不同 type 不判重、0.85/0.95 重叠率两侧阈值、子串门槛两侧一致 |
| `test/common/LlmCircuitBreakerHalfOpenTest.java` | 新增 | L15 5 用例：OPEN 全拒、半开单探测（并发第二个拒绝）、探测成功全放行、探测失败重开、租约超时再探测 |
| `test/runtime/RedisRuntimeStorePermitTest.java` | 新增 | E15 5 用例：内存授予路由释放、重复释放幂等、内存授予续租跳过 Redis、Lua 脚本 ZSET 语义校验、Redis 授予续租走脚本 |
| `test/service/ReportServiceEvidenceMissingTest.java` | 修改 | R16 1 用例：Writer 流式版三态渲染语义不变且完整收尾 |
| `test/service/ExecutionServiceQueueTimeoutTest.java` | 修改 | E15 回归：适配 tryAcquire 新签名，v7.11 三用例语义不变 |
| `test/runtime/MemoryRuntimeStoreTest.java` 等 3 个 | 修改 | E15 回归：适配 permitId 新签名 |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| `src/api/testcase.js` | 修改 | **E16**：streamGenerate/streamGenerateAppend 的 error 分支区分后端下发错误（e.data 有值 → onError）与连接层断开（e.data 为空 → onDisconnect）；新增可选 onDisconnect 回调 |
| `src/views/TestCaseList.vue` | 修改 | **E16**：重新生成/追加生成两处传 onDisconnect——断连降级为项目状态轮询（与刷新恢复 resumeGenerationIfActive 同构），不再误报"生成失败" |
| `src/views/ProjectDetail.vue` | 修改 | **E16**：分析 SSE error 分支同构区分——断连先刷新项目状态再复用 resumeActiveStatus() 轮询（终态自动提示并刷新），后端错误维持现状 |

### API 契约变化

- **`GET /api/executions/{executionId}/report`**：响应改为流式分块写出（Content-Type/Content-Disposition 不变）；对客户端（浏览器预览/下载/分享）行为零变化
- 其余 REST 接口无变更

### 验证结果

- 后端全量 `mvn test`：381 用例全部通过（v7.12 新增 4 个测试类 18 用例 + 既有 5 类扩充/适配）
- 前端 `npm run build`：BUILD SUCCESS

### 预期影响（P1/P2 修复）

- 评审保护带可靠：截断场景下批量 reject 不再被缺评条目稀释，真实高危（>70% reject）可靠触发全保留（R15）
- 用例资产干净：废选择器（表单字段混池）不再固化进用例；两侧判重口径一致，追加生成的负向/边界用例不再被误杀（G22/G23）
- LLM 故障恢复平滑：熔断开启期过后单探测试探，恢复确认前不再放行 doomed 请求风暴（L15）
- 多实例并发配额正确：长执行不再因键过期超发配额；释放按持有者精确路由，跨存储漂移消除；JVM 崩溃 5 分钟自愈（E15）
- 大报告不爆内存：百步级 Agent 执行报告峰值从 2×报告体积（数百 MB）降到单截图 + base64 缓冲（R16）
- 断连不再误报：网络瞬断/代理超时后降级轮询跟踪进度，后端任务照常完成并提示（E16）

---

## v7.11 — 关键缺陷修复
**日期**: 2026-08-23
**基线**: v7.10
**主题**: 全量代码审查暴露的 7 项关键缺陷修复——流式错误死循环、跨项目用例 ID 撞号静默覆盖、并发执行共享浏览器互相干扰、补测循环不收敛、不可变空容器炸评审链路、排队取消状态被翻转、终态可被迟到收尾改写（对应风险清单 L14/T1/T2/E12/G21/T3/E13/E14）

### 背景

v7.10 收口缓冲区后对全项目做了一轮完整代码审查，新发现一批此前风险清单未覆盖的关键缺陷，按 P0/速赢分级处理。**L14** 流式 LLM 调用的 error 信号不释放 CountDownLatch——外层 `await` 轮询永不退出（线程死循环），且异常处理先查取消标志，网络错误被谎报为"用户取消生成"；**T1/T2** test_cases.id 是全局主键而编号处处按项目独立分配（生成从 TC-001 重编、导入/复制取项目内 max+1、手动创建用 size()+1）——JPA 对非 null id 的 save 走 merge，跨项目同号用例静默整行覆盖他行；**E12** Playwright MCP Server 只持有一个全局 browser/context/page，并发执行任务共享同一浏览器实例（导航抢页、录屏互串、取消一个任务全局杀浏览器）；**G21** 补测循环以 hasRemainingGaps 为续跑条件，但 componentIds/dependencyIds 缺口不随补测收敛，导致无限循环烧 token 直到手数上限；**T3** JsonHelper 空值/解析失败返回不可变 Collections.emptyMap()，调用方直接 put 补充字段抛 UnsupportedOperationException 炸掉整条评审链路；**E13** 排队任务被取消后排队超时路径仍翻转 failed 且 exec:cancel 标志不清理（内存版 RuntimeStore 永久残留）；**E14** AgentTask 收尾类操作无终态保护，CANCELLED 可被排队超时 fail()/迟到 worker succeed() 覆盖。

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| `service/LlmService.java` | 修改 | **L14**：doOnError 内 done.countDown()——error 信号必须释放 latch；errorRef 检查提前到取消判定之前（真实错误优先，不再谎报"用户取消"）；GenerationCancelledException 经 error 信号回流时语义原样透传 |
| `service/TestCaseIdAllocator.java` | 新增 | **T1/T2**：用例 ID 全局唯一分配器——JVM 内 synchronized + AtomicInteger 缓存，首次取号冷启动加载全库 TC- 前缀最大数字后缀，此后纯内存递增（单实例部署无跨进程竞争） |
| `service/TestCaseService.java` | 修改 | **T1/T2**：手动创建（弃 size()+1）、追加生成、JSON/XMind 导入、跨项目复制四条新建路径全部改走全局分配器，且批量路径逐条取号保证缓存与已落库编号同步推进 |
| `agent/TestGeneratorAgent.java` | 修改 | **T1**：批内编号从 TC-001 连续重编改走全局分配器（单测未注入时回退旧编号）；**G21**：hasRemainingGaps 收敛检查收窄到 requirementIds/transitionIds/endpointIds/ruleIds 四类可收敛缺口，componentIds/dependencyIds 不再触发补测循环 |
| `dto/JsonHelper.java` | 修改 | **T3**：parseMap/parseListMap/parseListString 空值与解析失败一律返回可变容器（LinkedHashMap/ArrayList），杜绝 UnsupportedOperationException |
| `service/AgentTaskService.java` | 修改 | **E14**：新增 TERMINAL_STATUSES 终态集合与 skipIfTerminal 守卫——succeed/fail/cancel 遇终态任务跳过翻转并 log.warn，终态不可逆 |
| `service/ExecutionService.java` | 修改 | **E13**：排队超时路径检查 cancelled 状态——已取消任务保持终态不再翻转 failed；runtimeStore.clearFlag 清除残留 exec:cancel 标志；**E12**：browserLaunch/stopRecording 传会话 ID，会话 ID 以 executionId 派生（exec-<executionId>），markRunningCancelled 兜底取会话 |
| `skill/PlaywrightRecordSkill.java` | 修改 | **E12**：全部方法增加 sessionId 参数并透传 MCP Server；截图文件名带会话前缀防并发毫秒级时间戳碰撞；closeSession 只关指定会话且吞异常（收尾不中断） |
| `playwright-mcp-server/index.js` | 修改 | **E12**：全局 browser/context/page 改为 sessions Map（sessionId → {browser, context, page}），所有工具增加 session_id 参数；browser_launch 复用已存在会话 ID 时先关旧会话；未传 session_id 走 default 会话保持向后兼容 |
| `test/service/TestCaseIdAllocatorTest.java` | 新增 | T1/T2 5 用例：跨项目冷启动取全库 max、连续分配不重复、resetCache 重载、parseSuffix 边界 |
| `test/skill/PlaywrightRecordSkillSessionTest.java` | 新增 | E12 6 用例：launch/navigate/screenshot/stopRecording/closeSession/getPageStatus 全链路 session_id 透传、截图文件名会话前缀、关浏览器吞异常 |
| `test/agent/TestGeneratorAgentGapConvergenceTest.java` | 新增 | G21 5 用例：组件/依赖缺口不触发循环、四类可收敛缺口各自触发、空缺口收敛 |
| `test/service/ExecutionServiceQueueTimeoutTest.java` | 新增 | E13 3 用例：取消终态保持、pending 超时仍 failed、残留标志清理 |
| `test/dto/JsonHelperTest.java` | 修改 | T3 1 用例：null/空串/坏 JSON 三种输入的兜底容器均可 put/add |
| `test/service/AgentTaskServiceTest.java` | 修改 | E14 4 用例：succeed/fail 不覆盖 CANCELLED、cancel 不覆盖 SUCCEEDED、RUNNING 正常翻转 |
| `test/service/LlmServiceTest.java` | 修改 | L14 2 用例：流式 error 释放 latch 且如实上报真实原因（非"取消"）、取消语义经 error 信号回流原样透传 |

### MCP Server 契约变化

- playwright-mcp-server 全部工具新增 `session_id` 参数（字符串，缺省 `default`）：不传时行为与旧版一致（单会话），传不同 ID 即多会话隔离
- `browser_launch` 返回值为会话 ID 文本；重复 launch 同一 ID 会先关闭旧会话再新建
- 后端 Java 侧调用已全部携带 `exec-<executionId>` 派生会话 ID，同一执行的多次操作、并发执行的各自任务均互不干扰

### API 契约变化

- 无 REST 接口变更
- **用例编号口径切换**：新创建用例（生成/追加/导入/复制/手动）的 id 为全库唯一递增（TC-001…按全库 max+1），存量项目内编号不迁移——旧数据可能出现跨项目同号（历史遗留），但不再新增撞号；跨项目同号存量行仍建议人工核对
- Agent 任务终态（SUCCEEDED/FAILED/CANCELLED/NEEDS_REVIEW/DLQ）不可再被后续 succeed/fail/cancel 翻转

### 验证结果

- 后端全量 `mvn test`：353 用例全部通过（v7.11 新增 5 个测试类 19 用例 + 既有 3 类扩充 7 用例）
- 前端 `npm run build`：BUILD SUCCESS（无代码变更，回归构建）
- playwright-mcp-server：Node 语法校验通过，会话隔离逻辑由 Skill 侧单测覆盖参数透传

### 预期影响（关键缺陷修复）

- 流式生成不再挂死：LLM 上游错误（超时/网络/配额）秒级如实上报，不再出现"永远生成中"的僵死任务与"用户取消"误报（L14）
- 数据不再静默丢失：跨项目/删号复用不再发生同号 merge 整行覆盖，用例 ID 全局唯一（T1/T2）
- 并发执行真正可用：每个执行任务独立浏览器实例，取消/收尾只影响自身会话，录屏截图不再互串（E12）
- 生成成本可控：组件/依赖缺口不再触发无限补测循环烧 token（G21）
- 评审链路稳定：LLM 输出为空/畸形时评审兜底容器可写，不再整链路崩溃（T3）
- 状态机语义诚实：取消终态不可被翻转，排队超时不再改写已取消任务，残留标志清理避免同 ID 复用受污染（E13/E14）

---

## v7.10 — 缓冲区收尾
**日期**: 2026-08-23
**基线**: v7.9
**主题**: 缓冲区 17 项 + 补入 G7/G19 两项计划遗漏，共 19 项收口——需求 ID 内容 hash 稳定化、索引维护移出热路径、流式单解析、RAG 分类配额、评审缺评补评与三分带、失败经验库治理、thinking 配置诚实化、分析器启发式扩展、证据链对账（对应风险清单 G7/G19/G8/G9/G12/G13/G18/R4/R5/R13/L3/L12/A3/A6/A12/A18/C2，A14/C3 正式关闭）

### 背景

v7.0–v7.9 完成 A 速赢区与 B 攻坚区后，复盘发现两项原排 v7.5 的条目实际未落地：**G7** req-N 仍是解析顺序临时编号——A15 缓存落地后"同一 PRD 两次生成"已稳定，但 PRD 一变更全量编号漂移，追加生成时旧用例 `coverageRefs.req-3` 与新 checklist 的 `req-3` 可能指向不同需求，覆盖率历史对比失真；**G19** `ensureRequirementContexts`（Milvus 索引维护）仍在生成热路径内——保存侧四条路径已全部触发重建，热路径这次调用属于"读路径藏写操作"。缓冲区剩余问题分七组：生成链路（**G8** 流式/全量双解析索引错位致重复/漏推、**G9** RAG 查询顺序截断挤占 contextDocs 配额、**G12** 多轮重复注入原文纯浪费 token、**G13** 置信度硬编码 0.8 无信息量）；评审（**R4** 输出截断时部分用例无结果且无告警、**R5** reject 半数保护"恰好一半"时真垃圾也全留）；失败经验（**G18** 需求形查询 vs 动作形语料向量天然弱、**R13** 失败记录无去重且文本贫瘠）；LLM 配置（**L3** thinking 开关是"幻觉配置"——打开无效果但用户以为已生效）；生成质量（**L12** 选择器匹配阈值 2 过宽，"删除"匹配到"批量删除"）；分析器（**A3** 异常只记第一个、**A6** 空指针防御全算业务规则、**A12** apiCalls 只扫 src/api、**A18** Integer/String 状态提不出）；一致性（**C2** PRD 与代码两条证据链无新鲜度/一致性校验静默分叉）。

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| `agent/TestGeneratorAgent.java` | 修改 | **G7**：requirement id 从顺序编号改内容 hash——`req-` + SHA-256(title+\u0001+description) 前 10 位十六进制（RAG 并入项 `rag-` 同款 hash），同一需求任意解析顺序/轮次 id 一致，PRD 局部修改只影响变更项；**G8**：StreamingTestCaseParser 新增收集列表为唯一返回值，流式分支直接返回收集结果，删除"完整响应重解析 + parsedCount 索引补推"双解析路径（保留 0 解析兜底全量重解析）；**G12**：第 2+ 轮不再注入 prdDocs/contextDocs/补充需求原文（保留结构化 prd 摘要 + gaps + 已生成摘要）；**G13**：confidence 从硬编码 0.8 改为 qualityScore/100（评分自 v7.8 起含评审结论）；**L12**：bestSelector 阈值 2→3 且要求唯一最高分（并列最高宁留空由 Agent 模式执行时自定位）；**C2**：context 注入 evidenceStale/evidenceInconsistencies（过期/冲突提示进 prompt） |
| `agent/OrchestratorAgent.java` | 修改 | **G19**：删除 loadGenerationContext 内 ensureRequirementContexts 调用——索引维护只在保存侧四条路径（updatePrd/uploadPrdPdf/fetchPrdUrl/updateProjectContext）；**G9**：buildRagQueries 分类别配额（requirements 6 / modules 3 / contextDocs 2 / supplementary 1）取代顺序截断，删除 app.rag.max-queries 配置；**G18**：retrieveFailures 改失败专用查询（前 6 条需求查询 + 操作/页面关键词后缀），embedding 调用数 12→6；**C2**：applyEvidenceStaleness（project.updatedAt 晚于代码分析/状态机时间 → SSE 提示"建议重新分析"）+ stateFlow 一致性检查（PRD 状态流在代码状态机无任何状态命中 → evidenceInconsistencies 备注"以代码为准，需人工确认"） |
| `agent/TestCaseReviewAgent.java` | 修改 | **R4**：byIndex putIfAbsent 去重保留首个；缺失 index 的用例子集二次送评（子集规模减半，截断概率大幅下降）；补评后仍缺 log.warn 告警并保留该用例（未评审≠删除）；**R5**：reject 保护三分带——>70% 全保留+告警、40%–70% 按置信度逐条裁决（confidence ≥ 0.75 才删）、≤40% 照删 |
| `agent/StateMachineAgent.java` | 修改 | **A18**：constants 启发式加类名语义过滤（类名不含状态语义的工具常量类不再当枚举喂 LLM） |
| `analyzer/SpringAnalyzer.java` | 修改 | **A3**：异常提取收集方法内全部 ThrowStmt 的 ObjectCreationExpr（去重上限 5 条，原只记第一个）；**A6**：NOISE_EXCEPTIONS 过滤集（NullPointerException/IllegalStateException/IllegalArgumentException 等 JDK/Spring 通用异常）不进业务规则，过滤量进 warnings；**A18**：状态字段检测扩展——字段名含 status/state/type 且 Integer/String 字面量时提取为状态候选 |
| `analyzer/VueAnalyzer.java` | 修改 | **A12**：apiCalls 扫描范围从 src/api 扩到全部 .vue/.js/.ts 文件（排除 node_modules），状态机前端证据变全 |
| `dto/PrdAnalysisResult.java` | 修改 | **C2**：新增 evidenceStale（证据过期标志）与 evidenceInconsistencies（PRD 与代码冲突清单）字段 |
| `service/SemanticService.java` | 修改 | **R13**：recordFailure 稳定 ID `fail-` + SHA-256(projectId+归一化 action+归一化 error) 前 16 位，写入前 deleteByIds 同 ID 覆盖（同源失败不堆积占满 topK）；语料富化为 `[用例标题 \| 页面URL \|] action -> error` |
| `service/ExecutionService.java` | 修改 | **R13**：失败记录调用点透传用例标题与页面 URL |
| `service/LlmService.java` | 修改 | **L3**：@PostConstruct 启动告警——thinking 配置开启时 warn 明示"Spring AI OpenAI starter 无法透传 enable_thinking，该配置不生效（咨询性配置）" |
| `resources/application.yml` / `.env.example` | 修改 | **L3**：thinking 三项配置注释标注咨询性（开启不生效） |
| `resources/skills/*.md` ×3 | 修改 | **G7**：prompt 中 requirementIds "用 req-N" 表述改为"原样使用 coverageChecklist.requirements[].id" |
| `test/agent/TestGeneratorAgentIdStabilityTest.java` | 新增 | G7 6 用例：内容 hash 确定性、重排序不变、局部修改只影响变更项、rag- 同款 hash |
| `test/agent/TestGeneratorAgentStreamParseTest.java` | 新增 | G8 5 用例：收集结果与解析数一致、分块输入、0 解析兜底全量重解析 |
| `test/agent/TestGeneratorAgentConfidenceTest.java` | 新增 | G13 4 用例：confidence = qualityScore/100、高质量高置信 |
| `test/agent/TestGeneratorAgentBestSelectorTest.java` | 新增 | L12 4 用例：阈值 3、唯一最高分才绑定、并列留空 |
| `test/agent/TestCaseReviewAgentMissingIndexTest.java` | 新增 | R4 4 用例：缺失子集补评、重复 index 保留首个、补评仍缺失保留用例 |
| `test/agent/TestCaseReviewAgentRejectBandTest.java` | 新增 | R5 4 用例：>70% 全保留、40–70% 置信度裁决、≤40% 照删 |
| `test/analyzer/SpringAnalyzerStateHeuristicsTest.java` | 新增 | A3/A6/A18 8 用例：多异常收集、噪音过滤、Integer/String 状态提取、过滤量进 warnings |
| `test/analyzer/VueAnalyzerApiCallsTest.java` | 新增 | A12 3 用例：全目录扫描、去重、上限 |
| `test/agent/OrchestratorAgentConsistencyTest.java` | 新增 | C2 5 用例：证据过期检测、状态流一致性、冲突注入 evidenceInconsistencies |
| `test/service/SemanticServiceFailureRecordTest.java` | 新增 | R13 6 用例：稳定 ID 去重、语料含标题与 URL、归一化一致性 |
| `test/analyzer/SpringAnalyzerTest.java` | 修改 | A6 行为变更同步：测试 fixture 改用业务语义异常（噪音异常已被过滤） |

### 部署配置变化

| 配置 | 默认 | 说明 |
|---|---|---|
| `app.rag.max-queries` | 删除 | G9 分类别配额（6+3+2+1）取代总量截断，该配置不再读取 |
| `LLM_THINKING_*` | false | L3 注释标注：三项均为咨询性配置，开启不生效（Spring AI OpenAI starter 无法透传 enable_thinking） |

### API 契约变化

- 无 REST 接口变更
- **requirement id 口径切换**：新需求 id 为 `req-<hash10>`（内容 hash），存量旧 `req-N` refs 不迁移——与旧实现"顺序漂移即失配"等价，无回退风险；重新生成后 coverageRefs 将逐步切换到新口径
- 用例 `confidence` 从硬编码 0.8 变为 qualityScore/100（0–1 区间有信息量）
- 失败经验记录 id 从随机 UUID 变为内容 hash 稳定 ID（同源失败覆盖更新）

### 验证结果

- 后端定向测试：新增 10 个测试类 54 用例 + 既有 SpringAnalyzerTest 行为同步，全量 `mvn test` 通过（327 用例）
- 前端 `npm run build`：BUILD SUCCESS（无代码变更，回归构建）

### 预期影响（缓冲区收尾与口径稳定）

- 覆盖率历史对比可信：需求 id 内容 hash 后 PRD 局部修改不再全量编号漂移，追加生成的新旧用例 refs 口径对齐（G7）
- 生成热路径纯读：索引维护移出后生成延迟不再被 Milvus 写入拖累；v6.4 前存量项目首生成检索空走优雅降级，重新保存需求资料即重建（G19）
- 流式推送不再错位：单一解析真源消除重复推送/漏推（G8）；RAG 四类查询各有配额不再被挤占（G9）；多轮 token 省约 20k×轮数（G12）
- 评审语义完备：截断缺评自动补评一轮且不再静默（R4）；reject 保护三分带消灭"恰好一半真垃圾全留"盲区（R5）
- 错题本可用：失败记录去重不占满 topK，语料含标题/URL 后"需求 vs 动作"的向量形状错配改善，检索调用减半（R13/G18）
- 配置不再说谎：thinking 开关打开即见启动告警与注释说明（L3）
- 分析器信噪比：多异常全收集（A3）、空指针防御不进业务规则（A6）、全目录 apiCalls（A12）、Integer/String 状态可提取（A18）
- 证据链分叉可见：PRD 更新未重新分析、PRD 状态流与代码状态机冲突时，SSE 提示 + prompt 显式标注"以代码为准，需人工确认"（C2）
- 风险清单 A/B/C 三区全部收口，剩余仅 D 延后区（效率成本 > 准确率收益，维持既定原则不做）

---

## v7.9 — 执行效率与证据存储
**日期**: 2026-08-23
**基线**: v7.8
**主题**: 执行链路效率与可靠性收尾——生效判断省 LLM 调用、批量入口限流防挂死、ID 碰撞消除、复制执行权限可收敛、证据丢失可见化（对应风险清单 E6/E7/E9/E10/R11）

### 背景

v7.8 完成评审闭环后，执行链路仍有五个效率/可靠性问题：**E6** 生效判断的 LLM 调用在"页面已变化"场景纯冗余——LLM 的输入（URL+标题+文本快照）与本地 `pageChanged` 三指纹比较完全相同，页面已变化时花钱调 LLM 拿同样的结论还可能误判；**E7** 批量执行无数量上限，execution 池 queue 满触发 CallerRunsPolicy 后浏览器自动化（单条数分钟）跑在 HTTP 请求线程上——接口挂死、batchId 不返回、用户重试产生重复批次，且项目并发配额 acquire 无限阻塞占满线程；**E9** 执行记录/步骤/批次 ID 均取 UUID 前 8 位（32bit），约 7.7 万条记录 50% 碰撞，步骤表按执行数×步骤数累积迟早撞——JPA save 静默覆盖另一条步骤；**E10** 复制执行仅 VIEW 权限即可对目标环境真实执行删除类用例；**R11** 报告截图读取失败静默返回空串——多实例部署（截图在另一实例本地盘）下报告必然缺图且无任何告警。

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| `agent/ExecutionAgent.java` | 修改 | **E6**：`askLlmIfEffective` 两级化——本地三指纹（URL/title/textSnippet）任一变化直接判生效**不调 LLM**（常见成功路径每点击步骤省一次 LLM 调用）；指纹完全相同才调 LLM 终审且 prompt 明示"快照无变化"事实（降低无证据幻觉式判生效）。**E9**：步骤 ID 经 `newStepId()` 从 8 位加长到 16 位十六进制（64bit） |
| `service/ExecutionService.java` | 修改 | **E7**：`executeBatch`/`copyExecute` 入口限流——空列表拒绝（50013）、单批 > 100 拒绝并提示分批（50014）；`acquireProjectPermitOrTimeout` 排队超时——`app.executor.project-acquire-timeout-minutes`（默认 30，<=0 禁用保持旧行为）超时该条执行记 failed（"项目执行并发排队超时"）不再无限阻塞，agent_task 标 QUEUE_TIMEOUT，无僵尸 running；**E9**：执行记录 ID/batchId/copyId 经 `newId()` 加长到 16 位；**E10**：`copyExecute` 权限开关 `app.execution.copy-execute-require-operate`（默认 false 保持 VIEW 口径，true 要求 OPERATE） |
| `runtime/RuntimeStore.java` | 修改 | **E7**：新增默认方法 `tryAcquireProjectPermit(projectId, maxPermits, timeoutMs)`——默认退化为无限等待（旧语义），双实现覆盖 |
| `runtime/MemoryRuntimeStore.java` | 修改 | **E7**：`Semaphore.tryAcquire(timeoutMs)` 带超时实现 |
| `runtime/RedisRuntimeStore.java` | 修改 | **E7**：自旋循环加 deadline 超时返回 false；Redis 异常回退内存实现同样带超时 |
| `service/ProjectExecutionLimiter.java` | 修改 | **E7**：新增 `tryAcquire(projectId, timeoutMs)` 透传 |
| `service/ReportService.java` | 修改 | **R11**：`imageToBase64` 三态语义——null=无截图（不渲染）/""=路径非空但读取失败（渲染"截图文件缺失"告警占位，含丢失路径与共享卷提示）/base64=正常渲染；读取失败记 warn 日志；报告新增 `.shot-missing` 告警样式 |
| `test/agent/ExecutionAgentEffectiveCheckTest.java` | 新增 | E6+E9 6 用例：指纹变化跳过 LLM 调用（verify never）、指纹相同调 LLM 且 prompt 含"快照无变化"、无 LLM 三指纹兜底、LLM 异常回退指纹比较、步骤 ID 16 位十六进制 |
| `test/service/ExecutionServiceBatchLimitTest.java` | 新增 | E7+E9+E10 8 用例：批量 101 条被拒（50014）、复制执行同限流、空批被拒、100 条正常受理、`newId()` 16 位十六进制、执行记录 ID/batchId 长度验证、开关 false 入口走 VIEW、开关 true 入口走 OPERATE |
| `test/runtime/MemoryRuntimeStoreTryAcquireTest.java` | 新增 | E7 4 用例：配额占满超时返回 false（真实等待）、可用配额立即获取、释放后可重取、acquire/tryAcquire 共享同一信号量池 |
| `test/service/ReportServiceEvidenceMissingTest.java` | 新增 | R11 2 用例：单报告三态并存（无截图不渲染/双坏路径双告警/好文件渲染 base64/告警含丢失路径）、`imageToBase64` 三态语义直测 |

### 部署配置变化

| 配置 | 默认 | 说明 |
|---|---|---|
| `EXECUTOR_PROJECT_ACQUIRE_TIMEOUT_MINUTES` | 30 | 项目执行并发配额排队超时（分钟），<=0 禁用（旧无限等待行为）；docker-compose 已透传 |
| `APP_COPY_EXECUTE_REQUIRE_OPERATE` | false | 复制执行权限收敛——false 保持 VIEW 即可（v4.3 现状），true 要求 OPERATE；docker-compose 已透传 |

### API 契约变化

- 无 REST 接口变更
- 新执行记录/步骤/批次 ID 长度从 8 位变为 16 位（String 主键，旧 8 位记录共存无需迁移）
- 批量/复制执行超限时返回业务错误 50014（HTTP 400，提示分批执行）
- 生效判断行为变化：页面指纹变化时不再调用 LLM（结论等价：旧版 LLM 基于相同证据几乎恒判生效）

---

## v7.8 — 评审闭环与覆盖率可信
**日期**: 2026-08-23
**基线**: v7.7
**主题**: 闭环回写——评审建议分级生效、endpoint 匹配不再"洗白"编造接口、覆盖矩阵计划/执行双栏、质量评分并入评审结论（对应风险清单 R1/R3/R7/G6）

### 背景

v7.7 完成上下文精准投喂后，评审与度量侧仍有四个"闭环断裂/数据失真"问题：**R1** LLM 评审的 suggestedChanges 从未被应用——prompt 承诺"可自动采纳的修正"，实际 fix 与 pass 唯一区别是存了个标签，评审发现的问题原样入库；**R3** endpoint 模糊匹配起始阈值 0.65+method 加分 0.2=0.867，编造的 `/api/order/delete` 能匹配兄弟路径 `/api/order/cancel`（2/3 token 相同）——编造接口被记为覆盖真实接口，覆盖率虚高的系统性来源；**R7** 覆盖矩阵主路径读 coverageRefs 不要求执行、兜底路径要求 isExecuted——两路标准不一致且只输出一栏 rate，用户把"计划覆盖 80%"当"验证过 80%"；**G6** 质量评分是纯"形式分"（字段填没填），无内容正确性维度——LLM 编造字段填满 = 高分，去重"保留高分者"时编造越全越容易挤掉真实用例。

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| `agent/TestCaseReviewAgent.java` | 修改 | **R1**：`applyReview` 分级采纳——confidence ≥ 0.8 时 coverageRefs 建议（并集合并，只增不减）与 priority 建议（枚举校验 P0-P3）自动应用，已采纳字段登记 `aiReview.autoApplied`；title/module/type 涉及正文改写保留前端"待人工确认"入口；空 refs 建议 no-op 不登记。**R3**：`matchEndpoint` 重构两级匹配——① 精确：归一化路径完全相等（method 一致或用例侧未标注）② 高门槛模糊：method 严格一致 + token 相似度 ≥ 0.9 + 双方 token 数一致（仅容分隔符/近形差异），编造的 CRUD 兄弟路径（0.667）不再被洗白、同路径不同 method（旧 0.8 过线）不再误配；模糊命中额外记入 `coverageRefs.fuzzyEndpointIds` 供前端提示 |
| `agent/TestGeneratorAgent.java` | 修改 | **G6**：`calculateQualityScore` 并入评审结论——形式分（原 6 项检查原样保留为 `calculateFormScore`）× 7/10 + 评审分（pass 30 / fix 按 issues 与未采纳建议数每项 -5 / 无评审中性 15 / confidence < 0.5 减半）- uiLanguageViolations 扣分（每项 -3 上限 9）；R1 自动采纳过的建议不参与扣分（已修复的不罚）；整数算术避免 85×0.7 浮点误差 |
| `service/CoverageService.java` | 修改 | **R7**：覆盖矩阵双栏口径——每个 transition 新增 `planned/plannedCaseIds`（coverageRefs 引用，不要求执行）与 `executed/executedCaseIds`（isExecuted 用例 refs 或 stateMachineRef 引用）；summary 新增 `plannedCoveredTransitions/plannedRate/executedCoveredTransitions/executedRate`；`covered/testCaseIds/coveredTransitions/rate` 保持旧口径（refs 计划 ∪ 已执行 smRef 兜底）向后兼容 |
| `test/agent/TestCaseReviewAgentEndpointMatchTest.java` | 新增 | R3 7 用例：编造兄弟路径不匹配、路径变量归一化精确匹配、大小写/query/尾斜杠归一化、method 不符不匹配、高相似模糊命中记录 fuzzyEndpointIds、token 数不一致不模糊匹配、既有合法 id 保留与非法过滤 |
| `test/agent/TestCaseReviewAgentAutoApplyTest.java` | 新增 | R1 5 用例：高置信 refs+priority 采纳并登记 autoApplied、低置信不采纳、非法枚举 priority 拒绝、空 refs 建议 no-op、评审顶层 coverageRefs 与建议 refs 双通道并集 |
| `test/agent/TestGeneratorAgentQualityScoreTest.java` | 新增 | G6 10 用例：满分+pass=100、无评审中性 85、pass>fix=中性梯度、重度 fix 低于中性、低置信评审分减半、autoApplied 建议不扣分、uiLanguageViolations 扣分封顶、空用例不为负、历史数据无 aiReview 不抛异常 |
| `test/service/CoverageServicePlannedExecutedTest.java` | 新增 | R7 3 用例：双栏字段语义（未执行计划/已执行双真/failed 也算执行/仅 smRef 兜底进执行栏）、summary 四字段口径与旧口径兼容、全未执行时执行覆盖为 0 |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| `components/CoverageMatrix.vue` | 修改 | **R7**：汇总区双进度条（计划覆盖绿/执行验证蓝）+ 表格"计划覆盖/执行验证"双列三态（已验证绿/已规划黄/未覆盖红）+ 关联用例分"计划 N 条/执行 N 条"两个筛选入口；planned/executed 字段缺省时回退旧 covered 口径（兼容旧数据） |
| `components/TestCaseCard.vue` | 修改 | **R1**：AI 评审块新增"已自动采纳"标签（中文字段名展示），待确认列表过滤已采纳字段；**R3**：新增模糊匹配接口警告提示（列出 fuzzyEndpointIds） |

### API 契约变化

- 无 REST 接口变更
- 覆盖矩阵 `GET /api/coverage/{projectId}/matrix` 每个 transition 新增 `planned/plannedCaseIds/executed/executedCaseIds`，summary 新增 `plannedCoveredTransitions/plannedRate/executedCoveredTransitions/executedRate`（旧字段口径不变）
- `executionHints.aiReview` 新增 `autoApplied` 数组（后端已自动采纳的字段名）
- `executionHints.coverageRefs` 新增 `fuzzyEndpointIds` 数组（模糊匹配命中的接口 id）

### 验证结果

- 后端定向测试：R1/R3/G6/R7 新增 4 个测试类 + 受影响既有 2 个测试类（MergeRefs/Preparse 语义回归）31 用例全部通过
- 前端 `npm run build`：BUILD SUCCESS（2847 modules）
- 既有 CoverageServicePreparseTest 无需修改即通过——旧口径 covered/testCaseIds 语义完整保留

### 预期影响（评审闭环与覆盖率可信）

- 评审从"只诊断不治疗"变为分级回写：高置信 coverageRefs/priority 修正即时生效，title/module/type 保留人工确认——评审发现问题不再原样入库（R1）
- 接口覆盖率不再虚高：编造接口与真实接口的模糊匹配被堵住，同路径不同 method 不再误配；模糊命中显式标记供人工复核（R3）
- 覆盖度量语义诚实：计划覆盖与执行验证双栏呈现，"规划过"不再冒充"验证过"；failed 用例也计入执行验证（有执行证据）（R7）
- 去重依据可信：质量评分反映评审结论而非纯形式完整度，编造字段填满的用例不再靠形式分挤掉真实用例；UI 语言违规参与扣分与 v7.3 lint 标记联动（G6）

---

## v7.7 — 上下文精准投喂
**日期**: 2026-08-23
**基线**: v7.6
**主题**: 投喂精准——长 PRD 需求不再系统性丢失、无关后端上下文不再稀释 prompt、轮间记忆注入、容量事实明示（对应风险清单 G16/G17/G4/A13/L4a/A4a/A5/G10）

### 背景

v7.6 闭合了断言与状态机可信闭环，但生成侧的上下文装载仍是"粗放投喂"：**G16** RAG 检索回的需求切片只作为附加材料贴 prompt，永远成不了"考点"——多轮补齐循环绕着它转却从不瞄准它，长 PRD 尾部需求（A14）系统性丢失；**G17** 前端上下文按 RAG 命中精筛、后端 endpoints/rules 却全量注入——大项目几百个无关接口费 token 又摊薄 LLM 注意力；**G4** 多轮补齐轮间失忆，第 2+ 轮 LLM 不知道已生成什么，靠事后去重兜底；**A13** 大 PRD 完整解析失败（输出被 maxTokens 截断是主因）直接阻断整个生成；**L4a** PRD 截断只保头部，验收标准/边界条件通常在文档后部恰被丢弃；**A4a** LLM 补充接口无源码存在性校验直接入库，看不全源码还要求"如实补充"等于鼓励编造；**A5** 规则层完全不提取端点参数，"关联 API + 测试数据"缺数据基础；**G10** 巨型 gaps 清单与 60 条生成上限不匹配，用户看到大量"未覆盖"无解释。

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| `agent/PrdAgent.java` | 修改 | **L4a**：`truncateDoc` 单文档超 12000 字符改为头尾各半保留（原纯头部截断系统性丢弃后部验收标准）；总量超 24000 同样头尾各半并明示。**A13**：`analyzeSlim`——完整解析失败后的瘦身重试，只要求 modules/requirements/businessRules 核心三块（stateFlows/entities 由代码侧 StateMachineAgent/SpringAnalyzer 提供），输出体积减半以上降低再截断概率；两次均失败抛"输出可能被截断，请精简文档或拆分后重试"的明确错误；缓存 kind 用 `prd_analysis_slim` 与完整解析分键互不污染 |
| `analyzer/SpringAnalyzer.java` | 修改 | **A5**：`extractParameters`——规则层 JavaParser 解析 `@RequestParam`（in=query，required 缺省 true，defaultValue）/`@PathVariable`（in=path）/`@RequestBody`（in=body 且类型写入 endpoint.requestBody），注解 name/value 缺省用参数名；**A4a**：`mergeSupplementalEndpoints` 源码存在性校验——function 含已知类名或 path 以已知控制器前缀开头才收，否则丢弃并记 warning（上限 50 条）；`collectControllerPathPrefix` 只收 ≥2 段的前缀（`/api` 这类单段前缀过于宽泛不能作为证据）；注解属性取值统一走 `annotationAttr`（单值/Normal/缺省回退） |
| `agent/TestGeneratorAgent.java` | 修改 | **G16**：`buildCoverageChecklist` 并入 RAG 检索切片——切片标题（剥 markdown 前缀截 60 字符）与既有需求 token 重叠相似度 <3 时作为 `rag-req-N` 进考点清单（上限 20 条），长 PRD 尾部需求经 Milvus 全文切片零成本找回；**G17**：后端上下文按需求关键词过滤——requirements 标题+描述+ragContexts 汇集关键词（上限 60 条每条 100 字符），endpoints/rules 按 path/function/description/validation 拼接文本 token 重叠打分过滤，全量兜底（命中为空不过滤）；operationDependencies 同步按保留 endpoint 的 function 过滤；**G4**：轮间摘要注入——第 2+ 轮 prompt 附已生成用例标题/类型摘要（上限 60 条）配合禁重复指令；requirementIds 兜底按标题语义匹配 checklist 需求（含 rag-req-*）；**G10**：gaps 清单按类截断（requirements 40/transitions 60/endpoints 80/rules 60/components 60/dependencies 60）并标 `truncated:true`；checklist.endpoints 注入 prompt 前截断 150 条并追加说明条目；`GenerationReport` 新增 `coverageCappedByLimit`（达 60 条上限仍有缺口——容量事实非降级信号，不触发 markDegraded） |
| `service/TestCaseService.java` | 修改 | **G10**：complete 事件携带 `coverageCappedByLimit`，前端可提示"精简需求或拆分生成" |
| `test/agent/TestGeneratorAgentContextFeedTest.java` | 新增 | G16/G17/G4/G10 15 用例：token 重叠打分（中文子串/英文大小写/不相交）、RAG 标题提取（markdown 前缀/60 字符截断/空值）、相似度上限、capIdsInto 截断、checklist 150 条截断+说明条目、需求关键词汇集（requirements+ragContexts 合并/60 条上限/100 字符截断）、endpointText/ruleText 拼接 |
| `test/agent/PrdAgentTruncateTest.java` | 新增 | L4a+A13 5 用例：短文档原样保留、长文档头尾各半+省略量明示、null 安全、完整解析失败降级瘦身重试成功、两次均失败抛截断提示 |
| `test/analyzer/SpringAnalyzerParameterTest.java` | 新增 | A5+A4a 3 用例：三种参数注解规则层提取（name/required/defaultValue/requestBody）、有源码证据的补充接口接受、类名+前缀证据链校验（无证据丢弃+告警可观测、仅前缀命中接受） |
| `test/agent/PrdAgentTest.java` | 修改 | A13 行为变更同步：空 LLM 结果两次失败后的错误消息从"未提取到有效需求"改为含"截断"提示 |
| `test/analyzer/SpringAnalyzerTest.java` | 修改 | A5 行为变更同步：规则层先填 requestBody 后，LLM 增强按既有"不覆盖规则事实"策略不再覆盖 |

### API 契约变化

- 无 REST 接口变更
- SSE complete 事件新增 `coverageCappedByLimit` 布尔字段（容量事实：达 60 条上限仍有缺口）
- 生成上下文新增 `generatedCasesSummary`（轮间摘要，仅 prompt 内部使用）
- endpoints 新增 `parameters` 规则层数据（`name/in/type/required/defaultValue`）

### 验证结果

- 后端 `mvn test`（JDK 17 + Maven 3.9.16）：全量 228 个测试通过，BUILD SUCCESS（新增 23 个用例：TestGeneratorAgentContextFeedTest 15 + PrdAgentTruncateTest 5 + SpringAnalyzerParameterTest 3）
- 编译修复：SpringAnalyzer 导入修正（`MemberPair`→`MemberValuePair`）+ 移除重复 `stripQuotes` 方法（保留增强版）

### 预期影响（投喂精准）

- 长 PRD 需求不再系统性丢失：尾部内容本就在 Milvus（全文切片入库），RAG 切片并入考点清单等于零成本找回（G16 免费覆盖 A14 昂贵修法的大半）
- prompt 噪声显著下降：无关后端接口/规则不再注入，LLM 注意力聚焦需求相关上下文（G17 准确率+效率双收益）
- 多轮补齐真实收敛：轮间摘要让 LLM 知道已生成什么，重复率下降、缺口收敛轮次减少（G4）
- 大 PRD 解析失败不再阻断生成：瘦身重试只求核心三块，降级生成好过整体阻断（A13）；截断错误信息明确指向"精简文档或拆分"
- 参数数据基础补齐：规则层零 LLM 成本提取参数注解，"关联 API + 测试数据"有据可依（A5）
- LLM 编造接口被拦截：补充接口必须有源码证据（类名或 ≥2 段路径前缀）才入库，丢弃行为 warning 可观测（A4a）
- 容量事实诚实明示：达生成上限仍有缺口时明确告知"受生成上限影响"，与真实降级信号区分（G10）

---

## v7.6 — 状态机与断言闭环
**日期**: 2026-08-23
**基线**: v7.5
**主题**: 闭环可信——状态机转换有源码证据校验、expected 真正被断言、Agent 模式验证步骤不再误点、错误→文案对照表入生成上下文（对应风险清单 A17/L6/E5/G20层3）

### 背景

v7.5 建立了可复现基线，但三个核心闭环仍是"猜测或空转"：**A17** 状态机转换关系完全由 LLM 猜测——编的 `CREATED→CANCELLED` 只要 code 合法就入库，"状态转换覆盖率"这一核心卖点建立在未验证的猜测之上；**L6** expected 从未被验证——"用例通过"≠"预期结果成立"，v7.0(E4) 只比对 url/title，中文 toast 文案（"删除成功"）无法断言；**E5** Agent 模式把 state_assert/api_call 掉进"找元素→截图→定位→点击"流水线——验证步骤可能随机点中页面元素（描述撞上删除按钮即生产事故）；**G20层3** 前端错误展示文案（ElMessage.error 等）从未被采集，LLM 无对照表可翻译，expected 编不出真实 UI 文案。

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| `service/ExecutionAssert.java` | 新增 | **L6**：三层断言共享工具（程序化/Agent 两模式共用）——层 1 URL/标题语义（v7.0 E4 能力）；层 2 DOM 文本断言（v7.6 新增）：引号短语完整包含 + 中文段剥叙述前后缀按连接词切分，核心短语 ≥3 字 3-gram 滑窗匹配、2 字完整包含，英文 token 大小写不敏感；层 3 无法验证诚实标 skipped 不误报失败。无 textSnippet（DOM 文本快照缺失/为空）时文本断言不可执行直接 skipped |
| `analyzer/SpringAnalyzer.java` | 修改 | **A17**：`extractStateTransitions`——JavaParser 扫描状态字段赋值点（`setStatus(X)` / `status = X`），沿父节点找赋值分支的 if 条件提取 from 状态，产出"field/from/to/method/file"证据（上限 200 条去重）；**G20层3**：`extractErrorMessages`——提取异常构造字面量（`new XxxException("...")` / `throw`），产出"exception/message/method/file"（上限 100 条） |
| `analyzer/result/BackendResult.java` | 修改 | 新增 `stateTransitions` / `errorMessages` 字段（默认空列表） |
| `agent/StateMachineAgent.java` | 修改 | **A17**：`applyEvidence`——LLM 推断的 transitions 与源码赋值证据比对：匹配标 `verified=true`，未匹配标 `unverified=true` 且该状态机 confidence 上限压到 0.4（此前固定 0.8 无差别信任）；证据在 `analyze` 主流程 LLM 提取后统一应用 |
| `agent/ExecutionAgent.java` | 修改 | **E5**：`executeStep` 按步骤类型分流——`state_assert` 走 `executeStateAssert`（getPageStatus + 共享断言 + 截图留证，不再找元素点击）；`api_call` 与程序化模式一致明确 skipped（不再掉进点击流水线） |
| `analyzer/VueAnalyzer.java` | 修改 | **G20层3**：`extractFeedbackTexts`——正则提取 `ElMessage/Message/$message.error|success|warning|info("...")` 调用字面量，产出"type/text/file"（上限 100 条去重） |
| `analyzer/result/FrontendResult.java` | 修改 | 新增 `userFeedbackTexts` 字段（默认空列表） |
| `agent/TestGeneratorAgent.java` | 修改 | **G20层3**：前端 userFeedbackTexts + 后端 errorMessages 合成对照表注入生成上下文（按文案去重，上限 60 条）；prompt 预期结果规范新增硬规则——expected 必须优先使用对照表中的真实提示文案原文，禁止自行编造 |
| `service/ExecutionService.java` | 修改 | **L6**：程序化模式 state_assert 断言逻辑移至共享 `ExecutionAssert` 并升级三层断言（委托方法保留包内可见性兼容既有测试）；failed 时 error 字段带期望/实际差异描述 |
| `test/service/ExecutionAssertTest.java` | 新增 | L6 13 用例：三层断言各路径（URL 命中/未命中、中文 toast 命中/未命中、纯叙述词不误判、多关键词全命中、大小写不敏感、API 形态 skipped、空 snippet skipped、空 expected/null 页面态 skipped、describe 摘要、snippet 截断） |
| `test/analyzer/SpringAnalyzerStateTransitionTest.java` | 新增 | A17 7 用例：赋值点提取、if 条件 from 状态、跨 if/else 分支、去重上限、非状态字段不误采 |
| `test/agent/StateMachineAgentEvidenceTest.java` | 新增 | A17 7 用例：证据匹配标 verified、无证据标 unverified、confidence 压降、null 容错 |
| `test/analyzer/SpringAnalyzerErrorMessageTest.java` | 新增 | G20层3 5 用例：异常字面量提取、方法定位、去重、上限 |
| `test/analyzer/VueAnalyzerFeedbackTest.java` | 新增 | G20层3 5 用例：ElMessage 四种类型提取、跨文件去重、模板字符串 |
| `test/agent/ExecutionAgentStepTypeTest.java` | 新增 | E5 4 用例：state_assert 分流不走点击、断言 verdict 传递、api_call 明确 skipped、页面态读取失败不误报 |

### API 契约变化

- 无 REST 接口变更
- 状态机 `transitions` JSON 各项新增 `verified` / `unverified` 布尔标记（前端可据此提示"未经源码验证"）
- 生成上下文新增 `userFeedbackTexts`（错误→用户文案对照表，仅 prompt 内部使用）

### 验证结果

- 后端 `mvn test`（JDK 17 + Maven 3.9.16）：全量测试通过，BUILD SUCCESS（新增 41 个用例：ExecutionAssertTest 13 + SpringAnalyzerStateTransitionTest 7 + StateMachineAgentEvidenceTest 7 + SpringAnalyzerErrorMessageTest 5 + VueAnalyzerFeedbackTest 5 + ExecutionAgentStepTypeTest 4；v7.0 既有 ExecutionServiceAssertTest 7 用例经委托路径回归通过）

### 预期影响（闭环可信）

- 状态机转换有 ground truth：LLM 编造的转换标 unverified 且 confidence ≤0.4，"状态转换覆盖率"不再建立在无差别信任上；真实转换标 verified 可区分展示
- expected 真正被断言：中文 toast 文案（"删除成功"）在 DOM 文本快照中比对，断言失败步骤记 failed 并带期望/实际差异——"用例通过"从此等于"预期结果成立"（UI 可感知形态）
- Agent 模式安全性：验证步骤不再进入点击流水线，消除"断言步骤随机点中删除按钮"的生产事故风险
- expected 文案真实性：生成侧拿到被测系统源码提取的真实提示文案对照表，禁止编造——expected 与页面实际展示的文案一致率提升，L6 断言命中率随之提升（G20层3 与 L6 闭环联动）
- 无法验证的场景诚实标 skipped（无 DOM 快照 / API 形态断言），不误报失败也不假通过

---

## v7.5 — 缓存与可复现基线
**日期**: 2026-08-23
**基线**: v7.4
**主题**: 基线可信——LLM 解析结果同输入同输出、同输入不重复付费（对应风险清单 A11/A15）

### 背景

v7.4 让分析输入可复现（A9 文件排序），但相同输入仍然重复付费调 LLM：**A15** 每次生成重新调 `prdAgent.analyze`（temp 0.2），同一 PRD 两次生成 requirements 列表本身漂移——追加生成与首次生成模块口径可能不一致；**A11** `VueAnalyzer` 每次分析对每个业务组件完整调 LLM（并发 4），文件没变也重跑，分析是高频操作，成本线性放大。

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| `entity/LlmResultCache.java` | 新增 | LLM 结果缓存表实体：`cache_key`（SHA-256）主键 / `cache_kind` / `result_text`（LLM 原始响应）/ `created_at` |
| `repository/LlmResultCacheRepository.java` | 新增 | JPA Repository |
| `service/LlmResultCacheService.java` | 新增 | 缓存层核心：键 = SHA-256(模型名 + systemPrompt + userPrompt)——换模型/prompt 模板演进/输入内容变化任一发生即新键自然失效，无 TTL；get 命中返回原始响应，put upsert（并发主键冲突静默忽略）；DB 异常降级为不缓存直调 LLM（get 返回 null / put 静默跳过），绝不阻断分析/生成 |
| `agent/PrdAgent.java` | 修改 | **A15**：`analyzeByLlm` 接入缓存——先查缓存命中直接复用；未命中调 LLM 解析成功后写缓存；拆出 `parsePrdResponse` 解析段（缓存命中与 LLM 直调共用单一解析路径）；毒缓存（解析失败/空结果）自动落回 LLM 路径重新生成 |
| `analyzer/VueAnalyzer.java` | 修改 | **A11**：`enhanceComponentSummary` 接入缓存——组件源码未变直接复用摘要；`mergeComponentSummary` 改返回 boolean（解析成功才写缓存，防毒缓存）；system prompt 提为常量供缓存键与 LLM 调用共用 |
| `db/migration/mysql/V11__add_llm_result_cache.sql` | 新增 | MySQL 迁移脚本（H2 由 ddl-auto 自动建表） |
| `test/LlmResultCacheServiceTest.java` | 新增 | 缓存层 8 用例（同输入往返/不同输入未命中/模型名入键/kind 校验/覆盖写/空响应不写/并发冲突静默/DB 异常降级） |
| `test/agent/PrdAgentCacheTest.java` | 新增 | A15 5 用例（首次调 LLM 写缓存/同输入二次命中不调 LLM 且结果一致/PRD 改一字重新调/非法响应不缓存/毒缓存落回 LLM） |
| `test/VueAnalyzerTest.java` | 修改 | A11 1 用例（同源码组件二次分析命中缓存不重复调 LLM，摘要内容一致） |

### API 契约变化

- 无 REST 接口变更
- 新增 `llm_result_cache` 表（H2 自动建 / MySQL V11 迁移）

### 验证结果

- 后端 `mvn test`（JDK 17 + Maven 3.9.16）：全量测试通过，BUILD SUCCESS（新增 14 个用例：LlmResultCacheServiceTest 8 + PrdAgentCacheTest 5 + VueAnalyzerTest +1）

### 预期影响（基线可信）

- 同一 PRD 两次生成：第二次不调 LLM，requirements 与第一次完全一致——temp 0.2 漂移消除，追加生成与首次生成模块口径一致
- 同一前端代码两次分析：业务组件摘要不重复调 LLM——分析是高频操作，此前每个业务组件一次调用成本线性放大，现在源码未变零调用
- 失效精确：PRD 改一个字 / 组件源码变 / prompt 模板升级 / 换模型，任一发生对应缓存自然失效重新调 LLM；未变化的条目继续命中
- 缓存 DB 故障时降级直调 LLM，分析/生成不受阻断（仅日志告警）
- 毒缓存自愈：缓存内容解析失败自动落回 LLM 路径重新生成并覆盖写

---

## v7.4 — 分析器规则层加固
**日期**: 2026-08-23
**基线**: v7.3
**主题**: 证据可信——分析结果干净（无测试污染）、完整（规则不静默丢）、可复现（文件序确定）、可观测（失败有告警）、不误导（兜底有标记）（对应风险清单 A1/A2/A7/A8/A9/A10/A19/A20/C1）

### 背景

分析结果是整个系统"代码证据链"的数据源，规则层存在三类问题：**数据污染**——测试 fixture 混入正式结果（A1）、方法级 `@RequestMapping` 的 HTTP 方法恒为 ANY 污染覆盖率分母（A2）；**静默丢失**——模板字符串让 rules 块括号计数错位（A7）、多 form 只取第一个 rules 块（A8）、LLM 补充被组件级整条丢弃（A10）、所有提取失败一律静默返回空列表，"0 个表单"无从区分真没有还是解析失败（C1）；**不可复现与误导**——文件遍历顺序依赖 OS，两次分析 LLM 看到的文件集合不同（A9）、规则兜底状态机无降级标记诱导 LLM 虚构转换（A20）、另有约 120 行死代码（A19）。

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| `analyzer/SpringAnalyzer.java` | 修改 | **A1**：`findJavaFiles` 统一排除 `src/test/` 路径（主循环与依赖图/LLM 源码收集三处口径一致），排除计数写入 warnings。**A2**：`mapHttpMethod` 解析注解 `method` 属性（`RequestMethod.POST` 单值与 `{GET, POST}` 数组取第一个，Normal/SingleMember 两种注解形态），无 method 属性仍为 ANY。**A9**：`collectJavaFiles` 结果按绝对路径字典序排序。**C1**：单文件解析失败（文件名+数量）、src/test 排除计数写入 warnings |
| `analyzer/VueAnalyzer.java` | 修改 | **A7**：`extractBalanced` 重写为字符串状态机——支持单双引号、反引号模板串（含嵌套 `${}` 表达式、表达式内引号/嵌套反引号/转义），模板串内的花括号不再错位计数。**A8**：rules 块收集由 `rs.find()` 单块改为 while 全量收集并拼接，字段校验查找跨全部块，多块计信息性 warning。**A9**：`collectVueFiles` 按绝对路径排序。**A10**：`parseAndMergeSupplements` 中 forms 改字段级合并（按 field name 去重，正则已有字段保留、LLM 新字段追加）、domSelectors 改选择器级合并（按 type+value 去重追加）。**C1**：文件读取失败/rules 块不配对/各提取器整体异常/LLM 补充解析失败/组件摘要 LLM 失败计数全部写入 warnings（VueAnalyzer 单例并发安全：warnings 参数传递而非实例字段） |
| `agent/StateMachineAgent.java` | 修改 | **A19**：删除无调用方死代码 `enhanceWithFrontend`/`mergeFrontendEnhancements`/`toMap`（约 120 行，v6.2 合并单次 LLM 调用后遗留） |
| `agent/TestGeneratorAgent.java` | 修改 | **A19**：删除 v7.1 遗留死代码（`SYSTEM_PROMPT_HEADER`/`buildSystemPrompt` 等）。**A20**：生成上下文中每个状态机附带 `source` 字段——`stateMachineSource(sm)` 从现有 `sources` JSON 派生（含 `rule_based` 且不含 `llm` → `rule`，否则 `llm`，不加数据库列）；system prompt 增加信任度规则：rule 来源仅状态枚举可信、transitions 可为空数组，禁止为兜底状态机虚构转换 |
| `analyzer/result/BackendResult.java` | 修改 | **C1**：新增 `warnings: List<String>` 字段，`skipped()` 初始化为空列表（非 null） |
| `analyzer/result/FrontendResult.java` | 修改 | **C1**：新增 `warnings: List<String>` 字段，`skipped()` 初始化为空列表（非 null） |
| `test/SpringAnalyzerTest.java` | 修改 | A1/A2/C1 3 个用例（测试 Controller 排除+告警/`@RequestMapping` method 属性单值与数组解析） |
| `test/VueAnalyzerTest.java` | 新增 | A7/A8/A10/C1 4 个用例（双 rules 块字段校验全解析+合并告警/模板串不破坏括号配对/LLM 补充字段级合并不覆盖已有字段/rules 块不配对告警） |
| `test/TestGeneratorAgentStateMachineSourceTest.java` | 新增 | A20 3 个用例（rule_based 派生 rule/含 llm 派生 llm/null 与空 sources 兜底 llm） |

### API 契约变化

- 无 REST 接口变更
- `code_analysis` 表 JSON 新增 `warnings` 数组字段（前端未知字段自动忽略，前端展示随后续版本）
- 生成上下文 stateMachines 元素新增 `source` 字段（"rule"/"llm"）

### 验证结果

- 后端 `mvn test`（JDK 17 + Maven 3.9.16）：**150 个测试全部通过，BUILD SUCCESS**（新增 9 个用例：SpringAnalyzerTest +2、VueAnalyzerTest 4、StateMachineSourceTest 3）
- 前端本版无改动

### 预期影响（证据可信）

- 分析结果不再混入测试 fixture：mock Controller/测试实体不出现在 endpoints/entities（此前直接污染生成上下文与覆盖率分母）
- 方法级 `@RequestMapping` 接口方法正确（POST 不再标 ANY），接口覆盖率分母恢复准确
- 表单校验规则提取完整：模板串消息不再截断 rules 块、多表单全部 rules 块合并解析、LLM 补充字段级合并（正则漏掉的备注/邮箱等字段不再丢失）
- 相同项目两次分析看到相同的文件集合（路径字典序），叠加既有温度 0.3 大幅降低结果漂移
- "0 个表单/规则"可解释：解析失败、文件排除、LLM 失败均以人可读告警落库 code_analysis
- 规则兜底状态机带 `source: rule` 降级标记，生成侧不再对空 transitions 虚构转换

---

## v7.3 — LLM 组件稳定与生成质量约束
**日期**: 2026-08-23
**基线**: v7.2
**主题**: 组件可信——并发流取消互不误杀、熔断通道隔离、SPA 生效判断不再重复点击、流式截断告警与抢救、预期结果语言约束（对应风险清单 L1/L2/L5/L8/G20层1+2）

### 背景

LLM 组件层存在 5 个稳定性/质量缺口：流式取消是全局单例（两个项目并发生成时 A 取消误杀 B 的流）；熔断器全局共享且 4xx 配置错误也计入（多模态故障连坐全部文本生成，API Key 填错 5 次全系统 503）；SPA 生效判断退化为 URL 比较（URL 不变几乎必判"未生效"→ DOM 兜底重复点击→重复下单）；流式 JSON 截断静默丢最后一条用例（无日志无提示）；生成的 expected 充斥"返回 401""errorMsg""status=PENDING_PAYMENT"等 API 语言（不是用户可感知现象，断言闭环无从落地）。

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| `service/LlmService.java` | 修改 | **L1**：删除全局 `activeStream`/`streamCancelled`/`cancelStreaming()`——取消信号改为 per-request `BooleanSupplier` 参数由调用方传入，并发生成流互不误杀；流内 doOnNext/等待循环只检查本次请求的信号。**L2**：熔断调用按通道拆分（chat/chatStreaming/chatJson → text，chatWithImage → multimodal），失败仅当 `LlmRetryPolicy.isRetryable` 为 true 时计入熔断（4xx 配置类错误直接失败不计数，Key 填错不再打满熔断）。**L8**：maxTokens 由硬编码 16384 改 `llm.max-tokens` 配置（默认不变） |
| `common/LlmCircuitBreaker.java` | 修改 | **L2**：内部按 channel 维护独立 CircuitState（ConcurrentHashMap），新增带 channel 的 isOpen/allowRequest/onSuccess/onFailure；保留无参旧签名（默认 text）兼容现有测试 |
| `agent/TestGeneratorAgent.java` | 修改 | **L1**：流式调用传入 `cancelled::isCancelled`（每项目独立 RuntimeFlag）。**L8**：StreamingTestCaseParser 新增 `finish()`——braceDepth 不归零时置截断标志 + warning 日志 + 截到最后一个字符串外逗号、按括号栈补齐闭合符重试解析（抢救最后一条，嵌套数组回退安全点）；GenerationReport 新增 `streamTruncated`/`truncatedRecovered` 随 complete 事件暴露。**G20层1**：两套 system prompt 追加"预期结果语言规范"（禁 HTTP 码/字段名/机器常量，api_call 步骤豁免但 UI 断言必须回到页面现象）；few-shot 示例 2/3 的 expectedResults 由"接口返回201/400""status=PENDING_PAYMENT"改为页面现象描述 |
| `service/TestCaseService.java` | 修改 | **L1**：cancelGeneration 删除全局 `llmService.cancelStreaming()`（per-request 信号已覆盖，flag.cancel() 即流内检查点） |
| `agent/ExecutionAgent.java` | 修改 | **L5**：askLlmIfEffective 无 LLM 兜底由"仅比 URL"改为 URL+title+textSnippet 三指纹比较（textSnippet 是 body 文本前 500 字符快照，零额外调用）；LLM 路径注入操作前后文本快照作证据（E6 最小版雏形）；点击后补 800ms 等待覆盖 SPA 异步渲染，避免"渲染未完成→指纹相同→误判未生效→DOM 兜底重复点击" |
| `agent/UiLanguageLinter.java` | 新增 | **G20层2**：静态正则 lint——扫描 expectedResults 与 structuredSteps 中非 api_call 步骤的 expected，命中三类规则（HTTP 状态码形态/全大写下划线机器常量/后端字段赋值errorMsg=status=code=）输出人可读违规说明；保守设计宁可漏报不误报 |
| `agent/TestCaseReviewAgent.java` | 修改 | **G20层2**：评审链路汇合处统一跑 lint，结果写入 `hints.uiLanguageViolations`（只标记不删改，前端展示后续版本） |
| `test/LlmCircuitBreakerChannelTest.java` | 新增 | L2 通道隔离 5 用例（multimodal 打满不连坐 text/反向/onSuccess 只重置本通道/null 默认 text/旧签名兼容） |
| `test/StreamingTestCaseParserTruncationTest.java` | 新增 | L8 截断检测与抢救 5 用例（完整响应无截断/半截对象抢救成功/嵌套数组内部截断回退安全点/无安全逗号只告警不抢救/空 buffer 无副作用） |
| `test/UiLanguageLinterTest.java` | 新增 | G20层2 规则 8 用例（三类规则各命中/UI 语言零误报/api_call 步骤豁免/ui_action 步骤命中/"400 元"金额不误报/null 安全） |
| `test/ExecutionAgentEffectivenessTest.java` | 新增 | L5 三指纹判断 6 用例（SPA URL 不变但内容变化→生效/标题变化/URL 变化/全不变→未生效/null 保守/缺失 textSnippet 键兜底） |

### API 契约变化

- 无 REST 接口变更
- SSE `complete` 事件 report 对象新增 `streamTruncated`/`truncatedRecovered` 字段（前端未知字段自动忽略）
- `executionHints` 新增可选 `uiLanguageViolations` 数组（前端本版不展示，数据已埋）
- 配置新增 `llm.max-tokens`（默认 16384）

### 验证结果

- 后端 `mvn clean test`（JDK 17 + Maven 3.9.16）：**141 个测试全部通过，BUILD SUCCESS**（新增 24 个用例：ChannelTest 5 + TruncationTest 5 + LinterTest 8 + EffectivenessTest 6）
- 前端 `npm run build`：通过（本版无前端改动，回归确认）

### 预期影响（组件可信）

- 多项目并发生成互不干扰：取消只作用于目标项目的流（此前 A 取消会误杀 B 并抛"用户取消"）
- 多模态（图片定位）故障或 API Key 配置错误不再拖垮文本生成/评审/PRD 解析
- SPA 执行不再系统性重复点击：页面内容变化即判生效，800ms 渲染窗口消除"未渲染完误判"
- 长批次生成的尾部用例不再静默丢失：截断有告警有抢救，数量差异可解释
- 新生成用例的 expected 逐步转向页面可感知现象（prompt 硬约束堵增量 + lint 标记存量），为 L6 断言闭环铺路

---

## v7.2 — 度量与报告诚实化
**日期**: 2026-08-23
**基线**: v7.1
**主题**: 数字可信——删除假字段、状态与通过率同口径、合并不丢数据、覆盖率提速与加权（对应风险清单 R6/R10/R2/R8/R9/R12）

### 背景

度量与报告层存在 6 个"数字不可信"问题：仪表盘 apiRate 是从未实现的假字段；全 skipped 用例挂 passed 徽章但报告显示 0% 通过率（同屏自相矛盾）；报告通过率分母含 skipped/运行中/待执行（与 Allure 惯例不一致）；评审 coverageRefs 整体替换而非合并（丢失生成阶段的正确引用）；覆盖率矩阵在双重循环内反复反序列化同一条 JSON（50 万次 parse）；项目间覆盖率简单平均被小项目噪声拉偏；报告 footer 版本硬编码 v2.4 从未更新。

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| `service/ExecutionService.java` | 修改 | **R10**：两处执行收尾（Agent 路径/程序化路径）状态判定统一收敛为 `determineStatus(passed, failed, skipped)`——全 skipped 记 `skipped` 终态，不再挂 passed 徽章；执行历史 stats 新增 skipped 计数 |
| `service/ReportService.java` | 修改 | **R10/R12**：新增 `passRateOf`——单次/批次报告通过率分母统一为 passed+failed（跳过/运行中/待执行/取消不计入）；有跳过或未判定记录时单元格追加说明；批次汇总表新增"已跳过"行；footer 版本收敛为单一常量 APP_VERSION（两处硬编码 v2.4 → v7.2，后续迭代只改一处） |
| `agent/TestCaseReviewAgent.java` | 修改 | **R2**：mergeCoverageRefs 由"评审非空即整体替换"改为**保序并集**（existing 在前、review 新增在后、去重）——生成阶段正确的 requirementIds 不再被评审 LLM 的不完整返回覆盖丢失 |
| `service/CoverageService.java` | 修改 | **R8**：循环外预解析每条用例的 coverageRefs.transitionIds 与（已执行用例的）stateMachineRef.transitions，双重循环内只做集合查找——旧实现 50 SM×20 转换×500 用例 ≈ 50 万次 JSON parse 归零；判定语义（计划覆盖 + isExecuted 兜底）保持不变 |
| `controller/StatsController.java` | 修改 | **R6**：删除从未真实赋值的 apiRate/avgApiRate 假字段（真实接口覆盖的分母 checklist endpoints 未持久化，不应呈现口径不全的统计）。**R9**：avgStateRate 由项目间简单平均改为按转换总数加权平均（totalTransitions=0 的项目不参与） |
| `test/ExecutionServiceStatusTest.java` | 新增 | R10 终态判定 5 用例（全 skipped→skipped/有 failed→failed/有 passed→passed/部分跳过有通过→passed/零步骤无错误→passed） |
| `test/ReportServicePassRateTest.java` | 新增 | R10/R12 通过率口径 4 用例（跳过不计分母/全 skipped→0 非 NaN/零记录→0/运行中待执行不稀释） |
| `test/TestCaseReviewAgentMergeRefsTest.java` | 新增 | R2 并集语义 4 用例（并集不替换/去重保序/空评审不清空/null 兜底） |
| `test/CoverageServicePreparseTest.java` | 新增 | R8 预解析等价性 2 用例（coverageRefs 命中 + isExecuted 兜底语义保持/无覆盖数据零速率） |
| `test/StatsControllerOverviewTest.java` | 新增 | R6/R9 仪表盘 2 用例（加权平均 42 vs 旧简单平均 25 + 假字段已删/零转换项目不参与加权避免 0/0） |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| `src/views/TestCaseList.vue` | 修改 | **R10**：执行状态白名单/文案补 skipped（"已跳过"，旧实现静默映射成"未执行"）；执行状态筛选下拉新增"已跳过"选项；status-pill 补中性色 |
| `src/components/TestCaseCard.vue` | 修改 | **R10**：执行历史标签映射补 skipped |
| `src/views/ExecutionHistory.vue` | 修改 | **R10**：记录状态标签补"已跳过"；统计卡新增"已跳过"（后端 stats.skipped + 本地兜底统计）；status-pill 补色 |
| `src/views/ExecutionResult.vue` | 修改 | **R10**：详情页状态标签映射与 status-pill 补 skipped |

### API 契约变化

- `GET /api/stats/overview`：响应**删除** `avgApiRate` 与 `projectCoverage[].apiRate`（从未真实赋值的假字段，前端无消费方）；`avgStateRate` 口径改为按转换总数加权
- 执行记录状态枚举：新增 `skipped`（全步骤跳过）；执行历史接口 stats 新增 `skipped` 字段
- 其余接口无契约变化（覆盖率矩阵为纯内部提速）

### 验证结果

- 后端 `mvn clean test`（JDK 17 + Maven 3.9.16）：**117 个测试全部通过，BUILD SUCCESS**（新增 17 个用例：StatusTest 5 + PassRateTest 4 + MergeRefsTest 4 + PreparseTest 2 + OverviewTest 2）
- 前端 `npm run build`：通过

### 预期影响（度量校准）

- 全 skipped 用例（如纯 api_call）状态列如实显示"已跳过"，不再与 0% 通过率同屏矛盾
- 报告通过率反映真实判定质量（跳过不再稀释），有跳过时明示口径
- 评审后的 coverageRefs 只增不减，覆盖率引用不回退
- 覆盖率页与仪表盘在大数据量下显著提速（JSON 反序列化次数从 O(转换×用例) 降为 O(用例)）
- 仪表盘平均覆盖率反映转换数加权后的真实水平，不被小项目拉偏

---

## v7.1 — 生成链路一致性修复
**日期**: 2026-08-23
**基线**: v7.0
**主题**: 生成结果诚实化——去重误杀、推送/落库差异可见、死代码清理（对应风险清单 G1/G2/G3/G5/G11/G14/G15）

### 背景

全量生成链路存在 7 个一致性问题：SSE 流式推送的"草稿"与最终落库结果不一致且差异静默；标题去重不校验类型导致"正向/逆向"成对用例误杀；选择器补齐注入空 data 占位导致执行期假失败；代码驱动生成死代码（~700 行）使降级检测永远失效；聚焦类型过滤后 0 条误报"生成失败"；全量生成缺少批内语义去重；settings 解析失败被误报为"请先添加 PRD"。

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| `agent/TestGeneratorAgent.java` | 修改 | **G1**：isDuplicate 增加 type 一致性前置条件、标题字符重叠阈值 0.8→0.9，"正向/逆向"同模块用例不再误杀。**G3**：删除 enrichStructuredSteps 的空 data 占位注入（`{field: ""}` 导致执行期假失败）。**G5**：删除代码驱动生成死代码（generateCodeDrivenCases/generateByLlmForStateMachine/buildPositiveTest 等）；generate/generateStreaming 双管线合并为 runPrdPipeline。**G11**：区分"未生成任何用例"与"已生成 N 条但聚焦类型过滤后为 0"。**G14**：管线末端接入批内语义去重。新增 `GenerationReport`（generated/focusDropped/reviewDropped/dedupDropped/semanticDropped/finalCount/roundsNotConverged/reviewDegraded） |
| `service/SemanticService.java` | 修改 | **G14**：新增 `deduplicateBatch`——批内同类型语义去重（cosine ≥ duplicateThreshold 判重、保留 qualityScore 更高者）；Milvus/embedding 未配置时原样返回，不阻塞生成主链路 |
| `agent/TestCaseReviewAgent.java` | 修改 | **G2/G5**：review 新增报告重载——记录评审阶段丢弃数（规则: 无 structuredSteps + LLM: reject）与 LLM 评审降级信号 |
| `agent/OrchestratorAgent.java` | 修改 | **G15**：settings 有实质内容但解析失败时抛 50015"项目配置解析失败"，不再静默降级误导为"请先添加 PRD 文档"；generate/generateStreaming 新增 GenerationReport 透传重载 |
| `service/TestCaseService.java` | 修改 | **G2**：complete 事件携带 total/pushed/droppedTotal/dropped 明细（review/dedup/semantic/focusType/other 分类 + 真实降级信号），推送≠落库不再静默；caseCb 实际计数推送草稿数。**G5**：三处 markDegraded 由死信号（rule_based source，随死代码删除不再产生）改为真实信号（roundsNotConverged/reviewDegraded）。**G15**：hasPrd 解析失败由静默 false 改为如实抛配置解析错误 |
| `test/TestGeneratorAgentDedupTest.java` | 新增 | G1 判重语义 5 用例（同题同型判重/同题不同型不判重/重叠 0.82 低于新阈值 0.9 不判重/重叠 1.0 仍判重/同题同型跨模块仍判重） |
| `test/TestGeneratorAgentReportTest.java` | 新增 | G2/G5/G11 报告计数 3 用例（3 轮×3 条生成→去重 6→落库 3 且 roundsNotConverged 置位；聚焦类型过滤为空抛专有错误且 focusDropped=generated；LLM 空返回抛"未生成任何用例"对照） |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| `src/api/testcase.js` | 修改 | **G2**：streamGenerate 的 complete 回调改为传完整对象（原仅传 total） |
| `src/views/TestCaseList.vue` | 修改 | **G2**：生成完成提示区分"全部落库"与"草稿被丢弃"（展示各阶段丢弃数与原因） |

### 附带工程改进（同版交付，非风险清单项）

| 文件 | 变更 | 说明 |
|---|---|---|
| `service/GitCloneService.java` | 新增 | git_url 项目创建时真实 clone 到受管目录（`app.git.clone-dir`），超时/格式校验/项目删除时清理克隆目录；`ProjectService.createProject` 接入，Dockerfile 安装 git |
| `service/SemanticService.java` | 修改 | 需求上下文**模块级增量重建**：指纹从"整批一份"改为按 prd/context/supplementary 三模块分别记录，仅内容变化的模块执行"先删后写"（补充需求单独修改不再连带重建 PRD/上下文文档向量，节省 embedding 调用） |
| `application*.yml` / `docker-compose.yml` / `.env.example` | 修改 | embedding 独立端点配置（`LLM_EMBEDDING_BASE_URL`/`LLM_EMBEDDING_API_KEY`，缺省回落 DASHSCOPE/LLM 主配置）；RAG 切片默认 900→500（rag-benchmark 验证更优） |
| `views/ProjectCreate.vue` | 修改 | Git 地址前端校验放宽为标准 http/ssh/git 协议格式 |
| `test/GitCloneServiceTest.java` | 新增 | URL 校验/清理边界 3 用例 |
| `test/SemanticServiceIncrementalReindexTest.java` | 新增 | 模块级增量重建 2 用例（仅补充变化→只重建该模块；无变化→零重建零 embed） |
| `docs/迭代历程.md` | 修改 | 回填 v5.13~vP5 历史条目与路线规划行；新增 v7.0/v7.1 条目 |

### 验证结果

- 后端 `mvn clean test`（JDK 17 + Maven 3.9.16）：**100 个测试全部通过，BUILD SUCCESS**（新增 13 个：DedupTest 5 + ReportTest 3 + GitCloneServiceTest 3 + IncrementalReindexTest 2）
- 前端 `npm run build`：通过（complete 回调向后兼容——旧字段 total 仍存在）
- 勘误：此前会话报告的"97/105 个测试"含 target 下改名前遗留的过期编译类 SecurityApiTest（5 个用例，源码已于 v6.3 重命名为 SecurityApiIntegrationTest 并被 surefire 排除）；本次 `mvn clean` 后以 100 为准

### 预期影响（度量校准）

- 同语义不同标题的重复用例不再全量落库（用例总数预期略降、单条质量预期升）
- "正向/逆向"成对用例不再被标题去重误删（正逆向覆盖率提升）
- SSE 推送数与落库数不一致时，用户能在完成提示中看到丢弃原因分类
- 聚焦类型过滤导致 0 条时，报错文案指向"调整聚焦类型"而非误导排查生成失败
- 项目 settings 损坏时报"项目配置解析失败"而非"请先添加 PRD 文档"

---

## v7.0 — 执行可信度修复（v7.x 系列首版：基于全链路代码审查）
**日期**: 2026-08-22
**基线**: v6.9
**主题**: 执行链路诚实化——取消复活、调度器误伤、假通过、skip 决策引导（对应风险清单 E1/E2/E3/E4/E8/E12）

### 背景

v7.x 系列基于《docs/代码审查风险清单.md》（80 项，L/A/G/R/E/C 六系列）按"诚实化 → 闭环 → 精准投喂 → 效率收尾"三波推进。v7.0 聚焦执行链路 4 个 P0"结果不可信"问题。

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| `service/ExecutionService.java` | 修改 | **E1**：cancelBatch/cancelExecution 的 pending 分支补运行时取消标志（此前仅改 DB 状态拦不住 worker）；两处收尾前复查 cancelled 状态、已取消记录不被覆盖。**E3**：errorMessage 参与最终状态判定，浏览器启动失败/导航异常/无步骤不再记 passed，summary 附原因。**E4**：state_assert 诚实断言（新增 `assertExpected` 静态方法：expected 含 URL/标题语义时提取关键词与页面 url/title 包含比较，无法比较时如实标 skipped 不误报） |
| `agent/ExecutionAgent.java` | 修改 | **E8**：注入 RuntimeStore，单步内 4 个耗时点补心跳（元素描述/每轮定位/策略决策/生效判断后），防慢步骤被误判 worker 已死。**E12**：策略决策 prompt 增加决策规则（found=false 且有备用选择器 → 优先 dom_click）；skip 的 error 按来源取真实 reason，不再统一栽赃"LLM 决策跳过" |
| `service/TaskRetryDispatcher.java` | 修改 | **E2**：dispatchQueued 在 CAS claim 前跳过执行类型任务——修复 v6.6 引入的回归（HaTaskScheduler 每 15s 扫 QUEUED 会抢占执行任务的 QUEUED→start() 窗口并误标 NEEDS_REVIEW(UNSUPPORTED_RETRY)） |
| `test/ExecutionServiceAssertTest.java` | 新增 | assertExpected 纯函数 7 用例（URL 命中/未命中/API 形态 skipped/中文目标 skipped/null 状态 skipped 等） |
| `test/TaskRetryDispatcherExecutionFilterTest.java` | 新增 | E2 回归测试 3 用例（执行任务 claim 前跳过/普通任务照常分发/非 QUEUED 忽略） |

### 前端变更

无（状态枚举不变，error/summary 内容更丰富由现有 UI 自动展示）。

### 验证结果

- 后端 `mvn test`：**97 个测试全部通过，BUILD SUCCESS**
- 前端 `npm run build` 通过（无代码改动，例行验证）

### 预期影响（度量校准）

基础设施故障的执行记录将如实显示 failed（此前显示 passed"通过 0, 失败 0"）；批量执行不再产生 NEEDS_REVIEW 误报；agent 模式步骤 skip 率预期下降（有备用选择器的步骤改走 dom_click）。

---

## v6.9 — 高可用收口：故障演练与容量
**日期**: 2026-08-22
**基线**: v6.8
**主题**: 任务 timeline 回放、故障演练/容量脚本、运维手册收口

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| `db/migration/mysql/V10__add_agent_task_events.sql` | 新增 | agent_task_events timeline 表 |
| `entity/AgentTaskEvent.java`、`repository/AgentTaskEventRepository.java` | 新增 | 任务事件实体与仓储 |
| `service/AgentTaskService.java` | 修改 | 状态/checkpoint 变更自动记录事件；新增 timeline 查询 |
| `controller/AgentTaskAdminController.java` | 修改 | 新增 `GET /api/admin/tasks/{id}/timeline` |
| `scripts/ha-fault-drill.ps1` | 新增 | 故障演练入口（LLM/工具/kill -9/Redis/取消） |
| `scripts/ha-capacity-drill.ps1` | 新增 | 容量与阈值基线入口 |
| `docs/运维手册.md` | 修改 | 新增高可用任务与演练章节 |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| `api/task.js` | 修改 | 新增 `getTaskTimeline` |
| `views/TaskCenter.vue` | 修改 | 详情抽屉新增任务回放 timeline |

### 验证结果

- 后端 `mvn verify` BUILD SUCCESS
- 前端 `npm test` / `npm run build` 通过

---

## v6.8 — 高可用 P3：队列与多实例
**日期**: 2026-08-22
**基线**: v6.7
**主题**: Redis Streams 事件总线、QUEUED CAS 抢占、本地状态迁 Redis、DLQ/失败率告警

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| `service/TaskEventStreamService.java` | 新增 | Redis Streams 事件总线（XADD/XREADGROUP），DB 轮询兜底 |
| `repository/AgentTaskRepository.java` | 修改 | `claimQueued` CAS 更新（仅 QUEUED 可领取） |
| `service/AgentTaskService.java` | 修改 | 创建任务后发布事件；`claimQueued` 抢占 |
| `service/TaskRetryDispatcher.java` | 修改 | `dispatchQueued` 先 CAS 抢占，防双执行 |
| `service/HaTaskScheduler.java` | 修改 | 调度先消费 Redis 事件，再做 DB 轮询 |
| `service/AnalysisService.java` | 修改 | 分析互斥改 RuntimeStore flag，多实例自洽 |
| `service/ExecutionService.java` | 修改 | 移除本地 executionCancellations Map，取消状态读 RuntimeStore |
| `monitoring/prometheus/alerts.yml` | 修改 | 新增 DLQ 非空、任务失败率告警 |

### 前端变更

无业务代码变更，`npm run build` 回归通过。

### 验证结果

- 后端 `mvn verify` BUILD SUCCESS
- `AgentTaskServiceTest.claimQueuedUsesCasUpdate` 等通过
- 前端 `npm run build` 成功

---

## v6.7 — 高可用 P2：断点续跑与降级闭环
**日期**: 2026-08-22
**基线**: v6.6
**主题**: 分析断点续跑、规则兜底降级标记、LLM provider 熔断、Telemetry 关联 task_id/attempt、前端任务中心

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| `service/AnalysisService.java` | 修改 | `runAnalysisResume`：completed 分析结果复用，跳过扫描/解析重建语义索引与缺失状态机 |
| `service/TaskRetryDispatcher.java` | 修改 | 分析重试统一走 `runAnalysisResume` |
| `service/AgentTaskService.java` | 修改 | 新增 `markDegraded`（记录 degraded 并打指标）；`getAttempt` |
| `service/TestCaseService.java` | 修改 | 生成/流式/追加生成出现 `rule_based` 用例时标记任务 degraded；Telemetry 回填任务上下文 |
| `common/LlmCircuitBreaker.java` | 新增 | LLM 连续失败熔断（默认 5 次/30s），chat/stream/image 接入 |
| `service/TelemetryService.java` | 修改 | `start` 支持 taskId/attempt；ThreadLocal 任务上下文 |
| `entity/TaskTelemetry.java` + `db/migration/mysql/V9__add_task_telemetry_task_link.sql` | 修改/新增 | `task_telemetry` 增加 task_id/attempt |
| `resources/application.yml` / `.env.example` / `docker-compose.yml` | 修改 | 新增 LLM 熔断配置 |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| `api/task.js` | 修改 | 新增 `listTasks/getTask/retryTask` |
| `views/TaskCenter.vue` | 新增 | 任务中心：筛选/表格/分页/详情抽屉/重试 |
| `router/index.js`、`App.vue` | 修改 | 新增 `/tasks` 路由与仅 ADMIN 导航入口 |

### 验证结果

- 后端 `mvn verify`（含 JaCoCo）BUILD SUCCESS
- `LlmCircuitBreakerTest` / `AgentTaskServiceTest`（markDegraded/getAttempt）等通过
- 前端 Vitest 7 个用例通过，`npm run build` 成功

---

## v6.6 — 高可用 P1：执行接入与工具超时
**日期**: 2026-08-22
**基线**: v6.5
**主题**: MCP 工具超时/错误分类/幂等重试，执行任务接入 agent_task+租约，任务 TTL 与 QUEUED 调度，任务/工具指标

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| `common/ToolRetryPolicy.java` | 新增 | 工具错误分类与幂等性判定（截图/状态/滚动/只读查询可重试） |
| `mcp/McpConnection.java` | 修改 | 普通/流式 MCP 调用加请求超时，超时清理 pending 并抛 TOOL_TIMEOUT |
| `mcp/McpClientManager.java` | 修改 | 幂等工具超时/进程不可用自动重试 1 次；新增 `aicasetest.tool.failures_total` 指标 |
| `service/AgentTaskService.java` | 修改 | 新增执行任务类型、`createTaskWithId`、TTL 过期、QUEUED 查询、任务生命周期指标 |
| `service/ExecutionService.java` | 修改 | 执行任务接入 agent_task，browser_launch/step{N} checkpoint，终态与取消同步 |
| `service/HaTaskScheduler.java` | 新增 | 租约恢复、TTL 过期、QUEUED 兜底分发定时任务 |
| `service/TaskRetryDispatcher.java` | 修改 | 新增 `dispatchQueued`，供调度器分发排队任务 |
| `resources/application.yml` / `.env.example` / `docker-compose.yml` | 修改 | 新增 MCP 超时、任务 TTL、调度间隔配置 |

### 前端变更

无业务代码变更，`npm test` / `npm run build` 回归通过。

### 验证结果

- 后端 `mvn verify`（含 JaCoCo）BUILD SUCCESS
- `ToolRetryPolicyTest` / `AgentTaskServiceTest` 等新增与回归单测通过
- 前端 Vitest 7 个用例通过，`npm run build` 成功

---

## v6.5 — 高可用 P0：任务状态持久化与中断恢复
**日期**: 2026-08-22
**基线**: v6.4
**主题**: agent_task 任务状态机、租约心跳与启动恢复、管理端任务重试、LLM 重试分类

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| `db/migration/mysql/V8__add_agent_task.sql` | 新增 | agent_task 表（状态/phase/checkpoint/attempt/lease/heartbeat/error） |
| `entity/AgentTask.java`、`repository/AgentTaskRepository.java` | 新增 | 任务实体与仓储（按状态租约查询、状态计数、Specification 分页） |
| `service/AgentTaskService.java` | 新增 | create/start/checkpoint/succeed/fail/cancel/NEEDS_REVIEW/DLQ/requeue；启动+定时恢复 stale RUNNING |
| `service/TaskRetryDispatcher.java` | 新增 | 管理端重试按任务类型分发分析/生成；追加生成提示前端重新触发 |
| `controller/AgentTaskAdminController.java`、`dto/AgentTaskDTO.java` | 新增 | `/api/admin/tasks` 列表/详情/重试（仅 ADMIN） |
| `common/LlmRetryPolicy.java` | 新增 | LLM 重试分类（超时/网络/429/5xx 可重试，4xx 与未知错误立即失败，带 4xx 模式识别） |
| `service/TestCaseService.java` | 修改 | 普通/SSE/追加生成接入任务生命周期并按阶段 checkpoint |
| `service/AnalysisService.java` | 修改 | 分析（含 SSE）接入任务生命周期：scan/parse/state_machine/index checkpoint |
| `config/DataInitializer.java` | 修改 | 启动先恢复租约过期任务为 NEEDS_REVIEW，再原有项目/执行记录恢复 |
| `controller/TaskController.java` | 修改 | `/api/tasks/stats` 增加 `agentTasks` 状态计数 |
| `service/LlmService.java` | 修改 | chat/stream/image 重试循环接入分类与抖动（`llm.retry.max-attempts`） |
| `resources/application.yml` / `.env.example` / `docker-compose.yml` | 修改 | 新增 `LLM_RETRY_MAX_ATTEMPTS`、`APP_HA_TASK_LEASE_SECONDS` 配置 |

### 前端变更

无业务代码变更，`npm run build` 回归通过。

### 验证结果

- 后端 `mvn compile`（Maven 3.9 + JDK 17）BUILD SUCCESS
- 新增 `AgentTaskServiceTest`（5 个）与 `LlmRetryPolicyTest`（3 个）通过，`LlmServiceTest` 回归通过
- 前端 `npm run build` 成功
- 全量 `mvn test` 中既有 `JwtAuthFilterTest` / `ProductionGuardTest` 3 个环境相关失败保持基线状态（本版未改动相关文件）

---

## v6.4 — RAG 切片化与多源检索增强
**日期**: 2026-08-22
**基线**: v6.3
**主题**: 需求类文档切片索引、contextDocs/补充需求入 Milvus、多路 RRF 重排、失败经验闭环、生成前按需重建

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| `service/RagTextChunker.java` | 新增 | Markdown 标题 + 段落切片，支持重叠窗口与章节标题元数据 |
| `service/SemanticService.java` | 修改 | 需求类上下文切片索引、RRF 融合检索、失败经验检索、按文档指纹按需重建 |
| `service/MilvusService.java` | 修改 | `SearchHit` 增加 module，支持 module 过滤检索 |
| `service/ProjectService.java` | 修改 | PRD/上下文文档/补充需求保存时统一重建切片索引 |
| `agent/OrchestratorAgent.java` | 修改 | 去掉整段 PRD 自我检索，查询段改为模块/需求/上下文文档/补充需求，检索并注入失败经验 |
| `agent/TestGeneratorAgent.java` | 修改 | prompt 注入 `ragFailures`，`ragContexts` 预算调整为 6×1200 |
| `dto/PrdAnalysisResult.java` | 修改 | 新增 `ragFailures` |
| `resources/application.yml` / `.env.example` / `docker-compose.yml` | 修改 | 新增 RAG 切片/RRF/topK 配置 |
| `resources/skills/test-generation-prd-footer.md` | 修改 | 补充 ragContexts/ragFailures 使用说明 |
| `service/RagTextChunkerTest.java` / `service/SemanticServiceRrfTest.java` | 新增 | 切片与 RRF 单测 |

### 前端变更

无业务代码变更，`npm run build` 回归通过。

### 验证结果

- 后端 `mvn compile`（Maven 3.9 + JDK 17）BUILD SUCCESS
- 新增单测 6 个全部通过
- 前端 `npm run build` 成功

---

## v6.3 — 本地代码审查整改：安全与工程健壮性补强
**日期**: 2026-08-21
**基线**: v6.2
**主题**: 依据《本地代码审查修改方案》补齐首登强制改密、SSE 票据化鉴权、业务组件口径统一、项目列表 N+1 优化，以及 Telemetry/H2/MCP/前端存储加固

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| `entity/User.java`、`dto/UserDTO.java`、`service/AuthService.java`、`config/DataInitializer.java` | 修改 | 新增 `mustChangePassword`；默认管理员初始化与“仍在使用初始密码”的存量管理员标记为 true；改密后清除并随登录/me 返回 |
| `security/SseTicketService.java`、`controller/SseTicketController.java` | 新增 | SSE 短期票据（默认 300s TTL、TTL 内可复用适配 EventSource 重连）；`POST /api/sse/ticket` 以 Bearer 换票 |
| `security/JwtAuthFilter.java` | 修改 | SSE 改收 `?ticket=`；长期 JWT 不再进入 SSE URL，仅媒体访问（video/file/report）保留 `?token=` 兼容 |
| `common/BusinessComponentPolicy.java` | 新增 | 统一业务组件判定：`needsLlmSummary`（默认 0.3）与 `inCoverage`（需严格 >0）；解析失败默认排除 |
| `analyzer/VueAnalyzer.java`、`agent/TestGeneratorAgent.java` | 修改 | 复用统一策略，消除 Analyzer `>=0.3` 与 Generator `>=0` 口径不一致，0 分/异常分组件不再漏进覆盖清单 |
| `service/ProjectAccessService.java`、`service/ProjectService.java` | 修改 | 新增 `getAccessLevels` 批量计算访问级别，项目列表不再逐项目重复查用户/项目/组成员（消除 N+1） |
| `service/TelemetryService.java` | 修改 | `finish` 清空线程残留 `contextStack` 与 `localPhase`，避免线程池复用脏状态/内存滞留 |
| `resources/application.yml`、`resources/application-dev.yml` | 修改/新增 | H2 Console 默认关闭、仅 dev profile 开放；新增 SSE 票据 TTL 与业务组件阈值配置 |
| `security/SecurityConfig.java` | 修改 | 补 `frameOptions.sameOrigin()` 支持 H2 Console/iframe 预览 |
| `config/ProductionGuard.java` | 修改 | prod 门禁新增 `MCP_BRIDGE_TOKEN` 必须显式覆盖默认弱 token 校验 |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| `stores/auth.js`、`App.vue` | 修改 | 暴露 `mustChangePassword`；首登/初始密码强制弹出不可关闭改密弹窗并阻断主功能，改密成功清除标记 |
| `api/sse.js`、`api/testcase.js`、`views/ProjectDetail.vue`、`views/TestCaseList.vue` | 新增/修改 | SSE 先以 Bearer 换取短期 ticket 再用 `?ticket=` 建连，长期 JWT 不再进入 SSE URL |

### 验证结果

- 后端 `mvn compile`（Maven 3.9 + JDK 17）BUILD SUCCESS
- 前端 `npm run build` 成功
- 已通过 docker compose 重新构建并部署 backend/frontend，新版本在本地栈生效

---

## v6.2 — 分析全链路并行化与状态机收口
**日期**: 2026-08-20
**基线**: v6.1
**主题**: 前端/后端代码分析并行、逐组件 LLM 摘要并发、状态机提取合并为单次调用并做确定性校验；Telemetry 跨线程埋点

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| `service/AnalysisService.java` | 修改 | 前端与后端分析改用 2 线程并发执行（独立定长线程池），不再串行；子线程通过 `bindPhase` 绑定共享埋点上下文 |
| `analyzer/VueAnalyzer.java` | 修改 | 逐组件 LLM 语义增强改有界并发（`EXECUTOR_LLM_CONCURRENCY`），规避串行 N 次调用这一分析最大瓶颈 |
| `agent/StateMachineAgent.java` | 修改 | 状态机提取与前端增强合并为 1 次 LLM 调用；新增 `validateTransitions` 将 from/to 归一化为规范 code、剔除未知状态与重复边 |
| `service/TelemetryService.java` | 修改 | 新增 `bindPhase`/`currentContext`/`currentPhaseOverride` 跨线程传播 phase；`record`/`closePhase` 改为线程安全累加 |
| `resources/application.yml` / `.env.example` / `docker-compose.yml` | 修改 | 新增 `app.executor.llm-concurrency`（默认 4，`.env` 建议 8） |

### 验证结果

- 前端 `npm run build` 成功
- 后端编译以 `docker compose build backend`（Maven 3.9 + JDK 17）验证

---

## v6.1 — 前端 Agentic RAG + 后端 SAINT
**日期**: 2026-08-20
**基线**: v6.0
**主题**: 前端逐组件语义索引（Agentic RAG）与后端操作依赖图（SAINT）融入分析与生成链路；统一 prompt 上限；SSE 鉴权与页面刷新状态恢复修复

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| `analyzer/VueAnalyzer.java` | 修改 | 逐组件生成语义摘要（交互事件/API/状态/路由/关键词 + 按需源码片段 + 业务分），支持 LLM 语义增强 |
| `analyzer/SpringAnalyzer.java` | 修改 | 新增 SAINT 风格操作依赖图提取；端点补充响应结构、业务逻辑片段与异常类型采集；LLM summary 降采样防超限 |
| `analyzer/result/OperationDep.java` | 新增 | 操作依赖图节点（operation/kind/file/description/dependsOn） |
| `analyzer/result/EndpointInfo.java` / `BackendResult.java` / `FrontendResult.java` / `dto/PrdAnalysisResult.java` | 修改 | 新增 `responseBody`/`businessLogic`/`exceptions`/`dependencyGraph`/`componentSummaries`/`frontendComponents` 字段 |
| `service/SemanticService.java` | 修改 | 新增组件级索引 `replaceComponents` 与 `retrieveComponents`（cosine+keyword+business 融合评分、多查询段去重）；`retrieveContexts` 支持多查询段 |
| `service/MilvusService.java` | 修改 | 新增 `components` 集合并建索引；`search` 显式指定向量字段 `embedding` |
| `agent/OrchestratorAgent.java` | 修改 | 按 module/requirement 分段构造 RAG 查询（上限 8 段），检索需求命中的前端组件摘要 |
| `agent/TestGeneratorAgent.java` | 修改 | 覆盖清单并入前端组件与操作依赖；前端组件按业务分过滤避免公共组件泄漏；RAG/文档上下文降采样 |
| `service/LlmService.java` | 修改 | 新增 `llm.max-prompt-chars` 统一 prompt 上限，抑制 277KB 巨型 prompt 触发 idle/read timeout |
| `security/SecurityConfig.java` | 修改 | `shouldFilterAllDispatcherTypes(false)`，修复 SSE 结束 async re-dispatch 时无谓的 Access Denied |
| `resources/application.yml` / `.env.example` / `docker-compose.yml` | 修改 | 新增 `LLM_MAX_PROMPT_CHARS`/`LLM_MAX_CONTEXT_CHARS`/HTTP 超时配置；默认模型切到 `mimo-v2.5`、base-url 切到 `opencode.ai/zen/go/v1` |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| `views/ProjectDetail.vue` | 修改 | 刷新/重新进入后若项目仍在分析/生成，恢复轮询与终态完成/失败提示 |
| `views/TestCaseList.vue` | 修改 | 生成中刷新页面后恢复轮询与完成/失败提示 |

### 验证结果

- 前端 `npm run build` 成功
- 后端编译以 `docker compose build backend`（Maven 3.9 + JDK 17）验证

---

## v6.0 — Spring AI 混合重构（保留 MCP）
**日期**: 2026-08-18
**主题**: LLM 文本/流式/JSON/Embedding 层从 MCP 子进程迁移到 Spring AI OpenAI starter；保留 MCP 浏览器/工具/多模态

### 变更

| 文件 | 变更 | 说明 |
|---|---|---|
| `backend/pom.xml` | 修改 | Spring Boot 3.2.5 -> 3.4.5；新增 `spring-ai-bom` 与 `spring-ai-starter-model-openai` 1.0.0 |
| `service/LlmService.java` | 重构 | `chat`/`chatStreaming`/`chatJson` 改用 `ChatClient`；`isConfigured` 改查 `ChatModel`；新增 `cancelStreaming()`；`chatWithImage` 保留 MCP |
| `service/EmbeddingService.java` | 重构 | 从 MCP `llm_embedding` 改为 Spring AI `EmbeddingModel`，新增 `getDimensions()` |
| `mcp/McpClientManager.java` | 修改 | 不再拉起 `llm-chat`/`llm-stream`/`llm-embedding`，保留 `llm`(vision)/`playwright`/`tools` |
| `resources/application.yml` / `application-prod.yml` | 修改 | 新增 `spring.ai.openai.*` 映射与 `completions-path`/`embeddings-path` 覆盖 |
| `docs/spring-ai-migration.md` | 新增 | 迁移说明 + 前后调用链路图 + PoC 对比 + 验证记录 |
| 测试 | 修改/新增 | 修复 `PrdAgentTest`/`StateMachineAgentTest` mock 方法；新增 `LlmServiceTest`、`EmbeddingServiceTest` |

### 验证结果

- `mvn verify` BUILD SUCCESS（JaCoCo 门禁通过，测试全绿）
- `npm run build` 成功；`docker compose config` 通过
- 真实 Spring AI 调用 `qwen3.7-max` 返回 200（`/api/settings/test-llm` 响应 `ok`）
- 真实 Spring AI 流式（`LlmStreamingIntegrationTest` 开启时运行）收到分块并回传 usage
- 真实 Spring AI `qwen3.7-text-embedding` 返回 1024 维向量
- 真实 PRD 生成 SSE 触发多条 `case` 事件并以 `complete(total=7)` 结束

---

## Performance — 默认关闭 qwen3.7-max 思考模式
**日期**: 2026-08-17
**主题**: qwen3.7-max 默认输出大量 reasoning token，单次用例生成耗时 60-90 秒；实测关闭 `enable_thinking` 后同任务从 35.5s 降到 5.5s，token 从 1933 降到 352

### 变更

| 文件 | 变更 | 说明 |
|---|---|---|
| `mcp-server/index.js` | 修改 | `llm_chat` 支持 `enable_thinking`，默认关闭 |
| `mcp/McpClientManager.java` | 修改 | 透传 `LLM_ENABLE_THINKING` 到 MCP 子进程 |
| `resources/application.yml` / `docker-compose.yml` / `.env.example` / `.env` | 修改 | 新增 `LLM_ENABLE_THINKING=false` 配置 |

### 验证结果

- 同 Prompt 实测：开启思考 35.5s / 1933 token；关闭思考 5.5s / 352 token

---

## Performance — 思考模式按任务粒度拆分
**日期**: 2026-08-17
**主题**: PRD 解析/状态机提取保留思考模式，用例生成与 AI 评审默认关闭思考模式

### 变更

| 文件 | 变更 | 说明 |
|---|---|---|
| `service/LlmService.java` | 修改 | 新增 `chatWithAnalysis`，`chat`/`chatStreaming` 默认走 `LLM_THINKING_GENERATION` |
| `agent/PrdAgent.java` / `agent/StateMachineAgent.java` | 修改 | 分析类调用改为保留思考模式 |
| `mcp-server/index.js` | 修改 | `llm_chat` 支持按调用覆盖 `enable_thinking` |
| `resources/application.yml` / `docker-compose.yml` / `.env.example` / `.env` | 修改 | 新增 `LLM_THINKING_ANALYSIS=true`、`LLM_THINKING_GENERATION=false` |

---

## Fix — 重新生成时清理 AI 评审历史
**日期**: 2026-08-17
**主题**: 重新生成会删除旧用例与版本快照，但未清理 `test_case_ai_reviews`，导致旧 AI 评审记录残留并与新记录混存

### 变更

| 文件 | 变更 | 说明 |
|---|---|---|
| `service/TestCasePersistenceService.java` | 修改 | `replaceAll` 先按项目删除旧 AI 评审历史，再写入新用例与新评审记录 |

### 验证结果

- Docker JDK17 镜像构建：通过

---

## Feature — 用例生成自动多轮补齐
**日期**: 2026-08-17
**主题**: PRD 驱动生成从“单轮 8-15 条”升级为按剩余覆盖缺口自动多轮补齐；标准档最多 3 轮，详尽档最多 4 轮，单次生成上限 60 条

### 变更

| 文件 | 变更 | 说明 |
|---|---|---|
| `agent/TestGeneratorAgent.java` | 修改 | PRD 生成拆为多轮，每轮用剩余 `coverageGaps` 继续补齐缺口，直到覆盖或达到轮数/条数上限 |
| `agent/TestGeneratorAgent.java` | 修改 | 每轮推送进度“第 N 轮补齐覆盖缺口...”，流式生成同步支持多轮 |

### 验证结果

- Docker JDK17 镜像构建：通过

---

## Fix — 编辑用例保存请求超时
**日期**: 2026-08-17
**主题**: 生成任务占用 MCP LLM 连接时，编辑用例触发的语义重建会在同一个 synchronized 连接上排队，导致 `PUT` 请求超过前端 30s 超时

### 变更

| 文件 | 变更 | 说明 |
|---|---|---|
| `service/SemanticIndexingAsyncService.java` | 新增 | 用例语义重建移到独立线程池异步执行 |
| `config/AsyncConfig.java` | 修改 | 新增 `semanticExecutor` 线程池 |
| `service/TestCaseService.java` | 修改 | 编辑用例改调异步语义重建，保存请求不再等待 embedding |
| `resources/application.yml` / `.env.example` | 修改 | 新增 `EXECUTOR_SEMANTIC_*` 配置 |

### 验证结果

- Docker JDK17 镜像构建：通过

---

## Fix — SSE 长连接超时导致“生成连接异常”
**日期**: 2026-08-17
**主题**: 用例生成包含多次 LLM 调用，耗时超过 5 分钟时后端 SSE 与 nginx 先后掐断连接，前端提示“生成连接异常”，且已完成结果被按取消丢弃；PRD 解析失败时透传 LLM 原始错误

### 变更

| 文件 | 变更 | 说明 |
|---|---|---|
| `controller/ProjectController.java` | 修改 | SSE 超时由 `app.sse.timeout-minutes` 控制，默认 30 分钟 |
| `resources/application.yml` | 修改 | 新增 `app.sse.timeout-minutes` 配置 |
| `docker-compose.yml` / `.env.example` | 修改 | 透传 `APP_SSE_TIMEOUT_MINUTES` |
| `frontend/nginx.conf` | 修改 | `proxy_read_timeout` / `proxy_send_timeout` 由 360s 提升至 1800s |
| `security/SecurityConfig.java` | 修改 | 放行 `/error`，避免 SSE 超时后的内部错误派发产生 AccessDenied 噪音 |
| `agent/PrdAgent.java` | 修改 | PRD 解析失败/结果为空时抛出 `BusinessException`，保留 LLM 原始错误原因 |

### 验证结果

- 后端 `mvn test`：通过
- 前端 `npm run build`：通过

---

## 配置 — 默认 LLM 切换为 qwen3.7-max
**日期**: 2026-08-17
**主题**: 默认语言模型从 mimo-v2.5-pro 切换为阿里百炼 qwen3.7-max

### 变更

| 文件 | 变更 | 说明 |
|---|---|---|
| `.env.example` / `docker-compose.yml` / `resources/application.yml` | 修改 | 默认 `LLM_PROVIDER=bailian`、`LLM_MODEL=qwen3.7-max`、`LLM_BASE_URL=百炼兼容地址` |

---

## v5.13 — 能力分层：MCP 工具化与 Prompt Skill 化
**日期**: 2026-08-16
**基线**: v5.12
**主题**: 候选 Agent 能力拆分为 MCP 工具与 Skill 模板；同时收口后端 LLM 增强、状态机前端增强、生成必须基于 PRD

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| `controller/McpBridgeController.java` | 新增 | 6 个 `/api/mcp/*` 桥接接口 |
| `tools-mcp-server/` | 新增 | MCP 工具：semantic_search/analyze_requirement_docs/extract_state_machine/review_test_cases/analyze_backend/analyze_frontend |
| `service/PromptSkillLoader.java` | 新增 | 从 classpath `skills/` 加载 Prompt 模板，缺失回退内嵌 Prompt |
| `resources/skills/*.md` | 新增 | 8 个 Prompt Skill 模板 |
| `mcp/McpClientManager.java` | 修改 | 注册并启动 `tools` MCP Server |
| `security/SecurityConfig.java` | 修改 | 放行 `/api/mcp/**`，控制器内做令牌校验 |
| `resources/application.yml` | 修改 | `mcp.servers.tools` 与 `app.mcp.bridge-url/bridge-token` |
| `docker-compose.yml` / `backend/Dockerfile` | 修改 | 部署与复制 tools-mcp-server |
| `analyzer/SpringAnalyzer.java` | 修改 | 后端规则提取 + LLM 增强，结果带 `sources` 来源标记 |
| `agent/StateMachineAgent.java` | 修改 | 状态机提取接入前端 pageFlows/apiCalls/componentStates 旁证增强 |
| `agent/OrchestratorAgent.java`、`agent/TestGeneratorAgent.java` | 修改 | 生成强制 PRD，PRD 失败不再回退代码驱动 |
| `service/TestCaseService.java`、`service/ProjectService.java` | 修改 | 生成前置校验改为 PRD 必需 |
| `agent/PrdAgent.java`、`StateMachineAgent.java`、`TestCaseReviewAgent.java`、`TestGeneratorAgent.java` | 修改 | Prompt 改为通过 PromptSkillLoader 加载 |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| `views/ProjectDetail.vue` | 修改 | 无 PRD 时禁用生成并提示“请先添加 PRD 文档” |
| `components/PrdPanel.vue` | 修改 | 无 PRD 提示不再提及代码驱动回退 |

### 验证结果

- 后端 `mvn test`：通过
- 前端 `npm run build`：通过
- `node --check tools-mcp-server/index.js`：通过
- tools MCP Server 注册 6 个工具

---

## 功能记录 — Grafana 埋点面板与 Prometheus 抓取修复
**日期**: 2026-08-16
**主题**: 放行 `/actuator/prometheus`、Grafana 增加任务埋点面板与 MySQL 原始表面板

### 变更

| 文件 | 变更 | 说明 |
|---|---|---|
| security/SecurityConfig.java | 放行 | `/actuator/prometheus` 无需登录，Prometheus 可抓取 |
| service/TelemetryService.java | 指标标签 | 埋点指标增加 project/status 标签 |
| monitoring/grafana/provisioning/datasources/datasources.yml | 新增 MySQL | Grafana 可直接查询 `task_telemetry` 原始数据 |
| monitoring/grafana/dashboards/aicasetest.json | 新增面板 | Task Duration/LLM Token/TTFT/Calls + Task Telemetry Raw |
| docker-compose.yml | Grafana 环境 | 透传 MySQL 连接变量 |

### 验证结果

- Prometheus `up{job="aicasetest-backend"}=1`
- Grafana provisioning 成功注册 Prometheus + MySQL 数据源
- AICaseTest SLO 仪表盘加载 11 个面板

---

## 功能记录 — Milvus 鉴权与 embedding 修复
**日期**: 2026-08-16
**主题**: Milvus 账号密码映射、embedding 模型切换 qwen3.7-text-embedding、向量维度 1024

### 变更

| 文件 | 变更 | 说明 |
|---|---|---|
| resources/application.yml | 配置补全 | `app.milvus.username/password` 显式绑定环境变量；`MILVUS_DIMENSION` 默认 1024 |
| mcp/McpClientManager.java | embedding 模型 | MCP 子进程注入 `OPENAI_EMBEDDING_MODEL=qwen3.7-text-embedding` |
| service/MilvusService.java | 维度自愈 | 集合维度与配置不一致时自动删除重建，避免写入维度错误 |
| docker-compose.yml / .env.example | 配置 | 默认 embedding 模型 qwen3.7-text-embedding、Milvus 维度 1024 |

### 验证结果

- `qwen3.7-text-embedding` 实测返回 1024 维向量
- Milvus root 密码已与 `MILVUS_ROOT_PASSWORD` 对齐
- Milvus 连接成功，cases/contexts/failures 集合均为 1024 维
- 后端日志不再出现 `UNAUTHENTICATED` 与 `Embedding failed`

---

## 功能记录 — 分析/生成/AI评审埋点
**日期**: 2026-08-16
**主题**: LLM usage 透出、任务耗时/首 token 埋点、task_telemetry 落库与 Prometheus 指标

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| mcp-server/index.js | usage 透出 | `llm_chat`/`llm_chat_with_image` 返回 usage；流式启用 `include_usage` 并记录 usage |
| mcp/McpToolResult.java / McpConnection.java / McpClientManager.java | 元数据解析 | 工具调用返回文本 + usage 元数据，保留旧文本接口兼容 |
| dto/LlmCallResult.java | 新增 | 单次 LLM 调用耗时/token/首 token 结果 |
| service/TelemetryService.java | 新增 | ThreadLocal 任务栈、阶段累计、落库与 Micrometer 指标 |
| entity/TaskTelemetry.java / repository/TaskTelemetryRepository.java | 新增 | `task_telemetry` 埋点表 |
| db/migration/mysql/V6__add_task_telemetry.sql | 新增 | Flyway V6 迁移 |
| service/AnalysisService.java | 接入 | 分析按 scan/frontend/backend/state_machine 阶段埋点 |
| agent/OrchestratorAgent.java | 接入 | 生成任务埋点（prd/generation 阶段） |
| agent/TestCaseReviewAgent.java | 接入 | AI 评审 LLM 阶段埋点 |
| service/TestCaseService.java | 接入 | 单条重评独立 ai_review 任务埋点 |
| service/LlmService.java | 接入 | 每次 LLM 调用自动记录耗时/usage/首 token |

### 指标

- `aicasetest.task.duration`：任务/阶段耗时
- `aicasetest.llm.tokens`：prompt/completion/total token 累计
- `aicasetest.llm.ttft`：流式首 token 耗时
- `aicasetest.llm.calls`：LLM 调用次数

### 验证结果

- `mvn compile`：BUILD SUCCESS
- `node --check mcp-server/index.js`：通过
- 流式 `stream_options.include_usage` 实测返回 usage

---

## v5.12 — AI 评审闭环与覆盖引用收口
**日期**: 2026-08-16
**基线**: v5.11
**主题**: AI 评审历史落库、单条重评异步化、采纳语义修正、覆盖引用合并与过滤、覆盖率口径统一

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| db/migration/mysql/V5__add_test_case_ai_reviews.sql | 新增 | `test_case_ai_reviews` 评审历史表 |
| entity/TestCaseAiReview.java / repository/TestCaseAiReviewRepository.java | 新增 | 评审历史实体与仓储 |
| service/AiReviewHistoryRecorder.java | 新增 | 生成/重评统一写历史 |
| agent/TestCaseReviewRunner.java | 新增 | 单条重评提交 generationExecutor 异步执行 |
| agent/TestCaseReviewAgent.java | 重构 | `suggestedChanges` 固定五键归一化；`coverageRefs` 合并而非覆盖；接口引用按代码清单过滤 |
| service/TestCaseService.java | 重构 | 重评改为 reviewing/执行/failed 状态机；采纳同步 `reviewStatus`；覆盖率并入计划引用；删除级联清理历史 |
| controller/TestCaseController.java | 调整 | 重评接口改为立即返回 `{status:"reviewing"}` |
| dto/UpdateTestCaseRequest.java | 扩展 | 新增可选 `reviewStatus` |
| service/TestCasePersistenceService.java | 扩展 | 生成落库后补记 AI 评审历史 |
| service/ProjectService.java | 扩展 | 删除项目级联清理评审历史 |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| utils/aiReview.js | 新增 | `pollAiReview` 轮询重评结果、`hasSuggestedChanges` 非空建议判断 |
| components/TestCaseCard.vue | 重构 | 重评改为异步轮询；采纳只提交非空建议并同步 `reviewStatus`；补齐评审中/失败文案 |
| views/TestCaseList.vue | 重构 | 同上；评审结果表建议判断与状态文案同步 |

### 验证结果

- `mvn compile`：BUILD SUCCESS（149 源文件）
- `npm run build`：成功

---

## v5.11 — 生成链路 AI 评审与前端体验
**日期**: 2026-08-16
**基线**: v5.10
**主题**: 需求文档多篇化、coverageRefs 注入与 AI 评审、代码分析/暗色主题/脑图导出体验

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| agent/OrchestratorAgent.java / PrdAgent.java | 需求文档分类 | PRD 文档 / 上下文文档 / 补充需求三类输入 |
| agent/TestGeneratorAgent.java | coverageRefs | 生成前注入覆盖清单与缺口，输出 `coverageRefs` |
| agent/TestCaseReviewAgent.java | 新增 | 规则兜底 + LLM 评审，结果写入 `executionHints.aiReview` |
| controller/TestCaseController.java | 新增接口 | 单条用例重新 AI 评审 |
| analyzer/result/BackendResult.java / FrontendResult.java | 修复 | 补充无参构造器，接口清单反序列化恢复 |
| service/XmindService.java | 移除 | 删除已废弃的 XMind 导入模板 |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| components/PrdPanel.vue | 需求文档面板 | “需求文档”支持多篇 PRD/上下文文档；“其他上下文信息”更名“补充需求” |
| components/TestCaseCard.vue / views/TestCaseList.vue | AI 评审 UI | 评审结果表、卡片评审区块、采纳/忽略/重评 |
| views/CodeAnalysis.vue | 统计与筛选 | 全 tab 关键字筛选 + 顶部统计摘要条 |
| views/MindMapPreview.vue | PNG 导出 | 全展开离屏渲染、2x 高清、不改展开状态 |
| styles/index.scss / StateMachineViewer.vue | 暗色主题 | danger/success/warning 暗色令牌与浅色硬编码收敛 |

### MCP 服务变更

| 文件 | 变更 | 说明 |
|---|---|---|
| mcp-server/index.js | max_tokens | `llm_chat` 8192 → 16384 |

### 验证结果

- `mvn compile`：BUILD SUCCESS
- `npm run build`：成功

---

## v5.10 — PRD 上下文改版与用例级执行历史
**日期**: 2026-08-16
**基线**: v5.9 / vP5
**主题**: “额外 Prompt”更名“其他上下文信息”、上下文文档支持 PRD 式多来源录入、执行历史可按用例维度查看

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| service/ProjectService.java | context 字段兼容 + 文档解析 | `otherContextInfo` 落库、旧键 `extraPrompt` 回退；新增 md/txt/PDF 解析与链接抓取 |
| controller/ProjectController.java | 新增 2 个接口 | `context/docs/upload`、`context/docs/fetch` |
| dto/PrdAnalysisResult.java | 字段更名 | `extraPrompt` → `otherContextInfo` |
| agent/OrchestratorAgent.java | 读取兼容 | 新键优先、旧键兜底 |
| agent/TestGeneratorAgent.java | LLM context 更名 | 注入 `otherContextInfo` |
| controller/ExecutionController.java | 新增参数 | 执行历史支持 `testCaseId` 过滤 |
| service/ExecutionService.java | 过滤后统计 | items/stats/trend 基于过滤后记录计算 |
| skill/PlaywrightRecordSkill.java | 新增 scroll | 元素定位滚动兜底 |
| agent/ExecutionAgent.java | 滚动重试 | 多模态找不到时 down/up 重试 |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| components/PrdPanel.vue | 上下文改版 | “额外 Prompt”更名“其他上下文信息”；文档支持文本/md/PDF/链接四来源 |
| api/project.js | 新增 API 封装 | `uploadContextDoc` / `fetchContextDocUrl` |
| views/TestCaseList.vue | 操作列入口 | 新增用例级“执行历史”按钮 |
| views/ExecutionHistory.vue | 按用例过滤 | `testCaseId` 参数 + 提示条 + 查看全部 |
| components/TestCaseCard.vue | 执行记录弹窗 | 仅展示该用例最近执行记录 |

### MCP 服务变更

| 文件 | 变更 | 说明 |
|---|---|---|
| playwright-mcp-server/index.js | 新增工具 | `browser_scroll` 上下滚动当前页面 |

### 验证结果

- `mvn clean compile`：BUILD SUCCESS（144 源文件）
- `npm run build`：成功（13.74s）
- `node --check playwright-mcp-server/index.js`：通过

---

## v5.9 — 项目上下文与操作体验优化
**日期**: 2026-08-16
**基线**: v5.8 / vP5
**主题**: 创建后 Cookie 可编辑、项目详情操作区上移、PRD 面板改版并支持额外 Prompt 与多上下文文档

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| controller/ProjectController.java | 新增 4 个接口 | execution-cookies 读写 + context 聚合读写 |
| service/ProjectService.java | 新增 Cookie/Context 服务方法 | 存于 Project.settings JSON，兼容旧数据 |
| agent/OrchestratorAgent.java | 读取额外上下文 | extraPrompt/contextDocs 注入 PrdAnalysisResult |
| agent/TestGeneratorAgent.java | LLM context 扩展 | 生成时携带额外 Prompt 与上下文文档 |
| dto/PrdAnalysisResult.java | 新增字段 | extraPrompt/contextDocs |
| service/ExecutionService.java | 执行链路增强 | 步骤间停顿、点击坐标、MCP 失败不再吞错 |
| skill/PlaywrightRecordSkill.java | 点击/输入坐标回传 | 截图标注与录屏标记一致 |
| agent/ExecutionAgent.java | 输入后回车支持 | 配合搜索等表单流程 |
| mcp/McpConnection.java | MCP isError 抛错 | 避免浏览器操作失败被误判通过 |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| views/ProjectDetail.vue | 操作区上移 + Cookie 配置弹窗 | 高频操作不再需要滚到底部 |
| components/PrdPanel.vue | 紧凑改版 | 有 PRD 默认摘要；新增额外 Prompt 与上下文文档 |
| api/project.js | 新增 API 封装 | context / execution-cookies |
| views/ExecutionResult.vue | 结果页布局优化 | 录屏上移到步骤前、快照合并进概览 |
| views/TestCaseList.vue | 执行状态本地刷新 | 返回列表自动刷新状态 |

### 验证结果

- `mvn compile`：BUILD SUCCESS
- `npm run build`：成功

---

## 修复记录 — 安全基线误报
**日期**: 2026-08-14
**主题**: 修正 `.env.example` 占位符与 ProductionGuard 默认值误报为密钥

### 变更

| 文件 | 变更 | 说明 |
|---|---|---|
| .env.example | APP_JWT_SECRET 改为短占位符 | 避免触发 secret 扫描误报 |
| scripts/security-check.ps1 | 跳过 ProductionGuard.java | 文件内默认值为主动检测目标，非真实密钥 |

### 验证结果

- `scripts/security-check.ps1`：security check OK

---

## vP5 — 压测与容量
**日期**: 2026-08-14
**基线**: vP4
**主题**: k6 压测基线、线程池/队列参数调优、大数据量分页与索引验证

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| repository/TestCaseRepository.java | JpaSpecificationExecutor | 支持 Specification 分页 |
| service/TestCaseService.java | SQL 分页 | 列表分页下推数据库，保持筛选语义 |
| db/migration/mysql/V3__add_testcase_pagination_indexes.sql | 新增 | type/review/exec/title 复合索引 |
| config/AsyncConfig.java | 参数化 | keep-alive/await 可配置 |
| resources/application.yml | 线程池调优 | analysis/generation max=6 queue=50；execution max=12 queue=500 |

### 压测/脚本变更

| 文件 | 变更 | 说明 |
|---|---|---|
| loadtest/k6/smoke.js | 新增 | 冒烟基线 |
| loadtest/k6/load.js | 新增 | 20 VU 负载基线 |
| loadtest/k6/README.md | 新增 | 运行与阈值说明 |
| scripts/pagination-baseline.ps1 | 新增 | EXPLAIN 验证索引命中 |

### 验证结果

- `mvn test`：41 个测试，0 失败，5 个环境相关跳过；BUILD SUCCESS
- `npm run build`：成功
- `docker compose config`：通过
- `pagination-baseline.ps1` 语法：通过

---

## vP4 — 发布流水线
**日期**: 2026-08-14
**基线**: vP3
**主题**: GHCR 镜像推送、多环境部署、Flyway staging 演练、回滚

### 变更

| 文件 | 变更 | 说明 |
|---|---|---|
| .github/workflows/publish.yml | 新增 | tag/手动触发构建并推送 GHCR |
| docker-compose.yml | 镜像变量 | IMAGE_BACKEND/FRONTEND/TAG/PULL_POLICY |
| scripts/deploy.ps1 | 新增 | dev/staging/prod 多环境部署 |
| scripts/rollback.ps1 | 新增 | 应用镜像回滚 |
| scripts/mysql-restore.ps1 | 新增 | 数据库备份恢复 |
| scripts/flyway-staging-drill.ps1 | 新增 | 临时库 Flyway 迁移演练 |
| deploy/README.md | 新增 | 部署/回滚/Flyway 演练说明 |

### 验证结果

- `mvn compile` BUILD SUCCESS
- `npm run build`：成功
- `docker compose config`（GHCR 模式）：通过
- PowerShell 脚本语法解析：通过

---

## vP3 — 可观测与告警
**日期**: 2026-08-14
**基线**: vP2
**主题**: Grafana 面板、告警规则、traceId/access log、SLO

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| observability/ObservabilityFilter.java | 新增 | traceId 生成/透传、access log、SLO 指标 |
| observability/QueueMetrics.java | 新增 | 队列 queued/running Gauge |
| service/TaskQueueService.java | 指标方法 | queuedTotal/runningTotal |
| config/WebConfig.java | CORS | 暴露 X-Trace-Id |

### 监控/部署变更

| 文件 | 变更 | 说明 |
|---|---|---|
| monitoring/prometheus/prometheus.yml | 新增 | 抓取后端指标 |
| monitoring/prometheus/alerts.yml | 新增 | 宕机/5xx/P95/队列告警 |
| monitoring/grafana/ | 新增 | 数据源/仪表盘 provisioning |
| docker-compose.yml | 新增服务 | prometheus/grafana，仅本机端口 |

### 验证结果

- `mvn -Dtest=ObservabilityFilterTest test`：2/2 通过
- `mvn compile` BUILD SUCCESS
- `npm run build`：成功
- `docker compose config`：通过
- 仪表盘 JSON 解析校验：通过

---

## vP2 — 高可用与容灾
**日期**: 2026-08-14
**基线**: vP1
**主题**: MySQL 备份调度 + 恢复演练、任务恢复、资源限制、优雅停机

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| queue/TaskQueueStore.java | 新增 clearQueue | 队列恢复接口 |
| queue/MemoryTaskQueueStore.java | clearQueue 实现 | 清空内存 queued/running |
| queue/RedisTaskQueueStore.java | clearQueue 实现 | 删除 Redis 队列键 |
| service/TaskQueueService.java | recoverStaleTasks | 重启清理残留任务队列 |
| config/DataInitializer.java | 启动恢复 | 恢复卡死项目状态 + 队列清理 |
| config/AsyncConfig.java | 优雅停机 | 线程池等待任务完成 30s |
| resources/application.yml | graceful shutdown | 停机等待 30s |

### 部署/脚本变更

| 文件 | 变更 | 说明 |
|---|---|---|
| docker-compose.yml | 资源限制 + stop_grace_period | 所有服务 CPU/内存上限与停机宽限 |
| scripts/mysql-backup.ps1 | 新增 | Docker 内 mysqldump + 保留轮转 |
| scripts/schedule-backup.ps1 | 新增 | Windows 计划任务每日调度 |
| scripts/restore-drill.ps1 | 新增 | 临时库恢复演练与校验 |

### 验证结果

- `mvn -Dtest=MemoryTaskQueueStoreTest test`：4/4 通过
- `mvn compile` BUILD SUCCESS
- `npm run build`：成功
- `docker compose config`：通过

---

## vP1 — 上线安全加固
**日期**: 2026-08-14
**基线**: vT9
**主题**: TLS、密码/密钥强制、DB/Redis/Milvus 访问控制、文件上传与 URL 抓取加固

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| config/ProductionGuard.java | 新增 | prod 强制校验 JWT Secret/管理员密码，弱配置阻断启动，可降级告警 |
| common/UploadGuard.java | 新增 | 上传大小二次校验（默认 20MB） |
| agent/PrdAgent.java | URL 抓取加固 | 仅 http/https、禁私网/回环、超时/大小/重定向限制 |
| service/MilvusService.java | Milvus 鉴权 | 支持 MILVUS_USERNAME/MILVUS_PASSWORD |
| service/ProjectService.java | PDF 上传校验 | UploadGuard 接入 |
| service/TestCaseService.java | JSON/XMind 上传校验 | UploadGuard 接入 |
| resources/application.yml | multipart 限制 | max-file-size/max-request-size 20MB |
| resources/application-prod.yml | prod 安全配置 | APP_ENFORCE_SECURITY 默认 true |

### 前端/部署变更

| 文件 | 变更 | 说明 |
|---|---|---|
| frontend/nginx.conf | TLS | 443 ssl + 安全头 + 20MB 上传限制 |
| frontend/entrypoint.sh | 新增 | 证书缺失时自动生成自签证书 |
| frontend/Dockerfile | 构建 | 安装 openssl，暴露 80/443 |
| docker-compose.yml | 访问控制 | MySQL/Redis/Milvus 绑定 127.0.0.1；Milvus 鉴权；443 挂载证书 |
| scripts/generate-self-signed-cert.ps1 | 新增 | 本地自签证书生成 |
| .env.example | 安全默认值 | 强密码/密钥占位 + 上传/安全开关 |

### 验证结果

- `mvn compile` BUILD SUCCESS
- `mvn -Dtest=ProductionGuardTest,UploadGuardTest test`：4/4 通过
- `npm run build`：成功
- `docker compose config`：通过

---

## 修复记录 — CI npm audit（nanoid 高危）
**日期**: 2026-08-14
**主题**: 升级 nanoid 3.3.17 → 3.3.18，解除 npm audit high 门禁

### 修复内容

| 文件 | 修复 | 说明 |
|---|---|---|
| frontend/package-lock.json | nanoid 3.3.17 → 3.3.18 | 修复 GHSA-2v37-7h3g-55p8（custom generators loop） |

### 验证结果

- `npm audit --omit=dev --audit-level=high`：通过（仅剩 echarts moderate，不触发 high 门禁）
- `npm ci`：干净安装通过（391 packages）
- `npm test`：7/7 通过；`npm run build`：成功

---

## 修复记录 — vT7 CI 集成测试
**日期**: 2026-08-14
**主题**: MySQL 测试未覆盖 driver-class-name，H2 驱动连接 MySQL 导致上下文启动失败；容器启动超时

### 修复内容

| 文件 | 修复 | 说明 |
|---|---|---|
| test/MySqlFlywayIntegrationTest.java | 增加 spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver | 修复 CI 上 H2 驱动连接 MySQL URL 导致 Flyway 启动失败 |
| test/MySqlFlywayIntegrationTest.java | 增加 spring.flyway.locations=classpath:db/migration/mysql + baseline-on-migrate | Flyway 指向真实 MySQL 迁移目录 |
| test/MySqlFlywayIntegrationTest.java | MySQL 容器 withStartupTimeout(3m) | 避免 CI 拉镜像/初始化超时 |
| test/runtime/RedisRuntimeStoreIntegrationTest.java | Redis 容器 withStartupTimeout(3m) | 同上 |

### 验证结果

- 真实 MySQL 8.0.29 验证：Flyway 迁移 + JPA 读写 2/2 通过
- 本地无 Docker 环境：集成测试自动跳过，`mvn verify` BUILD SUCCESS

---

## vT9 — 安全扫描与部署加固
**日期**: 2026-08-14
**基线**: vT8
**主题**: .env.example + nginx 加固 + Redis AUTH + CI 安全/镜像 + 运维手册

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| .env.example | 新增 | 全量环境变量模板 |
| docker-compose.yml | Redis AUTH + REDIS_PASSWORD | 运行态安全 |
| .github/workflows/ci.yml | security/docker job | gitleaks + npm audit + 镜像构建 |
| docs/运维手册.md | 新增 | 部署/升级/备份/监控/排障 |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| frontend/nginx.conf | 安全头/登录限流/上传限制/gzip | Nginx 加固 |

### 验证结果

- docker compose config: 校验通过
- 前端构建: ✓ built
- 安全扫描: security check OK

---

## vT8 — 前端测试扩充与覆盖率门禁
**日期**: 2026-08-14
**基线**: vT7
**主题**: auth store 测试 + Vitest/JaCoCo 覆盖率门禁

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| backend/pom.xml | JaCoCo 插件 | LINE/INSTRUCTION ≥ 5% 检查 |
| .github/workflows/ci.yml | mvn -B verify | 触发 JaCoCo 检查 |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| src/stores/auth.test.js | 新增 2 个测试 | 登录持久化/登出清理 |
| vitest.config.js | v8 coverage + 阈值 | 覆盖率门禁 |
| package.json / package-lock.json | @vitest/coverage-v8 + test:coverage | 覆盖率脚本 |

### 验证结果

- 前端测试: Tests 7 passed（npm test --coverage，阈值通过）
- 前端构建: ✓ built
- 后端 verify: BUILD SUCCESS，JaCoCo checks met
- npm ci: 干净安装通过（391 packages）

---

## vT7 — Testcontainers 集成测试
**日期**: 2026-08-14
**基线**: vT6
**主题**: MySQL Flyway / Redis RuntimeStore 真实中间件集成测试

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| backend/pom.xml | testcontainers junit-jupiter/mysql | 集成测试依赖 |
| test/MySqlFlywayIntegrationTest.java | 新增 2 个测试 | MySQL Flyway V1/V2 + JPA 读写 |
| test/runtime/RedisRuntimeStoreIntegrationTest.java | 新增 3 个测试 | Redis 标志/登录计数/信号量 |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| 无 | - | 本版本无前端代码变更 |

### 验证结果

- 后端测试: Tests run 34, Failures 0, Skipped 5（本地无 Docker 自动跳过，CI 有 Docker 时运行）

---

## vT6 — 服务层与接口安全测试
**日期**: 2026-08-14
**基线**: vT5
**主题**: MockMvc API 安全测试 + ProjectService 级联测试 + 401 响应 NPE 修复

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| security/SecurityConfig.java | 修复 401 响应 NPE | Map.of 不允许 null，改用 LinkedHashMap |
| mcp/McpClientManager.java | app.mcp.enabled 开关 | 测试环境不拉起 MCP 子进程 |
| resources/application.yml | mcp.enabled 配置 | 默认 true |
| test/SecurityApiTest.java | 新增 5 个测试 | 健康公开/401/403/429 登录锁定 |
| test/service/ProjectServiceTest.java | 新增 1 个测试 | 项目级联删除 |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| 无 | - | 本版本无前端代码变更 |

### 验证结果

- 后端测试: Tests run 29, Failures 0（mvn test）

---

## vT5 — 安全与全量回归收口
**日期**: 2026-08-14
**基线**: vT4
**主题**: 敏感信息扫描 + 回归入口收口 + CI 锁文件兼容修复

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| scripts/security-check.ps1 | 新增 | .env 跟踪校验 + 密钥/私钥扫描 |
| scripts/verify-v5-stack.ps1 | 集成安全基线 | 回归入口包含安全检查 |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| package.json / package-lock.json | vitest 4.x → 3.x | 修复 CI npm 10 下 `npm ci` 与锁文件不同步问题 |

### 验证结果

- 后端测试: Tests run 23, Failures 0（mvn test）
- 前端测试: Tests 5 passed（npm test）
- 前端构建: ✓ built（npm run build）
- npm ci: 干净安装通过（379 packages）
- docker compose config: 校验通过
- 安全基线: security check OK

---

## vT4 — 运维与可观测基线
**日期**: 2026-08-14
**基线**: vT3
**主题**: Actuator/Prometheus 指标 + 备份脚本

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| backend/pom.xml | actuator + micrometer-prometheus | 标准健康/指标端点 |
| resources/application.yml | management 配置 | health/info/metrics/prometheus 暴露 |
| security/SecurityConfig.java | /actuator/health permitAll | 健康检查免认证 |
| scripts/backup-v5.ps1 | 新增 | data/outputs/MySQL 备份 |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| 无 | - | 本版本无前端代码变更 |

### 验证结果

- 后端测试: Tests run 23, Failures 0（mvn test）
- 前端构建: ✓ built（npm run build）
- docker compose config: 校验通过
- Actuator 冒烟: /actuator/health 免认证 UP；/actuator/prometheus 认证后返回指标

---

## vT3 — 前端测试基线
**日期**: 2026-08-14
**基线**: vT2
**主题**: Vitest + Vue Test Utils 前端测试

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| 无 | - | 本版本后端无代码变更 |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| vitest.config.js | 新增 | jsdom + @ 别名 |
| src/utils/stateLabel.test.js | 新增 3 个测试 | 状态文案翻译 |
| src/components/ProgressTracker.test.js | 新增 2 个测试 | 组件状态渲染 |
| package.json / package-lock.json | 新增 vitest/@vue/test-utils/jsdom | 测试依赖与 test script |
| .github/workflows/ci.yml | frontend job 增加 npm test | CI 测试门禁 |

### 验证结果

- 前端测试: Tests 5 passed（npm test）
- 前端构建: ✓ built（npm run build）

---

## vT2 — 服务层与集成测试
**日期**: 2026-08-14
**基线**: vT1
**主题**: 安全（JWT）、工具类、JPA Repository 测试基线

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| test/security/JwtUtilTest.java | 新增 3 个测试 | 签发/解析/非法与过期 token |
| test/security/JwtAuthFilterTest.java | 新增 3 个测试 | Bearer 认证、缺失/非法 token |
| test/dto/JsonHelperTest.java | 新增 3 个测试 | Map/List 解析与容错 |
| test/repository/TestCaseVersionRepositoryTest.java | 新增 1 个测试 | H2 JPA 保存/查询/删除 |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| 无 | - | 本版本无前端代码变更 |

### 验证结果

- 后端测试: Tests run 23, Failures 0（mvn test）

---

## vT1 — 测试与运维基线
**日期**: 2026-08-14
**基线**: v5.8 + v5 数据层复查修复
**主题**: 建立独立工程基线版本线（vT 系列），补齐测试与运维基线

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| test/runtime/MemoryRuntimeStoreTest.java | 新增 5 个测试 | 取消标志/会话/心跳/登录计数/并发配额 |
| test/queue/MemoryTaskQueueStoreTest.java | 新增 3 个测试 | 队列计数、多队列独立、幂等 |
| test/service/LoginAttemptServiceTest.java | 新增 3 个测试 | 5 次锁定、阈值内不锁、成功后清状态 |
| .github/workflows/ci.yml | 新增 compose job | `docker compose config --quiet` 配置校验 |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| 无 | - | 前端仅作 CI 构建回归 |

### 验证结果

- 后端测试: Tests run 13, Failures 0（mvn test）
- 前端构建: ✓ built（npm run build）
- docker compose config: 校验通过
- MCP 语法: node --check 通过

---

## 修复记录 — v5 数据层复查（v5.6~v5.8）
**日期**: 2026-08-14
**主题**: 重新生成误清语义上下文 / 取消标志残留 / Milvus 已有集合未建索引 / 迁移 dry-run 顺序

### 修复内容

| 文件 | 修复 | 说明 |
|---|---|---|
| service/SemanticService.java | 新增 clearCases，clearProject 仅用于项目删除 | 重新生成不再误删 contexts/failures |
| service/TestCaseService.java | 重新生成改用 clearCases；取消无任务时不再写残留标志；生成前清残留取消标志 | 修复取消标志导致下一次生成被误取消 |
| service/MilvusService.java | 已有集合也补建 ANN 索引并加载 | 修复存量集合仍走暴力扫描 |
| migration/H2ToMysqlMigrator.java | dry-run 提前到备份之前 | dry-run 只统计不产生备份文件 |

### 验证结果

- 后端编译: BUILD SUCCESS（140 源文件）
- 后端测试: Tests run 2, Failures 0（mvn test）
- 前端构建: ✓ built in 16.40s

---

## v5.8 — 数据治理与可观测
**日期**: 2026-08-14
**基线**: v5.7
**主题**: 执行数据保留策略 + 数据健康检查 API + 迁移 dry-run

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| service/DataRetentionService.java | 新增 | 定时清理过期终态执行/步骤/录屏/证据 |
| service/DataHealthService.java | 新增 | 表计数 + 孤儿数据 + Milvus 行数 |
| controller/DataHealthController.java | 新增 | GET /api/admin/data/health |
| security/SecurityConfig.java | /api/admin/** 仅 ADMIN | 数据健康接口权限 |
| config/AsyncConfig.java | @EnableScheduling | 定时任务开关 |
| repository/ExecutionRecordRepository.java | findByEndTimeBeforeAndStatusIn | 保留策略查询 |
| service/MilvusService.java | countCollection | 集合行数统计 |
| migration/H2ToMysqlMigrator.java | dry-run 模式 | 只统计不写库 |
| resources/application.yml | retention 配置 | execution-days / cron |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| 无 | - | 本版本无前端代码变更 |

### 验证结果

- 后端编译: BUILD SUCCESS（140 源文件）
- 后端测试: Tests run 2, Failures 0（mvn test）
- 前端构建: ✓ built in 15.83s
- 数据健康 API 冒烟: 表计数/孤儿/Milvus 结构正确；无 token 返回 401

---

## v5.7 — 数据索引与查询性能
**日期**: 2026-08-14
**基线**: v5.6
**主题**: Flyway 复合索引 + 执行历史分页 + 连接池调优 + Milvus ANN 索引

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| resources/db/migration/mysql/V2__add_performance_indexes.sql | 新增 | 6 组高频查询复合索引 |
| service/ExecutionService.java | 分页 + stats/trend | 执行历史全量统计一次返回 |
| controller/ExecutionController.java | page/pageSize 参数 | 分页接口 |
| resources/application-mysql.yml | Hikari 参数化 | 连接池调优 |
| service/MilvusService.java | createIndex(IVF_FLAT) + loadCollection | ANN 检索 |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| api/execution.js | getExecutions 支持 params | 分页参数 |
| views/ExecutionHistory.vue | 分页器 + 后端 stats/trend | 表格分页，统计保持全量口径 |

### 验证结果

- 后端编译: BUILD SUCCESS（137 源文件）
- 后端测试: Tests run 2, Failures 0（mvn test）
- 前端构建: ✓ built in 14.26s
- MySQL 冒烟: Flyway V2（add performance indexes）success=1
- 分页接口冒烟: `{items,total,page,pageSize,stats,trend}` 结构正确

---

## v5.6 — 数据一致性与生命周期
**日期**: 2026-08-14
**基线**: v5.5
**主题**: 事务落库、项目级联清理、用例增删改/导入/复制同步语义索引、上下文按模块替换

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| service/TestCasePersistenceService.java | 新增 | 重新生成用例事务化落库 |
| service/MilvusService.java | deleteByIds / deleteByModule | 向量按 ID/模块删除 |
| service/SemanticService.java | removeCases / reindexCase / replaceContext / clearProject 三集合 | 语义生命周期同步 |
| service/TestCaseService.java | 增删改/导入/复制同步索引；删除同步清版本 | 数据库与向量一致 |
| service/ProjectService.java | 删除项目级联清理执行/步骤/测试集/版本/向量 | 消除孤儿数据 |
| service/AnalysisService.java | 上下文 replace | 分析结果按模块替换 |
| repository/* | deleteByProjectId / findByExecutionIdIn | 级联删除支撑 |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| 无 | - | 本版本无前端代码变更 |

### 验证结果

- 后端编译: BUILD SUCCESS（137 源文件）
- 后端测试: Tests run 2, Failures 0（mvn test）
- 前端构建: ✓ built in 14.15s

---

## v5.5 — 正式切换 MySQL + Redis + Milvus
**日期**: 2026-08-14
**基线**: v5.4
**主题**: prod 默认全栈切换，H2 保留为开发 profile；全量回归 + 轻量压测

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| resources/application.yml | profile group prod/migrate → mysql；Redis 默认 host 127.0.0.1 | 修复 profile 专用文件 include 非法问题 |
| resources/application-prod.yml | 默认启用 Redis/Milvus | 正式切换全栈 |
| resources/application-migrate.yml | 移除非法 include | 迁移 profile 复用 group |
| controller/HealthController.java / dto/HealthDTO.java | 组件健康状态 | dataSource / redis / milvus |
| docker-compose.yml | 后端依赖 mysql/redis/milvus + 连接环境变量 | 全栈编排 |
| scripts/verify-v5-stack.ps1 | 新增 | 全量回归脚本 |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| 无 | - | 本版本无前端代码变更 |

### 验证结果

- 后端测试: Tests run 2, Failures 0（mvn test）
- 前端构建: ✓ built in 14.91s
- docker compose config 校验通过
- prod + MySQL + Redis 冒烟：`/api/health` dataSource=UP / redis=redis / version=5.5.0
- 登录防爆破实测：5 次失败后 Redis 出现 `rt:login:admin`，第 6 次返回 429
- 轻量压测：50 次健康请求全部成功，耗时 369ms

---

## v5.4 — Milvus 语义检索层
**日期**: 2026-08-14
**基线**: v5.3
**主题**: embedding 管道 + 语义去重 + RAG 上下文检索 + 语义搜索 + 失败经验库

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| mcp-server/index.js | 新增 llm_embedding 工具 | OpenAI 兼容 embeddings |
| service/EmbeddingService.java / MilvusService.java / SemanticService.java | 新增 | 向量化 + Milvus 集合 + 语义能力 |
| dto/PrdAnalysisResult.java | 新增 ragContexts | RAG 上下文透传 |
| agent/OrchestratorAgent.java / TestGeneratorAgent.java | RAG 注入 | 生成前 Top-K 上下文进 prompt |
| service/TestCaseService.java | 语义去重/索引 | 追加生成相似度判重 + 重建索引 |
| service/ProjectService.java / AnalysisService.java | 上下文入库 | PRD/后端/前端分析写入 contexts |
| service/ExecutionService.java | 失败经验库 | 失败步骤写入 failures |
| controller/TestCaseController.java | 语义搜索 API | GET /testcases/semantic-search |
| application.yml / docker-compose.yml | Milvus 配置 | etcd + minio + milvus standalone |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| api/testcase.js | 新增 semanticSearch | 语义搜索 API |
| views/TestCaseList.vue | 新增语义搜索对话框 | 自然语言检索并查看用例 |

### 验证结果

- 后端编译: BUILD SUCCESS（136 源文件）
- 后端测试: Tests run 2, Failures 0（mvn test）
- 前端构建: ✓ built in 15.32s
- MCP 语法: node --check 通过（llm + playwright）

---

## v5.3 — 缓存与任务队列
**日期**: 2026-08-14
**基线**: v5.2
**主题**: Spring Cache（设置/参数/分析结果）+ 生成/执行任务队列统计

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| config/CacheConfig.java | 新增 | Redis/内存 CacheManager 按开关选择 |
| queue/* | 新增 TaskQueueStore 三件套 | 内存/Redis 任务队列计数 |
| service/TaskQueueService.java | 新增 | 生成/执行队列封装与统计 |
| controller/TaskController.java | 新增 | GET /api/tasks/stats |
| service/SettingsService.java / ProjectService.java | 缓存注解 | 设置/参数读缓存、写失效 |
| service/AnalysisService.java / controller/AnalysisController.java | 分析缓存 | 分析/状态机缓存 + 重新分析 evict |
| controller/ProjectController.java / service/TestCaseService.java / ExecutionService.java | 队列接入 | 任务 enqueue/running/done |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| api/task.js | 新增 | 任务统计 API |
| views/Dashboard.vue | 新增任务队列区块 | 展示生成/执行排队与运行计数 |

### 验证结果

- 后端编译: BUILD SUCCESS（133 源文件）
- 后端测试: Tests run 2, Failures 0（mvn test）
- 前端构建: ✓ built in 14.12s

---

## v5.2 — Redis 运行态接入
**日期**: 2026-08-14
**基线**: v5.1
**主题**: 取消标志/心跳/并发配额/登录防爆破迁至 Redis，保留内存降级

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| runtime/* | 新增 RuntimeStore / Memory / Redis / Flag / Config | 运行态存储抽象，Redis 不可用时降级内存 |
| service/TestCaseService.java | 取消标志改 RuntimeFlag | 生成/追加取消支持多实例 |
| service/ExecutionService.java | 取消/会话/心跳改 RuntimeStore | 执行取消与 worker 心跳 Redis 化 |
| service/ProjectExecutionLimiter.java | 配额改 RuntimeStore | Redis Lua 计数信号量 |
| service/LoginAttemptService.java | 防爆破改 RuntimeStore | 登录锁定跨实例生效 |
| agent/TestGeneratorAgent.java / OrchestratorAgent.java | 取消参数改 CancellationSignal | 链路解耦 AtomicBoolean |
| application.yml | 新增 spring.data.redis | 连接配置 |
| docker-compose.yml | 新增 aicasetest-redis | Redis 7 容器 |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| 无 | - | 本版本无前端代码变更 |

### 验证结果

- 后端编译: BUILD SUCCESS（126 源文件）
- 后端测试: Tests run 2, Failures 0（mvn test）
- 前端构建: ✓ built in 13.70s

---

## v5.1 — H2 → MySQL 全量迁移工具
**日期**: 2026-08-14
**基线**: v5.0
**主题**: 全量数据迁移 + 备份/回滚 + 方言回归

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| migration/H2ToMysqlMigrator.java | 新增 | 通用 JDBC 迁移器：H2 备份 → 逐表复制 → 行数校验 → 失败清理 |
| resources/application-migrate.yml | 新增 | migrate profile，复用 mysql profile 作为目标库 |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| 无 | - | 本版本无前端代码变更 |

### 验证结果

- 后端编译: BUILD SUCCESS（120 源文件）
- 后端测试: Tests run 2, Failures 0（mvn test）
- 前端构建: ✓ built in 15.27s

---

## v5.0 — 数据层准备
**日期**: 2026-08-14
**基线**: v4.4
**主题**: Flyway schema 版本管理 + 双数据源 profile（dev=H2 / prod=MySQL）+ MySQL 容器基建

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| pom.xml | 新增 flyway-core + flyway-mysql + mysql-connector-j | MySQL 驱动与 schema 版本管理 |
| resources/application.yml | 新增 `spring.flyway.enabled=false` | H2 开发环境不启用 Flyway |
| resources/application-mysql.yml | 新增 | MySQL profile：Flyway 开启、JPA ddl-auto=none |
| resources/application-prod.yml | 改为 include mysql | 生产走 MySQL |
| resources/db/migration/mysql/V1__init_schema.sql | 新增 | 13 张业务表 MySQL 基线 schema + 索引 |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| 无 | - | 本版本无前端代码变更 |

### 验证结果

- 后端编译: BUILD SUCCESS（mvn compile，119 源文件）
- 前端构建: ✓ built in 27.58s

---

## v4.4 — 分析流式化

**日期**: 2026-08-13
**基线**: v4.3
**主题**: 代码分析改为 SSE 流式推送实时阶段进度

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| service/AnalysisService.java | 进度回调 + 流式分析 | 阶段：扫描结构/后端/前端/状态机/完成；runAnalysisStream 推送 progress/complete/error |
| controller/ProjectController.java | analyze-stream 端点 | GET /api/projects/{id}/analyze-stream（操作权限 + created/failed 守卫） |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| views/ProjectDetail.vue | 开始分析改用 EventSource | 实时显示阶段进度；完成/失败自动刷新；卸载关闭连接 |

### 验证结果

- 后端测试: Tests run 2, Failures 0, BUILD SUCCESS
- 前端构建: ✓ built in 14.21s

---

## v4.3 — 项目组与权限

**日期**: 2026-08-13
**基线**: v4.2
**主题**: 项目组共享 + 成员角色权限 + 复制执行隔离

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| entity/ProjectGroup + GroupMember + 仓库 | 项目组与成员 | VIEWER/OPERATOR 角色 |
| service/GroupService + GroupController + UserController | 组 CRUD + 成员管理 + 用户查询 | 仅组创建者可指派 |
| entity/Project + ProjectDTO | groupId + accessLevel | 项目归属组与当前用户访问级别 |
| service/ProjectAccessService | OWNER/OPERATOR/VIEWER 分级 | 读=view，写/执行=operate |
| service/ExecutionService + controller | 复制执行 | copy-execute 快照执行，不回写原用例状态 |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| views/Groups.vue + api/group.js + api/user.js | 项目组管理页 | 建组/编辑/删除/成员角色管理 |
| router + App.vue | /groups 路由 + 侧边栏入口 | - |
| views/ProjectCreate.vue | 创建项目可选所属组 | - |
| views/ProjectList.vue | 组徽标 + 只读标记 | - |
| views/TestCaseList.vue | 权限门控 + 复制执行 | VIEWER 只读；复制执行独立批次 |
| views/ProjectDetail.vue | 操作区权限门控 | VIEWER 隐藏写操作 + 只读提示 |

### 验证结果

- 后端测试: Tests run 2, Failures 0, BUILD SUCCESS
- 前端构建: ✓ built in 16.17s

---

## v4.2 — 多线程高并发治理

**日期**: 2026-08-13
**基线**: v4.1
**主题**: 线程池参数化 + 独立执行池 + 项目级并发配额 + 批量排队/取消 + 幂等

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| config/AsyncConfig.java + application.yml | 线程池参数化 + executionExecutor | 批量执行不再占用分析池；参数可环境变量调整 |
| service/ProjectExecutionLimiter.java | 项目级并发配额 | 默认每项目同时执行 ≤3，超出排队 |
| service/ExecutionService.java | 排队/取消/幂等 | 批量记录 pending→running；cancelBatch；运行中步骤检查点停止；重复触发拒绝 |
| repository/ExecutionRecordRepository.java | 状态查询 | findByTestCaseIdAndStatus |
| controller/ExecutionController.java | 批次取消端点 | POST /api/batches/{id}/cancel |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| views/BatchResult.vue | 排队/已取消统计 + 取消批次 | 排队中/已取消卡片；取消按钮与确认 |
| views/ExecutionResult.vue / ExecutionHistory.vue | cancelled 状态展示 | 已取消标签 |
| api/execution.js | cancelBatch | - |

### 验证结果

- 后端测试: Tests run 2, Failures 0, BUILD SUCCESS
- 前端构建: ✓ built in 11.94s

---

## v4.1 — 安全校验与权限

**日期**: 2026-08-12
**基线**: v4.0
**主题**: 登录防爆破 + 密码策略与改密 + CORS 收敛 + 审计完善

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| service/LoginAttemptService.java | 新增防爆破 | 5 次失败锁定 5 分钟 |
| dto/ChangePasswordRequest + AuthService/Controller | 修改密码 | 校验旧密码 + 新密码策略 |
| dto/RegisterRequest.java | 密码策略 | ≥8 位含字母和数字 |
| config/WebConfig + application.yml | CORS 白名单 | 默认 localhost:3000/5173/8080 |
| entity/ExecutionRecord + ExecutionService | 操作人 | 执行记录记录登录用户名 |
| service/TestCaseService | 评审人取登录态 | 不再信任前端传参；移除异步生成误报 401 的断言 |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| App.vue + api/auth.js | 修改密码弹窗 | 用户菜单入口 |
| views/Register.vue | 密码规则更新 | 8-64 位含字母数字 |
| views/TestCaseList.vue | 移除评审人输入 | 后端取登录态 |

### 验证结果

- 后端测试: Tests run 2, Failures 0, BUILD SUCCESS
- 前端构建: ✓ built in 12.12s

---

## v4.0 — 账号体系与登录

**日期**: 2026-08-12
**基线**: v3.18
**主题**: 用户注册/登录 + JWT 认证 + 数据归属隔离 + 存量迁移与默认管理员

> ⚠️ 破坏性变更：本版起所有业务接口需登录后携带 `Authorization: Bearer <token>` 访问。

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| entity/User + repository/UserRepository | 新增用户实体 | BCrypt 密码哈希 |
| security/JwtUtil/JwtAuthFilter/SecurityConfig/SecurityUtils | 新增 JWT 认证链 | 无状态；settings/stats 仅 ADMIN |
| service/AuthService + controller/AuthController | 注册/登录/当前用户 | /api/auth/register|login|me |
| service/ProjectAccessService | 项目级越权校验 | 接入全部项目级接口 |
| entity/Project + ProjectService | userId 归属 | 列表/创建/各操作按归属过滤 |
| config/DataInitializer | 初始化与迁移 | 默认管理员 + 存量项目归属迁移 |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| views/Login.vue / Register.vue | 新增登录/注册页 | 居中卡片 + 表单校验 |
| stores/auth.js + api/auth.js | 登录态管理 | localStorage 持久化 |
| api/request.js | Bearer 注入 + 401 跳登录 | - |
| router/index.js | 登录路由 + 守卫 | 未登录重定向 /login |
| App.vue | 用户菜单 + 角色导航 | 仪表盘/设置仅 ADMIN |

### 验证结果

- 后端测试: Tests run 2, Failures 0, BUILD SUCCESS
- 前端构建: ✓ built in 12.88s

---

## v3.18 — 前端体验打磨

**日期**: 2026-08-12
**基线**: v3.17
**主题**: 表格列设置/密度/筛选持久化 + 窄屏适配 + 骨架屏/过渡 + 空状态引导 + 步骤过滤 + 版本号动态化 + 深色主题 + 脑图导出 PNG

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| views/TestCaseList.vue | 显示设置 + 筛选持久化 + 空状态 + 骨架屏 | 列显隐/紧凑密度 localStorage；筛选条件记忆；空列表引导；加载骨架 |
| views/ExecutionResult.vue | 仅显示失败 + 失败高亮 | 步骤过滤与结果色 |
| App.vue | 深色主题 + 版本号动态化 + 窄屏折叠 + 路由过渡 | html.dark 切换；package.json 版本；≤1024px 自动折叠 |
| main.js / styles/index.scss | 深色主题支持 | Element Plus dark css-vars + 令牌覆盖 |
| views/MindMapPreview.vue | 导出 PNG | SVG → canvas 下载 |

### 验证结果

- 后端测试: Tests run 2, Failures 0, BUILD SUCCESS（无后端改动）
- 前端构建: ✓ built in 12.82s

---

## v3.17 — 平台化打磨

**日期**: 2026-08-12
**基线**: v3.16
**主题**: 全局仪表盘 + 系统级默认生成参数 + 内嵌 API 文档 + 用例抽屉 + 项目内导航/面包屑

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| controller/StatsController.java | 新增全局统计 | GET /api/stats/overview |
| service/SettingsService.java + controller | 默认生成参数 | 新建项目自动初始化 |
| pom.xml | springdoc 依赖 | 内嵌 Swagger UI |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| views/Dashboard.vue | 新增仪表盘 | 统计卡 + 类型分布饼图 + 项目覆盖率柱状图 |
| views/Settings.vue | 默认参数 + API 文档入口 | 系统级默认生成参数配置 |
| App.vue + router | 仪表盘导航 + 项目二级导航 + 面包屑 | 层级导航 |
| views/TestCaseList.vue | 用例详情抽屉 | el-dialog → el-drawer |

### 验证结果

- 后端测试: Tests run 2, Failures 0, BUILD SUCCESS
- 前端构建: ✓ built in 12.52s

---

## v3.16 — 数据与协作

**日期**: 2026-08-12
**基线**: v3.15
**主题**: XMind 模板与导入校验明细 + 执行时用例快照 + 评审审计 + 项目导出备份

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| entity/ExecutionRecord.java | 新增 testCaseSnapshot | 执行时用例快照 |
| service/ExecutionService.java | 保存快照 | 启动执行时写入 |
| service/XmindService.java | 模板文件 | 生成含示例用例的模板 |
| service/TestCaseService.java | 导入跳过明细 | skippedDetails（标题为空） |
| controller/TestCaseController.java | 模板端点 | GET /testcases/xmind-template |
| service/BackupService.java + controller | 项目导出备份 | ZIP 含 5 类数据 |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| views/TestCaseList.vue | 模板下载 + 跳过明细 + 评审人录入 | 导入结果可视化、审计留痕 |
| views/ProjectDetail.vue | 导出备份按钮 | 下载项目 ZIP |
| views/ExecutionResult.vue | 执行快照折叠区 | 回溯"当时跑的什么用例" |

### 验证结果

- 后端测试: Tests run 2, Failures 0, BUILD SUCCESS
- 前端构建: ✓ built in 12.13s

---

## v3.15 — 回归与统计

**日期**: 2026-08-12
**基线**: v3.14
**主题**: 测试集/回归集 + 多执行环境 + 通过率趋势

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| entity/TestSuite.java + repository | 新增测试集实体 | caseIds JSON + createdAt |
| service/TestSuiteService.java + controller | 测试集 CRUD + 一键执行 | 执行复用 executeBatch |
| service/ProjectService.java + controller | 多执行环境 | settings.executionEnvironments，激活环境 URL 同步 defaultTargetUrl |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| views/TestCaseList.vue | 测试集 + 执行环境 | 保存当前选中为测试集/管理/一键执行；环境增删与激活切换 |
| api/suite.js + api/project.js | 新增 API 封装 | 测试集 4 接口 + 环境 2 接口 |
| views/ExecutionHistory.vue | 通过率趋势图 | 最近 20 次滚动通过率折线图 |

### 验证结果

- 后端测试: Tests run 2, Failures 0, BUILD SUCCESS
- 前端构建: ✓ built in 12.30s

---

## v3.14 — 冷启动与工程基础

**日期**: 2026-08-12
**基线**: v3.13
**主题**: 内置示例 PRD + 最小单元测试 + CI 构建门禁

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| src/test/.../CsvExporterTest.java | 新增单测 | CSV 表头/内容/BOM/列表拼接 |
| src/test/.../XmindServiceTest.java | 新增单测 | XMind 生成→解析 round-trip |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| assets/samples/order-prd.md | 新增示例 PRD | 电商订单系统完整示例 |
| components/PrdPanel.vue | 新增"使用示例"按钮 | 一键载入示例 PRD |
| .github/workflows/ci.yml | 新增 CI | 后端 mvn test + 前端 npm build 门禁 |

### 验证结果

- 后端测试: Tests run 2, Failures 0, BUILD SUCCESS
- 前端构建: ✓ built in 11.92s

---

## v3.13 — 结果输出与用例资产

**日期**: 2026-08-12
**基线**: v3.12
**主题**: 报告在线预览 + 恢复 JSON/CSV 导入导出与跨项目复制 + 生成聚焦类型真正生效

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| controller/ExecutionController.java | 报告端点支持 inline/download | 默认 inline 预览，download=1 附件下载 |
| agent/TestGeneratorAgent.java | focusTypes 强制过滤 | generate/generateStreaming 均过滤；流式回调与落库一致 |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| views/ExecutionResult.vue | 报告预览/下载 | 新增"预览报告"，下载改为 download=1 |
| views/BatchResult.vue | 批次报告预览/下载 | 同上 |
| views/TestCaseList.vue | 恢复用例资产入口 | 导出 JSON/CSV、导入 JSON、跨项目复制；批量选择过滤模块行；聚焦类型提示更新 |

### 验证结果

- 后端编译: BUILD SUCCESS
- 前端构建: ✓ built in 11.85s

---

## v3.12 — 执行体验增强

**日期**: 2026-08-12
**基线**: v3.11
**主题**: 执行状态可视化与快捷操作 + 默认执行 URL + 生成前置预检 + 批次失败摘要

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| service/TestCaseService.java | listTestCases 新增 executionStatus 筛选 | 未执行/执行中/通过/失败 |
| controller/TestCaseController.java | 参数透传 | - |
| dto/GenerationParams.java | 新增 defaultTargetUrl | 项目默认执行 URL |
| service/ExecutionService.java | getBatchStatus 新增 executions 别名 | 修复批次列表为空的数据映射 bug |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| views/TestCaseList.vue | 执行状态列/筛选 + 快捷执行 | 状态胶囊列；重跑失败/执行已批准一键批量执行；默认 URL 带入执行对话框 |
| components/TestCaseCard.vue | 新增 defaultTargetUrl prop | 单条执行对话框默认带入项目 URL |
| views/ProjectDetail.vue | 生成前置预检 | created 且无 PRD 时禁用"生成用例"并提示 |
| views/BatchResult.vue | 修复列表映射 + 失败摘要 | 读取 executions/id/testCaseTitle；失败用例错误摘要折叠区 |

### 验证结果

- 后端编译: BUILD SUCCESS
- 前端构建: ✓ built in 12.26s

---

## v3.11 — 执行闭环补全

**日期**: 2026-08-12
**基线**: v3.10（含 12f0795 前端界面重构）
**主题**: 执行结果回写用例状态 + 执行历史页 + 覆盖率矩阵跳转修复，打通"生成→执行→结果→回看"闭环

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| service/ExecutionService.java | 执行状态回写 | 执行启动置 running、结束置 passed/failed；单条/批量、agent/程序化模式全覆盖 |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| views/ExecutionHistory.vue | 新增执行历史页 | 统计卡（总计/通过/失败/运行中/通过率）+ 记录表格 + 详情/报告入口 |
| router/index.js | 新增路由 | 注册 `/projects/:id/executions` 执行历史列表页 |
| views/ProjectDetail.vue | 新增"执行历史"按钮 | 查看操作卡入口 |
| views/TestCaseList.vue | 新增入口 + 覆盖率跳转修复 | 页头"执行历史"按钮；覆盖率关联用例改为内存按 ID 筛选 + 清除筛选横幅（替代失效的 keyword 跳转） |

### 验证结果

- 后端编译: BUILD SUCCESS
- 前端构建: ✓ built in 13.30s（ExecutionHistory 5.49 kB）

---

## v3.10 — 前端界面优化（视觉升级）

**日期**: 2026-08-12
**基线**: v3.9
**主题**: 接入全局设计系统 + 顶部导航/项目卡片/项目详情流程条/用例列表信息重组视觉升级

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| （无） | - | 纯前端迭代，后端无改动 |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| styles/index.scss | 接入并升级为全局设计系统 | main.js 引入；主题色/渐变背景/卡片/统计卡/表格统一视觉 |
| main.js | 引入全局样式 | 修复 styles/index.scss 此前未被引用（死代码）的问题 |
| App.vue | 顶部导航重设计 | 渐变头部+光斑、渐变 Logo 图标、图标导航胶囊 active |
| ProjectList.vue | 项目卡片重设计 | 状态色条 + 图标头像 + 信息层级 + 时间/删除区 |
| ProjectDetail.vue | 新增迭代流程步骤条 | el-steps 按项目状态高亮；操作按钮按主线/查看分组 |
| TestCaseList.vue | 工具栏分组 + 统计卡图标化 + 模块行美化 | 生成/批量/用例三组；图标渐变统计卡；模块文件夹图标+数量徽标 |
| index.html + public/favicon.svg | 新增自定义 favicon | 替换默认 vite 图标 |

### 验证结果

- 后端编译: BUILD SUCCESS（无代码改动）
- 前端构建: ✓ built in 10.48s（TestCaseList 31.86 kB）

---

## v3.9 — XMind 导入与脑图查看入口

**日期**: 2026-08-12
**基线**: v3.8
**主题**: 移除 JSON/CSV 导入导出按钮，改为 XMind 导入（逆向解析）+ 生成脑图后查看入口

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| service/XmindService.java | 新增 parseXmind | 逆向解析 XMind ZIP+JSON 树 → List<TestCase> |
| service/TestCaseService.java | 新增 importFromXmind | 从 XMind 导入用例（追加模式，重新编号） |
| controller/TestCaseController.java | 新增 POST 端点 | `POST /api/projects/{id}/testcases/import-xmind` |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| TestCaseList.vue | 移除按钮 | 导出JSON/导出CSV/导入JSON/复制到 + 对应函数 |
| TestCaseList.vue | 新增按钮 | 导入XMind + 查看脑图（生成后显示） |
| TestCaseList.vue | 修改 | handleGenerateMindmap 成功后设置 mindmapGenerated |
| api/testcase.js | 新增 importXmind | 调用 POST import-xmind API |

### 验证结果

- 后端编译: BUILD SUCCESS (89 source files)
- 前端构建: ✓ built in 15.78s (TestCaseList 30.67 kB)

---

## v3.8 — 树状用例列表

**日期**: 2026-08-11
**基线**: v3.7
**主题**: 将扁平用例表格改为树状结构——按模块分组 + 前置条件/步骤/预期结果直接显示在列中（不再隐藏在展开行）

### 改动清单与目的

#### 前端

| 文件 | 类型 | 说明 |
|------|------|------|
| `views/TestCaseList.vue` | 修改 | el-table 改为树状结构（`row-key` + `tree-props`），按模块分组，模块为父节点用例为子节点；新增 `treeData` computed 将 `displayTestCases` 按 module 字段分组 |
| `views/TestCaseList.vue` | 修改 | 移除 `type="expand"` 展开列，新增 3 列直接显示前置条件/步骤/预期结果摘要（第一项 + 计数） |
| `views/TestCaseList.vue` | 修改 | 移除分页组件 + `handlePageChange`/`handleSizeChange` 函数；`loadList` 改为 `pageSize: 9999` 加载全部用例 |
| `views/TestCaseList.vue` | 修改 | 新增 `rowClassName` 区分模块行（加粗+背景色）和用例行（可点击）；`handleRowClick` 跳过模块行 |
| `views/TestCaseList.vue` | 修改 | 移除 v3.6 展开行 CSS（`expand-content`/`expand-section`/`expand-label`/`expand-list`），新增树状样式（`module-row`/`case-row`/`detail-summary`） |

#### 后端

无改动（数据已包含 module/preconditions/steps/expectedResults 字段）

### 验证结果

- 前端构建: ✓ built in 10.98s (TestCaseList chunk 32.47 kB)
- 树状分组: 模块为父节点，用例为子节点，默认展开
- 详情可见: 前置条件/步骤/预期结果直接显示在列中（摘要 + 计数）
- 点击用例行打开详情对话框

### 说明

- **根因**: v3.6 的展开行（`type="expand"`）箭头不明显，用户不知道点击展开；扁平列表看不出模块分组
- **方案**: el-table `tree-props` 树状结构 + 详情列直接显示（不再需要展开）
- **向后兼容**: API 不变，筛选功能不变，TestCaseCard 详情对话框不变

---

## v3.7 — 真正的 LLM 流式输出

**日期**: 2026-08-11
**基线**: v3.6
**主题**: 将"伪流式"升级为"真流式"——MCP Server stream:true + JSON-RPC notification + Java 逐行解析 + 增量 JSON 解析器，首条用例出现时间从 40~120 秒降至 ~5 秒

### 改动清单与目的

#### MCP Server

| 文件 | 类型 | 说明 |
|------|------|------|
| `mcp-server/index.js` | 修改 | 新增 `StreamingServer extends Server` 子类（暴露 protected `notification()` 方法）；`llm_chat` 工具新增 `stream` 参数（默认 false 向后兼容），启用时 OpenAI `stream: true` 逐块通过 JSON-RPC notification 推送，累积完整文本返回；版本号升至 v1.2.0 |

#### 后端

| 文件 | 类型 | 说明 |
|------|------|------|
| `mcp/McpConnection.java` | 修改 | 新增 `callToolStreaming(toolName, args, chunkConsumer)` 方法——发送 JSON-RPC 请求后循环 `readLine()`，`notifications/llm_chunk` 通知 dispatch 到 `chunkConsumer`，匹配 id 的 response 返回完整结果；`synchronized` 保证 stdio 线程安全 |
| `mcp/McpClientManager.java` | 修改 | 新增 `callToolStreaming(serverName, toolName, args, chunkConsumer)` 路由方法 |
| `service/LlmService.java` | 修改 | 新增 `chatStreaming(systemPrompt, userPrompt, temperature, chunkConsumer)` 方法——调用 `callToolStreaming` 传入 `stream: true` 参数，`chunkConsumer` 接收逐块文本，返回完整响应；复用重试机制 |
| `agent/TestGeneratorAgent.java` | 修改 | 新增 `StreamingTestCaseParser` 内部类——积累文本 chunk，跟踪花括号深度检测完整用例对象后立即 `caseCb.onCase` 回调；`generateByLlmWithPrd` / `generateByLlmForStateMachine` 在 `caseCb != null` 时启用流式调用 + 增量解析，完整响应兜底推送未解析的用例 |

#### 前端

| 文件 | 类型 | 说明 |
|------|------|------|
| `views/TestCaseList.vue` | 修改 | 流式面板标题优化——0 条时提示"正在接收 LLM 流式响应..."，有数据后显示"已收到 N 条" |

### 验证结果

- 后端编译: BUILD SUCCESS (89 source files)
- 前端构建: ✓ built in 10.91s (TestCaseList chunk 32.40 kB)
- 向后兼容: `callTool`/`chat`/非流式 `llm_chat` 完全不变

### 说明

- **数据流改造**: MCP Server 逐 token 推送 notification → McpConnection 逐行读取 dispatch → LlmService chatStreaming 回调 → StreamingTestCaseParser 增量解析 → caseCb → SSE 推送
- **增量 JSON 解析**: `StreamingTestCaseParser` 跟踪 `inString`/`escaped`/`braceDepth` 状态机，检测花括号深度从 1→0 时提取完整对象 JSON 解析为 TestCase
- **兜底机制**: 流式结束后用完整响应重新 `parseTestCases(json, null)`，推送流式期间未推送的用例（通过 `parser.getParsedCount()` 对比）
- **向后兼容**: `caseCb == null`（非流式场景）走原 `chat()` 路径；MCP Server `stream` 参数默认 false

---

## v3.6 — 用例列表体验优化

**日期**: 2026-08-11
**基线**: v3.5
**主题**: 追加生成闪烁修复 + 列表展开行 + 手动添加用例

### 后端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| dto/CreateTestCaseRequest.java | 新增 | 创建用例请求 DTO |
| controller/TestCaseController.java | 新增 POST 端点 | `POST /api/projects/{projectId}/testcases` |
| service/TestCaseService.java | 新增 createTestCase 方法 | 手动创建用例，自动分配 TC 编号 |
| mcp/McpConnection.java | 修复编码 | InputStreamReader/OutputStreamWriter 指定 UTF-8 |

### 前端变更

| 文件 | 变更 | 说明 |
|---|---|---|
| TestCaseList.vue | 修复 displayTestCases | 追加模式合并已有用例+新用例，不闪烁 |
| TestCaseList.vue | 新增展开行 | el-table type=expand 显示前置条件/步骤/预期结果 |
| TestCaseList.vue | 新增按钮 | "新增用例"按钮 + 创建对话框 |
| TestCaseList.vue | 覆盖率面板优化 | 移到列表下方，可折叠 |
| TestCaseCard.vue | 新增 mode prop | 支持 mode='create' 创建模式 |
| TestCaseCard.vue | 修改 handleSave | 创建模式 emit('create') |
| TestCaseCard.vue | 修改 cancelEdit | 创建模式取消=关闭对话框 |
| api/testcase.js | 新增 createTestCase | 调用 POST 创建 API |

### 验证结果

- 后端编译: BUILD SUCCESS
- 前端构建: ✓ built in 14.25s
- 追加生成不闪烁: 追加模式 displayTestCases 合并已有+新用例
- 展开行: 显示前置条件、步骤、预期结果
- 手动添加: 新增按钮 → 空表单 → 保存 → 列表刷新

---

## v3.5 — 追加生成模式

**日期**: 2026-08-11
**基线**: v3.4
**迭代主题**: 新增"追加生成"模式——不删除现有用例，按类型追加新用例并跨去重，让用户敢于增量改进而无需担心丢失人工评审成果

### 改动清单与目的

#### 后端

| 文件 | 类型 | 说明 |
|------|------|------|
| `service/TestCaseService.java` | 修改 | 新增 `runGenerateStreamAppend(projectId, type, emitter)` 方法：不删除现有用例、type 非空时过滤、新用例 vs 现有用例跨去重、ID 从现有最大 +1 续号；新增私有 `isDuplicate(a, b)` 判重逻辑（与 TestGeneratorAgent 一致，复制以保持封装职责分离）；complete 事件携带 total/appended/dropped/existingBefore 字段；复用 cancellationFlags + GenerationCancelledException，取消时跳过落库 |
| `controller/ProjectController.java` | 修改 | 新增 `GET /{projectId}/testcases/generate-stream-append?type={type}` SSE 端点；复用 generating 状态机互斥校验；`cancelGenerate` 端点同时适用于追加生成（共用 cancellationFlags） |

#### 前端

| 文件 | 类型 | 说明 |
|------|------|------|
| `api/testcase.js` | 修改 | 新增 `streamGenerateAppend(projectId, type, callbacks)`：基于 EventSource，URL `generate-stream-append?type=`，complete 事件回调接收整个 data 对象（含 appended/dropped/existingBefore） |
| `views/TestCaseList.vue` | 修改 | 工具栏新增"追加生成"按钮（warning 类型，streaming 时 disabled）；新增类型选择 el-dialog（radio: 全部/正向/异常/边界/数据）+ form-tip 说明；新增 `currentGenMode` 状态（'regenerate'/'append'/null）+ `streamingAlertTitle` 计算属性差异化流式面板标题；新增 `handleOpenAppendDialog`/`handleConfirmAppend`/`startAppendStream` 函数；`handleRegenerate` 同步设置/重置 currentGenMode；导入 Plus 图标 + streamGenerateAppend |

### 验证
- 后端编译: `mvn compile` BUILD SUCCESS (88 source files)
- 前端构建: `npm run build` 成功（TestCaseList chunk 30.53 kB）

### 说明
- **追加 vs 重新生成**：追加保留现有用例 + 跨去重 + 续号；重新生成仍为全量覆盖（行为不变）
- **类型过滤策略**：LLM 仍生成全类型用例，落库阶段后置过滤（避免 LLM 不遵守类型约束丢失优质用例）；type 为空时全类型追加
- **跨去重**：新用例与现有同模块用例标题相似度 > 80% 判重（与生成阶段 isDuplicate 标准一致）
- **续号保存**：复用 `nextTestCaseNumber`，新用例 ID 从 TC-{现有最大+1} 开始
- **取消保护**：复用 v3.3 取消机制，追加生成取消时跳过落库，现有用例完整保留
- **状态机互斥**：追加生成与重新生成共用 generating 状态，并发触发推送 error 事件
- **向后兼容**：重新生成端点/前端逻辑完全不变；complete 事件新增字段，前端按存在性读取

---

## v3.4 — 生成参数可配置

**日期**: 2026-08-11
**基线**: v3.3
**迭代主题**: 将硬编码的用例生成参数（temperature 0.4、数量引导、测试类型）提取为项目级可配置项，支持按项目类型调整用例密度与多样性

### 改动清单与目的

#### 后端

| 文件 | 类型 | 说明 |
|------|------|------|
| `dto/GenerationParams.java` | 新建 | 项目级生成参数 DTO（caseDensity/temperature/focusTypes），含 `defaults()` 兜底默认值 |
| `service/ProjectService.java` | 修改 | 新增 `getGenerationParams`/`updateGenerationParams`/`parseGenerationParams`，从 Project.settings JSON 读写 generationParams 字段，解析失败降级默认值 |
| `controller/ProjectController.java` | 修改 | 新增 `GET/PUT /{projectId}/generation-params` 端点 |
| `agent/OrchestratorAgent.java` | 修改 | GenContext record 新增 `params` 字段；`loadGenerationContext` 解析 Project.settings 得到 GenerationParams；`generate`/`generateStreaming` 透传 params 给 TestGeneratorAgent |
| `agent/TestGeneratorAgent.java` | 修改 | 将 `SYSTEM_PROMPT`/`SYSTEM_PROMPT_PRD_DRIVEN` 拆为 HEADER + 动态数量引导段 + FOOTER；新增 `buildQuantityGuide`/`buildPrdQuantityGuide`/`buildSystemPrompt`/`buildPrdDrivenPrompt`/`resolveTemperature`；`generate`/`generateStreaming`/`generateCodeDrivenCases`/`generateByLlmForStateMachine`/`generateByLlmWithPrd` 新增 `params` 参数，LLM 调用改用动态 prompt + 参数化 temperature |

#### 前端

| 文件 | 类型 | 说明 |
|------|------|------|
| `api/project.js` | 修改 | 新增 `getGenerationParams(projectId)` + `updateGenerationParams(projectId, params)` |
| `views/TestCaseList.vue` | 修改 | 工具栏新增"生成参数"按钮 + el-dialog（caseDensity radio 三档 + temperature slider 0.2~0.6 + focusTypes checkbox 四选）+ `handleOpenGenParams`（拉取当前参数）+ `handleSaveGenParams`（保存）+ form-tip 样式 |

### 验证
- 后端编译: `mvn compile` BUILD SUCCESS
- 前端构建: `npm run build` 成功（TestCaseList chunk 27.50 kB）

### 说明
- **存储复用**：参数存入 `Project.settings` JSON 的 `generationParams` 字段，无需新建表
- **向后兼容**：settings 为空 `{}` 时使用默认值（medium/0.4/[]），行为与 v3.3 完全一致；medium 档 system prompt 文本与 v3.3 完全一致
- **density 映射**：low（正向≥1/异常≥1/边界≥1/数据可选）/ medium（当前行为）/ high（正向≥2/异常≥2/边界≥3/数据≥2）
- **temperature 范围**：0.2~0.6，越低越稳定一致，越高越多样发散；越界降级为 0.4
- **focusTypes**：当前版本仅作为生成提示写入 prompt 上下文，不强制过滤解析结果（避免丢弃高质量用例）
- 参数保存后下次"重新生成"时生效（OrchestratorAgent 从 Project.settings 读取）

---

## v3.3 — 流式生成取消与落库保护

**日期**: 2026-08-11
**基线**: v3.2
**迭代主题**: 为流式用例生成增加取消能力 + 落库保护，修补 v3.2 遗留的"生成不可中断 + 先删后存覆盖旧用例"数据安全风险

### 改动清单与目的

#### 后端

| 文件 | 类型 | 说明 |
|------|------|------|
| `common/GenerationCancelledException.java` | 新建 | 取消异常类型，生成线程在检查点抛出，`runGenerateStream` catch 后跳过落库 |
| `service/TestCaseService.java` | 修改 | 新增 `cancellationFlags` 注册表（ConcurrentHashMap）+ `cancelGeneration(projectId)` + `restoreProjectStatus`；`runGenerateStream` 新增 `cancelled` 标志，客户端断开/cancel 端点触发取消，catch `GenerationCancelledException` 跳过 deleteAll+save（落库保护），finally 清理注册表 |
| `agent/OrchestratorAgent.java` | 修改 | `generateStreaming` 新增 `cancelled` 参数，透传给 TestGeneratorAgent |
| `agent/TestGeneratorAgent.java` | 修改 | 新增 `checkCancelled` helper；`generateStreaming`/`generateCodeDrivenCases`/`generateByLlmWithPrd`/`generateByLlmForStateMachine` 新增 `cancelled` 参数，在 LLM 调用前/状态机循环迭代前检查取消标志；`GenerationCancelledException` 向上传播不触发 fallback |
| `controller/ProjectController.java` | 修改 | 新增 `POST /{projectId}/testcases/generate-cancel` 取消端点 |

#### 前端

| 文件 | 类型 | 说明 |
|------|------|------|
| `api/testcase.js` | 修改 | 新增 `cancelGenerate(projectId)`；`streamGenerate` 新增 `cancelled` 事件监听 + `onCancelled` 回调，区分"取消"（warning）与"失败"（error） |
| `views/TestCaseList.vue` | 修改 | 新增 `cancelling` 状态 + 取消生成按钮（el-button danger，流式 alert 内）+ `handleCancelGenerate`（二次确认 + 调 cancelGenerate）+ `onCancelled` 回调（warning 提示 + 刷新列表显示旧用例）+ streaming-alert-body 样式 |

### 验证
- 后端编译: `mvn compile` BUILD SUCCESS (87 source files)
- 前端构建: `npm run build` 成功（TestCaseList chunk 24.98 kB）

### 说明
- **落库保护**：取消时跳过 `deleteAll` + `save`，旧用例（含人工修改）完整保留
- **客户端断开 → 取消**：关闭页面/路由切换/网络断开时，后端自动取消生成并保留旧用例（v3.2 是继续跑完并覆盖）
- **检查点**：PRD 驱动 LLM 调用前、代码驱动状态机循环每次迭代前、分模块 LLM 调用前、落库前
- **向后兼容**：非流式 `runGenerate` 不受影响（无 cancelled 参数）；原有 SSE 事件不变，仅新增 cancelled 事件
- LLM 同步调用本身无法中途 abort，在调用前检查取消标志；LLM 返回后若已取消则丢弃结果不落库

---

## v3.2 — 用例生成流式输出（SSE Stream）

**日期**: 2026-08-11
**基线**: v3.1
**迭代主题**: 将用例生成从"异步轮询 + 终态一次性返回"升级为"SSE 流式推送"，用户每生成一条用例即可实时看到，无需等待全部完成

### 改动清单与目的

#### 后端

| 文件 | 类型 | 说明 |
|------|------|------|
| `agent/TestGeneratorAgent.java` | 修改 | 新增 `CaseCallback` 接口 + `generateStreaming` 重载；`parseTestCases`/`generateByLlmWithPrd`/`generateByLlmForStateMachine`/`generateByRulesForStateMachine`/`generateByEndpoints`/`generateCodeDrivenCases` 增加 caseCb 参数，每条用例解析/构建后立即回调（去重前），用于 SSE 推送 |
| `agent/OrchestratorAgent.java` | 修改 | 抽取 `loadGenerationContext` helper（PRD 解析 + 代码/前端结果加载），`generate` 与新增 `generateStreaming` 共用，避免重复 |
| `service/TestCaseService.java` | 修改 | 新增 `runGenerateStream(projectId, emitter)`（`@Async`）：通过 SseEmitter 推送 progress/case/complete/error 事件，结束时落库；新增 `sendSseEvent`/`safeSseComplete` 私有方法处理客户端断开/超时 |
| `controller/ProjectController.java` | 修改 | 新增 `GET /{projectId}/testcases/generate-stream` SSE 端点（5 分钟超时），generating 状态立即推送 error 事件并关闭 |

#### 前端

| 文件 | 类型 | 说明 |
|------|------|------|
| `api/testcase.js` | 修改 | 新增 `streamGenerate(projectId, callbacks)`，基于浏览器原生 EventSource 消费 SSE，返回事件源供调用方 close() |
| `views/TestCaseList.vue` | 修改 | 新增流式状态（streaming/streamProgress/streamedCases）+ 流式生成面板（绿色 alert 显示进度与计数）+ 表格流式数据源切换 + 编号列显示"生成中" + 流式期间隐藏分页 + onMounted 自动触发（?generate=1）+ onUnmounted 释放 EventSource；`handleRegenerate` 改用 streamGenerate |
| `views/ProjectDetail.vue` | 修改 | "生成用例"改为 `router.push('/projects/:id/testcases?generate=1')` 跳转自动触发流式生成；移除 triggerGenerate import 与轮询逻辑 |

### 验证
- 后端编译: `mvn compile` BUILD SUCCESS (86 source files)
- 前端构建: `npm run build` 成功（TestCaseList chunk 24.18 kB）

### 说明
- 保留旧端点 `POST /api/projects/{id}/generate` 与 `TestCaseService.runGenerate`，作为非流式回退路径，完全向后兼容
- 流式生成与旧端点共用 `generating` 状态机，互斥（任一在 generating 时另一端点拒绝）
- caseCb 在去重前触发，流式推送的用例编号为临时序号，落库后重新编号；前端 complete 事件后刷新列表拿最终编号
- 客户端断开/超时由 `onCompletion`/`onTimeout`/`onError` 置 `clientGone` 标志，后续 send 静默跳过，生成继续完成落库
- LLM 一次返回全部 JSON，Java 侧解析后逐条回调模拟流式体验（首条延迟 ≈ LLM 总耗时）

---

## v3.1 — 目录选择器与界面优化

**日期**: 2026-08-10
**基线**: v3.0
**迭代主题**: 将本地路径从手动输入升级为可视化目录选择器，并优化创建项目表单界面

### 改动清单与目的

#### 后端

| 文件 | 类型 | 说明 |
|------|------|------|
| `dto/DirItem.java` | 新建 | 目录项 DTO（name/path/leaf），用于目录树懒加载节点数据传输 |
| `controller/FilesystemController.java` | 新建 | 提供 `GET /api/filesystem/dirs` 接口：path 为空返回系统根盘符，非空返回子目录列表（过滤文件、仅返回可读目录） |

#### 前端

| 文件 | 类型 | 说明 |
|------|------|------|
| `api/filesystem.js` | 新建 | 封装 `getDirs(path)` 目录列表 API |
| `components/DirSelector.vue` | 新建 | 目录选择器组件：el-popover + el-tree 懒加载，支持返回上级、节点点击选中、确定回调 |
| `views/ProjectCreate.vue` | 修改 | 来源类型从 el-select 改为 el-radio-button（更直观）；本地路径输入框集成 DirSelector（el-input append 插槽）；Git 地址加格式校验 + https:// 前缀；无代码模式加 el-alert 说明；新增重置按钮；表单卡片加 header 提示文案；样式优化 |

### 验证
- 后端编译: `mvn compile` BUILD SUCCESS
- 前端构建: `npm run build` 成功（ProjectCreate chunk 6.05 kB）

### 说明
- 目录选择器仅用于"本地路径"来源类型，Git 地址和无代码模式不展示
- 后端目录列表 API 过滤掉文件和不可读目录，仅返回目录
- DirSelector 通过 el-tree 懒加载按需请求子目录，避免一次性加载大量节点
- 切换来源类型时自动清空路径并清除校验状态

---

## v3.0 — PRD 驱动流程改造

**日期**: 2026-08-10
**基线**: v2.9
**迭代主题**: 将产品流程从"代码驱动"改为"PRD 驱动"——PRD 为用例生成主线（必须），代码路径降级为可选上下文

### 改动清单与目的

#### 后端

| 文件 | 类型 | 说明 |
|------|------|------|
| `dto/CreateProjectRequest.java` | 修改 | 移除 `sourcePath` 的 `@NotBlank`，改为可选（纯 PRD 驱动项目可不填代码路径） |
| `service/ProjectService.java` | 修改 | `createProject` 在 `sourcePath` 为空时跳过路径存在校验 |
| `service/TestCaseService.java` | 修改 | `runGenerate` 前置校验——PRD 和代码分析结果都为空时抛 `IllegalStateException` 阻止生成 |

#### 前端

| 文件 | 类型 | 说明 |
|------|------|------|
| `views/ProjectCreate.vue` | 修改 | 来源类型新增"无代码（纯 PRD）"选项；项目路径条件显示（选"无代码"时隐藏）；`sourcePath` 校验改为动态（仅非"无代码"时必填）；watch sourceType 切换时清空路径 |
| `views/ProjectDetail.vue` | 修改 | PRD 面板上提到操作区上方（作为主线）；"生成用例"按钮提至首位；"开始分析"按钮在无代码路径时禁用并显示"（可选）"标注；`canGenerate` 放宽为非 analyzing/generating 即可（支持 created 状态纯 PRD 生成）；新增 `hasSourcePath` computed |

### 验证
- 后端编译: `mvn compile` BUILD SUCCESS (84 source files)
- 前端构建: `npm run build` 成功（ProjectDetail chunk 9.69 kB）

### 说明
- 后端生成链路（OrchestratorAgent/TestGeneratorAgent）v1.10 已支持纯 PRD 驱动，本迭代仅放开创建校验和生成前置校验
- 历史项目（有 sourcePath）行为完全不变，向后兼容
- "PRD 必填"体现在生成用例环节的校验，创建项目时不强制填 PRD（降低创建门槛，PRD 在详情页 PrdPanel 编辑）

---

## v2.9 — Selenium 清理 + 录屏回放升级为视频播放

**日期**: 2026-08-09
**基线**: v2.8
**迭代主题**: 清理 Selenium 死代码与依赖；前端录屏回放从图片轮播升级为 WebM 视频播放

### 改动清单与目的

#### 后端

| 文件 | 类型 | 说明 |
|------|------|------|
| `skill/BrowserSkill.java` | 删除 | Selenium 浏览器操作 Skill（v2.8 已被 PlaywrightRecordSkill 完全替代，成为死代码） |
| `pom.xml` | 修改 | 移除 `selenium-java` + `webdrivermanager` 两个依赖块 |
| `skill/PlaywrightRecordSkill.java` | 修改 | 类注释更新（去掉"过渡期共存"说明，标注为唯一浏览器操作实现） |

#### 前端

| 文件 | 类型 | 说明 |
|------|------|------|
| `api/execution.js` | 修改 | 新增 `getExecutionVideoUrl(eid)` 辅助函数（返回视频下载 API URL） |
| `views/ExecutionResult.vue` | 修改 | 录屏回放升级为双模式：优先 `<video>` 播放 WebM（v2.8+ 新记录），回退图片轮播（兼容 v2.4~v2.5 历史记录）；新增 `recordingVideoUrl`/`hasRecording` computed + `downloadVideo` 方法 + 视频播放器样式 |

### 验证
- 后端编译: `mvn compile` BUILD SUCCESS (84 source files，较 v2.8 减少 1)
- 前端构建: `npm run build` 成功（ExecutionResult chunk 7.41 kB）
- Grep 确认无 selenium 残留引用（仅注释保留历史说明）

### 说明
- `ExecutionRecord.recordingFrames` 字段保留，兼容历史记录的图片轮播回放
- 视频播放器使用原生 `<video controls autoplay>`，支持播放/暂停/进度条/全屏 + 下载按钮

---

## v2.8 — 执行链路切换（Selenium → Playwright）

**日期**: 2026-08-09
**基线**: v2.7
**迭代主题**: 执行引擎从 Selenium BrowserSkill 切换到 Playwright PlaywrightRecordSkill，录屏从图片序列升级为 WebM 视频

### 改动清单与目的

#### 后端

| 文件 | 类型 | 说明 |
|------|------|------|
| `entity/ExecutionRecord.java` | 修改 | 新增 `recordingVideoPath` 字段，存储 WebM 视频路径（替代 recordingFrames 图片序列） |
| `controller/ExecutionController.java` | 修改 | 新增 `GET /executions/{executionId}/video` 视频下载 API（返回 video/webm 流） |
| `service/ExecutionService.java` | 修改 | 依赖从 `BrowserSkill` 替换为 `PlaywrightRecordSkill`；录屏逻辑改为 `stopRecording(videoPath)` 保存视频；移除周期截图 startRecording 调用（Playwright 在 launch 时自动开始录屏） |
| `agent/ExecutionAgent.java` | 修改 | 依赖从 `BrowserSkill` 替换为 `PlaywrightRecordSkill`；所有 `browserSkill.xxx()` 调用替换为 `playwrightSkill.xxx()`（takeScreenshot/visualClick/domClick/getPageStatus/takeScreenshotWithMarker） |

### 验证
- 后端编译: `mvn compile` BUILD SUCCESS (85 source files)
- 前端: 无改动（前端 API 调用不变，录屏回放仍用旧图片轮播，v2.9 升级为视频播放）

### 说明
- BrowserSkill 与 PlaywrightRecordSkill 过渡期共存，v2.9 将清理 Selenium 依赖
- 截图标注逻辑（红圈+十字准星+坐标文本）在 PlaywrightRecordSkill 中复用 Graphics2D 实现，与 BrowserSkill 行为一致

---

## v2.7 — Playwright MCP Server + PlaywrightRecordSkill

**日期**: 2026-08-09
**基线**: v2.6
**迭代主题**: 自建 Playwright MCP Server（9个工具）+ PlaywrightRecordSkill 封装 + 真正视频录屏

### 改动清单与目的

#### 后端

| 文件 | 类型 | 说明 |
|------|------|------|
| `playwright-mcp-server/package.json` | 新建 | MCP Server 依赖配置（@modelcontextprotocol/sdk + playwright） |
| `playwright-mcp-server/index.js` | 新建 | Playwright MCP Server（9个工具：launch/navigate/screenshot/visualClick/domClick/pageStatus/videoGetPath/videoSave/close） |
| `skill/PlaywrightRecordSkill.java` | 新建 | Java 端封装，方法签名与 BrowserSkill 对齐，截图标注复用 Graphics2D |
| `mcp/McpClientManager.java` | 修改 | 注册 "playwright" Server |
| `resources/application.yml` | 修改 | 添加 playwright Server 配置 |

### 验证
- npm install: 成功（96 packages）
- 后端编译: `mvn compile` BUILD SUCCESS (85 source files)
- 前端: 无改动，跳过构建验证

---

## v2.6 — MCP Client 多 Server 架构

**日期**: 2026-08-09
**基线**: v2.5
**迭代主题**: McpClient 重构为 McpClientManager + McpConnection，支持多 MCP Server 并行管理

### 改动清单与目的

#### 后端

| 文件 | 类型 | 说明 |
|------|------|------|
| `mcp/McpConnection.java` | 新建 | 单 Server 连接封装（进程管理 + JSON-RPC 通信 + synchronized 防并发） |
| `mcp/McpClientManager.java` | 新建 | 多 Server 管理器（Map<String, McpConnection>，替代原 McpClient） |
| `mcp/McpClient.java` | 删除 | 逻辑已拆分到 McpConnection + McpClientManager |
| `service/LlmService.java` | 修改 | McpClient → McpClientManager，callTool 加 serverName 参数 |
| `service/McpBridgeService.java` | 修改 | McpClient → McpClientManager，callTool 加 serverName 参数 |
| `resources/application.yml` | 修改 | `mcp.server.*` → `mcp.servers.llm.*` 多 Server 配置格式 |

### 验证
- 后端编译: `mvn compile` BUILD SUCCESS (84 source files)
- 前端: 无改动，跳过构建验证

---

## v2.5 — 截图标注 + 录屏回放增强

**日期**: 2026-08-09
**基线**: v2.4
**迭代主题**: 截图标注点击位置（红圈+十字准星）+ 录屏帧合并步骤截图

### 改动清单与目的

#### 后端

| 文件 | 类型 | 说明 |
|------|------|------|
| `skill/BrowserSkill.java` | 修改 | 新增 takeScreenshotWithMarker + annotateScreenshot（Graphics2D 绘制红圈+十字+坐标文本） |
| `service/ExecutionService.java` | 修改 | 步骤截图改用带标注版本 + 录屏帧合并步骤截图 |
| `agent/ExecutionAgent.java` | 修改 | Agent 模式截图改用 takeScreenshotWithMarker + clickX/clickY 传递 |

### 标注效果
```
┌──────────────────────────┐
│                          │
│        ⊕ ← 红圈+十字     │
│       登录按钮            │
│  click: (260, 340)       │
│                          │
└──────────────────────────┘
```

### 验证
- 后端编译: `mvn compile` BUILD SUCCESS (83 source files)
- 前端构建: `npm run build` 成功 (11.61s，无改动)

---

## v2.4 — 执行报告 + 录屏

**日期**: 2026-08-09
**基线**: v2.3
**迭代主题**: HTML 执行报告生成 + 浏览器录屏（周期截图+前端播放）

### 改动清单与目的

#### 后端

| 文件 | 类型 | 说明 |
|------|------|------|
| `entity/ExecutionRecord.java` | 修改 | 新增 recordingFrames 字段（JSON 数组，存录屏帧路径） |
| `skill/BrowserSkill.java` | 修改 | 新增 startRecording/stopRecording（每2s截图，最多60帧） |
| `service/ExecutionService.java` | 修改 | 执行流程集成录屏（start→执行→stop→存帧） |
| `service/ReportService.java` | 新建 | HTML 报告生成（单条+批次，内嵌base64截图） |
| `controller/ExecutionController.java` | 修改 | 新增 2 个报告下载 API |
| `repository/ExecutionRecordRepository.java` | 修改 | 新增 findByBatchId 方法 |

#### 前端

| 文件 | 类型 | 说明 |
|------|------|------|
| `views/ExecutionResult.vue` | 修改 | 新增"下载报告"按钮 + 录屏播放器（播放/暂停/进度条） |
| `views/BatchResult.vue` | 修改 | 新增"下载批次报告"按钮 |

### 验证
- 后端编译: `mvn compile` BUILD SUCCESS (9s)
- 前端构建: `npm run build` 成功 (11.71s)

---

## v2.3 — LLM 调用全量拆分到 MCP Server

**日期**: 2026-08-09
**基线**: v2.2
**迭代主题**: 后端所有 LLM 调用从 OkHttp 直调改为通过 MCP 协议调用

### 改动清单与目的

#### MCP Server

| 文件 | 改动 | 目的 |
|------|------|------|
| `mcp-server/index.js` | 修改 | 新增 llm_chat + llm_chat_with_image 工具（共 3 个工具） |

#### 后端

| 文件 | 改动 | 目的 |
|------|------|------|
| `service/LlmService.java` | 重构 | 从 OkHttp 直调 OpenAI API 改为通过 McpClient 调用 MCP Server |

### 架构变更
```
v2.2: LlmService → OkHttp → OpenAI API（直调）
      McpBridgeService → McpClient → MCP Server → OpenAI Vision API

v2.3: LlmService → McpClient → MCP Server → OpenAI API（全部走 MCP）
      McpBridgeService → McpClient → MCP Server → OpenAI API
```

### 关键设计
- LlmService 所有 public 方法签名不变（chat/chatJson/chatWithImage/isConfigured/testConnection）
- 6 个调用方（PrdAgent/TestGeneratorAgent/StateMachineAgent/VueAnalyzer/ExecutionAgent/SettingsService）零改动
- 重试逻辑保留在 Java 侧
- MCP Server 暴露 3 个工具：llm_chat、llm_chat_with_image、multimodal_element_locate

### 验证
- 后端编译: `mvn compile` BUILD SUCCESS (82 source files, 10.5s)
- 前端: 无改动

---

## v2.2 — 独立 MCP Server

**日期**: 2026-08-09
**基线**: v2.1
**迭代主题**: 拆分独立 MCP Server，后端改为 MCP 客户端接入

### 改动清单与目的

#### MCP Server（Node.js，新建）

| 文件 | 类型 | 说明 |
|------|------|------|
| `mcp-server/package.json` | 新建 | MCP Server 依赖配置（@modelcontextprotocol/sdk + openai） |
| `mcp-server/index.js` | 新建 | MCP Server 实现（stdio 传输，暴露 multimodal_element_locate 工具） |

#### 后端

| 文件 | 类型 | 说明 |
|------|------|------|
| `mcp/McpClient.java` | 新建 | Java MCP 客户端（ProcessBuilder + JSON-RPC 2.0 over stdio） |
| `service/McpBridgeService.java` | 修改 | 从直调 LlmService 改为通过 McpClient 调用 MCP Server |
| `application.yml` | 修改 | 新增 mcp.server 配置项 |

### 架构变更
```
v2.1: McpBridgeService → LlmService.chatWithImage() → OpenAI API
v2.2: McpBridgeService → McpClient → MCP Server (Node.js) → OpenAI API
```

### 验证
- MCP Server npm install: 117 packages
- 后端编译: `mvn compile` BUILD SUCCESS (82 source files, 10s)
- 前端: 无改动

---

## v2.1 — MCP 多模态桥接 + Agent 执行引擎

**日期**: 2026-08-09
**基线**: v2.0
**迭代主题**: 多模态视觉识别 + LLM 驱动的 Agent 执行引擎 + 批量执行

### 改动清单与目的

#### 后端

| 文件 | 改动 | 目的 |
|------|------|------|
| `service/LlmService.java` | 新增 `chatWithImage()` 多模态方法 | 发送图片+文本到 OpenAI Vision API |
| `dto/LocateResult.java` | 新建 | MCP 返回结构（found/bbox/clickX/clickY/confidence） |
| `service/McpBridgeService.java` | 新建 | MCP 多模态视觉识别服务（截图+描述→位置JSON） |
| `agent/ExecutionAgent.java` | 新建 | LLM 驱动的 Agent 执行引擎（agentic loop + 两层兜底） |
| `entity/ExecutionRecord.java` | 修改 | 新增 batchId + mode 字段 |
| `repository/ExecutionRecordRepository.java` | 修改 | 新增 findByBatchId 查询 |
| `service/ExecutionService.java` | 修改 | 新增 executeWithAgent + executeBatch + getBatchStatus |
| `controller/ExecutionController.java` | 修改 | 新增 Agent 模式参数 + 批量执行 + 批次查询 API |

#### 前端

| 文件 | 改动 | 目的 |
|------|------|------|
| `api/execution.js` | 修改 | 新增 executeBatch + getBatch + executeTestCase 支持 mode |
| `components/TestCaseCard.vue` | 修改 | 执行对话框新增"执行模式"选择（Agent/程序化） |
| `views/TestCaseList.vue` | 修改 | 新增"批量执行"按钮+URL对话框 |
| `views/BatchResult.vue` | 新建 | 批次结果页（进度条+用例列表+轮询） |
| `router/index.js` | 修改 | 新增 BatchResult 路由 |

### 验证
- 后端编译：`mvn compile` BUILD SUCCESS（81 source files, 9.2s）
- 前端构建：`npm run build` 成功（11.84s）

### 核心架构
```
Agent 执行单步:
  1. LLM 生成元素查找描述
  2. Skill take_screenshot
  3. MCP multimodal_element_locate（多模态视觉识别）
  4. LLM 决策策略（visual_click / dom_click / skip）
  5. 执行点击
  6. LLM 判断是否生效 → 不生效则 LLM 决策兜底
  7. 截图 + 组装证据
```

---

## v2.0 — Skill 工具层 + 执行数据模型

**日期**: 2026-08-09
**基线**: v1.12
**迭代主题**: AI 用例执行引擎基础设施 — Selenium WebDriver 集成、7 个 Skill 工具、执行数据模型、API、前端触发

### 改动清单与目的

#### 后端

| 文件 | 改动 | 目的 |
|------|------|------|
| `pom.xml` | 新增 selenium-java 4.21.0 + webdrivermanager 5.8.0 | Selenium WebDriver 依赖 |
| `skill/BrowserSkill.java` | 新建 | 7 个浏览器操作 Skill：browserLaunch/navigate/takeScreenshot/visualClick/domClick/getPageStatus/closeSession |
| `skill/EvidenceSkill.java` | 新建 | 证据存储 Skill：saveTestEvidence → Markdown 文档 |
| `entity/ExecutionRecord.java` | 新建 | 执行记录实体（id/projectId/testCaseId/status/startTime/endTime/summary） |
| `entity/ExecutionStep.java` | 新建 | 执行步骤实体（stepIndex/action/strategy/result/screenshotBefore/After/error） |
| `repository/ExecutionRecordRepository.java` | 新建 | 执行记录 Repository |
| `repository/ExecutionStepRepository.java` | 新建 | 执行步骤 Repository |
| `service/ExecutionService.java` | 新建 | 程序化执行服务：逐步骤调用 Skill 工具，记录结果，生成证据 |
| `controller/ExecutionController.java` | 新建 | 4 个执行 API：触发执行/查询结果/执行历史/步骤详情 |

#### 前端

| 文件 | 改动 | 目的 |
|------|------|------|
| `api/execution.js` | 新建 | 4 个执行 API 封装 |
| `views/ExecutionResult.vue` | 新建 | 执行结果展示页（概览+步骤详情+轮询） |
| `components/TestCaseCard.vue` | 修改 | 新增"执行"按钮+URL输入对话框 |
| `router/index.js` | 修改 | 新增 ExecutionResult 路由 |

### 验证
- 后端编译：`mvn compile` BUILD SUCCESS（78 source files, 9.4s）
- 前端构建：`npm run build` 成功

### v2.x 路线
| 版本 | 主题 | 状态 |
|------|------|------|
| v2.0 | Skill 工具层 + 执行数据模型 | ✅ 完成 |
| v2.1 | MCP 多模态桥接 + Agent 执行引擎 | 规划中 |
| v2.2 | 执行报告 + 录屏 | 规划中 |

---

## v1.12 — VueAnalyzer LLM 增强

**日期**: 2026-08-09
**基线**: v1.11
**迭代主题**: 正则先提取 + LLM 补充，提升前端分析覆盖率

### 改动清单与目的

#### 后端

| 文件 | 改动 | 目的 |
|------|------|------|
| `service/LlmService.java` | 新增 `isConfigured()` 方法 | 供 VueAnalyzer 判断是否可调用 LLM |
| `analyzer/VueAnalyzer.java` | 注入 LlmService + 新增 enhanceWithLlm/collectSourceSnippets/parseAndMergeSupplements/parseFields/parseSelectors 5 个方法 | 正则提取后用 LLM 补充遗漏内容（非 Element Plus 组件、Composition API 状态、动态路由等） |

### 验证
- 后端编译：`mvn compile` BUILD SUCCESS（2.2s）

### 向后兼容
- LLM 未配置 API Key 时跳过增强步骤，行为同 v1.11
- LLM 调用失败时降级为纯正则结果
- FrontendResult 数据模型不变，前端无改动

### 范围说明
- In Scope：VueAnalyzer LLM 补充逻辑（正则结果 + 源码摘要 → LLM → 合并去重）
- Out of Scope：前端 UI 变更、非 Vue 框架支持、AST 解析

---

## v1.11 — 前端代码分析 Agent

**日期**: 2026-08-09
**基线**: v1.10
**迭代主题**: 增强 VueAnalyzer 为前端代码分析 Agent，补上交互流转/DOM选择器/表单校验上下文

### 改动清单与目的

#### 后端

| 文件 | 改动 | 目的 |
|------|------|------|
| `analyzer/result/FrontendResult.java` | 新增 forms/componentStates/domSelectors/pageFlows 4 个字段 | 扩展前端分析数据模型 |
| `analyzer/VueAnalyzer.java` | 新增 4 个提取方法：extractForms/extractComponentStates/extractDomSelectors/extractPageFlows + collectVueFiles + 多个辅助方法 | 深度解析 Vue SFC 提取表单/交互状态/DOM选择器/页面跳转 |
| `agent/OrchestratorAgent.java` | 新增 loadFrontendResult() + 传给 TestGeneratorAgent | 编排 Agent 加载前端上下文 |
| `agent/TestGeneratorAgent.java` | 新增 generate(...frontendResult) 重载 + putFrontendContext() + prompt 补充前端信息 | 用例生成消费前端上下文（表单字段→testData、选择器→uiSelector、页面流转→跳转用例） |

#### 前端

| 文件 | 改动 | 目的 |
|------|------|------|
| `views/CodeAnalysis.vue` | 新增"前端分析"tab，含 4 个展示面板（表单字段/组件交互状态/DOM选择器/页面跳转关系） | 可视化前端分析结果 |

### 验证
- 后端编译：`mvn compile` BUILD SUCCESS（8.3s，70 source files）
- 前端构建：`npm run build` 成功（11.63s）

### 向后兼容
- FrontendResult 新字段默认空列表，历史数据无影响
- frontendResult 为 null 时 TestGeneratorAgent 退化为原逻辑
- H2 无 schema 变更（frontendResult 序列化在 CodeAnalysis.text 字段中）
- 前端无 Vue 项目时前端分析 tab 显示"无数据"

### 范围说明
- In Scope：VueAnalyzer 4 维度增强、FrontendResult 扩展、OrchestratorAgent 接入、TestGeneratorAgent prompt 改造、CodeAnalysis 页面增强
- Out of Scope：AI 执行引擎（v2.0）、React/Angular 分析器、Pinia/Vuex 深度分析、Vue SFC 完整 AST 解析

---

## v1.10 — PRD 驱动的用例生成

**日期**: 2026-08-09
**基线**: v1.9
**迭代主题**: 引入 PRD 作为主上下文 + 多 Agent 编排架构（PrdAgent + OrchestratorAgent）

### 改动清单与目的

#### 后端

| 文件 | 改动 | 目的 |
|------|------|------|
| `entity/Project.java` | 新增 `prdContent`/`prdSourceType`/`prdSourceRef` 三个字段 | 持久化 PRD 内容与来源信息 |
| `dto/ProjectDTO.java` | 透传 PRD 三个字段 | 前端可读写 PRD |
| `dto/PrdAnalysisResult.java` | 新建 DTO：modules/requirements/businessRules/stateFlows/entities | PRD 结构化解析结果载体 |
| `agent/PrdAgent.java` | 新建 PRD 解析 Agent：文本 LLM 结构化 + PDFBox 解析 PDF + Jsoup 抓取 URL | 三种 PRD 接入形式统一为结构化结果 |
| `agent/OrchestratorAgent.java` | 新建编排 Agent：协调 PrdAgent + 代码侧（状态机/后端结果）→ TestGeneratorAgent | 显式编排替代隐式调用链；PRD 为空时退化为代码驱动 |
| `agent/TestGeneratorAgent.java` | 新增 `SYSTEM_PROMPT_PRD_DRIVEN` + `generate(prdResult, stateMachines, backendResult, callback)` 重载 | PRD 为主上下文生成用例；PRD 为空时退化为原逻辑 |
| `controller/ProjectController.java` | 新增 4 接口：`GET /prd`、`PUT /prd`、`POST /prd/upload`、`POST /prd/fetch` | PRD 查询/文本更新/PDF上传/URL抓取 |
| `service/ProjectService.java` | 新增 `getPrd`/`updatePrd`/`uploadPrdPdf`/`fetchPrdUrl` | PRD 管理逻辑（PDF 调 PrdAgent.parsePdf，URL 调 PrdAgent.fetchUrl） |
| `service/TestCaseService.java` | `runGenerate` 改由 `orchestratorAgent.generate()` 编排 | 从隐式调用链升级为显式 Agent 编排 |
| `pom.xml` | 新增 PDFBox 3.0.1 + Jsoup 1.17.2 依赖 | PDF 解析与 URL 抓取支持 |

#### 前端

| 文件 | 改动 | 目的 |
|------|------|------|
| `api/project.js` | 新增 `getPrd`/`updatePrd`/`uploadPrdPdf`/`fetchPrdUrl` | 四个 PRD 接口封装 |
| `components/PrdPanel.vue` | 新建 PRD 面板：文本/Markdown 编辑器 + PDF 拖拽上传 + URL 抓取 + PRD 预览 + 来源标识 | 三种接入形式切换与交互 |
| `views/ProjectDetail.vue` | 引入 PrdPanel 组件 | 项目详情页展示 PRD 面板 |

### 验证
- 后端编译：`mvn compile` BUILD SUCCESS（3.2s）
- 前端构建：`npm run build` 成功（16.04s），无 chunk 警告

### 向后兼容
- 历史项目无 PRD 字段，`prdContent` 为 null，生成时退化为代码驱动（v1.9 行为）
- H2 `ddl-auto=update` 自动加列，无需迁移脚本
- 既有 API 端点不变，新增 4 个 PRD 端点

### 范围说明
- In Scope：PRD 数据模型 + 三种接入形式 + PrdAgent 解析 + OrchestratorAgent 编排 + TestGeneratorAgent 改造 + 前端 PRD 面板
- Out of Scope：前端代码分析 Agent（v1.11）、AI 执行引擎（v2.0）、需 OAuth 认证的 docs 接入、PRD 版本管理

---

## v1.9 — 用例版本管理

**日期**: 2026-08-09
**基线**: v1.8
**迭代主题**: 用例编辑版本快照 + 历史版本列表 + 字段级对比 + 一键回滚

### 改动清单与目的

#### 后端

| 文件 | 改动 | 目的 |
|------|------|------|
| `entity/TestCaseVersion.java` | 新建版本快照实体（id/testCaseId/projectId/versionNo/snapshot/action/createdAt） | 持久化用例历史快照 |
| `repository/TestCaseVersionRepository.java` | 新建版本仓库（按用例倒序查、计数、按 id+testCaseId 查） | 版本查询支持 |
| `dto/TestCaseVersionDTO.java` | 新建 DTO（list/detail 双视图，detail 含 snapshot） | 列表精简、详情含快照 |
| `controller/TestCaseController.java` | 新增 3 接口：`GET /{tcId}/versions`、`GET /{tcId}/versions/{vId}`、`POST /{tcId}/versions/{vId}/rollback` | 版本列表/详情/回滚入口 |
| `service/TestCaseService.java` | 注入版本仓库；`updateTestCase` 前置存 edit 快照；新增 `listVersions`/`getVersion`/`rollbackToVersion`（回滚前存 rollback 快照）/`createVersion`/`toSnapshotJson`/`applySnapshotToTestCase` | 编辑前留档、回滚可撤销 |

#### 前端

| 文件 | 改动 | 目的 |
|------|------|------|
| `api/testcase.js` | 新增 `listTestCaseVersions`/`getTestCaseVersion`/`rollbackTestCaseVersion` | 三个版本接口封装 |
| `components/TestCaseVersionDrawer.vue` | 新建版本抽屉：版本列表 + 查看快照 + 与当前用例字段级 diff + 回滚（二次确认） | 历史版本查看/对比/回滚交互 |
| `components/TestCaseCard.vue` | footer 新增"历史版本"按钮（Clock 图标）+ emit `versions` | 详情内打开版本抽屉 |
| `views/TestCaseList.vue` | 引入抽屉组件 + `versionDrawerVisible` + `handleOpenVersions` + `handleVersionRollback`（回滚后刷新列表与当前用例） | 接入抽屉并同步回滚结果 |

### 验证
- 后端编译：`mvn compile` BUILD SUCCESS（10.2s）
- 前端构建：`npm run build` 成功（11.93s），TestCaseList 16KB→20KB，无 chunk 警告

### 向后兼容
- 新增 `test_case_versions` 表，H2 `ddl-auto=update` 自动建表
- 历史用例（v1.9 前数据）无版本记录时列表为空，展示"暂无历史版本"
- `updateTestCase` 行为不变，仅额外写入版本，对调用方透明

### 范围说明
- In Scope：编辑/回滚触发的用例级版本快照
- Out of Scope：重新生成全量覆盖前的项目级快照（已有 v1.3 确认 + v1.7 导出备份兜底，留待后续）

---

## v1.8 — 用例评审状态流转

**日期**: 2026-08-09
**基线**: v1.7
**迭代主题**: 用例评审状态（draft/reviewed/approved/rejected）+ 批量改状态 + 状态筛选

### 改动清单与目的

#### 后端

| 文件 | 改动 | 目的 |
|------|------|------|
| `entity/TestCase.java` | 新增 `reviewStatus` 字段（默认 draft） | 持久化评审状态 |
| `dto/TestCaseDTO.java` | 新增 `reviewStatus` 字段；`from()` 对 null 兜底为 draft | 透传评审状态，历史数据兼容 |
| `dto/ReviewRequest.java` | 新建 DTO：ids + status + reviewer | 批量改评审状态请求体 |
| `controller/TestCaseController.java` | `listTestCases` 新增 `reviewStatus` 筛选参数；新增 `POST /testcases/review` 批量改状态接口 | 状态筛选 + 批量评审入口 |
| `service/TestCaseService.java` | `listTestCases` 增加 reviewStatus 筛选（null 视为 draft）；新增 `batchUpdateReviewStatus`（校验合法状态、按 ids 更新） | 评审状态过滤与批量更新逻辑 |

#### 前端

| 文件 | 改动 | 目的 |
|------|------|------|
| `api/testcase.js` | 新增 `reviewTestCases(projectId, ids, status, reviewer)` | 批量评审接口封装 |
| `views/TestCaseList.vue` | 表格新增"评审"列（彩色 tag）；筛选区新增评审状态下拉；header 新增批量评审下拉菜单（已评审/已批准/已拒绝/重置草稿）；新增 `reviewTagType`/`reviewText`/`handleReviewCommand`；filters 加 reviewStatus；loadList 传参 | 评审状态可视化展示、筛选、批量操作 |

### 验证
- 后端编译：`mvn compile` BUILD SUCCESS（2.45s）
- 前端构建：`npm run build` 成功（12.85s），TestCaseList 14KB→16.08KB，无 chunk 警告

### 向后兼容
- 历史用例 reviewStatus 为 null，DTO 兜底为 draft，前端展示为"草稿"
- 全部为新增字段与接口，不破坏既有功能

---

## v1.7 — 导入导出与协作增强

**日期**: 2026-08-09
**基线**: v1.6
**迭代主题**: JSON 导入导出 + CSV 导出 + 跨项目用例复制

### 改动清单与目的

#### 后端

| 文件 | 改动 | 目的 |
|------|------|------|
| `controller/TestCaseController.java` | 新增 `GET /export`（JSON/CSV）、`POST /import`（multipart）、`POST /copy-to` 接口 | 导入导出与跨项目复制入口 |
| `dto/CopyToRequest.java` | 新增 DTO：ids + targetProjectId | 复制请求体 |
| `service/TestCaseService.java` | 新增 `exportTestCases`/`importTestCases`/`copyToProject` + 辅助方法 `nextTestCaseNumber`/`parseTestCaseFromJson`/`cloneTestCase`/`jsonField` | 导出文件流、JSON 解析回灌（重生成 ID、source=imported）、跨项目复制（source=copied）；导入字段缺失用默认值兜底 |
| `service/CsvExporter.java` | 新增工具类：TestCase 列表转 CSV，含 UTF-8 BOM 与标准 CSV 转义 | Excel 打开中文不乱码、字段含逗号/换行正确转义 |

#### 前端

| 文件 | 改动 | 目的 |
|------|------|------|
| `api/testcase.js` | 新增 `exportTestCases`（fetch 拿 headers+blob）、`importTestCases`（FormData）、`copyToProject` | 三个新接口封装 |
| `views/TestCaseList.vue` | header 新增 4 按钮（导出JSON/导出CSV/导入JSON/复制到）+ 隐藏 file input；新增 `downloadBlob`/`handleExportJson`/`handleExportCsv`/`triggerImportFile`/`handleImportFile`/`handleCopyTo` | 导入导出 UI 与交互；复制到用 prompt 列出可选目标项目 |

### 验证
- 后端编译：`mvn compile` BUILD SUCCESS（63 源文件，8.6s）
- 前端构建：`npm run build` 成功（15.71s），TestCaseList 12KB→14KB
- 导出 JSON → 导入回同项目，数量翻倍且 ID 不冲突（重生成 TC-XXX）
- CSV 导出含 BOM，Excel 中文正常
- 跨项目复制后目标项目有用例，源项目不受影响
- 导入非数组 JSON 返回错误提示且不写入

---

## v1.6 — 高可用增强

**日期**: 2026-08-09
**基线**: v1.5
**迭代主题**: 错误详情存储与返回 + 生成进度反馈 + 并发提示优化 + 日志结构化

### 改动清单与目的

#### 后端

| 文件 | 改动 | 目的 |
|------|------|------|
| `entity/Project.java` | 新增 `errorMessage`(TEXT) + `progress` 字段 | 持久化生成失败原因和实时进度 |
| `dto/ProjectDTO.java` | 透传 `errorMessage` + `progress` | 前端轮询可读取 |
| `repository/ProjectRepository.java` | 新增 `updateProgress` + `updateStatusWithError`，均带 `@Transactional` | 进度更新即时提交对前端可见；失败时原子更新 status+errorMessage 并清空 progress |
| `agent/TestGeneratorAgent.java` | 新增 `ProgressCallback` 函数式接口 + `generate(states, result, callback)` 重载；原方法委托新方法传 null | 分模块生成时实时回调进度，不破坏既有调用 |
| `service/TestCaseService.java` | `runGenerate` 增加进度更新（解析/分模块/保存各阶段）+ 失败存储 errorMessage；`updateProjectStatus` 在 generating/completed 时清除残留 errorMessage | 进度反馈、错误可追溯、重新生成成功后旧错误不残留 |
| `service/ProjectService.java` | `triggerGenerate` 对 `generating` 状态给出明确并发提示"正在生成中，请等待当前任务完成" | 避免用户重复触发生成 |
| `resources/logback-spring.xml` | 新增：结构化 JSON 日志配置（LogstashEncoder，含 app 字段） | 便于日志聚合、检索与问题排查 |
| `pom.xml` | 新增 logstash-logback-encoder 7.4 依赖 | 结构化日志输出支持 |

#### 前端

| 文件 | 改动 | 目的 |
|------|------|------|
| `stores/project.js` | 新增 `progressMessage` ref；轮询时实时同步 `project.progress`，终态时清空；回调第三参数显式传整个 project | 进度信息跨组件可读，组件可拿到 errorMessage |
| `views/TestCaseList.vue` | 新增 `generationError` ref + `progressText` computed（优先 store 实时进度，兜底本地提示）；轮询回调读取 `project.errorMessage` 展示；模板新增 error 类型 alert；开始生成时清除上次错误 | 实时展示"正在生成第 X/Y 个模块: xxx"进度 + 失败时展示具体错误详情 |

### 验证
- 后端编译：`mvn compile` BUILD SUCCESS（61 源文件，15.5s）
- 前端构建：`npm run build` 成功（12.13s），无 chunk 警告
- 进度反馈：生成过程中前端轮询每 3s 可见"正在生成第 N/M 个模块: 状态机名"
- 错误详情：生成失败时前端展示后端 errorMessage（如 LLM 调用异常原因）

---

## v1.5 — 可视化增强

**日期**: 2026-08-09
**基线**: v1.4
**迭代主题**: 覆盖率矩阵可视化 + 状态机覆盖图 + 前端 chunk 拆分

### 改动清单与目的

#### 后端

| 文件 | 改动 | 目的 |
|------|------|------|
| `controller/CoverageController.java` | 新增 `GET /coverage/matrix` 接口 | 返回每个状态转换的覆盖详情（covered + testCaseIds） |
| `service/CoverageService.java` | 新增 `getCoverageMatrix()` | 遍历状态机 transitions 与用例 stateMachineRef 匹配，计算覆盖状态 |

#### 前端

| 文件 | 改动 | 目的 |
|------|------|------|
| `components/CoverageMatrix.vue` | 新增组件：覆盖率矩阵表格 | 每行一个转换，显示覆盖状态（✓/✗），未覆盖行红色高亮，可点击关联用例跳转 |
| `components/StateMachineViewer.vue` | 新增 `coverageData` prop；`buildEdges()` 根据覆盖状态着色（绿=已覆盖/红虚线=未覆盖） | 图上直观标注覆盖状态 |
| `views/StateMachineOverview.vue` | 新增页面：状态机覆盖图 | 独立路由展示状态机图+覆盖标注+统计摘要 |
| `views/TestCaseList.vue` | 引入 CoverageMatrix 组件；新增 coverageMatrix 状态和 loadCoverageMatrix | 在用例列表页展示覆盖率矩阵 |
| `api/coverage.js` | 新增 `getCoverageMatrix()` | 调用矩阵接口 |
| `router/index.js` | 新增 `/projects/:id/state-machines` 路由 | 状态机覆盖图页面入口 |
| `vite.config.js` | 新增 `build.rollupOptions.output.manualChunks`（echarts/elementPlus/vendor 分离）+ `chunkSizeWarningLimit: 1100` | 解决单 chunk 2.2MB 问题，首屏仅需 index+vendor（185KB） |

### 验证
- 后端编译：`mvn compile` BUILD SUCCESS（61 源文件）
- 前端构建：`npm run build` 成功（13.71s），无 chunk 警告
- chunk 效果：index.js 26KB + vendor 159KB + echarts 1035KB + elementPlus 1074KB（按需懒加载）

---

## v1.4 — 生成质量增强II & 批量操作

**日期**: 2026-08-09
**基线**: v1.3
**迭代主题**: LLM prompt 深度优化（具体字段值/边界值/few-shot）+ LLM 重试机制 + 批量操作

### 改动清单与目的

#### 后端

| 文件 | 改动 | 目的 |
|------|------|------|
| `agent/TestGeneratorAgent.java` | systemPrompt 从单行拼接重构为结构化分段常量（角色/任务/数量引导/测试数据要求/structuredSteps要求/stateMachineRef要求/输出格式）；userPrompt 注入 few-shot 示例（1 正向+1 异常） | 让 LLM 生成有具体字段值（amount:-1）、边界值组合、完整结构化步骤的高质量用例 |
| `service/LlmService.java` | `chat()` 重构为重试包装 + `callLlmApi()` 抽取；指数退避 1s→2s→4s，最多 3 次；400/401/403 不重试 | 网络抖动/限流时自动重试，减少不必要的规则回退 |
| `controller/TestCaseController.java` | 新增 `DELETE /batch` 批量删除端点 | 支持批量删除用例 |
| `dto/BatchDeleteRequest.java` | 新增 DTO | 批量删除请求体 |
| `service/TestCaseService.java` | 新增 `batchDeleteTestCases()` | 批量删除逻辑 |
| `controller/MindMapController.java` | `generateMindMap` 新增可选 `testcaseIds` 参数 | 支持只导出选中用例 |
| `service/MindMapService.java` | `generateMindMap` 按 testcaseIds 过滤 | 批量导出选中用例 |

#### 前端

| 文件 | 改动 | 目的 |
|------|------|------|
| `api/testcase.js` | 新增 `batchDeleteTestCases()` | 调用批量删除接口 |
| `api/mindmap.js` | `generateMindmap` 新增 data 参数 | 支持传入 testcaseIds |
| `views/TestCaseList.vue` | el-table 新增 selection 列 + `@selection-change`；header 新增批量删除/导出选中按钮（带数量提示）；新增 `selectedRows`/`handleBatchDelete`/`handleExportSelected` | 批量操作能力 |

### 验证
- 后端编译：`mvn compile` BUILD SUCCESS（59 源文件）
- 前端构建：`npm run build` 成功（16.35s）

---

## v1.3 — 用例体验增强

**日期**: 2026-08-09
**基线**: v1.2
**迭代主题**: 提升用例日常使用体验——搜索、安全确认、结构化步骤可编辑、上下条导航、单个删除

### 改动清单与目的

#### 后端

| 文件 | 改动 | 目的 |
|------|------|------|
| `controller/TestCaseController.java` | 新增 `DELETE /{testcaseId}` 接口；`listTestCases` 新增 `keyword` 参数 | 支持删除单个用例、关键字搜索 |
| `service/TestCaseService.java` | 新增 `deleteTestCase()`；`listTestCases` 新增 keyword 模糊过滤（标题/模块） | 删除能力 + 搜索过滤 |

#### 前端

| 文件 | 改动 | 目的 |
|------|------|------|
| `api/testcase.js` | 新增 `deleteTestCase()` API | 调用删除接口 |
| `views/TestCaseList.vue` | 重新生成增加 `ElMessageBox.confirm` 确认；筛选区新增搜索输入框（keyword）；对话框新增 `canGoPrev/canGoNext` props 传递 + `@delete/@prev/@next` 事件监听；新增 `currentIndex`/`handlePrev`/`handleNext`/`handleDeleteTestCase` | 防误操作丢失数据、搜索用例、导航与删除 |
| `components/TestCaseCard.vue` | 新增 `canGoPrev/canGoNext` props + `delete/prev/next` emits；编辑模式新增结构化步骤编辑器（增删改 action/target/expected/type）；对话框 footer 新增删除按钮 + 上一条/下一条按钮；`handleSave` 提交 structuredSteps | 结构化步骤可编辑、删除用例、详情内导航 |

### 验证
- 后端编译：`mvn compile` BUILD SUCCESS（58 源文件）
- 前端构建：`npm run build` 成功（17.91s）

---

## v1.2 — 用例生成质量增强

**日期**: 2026-08-09
**基线**: v1.1
**迭代主题**: 提升用例生成质量——分模块精准生成、去重、覆盖率度量、质量评分

### 改动清单与目的

#### 后端

| 文件 | 改动 | 目的 |
|------|------|------|
| `agent/TestGeneratorAgent.java` | 重构 `generate()` 为分模块生成：按状态机逐个调用 LLM，单模块失败仅回退该模块；新增 `deduplicate()`（标题相似度去重，保留质量更高者）、`calculateQualityScore()`（结构完整度 0-100 评分） | 单次聚焦提升质量、避免 token 超限、单点失败隔离；消除重复用例；量化用例质量 |
| `entity/TestCase.java` | 新增 `qualityScore` 字段（Integer） | 持久化质量评分 |
| `dto/TestCaseDTO.java` | 新增 `qualityScore` 字段 + `from()` | 向前端透传质量分 |
| `dto/TestCaseListResponse.java` | 新增 `coverage` 字段（Map） | 随列表响应返回覆盖率 |
| `service/TestCaseService.java` | `listTestCases()` 增加覆盖率计算；新增 `calculateCoverage()`（状态转换覆盖率 + 接口覆盖率 + 类型分布） | 让用例质量可度量、可视化 |

#### 前端

| 文件 | 改动 | 目的 |
|------|------|------|
| `views/TestCaseList.vue` | 新增覆盖率面板（状态转换/接口覆盖率进度条）；表格新增"质量"列（进度条） | 质量可视化，用户直观感知覆盖率与用例质量 |
| `components/TestCaseCard.vue` | 元信息区新增"质量评分"进度条 | 详情中展示单用例质量分 |

### 验证
- 后端编译：`mvn compile` BUILD SUCCESS（58 源文件）
- 前端构建：`npm run build` 成功（19.40s）

### 下一步（v1.3 规划）
- AI 用例执行引擎：基于 structuredSteps + executionHints 自动调用 API 执行用例
- 执行结果存储与 executionStatus 状态流转

---

## v1.1 — 结构化可执行用例（Executable Test Case Spec）

**日期**: 2026-08-09
**基线**: v1.0
**迭代主题**: 让测试用例从"自然语言文档"升级为"AI 可读可执行的结构化剧本"

### 改动总览

本次迭代为后续"AI 执行用例"打地基：用例数据模型新增结构化字段，生成逻辑升级以产出结构化步骤、关联 API、真实填充状态机引用，前端升级为结构化步骤卡片展示。**不实现实际执行**（留待 v1.2）。

### 改动清单与目的

#### 后端

| 文件 | 改动 | 目的 |
|------|------|------|
| `entity/TestCase.java` | 新增 5 个字段：`structuredSteps`、`apiEndpoints`、`testData`、`executionHints`、`executionStatus` | 让用例携带 AI 执行所需的操作目标、测试数据、执行方式、执行状态，从"文档"变为"可执行剧本" |
| `dto/TestCaseDTO.java` | 新增对应字段及 `from()` 转换 | 向前端透传结构化数据 |
| `dto/UpdateTestCaseRequest.java` | 新增对应可选字段 | 支持通过 API 更新结构化字段 |
| `service/TestCaseService.java` | `updateTestCase()` 补充新字段更新逻辑 | 编辑能力覆盖新字段 |
| `agent/TestGeneratorAgent.java` | LLM prompt 升级要求生成结构化步骤/API关联/执行提示/状态机引用；规则回退也填充新字段；新增 `buildStateMachineRef`/`matchEndpoints`/`buildForbiddenTransitions` 辅助方法 | 生成的用例结构上具备被 AI 执行的条件，并真实关联状态转换与接口端点 |

**核心设计**：
- `structuredSteps`：每步含 `order/action/target/expected/data/type`，`type` 标注 `api_call|ui_action|state_assert|manual`，AI 可据此选择执行方式
- `executionHints.approach`：标注推荐执行方式，为 v1.2 执行引擎提供决策依据
- `stateMachineRef`：状态机用例真实关联 states/transitions/forbiddenTransitions，支撑覆盖率度量
- 向后兼容：旧字段 `steps/preconditions/expectedResults` 仍填充，XMind 导出与列表展示不受影响

#### 前端

| 文件 | 改动 | 目的 |
|------|------|------|
| `components/TestCaseCard.vue` | 查看模式新增：结构化步骤卡片（步骤-目标-预期-数据配对）、关联接口标签、执行提示 alert、测试数据表格、执行状态标签；含回退兼容（无 structuredSteps 时回退纯文本列表） | 提升人类阅读友好度，"步骤-预期"配对可视化；同时直观呈现 AI 可执行性 |

#### 文档

| 文件 | 说明 |
|------|------|
| `docs/v1.1/PRD_v1.1_结构化可执行用例.md` | 本次迭代产品需求文档 |
| `docs/v1.1/后端技术评审_v1.1.md` | 后端技术评审（标注 v1.1 版本） |
| `docs/v1.1/前端技术评审_v1.1.md` | 前端技术评审（标注 v1.1 版本） |
| `docs/CHANGELOG.md` | 本文件 |

### 向后兼容

- v1.0 已有用例（无新字段）前端正常展示：`structuredSteps` 为空时回退纯文本 `steps` 列表
- H2 ddl-auto=update 自动加列，无需迁移脚本
- API 端点不变，响应体向后兼容扩展

### 验证

- 后端编译通过：`mvn compile`（JDK 17，58 源文件，BUILD SUCCESS）
- 前端构建验证：`npm run build`

### 下一步（v1.2 规划）

- AI 用例执行引擎：基于 `structuredSteps` + `executionHints` 自动调用 API 执行用例
- 执行结果存储与 `executionStatus` 状态流转
- 执行结果报告与可视化
