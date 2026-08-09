<template>
  <el-container class="app-container">
    <el-header class="app-header">
      <div class="header-left">
        <div class="logo">
          <span class="logo-icon">AI</span>
          <span class="logo-text">AI测试用例生成系统</span>
        </div>
      </div>
      <div class="header-nav">
        <router-link to="/projects" class="nav-link" active-class="active">
          <span>项目列表</span>
        </router-link>
        <router-link to="/settings" class="nav-link" active-class="active">
          <span>系统设置</span>
        </router-link>
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
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: linear-gradient(90deg, #1e3a8a 0%, #3b82f6 100%);
  color: #fff;
  padding: 0 24px;
  height: 60px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.15);
  z-index: 100;
}

.header-left {
  display: flex;
  align-items: center;
}

.logo {
  display: flex;
  align-items: center;
  gap: 10px;

  .logo-icon {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 36px;
    height: 36px;
    border-radius: 8px;
    background: rgba(255, 255, 255, 0.2);
    color: #fff;
    font-size: 16px;
    font-weight: 700;
    letter-spacing: 0.5px;
  }

  .logo-text {
    font-size: 20px;
    font-weight: 600;
    color: #fff;
    letter-spacing: 1px;
  }
}

.header-nav {
  display: flex;
  align-items: center;
  gap: 8px;

  .nav-link {
    display: flex;
    align-items: center;
    gap: 6px;
    padding: 8px 16px;
    color: rgba(255, 255, 255, 0.85);
    text-decoration: none;
    border-radius: 6px;
    font-size: 14px;
    transition: all 0.2s ease;

    &:hover {
      background: rgba(255, 255, 255, 0.15);
      color: #fff;
    }

    &.active {
      background: rgba(255, 255, 255, 0.2);
      color: #fff;
      font-weight: 500;
    }
  }
}

.app-main {
  flex: 1;
  padding: 20px;
  background: #f5f7fa;
  overflow-y: auto;
}
</style>
