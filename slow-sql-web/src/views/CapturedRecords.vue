<template>
  <div class="page">
    <div class="page-header">
      <h3>采集记录</h3>
      <div class="filters">
        <el-select v-model="severity" placeholder="严重度" clearable size="small" style="width:110px" @change="load">
          <el-option label="P0" value="P0" />
          <el-option label="P1" value="P1" />
          <el-option label="P2" value="P2" />
        </el-select>
        <el-button size="small" @click="load" :icon="Refresh">刷新</el-button>
      </div>
    </div>

    <el-table :data="records" stripe v-loading="loading" empty-text="暂无采集记录" style="width:100%">
      <el-table-column label="严重度" width="70">
        <template #default="{ row }">
          <span class="severity-tag" :class="'sev-' + (row.severity || 'P2')">
            {{ row.severity || 'P2' }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="SQL 预览" min-width="280" show-overflow-tooltip>
        <template #default="{ row }">{{ row.sqlText }}</template>
      </el-table-column>
      <el-table-column label="用时" width="90" align="right">
        <template #default="{ row }">{{ fmtTime(row.queryTimeSec) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag v-if="row.diagnosed" type="success" size="small">已诊断</el-tag>
          <el-tag v-else type="info" size="small">未诊断</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="来源" width="120">
        <template #default="{ row }">
          {{ sourceLabel(row.source || row._source) }}
        </template>
      </el-table-column>
      <el-table-column label="采集时间" width="160">
        <template #default="{ row }">{{ row.capturedAt?.substring(0,19) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="160" fixed="right">
        <template #default="{ row }">
          <el-button v-if="row.diagnosed" size="small" text type="primary" @click="viewReport(row)">
            查看报告
          </el-button>
          <el-button v-else size="small" text type="primary" :loading="row._diagnosing" @click="diagnose(row)">
            诊断
          </el-button>
          <el-popconfirm title="删除？" @confirm="del(row.id)">
            <template #reference>
              <el-button size="small" text type="danger">删</el-button>
            </template>
          </el-popconfirm>
        </template>
      </el-table-column>
    </el-table>

    <!-- 报告弹窗 -->
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
const severity = ref('')
const reportVisible = ref(false)
const reportHtml = ref('')

function fmtTime(s) {
  if (s == null || s === 0) return '0.00s'
  return Number(s).toFixed(2) + 's'
}

function sourceLabel(s) {
  const map = { slow_log_table:'慢日志表', slow_log_file:'慢日志文件', http_endpoint:'HTTP', manual:'手动' }
  return map[s] || s || '—'
}

async function load() {
  loading.value = true
  try {
    const params = { limit: 50, projectCode: app.currentProject }
    if (severity.value) params.severity = severity.value
    records.value = await http.get('/monitor/records', { params })
    records.value.forEach((r) => {
      r['_diagnosing'] = false
      r['_source'] = r.source // preserve original if column mapping differs
    })
  } catch (e) {
    console.error('加载采集记录失败:', e.message)
  } finally {
    loading.value = false
  }
}

async function diagnose(row) {
  row._diagnosing = true
  try {
    const resp = await http.post(`/monitor/records/${row.id}/diagnose`)
    if (resp.status === 'completed') {
      row.diagnosed = true
      row._report = resp.report
      row.diagnosisReport = resp.report
    } else {
      alert('诊断失败: ' + (resp.error || '未知'))
    }
  } catch (e) {
    alert('诊断请求失败: ' + e.message)
  } finally {
    row._diagnosing = false
  }
}

function viewReport(row) {
  const md = row.diagnosisReport || row._report || ''
  reportHtml.value = md.replace(/^### (.+)/gm, '<h4>$1</h4>')
    .replace(/^## (.+)/gm, '<h3>$1</h3>')
    .replace(/^# (.+)/gm, '<h2>$1</h2>')
    .replace(/```sql([\s\S]*?)```/g, '<pre><code>$1</code></pre>')
    .replace(/```([\s\S]*?)```/g, '<pre><code>$1</code></pre>')
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    .replace(/\n/g, '<br>')
    .replace(/\|(.+)\|/g, (m) => '<span style="font-family:monospace">' + m + '</span>')
  reportVisible.value = true
}

async function del(id) {
  try {
    await http.post('/monitor/records/delete', { id })
    records.value = records.value.filter((r) => r.id !== id)
  } catch (e) {
    alert('删除失败: ' + e.message)
  }
}

// Auto-load when project changes
watch(() => app.currentProject, () => { if (app.currentProject) load() }, { immediate: true })
</script>

<style scoped>
.page { display: flex; flex-direction: column; height: 100%; }
.page-header {
  display: flex; align-items: center; justify-content: space-between;
  margin-bottom: 16px;
}
.page-header h3 { font-size: 18px; font-weight: 600; }
.filters { display: flex; gap: 8px; }

.severity-tag {
  display: inline-block; padding: 2px 10px; border-radius: 12px;
  font-size: 12px; font-weight: 700; color: #fff;
}
.sev-P0 { background: #f56c6c; }
.sev-P1 { background: #e6a23c; }
.sev-P2 { background: #67c23a; }

.report-body {
  max-height: 70vh; overflow-y: auto; line-height: 1.7; font-size: 14px;
}
.report-body h2, .report-body h3, .report-body h4 { margin: 12px 0 6px; }
.report-body pre { background: #1e1e1e; color: #d4d4d4; padding: 12px; border-radius: 6px; overflow-x: auto; }
</style>
