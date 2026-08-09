<template>
  <div class="execution-result" v-loading="loading">
    <div class="page-header">
      <h2>执行结果</h2>
      <el-button :icon="ArrowLeft" @click="goBack">返回</el-button>
    </div>

    <!-- 执行概览 -->
    <el-card v-if="execution" class="overview-card">
      <template #header>
        <div class="card-header">
          <span>执行概览</span>
          <el-button type="primary" :icon="Download" @click="downloadReport">
            下载报告
          </el-button>
        </div>
      </template>
      <el-descriptions :column="2" border>
        <el-descriptions-item label="用例标题">
          {{ execution.testCaseTitle || '-' }}
        </el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="statusTagType(execution.status)" size="small">
            {{ statusLabel(execution.status) }}
          </el-tag>
          <span v-if="execution.status === 'running'" class="running-hint">
            <el-icon class="is-loading"><Loading /></el-icon>
            执行中，自动刷新...
          </span>
        </el-descriptions-item>
        <el-descriptions-item label="耗时">
          {{ duration }}
        </el-descriptions-item>
        <el-descriptions-item label="摘要">
          {{ execution.summary || '-' }}
        </el-descriptions-item>
      </el-descriptions>
      <el-alert
        v-if="execution.errorMessage"
        :title="execution.errorMessage"
        type="error"
        :closable="false"
        show-icon
        class="error-alert"
      />
    </el-card>

    <el-empty
      v-else-if="!loading"
      description="未找到执行记录"
      :image-size="80"
    />

    <!-- 步骤列表 -->
    <el-card v-if="execution && steps.length > 0" class="steps-card">
      <template #header>执行步骤（{{ steps.length }}）</template>
      <div class="step-list">
        <div v-for="step in steps" :key="step.id || step.stepIndex" class="step-item">
          <div class="step-header">
            <el-tag size="small" type="info">步骤 {{ step.stepIndex }}</el-tag>
            <span class="step-action">{{ step.action || '-' }}</span>
            <el-tag
              v-if="step.strategy"
              size="small"
              :type="strategyTagType(step.strategy)"
            >
              {{ strategyLabel(step.strategy) }}
            </el-tag>
            <el-tag
              v-if="step.result"
              size="small"
              :type="resultTagType(step.result)"
            >
              {{ resultLabel(step.result) }}
            </el-tag>
          </div>
          <div class="step-body">
            <div v-if="step.target" class="step-row">
              <span class="step-label">目标:</span>
              <code>{{ step.target }}</code>
            </div>
            <div v-if="step.coordinates" class="step-row">
              <span class="step-label">坐标:</span>
              <code>{{ step.coordinates }}</code>
            </div>
            <div v-if="step.error" class="step-row error-row">
              <span class="step-label">错误信息:</span>
              <span class="error-text">{{ step.error }}</span>
            </div>
            <div
              v-if="step.screenshotBefore || step.screenshotAfter"
              class="screenshots"
            >
              <div v-if="step.screenshotBefore" class="screenshot-item">
                <div class="screenshot-label">执行前截图</div>
                <div class="screenshot-path">{{ step.screenshotBefore }}</div>
              </div>
              <div v-if="step.screenshotAfter" class="screenshot-item">
                <div class="screenshot-label">执行后截图</div>
                <div class="screenshot-path">{{ step.screenshotAfter }}</div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </el-card>

    <el-card v-if="execution && steps.length === 0 && !loading" class="steps-card">
      <el-empty description="暂无步骤数据" :image-size="60" />
    </el-card>

    <!-- 录屏播放器 -->
    <el-card
      v-if="recordingFrames && recordingFrames.length > 0"
      class="recording-card"
    >
      <template #header>录屏回放</template>
      <div class="recording-player">
        <div class="frame-display">
          <img
            v-if="currentFrameUrl"
            :src="currentFrameUrl"
            :alt="`帧 ${currentFrameIndex + 1}`"
            class="frame-image"
          />
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
          <span class="frame-counter">
            {{ currentFrameIndex + 1 }} / {{ recordingFrames.length }}
          </span>
        </div>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ArrowLeft,
  Loading,
  Download,
  VideoPlay,
  VideoPause
} from '@element-plus/icons-vue'
import { getExecution, getExecutionSteps } from '@/api/execution'

const RECORDING_BASE_URL = 'http://localhost:8000'

const route = useRoute()
const router = useRouter()
const projectId = route.params.id
const executionId = route.params.eid

const loading = ref(true)
const execution = ref(null)
const steps = ref([])
let pollTimer = null

// 录屏播放相关状态
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
  // 拼接后端 base URL，去掉开头的斜杠避免重复
  const normalized = String(frame).replace(/^\//, '')
  return `${RECORDING_BASE_URL}/${normalized}`
})

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
  window.open(`/api/executions/${executionId}/report`, '_blank')
}

// 状态标签颜色: passed=success, failed=danger, running=warning, pending=info
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
    // 如果仍在执行中，启动轮询
    if (execution.value && execution.value.status === 'running') {
      schedulePoll()
    }
  } catch (e) {
    // 错误已由响应拦截器统一提示
  }
}

async function loadSteps() {
  try {
    const res = await getExecutionSteps(executionId)
    steps.value = res.data || []
  } catch (e) {
    // 错误已由响应拦截器统一提示
  }
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
})

onUnmounted(() => {
  stopPoll()
  pausePlay()
})
</script>

<style scoped>
.execution-result {
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
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.error-alert {
  margin-top: 12px;
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
.steps-card {
  margin-bottom: 20px;
}
.step-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.step-item {
  border: 1px solid #ebeef5;
  border-radius: 6px;
  padding: 12px 14px;
  background: #fafafa;
}
.step-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
  flex-wrap: wrap;
}
.step-action {
  font-weight: 600;
  color: #303133;
  flex: 1;
  min-width: 120px;
}
.step-body {
  padding-left: 4px;
}
.step-row {
  margin-bottom: 6px;
  font-size: 13px;
  line-height: 1.6;
}
.step-row .step-label {
  color: #909399;
  margin-right: 6px;
}
.step-row code {
  background: #f0f0f0;
  padding: 1px 6px;
  border-radius: 3px;
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 12px;
  color: #e63946;
}
.error-row .error-text {
  color: #f56c6c;
}
.screenshots {
  display: flex;
  gap: 16px;
  margin-top: 8px;
  flex-wrap: wrap;
}
.screenshot-item {
  flex: 1;
  min-width: 200px;
}
.screenshot-label {
  font-size: 12px;
  color: #909399;
  margin-bottom: 4px;
}
.screenshot-path {
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 12px;
  color: #606266;
  background: #f0f0f0;
  padding: 4px 8px;
  border-radius: 3px;
  word-break: break-all;
}
.recording-card {
  margin-bottom: 20px;
}
.recording-player {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.frame-display {
  border: 1px solid #ebeef5;
  border-radius: 6px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.1);
  background: #000;
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: 200px;
  overflow: hidden;
}
.frame-image {
  max-width: 100%;
  height: auto;
  display: block;
}
.player-controls {
  display: flex;
  align-items: center;
  gap: 12px;
}
.frame-slider {
  flex: 1;
}
.frame-counter {
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 13px;
  color: #606266;
  white-space: nowrap;
  min-width: 80px;
  text-align: right;
}
</style>
