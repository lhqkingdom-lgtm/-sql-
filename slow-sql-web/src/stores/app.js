import { defineStore } from 'pinia'
import { ref } from 'vue'
import request from '@/api/request'

export const useAppStore = defineStore('app', () => {
  const sidebarCollapsed = ref(false)
  const healthStatus = ref('unknown')
  const projects = ref([])
  const selectedProjectCode = ref('')
  const instancesHealth = ref([])

  const selectedProject = ref(null)
  const selectedProjectInstances = ref([])

  function toggleSidebar() { sidebarCollapsed.value = !sidebarCollapsed.value }

  async function checkHealth() {
    try { const d = await request.get('/health'); healthStatus.value = d?.status === 'UP' ? 'up' : 'down' } catch { healthStatus.value = 'down' }
  }

  async function fetchProjects() {
    try {
      const d = await request.get('/api/sql/projects')
      projects.value = d?.projects || []
      if (projects.value.length && !selectedProjectCode.value) {
        selectProject(projects.value[0].code)
      }
    } catch { projects.value = [] }
  }

  function selectProject(code) {
    selectedProjectCode.value = code
    const p = projects.value.find(p => p.code === code)
    selectedProject.value = p
    selectedProjectInstances.value = p?.instanceIds || []
  }

  async function fetchInstancesHealth() {
    try { instancesHealth.value = await request.get('/api/sql/instances/health') } catch { instancesHealth.value = [] }
  }

  return {
    sidebarCollapsed, healthStatus, projects, selectedProjectCode,
    selectedProject, selectedProjectInstances, instancesHealth,
    toggleSidebar, checkHealth, fetchProjects, selectProject, fetchInstancesHealth,
  }
})
