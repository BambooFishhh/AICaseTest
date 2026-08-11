# PRD v3.8 — 树状用例列表

**日期**: 2026-08-11
**基线**: v3.7
**主题**: 将扁平用例表格改为树状结构——按模块分组 + 前置条件/步骤/预期结果直接显示在列中

---

## 1. 背景与痛点

### 1.1 现状

v3.6 为 el-table 添加了 `type="expand"` 展开行，显示前置条件/步骤/预期结果。但用户反馈：

1. **看不到详情**：展开箭头（左侧小三角）不明显，用户不知道点击哪里展开
2. **看不出模块分组**：扁平列表无法直观看出哪些用例属于同一模块

### 1.2 痛点

| 问题 | 影响 |
|------|------|
| 展开行不明显 | 用户不知道有详情可看 |
| 扁平列表无分组 | 同模块用例分散，难以批量查看 |
| 前置条件/步骤/预期结果隐藏 | 必须点击展开才能看到，体验差 |

---

## 2. 范围

### 2.1 In Scope

| # | 改动 | 说明 |
|---|------|------|
| 1 | el-table 树状结构 | 按模块分组，模块为父节点，用例为子节点 |
| 2 | 详情列直接显示 | 前置条件/步骤/预期结果以摘要形式直接显示在列中 |
| 3 | 加载全部用例 | 移除分页，加载全部用例用于树状分组 |
| 4 | 模块行样式 | 模块行加粗/背景色区分 |
| 5 | 行点击查看详情 | 点击用例行打开 TestCaseCard 详情对话框 |

### 2.2 Out of Scope

- 不修改后端 API（数据已包含 module 字段和 preconditions/steps/expectedResults）
- 不修改 TestCaseCard 详情对话框

---

## 3. 技术方案

### 3.1 树状数据结构

将 `displayTestCases` 转换为树状数据：

```javascript
const treeData = computed(() => {
  const cases = displayTestCases.value
  const moduleMap = new Map()
  cases.forEach(tc => {
    const mod = tc.module || '未分类'
    if (!moduleMap.has(mod)) moduleMap.set(mod, [])
    moduleMap.get(mod).push(tc)
  })
  const tree = []
  moduleMap.forEach((children, mod) => {
    tree.push({
      id: `module-${mod}`,
      isModule: true,
      title: `${mod} (${children.length}条)`,
      module: mod,
      children
    })
  })
  return tree
})
```

### 3.2 el-table 树状配置

```html
<el-table
  :data="treeData"
  row-key="id"
  :tree-props="{ children: 'children' }"
  :row-class-name="rowClassName"
  @row-click="handleRowClick"
>
```

### 3.3 详情列摘要显示

新增 3 列，直接显示摘要：

```html
<el-table-column label="前置条件" width="200">
  <template #default="{ row }">
    <span v-if="row.isModule" class="module-cell"></span>
    <span v-else-if="row.preconditions?.length" class="detail-summary">
      {{ row.preconditions[0] }}{{ row.preconditions.length > 1 ? ` (+${row.preconditions.length - 1})` : '' }}
    </span>
    <span v-else class="text-muted">无</span>
  </template>
</el-table-column>
```

步骤和预期结果同理。

### 3.4 模块行样式

```javascript
function rowClassName({ row }) {
  if (row.isModule) return 'module-row'
  return ''
}
```

```css
.module-row {
  font-weight: bold;
  background-color: var(--el-fill-color-light);
}
```

### 3.5 移除分页

- `loadList()` 改为 `pageSize: 9999` 加载全部
- 隐藏分页组件（`v-if="!streaming"` → 直接隐藏，或保留但默认全部）
- 流式生成期间仍用 `streamedCases`

---

## 4. 验收标准

| # | 标准 |
|---|------|
| 1 | 用例列表按模块分组，模块为可展开的父行 |
| 2 | 前置条件/步骤/预期结果直接显示在列中（无需展开） |
| 3 | 模块行有明显视觉区分（加粗/背景色） |
| 4 | 点击用例行打开详情对话框 |
| 5 | 流式生成期间树状结构正常更新 |
| 6 | 筛选（类型/优先级/关键字）正常工作 |
| 7 | 前端构建通过 |

---

## 5. 交付物清单

- [ ] `docs/v3.8/PRD_v3.8_树状用例列表.md`
- [ ] `docs/v3.8/前端技术评审_v3.8.md`
- [ ] `frontend/src/views/TestCaseList.vue` — 树状表格 + 详情列
- [ ] `docs/CHANGELOG.md` — v3.8 章节
- [ ] `README.md` — v3.8 章节
