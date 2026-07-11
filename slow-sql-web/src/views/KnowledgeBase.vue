<template>
  <div class="page">
    <div class="page-header">
      <h3>知识库</h3>
      <el-button type="primary" size="small" @click="openAdd">+ 新增规则</el-button>
    </div>

    <el-table :data="docs" stripe v-loading="loading" empty-text="暂无知识库文档" style="width:100%">
      <el-table-column label="标题" min-width="200">
        <template #default="{ row }">{{ row.title }}</template>
      </el-table-column>
      <el-table-column label="分类" width="110">
        <template #default="{ row }">
          <el-tag size="small">{{ row.category || '未分类' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="内容" min-width="280" show-overflow-tooltip>
        <template #default="{ row }">{{ row.content }}</template>
      </el-table-column>
      <el-table-column label="操作" width="140" fixed="right">
        <template #default="{ row }">
          <el-button size="small" text type="primary" @click="openEdit(row)">编辑</el-button>
          <el-popconfirm title="删除？" @confirm="del(row.id)">
            <template #reference>
              <el-button size="small" text type="danger">删</el-button>
            </template>
          </el-popconfirm>
        </template>
      </el-table-column>
    </el-table>

    <!-- Add/Edit Dialog -->
    <el-dialog v-model="dialogVisible" :title="editing ? '编辑规则' : '新增规则'" width="600px" top="8vh">
      <el-form :model="form" label-position="top">
        <el-form-item label="标题">
          <el-input v-model="form.title" />
        </el-form-item>
        <el-form-item label="分类">
          <el-select v-model="form.category" placeholder="选择分类" style="width:100%">
            <el-option label="军规" value="军规" />
            <el-option label="事故复盘" value="事故复盘" />
            <el-option label="业务规则" value="业务规则" />
            <el-option label="其他" value="其他" />
          </el-select>
        </el-form-item>
        <el-form-item label="内容（Markdown）">
          <el-input v-model="form.content" type="textarea" :rows="8" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import http from '@/api/request'

const docs = ref([])
const loading = ref(false)
const saving = ref(false)
const dialogVisible = ref(false)
const editing = ref(null)
const form = ref({ title: '', category: '军规', content: '' })

async function load() {
  loading.value = true
  try {
    docs.value = await http.get('/rag/documents')
  } catch (e) {
    console.error('加载知识库失败:', e.message)
  } finally {
    loading.value = false
  }
}

function openAdd() {
  editing.value = null
  form.value = { title: '', category: '军规', content: '' }
  dialogVisible.value = true
}

function openEdit(row) {
  editing.value = row
  form.value = { title: row.title, category: row.category, content: row.content }
  dialogVisible.value = true
}

async function save() {
  saving.value = true
  try {
    if (editing.value) {
      await http.put(`/rag/documents/${editing.value.id}`, form.value)
    } else {
      await http.post('/rag/documents', form.value)
    }
    dialogVisible.value = false
    await load()
  } catch (e) {
    alert('保存失败: ' + e.message)
  } finally {
    saving.value = false
  }
}

async function del(id) {
  try {
    await http.delete(`/rag/documents/${id}`)
    docs.value = docs.value.filter((d) => d.id !== id)
  } catch (e) {
    alert('删除失败: ' + e.message)
  }
}

onMounted(load)
</script>

<style scoped>
.page { display: flex; flex-direction: column; height: 100%; }
.page-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 16px; }
.page-header h3 { font-size: 18px; font-weight: 600; }
</style>
