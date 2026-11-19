<script setup>
import { ref, onBeforeUnmount } from 'vue'
import { flowerPixels, gridSize, pixelColors, validPixels, pixelsSvg } from '../experiments.js'
import { notify } from '../state.js'
const pixels = ref(flowerPixels())
try { const saved = JSON.parse(localStorage.getItem('qiwuli-pixel-garden')); if (validPixels(saved)) pixels.value = saved } catch {}
const selected = ref(pixelColors[1]); const history = ref([]); const cursor = ref(0)
const grid = ref(null)
let painting = false
let stroke = null
function remember(previous) { history.value = [...history.value.slice(-29), previous] }
function paint(index) {
  if (pixels.value[index] === selected.value) return
  if (!stroke) { stroke = [...pixels.value]; remember(stroke) }
  pixels.value[index] = selected.value
}
function start(event) {
  if (event.button !== 0) return
  const cell = event.target.closest('[data-cell]')
  if (!cell) return
  painting = true; stroke = null; cursor.value = Number(cell.dataset.cell)
  cell.focus({ preventScroll: true }); grid.value.setPointerCapture(event.pointerId)
  paint(cursor.value)
}
function move(event) {
  if (!painting) return
  const cell = document.elementFromPoint(event.clientX, event.clientY)?.closest('[data-cell]')
  if (cell && grid.value.contains(cell)) paint(Number(cell.dataset.cell))
}
function stop() { painting = false; stroke = null }
onBeforeUnmount(stop)
function keyboard(event, index) {
  const offsets = { ArrowLeft: -1, ArrowRight: 1, ArrowUp: -gridSize, ArrowDown: gridSize }
  if (event.key in offsets) {
    event.preventDefault(); cursor.value = Math.min(gridSize ** 2 - 1, Math.max(0, index + offsets[event.key]))
    grid.value.querySelector(`[data-cell="${cursor.value}"]`)?.focus()
  }
  if (event.key === ' ' || event.key === 'Enter') { event.preventDefault(); stroke = null; paint(index); stop() }
}
function replace(next) { stop(); if (pixels.value.every((c, i) => c === next[i])) return; remember([...pixels.value]); pixels.value = next }
function undo() { stop(); const previous = history.value.at(-1); if (previous) { pixels.value = previous; history.value = history.value.slice(0, -1) } }
function save() { try { localStorage.setItem('qiwuli-pixel-garden', JSON.stringify(pixels.value)); notify('画作已保存到当前浏览器') } catch { notify('浏览器未允许保存，请下载作品留存') } }
function download() {
  const url = URL.createObjectURL(new Blob([pixelsSvg(pixels.value)], { type: 'image/svg+xml;charset=utf-8' }))
  const a = document.createElement('a'); a.href = url; a.download = 'qiwuli-pixel-garden.svg'; a.click()
  setTimeout(() => URL.revokeObjectURL(url), 30000); notify('已生成 SVG 画作，请查看浏览器下载')
}
</script>
<template>
  <section class="experiment-panel garden-lab" aria-labelledby="garden-title">
    <div class="experiment-copy"><span class="eyebrow">03 / PIXEL GARDEN</span><h2 id="garden-title">画一朵像素花</h2><p>选好颜色，点按或拖动画布。</p>
      <div class="pixel-colors" role="group" aria-label="选择画笔颜色"><button v-for="(color, i) in pixelColors" :key="color" :style="{ background: color }" :aria-label="i === 0 ? '橡皮擦' : `画笔颜色 ${color}`" :aria-pressed="selected === color" @click="selected = color"><span v-if="selected === color" :style="{ color: [1, 7].includes(i) ? '#fff' : '#253c32' }">✓</span></button></div>
      <div class="experiment-actions"><button class="button secondary" :disabled="!history.length" @click="undo">撤销</button><button class="button secondary" @click="replace(flowerPixels())">花朵图案</button><button class="button secondary" @click="replace(Array(gridSize ** 2).fill(pixelColors[0]))">清空画布</button></div>
      <p id="pixel-help" class="tool-note">键盘：方向键移动，空格或回车涂色。支持撤销最近 30 步。</p>
      <div class="experiment-actions"><button class="button secondary" @click="save">保存到本机</button><button class="button primary" @click="download">下载 SVG 画作</button></div><p class="tool-note">手动保存到当前浏览器；未保存内容离开实验室后重置。</p>
    </div>
    <div class="pixel-paper"><div ref="grid" class="pixel-grid" role="group" aria-label="十二乘十二像素画布" aria-describedby="pixel-help" @pointerdown.prevent="start" @pointermove="move" @pointerup="stop" @pointercancel="stop" @lostpointercapture="stop"><button v-for="(color, i) in pixels" :key="i" :data-cell="i" :tabindex="cursor === i ? 0 : -1" :style="{ background: color }" :aria-label="`第 ${Math.floor(i / gridSize) + 1} 行，第 ${i % gridSize + 1} 列，${color}`" @keydown="keyboard($event, i)" @click="event => { if (event.detail === 0) { stroke = null; paint(i); stop() } }"></button></div><div class="paper-caption"><span>A GARDEN OF YOUR OWN</span><span>12 × 12</span></div></div>
  </section>
</template>
