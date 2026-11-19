<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { projects } from '../content.js'
import ProjectArt from '../components/ProjectArt.vue'
import Icon from '../components/Icon.vue'
const route = useRoute(); const project = computed(() => projects.find(p => p.id === route.params.id))
</script>
<template><div v-if="project" class="container page-space project-detail"><RouterLink to="/projects" class="back-link">← 返回项目集</RouterLink><div class="project-detail-hero"><div><div class="eyebrow">{{ project.english }} / {{ project.year }}</div><h1>{{ project.name }}</h1><p class="detail-lead">{{ project.summary }}</p><div class="tags"><span v-for="tag in project.tags" :key="tag">{{ tag }}</span></div><RouterLink :to="project.link" class="button primary">{{ project.action }}<Icon name="arrow" /></RouterLink></div><ProjectArt :type="project.art" /></div><div class="detail-columns"><div class="prose"><h2>项目介绍</h2><p>{{ project.description }}</p><h2>功能</h2><ul><li v-for="feature in project.features" :key="feature">{{ feature }}</li></ul></div><aside class="project-facts"><span class="status-dot">{{ project.status }}</span><h3>使用之前</h3><p>{{ project.note }}</p><RouterLink to="/contact" class="text-link">交流反馈 <Icon name="diagonal" /></RouterLink></aside></div></div></template>
