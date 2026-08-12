<template>
  <div class="settings-page page-container">
    <!-- 页头 -->
    <header class="page-header">
      <div class="page-header-main">
        <h1 class="page-title">系统设置</h1>
        <p class="page-subtitle">配置 LLM 服务参数以启用 AI 用例生成</p>
      </div>
    </header>

    <!-- 加载骨架屏 -->
    <div v-if="loading" class="skeleton-block">
      <div v-for="i in 4" :key="i" class="skeleton-line" :style="{ width: ['60%','80%','40%','70%'][i-1] }"></div>
    </div>

    <!-- 设置卡片 -->
    <section v-else class="settings-section">
      <div class="section-head">
        <div class="section-head-text">
          <h2 class="section-title">LLM 配置</h2>
          <p class="section-desc">配置大模型服务商、模型与认证参数</p>
        </div>
        <el-icon :size="28" class="section-icon"><Setting /></el-icon>
      </div>

      <el-form
        ref="formRef"
        :model="form"
        label-position="top"
        class="settings-form"
      >
        <div class="form-grid">
          <el-form-item label="LLM Provider">
            <el-select v-model="form.llmProvider" placeholder="请选择提供商" size="large">
              <el-option label="mimo" value="mimo" />
              <el-option label="deepseek" value="deepseek" />
              <el-option label="openai" value="openai" />
            </el-select>
          </el-form-item>

          <el-form-item label="Model">
            <el-input
              v-model="form.llmModel"
              placeholder="例如 mimo-v2.5-pro"
              size="large"
            />
          </el-form-item>

          <el-form-item label="API Key">
            <el-input
              v-model="form.llmApiKey"
              type="password"
              show-password
              placeholder="请输入 API Key"
              size="large"
            />
          </el-form-item>

          <el-form-item label="Base URL">
            <el-input
              v-model="form.llmBaseUrl"
              placeholder="例如 https://api.xiaomimimo.com/v1"
              size="large"
            />
          </el-form-item>
        </div>

        <div class="form-actions">
          <el-button type="primary" size="large" :loading="saving" @click="handleSave">
            保存配置
          </el-button>
          <el-button size="large" :loading="testing" @click="handleTest">
            测试连接
          </el-button>
        </div>
      </el-form>

      <!-- 测试结果 -->
      <Transition name="slide-down">
        <div
          v-if="testResult"
          class="test-result"
          :class="testResult.status === 'success' ? 'is-success' : 'is-error'"
        >
          <div class="result-icon">
            <el-icon :size="20">
              <CircleCheckFilled v-if="testResult.status === 'success'" />
              <CircleCloseFilled v-else />
            </el-icon>
          </div>
          <div class="result-body">
            <div class="result-title">
              {{ testResult.status === 'success' ? '连接成功' : '连接失败' }}
            </div>
            <div v-if="testResult.response_preview" class="result-desc">
              {{ testResult.response_preview }}
            </div>
          </div>
          <el-button text :icon="Close" @click="testResult = null" />
        </div>
      </Transition>
    </section>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import {
  Setting, Close, CircleCheckFilled, CircleCloseFilled
} from '@element-plus/icons-vue'
import { getSettings, updateSettings, testLlm } from '@/api/settings'

const loading = ref(false)
const saving = ref(false)
const testing = ref(false)
const testResult = ref(null)
const originalApiKey = ref('')

const form = reactive({
  llmProvider: '',
  llmModel: '',
  llmApiKey: '',
  llmBaseUrl: ''
})

async function loadSettings() {
  loading.value = true
  try {
    const res = await getSettings()
    const data = res.data || {}
    form.llmProvider = data.llmProvider || ''
    form.llmModel = data.llmModel || ''
    form.llmApiKey = data.llmApiKey || ''
    form.llmBaseUrl = data.llmBaseUrl || ''
    originalApiKey.value = form.llmApiKey
  } finally {
    loading.value = false
  }
}

function buildPayload() {
  const payload = {
    llmProvider: form.llmProvider,
    llmModel: form.llmModel,
    llmBaseUrl: form.llmBaseUrl
  }
  // 后端返回的 API Key 是脱敏值，仅在用户修改时才提交
  if (form.llmApiKey && form.llmApiKey !== originalApiKey.value) {
    payload.llmApiKey = form.llmApiKey
  }
  return payload
}

async function handleSave() {
  saving.value = true
  try {
    await updateSettings(buildPayload())
    ElMessage.success('配置保存成功')
    await loadSettings()
  } finally {
    saving.value = false
  }
}

async function handleTest() {
  testing.value = true
  testResult.value = null
  try {
    const res = await testLlm()
    testResult.value = res.data || {}
  } finally {
    testing.value = false
  }
}

onMounted(loadSettings)
</script>

<style scoped lang="scss">
.settings-page {
  padding: var(--space-lg) var(--space-xl);
  max-width: 880px;
  margin: 0 auto;
}

/* ===== 骨架屏 ===== */
.skeleton-block {
  background: var(--bg-surface);
  border: 1px solid var(--card-border);
  border-radius: var(--radius-xl);
  padding: var(--space-xl);
  box-shadow: var(--shadow-xs);

  .skeleton-line {
    height: 16px;
    background: linear-gradient(90deg, #f1f5f9 25%, #e2e8f0 37%, #f1f5f9 63%);
    background-size: 400% 100%;
    animation: shimmer 1.4s ease infinite;
    border-radius: var(--radius-sm);
    margin-bottom: 16px;
  }
}

@keyframes shimmer {
  0% { background-position: 100% 50%; }
  100% { background-position: 0 50%; }
}

/* ===== 设置区 ===== */
.settings-section {
  background: var(--bg-surface);
  border: 1px solid var(--card-border);
  border-radius: var(--radius-xl);
  box-shadow: var(--shadow-sm);
  overflow: hidden;
}

.section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-lg) var(--space-xl);
  background: linear-gradient(135deg, var(--el-color-primary-light-9) 0%, transparent 100%);
  border-bottom: 1px solid var(--card-border-light);

  .section-title {
    font-size: 18px;
    font-weight: 600;
    color: var(--text-primary);
    margin: 0 0 4px 0;
  }

  .section-desc {
    font-size: 13px;
    color: var(--text-tertiary);
    margin: 0;
  }

  .section-icon {
    color: var(--brand-primary);
    opacity: 0.6;
  }
}

.settings-form {
  padding: var(--space-xl);
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: var(--space-md) var(--space-lg);
  margin-bottom: var(--space-lg);

  .el-select {
    width: 100%;
  }
}

.form-actions {
  display: flex;
  gap: var(--space-sm);
  padding-top: var(--space-md);
  border-top: 1px dashed var(--card-border-light);
}

/* ===== 测试结果 ===== */
.test-result {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 16px;
  margin: 0 var(--space-xl) var(--space-xl);
  border-radius: var(--radius-md);
  border: 1px solid;

  &.is-success {
    background: var(--color-success-bg);
    border-color: var(--color-success);
    color: var(--color-success);
  }

  &.is-error {
    background: var(--color-danger-bg);
    border-color: var(--color-danger);
    color: var(--color-danger);
  }

  .result-icon {
    flex-shrink: 0;
  }

  .result-body {
    flex: 1;
    min-width: 0;
  }

  .result-title {
    font-weight: 600;
    font-size: 14px;
  }

  .result-desc {
    font-size: 12px;
    color: var(--text-secondary);
    margin-top: 2px;
    word-break: break-all;
  }
}

/* ===== 过渡动画 ===== */
.slide-down-enter-active, .slide-down-leave-active {
  transition: all var(--transition-normal);
  overflow: hidden;
}

.slide-down-enter-from, .slide-down-leave-to {
  opacity: 0;
  max-height: 0;
  margin-top: -16px;
}

/* ===== 响应式 ===== */
@media (max-width: 768px) {
  .settings-page {
    padding: var(--space-md);
  }

  .form-grid {
    grid-template-columns: 1fr;
  }

  .settings-form {
    padding: var(--space-md);
  }

  .test-result {
    margin: 0 var(--space-md) var(--space-md);
  }
}
</style>
