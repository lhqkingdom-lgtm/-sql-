<template>
  <div class="sql-input">
    <el-input
      v-model="sql"
      type="textarea"
      :rows="10"
      placeholder="请输入需要诊断的慢SQL语句&#10;&#10;例如：SELECT * FROM orders WHERE status='pending' ORDER BY created_at DESC LIMIT 100"
      :disabled="store.isDiagnosing"
      class="sql-textarea"
    />
    <div class="input-actions">
      <span class="char-count">{{ sql.length }} 字符</span>
      <el-button @click="handleClear" :disabled="store.isDiagnosing || !sql">清空</el-button>
      <el-button
        type="primary"
        @click="handleSubmit"
        :disabled="!canSubmit"
        :loading="store.isDiagnosing"
      >
        {{ store.isDiagnosing ? '诊断中...' : '开始诊断' }}
      </el-button>
    </div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { useDiagnosisStore } from '@/stores/diagnosis'

const store = useDiagnosisStore()
const sql = ref(store.sqlText)

const canSubmit = computed(() =>
  sql.value.trim() &&
  store.selectedInstanceId &&
  !store.isDiagnosing
)

function handleClear() {
  sql.value = ''
  store.resetDiagnosis()
}

function handleSubmit() {
  store.setSql(sql.value)
  store.submitDiagnosis()
}
</script>

<style scoped>
.sql-textarea :deep(textarea) {
  font-family: var(--font-mono) !important;
  font-size: 14px !important;
  line-height: 1.7 !important;
  background: var(--bg-secondary) !important;
  color: var(--text-primary) !important;
  resize: vertical;
}
.input-actions {
  display: flex; align-items: center; justify-content: flex-end;
  gap: 10px; margin-top: 10px;
}
.char-count { font-size: 12px; color: var(--text-muted); margin-right: auto; }
</style>
