<script setup>
import { ref, watch, nextTick } from 'vue'
import { useRoute } from 'vue-router'
import Icon from './components/Icon.vue'
import { reduceMotion, toggleMotion, notice } from './state.js'
const route = useRoute()
const menu = ref(false)
const nav = [{ to: '/projects', name: '项目集' }, { to: '/journal', name: '笔记' }, { to: '/lab', name: '实验室' }, { to: '/about', name: '关于' }]
watch(() => route.path, async () => { menu.value = false; await nextTick(); document.querySelector('#main')?.focus({ preventScroll: true }) })
function closeMenu() { menu.value = false; document.querySelector('.menu-toggle')?.focus() }
</script>
<template>
  <a class="skip-link" href="#main">跳到主要内容</a>
  <header class="site-header">
    <div class="header-inner">
      <RouterLink class="brand" to="/" aria-label="栖物里首页"><img src="/mark.svg" width="38" height="38" alt=""><span><strong>栖物里</strong><small>QIWULI</small></span></RouterLink>
      <nav class="desktop-nav" aria-label="主导航"><RouterLink v-for="link in nav" :key="link.to" :to="link.to">{{ link.name }}</RouterLink></nav>
      <div class="header-actions"><button class="motion-toggle" @click="toggleMotion" :aria-pressed="!reduceMotion" aria-label="页面动效" :title="reduceMotion ? '开启动效' : '关闭动效'"><Icon :name="reduceMotion ? 'play' : 'pause'" /></button><RouterLink to="/contact" class="contact-link">聊一聊 <Icon name="diagonal" /></RouterLink><button class="menu-toggle icon-button" aria-controls="mobile-nav" :aria-expanded="menu" :aria-label="menu ? '关闭导航' : '打开导航'" @click="menu = !menu"><Icon :name="menu ? 'close' : 'menu'" /></button></div>
    </div>
    <nav v-if="menu" id="mobile-nav" class="mobile-nav" aria-label="手机导航" @keydown.esc="closeMenu"><RouterLink to="/">首页</RouterLink><RouterLink v-for="link in nav" :key="link.to" :to="link.to">{{ link.name }}</RouterLink><RouterLink to="/contact">联系</RouterLink></nav>
  </header>
  <main id="main" tabindex="-1"><RouterView v-slot="{ Component }"><Transition name="page" mode="out-in"><component :is="Component" :key="route.path" /></Transition></RouterView></main>
  <footer class="site-footer"><div class="footer-top"><div><RouterLink class="brand" to="/"><img src="/mark.svg" width="34" height="34" alt=""><span><strong>栖物里</strong><small>A HOME FOR IDEAS</small></span></RouterLink><p>工具、笔记与实验。</p></div><div class="footer-links"><RouterLink to="/projects">项目集</RouterLink><RouterLink to="/journal">笔记</RouterLink><RouterLink to="/about">关于</RouterLink><RouterLink to="/contact">联系</RouterLink></div><a class="footer-mail" href="mailto:lo.c@live.cn">lo.c@live.cn <Icon name="diagonal" /></a></div><div class="footer-bottom"><span>© {{ new Date().getFullYear() }} 栖物里 QIWULI</span><RouterLink to="/about#privacy">隐私与使用说明</RouterLink></div></footer>
  <Transition name="toast"><div v-if="notice" class="toast" role="status"><Icon name="check" />{{ notice }}</div></Transition>
</template>
