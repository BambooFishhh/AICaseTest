<template>
  <section class="prd-panel">
    <div class="prd-head">
      <div class="prd-head-text">
        <h2 class="section-title">需求文档</h2>
        <p class="section-desc">可添加多篇文档；每篇标注 PRD 或上下文，生成时会按类型分别注入分析 Agent</p>
      </div>
      <div class="prd-head-meta">
        <el-tag size="small" effect="light">{{ reqDocs.length }} 篇</el-tag>
        <el-button size="small" type="primary" :icon="Plus" @click="openDocDialog()">新增文档</el-button>
      </div>
    </div>

    <el-alert
      v-if="!hasPrdDoc"
      title="请先添加 PRD 文档，否则无法生成测试用例"
      type="warning"
      :closable="false"
      show-icon
      class="prd-empty-alert"
    />

    <div class="context-block">
      <div class="context-head">
        <span>需求文档</span>
        <span class="context-hint">可多篇，类型必选</span>
      </div>
      <div v-if="reqDocs.length" class="doc-list">
        <div v-for="doc in reqDocs" :key="doc.id" class="doc-item">
          <div class="doc-info">
            <div class="doc-title">
              <span class="doc-title-text">{{ doc.title || '未命名文档' }}</span>
              <el-tag
                :type="doc.docType === 'prd' ? 'danger' : 'info'"
                size="small"
                effect="light"
                class="doc-type-tag"
              >
                {{ docTypeText(doc.docType) }}
              </el-tag>
              <el-tag v-if="doc.sourceType" size="small" effect="plain" class="doc-source">
                {{ docSourceText(doc.sourceType) }}
              </el-tag>
            </div>
            <div class="doc-preview">{{ doc.content }}</div>
          </div>
          <div class="doc-actions">
            <el-button link :icon="EditPen" @click="openDocDialog(doc)">编辑</el-button>
            <el-button link type="danger" :icon="Delete" @click="removeDoc(doc.id)">删除</el-button>
          </div>
        </div>
      </div>
      <el-empty v-else description="暂无需求文档" :image-size="50" />
    </div>

    <div class="context-block">
      <div class="context-head">
        <span>补充需求</span>
        <span class="context-hint">可选</span>
      </div>
      <el-input
        v-model="supplementaryRequirements"
        type="textarea"
        :rows="3"
        placeholder="例如：请重点覆盖支付失败、库存不足等异常场景"
      />
      <div class="pane-actions">
        <el-button :loading="savingContext" :icon="Check" @click="saveSupplementary">保存补充需求</el-button>
      </div>
    </div>

    <el-dialog
      v-model="docDialogVisible"
      :title="docForm.id ? '编辑需求文档' : '新增需求文档'"
      width="720px"
    >
      <el-form label-width="70px">
        <el-form-item label="文档类型">
          <el-radio-group v-model="docForm.docType">
            <el-radio-button value="prd">PRD 文档</el-radio-button>
            <el-radio-button value="context">上下文文档</el-radio-button>
          </el-radio-group>
          <div class="form-tip">PRD 是核心需求来源；上下文文档用于补充接口、约束与业务说明</div>
        </el-form-item>
        <el-form-item label="标题">
          <el-input v-model="docForm.title" placeholder="如：订单主 PRD / 支付接口文档" />
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
 * v5.11: 需求文档面板
 * - 多篇需求文档统一管理，每篇标注 PRD / 上下文
 * - 补充需求（原“其他上下文信息”）单独保存
 * - 后端 PrdAgent 会按 PRD 文档 / 上下文文档 / 补充需求三部分拼接 Prompt
 */
import { ref, computed, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  UploadFilled, Check, Download, EditPen, Plus, Delete
} from '@element-plus/icons-vue'
import {
  getProjectContext, updateProjectContext, uploadContextDoc, fetchContextDocUrl
} from '@/api/project'

const props = defineProps({ projectId: String })

const reqDocs = ref([])
const supplementaryRequirements = ref('')
const savingContext = ref(false)
const docDialogVisible = ref(false)
const docForm = ref({
  id: '', title: '', content: '', sourceType: 'text', sourceRef: '', docType: 'prd'
})
const docActiveTab = ref('text')
const docLinkUrl = ref('')
const docLinkError = ref('')
const docFetching = ref(false)

const hasPrdDoc = computed(() => reqDocs.value.some((d) => d.docType === 'prd'))

function normalizeDoc(doc) {
  return {
    id: doc.id || `doc-${Math.random().toString(36).slice(2, 8)}`,
    title: doc.title || '',
    content: doc.content || '',
    sourceType: doc.sourceType || 'text',
    sourceRef: doc.sourceRef || '',
    docType: doc.docType === 'prd' ? 'prd' : 'context'
  }
}

function defaultDocType() {
  return hasPrdDoc.value ? 'context' : 'prd'
}

watch(() => props.projectId, (id) => { if (id) loadPrd() }, { immediate: true })

async function loadPrd() {
  try {
    const res = await getProjectContext(props.projectId)
    let docs = Array.isArray(res.data?.reqDocs) && res.data.reqDocs.length
      ? res.data.reqDocs
      : []
    if (docs.length === 0) {
      if (res.data?.prdContent) {
        docs.push({
          id: 'prd-legacy',
          title: '主 PRD',
          content: res.data.prdContent,
          sourceType: res.data.prdSourceType || 'text',
          sourceRef: res.data.prdSourceRef || '',
          docType: 'prd'
        })
      }
      ;(res.data?.contextDocs || []).forEach((doc) => {
        docs.push({ ...doc, docType: 'context' })
      })
    }
    reqDocs.value = docs.map(normalizeDoc)
    supplementaryRequirements.value =
      res.data?.otherContextInfo ??
      res.data?.supplementaryRequirements ??
      res.data?.extraPrompt ??
      ''
  } catch {
    // 错误已由响应拦截器统一提示
  }
}

async function persistDocs(showMessage, message) {
  savingContext.value = true
  try {
    await updateProjectContext(props.projectId, {
      otherContextInfo: supplementaryRequirements.value,
      supplementaryRequirements: supplementaryRequirements.value,
      reqDocs: reqDocs.value.map(({ id, title, content, sourceType, sourceRef, docType }) => ({
        id, title, content, sourceType, sourceRef, docType
      }))
    })
    if (showMessage) ElMessage.success(message || '需求文档已保存')
  } finally {
    savingContext.value = false
  }
}

async function saveSupplementary() {
  await persistDocs(true, '补充需求已保存')
}

function openDocDialog(doc) {
  docForm.value = doc
    ? normalizeDoc(doc)
    : { id: '', title: '', content: '', sourceType: 'text', sourceRef: '', docType: defaultDocType() }
  docActiveTab.value = ['md', 'pdf', 'link'].includes(docForm.value.sourceType)
    ? docForm.value.sourceType
    : 'text'
  docLinkUrl.value = docForm.value.sourceType === 'link' ? (docForm.value.sourceRef || '') : ''
  docLinkError.value = ''
  docDialogVisible.value = true
}

async function saveDoc() {
  if (!docForm.value.title || !docForm.value.title.trim()) {
    ElMessage.warning('请填写文档标题')
    return
  }
  if (!docForm.value.content || !docForm.value.content.trim()) {
    ElMessage.warning('请填写文档内容或先完成上传/抓取')
    return
  }
  const payload = {
    id: docForm.value.id || `doc-${Date.now()}`,
    title: docForm.value.title.trim(),
    content: docForm.value.content,
    sourceType: docForm.value.sourceType || docActiveTab.value,
    sourceRef: docForm.value.sourceRef || (docActiveTab.value === 'link' ? docLinkUrl.value : ''),
    docType: docForm.value.docType || defaultDocType()
  }
  const idx = reqDocs.value.findIndex((d) => d.id === payload.id)
  if (idx >= 0) {
    reqDocs.value[idx] = payload
  } else {
    reqDocs.value.push(payload)
  }
  docDialogVisible.value = false
  await persistDocs(true, '需求文档已保存')
}

async function removeDoc(id) {
  try {
    await ElMessageBox.confirm('确定删除该需求文档吗？', '删除确认', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch {
    return
  }
  reqDocs.value = reqDocs.value.filter((d) => d.id !== id)
  await persistDocs(true, '需求文档已删除')
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

function docTypeText(type) {
  return type === 'prd' ? 'PRD' : '上下文'
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

.prd-empty-alert {
  margin-bottom: 12px;
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
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  font-size: 13px;
  font-weight: 600;
  color: var(--text-primary);
}

.doc-title-text {
  max-width: 320px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.doc-type-tag,
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

.pane-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: 8px;
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

.form-tip {
  width: 100%;
  font-size: 12px;
  color: var(--text-tertiary);
  margin-top: 4px;
  line-height: 1.4;
}
</style>
