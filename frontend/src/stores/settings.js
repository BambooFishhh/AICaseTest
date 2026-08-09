import { defineStore } from 'pinia'
import { ref } from 'vue'
import { getSettings } from '@/api/settings'

export const useSettingsStore = defineStore('settings', () => {
  const settings = ref(null)
  const loading = ref(false)

  async function fetchSettings() {
    loading.value = true
    try {
      const res = await getSettings()
      settings.value = res.data
      return res.data
    } finally {
      loading.value = false
    }
  }

  function setSettings(data) {
    settings.value = data
  }

  function reset() {
    settings.value = null
    loading.value = false
  }

  return {
    settings,
    loading,
    fetchSettings,
    setSettings,
    reset
  }
})
