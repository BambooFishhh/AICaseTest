<template>
  <div class="progress-tracker" :class="`tracker-${status}`">
    <div class="tracker-icon">
      <el-icon
        v-if="alertConfig.loading"
        class="is-loading"
        :size="20"
      >
        <Loading />
      </el-icon>
      <el-icon v-else :size="20">
        <component :is="alertConfig.icon" />
      </el-icon>
    </div>
    <div class="tracker-body">
      <div class="tracker-title">{{ alertText }}</div>
      <div v-if="description" class="tracker-desc">{{ description }}</div>
    </div>
  </div>
</template>

<script setup>
/**
 * 进度追踪组件
 * 根据 status 展示不同状态：
 * - analyzing/generating: 加载中（旋转图标）
 * - completed: 处理完成（绿色对勾）
 * - failed: 处理失败（红色叉号）
 */
import { computed } from 'vue'
import {
  Loading,
  CircleCheckFilled,
  CircleCloseFilled
} from '@element-plus/icons-vue'

const props = defineProps({
  status: {
    type: String,
    default: 'analyzing'
  },
  message: {
    type: String,
    default: ''
  },
  description: {
    type: String,
    default: ''
  }
})

// 默认文案映射
const defaultMessages = {
  analyzing: '正在分析中...',
  generating: '正在生成中...',
  completed: '处理完成',
  failed: '处理失败'
}

// 计算告警配置（图标 + 是否加载中）
const alertConfig = computed(() => {
  switch (props.status) {
    case 'analyzing':
    case 'generating':
      return {
        loading: true,
        icon: Loading
      }
    case 'completed':
      return {
        loading: false,
        icon: CircleCheckFilled
      }
    case 'failed':
      return {
        loading: false,
        icon: CircleCloseFilled
      }
    default:
      return {
        loading: false,
        icon: Loading
      }
  }
})

const alertText = computed(() => {
  return props.message || defaultMessages[props.status] || '正在处理中...'
})
</script>

<style scoped>
.progress-tracker {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 14px 18px;
  border-radius: var(--radius-lg);
  border: 1px solid var(--card-border);
  background: var(--bg-surface);
  transition: all var(--transition-normal);
}

.tracker-icon {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border-radius: var(--radius-md);
  color: #fff;
}

.tracker-body {
  flex: 1;
  min-width: 0;
}

.tracker-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
  line-height: 1.4;
}

.tracker-desc {
  margin-top: 4px;
  font-size: 12px;
  color: var(--text-tertiary);
  line-height: 1.5;
  word-break: break-word;
}

/* 状态变体 */
.tracker-analyzing,
.tracker-generating {
  border-color: var(--color-warning);
  background: linear-gradient(to right, var(--color-warning-bg), var(--bg-surface));

  .tracker-icon { background: var(--color-warning); }
  .tracker-title { color: var(--color-warning); }
}

.tracker-completed {
  border-color: var(--color-success);
  background: linear-gradient(to right, var(--color-success-bg), var(--bg-surface));

  .tracker-icon { background: var(--color-success); }
  .tracker-title { color: var(--color-success); }
}

.tracker-failed {
  border-color: var(--color-danger);
  background: linear-gradient(to right, var(--color-danger-bg), var(--bg-surface));

  .tracker-icon { background: var(--color-danger); }
  .tracker-title { color: var(--color-danger); }
}

.is-loading {
  animation: rotating 1.5s linear infinite;
}

@keyframes rotating {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}
</style>
