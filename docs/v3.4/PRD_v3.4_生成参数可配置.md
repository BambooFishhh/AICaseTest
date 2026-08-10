# PRD v3.4 — 生成参数可配置

> 版本：v3.4 | 主题：项目级生成参数可配置
> 基线：v3.3（流式生成取消与落库保护）
> 日期：2026-08-11

## 一、背景与痛点

### 1.1 现状

用例生成的核心参数全部硬编码在 `TestGeneratorAgent`：

| 参数 | 硬编码位置 | 当前值 |
|------|-----------|--------|
| LLM temperature | `generateByLlmForStateMachine` L376 / `generateByLlmWithPrd` L439 | `0.4` |
| 用例数量引导 | `SYSTEM_PROMPT` / `SYSTEM_PROMPT_PRD_DRIVEN` 的"数量引导"段 | 正向≥1/异常≥1/边界≥2/数据≥1 |
| 测试类型 | system prompt + `parseTestCases` | positive/negative/boundary/data |

不同项目类型对用例密度需求不同：订单系统需要大量边界值，权限系统需要大量异常用例，原型项目只需少量正向验证。当前一刀切，用户无法调整。

### 1.2 机会

`Project.settings` 字段（`@Column(columnDefinition = "TEXT") private String settings = "{}"`）已存在但**完全未使用**——天然的"项目级生成参数"存储位，无需新建表。

## 二、范围

### In Scope

1. **GenerationParams DTO**：caseDensity（低/中/高）、temperature（0.2~0.6）、focusTypes（聚焦的测试类型）
2. **Project.settings 存储**：JSON 序列化 GenerationParams
3. **动态 system prompt**：根据 caseDensity 动态拼接"数量引导"段
4. **temperature 参数化**：从 GenerationParams 读取，替换硬编码 0.4
5. **API 端点**：GET/PUT `/api/projects/{id}/generation-params`
6. **前端参数对话框**：生成前可调整参数

### Out of Scope

- focusModules（模块聚焦）— 需要 PRD 模块列表联动，复杂度高，留待后续
- 优先级偏好 — 影响较小，留待后续
- 质量评分权重可配 — 留待后续
- 去重阈值可配 — 留待后续

## 三、功能详情

### 3.1 GenerationParams 参数定义

| 参数 | 类型 | 默认值 | 可选值 | 说明 |
|------|------|--------|--------|------|
| caseDensity | String | `"medium"` | `low`/`medium`/`high` | 用例数量密度 |
| temperature | Double | `0.4` | `0.2`/`0.3`/`0.4`/`0.5`/`0.6` | LLM 创造性 |
| focusTypes | List\<String\ | `[]`(全部) | `positive`/`negative`/`boundary`/`data` 子集 | 聚焦类型（空=全部） |

### 3.2 caseDensity → 数量引导映射

| density | 数量引导文本 |
|---------|-------------|
| low | 正向≥1/异常≥1/边界≥1/数据可选 |
| medium | 正向≥1/异常≥1/边界≥2/数据≥1（**当前行为**） |
| high | 正向≥2/异常≥2/边界≥3/数据≥2 |

### 3.3 存储格式（Project.settings JSON）

```json
{
  "generationParams": {
    "caseDensity": "medium",
    "temperature": 0.4,
    "focusTypes": []
  }
}
```

兼容空 `{}`：解析失败或字段缺失时使用默认值。

### 3.4 动态 system prompt

将 `SYSTEM_PROMPT` / `SYSTEM_PROMPT_PRD_DRIVEN` 中的"数量引导"段从静态常量改为动态拼接：

```java
private String buildQuantityGuide(String caseDensity) {
    return switch (caseDensity) {
        case "low" -> """
            - 正向用例（positive）：每个合法状态转换至少 1 条
            - 异常用例（negative）：每个状态转换至少 1 条
            - 边界值用例（boundary）：涉及数值/长度字段的至少 1 条
            - 数据驱动用例（data）：可选""";
        case "high" -> """
            - 正向用例（positive）：每个合法状态转换至少 2 条
            - 异常用例（negative）：每个状态转换至少 2 条
            - 边界值用例（boundary）：涉及数值/长度字段的至少 3 条（上界+下界+越界）
            - 数据驱动用例（data）：多参数组合场景至少 2 条""";
        default -> /* medium = 当前文本 */;
    };
}
```

system prompt 由静态常量改为模板 + 动量段拼接。

### 3.5 参数传递链路

```
Project.settings (JSON)
  → OrchestratorAgent.loadGenerationContext 解析为 GenerationParams
  → GenContext 携带 params
  → TestGeneratorAgent.generateStreaming(..., params)
  → buildSystemPrompt(params) + llmService.chat(prompt, userPrompt, params.temperature)
```

### 3.6 API 端点

```
GET  /api/projects/{id}/generation-params  → GenerationParams
PUT  /api/projects/{id}/generation-params  ← GenerationParams → GenerationParams
```

PUT 将参数写入 `Project.settings` JSON 的 `generationParams` 字段。

### 3.7 前端参数对话框

- TestCaseList 工具栏新增"生成参数"按钮
- 点击弹出 el-dialog：caseDensity（radio-button 三档）、temperature（slider 0.2~0.6 步长 0.1）、focusTypes（checkbox-group 四选）
- 保存调 PUT 端点
- "重新生成"前可先调整参数

## 四、验收标准

| # | 验收点 |
|---|--------|
| 1 | GET generation-params 返回当前参数（默认 medium/0.4/[]） |
| 2 | PUT 后 GET 返回更新后的参数 |
| 3 | caseDensity=low 生成用例数 ≤ medium ≤ high |
| 4 | temperature=0.6 生成的用例多样性高于 0.2 |
| 5 | 前端参数对话框可调整并保存 |
| 6 | 未设置参数（settings={}）时使用默认值，不报错 |
| 7 | 后端编译通过 |
| 8 | 前端构建通过 |

## 五、风险与缓解

| 风险 | 缓解 |
|------|------|
| settings JSON 解析失败 | try-catch 降级为默认值 |
| 非流式 generate 路径未传 params | params 为 null 时使用默认值（向后兼容） |
| system prompt 改为动态拼接可能影响质量 | medium 档保持与当前完全一致的文本 |

## 六、交付物清单

- [ ] 后端：`GenerationParams` DTO
- [ ] 后端：`ProjectService` get/update generation-params
- [ ] 后端：`ProjectController` GET/PUT 端点
- [ ] 后端：`OrchestratorAgent` 解析 settings → GenerationParams
- [ ] 后端：`TestGeneratorAgent` 动态 prompt + temperature 参数化
- [ ] 前端：`api/project.js` get/update generation-params
- [ ] 前端：参数对话框组件
- [ ] 文档：CHANGELOG + README 更新
