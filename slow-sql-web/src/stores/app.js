import { defineStore } from 'pinia'
import { ref } from 'vue'
import { checkHealth } from '@/api/health'

let healthTimer = null

export const useAppStore = defineStore('app', () => {
  const sidebarCollapsed = ref(false)
  const healthStatus = ref('unknown')
  const globalLoading = ref(false)

  function toggleSidebar() {
    sidebarCollapsed.value = !sidebarCollapsed.value
  }

  async function refreshHealth() {
    try {
      const data = await checkHealth()
      healthStatus.value = (data?.status === 'UP' || data?.status === 'ok') ? 'up' : 'down'
    } catch {
      healthStatus.value = 'down'
    }
  }

  function startHealthCheck(intervalMs = 30000) {
    refreshHealth()
    if (healthTimer) clearInterval(healthTimer)
    healthTimer = setInterval(refreshHealth, intervalMs)
  }

  function stopHealthCheck() {
    if (healthTimer) { clearInterval(healthTimer); healthTimer = null }
  }

  return {
    sidebarCollapsed, healthStatus, globalLoading,
    toggleSidebar, refreshHealth, startHealthCheck, stopHealthCheck,
  }
})
