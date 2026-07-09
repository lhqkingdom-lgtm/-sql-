<template>
  <div class="top-header">
    <div class="header-left">
      <h1 class="app-title">慢SQL智能诊断平台</h1>
      <span class="version-tag">V5.0</span>
    </div>
    <div class="header-right">
      <span class="health-dot" :class="store.healthStatus">
        {{ store.healthStatus === 'up' ? '●' : store.healthStatus === 'down' ? '●' : '○' }}
      </span>
      <span class="health-label">{{ healthLabel }}</span>
      <span class="time">{{ now }}</span>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useAppStore } from '@/stores/app'

const store = useAppStore()
const now = ref('')

function updateTime() {
  now.value = new Date().toLocaleTimeString('zh-CN', { hour12: false })
}

let timer = null
onMounted(() => { updateTime(); timer = setInterval(updateTime, 1000) })
onUnmounted(() => clearInterval(timer))

const healthLabel = computed(() => {
  switch (store.healthStatus) {
    case 'up': return '服务正常'
    case 'down': return '服务异常'
    default: return '检测中...'
  }
})
</script>

<style scoped>
.top-header {
  width: 100%; display: flex; justify-content: space-between; align-items: center;
}
.header-left { display: flex; align-items: center; gap: 10px; }
.app-title { font-size: 16px; font-weight: 600; color: var(--text-primary); }
.version-tag {
  font-size: 11px; background: var(--accent-blue); color: #fff;
  padding: 1px 6px; border-radius: 4px; font-weight: 500;
}
.header-right { display: flex; align-items: center; gap: 8px; }
.health-dot { font-size: 12px; }
.health-dot.up { color: var(--accent-green); }
.health-dot.down { color: var(--accent-red); }
.health-dot.unknown { color: var(--text-muted); }
.health-label { font-size: 12px; color: var(--text-secondary); }
.time {
  font-family: var(--font-mono); font-size: 13px;
  color: var(--text-muted); margin-left: 12px;
}
</style>
