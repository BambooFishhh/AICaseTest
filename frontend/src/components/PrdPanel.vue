<template>
  <el-card class="prd-panel" shadow="never">
    <template #header>
      <div class="prd-header">
        <span class="prd-title">PRD 需求文档</span>
        <el-tag v-if="sourceType" size="small" :type="sourceTagType">{{ sourceText }}</el-tag>
        <span v-if="prdContent" class="prd-word-count">{{ prdContent.length }} 字</span>
      </div>
    </template>

    <!-- 来源切换 -->
    <el-radio-group v-model="activeTab" size="small" class="prd-tabs">
      <el-radio-button label="text">文本/Markdown</el-radio-button>
      <el-radio-button label="pdf">PDF 上传</el-radio-button>
      <el-radio-button label="link">在线链接</el-radio-button>
    </el-radio-group>

    <!-- 文本编辑 -->
    <div v-show="activeTab === 'text'" class="prd-text-area">
      <el-input
        v-model="textForm.content"
        type="textarea"
        :rows="12"
        placeholder="粘贴或编辑 PRD 内容（支持 Markdown）。生成用例时 PRD 作为主上下文，代码作为辅助上下文。"
      />
      <div class="prd-actions">
        <el-button type="primary" :loading="saving" @click="saveText">保存 PRD</el-button>
        <el-button v-if="prdContent" @click="previewVisible = true">预览完整</el-button>
      </div>
    </div>

    <!-- PDF 上传 -->
    <div v-show="activeTab === 'pdf'" class="prd-upload-area">
      <el-upload
        drag
        accept=".pdf"
        :auto-upload="true"
        :show-file-list="false"
        :http-request="handlePdfUpload"
      >
        <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
        <div class="el-upload__text">拖拽 PDF 到此，或<em>点击上传</em></div>
        <template #tip>
          <div class="el-upload__tip">仅支持文本型 PDF；扫描件需先用 OCR 转文本</div>
        </template>
      </el-upload>
    </div>

    <!-- 在线链接 -->
    <div v-show="activeTab === 'link'" class="prd-link-area">
      <el-input v-model="linkForm.url" placeholder="https://example.com/prd.md" clearable>
        <template #prepend>URL</template>
      </el-input>
      <div class="prd-actions">
        <el-button type="primary" :loading="fetching" @click="fetchLink">抓取内容</el-button>
      </div>
      <el-alert
        v-if="linkError"
        :title="linkError"
        type="error"
        :closable="false"
        show-icon
      />
    </div>

    <!-- 当前 PRD 概要 -->
    <div v-if="prdContent" class="prd-summary">
      <el-divider content-position="left">当前 PRD</el-divider>
      <div class="prd-meta">
        <span v-if="sourceRef">来源：{{ sourceRef }}</span>
      </div>
      <div class="prd-preview-text">{{ prdPreview }}</div>
    </div>

    <!-- 预览对话框 -->
    <el-dialog v-model="previewVisible" title="PRD 预览" width="760px">
      <pre class="prd-full-text">{{ prdContent }}</pre>
    </el-dialog>
  </el-card>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { UploadFilled } from '@element-plus/icons-vue'
import { getPrd, updatePrd, uploadPrdPdf, fetchPrdUrl } from '@/api/project'

const props = defineProps({ projectId: String })

const activeTab = ref('text')
const saving = ref(false)
const fetching = ref(false)
const previewVisible = ref(false)

const prdContent = ref('')
const sourceType = ref('')
const sourceRef = ref('')

const textForm = ref({ content: '' })
const linkForm = ref({ url: '' })
const linkError = ref('')

const prdPreview = computed(() => {
  if (!prdContent.value) return ''
  return prdContent.value.length > 200
    ? prdContent.value.substring(0, 200) + '...'
    : prdContent.value
})

const sourceTagType = computed(() => ({ text: '', pdf: 'warning', link: 'success' }[sourceType.value] || 'info'))
const sourceText = computed(() => ({ text: '文本', pdf: 'PDF', link: '链接' }[sourceType.value] || sourceType.value))

watch(() => props.projectId, (id) => { if (id) loadPrd() }, { immediate: true })

async function loadPrd() {
  try {
    const res = await getPrd(props.projectId)
    prdContent.value = res.data?.prdContent || ''
    sourceType.value = res.data?.prdSourceType || ''
    sourceRef.value = res.data?.prdSourceRef || ''
    textForm.value.content = prdContent.value
    // 切到已有来源对应的 tab
    if (sourceType.value === 'pdf') activeTab.value = 'pdf'
    else if (sourceType.value === 'link') {
      activeTab.value = 'link'
      linkForm.value.url = sourceRef.value || ''
    } else {
      activeTab.value = 'text'
    }
  } catch (e) {
    // 静默失败，不打扰用户
  }
}

async function saveText() {
  saving.value = true
  try {
    const res = await updatePrd(props.projectId, textForm.value.content)
    prdContent.value = res.data?.prdContent || textForm.value.content
    sourceType.value = 'text'
    sourceRef.value = ''
    ElMessage.success('PRD 已保存')
  } finally {
    saving.value = false
  }
}

async function handlePdfUpload(option) {
  try {
    const res = await uploadPrdPdf(props.projectId, option.file)
    prdContent.value = res.data?.prdContent || ''
    sourceType.value = 'pdf'
    sourceRef.value = res.data?.prdSourceRef || option.file.name
    ElMessage.success('PDF 解析成功')
    option.onSuccess(res)
  } catch (e) {
    option.onError(e)
  }
}

async function fetchLink() {
  linkError.value = ''
  if (!linkForm.value.url) {
    linkError.value = '请输入 URL'
    return
  }
  fetching.value = true
  try {
    const res = await fetchPrdUrl(props.projectId, linkForm.value.url)
    prdContent.value = res.data?.prdContent || ''
    sourceType.value = 'link'
    sourceRef.value = linkForm.value.url
    ElMessage.success('链接内容已抓取')
  } catch (e) {
    linkError.value = e.message || '抓取失败（可能是 SPA 或需认证页面）'
  } finally {
    fetching.value = false
  }
}
</script>

<style scoped>
.prd-panel { margin-bottom: 16px; }
.prd-header { display: flex; align-items: center; gap: 8px; }
.prd-title { font-weight: 600; }
.prd-word-count { margin-left: auto; color: #909399; font-size: 12px; }
.prd-tabs { margin-bottom: 12px; }
.prd-actions { margin-top: 8px; display: flex; gap: 8px; }
.prd-summary { margin-top: 8px; }
.prd-meta { color: #909399; font-size: 12px; margin-bottom: 6px; }
.prd-preview-text {
  background: #f5f7fa;
  padding: 10px;
  border-radius: 4px;
  font-size: 13px;
  color: #606266;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 120px;
  overflow: auto;
}
.prd-full-text {
  max-height: 60vh;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-all;
  font-size: 13px;
  background: #f5f7fa;
  padding: 12px;
  border-radius: 4px;
}
</style>
