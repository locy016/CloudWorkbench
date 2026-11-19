<script setup>
import { computed, ref } from 'vue'
import { projects, filterItems } from '../content.js'
import ProjectCard from '../components/ProjectCard.vue'
import Icon from '../components/Icon.vue'
const category = ref('全部'); const query = ref('')
const filtered = computed(() => filterItems(projects, query.value, category.value))
</script>
<template><div class="container page-space"><header class="page-heading"><div class="eyebrow">THE THINGS WE MAKE</div><h1>项目集<span class="serif-dot">.</span></h1><p>实用工具与交互实验。</p></header><div class="filter-bar"><div class="filter-chips" role="group" aria-label="项目分类"><button v-for="tag in ['全部','应用','工具','实验']" :key="tag" :class="{ active: category === tag }" :aria-pressed="category === tag" @click="category = tag">{{ tag }}</button></div><label class="search-box"><Icon name="search" /><input v-model="query" type="search" placeholder="搜索项目或关键词" aria-label="搜索项目"></label></div><p class="result-count" aria-live="polite">{{ filtered.length }} 个项目</p><TransitionGroup name="cards" tag="div" class="project-grid"><ProjectCard v-for="project in filtered" :key="project.id" :project="project" /></TransitionGroup><div v-if="!filtered.length" class="empty-state"><Icon name="search" /><h2>暂时没有找到这个项目</h2><p>换个关键词，或者看看全部项目。</p><button class="button secondary" @click="query = ''; category = '全部'">查看全部</button></div><aside class="quiet-note"><Icon name="leaf" /><p>「可以体验」的项目可直接使用；云端工作台仍在个人环境中迭代。</p></aside></div></template>
