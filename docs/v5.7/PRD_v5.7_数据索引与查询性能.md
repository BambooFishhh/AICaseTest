# v5.7 PRD：数据索引与查询性能

## 1. 迭代背景与痛点

- MySQL 只有 V1 基线索引，执行历史、批次状态、用例模块等高频查询缺少复合索引。
- 执行历史接口一次性返回全部记录，项目数据量大后响应变慢。
- MySQL 连接池使用默认值，未针对后端异步执行场景调优。
- Milvus 集合未创建 ANN 索引，检索退化为全量暴力扫描。

## 2. 范围（In / Out of scope）

### In scope

- Flyway V2 复合索引：执行历史时间、批次状态、用例模块、版本序号、分析/脑图时间。
- 执行历史分页：后端 `page/pageSize` + 前端分页器；统计与趋势由后端一次性返回。
- MySQL Hikari 连接池参数化。
- Milvus `createIndex(IVF_FLAT)` + `loadCollection`。

### Out of scope

- 数据保留策略与治理 API（v5.8）。

## 3. 功能详情

### 3.1 Flyway V2 索引

`V2__add_performance_indexes.sql`：

| 表 | 索引 | 场景 |
|---|---|---|
| execution_record | (project_id, start_time DESC) | 执行历史列表 |
| execution_record | (batch_id, status) | 批次状态统计 |
| test_cases | (project_id, module) | 模块筛选 |
| test_case_versions | (test_case_id, version_no) | 版本列表 |
| code_analysis | (project_id, created_at) | 最新分析 |
| mindmaps | (project_id, created_at) | 最新脑图 |

### 3.2 执行历史分页

`GET /api/projects/{projectId}/executions?page=1&pageSize=20` 返回：

```json
{
  "items": [...],
  "total": 100,
  "page": 1,
  "pageSize": 20,
  "stats": {"total":100,"passed":80,"failed":20,"running":0},
  "trend": [80, 85, ...]
}
```

前端表格只渲染当前页，统计卡与趋势图使用后端全量统计。

### 3.3 连接池

`application-mysql.yml` 增加 Hikari 参数：

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: ${HIKARI_MAX_POOL:20}
      minimum-idle: ${HIKARI_MIN_IDLE:5}
      connection-timeout: ${HIKARI_CONNECTION_TIMEOUT:30000}
      idle-timeout: 600000
      max-lifetime: 1800000
```

### 3.4 Milvus ANN 索引

- 集合创建后对 `embedding` 字段建 IVF_FLAT 索引（nlist=128）。
- `loadCollection` 让索引生效，检索走 ANN 而非暴力扫描。

## 4. 验收标准

1. `mvn compile` / `mvn test` 通过。
2. Flyway V2 在空 MySQL 上可成功执行。
3. 执行历史分页接口返回 items/total/stats/trend。
4. 前端分页切换、统计卡与趋势图正常。
5. `npm run build` 成功。

## 5. 风险与规避

| 风险 | 规避 |
|---|---|
| 索引名重复 | 前缀 idx_ 且全局唯一 |
| 分页破坏旧前端 | 前端同步改造，stats/trend 由后端计算 |
| Milvus SDK 索引 API 差异 | createIndex/loadCollection 失败仅告警，不影响检索 |

## 6. 交付物清单

- PRD / 后端评审 / 前端评审
- Flyway V2、执行历史分页、Hikari、Milvus 索引
