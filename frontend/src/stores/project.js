import { defineStore } from 'pinia'
import { ref } from 'vue'
import { getProject } from '@/api/project'

// 终态状态：分析完成或失败，无需继续轮询
const TERMINAL_STATUSES = ['analyzed', 'completed', 'failed']

export const useProjectStore = defineStore('project', () => {
  const currentProject = ref(null)
  const pollingTimer = ref(null)
  const loading = ref(false)
  // v1.6: 实时生成进度信息，供组件展示（如 "正在生成第 2/5 个模块: 订单状态机"）
  const progressMessage = ref('')

  // 拉取项目详情并设置 currentProject
  async function fetchProject(id) {
    loading.value = true
    try {
      const res = await getProject(id)
      currentProject.value = res.data
      return res.data
    } finally {
      loading.value = false
    }
  }

  // 开始轮询项目状态，每 3 秒一次
  // 当状态进入 analyzed/completed/failed 时停止
  // v1.6: 轮询时实时更新 progressMessage，终态时清空
  function startPolling(id, onStatusChange) {
    stopPolling()
    const poll = async () => {
      try {
        const res = await getProject(id)
        const prevStatus = currentProject.value?.status
        const project = res.data
        currentProject.value = project
        const nextStatus = project?.status
        // v1.6: 实时同步进度信息；终态时清空
        progressMessage.value = project?.progress || ''
        if (typeof onStatusChange === 'function') {
          // 第三个参数传整个 project，组件可读取 errorMessage
          onStatusChange(nextStatus, prevStatus, project)
        }
        if (nextStatus && TERMINAL_STATUSES.includes(nextStatus)) {
          progressMessage.value = ''
          stopPolling()
        }
      } catch (err) {
        // 轮询出错时停止，避免持续报错
        progressMessage.value = ''
        stopPolling()
        if (typeof onStatusChange === 'function') {
          onStatusChange('failed', currentProject.value?.status, null, err)
        }
      }
    }
    // 立即执行一次，再设置定时器
    poll()
    pollingTimer.value = setInterval(poll, 3000)
  }

  // 停止轮询
  function stopPolling() {
    if (pollingTimer.value) {
      clearInterval(pollingTimer.value)
      pollingTimer.value = null
    }
  }

  // 重置 store
  function reset() {
    stopPolling()
    currentProject.value = null
    progressMessage.value = ''
    loading.value = false
  }

  return {
    currentProject,
    loading,
    pollingTimer,
    progressMessage,
    fetchProject,
    startPolling,
    stopPolling,
    reset
  }
})
