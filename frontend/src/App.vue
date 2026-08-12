<template>
  <div class="app-shell">
    <!-- 侧边栏 -->
    <aside class="app-sidebar" :class="{ collapsed: sidebarCollapsed }">
      <div class="sidebar-header">
        <div class="logo-mark">
          <span class="logo-text-letter">AI</span>
        </div>
        <span v-if="!sidebarCollapsed" class="logo-label">AICaseTest</span>
      </div>

      <nav class="sidebar-nav">
        <router-link
          v-for="item in navItems"
          :key="item.path"
          :to="item.path"
          class="nav-item"
          active-class="active"
        >
          <el-icon :size="18"><component :is="item.icon" /></el-icon>
          <span v-if="!sidebarCollapsed" class="nav-label">{{ item.label }}</span>
        </router-link>
      </nav>

      <!-- v3.17: 项目内二级导航 -->
      <div v-if="projectId && !sidebarCollapsed" class="sidebar-subnav">
        <div class="subnav-title">当前项目</div>
        <router-link
          v-for="item in projectSubNav"
          :key="item.path"
          :to="item.path"
          class="subnav-item"
          :class="{ active: route.path === item.path }"
        >
          <el-icon :size="15"><component :is="item.icon" /></el-icon>
          <span>{{ item.label }}</span>
        </router-link>
      </div>

      <div class="sidebar-footer">
        <button class="collapse-btn" @click="sidebarCollapsed = !sidebarCollapsed">
          <el-icon :size="16">
            <Fold v-if="!sidebarCollapsed" />
            <Expand v-else />
          </el-icon>
        </button>
      </div>
    </aside>

    <!-- 主区域 -->
    <div class="app-main">
      <!-- 顶栏 -->
      <header class="app-topbar">
        <div class="topbar-left">
          <!-- v3.17: 面包屑 -->
          <nav class="breadcrumb">
            <template v-for="(crumb, idx) in breadcrumbs" :key="idx">
              <span v-if="idx > 0" class="breadcrumb-sep">/</span>
              <router-link
                v-if="idx < breadcrumbs.length - 1 && crumbLink(idx, crumb)"
                :to="crumbLink(idx, crumb)"
                class="breadcrumb-link"
              >
                {{ crumb }}
              </router-link>
              <span v-else class="breadcrumb-current">{{ crumb }}</span>
            </template>
          </nav>
        </div>
        <div class="topbar-right">
          <span class="version-tag">v4.0</span>
        </div>
      </header>

      <!-- 内容区域 -->
      <main class="app-content">
        <router-view />
      </main>
    </div>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { useRoute } from 'vue-router'
import {
  FolderOpened, Setting, Fold, Expand, DataAnalysis, View, Document, Clock, Share, Picture
} from '@element-plus/icons-vue'

const route = useRoute()
const sidebarCollapsed = ref(false)

const navItems = [
  { path: '/dashboard', label: '仪表盘', icon: DataAnalysis },
  { path: '/projects', label: '项目列表', icon: FolderOpened },
  { path: '/settings', label: '系统设置', icon: Setting }
]

// v3.17: 面包屑
const breadcrumbs = computed(() => {
  const bc = route.meta?.breadcrumb
  return Array.isArray(bc) && bc.length ? bc : [route.meta?.title || 'AI 测试用例生成系统']
})

function crumbLink(idx, crumb) {
  if (crumb === '项目列表') return '/projects'
  if (crumb === '项目详情' && route.params.id) return `/projects/${route.params.id}`
  return ''
}

// v3.17: 项目内二级导航
const projectId = computed(() => route.params.id || '')

const projectSubNav = computed(() => {
  const id = projectId.value
  if (!id) return []
  return [
    { path: `/projects/${id}`, label: '项目详情', icon: View },
    { path: `/projects/${id}/testcases`, label: '测试用例', icon: Document },
    { path: `/projects/${id}/executions`, label: '执行历史', icon: Clock },
    { path: `/projects/${id}/analysis`, label: '代码分析', icon: DataAnalysis },
    { path: `/projects/${id}/state-machines`, label: '状态机覆盖', icon: Share },
    { path: `/projects/${id}/mindmap`, label: '脑图预览', icon: Picture }
  ]
})
</script>

<style scoped>
.app-shell {
  display: flex;
  height: 100vh;
  overflow: hidden;
}

/* ===== 侧边栏 ===== */
.app-sidebar {
  flex-shrink: 0;
  width: 240px;
  background: var(--bg-sidebar);
  display: flex;
  flex-direction: column;
  transition: width var(--transition-normal);
  position: relative;
  z-index: 10;

  &.collapsed {
    width: 64px;
  }
}

.sidebar-header {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 20px 20px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
}

.logo-mark {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border-radius: 10px;
  background: var(--brand-gradient);
  box-shadow: 0 4px 12px rgba(79, 70, 229, 0.4);
}

.logo-text-letter {
  color: #fff;
  font-size: 15px;
  font-weight: 800;
  letter-spacing: 1px;
}

.logo-label {
  color: #f1f5f9;
  font-size: 16px;
  font-weight: 700;
  letter-spacing: 0.5px;
  white-space: nowrap;
}

.sidebar-nav {
  flex: 1;
  padding: 16px 12px;
  display: flex;
  flex-direction: column;
  gap: 4px;
  overflow-y: auto;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 14px;
  border-radius: var(--radius-md);
  color: #94a3b8;
  text-decoration: none;
  font-size: 14px;
  font-weight: 500;
  transition: all var(--transition-fast);
  position: relative;

  &:hover {
    background: rgba(255, 255, 255, 0.06);
    color: #e2e8f0;
  }

  &.active {
    background: var(--bg-sidebar-active);
    color: #fff;
    box-shadow: 0 4px 12px rgba(79, 70, 229, 0.35);
  }

  .nav-label {
    white-space: nowrap;
  }
}

/* v3.17: 项目内二级导航 */
.sidebar-subnav {
  padding: 4px 12px 12px;
  border-top: 1px solid rgba(255, 255, 255, 0.08);
  margin-top: 4px;

  .subnav-title {
    font-size: 11px;
    color: #64748b;
    letter-spacing: 1px;
    padding: 8px 10px 6px;
  }

  .subnav-item {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 7px 10px;
    border-radius: var(--radius-md);
    color: #94a3b8;
    text-decoration: none;
    font-size: 13px;
    transition: all var(--transition-fast);

    &:hover {
      background: rgba(255, 255, 255, 0.06);
      color: #e2e8f0;
    }

    &.active {
      background: rgba(79, 70, 229, 0.35);
      color: #fff;
    }
  }
}

.sidebar-footer {
  padding: 12px;
  border-top: 1px solid rgba(255, 255, 255, 0.08);
}

.collapse-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  padding: 8px;
  border: none;
  border-radius: var(--radius-md);
  background: rgba(255, 255, 255, 0.04);
  color: #64748b;
  cursor: pointer;
  transition: all var(--transition-fast);

  &:hover {
    background: rgba(255, 255, 255, 0.08);
    color: #e2e8f0;
  }
}

/* ===== 主区域 ===== */
.app-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  min-width: 0;
}

.app-topbar {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 56px;
  padding: 0 24px;
  background: var(--bg-surface);
  border-bottom: 1px solid var(--card-border);
  box-shadow: var(--shadow-xs);
}

/* v3.17: 面包屑 */
.breadcrumb {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 14px;

  .breadcrumb-sep {
    color: var(--text-tertiary);
  }

  .breadcrumb-link {
    color: var(--text-secondary);
    text-decoration: none;
    transition: color var(--transition-fast);

    &:hover {
      color: var(--brand-primary);
    }
  }

  .breadcrumb-current {
    color: var(--text-primary);
    font-weight: 600;
  }
}

.version-tag {
  display: inline-flex;
  align-items: center;
  padding: 2px 10px;
  border-radius: var(--radius-full);
  background: var(--el-color-primary-light-9);
  color: var(--brand-primary);
  font-size: 12px;
  font-weight: 600;
}

.app-content {
  flex: 1;
  overflow-y: auto;
  padding: 0;
}

/* ===== 响应式 ===== */
@media (max-width: 768px) {
  .app-sidebar {
    position: fixed;
    left: 0;
    top: 0;
    bottom: 0;
    z-index: 100;

    &:not(.collapsed) {
      box-shadow: var(--shadow-xl);
    }
  }

  .app-main {
    width: 100%;
  }

  .app-topbar {
    padding: 0 16px;
  }
}
</style>
