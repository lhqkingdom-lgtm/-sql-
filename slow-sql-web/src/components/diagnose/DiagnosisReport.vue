<template>
  <div class="diagnosis-report" v-if="store.status === 'completed'">
    <div class="report-header">
      <h3>诊断报告</h3>
      <div class="report-actions">
        <el-button text size="small" @click="copyReport">复制报告</el-button>
        <el-button text size="small" @click="downloadReport">下载 .md</el-button>
        <el-button type="primary" size="small" @click="store.resetDiagnosis()">重新诊断</el-button>
      </div>
    </div>
    <div class="report-body">
      <MarkdownRenderer :content="store.report || ''" />
    </div>
  </div>
  <div class="diagnosis-empty" v-else-if="store.status === 'idle'">
    <EmptyState
      title="输入 SQL 开始诊断"
      description="选择目标 MySQL 实例，输入慢 SQL，AI 将自动调用工具分析并给出优化建议"
    />
  </div>
</template>

<script setup>
import { ElMessage } from 'element-plus'
import { useDiagnosisStore } from '@/stores/diagnosis'
import MarkdownRenderer from '@/components/common/MarkdownRenderer.vue'
import EmptyState from '@/components/common/EmptyState.vue'

const store = useDiagnosisStore()

function copyReport() {
  navigator.clipboard.writeText(store.report || '')
  ElMessage.success('已复制到剪贴板')
}

function downloadReport() {
  const blob = new Blob([store.report || ''], { type: 'text/markdown' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url; a.download = `diagnosis-${store.taskId}.md`
  a.click(); URL.revokeObjectURL(url)
}
</script>

<style scoped>
.report-header {
  display: flex; align-items: center; justify-content: space-between;
  margin-bottom: 16px;
}
.report-header h3 { font-size: 16px; color: var(--accent-cyan); }
.report-actions { display: flex; gap: 8px; }
.report-body {
  background: var(--bg-secondary); border: 1px solid var(--border-color);
  border-radius: var(--radius-md); padding: 24px;
}
</style>
