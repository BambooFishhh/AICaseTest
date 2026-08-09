<template>
  <div class="settings-page">
    <div class="page-header">
      <h2>系统设置</h2>
    </div>

    <el-card class="form-card" v-loading="loading">
      <el-form
        ref="formRef"
        :model="form"
        label-width="120px"
        class="settings-form"
      >
        <el-form-item label="LLM Provider">
          <el-select v-model="form.llmProvider" placeholder="请选择提供商">
            <el-option label="mimo" value="mimo" />
            <el-option label="deepseek" value="deepseek" />
            <el-option label="openai" value="openai" />
          </el-select>
        </el-form-item>
        <el-form-item label="Model">
          <el-input v-model="form.llmModel" placeholder="例如 mimo-v2.5-pro" />
        </el-form-item>
        <el-form-item label="API Key">
          <el-input
            v-model="form.llmApiKey"
            type="password"
            show-password
            placeholder="请输入 API Key"
          />
        </el-form-item>
        <el-form-item label="Base URL">
          <el-input
            v-model="form.llmBaseUrl"
            placeholder="例如 https://api.xiaomimimo.com/v1"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="saving" @click="handleSave">
            保存配置
          </el-button>
          <el-button :loading="testing" @click="handleTest">测试连接</el-button>
        </el-form-item>
      </el-form>

      <el-alert
        v-if="testResult"
        :title="testResult.status === 'success' ? '连接成功' : '连接失败'"
        :type="testResult.status === 'success' ? 'success' : 'error'"
        :description="testResult.response_preview || ''"
        show-icon
        closable
        @close="testResult = null"
        class="test-alert"
      />
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
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
  // 后端返回的 API Key 是脱敏值，仅在用户修改时才提交，避免把脱敏值回写
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
  } catch (e) {
    // 错误已由响应拦截器统一提示
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
  } catch (e) {
    // 错误已由响应拦截器统一提示
  } finally {
    testing.value = false
  }
}

onMounted(loadSettings)
</script>

<style scoped>
.settings-page {
  padding: 20px;
}
.page-header {
  margin-bottom: 20px;
}
.page-header h2 {
  margin: 0;
}
.form-card {
  max-width: 600px;
  margin: 0 auto;
}
.test-alert {
  margin-top: 16px;
}
</style>
