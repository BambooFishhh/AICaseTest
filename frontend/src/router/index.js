import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  {
    path: '/',
    redirect: '/projects'
  },
  {
    path: '/projects',
    name: 'ProjectList',
    component: () => import('@/views/ProjectList.vue'),
    meta: { title: '项目列表' }
  },
  {
    path: '/projects/create',
    name: 'ProjectCreate',
    component: () => import('@/views/ProjectCreate.vue'),
    meta: { title: '创建项目' }
  },
  {
    path: '/projects/:id',
    name: 'ProjectDetail',
    component: () => import('@/views/ProjectDetail.vue'),
    meta: { title: '项目详情' }
  },
  {
    path: '/projects/:id/analysis',
    name: 'CodeAnalysis',
    component: () => import('@/views/CodeAnalysis.vue'),
    meta: { title: '代码分析' }
  },
  {
    path: '/projects/:id/testcases',
    name: 'TestCaseList',
    component: () => import('@/views/TestCaseList.vue'),
    meta: { title: '测试用例' }
  },
  {
    path: '/projects/:id/state-machines',
    name: 'StateMachineOverview',
    component: () => import('@/views/StateMachineOverview.vue'),
    meta: { title: '状态机覆盖图' }
  },
  {
    path: '/projects/:id/mindmap',
    name: 'MindMapPreview',
    component: () => import('@/views/MindMapPreview.vue'),
    meta: { title: '思维导图' }
  },
  {
    path: '/settings',
    name: 'Settings',
    component: () => import('@/views/Settings.vue'),
    meta: { title: '系统设置' }
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.afterEach((to) => {
  if (to.meta && to.meta.title) {
    document.title = `${to.meta.title} - AI测试用例生成系统`
  } else {
    document.title = 'AI测试用例生成系统'
  }
})

export default router
