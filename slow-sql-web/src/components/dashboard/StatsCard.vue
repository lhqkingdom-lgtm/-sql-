<template>
  <el-card shadow="never" class="stats-card" :style="{ borderLeftColor: color }">
    <div class="card-inner">
      <div class="card-icon" :style="{ color }">{{ icon }}</div>
      <div class="card-data">
        <div class="card-value" :style="{ color }">{{ formatted }}</div>
        <div class="card-title">{{ title }}</div>
      </div>
    </div>
  </el-card>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  title: String,
  value: [Number, String],
  icon: String,
  color: { type: String, default: 'var(--accent-blue)' },
})

const formatted = computed(() => {
  const v = props.value
  if (typeof v === 'number') return v.toLocaleString()
  return v || '0'
})
</script>

<style scoped>
.stats-card {
  border-left: 3px solid; border-radius: var(--radius-md);
}
.card-inner { display: flex; align-items: center; gap: 16px; }
.card-icon { font-size: 32px; }
.card-value { font-size: 28px; font-weight: 700; font-family: var(--font-mono); }
.card-title { font-size: 13px; color: var(--text-secondary); margin-top: 2px; }
</style>
