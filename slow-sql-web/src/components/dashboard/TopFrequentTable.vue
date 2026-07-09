<template>
  <div class="top-frequent">
    <h4 v-if="data.length">Top SQL 频率排行</h4>
    <el-table :data="data" v-if="data.length" size="small" style="width: 100%">
      <el-table-column type="index" label="#" width="40" />
      <el-table-column prop="sqlText" label="SQL" min-width="200" show-overflow-tooltip />
      <el-table-column prop="occurrenceCount" label="出现次数" width="100" sortable />
    </el-table>
    <EmptyState v-else title="暂无高频SQL" />
  </div>
</template>

<script setup>
import EmptyState from '@/components/common/EmptyState.vue'
defineProps({ data: { type: Array, default: () => [] } })
</script>

<style scoped>
h4 { font-size: 14px; color: var(--text-secondary); margin-bottom: 10px; }
</style>
