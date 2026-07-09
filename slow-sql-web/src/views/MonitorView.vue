<template>
  <div class="monitor-view">
    <h2 class="page-title">采集记录</h2>
    <RecordActions
      :selectedIds="selectedIds"
      :total="records.length"
      @refresh="fetchRecords"
      @deleteSelected="batchDelete"
      @clearAll="clearAll"
    />
    <SqlRecordTable
      :data="records"
      :loading="loading"
      @selectionChange="(val) => selectedIds = val"
      @delete="confirmDelete($event)"
    />
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessageBox, ElMessage } from 'element-plus'
import { getRecords, deleteRecord, clearRecords } from '@/api/monitor'
import SqlRecordTable from '@/components/monitor/SqlRecordTable.vue'
import RecordActions from '@/components/monitor/RecordActions.vue'

const records = ref([])
const selectedIds = ref([])
const loading = ref(false)

async function fetchRecords() {
  loading.value = true
  try { records.value = await getRecords(50) } catch { records.value = [] }
  loading.value = false
}

function confirmDelete(id) {
  ElMessageBox.confirm('确定删除该记录？', '确认', { type: 'warning' })
    .then(async () => { await deleteRecord(id); ElMessage.success('已删除'); fetchRecords() })
}

async function batchDelete() {
  for (const row of selectedIds.value) await deleteRecord(row.id)
  ElMessage.success(`已删除 ${selectedIds.value.length} 条`)
  selectedIds.value = []
  fetchRecords()
}

async function clearAll() {
  await clearRecords()
  ElMessage.success('已清空')
  fetchRecords()
}

onMounted(fetchRecords)
</script>
