<template>
  <div class="page">
    <div class="page-header">
      <h3>轮询管理</h3>
      <el-button size="small" @click="load" :icon="Refresh">刷新</el-button>
    </div>

    <div v-loading="loading" class="project-list">
      <el-empty v-if="!loading && projects.length === 0" description="暂无项目配置" />

      <el-card v-for="p in projects" :key="p.projectCode" class="project-card" shadow="hover">
        <div class="card-row">
          <div class="card-left">
            <span class="project-name">{{ p.projectName }}</span>
            <span class="project-code">{{ p.projectCode }}</span>
          </div>
          <div class="card-center">
            <div v-for="inst in p.instances" :key="inst.instanceId" class="instance-row">
              <span class="dot" :class="inst.reachable ? 'online' : 'offline'"></span>
              <span class="instance-id">{{ inst.instanceId }}</span>
              <el-tag size="small" :type="inst.reachable ? 'success' : 'danger'">
                {{ inst.reachable ? '在线' : '离线' }}
              </el-tag>
              <span v-if="inst.lastCollectAt" class="meta">
                上次采集: {{ inst.lastCollectAt?.substring(0,19) }}
              </span>
              <span v-if="inst.totalCollected > 0" class="meta">
                已采集: {{ inst.totalCollected }} 条
              </span>
              <span v-if="inst.lastError" class="meta error">{{ inst.lastError }}</span>
            </div>
          </div>
          <div class="card-right">
            <el-switch
              v-model="p._enabled"
              active-text="开启"
              inactive-text="关闭"
              @change="(val) => togglePolling(p, val)"
            />
          </div>
        </div>
      </el-card>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import http from '@/api/request'

const projects = ref([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const data = await http.get('/capture/status')
    projects.value = (data || []).map((p) => ({
      ...p,
      _enabled: p.instances?.some((i) => i.enabled) || false,
    }))
  } catch (e) {
    console.error('加载轮询状态失败:', e.message)
  } finally {
    loading.value = false
  }
}

async function togglePolling(project, enable) {
  const instances = project.instances || []
  for (const inst of instances) {
    try {
      if (enable) {
        await http.post(`/capture/${inst.instanceId}/enable`)
      } else {
        await http.post(`/capture/${inst.instanceId}/disable`)
      }
    } catch (e) {
      console.error('切换轮询失败:', e.message)
      project._enabled = !enable
      return
    }
  }
}

onMounted(load)
</script>

<style scoped>
.page { height: 100%; display: flex; flex-direction: column; }
.page-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 16px; }
.page-header h3 { font-size: 18px; font-weight: 600; }
.project-list { display: flex; flex-direction: column; gap: 12px; overflow-y: auto; }

.project-card { border-radius: 8px; }
.card-row { display: flex; align-items: center; gap: 24px; }
.card-left { min-width: 160px; }
.project-name { font-size: 16px; font-weight: 600; display: block; }
.project-code { font-size: 12px; color: #999; }

.card-center { flex: 1; }
.instance-row { display: flex; align-items: center; gap: 8px; padding: 3px 0; }
.instance-id { font-family: monospace; font-size: 13px; }
.meta { font-size: 12px; color: #999; }
.meta.error { color: #f56c6c; }

.dot { width: 8px; height: 8px; border-radius: 50%; display: inline-block; }
.dot.online { background: #67c23a; }
.dot.offline { background: #c0c4cc; }
</style>
