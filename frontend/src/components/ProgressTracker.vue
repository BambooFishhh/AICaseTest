<template>
  <div class="progress-tracker">
    <el-alert
      :title="alertText"
      :type="alertConfig.type"
      :closable="false"
      show-icon
      :description="description"
    >
      <template #title>
        <div class="alert-title">
          <el-icon v-if="alertConfig.loading" class="is-loading">
            <Loading />
          </el-icon>
          <el-icon v-else>
            <component :is="alertConfig.icon" />
          </el-icon>
          <span class="alert-text">{{ alertText }}</span>
        </div>
      </template>
    </el-alert>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import {
  Loading,
  CircleCheck,
  CircleClose
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

// 计算告警配置（类型 + 图标 + 是否加载中）
const alertConfig = computed(() => {
  switch (props.status) {
    case 'analyzing':
    case 'generating':
      return {
        type: 'info',
        loading: true,
        icon: Loading
      }
    case 'completed':
      return {
        type: 'success',
        loading: false,
        icon: CircleCheck
      }
    case 'failed':
      return {
        type: 'error',
        loading: false,
        icon: CircleClose
      }
    default:
      return {
        type: 'info',
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
  width: 100%;
}

.alert-title {
  display: flex;
  align-items: center;
  gap: 6px;
}

.alert-text {
  font-weight: 500;
}

.is-loading {
  animation: rotating 1.5s linear infinite;
}

@keyframes rotating {
  from {
    transform: rotate(0deg);
  }
  to {
    transform: rotate(360deg);
  }
}
</style>
