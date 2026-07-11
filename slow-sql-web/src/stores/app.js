import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import http from '@/api/request'

export const useAppStore = defineStore('app', () => {
  const projects = ref([])
  const currentProject = ref('')
  const instances = ref([])

  const databases = ref([])
  const currentDatabase = ref('')

  const currentInstances = computed(() => {
    const p = projects.value.find((p) => p.code === currentProject.value)
    return p?.instanceIds || []
  })

  async function fetchProjects() {
    try {
      const data = await http.get('/sql/projects')
      projects.value = data.projects || []
      if (!currentProject.value && projects.value.length > 0) {
        currentProject.value = projects.value[0].code
      }
      await fetchInstanceHealth()
    } catch (e) {
      console.warn('加载项目列表失败:', e.message)
    }
  }

  async function fetchInstanceHealth() {
    if (!currentProject.value) return
    try {
      instances.value = await http.get('/instances/health', {
        params: { projectCode: currentProject.value },
      })
    } catch (e) {
      instances.value = []
    }
  }

  async function fetchDatabases() {
    if (!currentProject.value) return
    try {
      const data = await http.get('/monitor/records', {
        params: { projectCode: currentProject.value, size: 999 }
      })
      const dbSet = new Set()
      ;(data.records || []).forEach(r => { if (r.databaseName) dbSet.add(r.databaseName) })
      databases.value = [...dbSet].sort()
      if (!databases.value.includes(currentDatabase.value)) {
        currentDatabase.value = ''
      }
    } catch { databases.value = [] }
  }

  function setProject(code) {
    currentProject.value = code
    currentDatabase.value = ''
    fetchInstanceHealth()
    fetchDatabases()
  }

  return {
    projects, currentProject, instances, currentInstances,
    databases, currentDatabase,
    fetchProjects, fetchDatabases, fetchInstanceHealth, setProject,
  }
})
