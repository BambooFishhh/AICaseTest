<template>
  <div class="execution-result page-container" v-loading="loading">
    <!-- 页头 -->
    <header class="page-header">
      <div class="page-header-main">
        <el-button text :icon="ArrowLeft" @click="goBack">返回</el-button>
        <div class="title-block">
          <h1 class="page-title">执行结果</h1>
          <p class="page-subtitle">查看用例执行的步骤、状态与录屏回放</p>
        </div>
      </div>
      <div v-if="execution" class="page-actions">
        <!-- 单条执行取消 -->
        <el-button
          v-if="execution.status === 'running'"
          type="danger"
          :icon="CircleClose"
          :loading="cancelling"
          @click="handleCancel"
        >
          取消执行
        </el-button>
        <!-- v3.13: 报告在线预览 + 下载 -->
        <el-button type="primary" :icon="Document" @click="previewReport">预览报告</el-button>
        <el-button :icon="Download" @click="downloadReport">下载报告</el-button>
      </div>
    </header>

    <!-- 空状态 -->
    <section v-if="!loading && !execution" class="empty-section">
      <el-empty
        :description="loadError ? '执行结果加载失败，请稍后重试' : '未找到执行记录'"
        :image-size="120"
      >
        <el-button v-if="loadError" type="primary" @click="reload">重新加载</el-button>
      </el-empty>
    </section>

    <template v-if="execution">
      <!-- 执行概览 -->
      <section class="overview-section">
        <div class="overview-head">
          <div class="overview-head-text">
            <h2 class="section-title">执行概览</h2>
            <p class="section-desc">用例执行的整体情况</p>
          </div>
          <div class="overview-status">
            <el-icon
              v-if="execution.status === 'running'"
              class="is-loading status-running-icon"
              :size="18"
            ><Loading /></el-icon>
            <span
              class="status-pill"
              :class="`status-${execution.status}`"
            >
              <i class="status-dot"></i>{{ statusLabel(execution.status) }}
            </span>
          </div>
        </div>

        <div class="overview-grid">
          <div class="overview-item">
            <div class="overview-label">用例标题</div>
            <div class="overview-value">{{ executionSnapshot?.title || execution.testCaseTitle || '-' }}</div>
          </div>
          <div class="overview-item">
            <div class="overview-label">耗时</div>
            <div class="overview-value mono">{{ duration }}</div>
          </div>
          <div class="overview-item">
            <div class="overview-label">模块</div>
            <div class="overview-value">{{ executionSnapshot?.module || '-' }}</div>
          </div>
          <div class="overview-item">
            <div class="overview-label">类型</div>
            <div class="overview-value">{{ snapshotTypeLabel(executionSnapshot?.type) }}</div>
          </div>
          <div class="overview-item">
            <div class="overview-label">优先级</div>
            <div class="overview-value">{{ snapshotPriorityLabel(executionSnapshot?.priority) }}</div>
          </div>
          <div class="overview-item overview-item-full">
            <div class="overview-label">摘要</div>
            <div class="overview-value">{{ execution.summary || '-' }}</div>
          </div>
        </div>

        <!-- 错误提示 -->
        <Transition name="slide-down">
          <el-alert
            v-if="execution.errorMessage"
            :title="execution.errorMessage"
            type="error"
            :closable="false"
            show-icon
            class="error-alert"
          />
        </Transition>

        <!-- 执行中提示 -->
        <Transition name="slide-down">
          <div v-if="execution.status === 'running'" class="running-banner">
            <el-icon class="is-loading" :size="16"><Loading /></el-icon>
            <span>执行进行中，结果每 3 秒自动刷新...</span>
          </div>
        </Transition>
      </section>

      <!-- 录屏回放：放在执行步骤前面，先看录屏再看截图 -->
      <section v-if="hasRecording" class="recording-section">
        <div class="section-head">
          <div class="section-head-text">
            <h2 class="section-title">录屏回放</h2>
            <p class="section-desc">
              {{ recordingVideoUrl ? 'WebM 视频格式' : '图片帧序列格式' }}
            </p>
          </div>
          <el-tag
            :type="recordingVideoUrl ? 'success' : 'info'"
            effect="light"
          >
            {{ recordingVideoUrl ? '视频' : '图片帧' }}
          </el-tag>
        </div>

        <!-- 视频模式 -->
        <div v-if="recordingVideoUrl" class="video-player">
          <video
            :src="recordingVideoUrl"
            controls
            autoplay
            class="video-element"
            @error="videoError = true"
          />
          <div class="video-controls">
            <el-button :icon="Download" @click="downloadVideo">下载视频</el-button>
          </div>
        </div>

        <!-- 图片帧轮播 -->
        <div v-else class="frame-player">
          <div class="frame-display">
            <img
              v-if="currentFrameUrl"
              :src="currentFrameUrl"
              :alt="`帧 ${currentFrameIndex + 1}`"
              class="frame-image"
            />
            <div v-else class="frame-empty">
              <el-icon :size="32"><Picture /></el-icon>
            </div>
          </div>
          <div class="player-controls">
            <el-button
              :icon="isPlaying ? VideoPause : VideoPlay"
              circle
              type="primary"
              @click="togglePlay"
            />
            <el-slider
              v-model="currentFrameIndex"
              :max="Math.max(0, recordingFrames.length - 1)"
              :show-tooltip="false"
              class="frame-slider"
            />
            <span class="frame-counter mono">
              {{ currentFrameIndex + 1 }} / {{ recordingFrames.length }}
            </span>
          </div>
        </div>
      </section>

      <!-- 步骤列表 -->
      <section v-if="steps.length > 0" class="steps-section">
        <div class="section-head">
          <div class="section-head-text">
            <h2 class="section-title">执行步骤</h2>
            <p class="section-desc">共 {{ displayedSteps.length }} 个步骤{{ onlyFailed ? '（仅失败）' : '' }}</p>
          </div>
          <!-- v3.18: 仅显示失败 -->
          <div class="steps-filter">
            <span class="filter-label">仅显示失败</span>
            <el-switch v-model="onlyFailed" />
          </div>
        </div>

        <div class="step-list">
          <article
            v-for="step in displayedSteps"
            :key="step.id || step.stepIndex"
            class="step-card"
            :class="stepResultClass(step.result)"
          >
            <div class="step-card-head">
              <div class="step-index">{{ step.stepIndex }}</div>
              <span class="step-action">{{ step.action || '-' }}</span>
              <div class="step-tags">
                <el-tag
                  v-if="step.strategy"
                  size="small"
                  :type="strategyTagType(step.strategy)"
                  effect="light"
                >{{ strategyLabel(step.strategy) }}</el-tag>
                <el-tag
                  v-if="step.result"
                  size="small"
                  :type="resultTagType(step.result)"
                  effect="dark"
                >{{ resultLabel(step.result) }}</el-tag>
              </div>
            </div>

            <div class="step-card-body">
              <div v-if="step.target" class="step-row">
                <span class="step-label">目标</span>
                <code class="step-code">{{ step.target }}</code>
              </div>
              <div v-if="step.coordinates" class="step-row">
                <span class="step-label">坐标</span>
                <code class="step-code">{{ step.coordinates }}</code>
              </div>
              <div v-if="step.error" class="step-row step-row-error">
                <span class="step-label">错误信息</span>
                <span class="step-error-text">{{ step.error }}</span>
              </div>

              <div
                v-if="step.screenshotBefore || step.screenshotAfter"
                class="screenshots"
              >
                <div v-if="step.screenshotBefore" class="screenshot-item">
                  <div class="screenshot-label">
                    <el-icon :size="12"><Camera /></el-icon>执行前截图
                  </div>
                  <el-image
                    :src="screenshotUrl(step.screenshotBefore)"
                    :preview-src-list="screenshotPreviewList(step)"
                    :initial-index="0"
                    fit="contain"
                    class="screenshot-image"
                  />
                  <div class="screenshot-path">{{ step.screenshotBefore }}</div>
                </div>
                <div v-if="step.screenshotAfter" class="screenshot-item">
                  <div class="screenshot-label">
                    <el-icon :size="12"><Camera /></el-icon>执行后截图
                  </div>
                  <el-image
                    :src="screenshotUrl(step.screenshotAfter)"
                    :preview-src-list="screenshotPreviewList(step)"
                    :initial-index="step.screenshotBefore ? 1 : 0"
                    fit="contain"
                    class="screenshot-image"
                  />
                  <div class="screenshot-path">{{ step.screenshotAfter }}</div>
                </div>
              </div>
            </div>
          </article>
        </div>
      </section>

      <section v-else-if="!loading" class="empty-section">
        <el-empty description="暂无步骤数据" :image-size="100" />
      </section>

    </template>
  </div>
</template>

<script setup>
/**
 * 执行结果页
 * 展示单次用例执行的：
 * - 概览（标题、模块、类型、优先级、状态、耗时、摘要）
 * - 录屏回放（优先 WebM 视频，回退图片帧轮播）
 * - 步骤列表（动作、目标、坐标、策略、结果、截图）
 * 执行中状态会每 3 秒自动轮询刷新。
 */
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ArrowLeft, Loading, Download, Document, VideoPlay, VideoPause, Camera, Picture, CircleClose
} from '@element-plus/icons-vue'
import { getExecution, getExecutionSteps, getExecutionVideoUrl, getExecutionFileUrl, cancelExecution } from '@/api/execution'
import { ElMessage, ElMessageBox } from 'element-plus'
import { openAuthPreview, downloadAuth } from '@/utils/download'

const route = useRoute()
const router = useRouter()
const projectId = route.params.id
const executionId = route.params.eid

const loading = ref(true)
const execution = ref(null)
const steps = ref([])
let pollTimer = null
const cancelling = ref(false)
const videoError = ref(false)
const loadError = ref('')
let initialRetryTimer = null

// v3.16: 执行时用例快照（JSON 字符串 → 对象）
const executionSnapshot = computed(() => {
  const raw = execution.value?.testCaseSnapshot
  if (!raw) return null
  try {
    const parsed = JSON.parse(raw)
    return parsed && typeof parsed === 'object' ? parsed : null
  } catch {
    return null
  }
})

// v3.18: 仅显示失败步骤
const onlyFailed = ref(false)
const displayedSteps = computed(() => {
  if (!onlyFailed.value) return steps.value
  return steps.value.filter((s) => s.result === 'failed')
})

// 录屏播放状态
const currentFrameIndex = ref(0)
const isPlaying = ref(false)
let playTimer = null

// 录屏帧列表（兼容数组或 JSON 字符串）
const recordingFrames = computed(() => {
  const raw = execution.value?.recordingFrames
  if (!raw) return []
  if (Array.isArray(raw)) return raw
  if (typeof raw === 'string') {
    try {
      const parsed = JSON.parse(raw)
      return Array.isArray(parsed) ? parsed : []
    } catch (e) {
      return []
    }
  }
  return []
})

const currentFrameUrl = computed(() => {
  const frame = recordingFrames.value[currentFrameIndex.value]
  if (!frame) return ''
  return getExecutionFileUrl(executionId, String(frame))
})

// v2.9: 视频录屏 URL（优先 WebM 视频，无视频时回退到图片帧）
const recordingVideoUrl = computed(() => {
  return execution.value?.recordingVideoPath && !videoError.value
    ? getExecutionVideoUrl(executionId)
    : ''
})

function screenshotUrl(path) {
  return path ? getExecutionFileUrl(executionId, path) : ''
}

function screenshotPreviewList(step) {
  return [step.screenshotBefore, step.screenshotAfter].filter(Boolean).map((p) => screenshotUrl(p))
}

// 是否有任何形式的录屏（视频或图片帧）
const hasRecording = computed(() => {
  return !!recordingVideoUrl.value || recordingFrames.value.length > 0
})

// v2.9: 下载视频
function downloadVideo() {
  window.open(recordingVideoUrl.value, '_blank')
}

// 帧数变化时校正索引，避免越界
watch(recordingFrames, (frames) => {
  if (frames.length === 0) {
    currentFrameIndex.value = 0
    return
  }
  if (currentFrameIndex.value > frames.length - 1) {
    currentFrameIndex.value = frames.length - 1
  }
})

watch(execution, () => {
  videoError.value = false
})

function togglePlay() {
  if (isPlaying.value) {
    pausePlay()
  } else {
    startPlay()
  }
}

function startPlay() {
  if (recordingFrames.value.length === 0) return
  // 已到最后一帧则从头开始
  if (currentFrameIndex.value >= recordingFrames.value.length - 1) {
    currentFrameIndex.value = 0
  }
  isPlaying.value = true
  playTimer = setInterval(() => {
    if (currentFrameIndex.value < recordingFrames.value.length - 1) {
      currentFrameIndex.value += 1
    } else {
      pausePlay()
    }
  }, 500)
}

function pausePlay() {
  isPlaying.value = false
  if (playTimer) {
    clearInterval(playTimer)
    playTimer = null
  }
}

function downloadReport() {
  downloadAuth(`/api/executions/${executionId}/report?download=1`, 'execution_report.html')
}

// v3.13: 报告在线预览（inline）
function previewReport() {
  openAuthPreview(`/api/executions/${executionId}/report`)
}

// 取消当前执行
async function handleCancel() {
  try {
    await ElMessageBox.confirm(
      '确定取消该执行吗？运行中的步骤会在下一个检查点停止。',
      '确认取消执行',
      { confirmButtonText: '确定取消', cancelButtonText: '继续执行', type: 'warning' }
    )
  } catch {
    return
  }
  cancelling.value = true
  try {
    const res = await cancelExecution(executionId)
    if (res.data?.cancelled) {
      ElMessage.success('取消请求已发出，正在停止...')
    } else {
      ElMessage.info('执行已结束，无需取消')
    }
  } catch {
    // 错误已由响应拦截器统一提示
  } finally {
    cancelling.value = false
  }
}

// 状态标签颜色: passed=success, failed=danger, running=warning, pending=info
const statusTagType = (status) => {
  const map = {
    passed: 'success',
    failed: 'danger',
    running: 'warning',
    pending: 'info',
    cancelled: 'info'
  }
  return map[status] || 'info'
}
const statusLabel = (status) => {
  const map = {
    passed: '通过',
    failed: '失败',
    running: '执行中',
    pending: '等待中',
    cancelled: '已取消'
  }
  return map[status] || status || '-'
}

// 执行时快照合并进概览后的展示辅助
const snapshotTypeLabel = (type) => {
  return { positive: '正向', negative: '异常', boundary: '边界', data: '数据' }[type] || type || '-'
}
const snapshotPriorityLabel = (priority) => {
  return priority || '-'
}

// 步骤卡片样式（根据结果）
function stepResultClass(result) {
  if (!result) return ''
  return `step-result-${result}`
}

// 策略标签
const strategyTagType = (strategy) => {
  const map = {
    visual: 'warning',
    dom: 'primary',
    manual: 'info',
    skipped: 'info'
  }
  return map[strategy] || 'info'
}
const strategyLabel = (strategy) => {
  const map = {
    visual: '视觉',
    dom: 'DOM',
    manual: '人工',
    skipped: '跳过'
  }
  return map[strategy] || strategy || '-'
}

// 结果标签
const resultTagType = (result) => {
  const map = {
    passed: 'success',
    failed: 'danger',
    skipped: 'info'
  }
  return map[result] || 'info'
}
const resultLabel = (result) => {
  const map = {
    passed: '通过',
    failed: '失败',
    skipped: '跳过'
  }
  return map[result] || result || '-'
}

// 耗时计算
const duration = computed(() => {
  if (!execution.value || !execution.value.startTime) return '-'
  const start = new Date(execution.value.startTime)
  const end = execution.value.endTime
    ? new Date(execution.value.endTime)
    : new Date()
  const diff = end - start
  if (isNaN(diff) || diff < 0) return '-'
  const seconds = Math.floor(diff / 1000)
  if (seconds < 60) return `${seconds} 秒`
  const minutes = Math.floor(seconds / 60)
  const remainSec = seconds % 60
  return `${minutes} 分 ${remainSec} 秒`
})

async function loadExecution() {
  try {
    const res = await getExecution(executionId)
    execution.value = res.data
    loadError.value = ''
    // 如果仍在执行中，启动轮询
    if (execution.value && execution.value.status === 'running') {
      schedulePoll()
    }
  } catch (e) {
    loadError.value = e?.message || '加载失败'
  }
}

async function loadSteps() {
  try {
    const res = await getExecutionSteps(executionId)
    steps.value = res.data || []
  } catch (e) {
    loadError.value = loadError.value || e?.message || '加载失败'
  }
}

async function reload() {
  loadError.value = ''
  loading.value = true
  await Promise.all([loadExecution(), loadSteps()])
  loading.value = false
}

// 每 3 秒轮询一次，直到 status != running
function schedulePoll() {
  if (pollTimer) return
  pollTimer = setInterval(async () => {
    try {
      const res = await getExecution(executionId)
      execution.value = res.data
      await loadSteps()
      if (execution.value && execution.value.status !== 'running') {
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

function goBack() {
  router.push(`/projects/${projectId}/testcases`)
}

onMounted(async () => {
  loading.value = true
  await Promise.all([loadExecution(), loadSteps()])
  loading.value = false
  // 执行记录刚创建时可能短暂不可见，做一次兜底重试，避免结果页空白
  if (!execution.value && !loadError.value) {
    initialRetryTimer = setTimeout(() => {
      reload()
    }, 1000)
  }
})

onUnmounted(() => {
  if (initialRetryTimer) {
    clearTimeout(initialRetryTimer)
    initialRetryTimer = null
  }
  stopPoll()
  pausePlay()
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
  padding: 14px 16px;
  box-shadow: var(--shadow-xs);
  margin-bottom: var(--space-md);
}

.overview-section .section-title {
  font-size: 15px;
}

.overview-section .section-desc {
  font-size: 12px;
}

.overview-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 10px;
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
  gap: 8px;
}

.status-running-icon {
  color: var(--color-warning);
}

/* 状态胶囊 */
.status-pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 12px;
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

.overview-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 8px;
}

.overview-item {
  padding: 8px 10px;
  background: #f8fafc;
  border: 1px solid var(--card-border-light);
  border-radius: var(--radius-md);

  &.overview-item-full {
    grid-column: 1 / -1;
  }
}

.overview-label {
  font-size: 11px;
  color: var(--text-tertiary);
  margin-bottom: 2px;
}

.overview-value {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-primary);
  word-break: break-word;

  &.mono {
    font-family: 'Consolas', 'Monaco', monospace;
  }
}

.error-alert {
  margin-top: 10px;
}

.running-banner {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
  padding: 8px 12px;
  background: var(--color-warning-bg);
  color: var(--color-warning);
  border-radius: var(--radius-md);
  font-size: 13px;
  font-weight: 500;
}

/* ===== 步骤列表 ===== */
.steps-section {
  margin-bottom: var(--space-lg);
}

.steps-filter {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--text-secondary);
}

.step-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-md);
}

.step-card {
  border: 1px solid var(--card-border);
  border-radius: var(--radius-lg);
  background: var(--bg-surface);
  overflow: hidden;
  box-shadow: var(--shadow-xs);
  transition: all var(--transition-normal);

  /* v3.18: 失败/通过步骤高亮 */
  &.step-result-failed {
    border-color: var(--color-danger);
    background: linear-gradient(180deg, var(--color-danger-bg), var(--bg-surface));
  }

  &.step-result-passed {
    border-color: rgba(16, 185, 129, 0.45);
  }

  &:hover {
    box-shadow: var(--shadow-sm);
    border-color: var(--brand-primary-lighter);
  }

  &.step-result-passed {
    border-left: 3px solid var(--color-success);
  }

  &.step-result-failed {
    border-left: 3px solid var(--color-danger);
  }

  &.step-result-skipped {
    border-left: 3px solid var(--text-tertiary);
  }
}

.step-card-head {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
  background: #f8fafc;
  border-bottom: 1px solid var(--card-border-light);
}

.step-index {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: var(--radius-md);
  background: var(--brand-primary);
  color: #fff;
  font-size: 13px;
  font-weight: 700;
}

.step-action {
  flex: 1;
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
  word-break: break-word;
}

.step-tags {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
}

.step-card-body {
  padding: 12px 16px;
}

.step-row {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-bottom: 8px;
  font-size: 13px;
  line-height: 1.6;

  &:last-child {
    margin-bottom: 0;
  }
}

.step-label {
  flex-shrink: 0;
  padding: 1px 8px;
  border-radius: var(--radius-sm);
  background: var(--bg-base);
  color: var(--text-tertiary);
  font-size: 12px;
  font-weight: 500;
}

.step-code {
  flex: 1;
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 12px;
  color: var(--brand-primary);
  background: var(--el-color-primary-light-9);
  padding: 2px 6px;
  border-radius: var(--radius-sm);
  word-break: break-all;
}

.step-row-error .step-error-text {
  flex: 1;
  color: var(--color-danger);
  font-size: 12px;
  word-break: break-word;
}

.screenshots {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--space-md);
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px dashed var(--card-border-light);
}

.screenshot-item {
  min-width: 0;
}

.screenshot-label {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: var(--text-tertiary);
  margin-bottom: 4px;
}

.screenshot-image {
  width: 100%;
  max-height: 260px;
  border: 1px solid var(--card-border-light);
  border-radius: var(--radius-sm);
  background: #000;
  cursor: zoom-in;
}

.screenshot-path {
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 12px;
  color: var(--text-secondary);
  background: #f8fafc;
  margin-top: 6px;
  padding: 6px 10px;
  border-radius: var(--radius-sm);
  border: 1px solid var(--card-border-light);
  word-break: break-all;
}

/* ===== 录屏 ===== */
.recording-section {
  background: var(--bg-surface);
  border: 1px solid var(--card-border);
  border-radius: var(--radius-lg);
  padding: 20px;
  box-shadow: var(--shadow-xs);
  margin-bottom: var(--space-lg);
}

.video-player {
  display: flex;
  flex-direction: column;
  gap: var(--space-md);
}

.video-element {
  width: 100%;
  max-height: 480px;
  background: #000;
  border-radius: var(--radius-md);
}

.video-controls {
  display: flex;
  justify-content: flex-end;
}

.frame-player {
  display: flex;
  flex-direction: column;
  gap: var(--space-md);
}

.frame-display {
  border: 1px solid var(--card-border);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-sm);
  background: #000;
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: 240px;
  overflow: hidden;
}

.frame-image {
  max-width: 100%;
  max-height: 480px;
  height: auto;
  display: block;
}

.frame-empty {
  color: var(--text-muted);
}

.player-controls {
  display: flex;
  align-items: center;
  gap: var(--space-md);
}

.frame-slider {
  flex: 1;
}

.frame-counter {
  font-size: 13px;
  color: var(--text-secondary);
  white-space: nowrap;
  min-width: 80px;
  text-align: right;
}

.mono {
  font-family: 'Consolas', 'Monaco', monospace;
}

/* ===== 响应式 ===== */
@media (max-width: 768px) {
  .screenshots {
    grid-template-columns: 1fr;
  }

  .step-card-head {
    flex-wrap: wrap;
  }
}
</style>
