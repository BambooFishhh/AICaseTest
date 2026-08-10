<template>
  <el-popover v-model:visible="visible" placement="bottom-start" :width="420" trigger="click">
    <template #reference>
      <el-button :icon="FolderOpened">浏览</el-button>
    </template>
    <div class="dir-selector">
      <div class="dir-toolbar">
        <el-button size="small" text :icon="Back" @click="goUp" :disabled="!currentPath">返回上级</el-button>
        <span class="current-path" :title="currentPath">{{ currentPath || '根目录' }}</span>
      </div>
      <div class="dir-tree-wrapper">
        <el-tree
          ref="treeRef"
          :data="treeData"
          :props="treeProps"
          lazy
          :load="loadNode"
          node-key="path"
          highlight-current
          :expand-on-click-node="false"
          @node-click="handleNodeClick"
        />
      </div>
      <div class="dir-actions">
        <el-button size="small" @click="visible = false">取消</el-button>
        <el-button size="small" type="primary" @click="confirmSelect" :disabled="!selectedPath">确定</el-button>
      </div>
    </div>
  </el-popover>
</template>

<script setup>
import { ref, nextTick } from 'vue'
import { FolderOpened, Back } from '@element-plus/icons-vue'
import { getDirs } from '@/api/filesystem'

const emit = defineEmits(['select'])

const visible = ref(false)
const treeRef = ref()
const treeData = ref([])
const currentPath = ref('')
const selectedPath = ref('')

const treeProps = {
  label: 'name',
  children: 'children',
  isLeaf: 'leaf'
}

// el-tree 懒加载：node.level === 0 时加载根盘符，否则加载子目录
async function loadNode(node, resolve) {
  let path
  if (node.level === 0) {
    path = null // 加载根盘符
  } else {
    path = node.data?.path
    currentPath.value = path
  }
  try {
    const res = await getDirs(path)
    resolve(res.data || [])
  } catch (e) {
    resolve([])
  }
}

function handleNodeClick(data) {
  selectedPath.value = data.path
  currentPath.value = data.path
}

// 返回上级目录
async function goUp() {
  if (!currentPath.value) return
  let parent
  const path = currentPath.value.replace(/\\/g, '/')
  const idx = path.lastIndexOf('/')
  if (idx <= 0) {
    // 已经是根目录（如 C:/），返回盘符列表
    parent = ''
  } else if (idx === 2 && path.charAt(1) === ':') {
    // 如 C:/project → C:/
    parent = path.substring(0, idx + 1)
  } else {
    parent = path.substring(0, idx)
  }
  // 路径转回 Windows 格式
  parent = parent.replace(/\//g, '\\')
  currentPath.value = parent
  selectedPath.value = parent
  // 重新加载树
  await reloadTree(parent)
}

async function reloadTree(path) {
  try {
    const res = await getDirs(path || undefined)
    treeData.value = (res.data || []).map(item => ({
      ...item,
      children: []
    }))
    // 清除懒加载状态，让 el-tree 重新触发
    if (treeRef.value && treeRef.value.store && treeRef.value.store.root) {
      treeRef.value.store.root.loaded = false
    }
  } catch (e) {
    treeData.value = []
  }
}

function confirmSelect() {
  if (selectedPath.value) {
    emit('select', selectedPath.value)
    visible.value = false
  }
}
</script>

<style scoped>
.dir-selector {
  padding: 4px 0;
}
.dir-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
  padding-bottom: 8px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.current-path {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 280px;
}
.dir-tree-wrapper {
  max-height: 300px;
  overflow-y: auto;
  margin-bottom: 8px;
}
.dir-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding-top: 8px;
  border-top: 1px solid var(--el-border-color-lighter);
}
</style>
