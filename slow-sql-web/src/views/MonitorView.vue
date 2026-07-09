<template>
  <div class="monitor-view">
    <div class="filter-bar">
      <el-select v-model="filterInstanceId" placeholder="全部实例" clearable size="default" style="width:160px" @change="load">
        <el-option v-for="id in store.selectedProjectInstances" :key="id" :label="id" :value="id" />
      </el-select>
      <el-select v-model="filterSeverity" placeholder="全部严重度" clearable size="default" style="width:120px" @change="load">
        <el-option label="P0 严重" value="P0" />
        <el-option label="P1 警告" value="P1" />
        <el-option label="P2 关注" value="P2" />
      </el-select>
      <el-button @click="load" :icon="Refresh" circle />
      <el-button type="danger" size="small" @click="confirmClear" :disabled="!records.length">清空当前项目</el-button>
      <span class="total-info">共 {{ records.length }} 条</span>
    </div>

    <el-table :data="records" v-loading="loading" size="small">
      <el-table-column prop="capturedAt" label="时间" width="160">
        <template #default="{ row }">{{ row.capturedAt?.substring(0, 19) || '-' }}</template>
      </el-table-column>
      <el-table-column prop="instanceId" label="实例" width="130" />
      <el-table-column prop="sqlText" label="SQL" min-width="250" show-overflow-tooltip>
        <template #default="{ row }"><code style="font-size:12px">{{ row.sqlText }}</code></template>
      </el-table-column>
      <el-table-column prop="queryTimeSec" label="耗时" width="70" sortable>
        <template #default="{ row }">{{ row.queryTimeSec }}s</template>
      </el-table-column>
      <el-table-column prop="occurrence" label="次数" width="70" sortable />
      <el-table-column prop="severity" label="严重度" width="80">
        <template #default="{ row }">
          <el-tag :type="sev(row.severity)" size="small" effect="dark">{{ row.severity || '-' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="diagnosed" label="诊断" width="70">
        <template #default="{ row }">{{ row.diagnosed ? '✅' : '⏳' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="70">
        <template #default="{ row }">
          <el-button text type="danger" size="small" @click="delRow(row.id)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup>
import { ref, watch, onMounted } from 'vue'
import { ElMessageBox, ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { useAppStore } from '@/stores/app'
import request from '@/api/request'

const store = useAppStore()
const records = ref([])
const loading = ref(false)
const filterInstanceId = ref('')
const filterSeverity = ref('')

function sev(s) { return s === 'P0' ? 'danger' : s === 'P1' ? 'warning' : 'info' }

async function load() {
  loading.value = true
  try {
    records.value = await request.get('/api/monitor/records', {
      params: {
        projectCode: store.selectedProjectCode || undefined,
        instanceId: filterInstanceId.value || undefined,
        severity: filterSeverity.value || undefined,
        limit: 100,
      }
    })
  } catch { records.value = [] }
  loading.value = false
}

async function delRow(id) {
  await ElMessageBox.confirm('删除该记录？', '确认', { type: 'warning' })
  await request.post('/api/monitor/records/delete', { id })
  ElMessage.success('已删除')
  load()
}

async function confirmClear() {
  await ElMessageBox.confirm('清空当前项目的所有记录？', '危险操作', { type: 'error' })
  await request.post('/api/monitor/records/clear', { projectCode: store.selectedProjectCode })
  ElMessage.success('已清空')
  load()
}

watch(() => store.selectedProjectCode, load)
onMounted(load)
</script>

<style scoped>
.filter-bar { display: flex; align-items: center; gap: 10px; margin-bottom: 16px; flex-wrap: wrap; }
.total-info { font-size: 12px; color: var(--text-muted); margin-left: auto; }
</style>
