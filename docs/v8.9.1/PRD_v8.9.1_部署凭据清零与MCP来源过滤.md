# PRD v8.9.1 — 部署层凭据清零 + MCP 来源过滤提层

> 版本 v8.9.1，一旦确定尽量不要轻易改动。基线 v8.8.2。
> 范围：计划书「阶段 6」任务 12.3 + 12.4（CR §9.3 C3，上线阻断双项）。
> 版本号说明：计划书原写"v8.10 起"，遵用户指示不开新大版本编号，阶段 6 沿用未占用的 v8.9 子序列。

## 一、背景（CR §9.3 C3）

v8.5 的 G1 治理只覆盖了应用层半栈：
1. **部署层弱默认残留**——compose 中 MySQL root/user、Redis、MinIO（minioadmin/minioadmin）、Milvus root、MCP bridge token 全部带弱默认值，公网部署可直接命中；
2. **MCP 来源控制层级太浅**——白名单校验仅在控制器层，且 `getRemoteAddr()` 在反代部署下是代理 IP，白名单形同虚设；`SecurityConfig` 对 `/api/mcp/**` permitAll 无过滤器层纵深。

## 二、范围

| # | 任务 | 内容 |
|---|---|---|
| 12.3 | compose 弱默认清零 | 业务密码类全部改 `:?` 必填（报错中文化）；MinIO 凭据必填；后端注入同步去默认 |
| 12.4 | MCP 来源白名单提层 | 新增 McpSourceFilter 置于 JwtAuthFilter 前；新增 `app.mcp.trust-proxy` 反代适配（XFF 首跳）；控制器层保留为第二道防线 |

## 三、功能细节

### 必填变量清单（12.3）

`MYSQL_ROOT_PASSWORD` / `MYSQL_PASSWORD` / `REDIS_PASSWORD` / `MILVUS_ROOT_PASSWORD` / `MINIO_ACCESS_KEY` / `MINIO_SECRET_KEY` / `MCP_BRIDGE_TOKEN` / `GRAFANA_ADMIN_PASSWORD`（已有）。健康检查与后端注入处的同名引用同步去默认。`.env.example` 补齐全部键并标注哪些为本版新增必填。

**存量数据卷兼容策略**：MySQL/Milvus 的凭据在数据目录首次初始化时固化，本机 `.env` 对齐存量卷现值（即原弱默认值）保证升级不破坏运行栈；`.env.example` 与 README 明示生产首次部署必须设置强值。

### MCP 过滤器（12.4）

- `security/McpSourceFilter`（OncePerRequestFilter，仅拦 `/api/mcp/**`）：解析客户端 IP（`app.mcp.trust-proxy=false` 时取 `getRemoteAddr()`；true 时取 `X-Forwarded-For` 首跳，注释警告仅可信反代可开）→ 回环 ∪ `APP_MCP_ALLOWED_REMOTE_ADDRS` 白名单外直接 403（code 40300 JSON），不进入控制器。
- 注册于 SecurityConfig `addFilterBefore(mcpSourceFilter, JwtAuthFilter.class)`；非 @Component 构造避免 Boot Servlet 自动重复注册。
- 控制器既有回环校验保留为第二道防线（IP 解析逻辑抽公共静态方法，两处同口径）；控制器注入同一 `trust-proxy` 配置保证代理场景两层判定一致。
- 配置三件套：`app.mcp.trust-proxy: ${APP_MCP_TRUST_PROXY:false}`。

## 四、验收标准

1. 未设置任一必填变量时 `docker compose config` 报错且信息明确；本地补齐后全栈健康。
2. CI compose 校验步骤同步注入全量占位值并通过。
3. 过滤器测试：非白名单 40300（过滤器层拦截）/ 白名单内错 token 仍 401（到达控制器）/ trust-proxy 开启时按 XFF 首跳判定 三例绿。
4. 全量回归绿；前端无变更。

## 五、风险与缓解

| 风险 | 缓解 |
|---|---|
| 存量卷凭据与新必填值不一致导致栈起不来 | 本机 .env 对齐存量值；README 标注生产首部署设强值 |
| CI compose 校验再次失败 | ci.yml 占位值集合与必填清单同步维护（放同处注释互相锚定） |
| trust-proxy 误开遭伪造 | 默认 false + 注释警告 + 仅反代场景开启说明 |

## 六、交付物清单

docker-compose.yml / .env.example / .env（本地）/ ci.yml 修改；security/McpSourceFilter 新增；SecurityConfig / McpBridgeController 修改；application.yml 一键；集成测试 +2~3 例。
