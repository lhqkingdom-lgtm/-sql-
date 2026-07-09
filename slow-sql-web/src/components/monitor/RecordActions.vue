<template>
  <div class="record-actions">
    <el-button @click="$emit('refresh')" :icon="Refresh" circle />
    <el-button
      type="danger" plain size="small"
      :disabled="!selectedIds?.length"
      @click="confirmDelSelected"
    >
      删除选中 ({{ selectedIds?.length || 0 }})
    </el-button>
    <el-button
      type="danger" size="small"
      @click="confirmClearAll"
    >
      清空全部
    </el-button>
    <span class="total-info" v-if="total != null">共 {{ total }} 条</span>
  </div>
</template>

<script setup>
import { Refresh } from '@element-plus/icons-vue'
import { ElMessageBox, ElMessage } from 'element-plus'

const props = defineProps({ selectedIds: Array, total: Number })
const emit = defineEmits(['deleteSelected', 'clearAll', 'refresh'])

function confirmDelSelected() {
  ElMessageBox.confirm(`确定删除选中的 ${props.selectedIds?.length} 条记录？`, '确认', { type: 'warning' })
    .then(() => emit('deleteSelected'))
}

function confirmClearAll() {
  ElMessageBox.confirm('确定清空所有记录？此操作不可恢复。', '危险操作', { type: 'error', confirmButtonText: '确认清空' })
    .then(() => emit('clearAll'))
}
</script>

<style scoped>
.record-actions { display: flex; align-items: center; gap: 10px; margin-bottom: 16px; }
.total-info { font-size: 12px; color: var(--text-muted); margin-left: auto; }
</style>
