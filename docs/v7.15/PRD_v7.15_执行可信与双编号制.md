# PRD v7.15 — 执行可信与双编号制

> 版本基线：v7.14（生成 Prompt 重复注入治理）
> 状态：已实施
> 主题关键词：流式去重 / 双编号制 / 未覆盖接口清单 / 口径标注 / 执行数据防御

## 一、迭代背景与痛点

本轮迭代源于一次真实使用中发现的三类可信度问题：

1. **流式生成重复草稿卡**：追加生成时同一用例在界面上出现 N 次。根因有二：
   - 运行镜像为回退前源码构建（standard 档 6 轮而非 3 轮），放大了重复；
   - 多轮补齐对未收敛缺口反复再生成同题用例，而 SSE 推送发生在落库去重之前，前端逐条 unshift 造成堆卡。
2. **用例编号不直观**：v7.11(T1/T2) 为修复跨项目同号 merge 静默覆盖，改为全局唯一 TC-xxx 分配器，
   但新项目首个用例直接从 TC-171 起，用户失去"项目内第几条"的感知。
3. **覆盖率口径混淆 + 缺口不可操作**：
   - 接口覆盖（分母=代码分析接口）与状态机矩阵（分母=转换）数值接近但含义不同，易误读；
   - 28/86 的未覆盖接口没有可视化清单，无法针对性补测。
4. **执行结果不可信案例**（TC-171 六步全败）：LLM 生成的结构化步骤存在三类脏数据——
   API 路径被塞进 ui_action 的 target、uiSelector 使用执行器不支持的 `ref` 类型、期望文本为叙述性描述不可断言。

## 二、范围

### In Scope

- 流式推送跨轮去重（后端 wrapPushDedup）+ 前端 onCase upsert 兜底
- 用例双编号制：全局 id 不变，新增 `project_seq` 项目内展示序号（含存量回填迁移）
- 未覆盖接口清单：新端点 + 前端可折叠面板
- 覆盖率口径标注（tooltip / 说明行 / 矩阵描述分母注明）
- PrdPanel 保存后通知父页面刷新（生成按钮实时解禁）
- 版本标注泄漏清理（CoverageMatrix / TestCaseList 用户可见文案中的 `（vN.N）`）
- docker-compose 待分析项目挂载改名 litemall → litemall-mall
- 执行数据防御三件套（A/B/C）：
  - A 生成侧 prompt 硬约束（target 禁止 HTTP 形态；uiSelector 类型白名单对齐执行器能力）
  - B 解析期 uiSelector 类型白名单清洗（非法类型整体剔除）
  - C ExecutionAgent 对 HTTP 形态 target 的 ui_action 自动降级 skip

### Out of Scope

- state_assert "叙述式期望"的语义匹配增强（涉及诚实性权衡，留待 v8.x 与断言引擎重构一并考虑）
- 多实例部署下的分配器跨进程竞争（与 TestCaseIdAllocator 同一已知限制）

## 三、验收标准

1. 追加生成过程中，同一用例标题的草稿卡在界面只出现一张。
2. 新生成的用例带项目内序号（#1 起），列表展示序号、悬浮可见平台全局 TC-id；存量数据回填正确；
   全量重生成后序号从 1 重计；删除用例产生的序号空洞不回收。
3. `/coverage/uncovered-endpoints` 返回与接口覆盖率完全同口径的差集清单。
4. LLM 再产出 `GET /xxx` 形态 target 时：生成侧被 prompt 约束、漏网者被解析期剔除 uiSelector、
   执行期自动 skip 且错误信息如实标注"接口引用而非页面元素"。
5. 后端全量测试通过（405）；前端 vitest 通过；两镜像重建部署成功。

## 四、风险与缓解

| 风险 | 缓解 |
|---|---|
| 推送去重可能吞掉同名但内容不同的合法用例 | 与落库侧 deduplicate() 同判据（标题），语义本就视为重复；且仅影响展示 |
| project_seq 并发分配冲突 | 单实例前提 + synchronized per-project 缓存，与全局分配器同策略 |
| 迁移回填在大表上的开销 | COUNT 相关子查询 O(n²) 仅存量执行一次；改用 ROW_NUMBER 物化派生表规避 MySQL 1093 |
| C 防御误伤正常 target | 正则仅匹配严格 `METHOD /path` 形态；人话描述不受影响（有对照测试） |

## 五、交付物清单

- 后端：TestGeneratorAgent / ExecutionAgent / CoverageService / CoverageController / TestCaseService /
  TestCasePersistenceService / ProjectSeqAllocator(新增) / TestCaseRepository / TestCase / TestCaseDTO /
  V12 迁移 / 测试 3 类
- 前端：TestCaseList.vue / CoverageMatrix.vue / PrdPanel.vue / ProjectDetail.vue / api/coverage.js
- 文档：本 PRD + 后端技术评审 + 前端技术评审 + CHANGELOG + README + API概览
