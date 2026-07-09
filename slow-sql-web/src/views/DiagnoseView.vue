<template>
  <div class="diagnose-view">
    <div class="diagnose-left">
      <el-card shadow="never">
        <template #header><span>诊断参数</span></template>
        <ProjectSelector />
        <div style="margin-top: 16px">
          <SqlInput />
        </div>
      </el-card>
    </div>
    <div class="diagnose-right">
      <DiagnosisProgress />
      <DiagnosisReport />
    </div>
  </div>
</template>

<script setup>
import ProjectSelector from '@/components/diagnose/ProjectSelector.vue'
import SqlInput from '@/components/diagnose/SqlInput.vue'
import DiagnosisProgress from '@/components/diagnose/DiagnosisProgress.vue'
import DiagnosisReport from '@/components/diagnose/DiagnosisReport.vue'
</script>

<style scoped>
.diagnose-view {
  display: grid; grid-template-columns: 420px 1fr; gap: 20px;
  height: calc(100vh - 100px);
}
@media (max-width: 900px) {
  .diagnose-view { grid-template-columns: 1fr; }
}
.diagnose-left { overflow-y: auto; }
.diagnose-right { overflow-y: auto; }
</style>
