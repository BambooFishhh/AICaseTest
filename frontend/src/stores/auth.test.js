import { describe, it, expect, vi, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from './auth'

vi.mock('@/api/auth', () => ({
  login: vi.fn(async () => ({
    data: { token: 'token-1', user: { username: 'admin', role: 'ADMIN' } }
  })),
  register: vi.fn(),
  getMe: vi.fn()
}))

describe('auth store', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
  })

  it('login persists token and admin role', async () => {
    const store = useAuthStore()
    await store.login({ username: 'admin', password: 'x' })

    expect(store.token).toBe('token-1')
    expect(store.isAdmin).toBe(true)
    expect(localStorage.getItem('aicase-token')).toBe('token-1')
  })

  it('logout clears persisted auth state', async () => {
    const store = useAuthStore()
    await store.login({ username: 'admin', password: 'x' })
    store.logout()

    expect(store.token).toBe('')
    expect(store.user).toBeNull()
    expect(localStorage.getItem('aicase-token')).toBeNull()
  })
})
