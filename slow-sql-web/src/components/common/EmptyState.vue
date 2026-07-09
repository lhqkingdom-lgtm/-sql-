<template>
  <div class="empty-state">
    <div class="empty-icon">&#8853;</div>
    <h3 class="empty-title">{{ title }}</h3>
    <p class="empty-desc" v-if="description">{{ description }}</p>
    <slot name="action" />
  </div>
</template>

<script setup>
defineProps({
  title: { type: String, default: '暂无数据' },
  description: { type: String, default: '' },
})
</script>

<style scoped>
.empty-state {
  display: flex; flex-direction: column; align-items: center;
  justify-content: center; padding: 60px 20px;
}
.empty-icon { font-size: 48px; color: var(--text-muted); margin-bottom: 16px; }
.empty-title { font-size: 16px; color: var(--text-secondary); font-weight: 500; margin-bottom: 8px; }
.empty-desc { font-size: 13px; color: var(--text-muted); }
</style>
