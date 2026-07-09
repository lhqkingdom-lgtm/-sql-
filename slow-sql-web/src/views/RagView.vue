<template>
  <div class="rag-view">
    <h2 class="page-title">知识库</h2>
    <div class="rag-toolbar">
      <el-input v-model="searchKeyword" placeholder="搜索标签..." clearable style="width: 200px" @change="loadDocs" />
      <el-select v-model="filterCategory" placeholder="全部分类" clearable style="width: 140px" @change="loadDocs">
        <el-option label="军规" value="军规" />
        <el-option label="事故复盘" value="事故复盘" />
        <el-option label="业务规则" value="业务规则" />
      </el-select>
      <el-button type="primary" @click="openCreate">新建文档</el-button>
    </div>

    <el-table :data="documents" v-loading="loading" size="small" style="width: 100%; margin-top: 10px">
      <el-table-column prop="id" label="ID" width="60" />
      <el-table-column prop="title" label="标题" min-width="150" />
      <el-table-column prop="category" label="分类" width="100">
        <template #default="{ row }">
          <el-tag size="small" effect="dark">{{ row.category }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="tags" label="标签" width="120" show-overflow-tooltip />
      <el-table-column prop="enabled" label="启用" width="60">
        <template #default="{ row }">{{ row.enabled ? '是' : '否' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="140">
        <template #default="{ row }">
          <el-button text size="small" @click="openEdit(row)">编辑</el-button>
          <el-button text type="danger" size="small" @click="confirmDelete(row.id)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑文档' : '新建文档'" width="560px">
      <el-form :model="form" label-width="60px">
        <el-form-item label="标题"><el-input v-model="form.title" /></el-form-item>
        <el-form-item label="内容"><el-input v-model="form.content" type="textarea" :rows="6" /></el-form-item>
        <el-form-item label="分类">
          <el-select v-model="form.category" style="width: 100%">
            <el-option label="军规" value="军规" />
            <el-option label="事故复盘" value="事故复盘" />
            <el-option label="业务规则" value="业务规则" />
          </el-select>
        </el-form-item>
        <el-form-item label="标签"><el-input v-model="form.tags" placeholder="逗号分隔" /></el-form-item>
        <el-form-item label="启用" v-if="editingId">
          <el-switch v-model="form.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveDoc">{{ editingId ? '保存' : '创建' }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessageBox, ElMessage } from 'element-plus'
import { getDocuments, createDocument, updateDocument, deleteDocument } from '@/api/rag'

const documents = ref([])
const loading = ref(false)
const searchKeyword = ref('')
const filterCategory = ref('')
const dialogVisible = ref(false)
const editingId = ref(null)

const form = reactive({ title: '', content: '', category: '业务规则', tags: '', enabled: true })

async function loadDocs() {
  loading.value = true
  try {
    documents.value = await getDocuments(filterCategory.value || undefined, searchKeyword.value || undefined)
  } catch { documents.value = [] }
  loading.value = false
}

function openCreate() {
  editingId.value = null
  Object.assign(form, { title: '', content: '', category: '业务规则', tags: '', enabled: true })
  dialogVisible.value = true
}

function openEdit(row) {
  editingId.value = row.id
  Object.assign(form, {
    title: row.title, content: row.content,
    category: row.category, tags: row.tags, enabled: row.enabled,
  })
  dialogVisible.value = true
}

async function saveDoc() {
  if (editingId.value) {
    await updateDocument(editingId.value, form)
    ElMessage.success('已更新')
  } else {
    await createDocument(form)
    ElMessage.success('已创建')
  }
  dialogVisible.value = false
  loadDocs()
}

function confirmDelete(id) {
  ElMessageBox.confirm('确定删除该文档？', '确认', { type: 'warning' })
    .then(async () => { await deleteDocument(id); ElMessage.success('已删除'); loadDocs() })
}

onMounted(loadDocs)
</script>

<style scoped>
.rag-toolbar { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
</style>
