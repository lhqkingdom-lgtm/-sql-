<template>
  <el-container class="app-layout">
    <el-aside :width="sidebarCollapsed ? '64px' : '220px'" class="app-sidebar">
      <SidebarNav :collapsed="sidebarCollapsed" @toggle="store.toggleSidebar()" />
    </el-aside>
    <el-container>
      <el-header class="app-header" height="56px">
        <TopHeader />
      </el-header>
      <el-main class="app-main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { computed, onMounted, onUnmounted } from 'vue'
import { useAppStore } from '@/stores/app'
import SidebarNav from './SidebarNav.vue'
import TopHeader from './TopHeader.vue'

const store = useAppStore()
const sidebarCollapsed = computed(() => store.sidebarCollapsed)

onMounted(() => store.startHealthCheck())
onUnmounted(() => store.stopHealthCheck())
</script>

<style scoped>
.app-layout { height: 100vh; overflow: hidden; }
.app-sidebar {
  transition: width 0.2s ease;
  overflow: hidden;
  background: var(--bg-secondary);
  border-right: 1px solid var(--border-color);
}
.app-header {
  background: var(--bg-secondary);
  border-bottom: 1px solid var(--border-color);
  display: flex; align-items: center;
  padding: 0 20px;
}
.app-main {
  background: var(--bg-primary);
  padding: 20px;
  overflow-y: auto;
  height: calc(100vh - 56px);
}
</style>
