# PRD v8.4 — 256k 上下文扩容与代码审查修复

> 版本：v8.4（2026-08-26）
> 前置依赖：v8.3 覆盖率口径重构（Scope-Aware 三期收官）
> 系列定位：工程加固版——不新增用户功能，专注三件事：**容量预算适配 256k 模型、链路可靠性修复（本地代码审查 13 项）、攻击面收敛**
> 本期仅后端改动，无前端变更（前端技术评审省略）

## 1. 背景与问题

### 1.1 容量瓶颈

切换 256k context 模型后，生成链路中大量历史硬编码截断阈值成为覆盖率与用例密度的新瓶颈：

- PRD 解析入口单文档 12000 / 总量 24000 字符——大 PRD 直接丢需求条目（覆盖率根因之一）
- 生成上下文 endpoints 80 / rules 100 条、RAG 切片 1200×6、文档原文 3000×3——大项目信息被系统性砍尾
- coverageGaps 六类 id 上限 40~80 不等，缺口清单先于上下文耗尽
- 单项目生成上限 60 条，只够中小项目
- LLM 输出上限 16384 tokens，高密度单轮易顶满导致流式 JSON 截断

### 1.2 可靠性问题（本地代码审查发现）

1. **线程池饱和拖垮全站**：分析/生成池统一 CallerRunsPolicy，队列满时分钟级 AI 任务回落 HTTP 线程执行，阻塞全部普通接口；SSE 路径则静默挂死无任何反馈
2. **流式重试重复推送**：LLM 流中途失败重试时已推送的半截草稿与新全量输出叠加，产生重复卡片/重复入库候选
3. **挂死流永久占线程**：网关心跳保活使底层 read-timeout 永不触发
4. **prompt 保险丝只保头**：超限裁剪丢掉尾部的任务指令与 gaps 清单
5. **整批解析连坐**：一条畸形用例数据抛异常丢失整轮结果
6. **多代码围栏取错块**：模型分段输出说明文字+JSON 时只取第一个围栏
7. **枚举脏值入库**：type/priority 中文别名直接落库污染统计筛选
8. **Milvus 静默丢向量**：中文超 VarChar 字节上限插入失败仅 warn；expr 特殊字符未转义致检索静默空召回；删除失败无重试无告警产生幽灵数据
9. **语义去重串行慢**：逐条串行 embedding，60 条用例浪费数十秒

### 1.3 安防问题

1. 目录选择器可枚举服务器全盘盘符
2. MCP 分析接口 sourcePath 可传任意路径读本地目录
3. bridge token 用非常量时间比较（时序侧信道）
4. Git 克隆地址可指向内网（SSRF 探测）
5. 用户上下文直拼 prompt 无注入隔离
6. 各 Controller 散落 @CrossOrigin 与集中式 CORS 配置（WebConfig）双轨并存

## 2. 目标

1. 全部截断预算参数化（`app.prd.*` / `app.generation.*` / `llm.*`），默认值按 256k 模型放宽，均可环境变量回调
2. 线程池满快速失败且用户可见（SSE busy 事件 / 503 / 状态回滚），不再拖垮或挂死
3. 流式重试端到端一致（解析器重置 + 前端 retryReset 清草稿）
4. 解析/检索/写入层容错：坏数据跳过并告警，不丢整批；向量层转义+字节截断+删除重试
5. 安防收敛：文件浏览白名单、MCP 路径白名单 + 常量时间比较、克隆 SSRF 校验、prompt `<context>` 注入隔离、CORS 单轨化

## 3. 验收标准

1. 大 PRD（≥40k 字符/篇）不再在解析入口丢需求；生成注入预算全部可配可回退
2. 线程池打满时：SSE 收到 `error` 事件提示"服务器繁忙"；同步接口返回 503 且项目状态回滚不卡 analyzing/generating；评审任务标记 failed 不卡 reviewing
3. LLM 流失败自动重试后，前端草稿区清空重渲染，最终无重复用例
4. 单条畸形用例被跳过并在日志可见，其余正常保留
5. Milvus 写入前按 schema 字节上限截断（UTF-8 边界安全）；含引号 module 检索不再空召回；删除失败有重试且终败 ERROR
6. 目录选择器根节点只出白名单目录；越界路径报"非法路径"
7. MCP analyze 接口拒绝白名单外 sourcePath（40300）；token 错误恒定拒绝
8. 指向内网的 Git 地址克隆被拒并提示
9. `mvn test` 全绿（407）；无 API 契约破坏性变更

## 4. 影响范围

| 层 | 文件 |
|---|---|
| 预算 | PrdAgent、TestGeneratorAgent、LlmService、application.yml |
| 可靠性 | AsyncConfig、ProjectController、ProjectService、TestCaseReviewRunner、TestCaseService |
| 数据层 | MilvusService、SemanticService |
| 安防 | FilesystemController、McpBridgeController、GitCloneService、全部 Controller（@CrossOrigin 移除） |

## 5. 风险与权衡

- 放宽预算会抬高单次生成的 token 成本——全部键位可用环境变量回调（128k 模型建议回退旧值）
- maxGeneratedCases 120 受补齐轮次（3~4）双重约束，先到者生效；若日志频现 coverageCappedByLimit 再评估放宽轮次
- SSRF 校验存在 DNS rebinding 残留风险（校验与 git 实际连接两次解析），本期先消除直接内网地址场景
- ssh/git@ 克隆地址无法本地预解析（企业堡垒机场景），不拦截仅日志告警
