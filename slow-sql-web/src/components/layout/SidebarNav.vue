<template>
  <div class="sidebar-nav" :class="{ collapsed }">
    <div class="logo" v-if="!collapsed">
      <span class="logo-icon">&#9879;</span>
      <span class="logo-text">慢SQL诊断 V5</span>
    </div>
    <div class="logo logo-collapsed" v-else>&#9879;</div>

    <el-menu
      :default-active="activeRoute"
      :collapse="collapsed"
      :router="true"
      class="nav-menu"
    >
      <el-menu-item index="/dashboard">
        <el-icon><DataAnalysis /></el-icon>
        <span>仪表盘</span>
      </el-menu-item>
      <el-menu-item index="/diagnose">
        <el-icon><Monitor /></el-icon>
        <span>SQL 诊断</span>
      </el-menu-item>
      <el-menu-item index="/monitor">
        <el-icon><List /></el-icon>
        <span>采集记录</span>
      </el-menu-item>
      <el-menu-item index="/rag">
        <el-icon><Reading /></el-icon>
        <span>知识库</span>
      </el-menu-item>
    </el-menu>

    <div class="sidebar-footer" @click="$emit('toggle')">
      <el-icon :size="18"><Fold v-if="!collapsed" /><Expand v-else /></el-icon>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { DataAnalysis, Monitor, List, Reading, Fold, Expand } from '@element-plus/icons-vue'

defineProps({ collapsed: Boolean })
defineEmits(['toggle'])

const route = useRoute()
const activeRoute = computed(() => route.path)
</script>

<style scoped>
.sidebar-nav {
  display: flex; flex-direction: column; height: 100%;
  user-select: none;
}
.logo {
  height: 56px; display: flex; align-items: center;
  padding: 0 20px; border-bottom: 1px solid var(--border-color);
  gap: 10px; flex-shrink: 0;
}
.logo-icon { font-size: 22px; }
.logo-text { font-size: 15px; font-weight: 600; white-space: nowrap; }
.logo-collapsed { justify-content: center; padding: 0; font-size: 22px; }
.nav-menu {
  flex: 1; border-right: none !important;
  background: transparent !important;
}
.nav-menu .el-menu-item {
  color: var(--text-secondary) !important;
}
.nav-menu .el-menu-item:hover {
  background: var(--bg-hover) !important;
  color: var(--text-primary) !important;
}
.nav-menu .el-menu-item.is-active {
  color: var(--accent-blue) !important;
  background: rgba(51, 154, 240, 0.1) !important;
}
.sidebar-footer {
  height: 40px; display: flex; align-items: center; justify-content: center;
  border-top: 1px solid var(--border-color); cursor: pointer;
  color: var(--text-muted); flex-shrink: 0;
}
.sidebar-footer:hover { color: var(--text-primary); }
</style>
