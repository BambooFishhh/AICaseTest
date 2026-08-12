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
          <span class="topbar-title">{{ currentPageTitle }}</span>
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
import { FolderOpened, Setting, Fold, Expand } from '@element-plus/icons-vue'

const route = useRoute()
const sidebarCollapsed = ref(false)

const navItems = [
  { path: '/projects', label: '项目列表', icon: FolderOpened },
  { path: '/settings', label: '系统设置', icon: Setting }
]

const currentPageTitle = computed(() => route.meta?.title || 'AI 测试用例生成系统')
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

.topbar-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-primary);
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
