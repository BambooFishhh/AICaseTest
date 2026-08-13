<template>
  <div class="project-detail page-container" v-loading="loading">
    <!-- 页头 -->
    <header class="page-header">
      <div class="page-header-main">
        <el-button text :icon="ArrowLeft" @click="goList">返回列表</el-button>
        <div class="title-block">
          <h1 class="page-title">{{ project?.name || '项目详情' }}</h1>
          <span v-if="project" class="status-pill" :class="`status-${project.status}`">
            <i class="status-dot"></i>{{ statusText(project.status) }}
          </span>
        </div>
      </div>
    </header>

    <template v-if="project">
      <!-- 流程步骤条 -->
      <section class="flow-section">
        <div class="flow-steps">
          <div
            v-for="(step, idx) in flowSteps"
            :key="step.key"
            class="flow-step"
            :class="{
              active: idx === activeStep,
              done: idx < activeStep,
              pending: idx > activeStep
            }"
          >
            <div class="step-marker">
              <el-icon v-if="idx < activeStep" :size="18"><Check /></el-icon>
              <span v-else>{{ idx + 1 }}</span>
            </div>
            <div class="step-info">
              <div class="step-title">{{ step.title }}</div>
              <div class="step-desc">{{ step.desc }}</div>
            </div>
            <div v-if="idx < flowSteps.length - 1" class="step-connector"></div>
          </div>
        </div>
      </section>

      <!-- 基本信息 -->
      <section class="info-section">
        <div class="section-head">
          <h2 class="section-title">基本信息</h2>
        </div>
        <div class="info-grid">
          <div class="info-item">
            <div class="info-label">项目名称</div>
            <div class="info-value">{{ project.name }}</div>
          </div>
          <div class="info-item">
            <div class="info-label">源码路径</div>
            <div class="info-value mono">{{ project.sourcePath || '纯 PRD 模式' }}</div>
          </div>
          <div class="info-item">
            <div class="info-label">创建时间</div>
            <div class="info-value">{{ formatDate(project.createdAt) }}</div>
          </div>
          <div class="info-item tech-stack-item">
            <div class="info-label">技术栈</div>
            <div class="info-value">
              <div v-if="techStackList.length" class="tech-tags">
                <span v-for="tech in visibleTechStack" :key="tech" class="tech-tag">{{ tech }}</span>
                <el-button
                  v-if="techStackList.length > MAX_TECH_VISIBLE"
                  link
                  size="small"
                  class="tech-more"
                  @click="techExpanded = !techExpanded"
                >
                  {{ techExpanded ? '收起' : `全部 ${techStackList.length} 个` }}
                </el-button>
              </div>
              <span v-else class="text-muted">尚未分析</span>
            </div>
          </div>
        </div>
      </section>

      <!-- PRD 面板 -->
      <PrdPanel :project-id="projectId" />

      <!-- 操作面板 -->
      <section class="actions-section">
        <div class="section-head">
          <h2 class="section-title">操作</h2>
        </div>
        <div class="action-grid">
          <!-- 主线操作 -->
          <div class="action-card primary-action">
            <div class="action-card-head">
              <el-icon :size="18"><Operation /></el-icon>
              <span class="action-card-title">主线操作</span>
            </div>
            <div class="action-buttons">
              <el-button
                type="primary"
                :icon="Aim"
                :disabled="!canAnalyze"
                :title="!hasSourcePath ? '未配置代码路径，可直接用 PRD 生成用例' : ''"
                @click="handleAnalyze"
              >
                开始分析
                <span v-if="!hasSourcePath" class="optional-tag">可选</span>
              </el-button>
              <el-button
                type="primary"
                :icon="MagicStick"
                :disabled="!canGenerate"
                :title="generateBlockedReason"
                @click="handleGenerate"
              >
                生成用例
              </el-button>
              <el-button
                type="primary"
                :icon="Share"
                :disabled="!canMindmap"
                @click="handleMindmap"
              >
                生成脑图
              </el-button>
              <el-button
                :icon="Download"
                :disabled="!canDownload"
                @click="handleDownload"
              >
                下载脑图
              </el-button>
            </div>
          </div>

          <!-- 查看操作 -->
          <div class="action-card">
            <div class="action-card-head">
              <el-icon :size="18"><View /></el-icon>
              <span class="action-card-title">查看</span>
            </div>
            <div class="action-buttons">
              <el-button :icon="DataAnalysis" :disabled="!canViewAnalysis" @click="goAnalysis">
                查看分析
              </el-button>
              <el-button :icon="Document" :disabled="!canViewTestcases" @click="goTestcases">
                查看用例
              </el-button>
              <el-button :icon="Share" @click="goMindmap">脑图预览</el-button>
              <!-- v3.11: 执行历史入口 -->
              <el-button :icon="Clock" @click="goExecutions">执行历史</el-button>
              <!-- v3.16: 项目导出备份 -->
              <el-button :icon="Download" @click="exportProject">导出备份</el-button>
            </div>
          </div>
        </div>
      </section>

      <!-- 轮询状态提示 -->
      <Transition name="fade">
        <div v-if="pollingMessage" class="polling-banner">
          <el-icon class="is-loading"><Loading /></el-icon>
          <span>{{ pollingMessage }}</span>
        </div>
      </Transition>
    </template>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  ArrowLeft, Aim, MagicStick, Share, Download,
  DataAnalysis, Document, Loading, Check, View, Operation, Clock
} from '@element-plus/icons-vue'
import { getProject } from '@/api/project'
import PrdPanel from '@/components/PrdPanel.vue'
import { triggerAnalysis } from '@/api/analysis'
import { generateMindmap, downloadMindmapUrl } from '@/api/mindmap'
import { useProjectStore } from '@/stores/project'

const route = useRoute()
const router = useRouter()
const projectStore = useProjectStore()
const projectId = route.params.id

const loading = ref(false)
const pollingMessage = ref('')

const project = computed(() => projectStore.currentProject)

const flowSteps = [
  { key: 'create', title: '创建项目', desc: '填写项目基础信息' },
  { key: 'analyze', title: '代码分析', desc: '解析技术栈与状态机' },
  { key: 'generate', title: '用例生成', desc: '基于上下文生成用例' },
  { key: 'mindmap', title: '脑图导出', desc: '生成 XMind 脑图' }
]

const activeStep = computed(() => {
  const s = project.value?.status
  if (s === 'analyzed') return 2
  if (s === 'generating') return 2
  if (s === 'completed') return 4
  return 1
})

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

const techStackList = computed(() => {
  const ts = project.value?.techStack
  if (!ts) return []
  if (Array.isArray(ts)) return ts
  if (typeof ts === 'object') return Object.keys(ts)
  return []
})

// 技术栈标签过多时折叠展示
const MAX_TECH_VISIBLE = 8
const techExpanded = ref(false)
const visibleTechStack = computed(() =>
  techExpanded.value ? techStackList.value : techStackList.value.slice(0, MAX_TECH_VISIBLE)
)

const hasSourcePath = computed(() => {
  return !!project.value?.sourcePath && project.value.sourcePath.trim() !== ''
})

const canAnalyze = computed(() => {
  const s = project.value?.status
  return hasSourcePath.value && (s === 'created' || s === 'failed')
})

// v3.12: 生成前置预检——created 且无 PRD 时不可生成（无任何上下文）
const hasPrd = computed(() => {
  return !!project.value?.prdContent && project.value.prdContent.trim() !== ''
})

const canGenerate = computed(() => {
  const s = project.value?.status
  if (s === 'analyzing' || s === 'generating') return false
  if (s === 'created' && !hasPrd.value) return false
  return true
})

const generateBlockedReason = computed(() => {
  const s = project.value?.status
  if (s === 'analyzing' || s === 'generating') return '正在处理中，请稍候'
  if (s === 'created' && !hasPrd.value) return '请先提供 PRD 或完成代码分析后再生成用例'
  return ''
})

const canMindmap = computed(() => project.value?.status === 'completed')
const canDownload = computed(() => project.value?.status === 'completed')
const canViewAnalysis = computed(() => project.value?.status !== 'created')
const canViewTestcases = computed(() => {
  const s = project.value?.status
  return s === 'analyzed' || s === 'completed'
})

async function refreshProject() {
  const res = await getProject(projectId)
  projectStore.currentProject = res.data
}

async function handleAnalyze() {
  try {
    await triggerAnalysis(projectId)
    ElMessage.success('分析已启动')
    pollingMessage.value = '正在分析代码结构，请稍候...'
    projectStore.startPolling(projectId, (status) => {
      pollingMessage.value = ''
      if (status === 'analyzed') {
        ElMessage.success('分析完成')
      } else if (status === 'failed') {
        ElMessage.error('分析失败')
      }
    })
  } catch (e) {
    // 错误已由响应拦截器统一提示
  }
}

function handleGenerate() {
  router.push(`/projects/${projectId}/testcases?generate=1`)
}

async function handleMindmap() {
  try {
    await generateMindmap(projectId)
    ElMessage.success('脑图生成成功')
    await refreshProject()
  } catch (e) {
    // 错误已由响应拦截器统一提示
  }
}

function handleDownload() {
  window.open(downloadMindmapUrl(projectId))
}

function goList() {
  router.push('/projects')
}

function goAnalysis() {
  router.push(`/projects/${projectId}/analysis`)
}

function goTestcases() {
  router.push(`/projects/${projectId}/testcases`)
}

function goMindmap() {
  router.push(`/projects/${projectId}/mindmap`)
}

// v3.11: 执行历史
function goExecutions() {
  router.push(`/projects/${projectId}/executions`)
}

// v3.16: 项目导出备份（ZIP）
function exportProject() {
  window.open(`/api/projects/${projectId}/export`, '_blank')
}

onMounted(async () => {
  loading.value = true
  try {
    await projectStore.fetchProject(projectId)
  } finally {
    loading.value = false
  }
})

onUnmounted(() => {
  projectStore.stopPolling()
})
</script>

<style scoped lang="scss">
.project-detail {
  padding: var(--space-lg) var(--space-xl);
  max-width: 1280px;
  margin: 0 auto;
}

.page-header-main {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
}

.title-block {
  display: flex;
  align-items: center;
  gap: var(--space-md);
}

.status-pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 12px;
  border-radius: var(--radius-full);
  font-size: 12px;
  font-weight: 500;

  .status-dot {
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: currentColor;
  }

  &.status-created { color: #64748b; background: #f1f5f9; }
  &.status-analyzing, &.status-generating { 
    color: #f59e0b; 
    background: #fef3c7;
    .status-dot { animation: pulse 1.5s ease-in-out infinite; }
  }
  &.status-analyzed, &.status-completed { color: #10b981; background: #d1fae5; }
  &.status-failed { color: #ef4444; background: #fee2e2; }
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.4; }
}

/* ===== 流程步骤条 ===== */
.flow-section {
  background: var(--bg-surface);
  border: 1px solid var(--card-border);
  border-radius: var(--radius-xl);
  padding: var(--space-xl);
  margin-bottom: var(--space-lg);
  box-shadow: var(--shadow-xs);
}

.flow-steps {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  position: relative;
}

.flow-step {
  flex: 1 1 0;
  display: flex;
  align-items: flex-start;
  gap: 12px;
  position: relative;
  min-width: 130px;

  .step-marker {
    flex-shrink: 0;
    display: flex;
    align-items: center;
    justify-content: center;
    width: 32px;
    height: 32px;
    border-radius: 50%;
    border: 2px solid var(--card-border);
    background: var(--bg-surface);
    color: var(--text-tertiary);
    font-weight: 600;
    font-size: 14px;
    transition: all var(--transition-normal);
    z-index: 1;
  }

  .step-info {
    flex: 1;
    min-width: 0;
  }

  .step-title {
    font-size: 13px;
    font-weight: 600;
    color: var(--text-secondary);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .step-desc {
    font-size: 11px;
    color: var(--text-tertiary);
    margin-top: 2px;
  }

  .step-connector {
    position: absolute;
    top: 16px;
    left: 44px;
    right: -12px;
    height: 2px;
    background: var(--card-border);
    z-index: 0;
  }

  &.done {
    .step-marker {
      background: var(--color-success);
      border-color: var(--color-success);
      color: #fff;
    }
    .step-connector {
      background: var(--color-success);
    }
    .step-title { color: var(--text-primary); }
  }

  &.active {
    .step-marker {
      background: var(--brand-primary);
      border-color: var(--brand-primary);
      color: #fff;
      box-shadow: 0 0 0 4px rgba(79, 70, 229, 0.15);
    }
    .step-title { color: var(--brand-primary); }
  }
}

/* ===== 信息区 ===== */
.info-section, .actions-section {
  background: var(--bg-surface);
  border: 1px solid var(--card-border);
  border-radius: var(--radius-xl);
  padding: var(--space-lg) var(--space-xl);
  margin-bottom: var(--space-lg);
  box-shadow: var(--shadow-xs);
}

.section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-md);

  .section-title {
    font-size: 16px;
    font-weight: 600;
    color: var(--text-primary);
    margin: 0;
  }
}

.info-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: var(--space-md);
}

.info-item {
  padding: 12px 14px;
  background: var(--bg-base);
  border-radius: var(--radius-md);
  min-width: 0;
  overflow: hidden;

  .info-label {
    font-size: 12px;
    color: var(--text-tertiary);
    margin-bottom: 4px;
  }

  .info-value {
    font-size: 14px;
    color: var(--text-primary);
    word-break: break-word;
    overflow-wrap: break-word;

    &.mono {
      font-family: 'Consolas', 'Monaco', monospace;
      font-size: 13px;
      word-break: break-all;
      overflow-wrap: break-word;
      line-height: 1.5;
    }
  }
}

/* 技术栈独占整行，避免撑高其他格子造成留白 */
.tech-stack-item {
  grid-column: 1 / -1;
}

.tech-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
}

.tech-tag {
  display: inline-flex;
  align-items: center;
  padding: 2px 10px;
  background: var(--el-color-primary-light-9);
  color: var(--brand-primary);
  border-radius: var(--radius-full);
  font-size: 12px;
  font-weight: 500;
}

.tech-more {
  padding: 0 4px;
}

/* ===== 操作区 ===== */
.action-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: var(--space-md);
}

.action-card {
  padding: var(--space-md);
  background: var(--bg-base);
  border-radius: var(--radius-md);
  border: 1px solid var(--card-border-light);
  transition: all var(--transition-normal);

  &:hover {
    border-color: var(--brand-primary-lighter);
    box-shadow: var(--shadow-sm);
  }

  &.primary-action {
    background: linear-gradient(135deg, var(--el-color-primary-light-9), transparent);
    border-color: var(--el-color-primary-light-8);
  }
}

.action-card-head {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 12px;
  color: var(--text-secondary);
  font-size: 13px;
  font-weight: 600;
}

.action-card-title {
  color: var(--text-secondary);
}

.action-buttons {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.optional-tag {
  display: inline-block;
  margin-left: 4px;
  padding: 0 6px;
  background: rgba(148, 163, 184, 0.15);
  color: #64748b;
  border-radius: var(--radius-sm);
  font-size: 11px;
  font-weight: 500;
}

/* ===== 轮询提示横幅 ===== */
.polling-banner {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 16px;
  background: var(--color-info-bg);
  color: var(--color-info);
  border-radius: var(--radius-md);
  font-size: 13px;
  margin-top: var(--space-md);

  .is-loading {
    animation: spin 1s linear infinite;
  }
}

/* ===== 过渡动画 ===== */
.fade-enter-active, .fade-leave-active {
  transition: all var(--transition-normal);
}

.fade-enter-from, .fade-leave-to {
  opacity: 0;
  transform: translateY(-8px);
}

/* ===== 响应式 ===== */
@media (max-width: 1100px) {
  .flow-step {
    .step-desc {
      display: none;
    }
  }
}

@media (max-width: 768px) {
  .project-detail {
    padding: var(--space-md);
  }

  .flow-steps {
    flex-direction: column;
    gap: 12px;
  }

  .flow-step {
    width: 100%;
    .step-connector { display: none; }
  }

  .info-grid {
    grid-template-columns: 1fr;
  }

  .action-grid {
    grid-template-columns: 1fr;
  }
}
</style>
