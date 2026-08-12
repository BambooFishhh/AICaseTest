import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { login as apiLogin, register as apiRegister, getMe } from '@/api/auth'

// v4.0: 登录态 Store
export const useAuthStore = defineStore('auth', () => {
  const token = ref(localStorage.getItem('aicase-token') || '')
  let cachedUser = null
  try {
    cachedUser = JSON.parse(localStorage.getItem('aicase-user') || 'null')
  } catch {
    cachedUser = null
  }
  const user = ref(cachedUser)

  function persist() {
    localStorage.setItem('aicase-token', token.value)
    localStorage.setItem('aicase-user', JSON.stringify(user.value))
  }

  async function login(payload) {
    const res = await apiLogin(payload)
    token.value = res.data.token
    user.value = res.data.user
    persist()
    return res.data
  }

  async function register(payload) {
    const res = await apiRegister(payload)
    token.value = res.data.token
    user.value = res.data.user
    persist()
    return res.data
  }

  async function fetchMe() {
    if (!token.value) return null
    const res = await getMe()
    user.value = res.data
    persist()
    return res.data
  }

  function logout() {
    token.value = ''
    user.value = null
    localStorage.removeItem('aicase-token')
    localStorage.removeItem('aicase-user')
  }

  const isAdmin = computed(() => user.value?.role === 'ADMIN')

  return { token, user, isAdmin, login, register, fetchMe, logout }
})
