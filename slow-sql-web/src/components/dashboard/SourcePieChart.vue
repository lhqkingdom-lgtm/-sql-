<template>
  <div class="pie-wrapper">
    <svg viewBox="0 0 200 200" class="pie-svg" v-if="segments.length">
      <circle
        v-for="(seg, i) in segments"
        :key="i"
        cx="100" cy="100" r="80"
        fill="none"
        :stroke="seg.color"
        :stroke-width="36"
        :stroke-dasharray="`${seg.dash} ${100 - seg.dash}`"
        :stroke-dashoffset="seg.offset"
        :transform="`rotate(-90 100 100)`"
      />
      <text x="100" y="96" text-anchor="middle" fill="var(--text-primary)" font-size="14" font-weight="600">
        {{ total }}
      </text>
      <text x="100" y="114" text-anchor="middle" fill="var(--text-muted)" font-size="11">
        总计
      </text>
    </svg>
    <div class="pie-legend" v-if="segments.length">
      <div v-for="(seg, i) in segments" :key="i" class="legend-item">
        <span class="legend-dot" :style="{ background: seg.color }"></span>
        <span class="legend-label">{{ seg.name }}</span>
        <span class="legend-value">{{ seg.value }}</span>
      </div>
    </div>
    <EmptyState v-else title="暂无数据" />
  </div>
</template>

<script setup>
import { computed } from 'vue'
import EmptyState from '@/components/common/EmptyState.vue'

const props = defineProps({ data: { type: Array, default: () => [] } })

const colors = ['#40c057', '#339af0', '#f76707', '#ae3ec9', '#15aabf']

const segments = computed(() => {
  const list = props.data || []
  const total = list.reduce((s, d) => s + (d.cnt || d.value || 0), 0)
  if (total === 0) return []
  let cumulative = 0
  return list.map((d, i) => {
    const v = d.cnt || d.value || 0
    const pct = v / total
    const seg = {
      name: d.source || d.name || 'unknown',
      value: v,
      color: colors[i % colors.length],
      dash: pct * 100,
      offset: -cumulative * (100 / 100),
    }
    cumulative += pct
    return seg
  })
})

const total = computed(() => (props.data || []).reduce((s, d) => s + (d.cnt || d.value || 0), 0))
</script>

<style scoped>
.pie-wrapper { text-align: center; }
.pie-svg { width: 180px; height: 180px; margin: 0 auto; display: block; }
.pie-svg circle { transition: stroke-dasharray 0.5s ease; }
.pie-legend { margin-top: 12px; display: flex; flex-wrap: wrap; justify-content: center; gap: 12px; }
.legend-item { display: flex; align-items: center; gap: 4px; font-size: 12px; }
.legend-dot { width: 8px; height: 8px; border-radius: 50%; display: inline-block; }
.legend-label { color: var(--text-secondary); }
.legend-value { color: var(--text-primary); font-weight: 600; }
</style>
