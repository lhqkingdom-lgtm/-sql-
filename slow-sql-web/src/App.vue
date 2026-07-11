<template>
  <div class="app-shell" :class="{ dark: isDark }">
    <!-- Sidebar -->
    <aside class="sidebar">
      <div class="sidebar-brand">
        <span class="brand-icon">◉</span>
        <span class="brand-text">slow-sql</span>
      </div>
      <nav class="sidebar-nav">
        <router-link
          v-for="route in navRoutes"
          :key="route.path"
          :to="route.path"
          class="nav-item"
          active-class="nav-active"
        >
          <el-icon v-if="route.meta.icon"><component :is="route.meta.icon" /></el-icon>
          <span class="nav-label">{{ route.meta.title }}</span>
        </router-link>
      </nav>
    </aside>

    <!-- Main -->
    <div class="main-area">
      <!-- Top bar -->
      <header class="topbar">
        <div class="topbar-left">
          <el-select
            v-model="projectCode"
            placeholder="选择项目"
            size="large"
            @change="switchProject"
            style="width: 240px"
          >
            <el-option
              v-for="p in app.projects"
              :key="p.code"
              :label="p.name"
              :value="p.code"
            />
          </el-select>
          <el-select
            v-model="selectedDb"
            placeholder="全部库"
            size="large"
            clearable
            @change="switchDb"
            style="width: 160px"
          >
            <el-option label="全部库" value="" />
            <el-option
              v-for="db in app.databases"
              :key="db"
              :label="db"
              :value="db"
            />
          </el-select>
          <span v-if="onlineCount > 0" class="instance-badge">
            <span class="dot online"></span>
            {{ onlineCount }} 实例在线
          </span>
        </div>
        <div class="topbar-right">
          <el-button circle @click="isDark = !isDark">
            {{ isDark ? '☀' : '☾' }}
          </el-button>
        </div>
      </header>

      <!-- Page -->
      <main class="page-content">
        <router-view v-slot="{ Component }">
          <keep-alive>
            <component :is="Component" />
          </keep-alive>
        </router-view>
      </main>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAppStore } from '@/stores/app'
import { Edit, Switch, Document, Clock, Collection } from '@element-plus/icons-vue'

const app = useAppStore()
const router = useRouter()
const projectCode = ref('')
const selectedDb = ref('')
const isDark = ref(true)

const navRoutes = router.options.routes.filter((r) => r.meta?.title)

const onlineCount = computed(
  () => app.instances.filter((i) => i.reachable).length
)

function switchProject(code) {
  selectedDb.value = ''
  app.setProject(code)
}

function switchDb(db) {
  app.currentDatabase = db || ''
}

onMounted(() => {
  app.fetchProjects().then(() => {
    projectCode.value = app.currentProject
  })
})
</script>

<style>
/* ===== Reset ===== */
* { margin: 0; padding: 0; box-sizing: border-box; }
html, body, #app { height: 100%; }
body { font-family: 'Inter', 'Segoe UI', sans-serif; }

/* ===== Shell ===== */
.app-shell {
  display: flex; height: 100%;
  --sidebar-w: 200px; --topbar-h: 56px;
  background: #f5f7fa; color: #303133;
}
.app-shell.dark {
  background: #141414; color: #e0e0e0;
}

/* ===== Sidebar ===== */
.sidebar {
  width: var(--sidebar-w); min-width: var(--sidebar-w);
  display: flex; flex-direction: column;
  background: #1a1a2e; color: #ccc; overflow-y: auto;
}
.dark .sidebar { background: #0d0d1a; }
.sidebar-brand {
  padding: 18px 20px; font-size: 18px; font-weight: 700;
  display: flex; align-items: center; gap: 8px;
}
.brand-icon { color: #409eff; font-size: 22px; }
.brand-text { letter-spacing: 1px; }

.sidebar-nav { flex: 1; padding: 8px 0; }
.nav-item {
  display: flex; align-items: center; gap: 10px;
  padding: 12px 20px; text-decoration: none;
  color: #999; font-size: 14px; transition: all .2s;
  border-left: 3px solid transparent;
}
.nav-item:hover { color: #eee; background: rgba(255,255,255,.05); }
.nav-active {
  color: #409eff; background: rgba(64,158,255,.1);
  border-left-color: #409eff;
}
.nav-label { white-space: nowrap; }

/* ===== Main ===== */
.main-area {
  flex: 1; display: flex; flex-direction: column; overflow: hidden;
}

/* ===== Top bar ===== */
.topbar {
  height: var(--topbar-h); min-height: var(--topbar-h);
  display: flex; align-items: center; justify-content: space-between;
  padding: 0 20px;
  background: #fff; border-bottom: 1px solid #ebeef5;
}
.dark .topbar { background: #1d1d1d; border-bottom-color: #333; }
.topbar-left { display: flex; align-items: center; gap: 12px; }
.instance-badge { font-size: 13px; color: #67c23a; }
.dot { display: inline-block; width: 8px; height: 8px; border-radius: 50%; margin-right: 4px; }
.dot.online { background: #67c23a; }

/* ===== Page ===== */
.page-content { flex: 1; padding: 20px; overflow-y: auto; }

/* ===== Placeholder ===== */
.page-placeholder {
  display: flex; flex-direction: column; align-items: center;
  justify-content: center; height: 100%; color: #999;
}
</style>
