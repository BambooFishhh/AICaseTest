# PRD v8.9.4 — 凭据卫生（票据作用域 + 媒体短票据 + 弱回退清零）

> 版本 v8.9.4，一旦确定尽量不要轻易改动。基线 v8.9.3。范围：计划书「阶段 6」任务 12.10（CR §9.5 N1/N2/N3）。含前端变更。

## 一、背景与痛点

- **N1**：SSE 票据存内存 Map——多实例下 A 实例签发 B 实例无效（水平扩展真实阻断点）；且 `JwtAuthFilter` 对**所有路径**接受 `?ticket=`，票据 TTL 内泄露可调用全部 API；
- **N2**：媒体资源 `?token=<12h 完整 JWT>` 曝给浏览器历史/访问日志/Referer；
- **N3**：DataInitializer 保留 `${app.admin.password:admin123}` 弱回退默认，时序变动会静默复活弱口令初始化。

## 二、范围与验收

| # | 内容 | 验收 |
|---|---|---|
| N1① | SseTicketService 双实现：Redis（SETEX 带 TTL）多实例互通 / 内存回落 | 单测：跨"实例"读取、过期 null、内存模式可用 |
| N1② | `?ticket=` 接受范围限定白名单（SSE 流式 + 媒体端点），其余路径忽略 | 集成测试：普通接口带 ticket 仍 401 |
| N2 | 媒体端点废弃 `?token=` 改走短票据；前端两处 URL 构造同步切换 | 媒体路径合法 ticket 通过认证层；旧 token 废弃期保持可用（WARN） |
| N3 | DataInitializer 移除回退默认 + 入口空值确定性抛错 | 单测：空密码启动失败并指明 APP_ADMIN_PASSWORD |

## 三、功能细节

- **票据 Redis 模式**：key=`sse:ticket:{ticket}`，值=`username|role`，SETEX 带 TTL（app.sse.ticket-ttl-seconds，默认 300s）；APP_REDIS_ENABLED=true 且模板可用时启用，否则内存 Map 回落（单实例语义）。TTL 内可复用兼容 EventSource 重连与媒体页面多次取图。
- **ticket 白名单**：URI 以 `/analyze-stream` 结尾或含 `generate-stream`（SSE 流式族）+ 媒体路径（/api/executions/*/video|file|report）。
- **媒体废弃期策略**：`?token=` 分支保留可用但每次 WARN（约束要求废弃期一周后再删分支，防存量链接直接断）；前端本次已全部切换。
- **前端**：execution.js 两函数改收 ticket 参数 + 新增 ensureMediaTicket()（复用 fetchSseTicket）；ExecutionResult.vue 页面加载即取票入 ref，媒体 computed 追加参数、就绪前渲染空不阻断页面。downloadAuth/openAuthPreview 走 Authorization 头无需改造。

## 四、交付物清单

SseTicketService 重构、JwtAuthFilter/DataInitializer 修改；api/execution.js 与 views/ExecutionResult.vue 修改；SseTicketServiceTest×3、SecurityApiIntegrationTest+3、DataInitializerAdminPasswordTest×1。
