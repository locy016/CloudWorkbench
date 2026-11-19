<script setup>defineProps({ type: String })</script>
<template>
  <div class="project-art" :class="`art-${type}`" aria-hidden="true">
    <template v-if="type === 'workbench'"><div class="mini-window"><div class="window-bar"><i></i><i></i><i></i><span>WORKSPACE</span></div><div class="window-body"><div class="window-rail"><b>◫</b><b>▤</b><b>◉</b></div><div class="window-content"><span class="mini-caption">自己的工作区</span><strong>把想法，<br>变成下一步。</strong><div class="mini-message"><i></i><span>设备 · 项目 · 对话</span><b>↗</b></div></div></div></div><span class="art-label">INTERFACE STUDY / 界面示意</span></template>
    <template v-else-if="type === 'palette'"><div class="swatch-deck"><span v-for="n in 5" :key="n" :class="`swatch-${n}`"><small>0{{ n }}</small></span></div><span class="art-label">A LITTLE COLOR, A NEW POSSIBILITY</span></template>
    <template v-else-if="type === 'garden'"><div class="garden-cover"><span>✿</span><i></i><b></b><small>GROW SOMETHING SMALL</small></div><span class="art-label">TWELVE BY TWELVE / 无穷的小可能</span></template>
    <template v-else-if="type === 'ideas'"><div class="ideas-cover"><span>今日的小签</span><strong>好奇</strong><i>从一个小动作开始</i><small>✳ &nbsp; QIWULI</small></div><span class="art-label">A SMALL BEGINNING</span></template>
    <template v-else-if="type === 'choice'"><div class="choice-cover"><span>读几页书</span><strong>去散步 <i>↗</i></strong><span>泡一杯茶</span></div><span class="art-label">LEAVE A LITTLE ROOM FOR CHANCE</span></template>
    <template v-else-if="type === 'breath'"><div class="breath-cover"><span>吸气</span><strong>4 / 6</strong><i>留一刻空白</i></div><span class="art-label">BREATHE IN · BREATHE OUT</span></template>
    <template v-else><div class="focus-art-ring"><div><span>留一点时间</span><strong>15:00</strong><i>给眼前的一件事</i></div></div><span class="art-label">ONE THING AT A TIME</span></template>
  </div>
</template>
