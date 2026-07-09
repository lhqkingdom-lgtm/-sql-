import { createRouter, createWebHashHistory } from 'vue-router'

const routes = [
  { path: '/', redirect: '/dashboard' },
  {
    path: '/dashboard',
    name: 'Dashboard',
    component: () => import('@/views/DashboardView.vue'),
    meta: { title: '仪表盘', icon: 'DataAnalysis' },
  },
  {
    path: '/diagnose',
    name: 'Diagnose',
    component: () => import('@/views/DiagnoseView.vue'),
    meta: { title: 'SQL诊断', icon: 'Monitor' },
  },
  {
    path: '/monitor',
    name: 'Monitor',
    component: () => import('@/views/MonitorView.vue'),
    meta: { title: '采集记录', icon: 'List' },
  },
  {
    path: '/rag',
    name: 'Rag',
    component: () => import('@/views/RagView.vue'),
    meta: { title: '知识库', icon: 'Reading' },
  },
  { path: '/:pathMatch(.*)*', redirect: '/dashboard' },
]

const router = createRouter({
  history: createWebHashHistory(),
  routes,
})

export default router
