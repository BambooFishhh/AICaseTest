<template>
  <div class="project-create">
    <div class="page-header">
      <h2>创建项目</h2>
      <el-button @click="goBack">返回</el-button>
    </div>

    <el-card class="form-card">
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-width="100px"
        class="create-form"
      >
        <el-form-item label="项目名称" prop="name">
          <el-input
            v-model="form.name"
            placeholder="请输入项目名称"
            maxlength="200"
            show-word-limit
          />
        </el-form-item>
        <el-form-item label="来源类型" prop="sourceType">
          <el-select v-model="form.sourceType" placeholder="请选择来源类型">
            <el-option label="本地路径" value="local_path" />
            <el-option label="Git 地址" value="git_url" />
            <el-option label="无代码（纯 PRD）" value="none" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="form.sourceType !== 'none'" label="项目路径" prop="sourcePath">
          <el-input
            v-model="form.sourcePath"
            placeholder="请输入项目源码路径"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="submitting" @click="handleSubmit">
            创建
          </el-button>
          <el-button @click="goBack">取消</el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { createProject } from '@/api/project'

const router = useRouter()
const formRef = ref()
const submitting = ref(false)

const form = reactive({
  name: '',
  sourceType: 'local_path',
  sourcePath: ''
})

const rules = {
  name: [
    { required: true, message: '请输入项目名称', trigger: 'blur' },
    { min: 1, max: 200, message: '长度在 1 到 200 个字符', trigger: 'blur' }
  ],
  sourcePath: [
    {
      // v3.0: 仅非"无代码"时必填
      validator: (rule, value, callback) => {
        if (form.sourceType !== 'none' && (!value || !value.trim())) {
          callback(new Error('请输入项目路径'))
        } else {
          callback()
        }
      },
      trigger: 'blur'
    }
  ]
}

// v3.0: 选"无代码"时清空路径
watch(() => form.sourceType, (newType) => {
  if (newType === 'none') {
    form.sourcePath = ''
  }
})

async function handleSubmit() {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    submitting.value = true
    try {
      const res = await createProject({
        name: form.name,
        sourceType: form.sourceType,
        sourcePath: form.sourcePath
      })
      ElMessage.success('项目创建成功')
      router.push(`/projects/${res.data.id}`)
    } catch (e) {
      // 错误已由响应拦截器统一提示
    } finally {
      submitting.value = false
    }
  })
}

function goBack() {
  router.back()
}
</script>

<style scoped>
.project-create {
  padding: 20px;
}
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}
.page-header h2 {
  margin: 0;
}
.form-card {
  max-width: 600px;
  margin: 0 auto;
}
.create-form {
  margin-top: 10px;
}
</style>
