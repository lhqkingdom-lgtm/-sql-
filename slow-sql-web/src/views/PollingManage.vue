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

        <div v-if="!p.instances || p.instances.length === 0" class="no-instance">该项目未配置实例</div>

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
                @change="(v) => onToggle(inst, v)"
              />
            </div>
          </div>

          <div class="instance-bottom">
            <div class="source-row">
              <span class="source-label">采集源:</span>
              <el-radio-group v-model="inst._source" size="small" @change="saveSources(inst)">
                <el-radio label="slow_log_table">Performance Schema</el-radio>
                <el-radio label="slow_log_file">慢日志文件</el-radio>
                <el-radio label="http_endpoint">HTTP端点</el-radio>
              </el-radio-group>
            </div>
            <div v-if="inst.lastCollectAt || inst.totalCollected > 0 || inst.nextPollSec >= 0" class="stats-row">
              <span v-if="inst.lastCollectAt" class="meta">上次采集: {{ inst.lastCollectAt?.substring(0,19) }}</span>
              <span v-if="inst.totalCollected > 0" class="meta">已采集 {{ inst.totalCollected }} 条</span>
              <span v-if="inst.nextPollSec != null && inst.nextPollSec >= 0" class="meta countdown">约 {{ inst.nextPollSec }} 秒后采集</span>
            </div>
            <div v-if="inst.lastError" class="meta error">{{ inst.lastError }}</div>
          </div>
        </div>
      </el-card>
    </div>

    <!-- 参数配置弹窗 -->
    <el-dialog v-model="configVisible" title="开启轮询参数" width="480px">
      <div v-if="mysqlSettings" class="config-body">
        <div class="mysql-info">
          <h4>MySQL 当前设置</h4>
          <div class="info-row">
            <span>long_query_time</span>
            <el-tag size="small" type="warning">{{ mysqlSettings.long_query_time || '?' }}s</el-tag>
          </div>
          <div class="info-row">
            <span>min_examined_row_limit</span>
            <el-tag size="small" type="warning">{{ mysqlSettings.min_examined_row_limit || '?' }}</el-tag>
          </div>
        </div>
        <el-divider />
        <h4>Gateway 采集参数</h4>
        <el-form label-width="140px" size="small">
          <el-form-item label="轮询间隔(秒)">
            <el-input-number v-model="pollingConfig.intervalSeconds" :min="10" :max="3600" :step="10" />
          </el-form-item>
          <el-form-item label="最低采集阈值(秒)">
            <el-input-number v-model="pollingConfig.minQueryTimeSec" :min="0" :max="60" :step="0.1" :precision="1" />
          </el-form-item>
        </el-form>
      </div>
      <div v-else class="config-body">
        <el-alert title="无法连接MySQL读取参数" type="warning" :closable="false" show-icon />
        <el-divider />
        <el-form label-width="140px" size="small">
          <el-form-item label="轮询间隔(秒)">
            <el-input-number v-model="pollingConfig.intervalSeconds" :min="10" :max="3600" :step="10" />
          </el-form-item>
          <el-form-item label="最低采集阈值(秒)">
            <el-input-number v-model="pollingConfig.minQueryTimeSec" :min="0" :max="60" :step="0.1" :precision="1" />
          </el-form-item>
        </el-form>
      </div>
      <template #footer>
        <el-button @click="cancelConfig">取消</el-button>
        <el-button type="primary" @click="confirmConfig">确认开启</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import http from '@/api/request'

const projects = ref([])
const loading = ref(false)
const configVisible = ref(false)
const mysqlSettings = ref(null)
const pollingConfig = ref({ intervalSeconds: 60, minQueryTimeSec: 0.5 })
const pendingInst = ref(null)

async function load() {
  loading.value = true
  try {
    const data = await http.get('/capture/status')
    projects.value = (data || []).map((p) => ({
      ...p,
      instances: (p.instances || []).map((inst) => ({
        ...inst,
        _enabled: inst.enabled !== false,
        _source: '',
      })),
    }))
    for (const p of projects.value) {
      for (const inst of p.instances || []) {
        try {
          const src = await http.get(`/capture/${inst.instanceId}/sources`)
          inst._source = src.source || ''
        } catch { inst._source = '' }
      }
    }
  } catch (e) { console.error('加载轮询状态失败:', e.message) }
  finally { loading.value = false }
}

async function onToggle(inst, enable) {
  if (!enable) {
    inst._enabled = false
    await http.post(`/capture/${inst.instanceId}/disable`).catch(() => {})
    return
  }
  // 开启前：PS / 文件源 → 弹出参数配置；HTTP → 直接开启
  inst._enabled = false  // 先回弹
  const src = inst._source
  if (src === 'http_endpoint') {
    // HTTP 端点直接开启，无需参数
    await http.post(`/capture/${inst.instanceId}/enable`)
    inst._enabled = true
    return
  }
  // PS / 文件源 → 弹窗配参数
  pendingInst.value = inst
  pollingConfig.value = { intervalSeconds: 60, minQueryTimeSec: 0.5 }
  try {
    mysqlSettings.value = await http.get(`/capture/${inst.instanceId}/mysql-settings`)
  } catch {
    mysqlSettings.value = null
  }
  configVisible.value = true
}

async function confirmConfig() {
  const inst = pendingInst.value
  try {
    await http.put(`/capture/${inst.instanceId}/polling-config`, pollingConfig.value)
    await http.post(`/capture/${inst.instanceId}/enable`)
    inst._enabled = true
  } catch (e) {
    console.error('开启失败:', e.message)
  }
  configVisible.value = false
  pendingInst.value = null
}

function cancelConfig() {
  configVisible.value = false
  pendingInst.value = null
}

async function saveSources(inst) {
  try {
    await http.put(`/capture/${inst.instanceId}/sources`, { source: inst._source || '' })
  } catch (e) { console.error('保存采集源失败:', e.message) }
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
.instance-block { padding: 12px 0; border-top: 1px solid var(--el-border-color-lighter, #eee); }
.instance-block:first-of-type { border-top: none; }
.instance-top { display: flex; align-items: center; justify-content: space-between; }
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
.config-body h4 { font-size: 14px; margin-bottom: 8px; }
.mysql-info { padding: 8px 12px; background: var(--el-color-warning-light-9, #fdf6ec); border-radius: 6px; }
.info-row { display: flex; justify-content: space-between; align-items: center; padding: 4px 0; font-size: 13px; }
</style>
