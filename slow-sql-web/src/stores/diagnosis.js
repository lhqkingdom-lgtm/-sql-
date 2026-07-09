import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { getProjects, analyzeSql } from '@/api/sql'
import { diagnoseWithFallback } from '@/api/diagnosis'

export const useDiagnosisStore = defineStore('diagnosis', () => {
  const projects = ref([])
  const selectedProjectCode = ref('')
  const selectedInstanceId = ref('')
  const sqlText = ref('')
  const taskId = ref(null)
  const status = ref('idle')
  const report = ref(null)
  const error = ref(null)
  const connectionMode = ref(null)

  let activeDiagnosis = null

  const availableInstances = computed(() => {
    if (!selectedProjectCode.value) return []
    const p = projects.value.find(p => p.code === selectedProjectCode.value)
    return p?.instanceIds || []
  })

  const selectedProjectName = computed(() => {
    const p = projects.value.find(p => p.code === selectedProjectCode.value)
    return p?.name || ''
  })

  const isDiagnosing = computed(() =>
    status.value === 'pending' || status.value === 'streaming'
  )

  async function fetchProjects() {
    try {
      const data = await getProjects()
      projects.value = data?.projects || []
      if (projects.value.length > 0 && !selectedProjectCode.value) {
        selectedProjectCode.value = projects.value[0].code
        if (availableInstances.value.length > 0) {
          selectedInstanceId.value = availableInstances.value[0]
        }
      }
    } catch {
      projects.value = []
    }
  }

  function selectProject(code) {
    selectedProjectCode.value = code
    selectedInstanceId.value = ''
  }

  function selectInstance(id) {
    selectedInstanceId.value = id
  }

  function setSql(text) {
    sqlText.value = text
  }

  async function submitDiagnosis() {
    if (!selectedInstanceId.value || !sqlText.value.trim()) return

    status.value = 'pending'
    error.value = null
    report.value = null
    connectionMode.value = null

    try {
      const data = await analyzeSql({
        instanceId: selectedInstanceId.value,
        sql: sqlText.value.trim(),
        projectCode: selectedProjectCode.value,
      })
      taskId.value = data.taskId
      if (data?.status === 'pending' || data?.status === 'running') {
        startResultListener(data.taskId)
      } else if (data?.errorCode) {
        status.value = 'failed'
        error.value = `[${data.errorCode}] ${data.error || '未知错误'}`
      }
    } catch (err) {
      status.value = 'failed'
      error.value = err?.response?.data?.error || '提交诊断请求失败'
    }
  }

  function startResultListener(tid) {
    status.value = 'streaming'
    activeDiagnosis = diagnoseWithFallback(tid, (newStatus, data) => {
      if (newStatus === 'completed') {
        status.value = 'completed'
        report.value = data.report || ''
        connectionMode.value = newStatus
      } else if (newStatus === 'failed') {
        status.value = 'failed'
        error.value = data.error || '诊断失败'
        connectionMode.value = newStatus
      } else if (newStatus === 'polling') {
        connectionMode.value = 'polling'
      }
    })
  }

  function resetDiagnosis() {
    if (activeDiagnosis) {
      activeDiagnosis.cancel()
      activeDiagnosis = null
    }
    status.value = 'idle'
    taskId.value = null
    report.value = null
    error.value = null
    connectionMode.value = null
  }

  return {
    projects, selectedProjectCode, selectedInstanceId, sqlText,
    taskId, status, report, error, connectionMode,
    availableInstances, selectedProjectName, isDiagnosing,
    fetchProjects, selectProject, selectInstance, setSql,
    submitDiagnosis, resetDiagnosis,
  }
})
