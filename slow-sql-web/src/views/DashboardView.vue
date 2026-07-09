<template>
  <div class="dashboard-view">
    <!-- 实例健康 -->
    <div class="instance-health-bar" v-if="store.instancesHealth.length">
      <span
        v-for="inst in filteredInstances"
        :key="inst.instanceId"
        class="health-chip"
        :class="{ reachable: inst.reachable, down: !inst.reachable }"
      >
        {{ inst.reachable ? '●' : '○' }} {{ inst.instanceId }}
      </span>
    </div>

    <!-- 统计卡片 -->
    <div class="stats-grid" v-loading="loading">
      <StatsCard title="今日采集" :value="data.todayCount" icon="⏣" color="var(--accent-blue)" />
      <StatsCard title="总记录数" :value="data.totalCount" icon="☰" color="var(--accent-green)" />
      <StatsCard title="诊断次数" :value="data.diagnosisCount" icon="⚙" color="var(--accent-cyan)" />
      <StatsCard title="来源种类" :value="(data.sourceDistribution || []).length" icon="★" color="var(--accent-orange)" />
    </div>

    <!-- 图表 + Top SQL -->
    <div class="dashboard-grid">
      <el-card shadow="never">
        <template #header><span>来源分布</span></template>
        <SourcePieChart :data="data.sourceDistribution" />
      </el-card>
      <el-card shadow="never">
        <template #header><span>Top 高频 SQL</span></template>
        <TopFrequentTable :data="data.topFrequent" />
      </el-card>
    </div>

    <!-- 最近诊断记录 -->
    <el-card shadow="never" style="margin-top: 20px">
      <template #header><span>最近诊断</span></template>
      <el-table :data="recentDiagnoses" v-loading="diagLoading" size="small" empty-text="暂无诊断记录">
        <el-table-column prop="createdAt" label="时间" width="160">
          <template #default="{ row }">{{ row.createdAt?.substring(0, 19) }}</template>
        </el-table-column>
        <el-table-column prop="instanceId" label="实例" width="130" />
        <el-table-column prop="sqlPreview" label="SQL" min-width="200" show-overflow-tooltip>
          <template #default="{ row }"><code style="font-size:12px">{{ row.sqlPreview }}</code></template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 'COMPLETED' ? 'success' : 'danger'" size="small" effect="dark">
              {{ row.status === 'COMPLETED' ? '已完成' : row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="source" label="来源" width="100" />
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive, computed, watch, onMounted } from 'vue'
import { useAppStore } from '@/stores/app'
import request from '@/api/request'
import StatsCard from '@/components/dashboard/StatsCard.vue'
import SourcePieChart from '@/components/dashboard/SourcePieChart.vue'
import TopFrequentTable from '@/components/dashboard/TopFrequentTable.vue'

const store = useAppStore()
const loading = ref(false)
const diagLoading = ref(false)
const recentDiagnoses = ref([])
const data = reactive({ todayCount: 0, totalCount: 0, diagnosisCount: 0, sourceDistribution: [], topFrequent: [] })

const filteredInstances = computed(() =>
  store.instancesHealth.filter(i => i.projectCode === store.selectedProjectCode)
)

async function load() {
  loading.value = true
  try {
    const pc = store.selectedProjectCode
    const r = await request.get('/api/dashboard/stats', { params: { projectCode: pc || undefined } })
    Object.assign(data, { todayCount: 0, totalCount: 0, diagnosisCount: 0, sourceDistribution: [], topFrequent: [], ...r })
  } catch { /* handled */ }
  loading.value = false
}

async function loadHistory() {
  diagLoading.value = true
  try {
    recentDiagnoses.value = await request.get('/api/sql/history', {
      params: { projectCode: store.selectedProjectCode || undefined, limit: 10 }
    })
  } catch { recentDiagnoses.value = [] }
  diagLoading.value = false
}

watch(() => store.selectedProjectCode, () => { load(); loadHistory() })
onMounted(() => { load(); loadHistory() })
</script>

<style scoped>
.instance-health-bar { display: flex; gap: 12px; margin-bottom: 16px; flex-wrap: wrap; }
.health-chip { font-size: 12px; padding: 2px 10px; border-radius: 12px; background: var(--bg-secondary); border: 1px solid var(--border-color); }
.health-chip.reachable { color: var(--accent-green); }
.health-chip.down { color: var(--accent-red); }
.stats-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 16px; margin-bottom: 20px; }
.dashboard-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 20px; }
@media (max-width: 768px) { .stats-grid { grid-template-columns: repeat(2, 1fr); } .dashboard-grid { grid-template-columns: 1fr; } }
</style>
