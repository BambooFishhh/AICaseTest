import { describe, it, expect, vi, beforeAll, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'

import router from '@/router'
import App from '@/App.vue'

// 本文件只验证「路由 / 守卫 / 子导航」三处接线，不验证数据与渲染细节
// （那两处已由 views/EvidenceConflicts.test.js 与 api/evidence.test.js 覆盖）。
// 故把 api 层整体 mock 掉——接线的断点（路由路径写错、懒加载分块导入失败、
// 守卫漏配、导航项漏挂）都与接口返回什么无关。
vi.mock('@/api/evidence', () => ({
  listEvidenceConflicts: vi.fn(() => Promise.resolve({ data: { conflicts: [], total: 0, pending: 0 } })),
  submitEvidenceVerdict: vi.fn(() => Promise.resolve({ data: {} }))
}))
vi.mock('@/api/auth', () => ({
  login: vi.fn(),
  register: vi.fn(),
  getMe: vi.fn(),
  changePassword: vi.fn()
}))
// App 挂载时若未被 stub 掉，会连带渲染 TestCaseList → 需要该模块的导出都存在
vi.mock('@/api/testcase', async (importOriginal) => {
  const actual = await importOriginal()
  return Object.fromEntries(Object.keys(actual).map((key) => [key, vi.fn()]))
})

const PID = 'ffff967b'
const PATH = `/projects/${PID}/evidence-conflicts`
const TOKEN_KEY = 'aicase-token'

beforeAll(() => {
  // el-table 依赖 ResizeObserver，jsdom 不提供；缺了会在挂载时直接抛错
  global.ResizeObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
})

/** 把 jsdom 视口调宽到桌面尺寸（见下方 sidebarCollapsed 的说明） */
function wideViewport() {
  Object.defineProperty(window, 'innerWidth', { value: 1440, writable: true, configurable: true })
}

/**
 * 造一个「已登录」的本地态。**必须同时写 token 与 user**：
 * App.vue 的 onMounted 在「有 token 但无 user」时会调 fetchMe()，
 * 失败即 logout() 并把 window.location 指向 /login。
 */
function loginAsFakeUser() {
  localStorage.setItem(TOKEN_KEY, 'fake-token')
  localStorage.setItem(
    'aicase-user',
    JSON.stringify({ id: 'u-test', username: 'admin', displayName: '管理员' })
  )
}

beforeEach(() => {
  localStorage.clear()
  vi.clearAllMocks()
})

describe('证据冲突页的路由接线', () => {
  it('路由表已登记该页，name 与 meta 正确', () => {
    const resolved = router.resolve(PATH)
    expect(resolved.name).toBe('EvidenceConflicts')
    expect(resolved.meta.title).toBe('证据冲突裁决')
    expect(resolved.meta.breadcrumb).toEqual(['项目列表', '项目详情', '证据冲突裁决'])
  })

  it('未登录时被守卫拦到登录页，并带 redirect 供登录后回跳', async () => {
    localStorage.removeItem(TOKEN_KEY)
    await router.push(PATH)
    expect(router.currentRoute.value.path).toBe('/login')
    expect(router.currentRoute.value.query.redirect).toBe(PATH)
  })

  it('已登录时能真实懒加载并渲染该页（可抓到分块导入路径写错/组件挂载报错）', async () => {
    loginAsFakeUser()
    // 必须先完成导航再挂载：router-view 挂载时渲染的是「当前」路由，
    // 上一条用例的守卫重定向会把当前位置留在 /login，直接挂载会去渲染 Login.vue
    await router.push(PATH)
    await router.isReady()

    const wrapper = mount(
      { template: '<router-view />' },
      { global: { plugins: [router, createPinia(), ElementPlus] } }
    )
    await flushPromises()

    expect(wrapper.find('.evidence-conflicts').exists()).toBe(true)
    expect(wrapper.find('h1.page-title').text()).toBe('证据冲突裁决')
    wrapper.unmount()
  })
})

describe('项目子导航中的入口', () => {
  it('「证据冲突」出现在项目子导航，且 href 指向该路由', async () => {
    loginAsFakeUser()
    // 子导航由 route.params.id 推导（App.vue: projectId = route.params.id || ''），
    // 故必须在含 :id 的项目内路由下挂载。这里注册一条探针路由替代真实页面：
    // 子导航照常计算，但不引入任何页面自身的副作用（拉接口/定时器）。
    const PROBE = `/projects/${PID}/subnav-probe`
    router.addRoute({
      path: '/projects/:id/subnav-probe',
      name: 'SubNavProbe',
      component: { name: 'SubNavProbe', template: '<div class="subnav-probe" />' }
    })
    // 子导航外层是 v-if="projectId && !sidebarCollapsed"，而 handleResize() 在
    // window.innerWidth <= 1024 时会折叠侧栏；**jsdom 默认 innerWidth 正好是 1024**
    // → 不调宽视口就一个 subnav-item 都渲染不出来。
    wideViewport()
    await router.push(PROBE)
    await router.isReady()

    const wrapper = mount(App, {
      global: { plugins: [router, createPinia(), ElementPlus] }
    })
    await flushPromises()

    const links = wrapper.findAll('a.subnav-item')
    const labels = links.map((a) => a.text())
    const entry = links.find((a) => a.text().includes('证据冲突'))
    expect(entry, `子导航中未找到「证据冲突」，实际为：${labels.join(' / ')}`).toBeTruthy()
    expect(entry.attributes('href')).toBe(PATH)

    wrapper.unmount()
  }, 20000)
})
