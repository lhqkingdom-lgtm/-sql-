<template>
  <div class="project-selector">
    <el-select
      v-model="store.selectedProjectCode"
      placeholder="选择项目"
      @change="store.selectProject($event)"
      :disabled="store.isDiagnosing"
      style="width: 100%"
    >
      <el-option
        v-for="p in store.projects"
        :key="p.code"
        :label="p.name"
        :value="p.code"
      />
    </el-select>
    <el-select
      v-model="store.selectedInstanceId"
      placeholder="选择MySQL实例"
      @change="store.selectInstance($event)"
      :disabled="store.isDiagnosing || !store.selectedProjectCode"
      style="width: 100%; margin-top: 10px"
    >
      <el-option
        v-for="id in store.availableInstances"
        :key="id"
        :label="id"
        :value="id"
      />
    </el-select>
  </div>
</template>

<script setup>
import { onMounted } from 'vue'
import { useDiagnosisStore } from '@/stores/diagnosis'

const store = useDiagnosisStore()
onMounted(() => store.fetchProjects())
</script>
