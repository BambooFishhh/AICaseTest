# PRD v9.2 — 用例人话化 + 执行器导航与断言修复

> 版本 v9.2，一旦确定尽量不要轻易改动。基线 v9.1。范围：用例语言质量根因修复（skill 漂移）+ 执行器导航/断言增强 + 前端流式渲染修复。litemall 实测驱动。

## 一、背景与痛点

- **用例"接口化"根因**：`skills/test-generation-prd-footer.md` 是 v5.13 旧文件，skill 文件覆盖代码内 prompt——代码侧多轮迭代的"禁止 api_call、步骤人话化"约束从未生效，FEW_SHOT_EXAMPLES 还含 3 个 api_call 示例（示例即行为，直接教坏模型）；
- **执行器假通过**：hash 路由应用（litemall `/#/collect`）导航后 URL 呈 `base/collect#/`——path 段残留路由、hash 停在首页，旧命中判定误判成功；
- **断言误报**：抽象断言（"加载完成/至少一个"）无具体文案可匹配必然 failed；占位符断言（"共 N 件收藏"）字面匹配必然 failed；
- **前端流式重影**：el-table row-key 依赖 `id`，流式草稿无持久化 id → key 全 undefined 冲突产生重影。

## 二、范围与验收

| # | 内容 | 验收 |
|---|---|---|
| 1 | skill 重写 + 代码 fallback 同步 + FEW_SHOT 全 UI 化 | 生成用例步骤全 UI 话术 |
| 2 | UiLanguageLinter 扩展（接口化话术/变量占位符/api_call/缺引号锚点/引号内占位符） | UiLanguageLinterTest |
| 3 | 评审层拒绝全 api_call 用例；ai-review.md 加 v9.2 检查项 | 评审拦截 |
| 4 | 确定性后处理：normalizeModules（候选互包含+批内词干投票）/ injectRouteSelectors | TestGeneratorAgentModuleNormalizeTest |
| 5 | 执行器：hash 感知 URL 命中 + route 选择器导航（history→hash 兜底）+ Agent 模式 hash 兜底 | ExecutionAgentNavigationTest |
| 6 | 断言：抽象断言诚实降级 skipped；占位符语义匹配（N 按数字） | ExecutionAssertTest |
| 7 | 前端：草稿 renderKey 修重影；追加同名草稿隐藏+横幅计数；执行结果媒体竖版展示 | 构建通过+实测 |

## 三、功能细节

- **skill 与代码同步**：重写 `test-generation-prd-footer.md`（步骤 type 仅 ui_action/input/state_assert，target 必须页面元素人话描述，禁止直接调接口）；代码内 FEW_SHOT_EXAMPLES 全 UI 化；skills/ai-review.md 增加 v9.2 检查项。
- **UiLanguageLinter 扩展**：接口化话术（"调用XX接口/POST 路径"）、变量占位符裸露、api_call 步骤、断言缺引号文案锚点、引号内占位符（提示可语义匹配）。
- **normalizeModules**：模块名候选互包含合并 + 批内词干投票归一（"收藏管理/我的收藏管理"→同一模块），解决 LLM 模块名碎片化。
- **injectRouteSelectors**：路由形态 target（"打开XX页"）按 frontendResult.routes 注入 `uiSelector={type:"route"}`，执行器确定性导航。
- **urlHitsRoute**：URL 命中判定感知 hash——`#` 后才是路由段；`navigateToRoute` history 形式未命中自动尝试 hash 形式。
- **ExecutionAssert**：无引号锚点的泛化表述降级 skipped；引号短语含占位符（N/X/{var}）时按 `字面段+\\d+` 正则语义匹配。

## 四、验收标准

1. 后端新增单测全绿（UiLanguageLinterTest 扩展 + TestGeneratorAgentModuleNormalizeTest + ExecutionAgentNavigationTest + ExecutionAssertTest 扩展）。
2. litemall 实测：生成用例全 UI 话术；collect 页导航 URL 命中 hash 路由；"共 N 件收藏"断言按数字匹配通过。

## 五、交付物清单

agent/TestGeneratorAgent、UiLanguageLinter、TestCaseReviewAgent、ExecutionAgent；service/ExecutionService、ExecutionAssert；skills/test-generation-prd-footer.md、ai-review.md；前端 TestCaseList.vue、ExecutionResult.vue；四个测试文件。
