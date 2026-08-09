# PRD v1.5 — 可视化增强

**版本**: v1.5（迭代版本）
**基线**: v1.4（生成质量增强II & 批量操作）
**日期**: 2026-08-09
**迭代主题**: 覆盖率矩阵可视化 + 状态机覆盖图 + 前端 chunk 拆分

---

## 一、迭代背景

### 1.1 痛点分析

| 编号 | 痛点 | 现状 | 影响 |
|------|------|------|------|
| P1 | 覆盖率只有进度条，无矩阵视图 | 覆盖率面板仅显示百分比进度条 | 无法快速定位"哪个状态转换没被覆盖" |
| P2 | 状态机图只在用例详情内展示 | StateMachineViewer 嵌在 TestCaseCard 中，无独立概览页 | 浏览全部状态机需要逐个打开用例 |
| P3 | 状态机图未标注覆盖状态 | 图中节点和边不区分"已覆盖/未覆盖" | 无法直观看出测试盲区 |
| P4 | 前端单 chunk 2.2MB | vite.config.js 无 manualChunks 配置 | 首屏加载慢，构建警告 chunk 过大 |

### 1.2 v1.5 目标

1. **覆盖率矩阵**：表格/热力图展示每个状态转换的覆盖情况
2. **状态机覆盖图**：独立页面展示状态机图，节点/边标注覆盖状态（绿=已覆盖，红=未覆盖）
3. **前端 chunk 拆分**：echarts/element-plus/vendor 分离，消除 500KB 警告

### 1.3 路线位置

```
v1.0~v1.4 已完成
v1.5 可视化增强                ◀── 本次迭代
v1.6 高可用（LLM并发控制/错误详情/日志结构化）（未来）
v2.0 AI 用例执行引擎             （未来）
```

---

## 二、范围

### 2.1 In Scope

| 编号 | 改动 | 优先级 |
|------|------|--------|
| F1 | 后端：覆盖率矩阵接口（每个状态转换的覆盖详情） | P0 |
| F2 | 前端：覆盖率矩阵组件（表格+热力色） | P0 |
| F3 | 前端：状态机覆盖图页面（独立路由+覆盖标注） | P0 |
| F4 | 前端：vite chunk 拆分配置 | P0 |
| F5 | 文档：PRD + 前后端技术评审 + CHANGELOG + README | P0 |

### 2.2 Out of Scope

- ❌ AI 执行（v2.0）
- ❌ 高可用/并发控制（v1.6）

---

## 三、功能详述

### 3.1 覆盖率矩阵接口（F1）

后端新增 `GET /api/projects/{projectId}/coverage/matrix` 接口，返回：

```json
{
  "stateMachines": [
    {
      "id": "SM-001",
      "name": "订单状态机",
      "transitions": [
        { "from": "NONE", "to": "PENDING_PAYMENT", "trigger": "create", "covered": true, "testCaseIds": ["TC-001", "TC-003"] },
        { "from": "PENDING_PAYMENT", "to": "PAID", "trigger": "pay", "covered": true, "testCaseIds": ["TC-002"] },
        { "from": "PAID", "to": "CANCELLED", "trigger": "cancel", "covered": false, "testCaseIds": [] }
      ]
    }
  ],
  "summary": { "totalTransitions": 15, "coveredTransitions": 10, "rate": 0.667 }
}
```

### 3.2 覆盖率矩阵组件（F2）

在 TestCaseList 的覆盖率面板下方新增矩阵表格：

- 每行一个状态转换（from → to / trigger）
- 列：状态机名称、From、To、Trigger、覆盖状态（绿色✓/红色✗）、关联用例数
- 未覆盖行高亮红色背景
- 点击关联用例数可跳转到筛选后的用例列表

### 3.3 状态机覆盖图页面（F3）

新增路由 `/projects/:id/state-machines`，页面内容：

- 左侧：状态机列表选择（el-select 或 el-tabs）
- 右侧：ECharts 力导向图，节点和边标注覆盖状态
  - 已覆盖的边：绿色实线
  - 未覆盖的边：红色虚线
  - 已覆盖的节点：绿色边框
  - 未覆盖的节点：灰色边框
- 底部：覆盖率统计摘要

### 3.4 前端 chunk 拆分（F4）

vite.config.js 新增 `build.rollupOptions.output.manualChunks`：

```js
build: {
  chunkSizeWarningLimit: 600,
  rollupOptions: {
    output: {
      manualChunks: {
        'echarts': ['echarts'],
        'element-plus': ['element-plus', '@element-plus/icons-vue'],
        'vendor': ['vue', 'vue-router', 'pinia', 'axios']
      }
    }
  }
}
```

---

## 四、验收标准

| 编号 | 验收项 | 验证方式 |
|------|--------|----------|
| AC1 | 调用 coverage/matrix 接口返回每个转换的覆盖详情 | API 验证 |
| AC2 | 覆盖率矩阵表格展示，未覆盖行红色高亮 | 页面验证 |
| AC3 | 状态机覆盖图页面可切换状态机，图上标注覆盖状态 | 页面验证 |
| AC4 | 前端构建无 500KB 警告，chunk 拆分为 3+ 个 | 构建验证 |
| AC5 | 后端编译通过，前端构建通过 | 构建 |

---

## 五、交付物清单

- [ ] `docs/v1.5/PRD_v1.5_可视化增强.md`
- [ ] `docs/v1.5/后端技术评审_v1.5.md`
- [ ] `docs/v1.5/前端技术评审_v1.5.md`
- [ ] 后端：CoverageController + CoverageService 矩阵接口
- [ ] 前端：CoverageMatrix.vue + StateMachineOverview.vue + 路由 + vite 配置
- [ ] CHANGELOG + README 更新
