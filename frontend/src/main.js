import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import './style.css'
import router from './router'

// Element Plus 全量引入
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'

// 全局组件
import StateMachineViewer from './components/StateMachineViewer.vue'
import ProgressTracker from './components/ProgressTracker.vue'
import TestCaseCard from './components/TestCaseCard.vue'

const app = createApp(App)

// 注册 Pinia 状态管理
app.use(createPinia())

// 注册路由
app.use(router)

// 注册 Element Plus
app.use(ElementPlus)

// 全量注册 Element Plus 图标
for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}

// 注册全局业务组件
app.component('StateMachineViewer', StateMachineViewer)
app.component('ProgressTracker', ProgressTracker)
app.component('TestCaseCard', TestCaseCard)

app.mount('#app')
