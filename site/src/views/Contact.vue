<script setup>
import { ref, computed } from 'vue'
import { useRoute } from 'vue-router'
import { email, mailto } from '../content.js'
import { copyText } from '../state.js'
import Icon from '../components/Icon.vue'
const route = useRoute(); const subject = ref(typeof route.query.subject === 'string' ? route.query.subject : '你好，栖物里'); const message = ref('')
const link = computed(() => mailto(subject.value, message.value))
</script>
<template><div class="container page-space contact-page"><header class="page-heading"><div class="eyebrow">LET’S START A CONVERSATION</div><h1>有想法，来聊聊<span class="serif-dot">.</span></h1><p>反馈、建议，或打个招呼。</p></header><div class="contact-layout"><section class="contact-info"><span class="eyebrow">写信到这里</span><a class="email-display" :href="`mailto:${email}`">{{ email }} <Icon name="diagonal" /></a><button class="text-link" @click="copyText(email)"><Icon name="copy" />复制邮箱地址</button><div class="letter-art" aria-hidden="true"><span>hello.</span><i>栖物里 / QIWULI</i><Icon name="leaf" /></div><p class="subtle">也可以复制邮箱，在常用邮箱中写信。</p></section><section class="letter-form"><h2>准备一封邮件</h2><label for="mail-subject">邮件主题</label><input id="mail-subject" v-model="subject" maxlength="150" placeholder="想聊聊什么？"><label for="mail-message">你的想法</label><textarea id="mail-message" v-model="message" rows="7" maxlength="4000" placeholder="你好，我想……"></textarea><a class="button primary" :href="link"><Icon name="mail" />打开邮件草稿 <Icon name="arrow" /></a><p class="form-note">通过你的邮件客户端确认发送，本站不提交或保存。</p></section></div></div></template>
