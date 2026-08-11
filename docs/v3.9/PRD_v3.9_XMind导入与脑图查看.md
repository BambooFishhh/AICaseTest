# PRD v3.9 — XMind 导入 + 脑图查看入口

**日期**: 2026-08-12
**基线**: v3.8
**主题**: 移除 JSON/CSV 导入导出，改为 XMind 导入（逆向解析）+ 生成脑图后查看入口

---

## 1. 背景与痛点

### 1.1 现状

- v1.7 添加了 JSON/CSV 导入导出 + 跨项目复制功能
- 脑图生成后仅显示成功提示，无法直接查看
- 用户希望：去掉 JSON/CSV 导入导出，只保留 XMind 导入；生成脑图后能查看

### 1.2 用户需求

1. **去掉** 导出JSON、导出CSV、导入JSON、复制到 按钮
2. **新增** 导入 XMind 功能（上传 .xmind 文件 → 逆向解析 → 生成用例）
3. **新增** 生成脑图后显示"查看脑图"按钮，点击跳转到脑图预览页

---

## 2. 范围

### 2.1 In Scope

| # | 改动 | 说明 |
|---|------|------|
| 1 | 后端：XMind 逆向解析 | XmindService 新增 `parseXmind(MultipartFile)` → List<TestCase> |
| 2 | 后端：导入端点 | TestCaseController 新增 `POST /import-xmind`（multipart） |
| 3 | 前端：移除旧按钮 | 删除导出JSON/导出CSV/导入JSON/复制到 + 对应函数 |
| 4 | 前端：导入XMind按钮 | 新增按钮 + 文件上传 + 调用导入API |
| 5 | 前端：查看脑图入口 | 生成脑图后显示"查看脑图"按钮 → router.push 跳转 |

### 2.2 Out of Scope

- 不修改 XMind 生成逻辑（导出选中保留）
- 不修改脑图预览页（MindMapPreview.vue）
- 不修改后端 JSON/CSV 导出接口（仅前端隐藏按钮，API 保留向后兼容）

---

## 3. 技术方案

### 3.1 XMind 逆向解析

XMind 文件结构（我们自己生成的格式）：

```
ZIP
├── content.json    ← 核心内容（JSON 数组）
├── metadata.json
└── manifest.json
```

content.json 树结构：

```
[{ rootTopic: { title: "项目名 测试用例", children: { attached: [
  { title: "模块名", children: { attached: [          ← 第2层：模块
    { title: "正向", children: { attached: [           ← 第3层：类型
      { title: "TC-001 用例标题", children: { attached: [  ← 第4层：用例
        { title: "前置条件", children: { attached: [item1, item2] } },
        { title: "测试步骤", children: { attached: [item1, item2] } },
        { title: "预期结果", children: { attached: [item1, item2] } }
      ]}}
    ]}}
  ]}}
]}}]
```

解析步骤：
1. `ZipInputStream` 解压 → 找到 `content.json` entry
2. `ObjectMapper.readTree()` 解析 JSON
3. 遍历 `rootTopic.children.attached` → 模块节点
4. 遍历模块 `children.attached` → 类型节点
5. 遍历类型 `children.attached` → 用例节点
6. 用例 `title` 按首个空格拆分 → id + title
7. 用例 `children.attached` → 详情节点（前置条件/测试步骤/预期结果）
8. 详情 `title` 匹配 "前置条件"/"测试步骤"/"预期结果" → 对应字段
9. 详情 `children.attached` → 具体条目列表

### 3.2 查看脑图入口

生成脑图成功后，在工具栏显示"查看脑图"按钮，点击 `router.push(/projects/${projectId}/mindmap)` 跳转到已有的 MindMapPreview 页面。

---

## 4. 验收标准

| # | 标准 |
|---|------|
| 1 | 导出JSON/导出CSV/导入JSON/复制到 按钮已移除 |
| 2 | 导入XMind按钮可见，上传 .xmind 文件后用例列表刷新 |
| 3 | 生成脑图后出现"查看脑图"按钮 |
| 4 | 点击"查看脑图"跳转到脑图预览页 |
| 5 | 后端编译 + 前端构建通过 |

---

## 5. 交付物清单

- [ ] `XmindService.java` — 新增 parseXmind 方法
- [ ] `TestCaseService.java` — 新增 importFromXmind 方法
- [ ] `TestCaseController.java` — 新增 POST /import-xmind 端点
- [ ] `TestCaseList.vue` — 移除旧按钮 + 新增导入XMind/查看脑图
- [ ] `api/testcase.js` — 新增 importXmind 函数
- [ ] 文档 + CHANGELOG + README
