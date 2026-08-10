# PRD v3.1 — 目录选择器 + 界面优化

**版本**: v3.1
**基线**: v3.0
**日期**: 2026-08-10
**主题**: 创建项目时支持可视化目录选择器（替代手动输入路径），并用 Element Plus 优化表单界面

---

## 1. 背景与痛点

v3.0 将 sourcePath 改为可选后，用户创建项目时仍需手动输入/粘贴代码路径。痛点：
1. **路径输入易错**：手动输入路径容易拼错，且无法直观看到目录结构
2. **体验不够友好**：其他 IDE/工具通常提供目录浏览功能，当前纯文本输入体验落后
3. **表单布局粗糙**：创建项目表单的间距、对齐、视觉层次可以进一步优化

## 2. 范围

### In Scope

- 后端：新增 `FilesystemController`，提供 `GET /api/filesystem/dirs?path=xxx` 列出子目录
- 前端：新增 `DirSelector.vue` 组件（el-popover + el-tree 懒加载目录树）
- 前端：`ProjectCreate.vue` 项目路径输入框旁加"浏览"按钮，点击弹出目录选择器
- 前端：表单界面优化（卡片布局、间距、按钮样式）

### Out of Scope

- 不做文件选择（只选目录）
- 不做权限控制（本地工具，信任用户操作）
- 不改动其他页面（只优化创建项目页面）

## 3. 功能详情

### 3.1 后端目录列表 API

`GET /api/filesystem/dirs?path=xxx`

- `path` 为空：返回系统根盘符列表（Windows: `C:\`, `D:\` 等）
- `path` 非空：返回该路径下的子目录列表（仅目录，不含文件）
- 返回格式：`[{ name: "src", path: "C:\\project\\src", isLeaf: false }]`
- 安全：规范化路径，拒绝含 `..` 的遍历攻击

### 3.2 前端目录选择器组件（DirSelector.vue）

- el-popover 触发方式：点击"浏览"按钮
- 内容：el-tree 懒加载（点击展开节点时请求子目录）
- 选中目录节点后 emit `select` 事件，回填路径到输入框
- 支持"返回上级"按钮

### 3.3 创建表单优化

- 项目路径输入框 + "浏览"按钮（el-input 带 append button）
- 表单卡片增加阴影和圆角
- 按钮间距和对齐优化
- 来源类型为"无代码"时隐藏路径输入框（v3.0 已实现）

## 4. 验收标准

- [x] 点击"浏览"按钮弹出目录树
- [x] 目录树可懒加载展开子目录
- [x] 选中目录后路径回填到输入框
- [x] 手动输入路径仍然可用
- [x] 后端 `mvn compile` + 前端 `npm run build` 通过

## 5. 交付物清单

- [ ] `docs/v3.1/PRD_v3.1_目录选择器与界面优化.md`
- [ ] `docs/v3.1/后端技术评审_v3.1.md`
- [ ] `docs/v3.1/前端技术评审_v3.1.md`
- [ ] 后端 `FilesystemController.java`
- [ ] 前端 `DirSelector.vue` + `api/filesystem.js`
- [ ] 前端 `ProjectCreate.vue` 改造
- [ ] CHANGELOG + README 更新
