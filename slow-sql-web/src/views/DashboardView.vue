<template>
  <div class="dashboard-view">
    <h2 class="page-title">仪表盘</h2>
    <div class="stats-grid" v-loading="loading">
      <StatsCard title="今日采集" :value="data.todayCount" icon="&#9203;" color="var(--accent-blue)" />
      <StatsCard title="总记录数" :value="data.totalCount" icon="&#9776;" color="var(--accent-green)" />
      <StatsCard title="来源种类" :value="(data.sourceDistribution || []).length" icon="&#9733;" color="var(--accent-orange)" />
      <StatsCard title="Top 高频" :value="(data.topFrequent || []).length" icon="&#9650;" color="var(--accent-cyan)" />
    </div>
    <div class="dashboard-grid">
      <el-card shadow="never">
        <template #header><span>来源分布</span></template>
        <SourcePieChart :data="data.sourceDistribution" />
      </el-card>
      <el-card shadow="never">
        <template #header><span>高频慢 SQL</span></template>
        <TopFrequentTable :data="data.topFrequent" />
      </el-card>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { getStats } from '@/api/dashboard'
import StatsCard from '@/components/dashboard/StatsCard.vue'
import SourcePieChart from '@/components/dashboard/SourcePieChart.vue'
import TopFrequentTable from '@/components/dashboard/TopFrequentTable.vue'

const loading = ref(false)
const data = reactive({
  todayCount: 0, totalCount: 0,
  sourceDistribution: [], topFrequent: [],
})

onMounted(async () => {
  loading.value = true
  try {
    const r = await getStats()
    Object.assign(data, r || {})
  } catch { /* handled by axios interceptor */ }
  loading.value = false
})
</script>

<style scoped>
.page-title { font-size: 20px; margin-bottom: 20px; }
.stats-grid {
  display: grid; grid-template-columns: repeat(4, 1fr); gap: 16px;
  margin-bottom: 20px;
}
.dashboard-grid {
  display: grid; grid-template-columns: 1fr 1fr; gap: 20px;
}
@media (max-width: 768px) {
  .stats-grid { grid-template-columns: repeat(2, 1fr); }
  .dashboard-grid { grid-template-columns: 1fr; }
}
</style>
