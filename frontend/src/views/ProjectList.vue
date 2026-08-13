<template>
  <div class="project-list page-container">
    <!-- 页头 -->
    <header class="page-header">
      <div class="page-header-main">
        <h1 class="page-title">项目列表</h1>
        <p class="page-subtitle">管理你的 AI 测试用例生成项目</p>
      </div>
      <div class="page-actions">
        <el-button type="primary" :icon="Plus" @click="goCreate">创建项目</el-button>
      </div>
    </header>

    <!-- 筛选区 -->
    <section class="filter-section">
      <el-input
        v-model="keyword"
        placeholder="搜索项目名称 / 源码路径"
        clearable
        :prefix-icon="Search"
        class="filter-search"
        @input="applyFilter"
        @clear="applyFilter"
      />
      <el-select
        v-model="statusFilter"
        placeholder="状态筛选"
        clearable
        class="filter-status"
        @change="applyFilter"
      >
        <el-option
          v-for="(text, key) in statusTextMap"
          :key="key"
          :label="text"
          :value="key"
        />
      </el-select>
      <span class="filter-count">共 {{ filteredProjects.length }} 个项目</span>
    </section>

    <!-- 加载骨架屏 -->
    <div v-if="loading" class="skeleton-grid">
      <div v-for="i in 6" :key="i" class="skeleton-card">
        <div class="skeleton-line skeleton-line-lg"></div>
        <div class="skeleton-line skeleton-line-md"></div>
        <div class="skeleton-line skeleton-line-sm"></div>
      </div>
    </div>

    <!-- 空状态 -->
    <div v-else-if="projects.length === 0" class="empty-state">
      <el-icon :size="64" class="empty-icon"><FolderOpened /></el-icon>
      <h3 class="empty-title">暂无项目</h3>
      <p class="empty-desc">点击右上角"创建项目"开始使用 AI 生成测试用例</p>
      <el-button type="primary" :icon="Plus" @click="goCreate">立即创建</el-button>
    </div>

    <!-- 筛选无结果 -->
    <div v-else-if="filteredProjects.length === 0" class="empty-state">
      <el-icon :size="64" class="empty-icon"><Search /></el-icon>
      <h3 class="empty-title">没有匹配的项目</h3>
      <p class="empty-desc">试试调整关键词或状态筛选</p>
      <el-button @click="clearFilter">清除筛选</el-button>
    </div>

    <!-- 项目卡片网格 -->
    <div v-else class="project-grid">
      <article
        v-for="project in filteredProjects"
        :key="project.id"
        class="project-card"
        :class="`is-${project.status}`"
        @click="goDetail(project.id)"
      >
        <!-- 顶部色条 -->
        <div class="card-accent"></div>

        <!-- 卡片头部 -->
        <div class="card-head">
          <div class="card-avatar">
            <el-icon :size="22"><Folder /></el-icon>
          </div>
          <div class="card-info">
            <h3 class="card-name" :title="project.name">{{ project.name }}</h3>
            <div class="card-meta">
              <el-icon :size="12"><Clock /></el-icon>
              <span>{{ formatDate(project.createdAt) }}</span>
              <el-tag
                v-if="groupName(project.groupId)"
                size="small"
                type="info"
                effect="plain"
                class="group-tag"
              >
                {{ groupName(project.groupId) }}
              </el-tag>
              <el-tag
                v-if="project.accessLevel === 'VIEWER'"
                size="small"
                type="warning"
                effect="plain"
              >
                只读
              </el-tag>
            </div>
          </div>
          <span class="status-pill" :class="`status-${project.status}`">
            {{ statusText(project.status) }}
          </span>
        </div>

        <!-- 卡片内容 -->
        <div class="card-body">
          <div class="path-line" :title="project.sourcePath">
            <el-icon :size="13"><FolderOpened /></el-icon>
            <span class="path-text">{{ project.sourcePath || '纯 PRD 模式 · 无代码路径' }}</span>
          </div>
        </div>

        <!-- 卡片底部操作 -->
        <div class="card-foot" @click.stop>
          <el-button text :icon="Document" @click="goDetail(project.id)">查看详情</el-button>
          <el-popconfirm
            title="确定删除该项目吗？删除后不可恢复"
            confirm-button-text="删除"
            cancel-button-text="取消"
            confirm-button-type="danger"
            @confirm="handleDelete(project.id)"
          >
            <template #reference>
              <el-button text type="danger" :icon="Delete">删除</el-button>
            </template>
          </el-popconfirm>
        </div>
      </article>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Plus, Folder, FolderOpened, Clock, Delete, Document, Search } from '@element-plus/icons-vue'
import { listProjects, deleteProject } from '@/api/project'
import { listGroups } from '@/api/group'

const router = useRouter()
const projects = ref([])
const loading = ref(false)
const groups = ref([])
// 筛选
const keyword = ref('')
const statusFilter = ref('')

const filteredProjects = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  return projects.value.filter((p) => {
    if (statusFilter.value && p.status !== statusFilter.value) return false
    if (!kw) return true
    const name = (p.name || '').toLowerCase()
    const path = (p.sourcePath || '').toLowerCase()
    return name.includes(kw) || path.includes(kw)
  })
})

function groupName(groupId) {
  if (!groupId) return ''
  const g = groups.value.find((x) => x.id === groupId)
  return g ? g.name : ''
}

function applyFilter() {
  // 计算属性自动响应
}

function clearFilter() {
  keyword.value = ''
  statusFilter.value = ''
}

const statusTextMap = {
  created: '已创建',
  analyzing: '分析中',
  analyzed: '已分析',
  generating: '生成中',
  completed: '已完成',
  failed: '失败'
}

function statusText(status) {
  return statusTextMap[status] || status
}

function formatDate(val) {
  if (!val) return '-'
  const d = new Date(val)
  if (isNaN(d.getTime())) return val
  return d.toLocaleString('zh-CN', { hour12: false })
}

async function loadProjects() {
  loading.value = true
  try {
    const res = await listProjects()
    projects.value = res.data || []
  } finally {
    loading.value = false
  }
}

async function loadGroups() {
  try {
    const res = await listGroups()
    groups.value = res.data || []
  } catch {
    groups.value = []
  }
}

function goCreate() {
  router.push('/projects/create')
}

function goDetail(id) {
  router.push(`/projects/${id}`)
}

async function handleDelete(id) {
  await deleteProject(id)
  ElMessage.success('删除成功')
  loadProjects()
}

onMounted(() => {
  loadProjects()
  loadGroups()
})
</script>

<style scoped lang="scss">
.project-list {
  padding: var(--space-lg) var(--space-xl);
}

/* ===== 骨架屏 ===== */
.skeleton-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: var(--space-lg);
}

.skeleton-card {
  background: var(--bg-surface);
  border: 1px solid var(--card-border);
  border-radius: var(--radius-lg);
  padding: var(--space-lg);
  box-shadow: var(--shadow-xs);

  .skeleton-line {
    height: 14px;
    background: linear-gradient(90deg, #f1f5f9 25%, #e2e8f0 37%, #f1f5f9 63%);
    background-size: 400% 100%;
    animation: shimmer 1.4s ease infinite;
    border-radius: var(--radius-sm);
    margin-bottom: 12px;

    &.skeleton-line-lg { width: 60%; height: 20px; }
    &.skeleton-line-md { width: 80%; }
    &.skeleton-line-sm { width: 40%; }
  }
}

@keyframes shimmer {
  0% { background-position: 100% 50%; }
  100% { background-position: 0 50%; }
}

/* ===== 筛选区 ===== */
.filter-section {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  background: var(--bg-surface);
  border: 1px solid var(--card-border);
  border-radius: var(--radius-lg);
  padding: 12px 16px;
  margin-bottom: 20px;
  box-shadow: var(--shadow-xs);

  .filter-search {
    flex: 1;
    min-width: 220px;
    max-width: 420px;
  }

  .filter-status {
    width: 160px;
  }

  .filter-count {
    font-size: 13px;
    color: var(--text-tertiary);
    margin-left: auto;
  }
}

/* ===== 空状态 ===== */
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: var(--space-2xl) var(--space-lg);
  text-align: center;

  .empty-icon {
    color: var(--text-muted);
    margin-bottom: var(--space-md);
  }

  .empty-title {
    font-size: 18px;
    font-weight: 600;
    color: var(--text-secondary);
    margin-bottom: var(--space-xs);
  }

  .empty-desc {
    color: var(--text-tertiary);
    margin-bottom: var(--space-lg);
  }
}

/* ===== 项目卡片网格 ===== */
.project-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: var(--space-lg);
}

.project-card {
  position: relative;
  background: var(--bg-surface);
  border: 1px solid var(--card-border);
  border-radius: var(--radius-lg);
  padding: var(--space-lg);
  cursor: pointer;
  transition: all var(--transition-normal);
  overflow: hidden;
  display: flex;
  flex-direction: column;
  gap: var(--space-md);
  animation: fadeInUp var(--transition-normal) backwards;

  &:hover {
    border-color: var(--brand-primary-lighter);
    box-shadow: var(--shadow-lg);
    transform: translateY(-2px);

    .card-accent {
      opacity: 1;
    }
  }

  .card-accent {
    position: absolute;
    top: 0;
    left: 0;
    right: 0;
    height: 3px;
    background: var(--brand-gradient);
    opacity: 0.85;
    transition: opacity var(--transition-fast);
  }

  /* 按状态着色顶部色条 */
  &.is-created .card-accent { background: linear-gradient(90deg, #94a3b8, #64748b); }
  &.is-analyzing .card-accent,
  &.is-generating .card-accent { background: linear-gradient(90deg, #fbbf24, #f59e0b); }
  &.is-analyzed .card-accent,
  &.is-completed .card-accent { background: linear-gradient(90deg, #34d399, #10b981); }
  &.is-failed .card-accent { background: linear-gradient(90deg, #f87171, #ef4444); }
}

.card-head {
  display: flex;
  align-items: flex-start;
  gap: var(--space-md);
}

.card-avatar {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  border-radius: var(--radius-md);
  background: var(--el-color-primary-light-9);
  color: var(--brand-primary);
  transition: all var(--transition-normal);

  .project-card:hover & {
    background: var(--brand-gradient);
    color: #fff;
    transform: scale(1.05);
  }
}

.card-info {
  flex: 1;
  min-width: 0;
}

.card-name {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-primary);
  margin: 0 0 4px 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.card-meta {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: var(--text-tertiary);
}

.status-pill {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  padding: 3px 10px;
  border-radius: var(--radius-full);
  font-size: 12px;
  font-weight: 500;

  &.status-created { color: #64748b; background: #f1f5f9; }
  &.status-analyzing, &.status-generating { color: #f59e0b; background: #fef3c7; }
  &.status-analyzed, &.status-completed { color: #10b981; background: #d1fae5; }
  &.status-failed { color: #ef4444; background: #fee2e2; }
}

.card-body {
  flex: 1;
}

.path-line {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--text-secondary);
  padding: 8px 12px;
  background: var(--bg-base);
  border-radius: var(--radius-md);

  .path-text {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    flex: 1;
  }
}

.card-foot {
  display: flex;
  justify-content: space-between;
  padding-top: var(--space-md);
  border-top: 1px dashed var(--card-border-light);
}

/* ===== 响应式 ===== */
@media (max-width: 768px) {
  .project-list {
    padding: var(--space-md);
  }

  .project-grid {
    grid-template-columns: 1fr;
    gap: var(--space-md);
  }
}
</style>
