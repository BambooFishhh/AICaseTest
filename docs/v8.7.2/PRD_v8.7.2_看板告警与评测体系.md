# PRD v8.7.2 — Grafana 看板告警 + 评测体系 v1

> 版本 v8.7.2，一旦确定尽量不要轻易改动。基线 v8.7.1。范围：计划书任务 9.5.5、9.5.7–9.5.10（9.5.6 追踪按计划书默认跳过；v8.7 拆分版下半）。

## 一、背景与痛点

v8.7.1 已把降级与一致性钩子送进 Prometheus，但：指标没有看板承载，劣化靠人盯原始数值；告警规则未覆盖新指标，劣化无法主动推送；计划书反复强调的"prompt/预算参数改动无回归防线"依旧裸奔——256k 扩容（v8.4）的效果至今没有数据回答。

## 二、范围

| # | 任务 | 内容 |
|---|---|---|
| 9.5.5 | 看板 + 告警 | 生成质量/数据一致性两块 dashboard；三条告警规则挂进既有 alerts 链路 |
| 9.5.7 | 黄金数据集 | eval/datasets 小/中/大三档代表性输入 + expected 标注 |
| 9.5.8 | 回放评测工具 | EvalRunner（mock 模式优先）产出结构指标报告落盘归档 |
| 9.5.9 | 流程固化 | prompt/预算参数改动必须跑评测对比基线的规则写入 eval/README |

## 三、功能细节

- **告警三则**：补偿积压 `vector_pending_ops_size > 0` 持续 1h；解析跳过率 10 分钟窗口占比 >30%；线程池 `increase(executor_rejected_total[5m]) > 0`。
- **数据集设计**：小（登录权限 2 需求）/中（订单库存 3 需求+状态流）/大（CMS 8 需求 14 接口）；合成内容已脱敏；每档附 fixture-response.json（mock 回放夹具，含故意畸形条目验证跳过统计）与 expected.json（requirements/endpoints/p0Points/requirementIdMap）。
- **EvalRunner**：mock 模式回放夹具→逐条 test-cases schema 校验→结构合格率/解析跳过率/需求召回（标题包含 + requirementIdMap 双口径）/接口覆盖率（coverageRefs.endpointIds）→报告落 `eval/results/{date}-{gitsha}.json`；`compare` 子命令对比基线，回归即退出码 2。

## 四、验收标准

1. promtool 校验规则通过；两看板 JSON 经 Grafana provisioning 无错误加载。
2. mock 模式端到端产出报告（本次归档：结构合格率/跳过率/召回/覆盖 全部健康线 PASS）。
3. compare 能识别指标回归并标 REGRESSION。

## 五、风险与缓解

| 风险 | 缓解 |
|---|---|
| 夹具故意畸形条目污染健康线 | `__malformed__` 显式标注剔除分母，跳过率只统计意外失败 |
| 召回判定误判 | requirementIdMap 精确 id 匹配为主口径，标题包含为兜底 |
| mock 报告被误当真实质量 | 报告带 mode=mock 字段；README 明示真实模式命令与 token 成本 |

## 六、交付物清单

monitoring 告警/看板 ×3 文件；eval/datasets ×9 文件 + README + results 首份报告；EvalRunner.java。9.5.10 真实回测留待用户执行（需消耗 LLM token）。
