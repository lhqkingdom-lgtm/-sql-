<template>
  <div class="page">
    <div class="page-header">
      <h3>诊断历史</h3>
      <el-button size="small" @click="load" :icon="Refresh">刷新</el-button>
    </div>

    <el-table :data="records" stripe v-loading="loading" empty-text="暂无诊断记录" style="width:100%">
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <el-tag :type="statusType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="SQL" min-width="260">
        <template #default="{ row }">
          <el-tooltip :content="row.cleanSql || row.originalSql || row.sqlPreview || ''" placement="top" :show-after="400">
            <span class="sql-cell">{{ sqlPreview(row) }}</span>
          </el-tooltip>
        </template>
      </el-table-column>
      <el-table-column label="来源" width="90">
        <template #default="{ row }">{{ sourceLabel(row.source) }}</template>
      </el-table-column>
      <el-table-column label="时间" width="170">
        <template #default="{ row }">{{ row.createdAt?.substring(0,19) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="160" fixed="right">
        <template #default="{ row }">
          <el-button v-if="(row.status||'').toLowerCase()==='completed'" size="small" text type="primary" @click="viewReport(row)">
            查看报告
          </el-button>
          <el-button size="small" text type="primary" :loading="row._retrying" @click="retry(row)">
            重试
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="reportVisible" title="诊断报告" width="760px" top="5vh">
      <div class="report-body" v-html="reportHtml"></div>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, watch } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { useAppStore } from '@/stores/app'
import http from '@/api/request'

const app = useAppStore()
const records = ref([])
const loading = ref(false)
const reportVisible = ref(false)
const reportHtml = ref('')

function sourceLabel(s) {
  const m = { manual:'手动', slow_log_table:'慢表', slow_log_file:'文件', http_endpoint:'HTTP' }
  return m[s] || s || '—'
}

function statusLabel(s) {
  const v = (s || '').toLowerCase()
  if (v === 'completed') return '已完成'
  if (v === 'failed') return '失败'
  if (v === 'running') return '诊断中'
  if (v === 'pending') return '等待中'
  return s || '—'
}

function statusType(s) {
  const v = (s || '').toLowerCase()
  if (v === 'completed') return 'success'
  if (v === 'failed') return 'danger'
  if (v === 'running') return 'warning'
  return 'info'
}

function sqlPreview(row) {
  const sql = row.cleanSql || row.originalSql || row.sqlPreview || ''
  return sql.length > 80 ? sql.substring(0, 80) + '...' : sql
}

async function load() {
  loading.value = true
  try {
    records.value = await http.get('/diagnosis/history', {
      params: { projectCode: app.currentProject }
    })
    records.value.forEach((r) => (r._retrying = false))
  } catch (e) {
    console.error('加载历史失败:', e.message)
  } finally {
    loading.value = false
  }
}

function renderMarkdown(md) {
  if (!md) return ''
  return md
    .replace(/^### (.+)/gm, '<h4>$1</h4>')
    .replace(/^## (.+)/gm, '<h3>$1</h3>')
    .replace(/^# (.+)/gm, '<h2>$1</h2>')
    .replace(/```sql\n?([\s\S]*?)```/g, '<pre><code>$1</code></pre>')
    .replace(/```\n?([\s\S]*?)```/g, '<pre><code>$1</code></pre>')
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    .replace(/\n\n/g, '<p></p>')
    .replace(/\n/g, '<br>')
}

async function viewReport(row) {
  try {
    const detail = await http.get(`/diagnosis/history/${row.taskId}`)
    reportHtml.value = renderMarkdown(detail.report || '无报告内容')
  } catch (e) {
    reportHtml.value = renderMarkdown(row.report || '加载失败')
  }
  reportVisible.value = true
}

async function retry(row) {
  row._retrying = true
  try {
    const resp = await http.post(`/sql/retry/${row.taskId}`)
    if (resp.status === 'completed') {
      row.status = 'completed'
      row.report = resp.report
    }
  } catch (e) {
    alert('重试失败: ' + e.message)
  } finally {
    row._retrying = false
  }
}

watch(() => app.currentProject, () => { if (app.currentProject) load() }, { immediate: true })
</script>

<style scoped>
.page { display: flex; flex-direction: column; height: 100%; }
.page-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 16px; }
.page-header h3 { font-size: 18px; font-weight: 600; }
.report-body { max-height: 70vh; overflow-y: auto; line-height: 1.7; font-size: 14px; }
.report-body :deep(h2) { font-size: 20px; margin: 16px 0 8px; }
.report-body :deep(h3) { font-size: 17px; margin: 14px 0 6px; }
.report-body :deep(pre) { background: #1a1a2e; color: #d4d4d4; padding: 14px; border-radius: 8px; overflow-x: auto; }
</style>
