<script setup>
import { computed, ref, watch, onBeforeUnmount } from 'vue'
import { breathingFrame } from '../experiments.js'
import { reduceMotion, notify } from '../state.js'
const props = defineProps({ active: Boolean })
const minutes = ref(1); const elapsed = ref(0); const running = ref(false)
const frame = computed(() => breathingFrame(elapsed.value, minutes.value * 60))
const remainingText = computed(() => `${Math.floor(frame.value.remaining / 60)}:${String(frame.value.remaining % 60).padStart(2, '0')}`)
let started = 0; let carried = 0; let interval
function tick() {
  if (!running.value) return
  elapsed.value = Math.min(minutes.value * 60, carried + (Date.now() - started) / 1000)
  if (frame.value.done) { running.value = false; clearInterval(interval); notify('这一刻留白结束了，慢慢回到手边的事。') }
}
function pause() { tick(); running.value = false; carried = elapsed.value; clearInterval(interval) }
function reset(value = minutes.value) { pause(); minutes.value = value; elapsed.value = 0; carried = 0 }
function toggle() {
  if (running.value) { pause(); return }
  if (frame.value.done) reset()
  started = Date.now(); running.value = true; interval = setInterval(tick, 100)
}
watch(() => props.active, active => { if (!active) pause() })
function visibility() { if (document.hidden) pause() }
document.addEventListener('visibilitychange', visibility)
onBeforeUnmount(() => { pause(); document.removeEventListener('visibilitychange', visibility) })
</script>
<template>
  <section class="experiment-panel breath-lab" aria-labelledby="breath-title"><div class="experiment-copy"><span class="eyebrow">06 / BREATHING SPACE</span><h2 id="breath-title">跟着节奏，慢慢呼吸</h2><p>4 秒吸气，6 秒呼气，不必屏息。</p><div class="filter-chips" role="group" aria-label="呼吸练习时长"><button v-for="value in [1, 2, 3]" :key="value" :class="{ active: minutes === value }" :aria-pressed="minutes === value" @click="reset(value)">{{ value }} 分钟</button></div><div class="experiment-actions"><button class="button primary" @click="toggle">{{ running ? '暂停练习' : frame.done ? '再来一刻' : elapsed ? '继续练习' : '开始呼吸' }}</button><button class="button secondary" @click="reset()">重新开始</button></div><p class="tool-note">切换实验或后台会暂停；切换时长、离开实验室或刷新后重置。关闭动效后可看文字节奏。</p><p class="tool-note">按舒适节奏自然呼吸，不适时停止。</p></div>
    <div class="breath-stage"><div class="breath-orbit" aria-hidden="true"><div class="breath-disc" :style="{ transform: `scale(${reduceMotion || !elapsed ? .86 : frame.scale})` }"></div></div><div class="breath-instruction"><span class="eyebrow">{{ frame.done ? 'A QUIET MOMENT, KEPT' : 'TAKE YOUR TIME' }}</span><strong aria-live="polite" aria-atomic="true">{{ frame.done ? '做得很好' : running ? frame.phase : elapsed ? '已暂停' : '准备好了' }}</strong><span>{{ running ? `${frame.phaseRemaining} 秒` : frame.done ? '让呼吸回到自然的节奏' : '按自己的节奏，慢慢来' }}</span><small role="timer" aria-label="练习剩余时间">{{ remainingText }}</small></div></div>
  </section>
</template>
