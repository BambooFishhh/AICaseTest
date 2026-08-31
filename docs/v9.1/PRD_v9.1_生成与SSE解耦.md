# PRD v9.1 — 生成与 SSE 解耦（断连不取消 + attach 续播）

> 版本 v9.1，一旦确定尽量不要轻易改动。基线 v9.0。范围：生成任务与 SSE 连接生命周期彻底解耦（计划书 12.11 的彻底方案，替代 v8.9.8 宽限机制）。

## 一、背景与痛点

- v8.9.8 的 12.11 采用"断连宽限 90s 后取消"——宽限期内重连可续，但超过宽限即误杀仍在跑的生成；刷新页面/切页超过 90s 生成被取消；
- 断连后重进页面无法看到生成进度，只能等终态。

## 二、范围与验收

| # | 内容 | 验收 |
|---|---|---|
| 1 | 断连/刷新**永不取消**生成（删宽限机制）；事件广播器多订阅者 | GenerationReattachTest |
| 2 | progress/case 事件持久化到 `agent_task_events` | 生成期逐事件落库 |
| 3 | 新端点 `generate-stream-attach`：回放持久化事件 + 无缝续播实况广播 | 重进页面进度无损恢复 |
| 4 | 前端 resumeGenerationIfActive 重写为 attach 模式 | ProjectDetail/testcase.js |

## 三、功能细节

- **广播器**：生成链路的事件（progress/case）发给 `bc.subscribers`（CopyOnWrite 语义的活跃连接列表）——发起连接是首个订阅者，attach 重接是后续订阅者；单个订阅者 send 失败仅摘除自身，不影响生成与他订阅者。
- **持久化**：progress 与 case 事件经 `AgentTaskService.recordGenerationEvent` 落 `agent_task_events`（phase=progress/case），跨实例可回放。
- **attach 端点**：`GET /{projectId}/testcases/generate-stream-attach`——任务 RUNNING 且广播器在内存：加入订阅者续播实况；任务终态/无任务/他实例：一次性回放 timeline 中的 progress+case 事件后按终态收尾。URI 含 "generate-stream" 自动进 SseTicket 白名单。
- **前端**：resumeGenerationIfActive 由"轮询项目状态"升级为 attach——重进页面立即回放历史事件并续播，完成后整表重载。

## 四、验收标准

1. GenerationReattachTest 6 例绿（分支路由：RUNNING 续播 / 终态回放 / 无任务收尾）。
2. 实测：生成中断网/刷新后重进页面，进度与用例草稿无损恢复，生成照常完成。

## 五、交付物清单

TestCaseService（+215）、ProjectController（attach 端点）、AgentTaskService.recordGenerationEvent；前端 testcase.js、ProjectDetail.vue；GenerationReattachTest 新增。
