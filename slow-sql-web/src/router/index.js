import { createRouter, createWebHashHistory } from 'vue-router'

export default createRouter({
  history: createWebHashHistory(),
  routes: [
    {
      path: '/',
      redirect: '/diagnose',
    },
    {
      path: '/diagnose',
      name: 'Diagnose',
      component: () => import('@/views/ManualDiagnose.vue'),
      meta: { title: '手动诊断', icon: 'Edit' },
    },
    {
      path: '/polling',
      name: 'Polling',
      component: () => import('@/views/PollingManage.vue'),
      meta: { title: '轮询管理', icon: 'Switch' },
    },
    {
      path: '/records',
      name: 'Records',
      component: () => import('@/views/CapturedRecords.vue'),
      meta: { title: '采集记录', icon: 'Document' },
    },
    {
      path: '/history',
      name: 'History',
      component: () => import('@/views/DiagnosisHistory.vue'),
      meta: { title: '诊断历史', icon: 'Clock' },
    },
    {
      path: '/rag',
      name: 'Rag',
      component: () => import('@/views/KnowledgeBase.vue'),
      meta: { title: '知识库', icon: 'Collection' },
    },
  ],
})
