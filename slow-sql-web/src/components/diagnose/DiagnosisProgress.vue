<template>
  <div class="diagnosis-progress" v-if="status !== 'idle'">
    <div class="progress-card" :class="statusClass">
      <div class="status-row">
        <el-icon v-if="status === 'completed'" class="status-icon success" :size="24"><CircleCheckFilled /></el-icon>
        <el-icon v-else-if="status === 'failed'" class="status-icon danger" :size="24"><CircleCloseFilled /></el-icon>
        <el-progress
          v-else
          :percentage="100"
          :indeterminate="true"
          :duration="3"
          :stroke-width="3"
          style="width: 60px"
        />
        <span class="status-text">{{ statusText }}</span>
        <el-tag v-if="connectionMode === 'polling'" size="small" type="warning" effect="dark">轮询模式</el-tag>
        <el-tag v-else-if="connectionMode" size="small" type="success" effect="dark">SSE</el-tag>
      </div>
      <div class="task-info" v-if="store.taskId">
        <span class="label">Task ID:</span>
        <code>{{ store.taskId }}</code>
      </div>
      <div class="error-info" v-if="status === 'failed' && store.error">
        <el-alert :title="store.error" type="error" :closable="false" show-icon />
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { CircleCheckFilled, CircleCloseFilled } from '@element-plus/icons-vue'
import { useDiagnosisStore } from '@/stores/diagnosis'

const store = useDiagnosisStore()

const status = computed(() => store.status)
const connectionMode = computed(() => store.connectionMode)

const statusClass = computed(() => `status-${status.value}`)
const statusText = computed(() => {
  switch (store.status) {
    case 'pending': return '正在提交诊断请求...'
    case 'streaming': return 'DeepSeek AI 正在分析...'
    case 'completed': return '诊断完成'
    case 'failed': return '诊断失败'
    default: return ''
  }
})
</script>

<style scoped>
.progress-card {
  background: var(--bg-secondary); border: 1px solid var(--border-color);
  border-radius: var(--radius-md); padding: 20px; margin-bottom: 16px;
}
.progress-card.status-completed { border-color: var(--accent-green); }
.progress-card.status-failed { border-color: var(--accent-red); }
.status-row { display: flex; align-items: center; gap: 12px; margin-bottom: 10px; }
.status-icon.success { color: var(--accent-green); }
.status-icon.danger { color: var(--accent-red); }
.status-text { font-size: 15px; font-weight: 500; }
.task-info { display: flex; align-items: center; gap: 8px; }
.task-info .label { font-size: 12px; color: var(--text-muted); }
.task-info code { font-family: var(--font-mono); font-size: 12px; color: var(--text-secondary); }
.error-info { margin-top: 10px; }
</style>
