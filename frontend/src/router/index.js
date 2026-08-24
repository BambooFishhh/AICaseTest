import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  {
    path: '/',
    redirect: '/projects'
  },
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/Login.vue'),
    meta: { title: '登录', public: true, breadcrumb: ['登录'] }
  },
  {
    path: '/register',
    name: 'Register',
    component: () => import('@/views/Register.vue'),
    meta: { title: '注册', public: true, breadcrumb: ['注册'] }
  },
  {
    path: '/dashboard',
    name: 'Dashboard',
    component: () => import('@/views/Dashboard.vue'),
    meta: { title: '仪表盘', breadcrumb: ['仪表盘'] }
  },
  {
    path: '/projects',
    name: 'ProjectList',
    component: () => import('@/views/ProjectList.vue'),
    meta: { title: '项目列表', breadcrumb: ['项目列表'] }
  },
  {
    path: '/projects/create',
    name: 'ProjectCreate',
    component: () => import('@/views/ProjectCreate.vue'),
    meta: { title: '创建项目', breadcrumb: ['项目列表', '创建项目'] }
  },
  {
    path: '/projects/:id',
    name: 'ProjectDetail',
    component: () => import('@/views/ProjectDetail.vue'),
    meta: { title: '项目详情', breadcrumb: ['项目列表', '项目详情'] }
  },
  {
    path: '/projects/:id/analysis',
    name: 'CodeAnalysis',
    component: () => import('@/views/CodeAnalysis.vue'),
    meta: { title: '代码分析', breadcrumb: ['项目列表', '项目详情', '代码分析'] }
  },
  {
    path: '/projects/:id/testcases',
    name: 'TestCaseList',
    component: () => import('@/views/TestCaseList.vue'),
    meta: { title: '测试用例', breadcrumb: ['项目列表', '项目详情', '测试用例'] }
  },
  {
    path: '/projects/:id/executions',
    name: 'ExecutionHistory',
    component: () => import('@/views/ExecutionHistory.vue'),
    meta: { title: '执行历史', breadcrumb: ['项目列表', '项目详情', '执行历史'] }
  },
  {
    path: '/projects/:id/executions/:eid',
    name: 'ExecutionResult',
    component: () => import('@/views/ExecutionResult.vue'),
    meta: { title: '执行结果', breadcrumb: ['项目列表', '项目详情', '执行结果'] }
  },
  {
    path: '/projects/:id/batches/:batchId',
    name: 'BatchResult',
    component: () => import('@/views/BatchResult.vue'),
    meta: { title: '批次执行结果', breadcrumb: ['项目列表', '项目详情', '批次执行结果'] }
  },
  {
    path: '/projects/:id/state-machines',
    name: 'StateMachineOverview',
    component: () => import('@/views/StateMachineOverview.vue'),
    meta: { title: '状态机覆盖图', breadcrumb: ['项目列表', '项目详情', '状态机覆盖图'] }
  },
  {
    path: '/projects/:id/mindmap',
    name: 'MindMapPreview',
    component: () => import('@/views/MindMapPreview.vue'),
    meta: { title: '思维导图', breadcrumb: ['项目列表', '项目详情', '思维导图'] }
  },
  {
    path: '/settings',
    name: 'Settings',
    component: () => import('@/views/Settings.vue'),
    meta: { title: '系统设置', breadcrumb: ['系统设置'] }
  },
  {
    path: '/groups',
    name: 'Groups',
    component: () => import('@/views/Groups.vue'),
    meta: { title: '项目组', breadcrumb: ['项目组'] }
  },
  {
    path: '/tasks',
    name: 'TaskCenter',
    component: () => import('@/views/TaskCenter.vue'),
    meta: { title: '任务中心', admin: true, breadcrumb: ['任务中心'] }
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

// v4.0: 登录守卫
router.beforeEach((to) => {
  const token = localStorage.getItem('aicase-token')
  if (to.meta.public) {
    if (token && (to.path === '/login' || to.path === '/register')) {
      return '/projects'
    }
    return true
  }
  if (!token) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  return true
})

router.afterEach((to) => {
  if (to.meta && to.meta.title) {
    document.title = `${to.meta.title} - AI测试用例生成系统`
  } else {
    document.title = 'AI测试用例生成系统'
  }
})

export default router
