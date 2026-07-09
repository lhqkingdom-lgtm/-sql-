<template>
  <el-table
    :data="data" v-loading="loading" size="small"
    @selection-change="(val) => $emit('selectionChange', val)"
    style="width: 100%"
  >
    <el-table-column type="selection" width="40" />
    <el-table-column prop="id" label="ID" width="60" sortable />
    <el-table-column prop="sqlText" label="SQL 文本" min-width="240" show-overflow-tooltip>
      <template #default="{ row }">
        <code class="sql-cell">{{ row.sqlText }}</code>
      </template>
    </el-table-column>
    <el-table-column prop="queryTimeSec" label="耗时" width="80" sortable>
      <template #default="{ row }">{{ row.queryTimeSec }}s</template>
    </el-table-column>
    <el-table-column prop="instanceId" label="实例" width="130" />
    <el-table-column prop="occurrenceCount" label="出现次数" width="90" sortable />
    <el-table-column prop="severity" label="严重度" width="80">
      <template #default="{ row }">
        <el-tag :type="sevType(row.severity)" size="small" effect="dark">
          {{ row.severity || '-' }}
        </el-tag>
      </template>
    </el-table-column>
    <el-table-column prop="diagnosed" label="已诊断" width="80">
      <template #default="{ row }">
        <span v-if="row.diagnosed" style="color:var(--accent-green)">&#10003;</span>
        <span v-else style="color:var(--text-muted)">&#10007;</span>
      </template>
    </el-table-column>
    <el-table-column prop="capturedAt" label="采集时间" width="160">
      <template #default="{ row }">{{ row.capturedAt?.substring(0, 19) || '-' }}</template>
    </el-table-column>
    <el-table-column label="操作" width="80" fixed="right">
      <template #default="{ row }">
        <el-button text type="danger" size="small" @click="$emit('delete', row.id)">删除</el-button>
      </template>
    </el-table-column>
  </el-table>
</template>

<script setup>
defineProps({ data: Array, loading: Boolean })
defineEmits(['delete', 'selectionChange'])

function sevType(s) {
  if (s === 'P0') return 'danger'
  if (s === 'P1') return 'warning'
  return 'info'
}
</script>

<style scoped>
.sql-cell { font-family: var(--font-mono); font-size: 12px; }
</style>
