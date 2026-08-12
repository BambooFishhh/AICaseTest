<template>
  <section class="prd-panel">
    <div class="prd-head">
      <div class="prd-head-text">
        <h2 class="section-title">PRD 需求文档</h2>
        <p class="section-desc">提供 PRD 作为用例生成的主上下文</p>
      </div>
      <div class="prd-head-meta">
        <el-tag v-if="sourceType" size="small" :type="sourceTagType" effect="light">
          {{ sourceText }}
        </el-tag>
        <span v-if="prdContent" class="word-count">{{ prdContent.length }} 字</span>
      </div>
    </div>

    <!-- 来源切换 -->
    <el-radio-group v-model="activeTab" size="default" class="prd-tabs">
      <el-radio-button value="text">文本 / Markdown</el-radio-button>
      <el-radio-button value="md">md / txt 上传</el-radio-button>
      <el-radio-button value="pdf">PDF 上传</el-radio-button>
      <el-radio-button value="link">在线链接</el-radio-button>
    </el-radio-group>

    <!-- 文本编辑 -->
    <div v-show="activeTab === 'text'" class="prd-pane">
      <el-input
        v-model="textForm.content"
        type="textarea"
        :autosize="{ minRows: 10, maxRows: 20 }"
        placeholder="粘贴或编辑 PRD 内容（支持 Markdown）。生成用例时 PRD 作为主上下文，代码作为辅助上下文。"
      />
      <div class="pane-actions">
        <el-button type="primary" :loading="saving" :icon="Check" @click="saveText">保存 PRD</el-button>
        <!-- v3.14: 载入示例 PRD -->
        <el-button :icon="MagicStick" @click="useSample">使用示例</el-button>
        <el-button v-if="prdContent" :icon="View" @click="previewVisible = true">预览完整</el-button>
      </div>
    </div>

    <!-- md/txt 上传 -->
    <div v-show="activeTab === 'md'" class="prd-pane">
      <el-upload
        drag
        accept=".md,.markdown,.txt"
        :auto-upload="true"
        :show-file-list="false"
        :http-request="handleMdUpload"
        class="prd-uploader"
      >
        <div class="uploader-icon">
          <el-icon :size="32"><UploadFilled /></el-icon>
        </div>
        <div class="uploader-text">拖拽 .md / .txt 文件到此处，或<em>点击上传</em></div>
        <template #tip>
          <div class="uploader-tip">支持 Markdown 和纯文本文件，限 5MB 以内</div>
        </template>
      </el-upload>
    </div>

    <!-- PDF 上传 -->
    <div v-show="activeTab === 'pdf'" class="prd-pane">
      <el-upload
        drag
        accept=".pdf"
        :auto-upload="true"
        :show-file-list="false"
        :http-request="handlePdfUpload"
        class="prd-uploader"
      >
        <div class="uploader-icon">
          <el-icon :size="32"><UploadFilled /></el-icon>
        </div>
        <div class="uploader-text">拖拽 PDF 到此处，或<em>点击上传</em></div>
        <template #tip>
          <div class="uploader-tip">仅支持文本型 PDF；扫描件需先用 OCR 转文本</div>
        </template>
      </el-upload>
    </div>

    <!-- 在线链接 -->
    <div v-show="activeTab === 'link'" class="prd-pane">
      <el-input v-model="linkForm.url" placeholder="https://example.com/prd.md" clearable size="large">
        <template #prepend>
          <span class="url-prefix">URL</span>
        </template>
      </el-input>
      <div class="pane-actions">
        <el-button type="primary" :loading="fetching" :icon="Download" @click="fetchLink">
          抓取内容
        </el-button>
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
    <Transition name="slide-down">
      <div v-if="prdContent" class="prd-summary">
        <div class="summary-head">
          <span class="summary-label">
            <el-icon :size="14"><Document /></el-icon>当前 PRD
          </span>
          <span v-if="sourceRef" class="summary-ref">来源：{{ sourceRef }}</span>
        </div>
        <div class="summary-preview">{{ prdPreview }}</div>
      </div>
    </Transition>

    <!-- 预览对话框 -->
    <el-dialog v-model="previewVisible" title="PRD 预览" width="760px">
      <pre class="prd-full-text">{{ prdContent }}</pre>
    </el-dialog>
  </section>
</template>

<script setup>
/**
 * PRD 面板组件
 * 支持四种来源输入 PRD：
 * - 文本/Markdown：直接编辑
 * - md/txt 上传：浏览器端读取后保存
 * - PDF 上传：后端解析
 * - 在线链接：后端抓取
 * 显示当前 PRD 概要，支持预览完整内容。
 */
import { ref, computed, watch } from 'vue'
import { ElMessage } from 'element-plus'
import {
  UploadFilled, Check, View, Download, Document, MagicStick
} from '@element-plus/icons-vue'
import { getPrd, updatePrd, uploadPrdPdf, fetchPrdUrl } from '@/api/project'
// v3.14: 内置示例 PRD（快速体验 PRD 驱动生成）
import samplePrd from '@/assets/samples/order-prd.md?raw'

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

const sourceTagType = computed(() =>
  ({ text: '', md: 'success', pdf: 'warning', link: 'success' }[sourceType.value] || 'info')
)
const sourceText = computed(() =>
  ({ text: '文本', md: 'md/txt', pdf: 'PDF', link: '链接' }[sourceType.value] || sourceType.value)
)

watch(() => props.projectId, (id) => { if (id) loadPrd() }, { immediate: true })

async function loadPrd() {
  try {
    const res = await getPrd(props.projectId)
    prdContent.value = res.data?.prdContent || ''
    sourceType.value = res.data?.prdSourceType || ''
    sourceRef.value = res.data?.prdSourceRef || ''
    textForm.value.content = prdContent.value
    // 切到已有来源对应的 tab
    if (sourceType.value === 'md') activeTab.value = 'md'
    else if (sourceType.value === 'pdf') activeTab.value = 'pdf'
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

// v3.14: 载入内置示例 PRD 到编辑器（不自动保存，用户确认后保存生效）
function useSample() {
  textForm.value.content = samplePrd
  activeTab.value = 'text'
  ElMessage.info('已载入示例 PRD（电商订单系统），点击"保存 PRD"后即可生成用例')
}

// md/txt 上传：浏览器端 FileReader 读取文本，再调用 updatePrd 保存
async function handleMdUpload(option) {
  const file = option.file
  if (file.size > 5 * 1024 * 1024) {
    ElMessage.warning('文件过大，请控制在 5MB 以内')
    option.onError(new Error('文件过大'))
    return
  }
  const reader = new FileReader()
  reader.onload = async () => {
    try {
      const content = reader.result
      const res = await updatePrd(props.projectId, content)
      prdContent.value = res.data?.prdContent || content
      sourceType.value = 'md'
      sourceRef.value = file.name
      textForm.value.content = content
      ElMessage.success(`${file.name} 导入成功`)
      option.onSuccess(res)
    } catch (e) {
      option.onError(e)
    }
  }
  reader.onerror = () => {
    ElMessage.error('文件读取失败')
    option.onError(new Error('文件读取失败'))
  }
  reader.readAsText(file, 'UTF-8')
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
.prd-panel {
  background: var(--bg-surface);
  border: 1px solid var(--card-border);
  border-radius: var(--radius-lg);
  padding: 20px;
  box-shadow: var(--shadow-xs);
  margin-bottom: var(--space-lg);
}

.prd-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: var(--space-md);
  gap: var(--space-md);
  flex-wrap: wrap;
}

.prd-head-text {
  display: flex;
  flex-direction: column;
}

.section-title {
  font-size: 16px;
  font-weight: 700;
  color: var(--text-primary);
  margin: 0;
}

.section-desc {
  font-size: 13px;
  color: var(--text-tertiary);
  margin-top: 2px;
}

.prd-head-meta {
  display: flex;
  align-items: center;
  gap: 8px;
}

.word-count {
  font-size: 12px;
  color: var(--text-tertiary);
  background: var(--bg-base);
  padding: 2px 8px;
  border-radius: var(--radius-full);
  font-family: 'Consolas', 'Monaco', monospace;
}

.prd-tabs {
  margin-bottom: var(--space-md);
}

.prd-pane {
  display: flex;
  flex-direction: column;
  gap: var(--space-md);
}

.pane-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

/* 上传区 */
.prd-uploader {
  width: 100%;

  :deep(.el-upload-dragger) {
    width: 100%;
    padding: 28px 20px;
    border: 2px dashed var(--card-border);
    border-radius: var(--radius-lg);
    background: var(--bg-base);
    transition: all var(--transition-normal);

    &:hover {
      border-color: var(--brand-primary);
      background: var(--el-color-primary-light-9);
    }
  }
}

.uploader-icon {
  color: var(--brand-primary);
  margin-bottom: 8px;
}

.uploader-text {
  font-size: 14px;
  color: var(--text-secondary);

  em {
    color: var(--brand-primary);
    font-style: normal;
    font-weight: 600;
  }
}

.uploader-tip {
  margin-top: 8px;
  font-size: 12px;
  color: var(--text-tertiary);
}

.url-prefix {
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 12px;
  font-weight: 600;
}

/* PRD 概要 */
.prd-summary {
  border: 1px solid var(--card-border-light);
  border-radius: var(--radius-md);
  background: #f8fafc;
  overflow: hidden;
}

.summary-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 14px;
  border-bottom: 1px solid var(--card-border-light);
  flex-wrap: wrap;
  gap: 8px;
}

.summary-label {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 13px;
  font-weight: 600;
  color: var(--text-secondary);

  .el-icon {
    color: var(--brand-primary);
  }
}

.summary-ref {
  font-size: 12px;
  color: var(--text-tertiary);
  font-family: 'Consolas', 'Monaco', monospace;
}

.summary-preview {
  padding: 12px 14px;
  font-size: 13px;
  color: var(--text-secondary);
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 140px;
  overflow-y: auto;
}

.prd-full-text {
  max-height: 60vh;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-all;
  font-size: 13px;
  background: #f8fafc;
  padding: 14px;
  border-radius: var(--radius-md);
  border: 1px solid var(--card-border-light);
  font-family: -apple-system, 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif;
}
</style>
