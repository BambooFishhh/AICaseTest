# PRD v8.5 — 安全闭环

> 版本 v8.5，一旦确定尽量不要轻易改动。迭代范围：《长期迭代计划书》阶段 1 任务 8.1–8.6，消灭差距 G1（凭据治理）并收敛 DNS rebinding 残留风险。
> 基线版本：v8.4。

## 一、背景与痛点

长期迭代计划书评估当前生产就绪度 6/10，首要短板是 G1 凭据治理：弱默认密钥只靠 prod profile 的 `ProductionGuard` 门禁兜底，非 prod profile 部署公网即失守。同时 v8.4 代码审查已记录两处已知残留：

1. **DNS rebinding TOCTOU 窗口**——`GitCloneService` 与 `PrdAgent` 在"校验时解析安全 → 连接时再次解析"之间存在换记录攻击面；
2. **MCP 桥接接口仅靠静态 token**——`/api/mcp/**` 在 Spring Security 层 permitAll，token 是唯一因子，未限制来源地址；
3. **Grafana compose 默认 admin/admin**——忘记改 `.env` 时监控面板直接裸奔；
4. **前端未消费 `retryReset`**——v8.4 后端已在流式重试前推送清态事件，前端不消费导致重推瞬间草稿卡片短暂重复；
5. **安防能力缺少回归测试固化**——白名单、路径逃逸等既有能力无集成测试防退化。

## 二、范围

### In Scope

| # | 任务 | 内容 |
|---|---|---|
| 8.1 | 弱默认密钥清零 | 四处 yml 默认值移除；新增全 profile 启动校验组件，密钥缺失启动失败且指明缺哪个环境变量 |
| 8.2 | Grafana 凭据纳管 | compose 层 `GF_SECURITY_ADMIN_PASSWORD` 改为必填无默认 |
| 8.3 | MCP 桥接回环限制 | `/api/mcp/**` 仅接受回环来源（token 降为第二因子）；支持 `app.mcp.allowed-remote-addrs` 白名单覆盖反代场景 |
| 8.4 | DNS rebinding 收敛 | 抽取公共 `SafeDnsResolver`；Git 克隆与 URL 抓取统一走"双解析一致性 + 全 A 记录内网判定" |
| 8.5 | 前端消费 retryReset | SSE 事件接线 + 清空草稿列表 + Vitest 测试 |
| 8.6 | 安全集成测试补齐 | MCP 无 token/错 token/非回环三例 + Filesystem 白名单越权断言 + sourcePath 逃逸断言 |

### Out of Scope

- 生产部署形态改造（K8s/网关鉴权）
- MCP token 票据化（计划书 11.3，开放 OpenAPI 前强制）
- 追踪体系（9.5.6）、一致性闭环（阶段 2）

## 三、功能细节

### 3.1 密钥缺失即启动失败（8.1）

- `app.jwt.secret` / `app.admin.password` / `app.milvus.password` / `app.mcp.bridge-token` 四键 yml 占位符去掉默认值。
- 新增 `SecurityKeyGuard`（config 包）：所有 profile 生效，容器刷新期校验四键非空，缺失抛 `IllegalStateException` 并逐项指明环境变量名。
- `ProductionGuard` 现有 prod 强度检查（长度/默认值）原样保留，形成"全 profile 必填 + prod 强度"两层门禁。
- `.env.example` 补齐四键示例（含新增的 `MCP_BRIDGE_TOKEN`）。

### 3.2 MCP 回环限制（8.3）

- 校验顺序：**来源地址 → token**（非回环即使携带正确 token 也返回 40300）。
- 回环判定：`127.*`、`::1`、`0:0:0:0:0:0:0:1`。
- 反代场景逃生口：`APP_MCP_ALLOWED_REMOTE_ADDRS`（逗号分隔 IP 白名单，默认空 = 仅回环）。默认行为保守，无需部署动作。

### 3.3 SafeDnsResolver（8.4）

- 统一组件：解析全部 A 记录 → 任一回环/私网/链路本地/通配地址即拒绝；再执行第二次解析比对一致性，两轮结果集不同或任一轮含内网地址均拒绝。
- 接入点：`GitCloneService`（http/https/git 协议克隆）与 `PrdAgent.validatePublicUrl`（URL 正文抓取，含重定向每一跳）。
- 取舍说明：git 进程内部连接仍由 OS 解析一次，无法在 JVM 内钉死 IP（需 hosts 注入或代理，复杂度超预期），采用计划书授权的轻量方案，TOCTOU 窗口从"分钟级人工配置"收窄到"秒级 TTL 轮换"，残留窗口注释说明。
- ssh/git@ 地址维持现状不拦截（企业堡垒机场景）。

### 3.4 前端 retryReset（8.5）

- `streamGenerate` / `streamGenerateAppend` 新增 `onRetryReset` 回调接线。
- 生成页收到事件后清空草稿列表、进度文案切换为重试提示，后续重推不再叠加旧草稿。

## 四、验收标准

1. 清空 `.env` 中任一关键钥后端启动失败，错误信息指明缺失的环境变量名；补回后恢复。
2. 未设置 `GRAFANA_ADMIN_PASSWORD` 时 `docker compose config` 直接报错。
3. MCP 集成测试覆盖"回环+正确 token 通过 / 回环+错 token 401 / 非回环+正确 token 40300"三例。
4. SafeDnsResolver 单测覆盖"首轮内网拒绝 / 双轮结果不一致拒绝 / 稳定公网放行"。
5. 前端 Vitest 模拟 `case→case→retryReset→case` 事件序列，retryReset 回调被触发且时序正确。
6. 安全集成测试新增 ≥6 条全绿；后端全量回归绿；前端 build 绿。

## 五、风险与缓解

| 风险 | 缓解 |
|---|---|
| 移除 yml 默认值后本地/测试环境启动失败 | 根目录 `.env` 为唯一密钥来源（spring-dotenv 自动加载），`.env.example` 同步补齐；本机 `.env` 先自查补键再部署 |
| 反代部署下 MCP 桥接被封 | `app.mcp.allowed-remote-addrs` 显式白名单逃生口，默认不影响现有本机部署 |
| 双解析引入 DNS 查询延迟 | 仅 Git 克隆与 URL 抓取两个低频入口；探测间隔可注入为 0 供测试 |
| 前端误清用户状态 | retryReset 仅清流式草稿缓冲区（`streamedCases`），不动落库数据与统计 |

## 六、交付物清单

- [ ] 后端：SecurityKeyGuard / SafeDnsResolver 新增；application.yml、McpBridgeController、GitCloneService、PrdAgent、docker-compose.yml、.env.example 修改
- [ ] 前端：api/testcase.js、views/TestCaseList.vue 修改
- [ ] 测试：SecurityKeyGuardTest、SafeDnsResolverTest 新增；McpBridgeControllerTest 扩充；SecurityApiIntegrationTest 扩充；frontend testcase.test.js 新增
- [ ] 文档：CHANGELOG / README / 本目录三份评审文档 / 长期迭代计划书状态列
