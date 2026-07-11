<template>
  <div class="page">
    <div class="page-header">
      <h3>轮询管理</h3>
      <el-button size="small" @click="load" :icon="Refresh">刷新</el-button>
    </div>

    <div v-loading="loading" class="project-list">
      <el-empty v-if="!loading && projects.length === 0" description="暂无项目配置" />

      <el-card v-for="p in projects" :key="p.projectCode" class="project-card" shadow="hover">
        <div class="card-header">
          <span class="project-name">{{ p.projectName }}</span>
          <span class="project-code">{{ p.projectCode }}</span>
        </div>

        <div v-if="!p.instances || p.instances.length === 0" class="no-instance">
          该项目未配置实例
        </div>

        <div v-for="inst in p.instances" :key="inst.instanceId" class="instance-block">
          <div class="instance-top">
            <div class="instance-info">
              <span class="dot" :class="inst.reachable ? 'online' : 'offline'"></span>
              <span class="instance-id">{{ inst.instanceId }}</span>
              <el-tag size="small" :type="inst.reachable ? 'success' : 'danger'">
                {{ inst.reachable ? '在线' : '离线' }}
              </el-tag>
            </div>

            <div class="instance-switch">
              <el-switch
                v-model="inst._enabled"
                active-text="开启"
                inactive-text="关闭"
                @change="(v) => toggleInstance(inst, v)"
              />
            </div>
          </div>

          <div class="instance-bottom">
            <div class="source-row">
              <span class="source-label">采集源:</span>
              <el-checkbox
                v-model="inst._sources"
                label="slow_log_table"
                size="small"
                @change="saveSources(inst)"
              >慢日志表</el-checkbox>
              <el-checkbox
                v-model="inst._sources"
                label="slow_log_file"
                size="small"
                @change="saveSources(inst)"
              >慢日志文件</el-checkbox>
              <el-checkbox
                v-model="inst._sources"
                label="http_endpoint"
                size="small"
                @change="saveSources(inst)"
              >HTTP端点</el-checkbox>
            </div>

            <div v-if="inst.lastCollectAt || inst.totalCollected > 0" class="stats-row">
              <span v-if="inst.lastCollectAt" class="meta">
                上次采集: {{ inst.lastCollectAt?.substring(0,19) }}
              </span>
              <span v-if="inst.totalCollected > 0" class="meta">
                已采集 {{ inst.totalCollected }} 条
              </span>
            </div>
            <div v-if="inst.lastError" class="meta error">{{ inst.lastError }}</div>
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
      instances: (p.instances || []).map((inst) => ({
        ...inst,
        _enabled: inst.enabled !== false,
        _sources: [],
      })),
    }))
    // 加载每个实例的采集源配置
    for (const p of projects.value) {
      for (const inst of p.instances || []) {
        try {
          const src = await http.get(`/capture/${inst.instanceId}/sources`)
          const raw = src.sources || []
          inst._sources = raw.includes('all')
            ? ['slow_log_table', 'slow_log_file', 'http_endpoint']
            : raw
        } catch {
          inst._sources = ['slow_log_table']
        }
      }
    }
  } catch (e) {
    console.error('加载轮询状态失败:', e.message)
  } finally {
    loading.value = false
  }
}

async function toggleInstance(inst, enable) {
  try {
    if (enable) {
      await http.post(`/capture/${inst.instanceId}/enable`)
    } else {
      await http.post(`/capture/${inst.instanceId}/disable`)
    }
  } catch (e) {
    inst._enabled = !enable
    console.error('切换失败:', e.message)
  }
}

async function saveSources(inst) {
  const allThree = ['slow_log_table', 'slow_log_file', 'http_endpoint']
  const send = (inst._sources || []).length >= 3 ? ['all'] : (inst._sources || [])
  try {
    await http.put(`/capture/${inst.instanceId}/sources`, { sources: send })
  } catch (e) {
    console.error('保存采集源失败:', e.message)
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

.card-header { margin-bottom: 12px; }
.project-name { font-size: 16px; font-weight: 600; }
.project-code { font-size: 12px; color: #999; margin-left: 10px; }

.no-instance { color: #999; font-size: 13px; padding: 8px 0; }

.instance-block {
  padding: 12px 0;
  border-top: 1px solid var(--el-border-color-lighter, #eee);
}
.instance-block:first-of-type { border-top: none; }

.instance-top {
  display: flex; align-items: center; justify-content: space-between;
}
.instance-info { display: flex; align-items: center; gap: 8px; }
.instance-id { font-family: monospace; font-size: 14px; }

.instance-bottom { margin-top: 8px; }
.source-row { display: flex; align-items: center; gap: 10px; }
.source-label { font-size: 12px; color: #999; margin-right: 4px; }

.stats-row { margin-top: 6px; display: flex; gap: 12px; }
.meta { font-size: 12px; color: #999; }
.meta.error { color: #f56c6c; display: block; margin-top: 4px; }

.dot { width: 8px; height: 8px; border-radius: 50%; display: inline-block; }
.dot.online { background: #67c23a; }
.dot.offline { background: #c0c4cc; }
</style>
