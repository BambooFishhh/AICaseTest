<template>
  <section class="prd-panel">
    <div class="prd-head">
      <div class="prd-head-text">
        <h2 class="section-title">PRD 需求文档</h2>
        <p class="section-desc">主 PRD 必填，可补充其他上下文信息与多篇文档</p>
      </div>
      <div class="prd-head-meta">
        <el-tag v-if="sourceType" size="small" :type="sourceTagType" effect="light">
          {{ sourceText }}
        </el-tag>
        <span v-if="prdContent" class="word-count">{{ prdContent.length }} 字</span>
        <el-button
          v-if="prdContent && !editorVisible"
          size="small"
          :icon="EditPen"
          @click="editorVisible = true"
        >
          编辑 PRD
        </el-button>
        <el-button v-if="editorVisible" size="small" @click="editorVisible = false">收起</el-button>
      </div>
    </div>

    <el-alert
      v-if="!prdContent"
      title="请先填写主 PRD，用例生成需要主 PRD 作为核心上下文"
      type="info"
      :closable="false"
      show-icon
      class="prd-empty-alert"
    />

    <!-- PRD 编辑区：无 PRD 时默认展开，有 PRD 时点击编辑再展开 -->
    <div v-show="editorVisible || !prdContent" class="prd-editor">
      <el-radio-group v-model="activeTab" size="default" class="prd-tabs">
        <el-radio-button value="text">文本 / Markdown</el-radio-button>
        <el-radio-button value="md">md / txt 上传</el-radio-button>
        <el-radio-button value="pdf">PDF 上传</el-radio-button>
        <el-radio-button value="link">在线链接</el-radio-button>
      </el-radio-group>

      <div v-show="activeTab === 'text'" class="prd-pane">
        <el-input
          v-model="textForm.content"
          type="textarea"
          :autosize="{ minRows: 4, maxRows: 10 }"
          placeholder="粘贴或编辑 PRD 内容（支持 Markdown）。生成用例时 PRD 作为主上下文，代码作为辅助上下文。"
        />
        <div class="pane-actions">
          <el-button type="primary" :loading="saving" :icon="Check" @click="saveText">保存 PRD</el-button>
          <el-button :icon="MagicStick" @click="useSample">使用示例</el-button>
          <el-button v-if="prdContent" :icon="View" @click="previewVisible = true">预览完整</el-button>
        </div>
      </div>

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
        <el-alert v-if="linkError" :title="linkError" type="error" :closable="false" show-icon />
      </div>
    </div>

    <!-- 当前 PRD 摘要 -->
    <Transition name="slide-down">
      <div v-if="prdContent && !editorVisible" class="prd-summary">
        <div class="summary-head">
          <span class="summary-label">
            <el-icon :size="14"><Document /></el-icon>当前 PRD
          </span>
          <span v-if="sourceRef" class="summary-ref">来源：{{ sourceRef }}</span>
        </div>
        <div class="summary-preview md-body" v-html="renderedPrd"></div>
      </div>
    </Transition>

    <!-- 其他上下文信息 -->
    <div class="context-block">
      <div class="context-head">
        <span>其他上下文信息</span>
        <span class="context-hint">可选</span>
      </div>
      <el-input
        v-model="otherContextInfo"
        type="textarea"
        :rows="3"
        placeholder="例如：请重点覆盖支付失败、库存不足等异常场景"
      />
      <div class="pane-actions">
        <el-button :loading="savingContext" :icon="Check" @click="saveContext">保存上下文信息</el-button>
      </div>
    </div>

    <!-- 上下文文档 -->
    <div class="context-block">
      <div class="context-head">
        <span>上下文文档</span>
        <span class="context-hint">可选，可多篇</span>
      </div>
      <div v-if="contextDocs.length" class="doc-list">
        <div v-for="doc in contextDocs" :key="doc.id" class="doc-item">
          <div class="doc-info">
            <div class="doc-title">{{ doc.title || '未命名文档' }}</div>
            <el-tag v-if="doc.sourceType" size="small" effect="plain" class="doc-source">
              {{ docSourceText(doc.sourceType) }}
            </el-tag>
            <div class="doc-preview">{{ doc.content }}</div>
          </div>
          <div class="doc-actions">
            <el-button link :icon="EditPen" @click="openDocDialog(doc)">编辑</el-button>
            <el-button link type="danger" :icon="Delete" @click="removeDoc(doc.id)">删除</el-button>
          </div>
        </div>
      </div>
      <el-empty v-else description="暂无上下文文档" :image-size="50" />
      <el-button size="small" :icon="Plus" @click="openDocDialog()">新增文档</el-button>
    </div>

    <!-- 预览对话框 -->
    <el-dialog v-model="previewVisible" title="PRD 预览" width="760px">
      <div class="prd-full-text md-body" v-html="renderedPrd"></div>
    </el-dialog>

    <!-- 上下文文档编辑对话框 -->
    <el-dialog
      v-model="docDialogVisible"
      :title="docForm.id ? '编辑上下文文档' : '新增上下文文档'"
      width="700px"
    >
      <el-form label-width="70px">
        <el-form-item label="标题">
          <el-input v-model="docForm.title" placeholder="如：接口文档 / 业务说明" />
        </el-form-item>
        <el-form-item label="来源">
          <el-radio-group v-model="docActiveTab" size="default">
            <el-radio-button value="text">文本 / Markdown</el-radio-button>
            <el-radio-button value="md">md / txt 上传</el-radio-button>
            <el-radio-button value="pdf">PDF 上传</el-radio-button>
            <el-radio-button value="link">在线链接</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-show="docActiveTab === 'text'" label="内容">
          <el-input v-model="docForm.content" type="textarea" :rows="10" placeholder="粘贴文档内容，支持 Markdown" />
        </el-form-item>
        <el-form-item v-show="docActiveTab === 'md'" label="上传">
          <el-upload
            drag
            accept=".md,.markdown,.txt"
            :auto-upload="true"
            :show-file-list="false"
            :http-request="handleDocMdUpload"
            class="prd-uploader"
          >
            <div class="uploader-icon">
              <el-icon :size="28"><UploadFilled /></el-icon>
            </div>
            <div class="uploader-text">拖拽 .md / .txt 文件到此处，或 <em>点击上传</em></div>
            <template #tip>
              <div class="uploader-tip">支持 Markdown 和纯文本，限 5MB 以内</div>
            </template>
          </el-upload>
        </el-form-item>
        <el-form-item v-show="docActiveTab === 'pdf'" label="上传">
          <el-upload
            drag
            accept=".pdf"
            :auto-upload="true"
            :show-file-list="false"
            :http-request="handleDocPdfUpload"
            class="prd-uploader"
          >
            <div class="uploader-icon">
              <el-icon :size="28"><UploadFilled /></el-icon>
            </div>
            <div class="uploader-text">拖拽 PDF 到此处，或 <em>点击上传</em></div>
            <template #tip>
              <div class="uploader-tip">仅支持文本型 PDF；扫描件需先 OCR 转文本</div>
            </template>
          </el-upload>
        </el-form-item>
        <el-form-item v-show="docActiveTab === 'link'" label="链接">
          <el-input v-model="docLinkUrl" placeholder="https://example.com/doc.md" clearable>
            <template #prepend>
              <span class="url-prefix">URL</span>
            </template>
          </el-input>
          <div class="pane-actions">
            <el-button :loading="docFetching" :icon="Download" @click="fetchDocLink">
              抓取内容
            </el-button>
          </div>
          <el-alert v-if="docLinkError" :title="docLinkError" type="error" :closable="false" show-icon />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="docDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveDoc">保存</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup>
/**
 * PRD 面板组件
 * v5.9 改版为紧凑模式：
 * - 有 PRD 时默认只展示摘要，点击"编辑 PRD"再展开编辑器
 * - 新增额外 Prompt 与多篇上下文文档
 */
import { ref, computed, watch } from 'vue'
import { ElMessage } from 'element-plus'
import {
  UploadFilled, Check, View, Download, Document, MagicStick, EditPen, Plus, Delete
} from '@element-plus/icons-vue'
import {
  getProjectContext, updatePrd, uploadPrdPdf, fetchPrdUrl, updateProjectContext,
  uploadContextDoc, fetchContextDocUrl
} from '@/api/project'
import samplePrd from '@/assets/samples/order-prd.md?raw'
import { marked } from 'marked'
import DOMPurify from 'dompurify'

const props = defineProps({ projectId: String })

const activeTab = ref('text')
const saving = ref(false)
const fetching = ref(false)
const previewVisible = ref(false)
const editorVisible = ref(false)

const prdContent = ref('')
const sourceType = ref('')
const sourceRef = ref('')

const textForm = ref({ content: '' })
const linkForm = ref({ url: '' })
const linkError = ref('')

// v5.9/v5.10: 其他上下文信息与上下文文档
const otherContextInfo = ref('')
const contextDocs = ref([])
const savingContext = ref(false)
const docDialogVisible = ref(false)
const docForm = ref({ id: '', title: '', content: '', sourceType: 'text', sourceRef: '' })
const docActiveTab = ref('text')
const docLinkUrl = ref('')
const docLinkError = ref('')
const docFetching = ref(false)

const prdPreview = computed(() => {
  if (!prdContent.value) return ''
  return renderedPrd.value
})

const renderedPrd = computed(() => {
  if (!prdContent.value) return ''
  return DOMPurify.sanitize(marked.parse(prdContent.value))
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
    const res = await getProjectContext(props.projectId)
    prdContent.value = res.data?.prdContent || ''
    sourceType.value = res.data?.prdSourceType || ''
    sourceRef.value = res.data?.prdSourceRef || ''
    textForm.value.content = prdContent.value
    otherContextInfo.value = res.data?.otherContextInfo ?? res.data?.extraPrompt ?? ''
    contextDocs.value = (res.data?.contextDocs || []).map((doc) => ({
      id: doc.id || `doc-${Math.random().toString(36).slice(2, 8)}`,
      title: doc.title || '',
      content: doc.content || '',
      sourceType: doc.sourceType || 'text',
      sourceRef: doc.sourceRef || ''
    }))
    editorVisible.value = false
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
    editorVisible.value = false
    ElMessage.success('PRD 已保存')
  } finally {
    saving.value = false
  }
}

function useSample() {
  textForm.value.content = samplePrd
  activeTab.value = 'text'
  ElMessage.info('已载入示例 PRD（电商订单系统），点击"保存 PRD"后即可生成用例')
}

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
      editorVisible.value = false
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
    editorVisible.value = false
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
    editorVisible.value = false
    ElMessage.success('链接内容已抓取')
  } catch (e) {
    linkError.value = e.message || '抓取失败（可能是 SPA 或需认证页面）'
  } finally {
    fetching.value = false
  }
}

async function persistContext(showMessage) {
  savingContext.value = true
  try {
    await updateProjectContext(props.projectId, {
      otherContextInfo: otherContextInfo.value,
      contextDocs: contextDocs.value.map(({ id, title, content, sourceType, sourceRef }) => ({
        id, title, content, sourceType, sourceRef
      }))
    })
    if (showMessage) ElMessage.success('项目上下文已保存')
  } finally {
    savingContext.value = false
  }
}

async function saveContext() {
  await persistContext(true)
}

function openDocDialog(doc) {
  docForm.value = doc
    ? { ...doc }
    : { id: '', title: '', content: '', sourceType: 'text', sourceRef: '' }
  docActiveTab.value = ['md', 'pdf', 'link'].includes(doc?.sourceType) ? doc.sourceType : 'text'
  docLinkUrl.value = doc?.sourceType === 'link' ? (doc.sourceRef || '') : ''
  docLinkError.value = ''
  docDialogVisible.value = true
}

async function saveDoc() {
  if (!docForm.value.title || !docForm.value.title.trim()) {
    ElMessage.warning('请填写文档标题')
    return
  }
  const docPayload = {
    id: docForm.value.id || `doc-${Date.now()}`,
    title: docForm.value.title.trim(),
    content: docForm.value.content,
    sourceType: docForm.value.sourceType || docActiveTab.value,
    sourceRef: docForm.value.sourceRef || (docActiveTab.value === 'link' ? docLinkUrl.value : '')
  }
  if (docForm.value.id) {
    const idx = contextDocs.value.findIndex((d) => d.id === docForm.value.id)
    if (idx >= 0) contextDocs.value[idx] = docPayload
  } else {
    contextDocs.value.push(docPayload)
  }
  docDialogVisible.value = false
  await persistContext(false)
  ElMessage.success('上下文文档已保存')
}

async function removeDoc(id) {
  contextDocs.value = contextDocs.value.filter((d) => d.id !== id)
  await persistContext(false)
  ElMessage.success('上下文文档已删除')
}

async function handleDocMdUpload(option) {
  const file = option.file
  if (file.size > 5 * 1024 * 1024) {
    ElMessage.warning('文件过大，请控制在 5MB 以内')
    option.onError(new Error('文件过大'))
    return
  }
  const reader = new FileReader()
  reader.onload = () => {
    try {
      docForm.value.content = reader.result
      docForm.value.sourceType = 'md'
      docForm.value.sourceRef = file.name
      if (!docForm.value.title) {
        docForm.value.title = file.name.replace(/\.(md|markdown|txt)$/i, '')
      }
      ElMessage.success(`${file.name} 已读取`)
      option.onSuccess(reader.result)
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

async function handleDocPdfUpload(option) {
  try {
    const res = await uploadContextDoc(props.projectId, option.file)
    const doc = res.data || {}
    docForm.value.content = doc.content || ''
    docForm.value.sourceType = 'pdf'
    docForm.value.sourceRef = doc.sourceRef || option.file.name
    if (!docForm.value.title) {
      docForm.value.title = doc.title || option.file.name
    }
    ElMessage.success('PDF 解析成功')
    option.onSuccess(res)
  } catch (e) {
    option.onError(e)
  }
}

async function fetchDocLink() {
  docLinkError.value = ''
  if (!docLinkUrl.value) {
    docLinkError.value = '请输入 URL'
    return
  }
  docFetching.value = true
  try {
    const res = await fetchContextDocUrl(props.projectId, docLinkUrl.value)
    const doc = res.data || {}
    docForm.value.content = doc.content || ''
    docForm.value.sourceType = 'link'
    docForm.value.sourceRef = doc.sourceRef || docLinkUrl.value
    if (!docForm.value.title) {
      docForm.value.title = doc.title || docLinkUrl.value
    }
    ElMessage.success('链接内容已抓取')
  } catch (e) {
    docLinkError.value = e.message || '抓取失败（可能是 SPA 或需认证页面）'
  } finally {
    docFetching.value = false
  }
}

function docSourceText(type) {
  return { text: '文本', md: 'md/txt', pdf: 'PDF', link: '链接' }[type] || '文本'
}
</script>

<style scoped>
.prd-panel {
  background: var(--bg-surface);
  border: 1px solid var(--card-border);
  border-radius: var(--radius-lg);
  padding: 16px;
  box-shadow: var(--shadow-xs);
  margin-bottom: var(--space-lg);
}

.prd-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 12px;
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

.prd-empty-alert {
  margin-bottom: 12px;
}

.prd-editor {
  margin-bottom: 12px;
}

.prd-tabs {
  margin-bottom: 12px;
}

.prd-pane {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.pane-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.prd-uploader {
  width: 100%;

  :deep(.el-upload-dragger) {
    width: 100%;
    padding: 20px;
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

.prd-summary {
  border: 1px solid var(--card-border-light);
  border-radius: var(--radius-md);
  background: #f8fafc;
  overflow: hidden;
  margin-bottom: 12px;
}

.summary-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
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
  color: var(--text-primary);
}

.summary-ref {
  font-size: 12px;
  color: var(--text-tertiary);
}

.summary-preview {
  padding: 10px 14px;
  max-height: 180px;
  overflow: hidden;
  position: relative;

  &::after {
    content: '';
    position: absolute;
    left: 0;
    right: 0;
    bottom: 0;
    height: 28px;
    background: linear-gradient(180deg, transparent, #f8fafc);
  }
}

.md-body {
  font-size: 13px;
  line-height: 1.7;
  color: var(--text-secondary);
}

.context-block {
  border: 1px solid var(--card-border-light);
  border-radius: var(--radius-md);
  background: var(--bg-base);
  padding: 12px;
  margin-bottom: 12px;
}

.context-head {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
  margin-bottom: 8px;
}

.context-hint {
  font-size: 12px;
  font-weight: 400;
  color: var(--text-tertiary);
}

.doc-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 8px;
}

.doc-item {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 10px 12px;
  background: var(--bg-surface);
  border: 1px solid var(--card-border);
  border-radius: var(--radius-md);
}

.doc-info {
  flex: 1;
  min-width: 0;
}

.doc-title {
  display: inline-flex;
  align-items: center;
  font-size: 13px;
  font-weight: 600;
  color: var(--text-primary);
}

.doc-source {
  margin-left: 8px;
}

.doc-preview {
  margin-top: 4px;
  font-size: 12px;
  color: var(--text-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.doc-actions {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
}

.prd-full-text {
  max-height: 70vh;
  overflow-y: auto;
}
</style>
