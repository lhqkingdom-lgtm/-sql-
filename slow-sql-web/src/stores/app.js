import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import http from '@/api/request'

export const useAppStore = defineStore('app', () => {
  const projects = ref([])
  const currentProject = ref('')
  const instances = ref([])

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

  function setProject(code) {
    currentProject.value = code
    fetchInstanceHealth()
  }

  return {
    projects, currentProject, instances, currentInstances,
    fetchProjects, fetchInstanceHealth, setProject,
  }
})
