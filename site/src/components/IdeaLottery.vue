<script setup>
import { ref } from 'vue'
import { ideaThemes, randomItem } from '../experiments.js'
import { copyText } from '../state.js'
const theme = ref('观察'); const prompt = ref(''); const count = ref(0)
function choose(value) { theme.value = value; prompt.value = ''; count.value = 0 }
function draw() { prompt.value = randomItem(ideaThemes[theme.value], prompt.value); count.value++ }
</script>
<template>
  <section class="experiment-panel ideas-lab" aria-labelledby="ideas-title"><div class="experiment-copy"><span class="eyebrow">04 / IDEA LOTTERY</span><h2 id="ideas-title">抽一个创作起点</h2><p>选个主题，抽一条小练习。</p><div class="filter-chips" role="group" aria-label="灵感主题"><button v-for="name in Object.keys(ideaThemes)" :key="name" :class="{ active: theme === name }" :aria-pressed="theme === name" @click="choose(name)">{{ name }}</button></div><div class="experiment-actions"><button class="button primary" @click="draw">{{ prompt ? '再抽一张' : '抽一张灵感签' }}</button><button class="button secondary" :disabled="!prompt" @click="copyText(prompt)">复制签文</button></div><p class="tool-note">共 18 条练习，连续抽取不重复上一张。不保存历史。</p></div>
    <div class="idea-slip" aria-live="polite" aria-atomic="true"><span class="eyebrow">{{ theme }} / A SMALL BEGINNING</span><span class="slip-symbol" aria-hidden="true">✳</span><p>{{ prompt || '今天，从哪件小事开始？' }}</p><small>{{ prompt ? `第 ${String(count).padStart(2, '0')} 次相遇` : '今天，会遇见什么？' }}</small><span class="slip-footer">栖物里 · KEEP YOUR CURIOSITY</span></div>
  </section>
</template>
