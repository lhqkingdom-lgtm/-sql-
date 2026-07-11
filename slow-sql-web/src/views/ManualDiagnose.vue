<template>
  <div class="diagnose-page">
    <!-- Input Area -->
    <div class="input-card">
      <div class="selectors">
        <el-select v-model="projectCode" placeholder="项目" size="large" style="width:200px" @change="onProjectChange">
          <el-option v-for="p in app.projects" :key="p.code" :label="p.name" :value="p.code" />
        </el-select>
        <el-select v-model="instanceId" placeholder="实例" size="large" style="width:200px">
          <el-option v-for="id in availableInstances" :key="id" :label="id" :value="id" />
        </el-select>
        <el-radio-group v-model="inputType" size="large">
          <el-radio-button value="SQL">SQL</el-radio-button>
          <el-radio-button value="MYBATIS_LOG">MyBatis 日志</el-radio-button>
        </el-radio-group>
      </div>

      <el-input
        v-model="sqlText"
        type="textarea"
        :rows="8"
        :placeholder="inputType === 'MYBATIS_LOG' ? '粘贴 MyBatis 日志...' : '粘贴 SQL 语句...'"
        size="large"
      />

      <el-button
        type="primary"
        size="large"
        :loading="diagnosing"
        :disabled="!canSubmit"
        @click="submitDiagnose"
        style="margin-top:16px;width:100%"
      >
        {{ diagnosing ? '诊断中,请等待...' : '⚡ 开始诊断' }}
      </el-button>
    </div>

    <!-- Report Area -->
    <div v-if="result" class="report-card">
      <div class="report-header">
        <span>
          <el-tag :type="result.status==='COMPLETED' ? 'success' : 'danger'">
            {{ result.status === 'COMPLETED' ? '诊断完成' : '诊断失败' }}
          </el-tag>
        </span>
        <span class="report-meta" v-if="result.status==='COMPLETED'">
          耗时: {{ result.durationMs || 0 }}ms | 工具调用: {{ result.toolCallCount || 0 }}次
        </span>
      </div>
      <div class="report-body" v-html="renderedReport"></div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { useAppStore } from '@/stores/app'
import http from '@/api/request'

const app = useAppStore()

const projectCode = ref('')
const instanceId = ref('')
const inputType = ref('SQL')
const sqlText = ref('')
const diagnosing = ref(false)
const result = ref(null)
const renderedReport = ref('')

const availableInstances = computed(() => {
  const p = app.projects.find((x) => x.code === projectCode.value)
  return p?.instanceIds || []
})

const canSubmit = computed(() => instanceId.value && sqlText.value.trim() && !diagnosing.value)

watch(() => app.currentProject, (v) => { projectCode.value = v }, { immediate: true })
watch(availableInstances, (ids) => {
  if (!instanceId.value || !ids.includes(instanceId.value)) {
    instanceId.value = ids[0] || ''
  }
})

onMounted(() => {
  if (app.projects.length > 0) {
    projectCode.value = app.currentProject || app.projects[0].code
    const p = app.projects.find((x) => x.code === projectCode.value)
    if (p?.instanceIds?.length) instanceId.value = p.instanceIds[0]
  }
})

function onProjectChange(code) {
  app.setProject(code)
}

function renderMarkdown(md) {
  if (!md) return ''
  return md
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/^### (.+)/gm, '<h4>$1</h4>')
    .replace(/^## (.+)/gm, '<h3>$1</h3>')
    .replace(/^# (.+)/gm, '<h2>$1</h2>')
    .replace(/```sql\n?([\s\S]*?)```/g, '<pre><code>$1</code></pre>')
    .replace(/```\n?([\s\S]*?)```/g, '<pre><code>$1</code></pre>')
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    .replace(/\n\n/g, '</p><p>')
    .replace(/\n/g, '<br>')
    .replace(/<p>/g, '<p style="margin:6px 0">')
}

async function submitDiagnose() {
  diagnosing.value = true
  result.value = null
  try {
    const resp = await http.post('/sql/analyze', {
      sql: sqlText.value,
      instanceId: instanceId.value,
      projectCode: projectCode.value || app.currentProject,
      type: inputType.value,
    })
    result.value = resp
    renderedReport.value = renderMarkdown(resp.report || '')
  } catch (e) {
    result.value = { status: 'FAILED', error: e.message }
    renderedReport.value = ''
  } finally {
    diagnosing.value = false
  }
}
</script>

<style scoped>
.diagnose-page {
  display: flex; flex-direction: column; gap: 20px;
  max-width: 960px; margin: 0 auto; width: 100%;
}
.input-card {
  background: var(--el-bg-color-overlay, #fff); border-radius: 8px;
  padding: 20px; border: 1px solid var(--el-border-color-light, #ebeef5);
}
.selectors { display: flex; gap: 12px; margin-bottom: 16px; flex-wrap: wrap; }

.report-card {
  background: var(--el-bg-color-overlay, #fff); border-radius: 8px;
  padding: 24px; border: 1px solid var(--el-border-color-light, #ebeef5);
}
.report-header {
  display: flex; align-items: center; gap: 16px;
  padding-bottom: 16px; border-bottom: 1px solid #eee; margin-bottom: 16px;
}
.report-meta { font-size: 13px; color: #999; }
.report-body { line-height: 1.8; font-size: 14px; }
.report-body :deep(h2) { font-size: 20px; margin: 16px 0 8px; color: #e6a23c; }
.report-body :deep(h3) { font-size: 17px; margin: 14px 0 6px; }
.report-body :deep(h4) { font-size: 15px; margin: 10px 0 4px; }
.report-body :deep(pre) {
  background: #1a1a2e; color: #d4d4d4; padding: 14px;
  border-radius: 8px; overflow-x: auto; font-size: 13px; line-height: 1.5;
}
.report-body :deep(strong) { color: #409eff; }
.report-body :deep(table) { border-collapse: collapse; width: 100%; margin: 8px 0; }
.report-body :deep(td), .report-body :deep(th) {
  border: 1px solid #ddd; padding: 6px 10px; text-align: left;
}
</style>
