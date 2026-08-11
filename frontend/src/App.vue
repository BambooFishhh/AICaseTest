<template>
  <el-container class="app-container">
    <el-header class="app-header">
      <div class="header-inner">
        <div class="header-left">
          <router-link to="/projects" class="logo-link">
            <div class="logo">
              <span class="logo-icon">AI</span>
              <span class="logo-text">AI 测试用例生成系统</span>
            </div>
          </router-link>
        </div>
        <nav class="header-nav">
          <router-link to="/projects" class="nav-link" active-class="active">
            <el-icon :size="16"><FolderOpened /></el-icon>
            <span>项目列表</span>
          </router-link>
          <router-link to="/settings" class="nav-link" active-class="active">
            <el-icon :size="16"><Setting /></el-icon>
            <span>系统设置</span>
          </router-link>
        </nav>
      </div>
    </el-header>
    <el-main class="app-main">
      <router-view />
    </el-main>
  </el-container>
</template>

<script setup>
</script>

<style scoped lang="scss">
.app-container {
  height: 100vh;
  display: flex;
  flex-direction: column;
}

.app-header {
  position: relative;
  z-index: 100;
  height: 64px;
  padding: 0 28px;
  overflow: hidden;
  color: #fff;
  background: linear-gradient(120deg, #16235c 0%, #2b45c9 52%, #4c6fff 100%);
  box-shadow: 0 2px 14px rgba(27, 42, 107, 0.28);

  &::before,
  &::after {
    content: '';
    position: absolute;
    border-radius: 50%;
    pointer-events: none;
  }

  &::before {
    width: 320px;
    height: 320px;
    right: -90px;
    top: -190px;
    background: radial-gradient(circle, rgba(124, 92, 255, 0.5), transparent 68%);
  }

  &::after {
    width: 240px;
    height: 240px;
    left: 34%;
    top: -150px;
    background: radial-gradient(circle, rgba(255, 255, 255, 0.16), transparent 70%);
  }
}

.header-inner {
  position: relative;
  z-index: 1;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.header-left {
  display: flex;
  align-items: center;
}

.logo-link {
  text-decoration: none;
}

.logo {
  display: flex;
  align-items: center;
  gap: 12px;

  .logo-icon {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 38px;
    height: 38px;
    border-radius: 10px;
    background: linear-gradient(135deg, #7c5cff 0%, #4c6fff 100%);
    color: #fff;
    font-size: 17px;
    font-weight: 800;
    letter-spacing: 0.5px;
    box-shadow: 0 4px 14px rgba(124, 92, 255, 0.45), inset 0 1px 0 rgba(255, 255, 255, 0.28);
  }

  .logo-text {
    font-size: 20px;
    font-weight: 700;
    color: #fff;
    letter-spacing: 1px;
    text-shadow: 0 1px 8px rgba(0, 0, 0, 0.2);
  }
}

.header-nav {
  display: flex;
  align-items: center;
  gap: 6px;

  .nav-link {
    display: inline-flex;
    align-items: center;
    gap: 7px;
    padding: 8px 18px;
    color: rgba(255, 255, 255, 0.82);
    text-decoration: none;
    border-radius: 999px;
    font-size: 14px;
    font-weight: 500;
    transition: all 0.2s ease;

    &:hover {
      background: rgba(255, 255, 255, 0.14);
      color: #fff;
    }

    &.active {
      background: rgba(255, 255, 255, 0.2);
      color: #fff;
      box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.18);
    }
  }
}

.app-main {
  flex: 1;
  padding: 24px;
  overflow-y: auto;
}

@media (max-width: 768px) {
  .app-header {
    padding: 0 16px;
  }

  .logo-text {
    display: none;
  }

  .app-main {
    padding: 16px;
  }
}
</style>
