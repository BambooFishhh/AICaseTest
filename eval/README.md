# eval — 评测体系（v8.7.2，计划书 9.5.7–9.5.10）

## 目录结构

```
eval/
├── datasets/            # 黄金数据集（小/中/大三档，合成内容已脱敏）
│   ├── small/           # prd.md + expected.json + fixture-response.json
│   ├── medium/
│   └── large/
└── results/             # 评测报告 {date}-{gitsha}.json（gitignore 之外，报告可入库）
```

- `prd.md`：待分析需求文档
- `expected.json`：标注口径——`requirements`（期望覆盖的需求名）、`endpoints`（期望引用的接口）、`p0Points`（P0 用例要点）
- `fixture-response.json`：mock 回放用的 LLM 出参夹具（含故意畸形条目用于验证跳过率统计）

## 运行（mock 模式）

```powershell
docker run --rm -v ${PWD}\backend:/build -v ${PWD}\.mvn-repo:/root/.m2 -w /build `
  maven:3.9-eclipse-temurin-17 mvn -q compile exec:java "-Dexec.mainClass=com.testagent.eval.EvalRunner"
```

mock 模式不调真实 LLM，回放夹具只测结构指标。真实模式（9.5.10 回测用，需 `.env` 配置 LLM_API_KEY）：

```powershell
# 真实链路回放：走完整 分析→生成 管线（消耗真实 token，预计每数据集分钟级）
docker run --rm -v ${PWD}\backend:/build --env-file .env -w /build `
  maven:3.9-eclipse-temurin-17 mvn -q compile exec:java "-Dexec.mainClass=com.testagent.eval.EvalRunner" `
  "-Dexec.args=--live"
```

## 基线对比

```powershell
# 报告产出后对比基线（回归即退出码 2）
EvalRunner compare eval/results/<baseline>.json eval/results/<candidate>.json
```

## 健康线（计划书 5.3）

| 指标 | 健康线 |
|---|---|
| 需求召回率 | ≥ 90% |
| 接口覆盖率 | ≥ 80% |
| 结构合格率 | ≥ 98% |
| 解析跳过率 | ≤ 2% |

## 流程固化规则（9.5.9，评审硬检查项）

1. **任何** prompt 模板 / 预算参数（`app.prd.*`、`app.generation.*`、`llm.max-*`）改动，合入前必须：
   - 跑一次评测产出新报告；
   - `compare` 对比最近基线，四项健康线全绿且无 REGRESSION；
   - 新报告随代码一同入库归档。
2. 基线更新需在 CHANGELOG 记录变更理由。
3. mock 模式仅验证结构与管线；涉及生成质量的 prompt 改动必须用真实模式跑 small 数据集复核。

## v8.4 扩容效果回测（9.5.10）

256k 预算扩容（v8.4）的覆盖率回测需真实 LLM：

```powershell
# 使用上方真实模式命令跑 large 数据集，将报告与 v8.3 时代口径对比
```

若覆盖率未提升或结构合格率下降，产出参数回调建议（如 endpoints-context-max 降回），经确认后实施——见计划书任务卡 `[需确认]` 标注。
