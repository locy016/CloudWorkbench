<script setup>
import { computed, ref, watch } from 'vue'
import { parseChoices, randomItem } from '../experiments.js'
import { copyText } from '../state.js'
const examples = { '午后清单': '去散步\n读几页书\n泡一杯茶\n整理桌面', '创作清单': '画一朵小花\n写五行随笔\n拍一张光影\n试一组新配色' }
const text = ref(examples['午后清单']); const result = ref(''); const submitted = ref(false)
const parsed = computed(() => parseChoices(text.value))
watch(text, () => { result.value = ''; submitted.value = false })
function choose() { submitted.value = true; result.value = parsed.value.error ? '' : randomItem(parsed.value.options) }
</script>
<template>
  <section class="experiment-panel choice-lab" aria-labelledby="choice-title"><div class="experiment-copy"><span class="eyebrow">05 / LITTLE DECISIONS</span><h2 id="choice-title">今天选什么？</h2><p>每行一个选项，随机选出一个。</p><div class="choice-examples"><span>试试：</span><button v-for="(value, name) in examples" :key="name" @click="text = value">{{ name }}</button></div><label class="field-label" for="choice-input">我的候选项</label><textarea id="choice-input" v-model="text" rows="6" maxlength="3000" :aria-invalid="submitted && !!parsed.error" aria-describedby="choice-help choice-error"></textarea><div id="choice-help" class="choice-count">{{ parsed.options.length }} 个不同选项 · 支持 2–20 项，每项不超过 40 字</div><p id="choice-error" class="field-error" role="alert">{{ submitted ? parsed.error : '' }}</p><button class="button primary" @click="choose">{{ result ? '再选一次' : '帮我选一个' }}</button><p class="tool-note">忽略空行与重复项，结果可能连续相同。输入与结果不上传、不保存。</p></div>
    <div class="choice-result"><span class="eyebrow">A LITTLE NUDGE FOR TODAY</span><span class="choice-star" aria-hidden="true">↗</span><div aria-live="polite" aria-atomic="true"><span class="subtle">{{ result ? '这一次，不妨试试' : '把选择写在左边' }}</span><h3>{{ result || '留一点意外给今天。' }}</h3></div><button class="text-link" :disabled="!result" @click="copyText(result)">复制这个选择 →</button></div>
  </section>
</template>
