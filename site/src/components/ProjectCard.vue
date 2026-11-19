<script setup>
import ProjectArt from './ProjectArt.vue'
import Icon from './Icon.vue'
defineProps({ project: Object })
</script>
<template><RouterLink :to="`/projects/${project.id}`" class="project-card"><ProjectArt :type="project.art" /><div class="project-card-body"><div class="card-topline"><span>{{ project.category }} / {{ project.english }}</span><span class="status-dot">{{ project.status }}</span></div><h3>{{ project.name }}<Icon name="diagonal" /></h3><p>{{ project.summary }}</p><div class="tags"><span v-for="tag in project.tags" :key="tag">{{ tag }}</span></div></div></RouterLink></template>
