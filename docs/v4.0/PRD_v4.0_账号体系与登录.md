# PRD v4.0 — 账号体系与登录

**版本**：v4.0
**基线**：v3.18
**日期**：2026-08-12
**主题**：用户注册/登录 + JWT 认证 + 数据归属隔离 + 存量迁移与默认管理员

## 一、背景与痛点

1. 系统完全无认证：任何人都能访问所有 API 与数据，多用户场景不可用
2. 项目/用例/执行记录无归属概念，无法区分"谁的项目"
3. 系统设置含 LLM API Key，当前任何人可读写
4. 存量数据需要平滑迁移，不能丢失

## 二、范围

### In scope

- 用户体系：User 实体、注册/登录/当前用户接口，BCrypt 加密
- JWT 认证：签发/解析/过期；SecurityConfig 放行规则
- 数据归属：Project.userId + 全项目级接口越权校验（ProjectAccessService）
- 权限边界：/api/settings/**、/api/stats/** 仅 ADMIN
- 存量迁移：默认管理员初始化、存量项目归属迁移
- 前端：登录/注册页、authStore、axios 拦截器、路由守卫、用户菜单、按角色隐藏入口

### Out of scope

- 登录防爆破、密码策略、token 刷新、审计完善（v4.1）
- 线程池与并发治理（v4.2）

## 三、功能详情

### 3.1 后端

- `User`（id/username 唯一/passwordHash/displayName/role/createdAt/updatedAt）
- `POST /api/auth/register`：用户名唯一、密码 ≥6 位，成功即返回 token（自动登录）
- `POST /api/auth/login`：校验密码，返回 `{token, user}`
- `GET /api/auth/me`：返回当前用户
- `JwtAuthFilter`：Bearer 解析 → SecurityContext（ROLE_USER/ROLE_ADMIN）
- `SecurityConfig`：放行 auth/health/swagger；其余认证；settings/stats 仅 ADMIN；无状态
- `Project.userId`；`ProjectAccessService.assertProjectAccess(projectId)`：非 ADMIN 只能访问自己的项目，覆盖项目/用例/执行/脑图/覆盖率/测试集/备份等接口
- `DataInitializer`：无用户 → 创建 admin（密码 `APP_ADMIN_PASSWORD` 默认 admin123）；存量项目 userId 为空 → 归 admin

### 3.2 前端

- `/login`、`/register` 页面（居中卡片 + 系统设计风格）
- `authStore`：token/user，localStorage 持久化；login/register/logout/fetchMe
- `request.js`：请求带 `Authorization: Bearer`；401 → 清 token 跳 `/login`
- 路由守卫：未登录访问受保护页 → `/login?redirect=...`
- App 顶栏用户菜单（头像/用户名/退出）；仪表盘、系统设置仅 ADMIN 显示

## 四、验收标准

1. 注册/登录/退出闭环可用；刷新页面登录态保持
2. 未登录访问任何业务接口返回 401；前端自动跳登录
3. 用户只能看到/操作自己的项目；ADMIN 可访问全部
4. 存量项目迁移到默认管理员，数据不丢失
5. 设置与仪表盘仅 ADMIN 可访问
6. mvn test + npm run build 通过

## 五、风险与规避

| 风险 | 影响 | 规避 |
|---|---|---|
| 破坏性变更 | 旧调用全部失效 | 前端 axios 统一加 token；文档注明 |
| 默认管理员密码弱 | 安全风险 | 初始密码可配置，v4.1 强制改密 |
| 存量数据归属 | 老项目看不到 | DataInitializer 归默认管理员并提示 |

## 六、交付物清单

- [x] PRD + 前后端评审
- [ ] 后端：用户/JWT/权限/归属/迁移
- [ ] 前端：登录注册/拦截器/守卫/用户菜单
- [ ] CHANGELOG / README + 提交推送
