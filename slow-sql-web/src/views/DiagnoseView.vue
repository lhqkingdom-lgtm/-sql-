<template>
  <div class="diagnose-view">
    <!-- 左栏: 输入区 -->
    <div class="diagnose-left">
      <el-card shadow="never">
        <template #header><span>手动诊断</span></template>

        <div class="input-mode-tabs">
          <el-radio-group v-model="inputMode" size="small">
            <el-radio-button value="sql">手动SQL</el-radio-button>
            <el-radio-button value="mybatis">MyBatis日志</el-radio-button>
          </el-radio-group>
        </div>

        <el-select v-model="selectedInstance" placeholder="选择实例" style="width:100%;margin-top:10px" :disabled="diagnosing">
          <el-option v-for="id in store.selectedProjectInstances" :key="id" :label="id" :value="id" />
        </el-select>

        <el-input
          v-model="sqlText"
          type="textarea"
          :rows="10"
          :placeholder="inputPlaceholder"
          class="sql-textarea"
          :disabled="diagnosing"
        />

        <div class="input-actions">
          <span class="char-count">{{ sqlText.length }} 字符</span>
          <el-button @click="handleClear" :disabled="diagnosing">清空</el-button>
          <el-button type="primary" @click="handleSubmit" :disabled="!canSubmit" :loading="diagnosing">
            {{ diagnosing ? '诊断中...' : '开始诊断' }}
          </el-button>
        </div>

        <!-- 进度 -->
        <div class="progress-area" v-if="diagnosing || status === 'completed' || status === 'failed'">
          <div class="progress-card" :class="'status-' + status">
            <div class="status-row">
              <el-progress v-if="diagnosing" :percentage="100" :indeterminate="true" :duration="3" :stroke-width="3" style="width:60px" />
              <span class="status-text">{{ statusText }}</span>
              <el-tag v-if="connectionMode" size="small" effect="dark" :type="connectionMode === 'sse' ? 'success' : 'warning'">
                {{ connectionMode === 'sse' ? 'SSE' : '轮询' }}
              </el-tag>
            </div>
            <div class="task-info" v-if="taskId"><span>Task: </span><code>{{ taskId }}</code></div>
            <el-alert v-if="status === 'failed' && errorMsg" :title="errorMsg" type="error" :closable="false" show-icon style="margin-top:10px" />
          </div>
        </div>
      </el-card>

      <!-- 诊断历史 -->
      <el-card shadow="never" style="margin-top:16px">
        <template #header><span>诊断历史</span></template>
        <div v-if="history.length" class="history-list">
          <div
            v-for="h in history"
            :key="h.taskId"
            class="history-item"
            :class="{ active: h.taskId === taskId }"
            @click="viewHistoryDetail(h.taskId)"
          >
            <span class="h-status">{{ h.status === 'COMPLETED' ? '✅' : '❌' }}</span>
            <code class="h-sql">{{ h.sqlPreview }}</code>
            <span class="h-time">{{ h.createdAt?.substring(11, 19) }}</span>
          </div>
        </div>
        <EmptyState v-else title="暂无诊断记录" description="提交SQL诊断后自动记录" />
      </el-card>
    </div>

    <!-- 右栏: 结果 -->
    <div class="diagnose-right">
      <div v-if="status === 'completed' && report">
        <div class="report-header">
          <h3>诊断报告</h3>
          <div class="report-actions">
            <el-button text size="small" @click="copyReport">复制</el-button>
            <el-button text size="small" @click="downloadReport">下载.md</el-button>
            <el-button type="primary" size="small" @click="resetAll">重新诊断</el-button>
          </div>
        </div>
        <div class="report-body">
          <MarkdownRenderer :content="report" />
        </div>
      </div>
      <EmptyState v-else title="开始诊断" description="选择实例，输入SQL，AI 将自动调用工具分析并给出优化建议" />
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { useAppStore } from '@/stores/app'
import request from '@/api/request'
import { diagnoseWithFallback } from '@/api/diagnosis'
import MarkdownRenderer from '@/components/common/MarkdownRenderer.vue'
import EmptyState from '@/components/common/EmptyState.vue'

const store = useAppStore()
const inputMode = ref('sql')
const selectedInstance = ref('')
const sqlText = ref('')
const taskId = ref(null)
const status = ref('idle')
const report = ref(null)
const errorMsg = ref(null)
const connectionMode = ref(null)
const history = ref([])
let activeDiag = null

const inputPlaceholder = computed(() => inputMode.value === 'mybatis'
  ? '粘贴MyBatis日志...\n\nPreparing: SELECT * FROM orders WHERE id = ? AND status = ?\nParameters: 1(Long), done(String)'
  : '输入SQL...\n\nSELECT * FROM _slow_test WHERE status="done" ORDER BY name')
const diagnosing = computed(() => status.value === 'pending' || status.value === 'streaming')
const canSubmit = computed(() => sqlText.value.trim() && selectedInstance.value && !diagnosing.value)
const statusText = computed(() => ({
  pending: '提交中...', streaming: 'AI 分析中...', completed: '诊断完成', failed: '诊断失败'
}[status.value] || ''))

watch(() => store.selectedProjectCode, () => { selectedInstance.value = ''; loadHistory() })
onMounted(() => { loadHistory() })

async function loadHistory() {
  try {
    history.value = await request.get('/api/sql/history', {
      params: { projectCode: store.selectedProjectCode || undefined, limit: 30 }
    })
  } catch { history.value = [] }
}

async function handleSubmit() {
  status.value = 'pending'; errorMsg.value = null; report.value = null; connectionMode.value = null
  try {
    const d = await request.post('/api/sql/analyze', {
      instanceId: selectedInstance.value, sql: sqlText.value.trim(),
      projectCode: store.selectedProjectCode, type: inputMode.value === 'mybatis' ? 'MYBATIS_LOG' : undefined,
    })
    if (d?.errorCode) { status.value = 'failed'; errorMsg.value = `[${d.errorCode}] ${d.error || ''}`; return }
    taskId.value = d.taskId
    status.value = 'streaming'
    activeDiag = diagnoseWithFallback(d.taskId, (s, data) => {
      if (s === 'completed') { status.value = 'completed'; report.value = data.report || ''; connectionMode.value = 'sse' }
      else if (s === 'failed') { status.value = 'failed'; errorMsg.value = data.error || '' }
      else if (s === 'polling') connectionMode.value = 'polling'
    }, { sseTimeoutMs: 60000, pollIntervalMs: 2000, maxPolls: 90 })
  } catch (e) { status.value = 'failed'; errorMsg.value = e?.response?.data?.error || '请求失败' }
}

async function viewHistoryDetail(tid) {
  resetAll()
  try {
    const d = await request.get(`/api/sql/history/${tid}`)
    taskId.value = d.taskId; status.value = 'completed'; report.value = d.report
    selectedInstance.value = d.instanceId || ''
  } catch { ElMessage.warning('详情加载失败') }
}

function handleClear() { resetAll(); sqlText.value = '' }
function resetAll() {
  if (activeDiag) { activeDiag.cancel(); activeDiag = null }
  status.value = 'idle'; taskId.value = null; report.value = null; errorMsg.value = null; connectionMode.value = null
}
function copyReport() { navigator.clipboard.writeText(report.value || ''); ElMessage.success('已复制') }
function downloadReport() {
  const blob = new Blob([report.value || ''], { type: 'text/markdown' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a'); a.href = url; a.download = `diagnosis-${taskId.value}.md`; a.click()
  URL.revokeObjectURL(url)
}
</script>

<style scoped>
.diagnose-view { display: grid; grid-template-columns: 420px 1fr; gap: 20px; height: calc(100vh - 100px); }
@media (max-width: 900px) { .diagnose-view { grid-template-columns: 1fr; } }
.diagnose-left { overflow-y: auto; }
.diagnose-right { overflow-y: auto; }
.sql-textarea { margin-top: 10px; }
.sql-textarea :deep(textarea) { font-family: var(--font-mono); font-size: 14px; line-height: 1.7; }
.input-mode-tabs { display: flex; gap: 8px; }
.input-actions { display: flex; align-items: center; justify-content: flex-end; gap: 10px; margin-top: 10px; }
.char-count { font-size: 12px; color: var(--text-muted); margin-right: auto; }
.progress-area { margin-top: 16px; }
.progress-card { background: var(--bg-elevated); border: 1px solid var(--border-color); border-radius: var(--radius-md); padding: 16px; }
.progress-card.status-completed { border-color: var(--accent-green); }
.progress-card.status-failed { border-color: var(--accent-red); }
.status-row { display: flex; align-items: center; gap: 12px; }
.status-text { font-size: 14px; font-weight: 500; }
.task-info { margin-top: 8px; font-size: 12px; color: var(--text-muted); }
.task-info code { font-family: var(--font-mono); }
.history-list { max-height: 300px; overflow-y: auto; }
.history-item { display: flex; align-items: center; gap: 8px; padding: 6px 8px; cursor: pointer; border-radius: 4px; font-size: 12px; }
.history-item:hover { background: var(--bg-hover); }
.history-item.active { background: rgba(51, 154, 240, 0.1); border-left: 2px solid var(--accent-blue); }
.h-sql { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.h-time { color: var(--text-muted); font-family: var(--font-mono); font-size: 11px; }
.report-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.report-header h3 { font-size: 16px; color: var(--accent-cyan); }
.report-actions { display: flex; gap: 8px; }
.report-body { background: var(--bg-secondary); border: 1px solid var(--border-color); border-radius: var(--radius-md); padding: 24px; }
</style>
