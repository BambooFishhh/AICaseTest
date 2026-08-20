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
        <div class="info-meta">
          <span class="meta-item">
            <span class="meta-label">项目名称</span>
            <strong>{{ project.name }}</strong>
          </span>
          <span class="meta-item">
            <span class="meta-label">源码路径</span>
            <code class="meta-mono">{{ project.sourcePath || '纯 PRD 模式' }}</code>
          </span>
          <span class="meta-item">
            <span class="meta-label">创建时间</span>
            {{ formatDate(project.createdAt) }}
          </span>
          <span v-if="techStackList.length" class="meta-item meta-tech">
            <span class="meta-label">技术栈</span>
            <span class="tech-tags">
              <span v-for="tech in visibleTechStack" :key="tech.label + tech.value" class="tech-tag">
                {{ tech.value ? `${tech.label}: ${tech.value}` : tech.label }}
              </span>
              <el-button
                v-if="techStackList.length > MAX_TECH_VISIBLE"
                link
                size="small"
                class="tech-more"
                @click="techExpanded = !techExpanded"
              >
                {{ techExpanded ? '收起' : `全部 ${techStackList.length} 个` }}
              </el-button>
            </span>
          </span>
          <span v-else class="meta-item">
            <span class="meta-label">技术栈</span>
            <span class="text-muted">尚未分析</span>
          </span>
        </div>
      </section>

      <!-- v5.9: 操作区 -->
      <section class="actions-section">
        <div class="section-head">
          <h2 class="section-title">操作</h2>
        </div>
        <div class="action-grid">
          <!-- 主线操作 -->
          <div v-if="canOperate" class="action-card primary-action">
            <div class="action-card-head">
              <el-icon :size="18"><Operation /></el-icon>
              <span class="action-card-title">主线操作</span>
            </div>
            <div class="action-buttons">
              <el-button
                type="primary"
                :icon="Aim"
                :disabled="!canAnalyze || analysisRunning"
                :loading="analysisRunning"
                :title="analyzeBlockedReason"
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
              <el-tooltip v-if="canOperate" content="导出备份" placement="top">
                <el-button text :icon="Download" @click="exportProject" />
              </el-tooltip>
            </div>
            <div class="action-buttons">
              <el-button :icon="DataAnalysis" :disabled="!canViewAnalysis" @click="goAnalysis">
                查看分析
              </el-button>
              <el-button :icon="Document" :disabled="!canViewTestcases" @click="goTestcases">
                查看用例
              </el-button>
              <el-button :icon="Share" :disabled="!canViewMindmap" @click="goMindmap">脑图预览</el-button>
              <el-button :icon="Clock" :disabled="!canViewExecutions" @click="goExecutions">执行历史</el-button>
            </div>
          </div>

          <!-- v5.9: 执行配置 -->
          <div v-if="canOperate" class="action-card">
            <div class="action-card-head">
              <el-icon :size="18"><Connection /></el-icon>
              <span class="action-card-title">执行配置</span>
            </div>
            <div class="action-buttons">
              <el-button :icon="Setting" @click="openCookieDialog">Cookie 配置</el-button>
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
        <div v-else-if="analysisSuccess" class="polling-banner success-banner">
          <el-icon :size="16"><Check /></el-icon>
          <span>分析完成</span>
        </div>
      </Transition>

      <!-- PRD 面板 -->
      <PrdPanel :project-id="projectId" />

      <!-- v4.3: 只读提示 -->
      <el-alert
        v-if="project && !canOperate"
        title="只读权限：可查看项目与复制执行用例。如需生成/修改/分析，请联系项目组创建者开通操作权限。"
        type="warning"
        :closable="false"
        show-icon
        class="readonly-alert"
      />

    </template>

    <!-- v5.9: 执行 Cookie 配置弹窗 -->
    <el-dialog v-model="cookieDialogVisible" title="执行 Cookie 配置" width="680px">
      <div class="cookie-config-tip">name 是 Cookie 名称，value 是对应的值；domain 填目标站点域名或 URL</div>
      <div v-for="(cookie, idx) in cookieForm" :key="idx" class="cookie-row">
        <el-input v-model="cookie.name" placeholder="名称，如 JSESSIONID" />
        <el-input v-model="cookie.value" placeholder="值" />
        <el-input v-model="cookie.domain" placeholder="域名/URL，如 host.docker.internal" />
        <el-button :icon="Delete" text type="danger" @click="removeCookie(idx)" />
      </div>
      <div class="cookie-actions">
        <el-button size="small" :icon="Plus" @click="addCookie">添加 Cookie</el-button>
      </div>
      <template #footer>
        <el-button @click="cookieDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingCookies" @click="saveCookies">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  ArrowLeft, Aim, MagicStick, Share, Download,
  DataAnalysis, Document, Loading, Check, View, Operation, Clock,
  Connection, Setting, Delete, Plus
} from '@element-plus/icons-vue'
import { getProject, getExecutionCookies, updateExecutionCookies } from '@/api/project'
import PrdPanel from '@/components/PrdPanel.vue'
import { generateMindmap, downloadMindmapUrl } from '@/api/mindmap'
import { fetchSseTicket } from '@/api/sse'
import { downloadAuth } from '@/utils/download'
import { useProjectStore } from '@/stores/project'

const route = useRoute()
const router = useRouter()
const projectStore = useProjectStore()
const projectId = route.params.id

const loading = ref(false)
const pollingMessage = ref('')
const analysisSuccess = ref(false)
const analysisRunning = ref(false)
// v4.4: 流式分析 EventSource
let analyzeEs = null

const project = computed(() => projectStore.currentProject)

// v4.3: 访问级别（OWNER/OPERATOR 可操作，VIEWER 只读）
const canOperate = computed(() => {
  const level = project.value?.accessLevel
  return level !== 'VIEWER'
})

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

const TECH_STACK_LABELS = {
  httpClient: 'HTTP 客户端',
  stateManagement: '状态管理',
  backendFramework: '后端框架',
  backendLanguage: '后端语言',
  language: '前端语言',
  orm: 'ORM',
  uiFramework: 'UI 框架',
  router: '路由',
  security: '安全框架',
  apiDocs: 'API 文档',
  vueVersion: 'Vue 版本',
  persistence: '持久化',
  backend: '后端',
  springBootVersion: 'Spring Boot 版本',
  frontendVersion: '前端版本',
  frontend: '前端框架',
  buildTool: '构建工具',
  cache: '缓存',
  type: '项目类型'
}

const techStackList = computed(() => {
  const ts = project.value?.techStack
  if (!ts) return []
  if (typeof ts === 'object' && !Array.isArray(ts)) {
    return Object.entries(ts).map(([key, value]) => ({
      label: TECH_STACK_LABELS[key] || key,
      value: Array.isArray(value) ? value.join(', ') : String(value ?? '')
    }))
  }
  if (Array.isArray(ts)) return ts.map((v) => ({ label: v, value: '' }))
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
  // v5.13: 已分析/已完成也可重新分析，仅 analyzing/generating 拦截
  return !analysisRunning.value && hasSourcePath.value && (
    s === 'created' || s === 'failed' || s === 'analyzed' || s === 'completed'
  )
})

const analyzeBlockedReason = computed(() => {
  const s = project.value?.status
  if (analysisRunning.value) return '正在分析中，请稍候'
  if (!hasSourcePath.value) return '未配置代码路径，可直接用 PRD 生成用例'
  if (s === 'analyzing') return '正在分析中，请稍候'
  if (s === 'generating') return '项目正在生成用例，请稍后再启动分析'
  return ''
})

// v5.13: 生成前置预检——生成必须基于 PRD，代码只作为辅助上下文
const hasPrd = computed(() => {
  return !!project.value?.prdContent && project.value.prdContent.trim() !== ''
})

const canGenerate = computed(() => {
  const s = project.value?.status
  if (s === 'analyzing' || s === 'generating') return false
  if (!hasPrd.value) return false
  return true
})

const generateBlockedReason = computed(() => {
  const s = project.value?.status
  if (s === 'analyzing' || s === 'generating') return '正在处理中，请稍候'
  if (!hasPrd.value) return '请先添加 PRD 文档'
  return ''
})

const canMindmap = computed(() => project.value?.status === 'completed')
const canDownload = computed(() => project.value?.status === 'completed')
const canViewAnalysis = computed(() => project.value?.status !== 'created')
const canViewTestcases = computed(() => {
  const s = project.value?.status
  return s === 'analyzed' || s === 'completed'
})
// v4.5: 查看类按钮前置条件——对应阶段未完成时置灰
const canViewMindmap = computed(() => project.value?.status === 'completed')
const canViewExecutions = computed(() => {
  const s = project.value?.status
  return s !== 'created' && s !== 'analyzing'
})

async function refreshProject() {
  const res = await getProject(projectId)
  projectStore.currentProject = res.data
}

// v4.4: 流式分析——SSE 实时阶段进度
// v6.6: 先换短期 ticket 再连 EventSource，避免长期 JWT 进 URL
async function handleAnalyze() {
  if (analysisRunning.value || analyzeEs) return
  analysisRunning.value = true
  ElMessage.success('分析已启动')
  analysisSuccess.value = false
  pollingMessage.value = '正在启动分析...'
  try {
    const { data } = await fetchSseTicket()
    analyzeEs = new EventSource(`/api/projects/${projectId}/analyze-stream?ticket=${encodeURIComponent(data.ticket)}`)
  } catch {
    analyzeEs = null
    analysisRunning.value = false
    pollingMessage.value = ''
    ElMessage.error('分析连接初始化失败')
    return
  }

  analyzeEs.addEventListener('progress', (e) => {
    try {
      pollingMessage.value = JSON.parse(e.data).message
    } catch {
      // 忽略解析失败
    }
  })

  analyzeEs.addEventListener('complete', () => {
    analyzeEs?.close()
    analyzeEs = null
    analysisRunning.value = false
    pollingMessage.value = ''
    analysisSuccess.value = true
    refreshProject()
  })

  analyzeEs.addEventListener('error', (e) => {
    let msg = '分析连接异常'
    if (e.data) {
      try {
        msg = JSON.parse(e.data).message || msg
      } catch {
        // 保持默认
      }
    }
    analyzeEs?.close()
    analyzeEs = null
    analysisRunning.value = false
    pollingMessage.value = ''
    analysisSuccess.value = false
    ElMessage.error(msg === '分析连接异常' ? '分析失败，请重试' : msg)
    refreshProject()
  })
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
  downloadAuth(downloadMindmapUrl(projectId), 'mindmap.xmind')
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
  downloadAuth(`/api/projects/${projectId}/export`, `project_${projectId}_backup.zip`)
}

// v5.9: 执行 Cookie 配置
const cookieDialogVisible = ref(false)
const cookieForm = ref([])
const savingCookies = ref(false)

async function openCookieDialog() {
  try {
    const res = await getExecutionCookies(projectId)
    cookieForm.value = (res.data || []).map((c) => ({
      name: c.name || '',
      value: c.value || '',
      domain: c.url || c.domain || ''
    }))
    cookieDialogVisible.value = true
  } catch {
    // 错误已由响应拦截器统一提示
  }
}

function addCookie() {
  cookieForm.value.push({ name: '', value: '', domain: '' })
}

function removeCookie(idx) {
  cookieForm.value.splice(idx, 1)
}

async function saveCookies() {
  const missing = cookieForm.value.find(
    (c) => !c.name?.trim() || !c.value?.trim() || !c.domain?.trim()
  )
  if (missing) {
    ElMessage.error('请填写完整的 Cookie 名称、值和域名')
    return
  }
  savingCookies.value = true
  try {
    const cookies = cookieForm.value.map((c) => {
      const base = { name: c.name.trim(), value: c.value.trim() }
      const domain = c.domain.trim()
      if (domain.startsWith('http://') || domain.startsWith('https://')) {
        base.url = domain
      } else {
        base.domain = domain
      }
      return base
    })
    await updateExecutionCookies(projectId, cookies)
    ElMessage.success('执行 Cookie 已保存')
    cookieDialogVisible.value = false
    await refreshProject()
  } catch {
    // 错误已由响应拦截器统一提示
  } finally {
    savingCookies.value = false
  }
}

onMounted(async () => {
  loading.value = true
  try {
    await projectStore.fetchProject(projectId)
    resumeActiveStatus()
  } finally {
    loading.value = false
  }
})

// v6.1/v6.2fix: 刷新/重新进入页面后，若项目仍在分析/生成中，恢复“需求文档上方”的进度横幅：
// 先给兜底文案，再由轮询用后端持久化的进度覆盖；终态补发完成/失败提示，并点亮常驻的绿色“分析完成”横幅。
function resumeActiveStatus() {
  const s = project.value?.status
  if (s !== 'analyzing' && s !== 'generating') return
  analysisSuccess.value = false
  pollingMessage.value = s === 'analyzing' ? '正在分析中...' : '正在生成用例中...'
  projectStore.startPolling(projectId, (next, prev, p) => {
    if (p?.progress) pollingMessage.value = p.progress
    if (prev !== 'analyzing' && prev !== 'generating') return
    if (next === prev) return
    if (next === 'analyzed') {
      ElMessage.success('代码分析完成')
      pollingMessage.value = ''
      analysisSuccess.value = true
      refreshProject()
    } else if (next === 'completed') {
      ElMessage.success('用例生成完成')
      pollingMessage.value = ''
      refreshProject()
    } else if (next === 'failed') {
      ElMessage.error('任务执行失败，请检查项目状态')
      pollingMessage.value = ''
      refreshProject()
    }
  })
}

onUnmounted(() => {
  if (analyzeEs) {
    analyzeEs.close()
    analyzeEs = null
  }
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

/* ===== 信息区（紧凑元信息行） ===== */
.info-section {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px 24px;
  padding: 10px 16px;
  background: var(--bg-surface);
  border: 1px solid var(--card-border);
  border-radius: var(--radius-lg);
  margin-bottom: var(--space-md);
  box-shadow: var(--shadow-xs);
}

.info-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px 24px;
  min-width: 0;
  width: 100%;
}

.meta-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  font-size: 13px;
  color: var(--text-secondary);
}

.meta-label {
  font-size: 12px;
  color: var(--text-tertiary);
  white-space: nowrap;
}

.meta-mono {
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 12px;
  color: var(--text-primary);
  word-break: break-all;
}

.meta-tech {
  max-width: 100%;
}

.tech-tags {
  display: inline-flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
}

.tech-tag {
  display: inline-flex;
  align-items: center;
  padding: 1px 8px;
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
.actions-section {
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
  justify-content: space-between;
  gap: 6px;
  margin-bottom: 12px;
  color: var(--text-secondary);
  font-size: 13px;
  font-weight: 600;

  .el-button {
    height: 24px;
    color: var(--text-tertiary);
  }
}

.action-card-title {
  color: var(--text-secondary);
}

.action-buttons {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;

  .el-button {
    width: 100%;
    margin: 0;
  }
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

.polling-banner.success-banner {
  background: var(--color-success-bg);
  color: var(--color-success);
  border: 1px solid var(--color-success);
}

/* ===== 过渡动画 ===== */
.fade-enter-active, .fade-leave-active {
  transition: all var(--transition-normal);
}

.fade-enter-from, .fade-leave-to {
  opacity: 0;
  transform: translateY(-8px);
}

/* ===== v5.9: Cookie 配置弹窗 ===== */
.cookie-config-tip {
  font-size: 12px;
  color: var(--text-tertiary);
  margin-bottom: 10px;
}

.cookie-row {
  display: grid;
  grid-template-columns: 1fr 1fr 1fr 32px;
  gap: 8px;
  margin-bottom: 8px;
  align-items: center;
}

.cookie-actions {
  display: flex;
  justify-content: flex-end;
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

  .action-grid {
    grid-template-columns: 1fr;
  }

  .cookie-row {
    grid-template-columns: 1fr;
  }
}
</style>
