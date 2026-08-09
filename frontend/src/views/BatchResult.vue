<template>
  <div class="batch-result" v-loading="loading">
    <div class="page-header">
      <h2>批次执行结果</h2>
      <el-button :icon="ArrowLeft" @click="goBack">返回</el-button>
    </div>

    <!-- 批次概览 -->
    <el-card v-if="batch" class="overview-card">
      <template #header>
        <span>批次概览</span>
        <span v-if="isRunning" class="running-hint">
          <el-icon class="is-loading"><Loading /></el-icon>
          执行中，自动刷新...
        </span>
      </template>
      <el-row :gutter="16">
        <el-col :xs="24" :sm="12">
          <div class="progress-section">
            <div class="progress-label">
              进度：{{ completedCount }} / {{ totalCount }}
            </div>
            <el-progress
              :percentage="progressPercent"
              :status="progressStatus"
              :stroke-width="18"
            />
          </div>
        </el-col>
        <el-col :xs="24" :sm="12">
          <div class="stat-tags">
            <div class="stat-tag-item">
              <el-tag type="success" size="large">通过 {{ passedCount }}</el-tag>
            </div>
            <div class="stat-tag-item">
              <el-tag type="danger" size="large">失败 {{ failedCount }}</el-tag>
            </div>
            <div class="stat-tag-item">
              <el-tag type="warning" size="large">运行中 {{ runningCount }}</el-tag>
            </div>
            <div class="stat-tag-item">
              <el-tag type="info" size="large">总计 {{ totalCount }}</el-tag>
            </div>
          </div>
        </el-col>
      </el-row>
    </el-card>

    <el-empty
      v-else-if="!loading"
      description="未找到批次记录"
      :image-size="80"
    />

    <!-- 用例执行列表 -->
    <el-card v-if="batch && executions.length > 0" class="list-card">
      <template #header>用例执行列表（{{ executions.length }}）</template>
      <el-table
        :data="executions"
        border
        style="width: 100%"
        @row-click="handleRowClick"
        highlight-current-row
      >
        <el-table-column prop="caseTitle" label="用例标题" min-width="220" />
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" size="small">
              {{ statusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="开始时间" width="200">
          <template #default="{ row }">
            <span>{{ formatTime(row.startTime) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120">
          <template #default="{ row }">
            <el-button
              v-if="row.executionId"
              type="primary"
              link
              :icon="View"
              @click.stop="goToExecution(row.executionId)"
            >
              查看详情
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card v-if="batch && executions.length === 0 && !loading" class="list-card">
      <el-empty description="暂无用例执行数据" :image-size="60" />
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, Loading, View } from '@element-plus/icons-vue'
import { getBatch } from '@/api/execution'

const route = useRoute()
const router = useRouter()
const projectId = route.params.id
const batchId = route.params.batchId

const loading = ref(true)
const batch = ref(null)
let pollTimer = null

// 用例执行列表
const executions = computed(() => {
  const list = batch.value?.executions
  return Array.isArray(list) ? list : []
})

// 统计数据
const totalCount = computed(() => batch.value?.total || executions.value.length || 0)
const runningCount = computed(() => batch.value?.running ?? 0)
const passedCount = computed(() => batch.value?.passed ?? 0)
const failedCount = computed(() => batch.value?.failed ?? 0)
const completedCount = computed(() => passedCount.value + failedCount.value)

const isRunning = computed(() => runningCount.value > 0)

const progressPercent = computed(() => {
  if (totalCount.value === 0) return 0
  return Math.min(100, Math.round((completedCount.value / totalCount.value) * 100))
})

const progressStatus = computed(() => {
  if (!isRunning.value && completedCount.value === totalCount.value) {
    return failedCount.value > 0 ? 'exception' : 'success'
  }
  return undefined
})

// 状态标签颜色: passed=success, failed=danger, running=warning
const statusTagType = (status) => {
  const map = {
    passed: 'success',
    failed: 'danger',
    running: 'warning',
    pending: 'info'
  }
  return map[status] || 'info'
}
const statusLabel = (status) => {
  const map = {
    passed: '通过',
    failed: '失败',
    running: '执行中',
    pending: '等待中'
  }
  return map[status] || status || '-'
}

// 时间格式化
const formatTime = (time) => {
  if (!time) return '-'
  const d = new Date(time)
  if (isNaN(d.getTime())) return '-'
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

async function loadBatch() {
  try {
    const res = await getBatch(batchId)
    batch.value = res.data
    if (batch.value && runningCount.value > 0) {
      schedulePoll()
    } else {
      stopPoll()
    }
  } catch (e) {
    // 错误已由响应拦截器统一提示
    stopPoll()
  }
}

// 每 3 秒轮询一次，直到 running === 0
function schedulePoll() {
  if (pollTimer) return
  pollTimer = setInterval(async () => {
    try {
      const res = await getBatch(batchId)
      batch.value = res.data
      if (!batch.value || runningCount.value === 0) {
        stopPoll()
      }
    } catch (e) {
      stopPoll()
    }
  }, 3000)
}

function stopPoll() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

// 点击用例行跳转到执行详情
function handleRowClick(row) {
  if (row.executionId) {
    goToExecution(row.executionId)
  }
}

function goToExecution(executionId) {
  router.push(`/projects/${projectId}/executions/${executionId}`)
}

function goBack() {
  router.push(`/projects/${projectId}/testcases`)
}

onMounted(async () => {
  loading.value = true
  await loadBatch()
  loading.value = false
})

onUnmounted(() => {
  stopPoll()
})
</script>

<style scoped>
.batch-result {
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
.overview-card {
  margin-bottom: 20px;
}
.running-hint {
  margin-left: 8px;
  color: #e6a23c;
  font-size: 12px;
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.running-hint .el-icon {
  vertical-align: middle;
}
.progress-section {
  margin-bottom: 8px;
}
.progress-label {
  font-size: 13px;
  color: #606266;
  margin-bottom: 8px;
}
.stat-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
  height: 100%;
}
.stat-tag-item {
  display: inline-flex;
}
.list-card {
  margin-bottom: 20px;
}
</style>
