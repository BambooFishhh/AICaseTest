import { defineStore } from 'pinia'
import { ref } from 'vue'
import { getProject } from '@/api/project'

// 终态状态：分析完成或失败，无需继续轮询
const TERMINAL_STATUSES = ['analyzed', 'completed', 'failed']

export const useProjectStore = defineStore('project', () => {
  const currentProject = ref(null)
  const pollingTimer = ref(null)
  const loading = ref(false)

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
  function startPolling(id, onStatusChange) {
    stopPolling()
    const poll = async () => {
      try {
        const res = await getProject(id)
        const prevStatus = currentProject.value?.status
        currentProject.value = res.data
        const nextStatus = res.data?.status
        if (typeof onStatusChange === 'function') {
          onStatusChange(nextStatus, prevStatus, res.data)
        }
        if (nextStatus && TERMINAL_STATUSES.includes(nextStatus)) {
          stopPolling()
        }
      } catch (err) {
        // 轮询出错时停止，避免持续报错
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
    loading.value = false
  }

  return {
    currentProject,
    loading,
    pollingTimer,
    fetchProject,
    startPolling,
    stopPolling,
    reset
  }
})
