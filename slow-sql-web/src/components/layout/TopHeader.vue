<template>
  <div class="top-header">
    <div class="header-left">
      <h1 class="app-title">慢SQL诊断平台</h1>
      <span class="version-tag">V5.0</span>
    </div>
    <div class="header-center">
      <el-select
        v-model="store.selectedProjectCode"
        @change="store.selectProject($event)"
        placeholder="选择项目"
        size="default"
        class="project-switcher"
      >
        <el-option v-for="p in store.projects" :key="p.code" :label="p.name" :value="p.code" />
      </el-select>
      <span class="project-meta" v-if="store.selectedProject">
        实例: {{ store.selectedProjectInstances.join(', ') }}
      </span>
    </div>
    <div class="header-right">
      <span class="health-dot" :class="store.healthStatus">
        {{ store.healthStatus === 'up' ? '●' : '○' }}
      </span>
      <span class="time">{{ now }}</span>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { useAppStore } from '@/stores/app'

const store = useAppStore()
const now = ref('')
let timer = null
onMounted(() => {
  store.fetchProjects()
  store.fetchInstancesHealth()
  store.checkHealth()
  updateTime()
  timer = setInterval(updateTime, 1000)
})
onUnmounted(() => clearInterval(timer))

function updateTime() { now.value = new Date().toLocaleTimeString('zh-CN', { hour12: false }) }
</script>

<style scoped>
.top-header { width: 100%; display: flex; justify-content: space-between; align-items: center; gap: 20px; }
.header-left { display: flex; align-items: center; gap: 10px; flex-shrink: 0; }
.app-title { font-size: 16px; font-weight: 600; white-space: nowrap; }
.version-tag { font-size: 11px; background: var(--accent-blue); color: #fff; padding: 1px 6px; border-radius: 4px; }
.header-center { display: flex; align-items: center; gap: 12px; flex: 1; justify-content: center; }
.project-switcher { width: 200px; }
.project-meta { font-size: 12px; color: var(--text-muted); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.header-right { display: flex; align-items: center; gap: 12px; flex-shrink: 0; }
.health-dot { font-size: 12px; }
.health-dot.up { color: var(--accent-green); }
.health-dot.down, .health-dot.unknown { color: var(--accent-red); }
.time { font-family: var(--font-mono); font-size: 13px; color: var(--text-muted); }
</style>
