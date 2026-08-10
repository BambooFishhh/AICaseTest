<template>
  <div class="project-detail" v-loading="loading">
    <div class="page-header">
      <h2>{{ project?.name || '项目详情' }}</h2>
      <el-button @click="goList">返回列表</el-button>
    </div>

    <el-card v-if="project" class="info-card">
      <template #header>基本信息</template>
      <el-descriptions :column="2" border>
        <el-descriptions-item label="项目名称">{{ project.name }}</el-descriptions-item>
        <el-descriptions-item label="项目状态">
          <el-tag :type="statusTagType(project.status)">
            {{ statusText(project.status) }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="源码路径">{{ project.sourcePath }}</el-descriptions-item>
        <el-descriptions-item label="创建时间">{{ formatDate(project.createdAt) }}</el-descriptions-item>
        <el-descriptions-item label="技术栈" :span="2">
          <template v-if="techStackList.length">
            <el-tag
              v-for="tech in techStackList"
              :key="tech"
              class="tech-tag"
              type="info"
            >{{ tech }}</el-tag>
          </template>
          <span v-else>-</span>
        </el-descriptions-item>
      </el-descriptions>
    </el-card>

    <!-- v3.0: PRD 面板上提为主线（v1.10 引入，v3.0 调整位置） -->
    <PrdPanel v-if="project" :project-id="projectId" />

    <el-card class="action-card">
      <template #header>操作</template>
      <div class="action-buttons">
        <!-- v3.0: 生成用例作为主操作 -->
        <el-button type="primary" :disabled="!canGenerate" @click="handleGenerate">生成用例</el-button>
        <!-- v3.0: 开始分析标注为可选上下文，无代码路径时禁用 -->
        <el-button
          type="primary"
          :disabled="!canAnalyze"
          :title="!hasSourcePath ? '未配置代码路径，可直接用 PRD 生成用例' : ''"
          @click="handleAnalyze"
        >开始分析<span v-if="!hasSourcePath" class="optional-mark">（可选）</span></el-button>
        <el-button type="primary" :disabled="!canMindmap" @click="handleMindmap">生成脑图</el-button>
        <el-button :disabled="!canDownload" @click="handleDownload">下载脑图</el-button>
        <el-button :disabled="!canViewAnalysis" @click="goAnalysis">查看分析</el-button>
        <el-button :disabled="!canViewTestcases" @click="goTestcases">查看用例</el-button>
        <el-button @click="goMindmap">脑图预览</el-button>
      </div>
    </el-card>

    <el-alert
      v-if="pollingMessage"
      :title="pollingMessage"
      type="info"
      :closable="false"
      show-icon
      class="polling-alert"
    />
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
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

const statusTypeMap = {
  created: 'info',
  analyzing: 'warning',
  analyzed: 'success',
  generating: 'warning',
  completed: 'success',
  failed: 'danger'
}

const statusTextMap = {
  created: '已创建',
  analyzing: '分析中...',
  analyzed: '已分析',
  generating: '生成中...',
  completed: '已完成',
  failed: '失败'
}

function statusTagType(status) {
  return statusTypeMap[status] || 'info'
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

// v3.0: 是否有代码路径（无代码路径时"开始分析"禁用）
const hasSourcePath = computed(() => {
  return !!project.value?.sourcePath && project.value.sourcePath.trim() !== ''
})

// v3.0: 开始分析需有代码路径
const canAnalyze = computed(() => {
  const s = project.value?.status
  return hasSourcePath.value && (s === 'created' || s === 'failed')
})

// v3.0: 生成用例放宽——created 状态也可生成（纯 PRD 驱动），只需不在分析/生成中
const canGenerate = computed(() => {
  const s = project.value?.status
  return s !== 'analyzing' && s !== 'generating'
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

// v3.2: 跳转 TestCaseList 并自动触发流式生成（SSE），生成进度与用例实时呈现
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

<style scoped>
.project-detail {
  padding: 20px;
}
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}
.page-header h2 {
  margin: 0;
}
.info-card {
  margin-bottom: 20px;
}
.tech-tag {
  margin-right: 6px;
  margin-bottom: 4px;
}
.action-card {
  margin-bottom: 20px;
}
.action-buttons {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}
.optional-mark {
  color: #909399;
  font-size: 12px;
}
.polling-alert {
  margin-top: 4px;
}
</style>
