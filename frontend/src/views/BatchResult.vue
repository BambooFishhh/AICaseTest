<template>
  <div class="batch-result page-container" v-loading="loading">
    <!-- 页头 -->
    <header class="page-header">
      <div class="page-header-main">
        <el-button text :icon="ArrowLeft" @click="goBack">返回</el-button>
        <div class="title-block">
          <h1 class="page-title">批次执行结果</h1>
          <p class="page-subtitle">查看批次执行的进度与各用例状态</p>
        </div>
      </div>
      <div v-if="batch" class="page-actions">
        <!-- v3.13: 批次报告在线预览 + 下载 -->
        <el-button type="primary" :icon="Document" @click="previewReport">预览批次报告</el-button>
        <el-button :icon="Download" @click="downloadReport">下载批次报告</el-button>
      </div>
    </header>

    <!-- 空状态 -->
    <section v-if="!loading && !batch" class="empty-section">
      <el-empty description="未找到批次记录" :image-size="120" />
    </section>

    <template v-if="batch">
      <!-- 进度概览 -->
      <section class="overview-section">
        <div class="overview-head">
          <div class="overview-head-text">
            <h2 class="section-title">批次概览</h2>
            <p class="section-desc">进度：{{ completedCount }} / {{ totalCount }}</p>
          </div>
          <div class="overview-status">
            <el-icon
              v-if="isRunning"
              class="is-loading running-icon"
              :size="18"
            ><Loading /></el-icon>
            <span v-if="isRunning" class="running-text">执行中，每 3 秒自动刷新...</span>
          </div>
        </div>

        <!-- 进度条 -->
        <div class="progress-block">
          <el-progress
            :percentage="progressPercent"
            :status="progressStatus"
            :stroke-width="14"
            :color="progressColors"
          />
        </div>

        <!-- 统计卡片 -->
        <div class="stat-grid">
          <div class="stat-card stat-passed">
            <div class="stat-icon"><el-icon :size="20"><CircleCheck /></el-icon></div>
            <div class="stat-body">
              <div class="stat-value">{{ passedCount }}</div>
              <div class="stat-label">通过</div>
            </div>
          </div>
          <div class="stat-card stat-failed">
            <div class="stat-icon"><el-icon :size="20"><CircleClose /></el-icon></div>
            <div class="stat-body">
              <div class="stat-value">{{ failedCount }}</div>
              <div class="stat-label">失败</div>
            </div>
          </div>
          <div class="stat-card stat-running">
            <div class="stat-icon"><el-icon :size="20"><Loading /></el-icon></div>
            <div class="stat-body">
              <div class="stat-value">{{ runningCount }}</div>
              <div class="stat-label">运行中</div>
            </div>
          </div>
          <div class="stat-card stat-total">
            <div class="stat-icon"><el-icon :size="20"><Files /></el-icon></div>
            <div class="stat-body">
              <div class="stat-value">{{ totalCount }}</div>
              <div class="stat-label">总计</div>
            </div>
          </div>
        </div>
      </section>

      <!-- v3.12: 失败用例错误摘要 -->
      <section v-if="failedExecutions.length > 0" class="failure-section">
        <el-collapse>
          <el-collapse-item :name="'failures'">
            <template #title>
              <div class="failure-title">
                <el-icon :size="16"><CircleClose /></el-icon>
                <span>失败用例（{{ failedExecutions.length }}）</span>
              </div>
            </template>
            <div v-for="row in failedExecutions" :key="row.id" class="failure-item">
              <div class="failure-item-head">
                <span class="failure-case-title">{{ row.testCaseTitle || '-' }}</span>
                <el-button
                  type="primary"
                  link
                  :icon="View"
                  @click="goToExecution(row.id)"
                >
                  查看详情
                </el-button>
              </div>
              <div v-if="row.errorMessage" class="failure-error">{{ row.errorMessage }}</div>
              <div v-else class="failure-error muted">无错误详情，请点击查看步骤结果</div>
            </div>
          </el-collapse-item>
        </el-collapse>
      </section>

      <!-- 用例执行列表 -->
      <section class="list-section">
        <div class="section-head">
          <div class="section-head-text">
            <h2 class="section-title">用例执行列表</h2>
            <p class="section-desc">共 {{ executions.length }} 条记录</p>
          </div>
        </div>

        <el-empty
          v-if="executions.length === 0"
          description="暂无用例执行数据"
          :image-size="100"
        />
        <el-table
          v-else
          :data="executions"
          stripe
          highlight-current-row
          @row-click="handleRowClick"
        >
          <el-table-column prop="testCaseTitle" label="用例标题" min-width="240">
            <template #default="{ row }">
              <span class="case-title">{{ row.testCaseTitle || '-' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="120">
            <template #default="{ row }">
              <span class="status-pill" :class="`status-${row.status}`">
                <i class="status-dot"></i>{{ statusLabel(row.status) }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="开始时间" width="200">
            <template #default="{ row }">
              <span class="time-text mono">{{ formatTime(row.startTime) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="120" align="center">
            <template #default="{ row }">
              <el-button
                v-if="row.id"
                type="primary"
                link
                :icon="View"
                @click.stop="goToExecution(row.id)"
              >查看详情</el-button>
              <span v-else class="text-muted">-</span>
            </template>
          </el-table-column>
        </el-table>
      </section>
    </template>
  </div>
</template>

<script setup>
/**
 * 批次执行结果页
 * 展示批次执行的：
 * - 概览（进度条、通过/失败/运行中/总计）
 * - 用例执行列表（标题、状态、开始时间、跳转详情）
 * 执行中状态会每 3 秒自动轮询刷新。
 */
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ArrowLeft, Loading, View, Download, Document, CircleCheck, CircleClose, Files
} from '@element-plus/icons-vue'
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

// v3.12: 失败用例（错误摘要折叠区）
const failedExecutions = computed(() => {
  return executions.value.filter((r) => r.status === 'failed')
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

// 进度条渐变色
const progressColors = [
  { color: '#f59e0b', percentage: 50 },
  { color: '#6366f1', percentage: 80 },
  { color: '#10b981', percentage: 100 }
]

// 状态标签
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
  if (row.id) {
    goToExecution(row.id)
  }
}

function goToExecution(executionId) {
  router.push(`/projects/${projectId}/executions/${executionId}`)
}

function downloadReport() {
  window.open(`/api/batches/${batchId}/report?download=1`, '_blank')
}

// v3.13: 批次报告在线预览（inline）
function previewReport() {
  window.open(`/api/batches/${batchId}/report`, '_blank')
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
/* ===== 页头 ===== */
.page-header-main {
  display: flex;
  align-items: center;
  gap: var(--space-md);
}

.title-block {
  display: flex;
  flex-direction: column;
}

/* ===== 通用区块头 ===== */
.section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-md);
}

.section-head-text {
  display: flex;
  flex-direction: column;
}

.section-title {
  font-size: 16px;
  font-weight: 700;
  color: var(--text-primary);
  margin: 0;
}

.section-desc {
  font-size: 13px;
  color: var(--text-tertiary);
  margin-top: 2px;
}

/* ===== 概览 ===== */
.overview-section {
  background: var(--bg-surface);
  border: 1px solid var(--card-border);
  border-radius: var(--radius-lg);
  padding: 20px;
  box-shadow: var(--shadow-xs);
  margin-bottom: var(--space-lg);
}

.overview-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: var(--space-md);
  gap: var(--space-md);
  flex-wrap: wrap;
}

.overview-head-text {
  display: flex;
  flex-direction: column;
}

.overview-status {
  display: flex;
  align-items: center;
  gap: 6px;
}

.running-icon {
  color: var(--color-warning);
}

.running-text {
  color: var(--color-warning);
  font-size: 13px;
  font-weight: 500;
}

.progress-block {
  margin-bottom: var(--space-md);
}

/* ===== 统计卡片 ===== */
.stat-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: var(--space-md);
}

.stat-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 16px;
  background: #f8fafc;
  border: 1px solid var(--card-border-light);
  border-radius: var(--radius-md);
  transition: all var(--transition-normal);

  &:hover {
    transform: translateY(-1px);
    box-shadow: var(--shadow-sm);
  }

  .stat-icon {
    flex-shrink: 0;
    display: flex;
    align-items: center;
    justify-content: center;
    width: 40px;
    height: 40px;
    border-radius: var(--radius-md);
    color: #fff;
  }

  .stat-body {
    flex: 1;
    min-width: 0;
  }

  .stat-value {
    font-size: 22px;
    font-weight: 700;
    line-height: 1.1;
    color: var(--text-primary);
  }

  .stat-label {
    font-size: 12px;
    color: var(--text-tertiary);
    margin-top: 2px;
  }

  &.stat-passed .stat-icon { background: var(--color-success); }
  &.stat-failed .stat-icon { background: var(--color-danger); }
  &.stat-running .stat-icon { background: var(--color-warning); }
  &.stat-total .stat-icon { background: var(--brand-primary); }
}

/* ===== 状态胶囊 ===== */
.status-pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 3px 10px;
  border-radius: var(--radius-full);
  font-size: 12px;
  font-weight: 600;

  &::before {
    content: '';
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: currentColor;
  }

  &.status-passed { color: var(--color-success); background: var(--color-success-bg); }
  &.status-failed { color: var(--color-danger); background: var(--color-danger-bg); }
  &.status-running { color: var(--color-warning); background: var(--color-warning-bg); }
  &.status-pending { color: var(--text-secondary); background: #f1f5f9; }
}

/* ===== v3.12: 失败用例摘要 ===== */
.failure-section {
  background: var(--bg-surface);
  border: 1px solid var(--card-border);
  border-radius: var(--radius-lg);
  padding: 8px 16px;
  box-shadow: var(--shadow-xs);
  margin-bottom: var(--space-lg);
}

.failure-title {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--color-danger);
  font-weight: 600;
  font-size: 14px;
}

.failure-item {
  padding: 12px 14px;
  background: #fef2f2;
  border: 1px solid var(--color-danger-bg);
  border-radius: var(--radius-md);
  margin-bottom: 10px;

  &:last-child {
    margin-bottom: 0;
  }
}

.failure-item-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.failure-case-title {
  font-weight: 600;
  color: var(--text-primary);
  font-size: 13px;
}

.failure-error {
  margin-top: 6px;
  font-size: 12px;
  color: var(--color-danger);
  line-height: 1.5;
  word-break: break-all;

  &.muted {
    color: var(--text-tertiary);
  }
}

/* ===== 列表 ===== */
.list-section {
  background: var(--bg-surface);
  border: 1px solid var(--card-border);
  border-radius: var(--radius-lg);
  padding: 20px;
  box-shadow: var(--shadow-xs);
}

.case-title {
  color: var(--text-primary);
  font-weight: 500;
}

.time-text {
  font-size: 13px;
  color: var(--text-secondary);
}

.text-muted {
  color: var(--text-tertiary);
}

.mono {
  font-family: 'Consolas', 'Monaco', monospace;
}

/* ===== 响应式 ===== */
@media (max-width: 768px) {
  .stat-grid {
    grid-template-columns: 1fr 1fr;
  }
}
</style>
