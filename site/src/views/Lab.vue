<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ref } from 'vue'
import { palettes } from '../content.js'
import { copyText, notify, focusMinutes, remaining, running, focusDone, resetFocus, toggleFocus, clockText } from '../state.js'
import Icon from '../components/Icon.vue'
import { labTools } from '../experiments.js'
import PixelGarden from '../components/PixelGarden.vue'
import IdeaLottery from '../components/IdeaLottery.vue'
import DailyChoice from '../components/DailyChoice.vue'
import BreathingSpace from '../components/BreathingSpace.vue'
const route = useRoute(); const router = useRouter(); const selected = ref(0)
const tool = computed(() => labTools.some(t => t.id === route.query.tool) ? route.query.tool : 'palette')
const palette = computed(() => palettes[selected.value])
function choose(value) { router.replace({ path: '/lab', query: { tool: value } }) }
function download() {
  const p = palette.value
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="1000" height="680" viewBox="0 0 1000 680"><rect width="1000" height="680" fill="#f8f6ef"/><text x="60" y="80" font-family="sans-serif" font-size="30" fill="#214c3c">${p.name} / QIWULI</text>${p.colors.map((color,i) => `<rect x="${60+i*176}" y="130" width="176" height="390" fill="${color}"/><text x="${72+i*176}" y="555" font-family="monospace" font-size="22" fill="#214c3c">${color}</text>`).join('')}<text x="60" y="630" font-family="sans-serif" font-size="18" fill="#667366">A little color for your next idea. · qiwuli.com</text></svg>`
  const url = URL.createObjectURL(new Blob([svg], { type: 'image/svg+xml;charset=utf-8' }))
  const a = document.createElement('a'); a.href = url; a.download = `qiwuli-palette-${selected.value+1}.svg`; a.click(); setTimeout(() => URL.revokeObjectURL(url), 30000); notify('已生成 SVG 色卡，请查看浏览器下载')
}
</script>
<template><div class="container page-space"><header class="page-heading"><div class="eyebrow">A LITTLE ROOM TO PLAY</div><h1>好奇心实验室<span class="serif-dot">.</span></h1><p>选一个小工具，即刻开始。</p></header><div class="lab-tabs" role="group" aria-label="选择实验"><button v-for="entry in labTools" :key="entry.id" :class="{ active: tool === entry.id }" :aria-pressed="tool === entry.id" @click="choose(entry.id)">{{ entry.name }}</button></div>
<PixelGarden v-show="tool === 'garden'" />
<IdeaLottery v-show="tool === 'ideas'" />
<DailyChoice v-show="tool === 'choice'" />
<BreathingSpace v-show="tool === 'breath'" :active="tool === 'breath'" />
<section v-if="tool === 'palette'" class="palette-lab"><div class="tool-heading"><div><span class="eyebrow">01 / COLOR NOTES</span><h2>自然配色</h2></div><span class="subtle">点击色块，复制 HEX 色值</span></div><div class="palette-options"><button v-for="(p,i) in palettes" :key="p.name" :aria-pressed="selected === i" :class="{ active: selected === i }" @click="selected = i"><span class="color-dot" :style="{ background: p.colors[1] }"></span>{{ p.name }}</button></div><div class="color-strips"><button v-for="(color,i) in palette.colors" :key="i" :style="{ backgroundColor: color, color: i < 2 ? '#ffffff' : '#182e24' }" :aria-label="`复制色值 ${color}`" @click="copyText(color)"><span>0{{ i+1 }}</span><strong>{{ color }}</strong><Icon name="copy" /></button></div><div class="palette-footer"><div><h3>{{ palette.name }}</h3><p>{{ palette.mood }}</p></div><div class="tool-actions"><button class="button secondary" @click="copyText(palette.colors.join(', '))"><Icon name="copy" />复制整组</button><button class="button primary" @click="download"><Icon name="download" />下载色卡</button></div></div><p class="tool-note">下载格式为 SVG，可缩放使用。</p></section>
<section v-else-if="tool === 'focus'" class="focus-lab"><div class="focus-intro"><span class="eyebrow">02 / QUIET FOCUS</span><h2>只做眼前这一件事。</h2><p>选一段时间，开始专注。</p><div class="filter-chips" aria-label="专注时长"><button v-for="minutes in [5,15,25]" :key="minutes" :class="{ active: focusMinutes === minutes }" :aria-pressed="focusMinutes === minutes" @click="resetFocus(minutes)">{{ minutes }} 分钟</button></div><p class="tool-note">站内切页继续计时，切换时长或刷新后重置。无提示音和通知。</p></div><div class="focus-clock"><div class="clock-face"><svg viewBox="0 0 280 280" aria-hidden="true"><circle class="clock-track" cx="140" cy="140" r="126"/><circle class="clock-progress" cx="140" cy="140" r="126" pathLength="100" :stroke-dasharray="`${remaining / (focusMinutes * 60) * 100} 100`"/></svg><div><span class="eyebrow">{{ focusDone ? '做得不错，休息一下' : running ? '把时间留给当下' : '留一刻给自己' }}</span><strong role="timer" aria-label="剩余专注时间">{{ clockText }}</strong><small>{{ running ? '专注中' : focusDone ? '已完成' : remaining === focusMinutes * 60 ? '准备好了就开始' : '已暂停' }}</small></div></div><div class="tool-actions"><button class="button primary" @click="toggleFocus"><Icon :name="running ? 'pause' : 'play'" />{{ running ? '暂停' : remaining === focusMinutes * 60 ? '开始专注' : focusDone ? '再来一段' : '继续' }}</button><button class="icon-button" aria-label="重新计时" @click="resetFocus()"><Icon name="reset" /></button></div></div></section></div></template>
