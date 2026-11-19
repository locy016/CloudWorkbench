import { createApp, nextTick } from 'vue'
import { createRouter, createWebHistory } from 'vue-router'
import App from './App.vue'
import Home from './views/Home.vue'
import Projects from './views/Projects.vue'
import Project from './views/Project.vue'
import Journal from './views/Journal.vue'
import Article from './views/Article.vue'
import Lab from './views/Lab.vue'
import About from './views/About.vue'
import Contact from './views/Contact.vue'
import NotFound from './views/NotFound.vue'
import { projects, articles } from './content.js'
import './style.css'
import './styles-layout.css'
import './experiments.css'

const routes = [
  { path: '/', component: Home, meta: { title: '让作品与想法安放的地方' } },
  { path: '/projects', component: Projects, meta: { title: '项目集' } },
  { path: '/projects/:id', component: Project, meta: { title: '项目' }, beforeEnter: to => projects.some(p => p.id === to.params.id) || '/404' },
  { path: '/journal', component: Journal, meta: { title: '笔记' } },
  { path: '/journal/:id', component: Article, meta: { title: '阅读笔记' }, beforeEnter: to => articles.some(a => a.id === to.params.id) || '/404' },
  { path: '/lab', component: Lab, meta: { title: '小小实验室' } },
  { path: '/about', component: About, meta: { title: '关于栖物里' } },
  { path: '/contact', component: Contact, meta: { title: '联系' } },
  { path: '/:pathMatch(.*)*', component: NotFound, meta: { title: '页面未找到' } }
]
const router = createRouter({ history: createWebHistory(), routes, scrollBehavior(to, from, saved) { return saved || (to.hash ? { el: to.hash, top: 100 } : { top: 0 }) } })
router.afterEach(async to => {
  const item = to.path.startsWith('/journal/') ? articles.find(a => a.id === to.params.id) : projects.find(p => p.id === to.params.id)
  document.title = `${item?.title || item?.name || to.meta.title} · 栖物里 QIWULI`
  document.querySelector('meta[name="description"]').content = item?.summary || '栖物里，让作品与想法安放的地方。探索项目、阅读笔记、体验互动实验。'
  await nextTick()
})
const app = createApp(App)
app.directive('reveal', {
  mounted(el) {
    if (!('IntersectionObserver' in window) || document.documentElement.dataset.motion === 'off') return
    el.classList.add('reveal')
    const observer = new IntersectionObserver(entries => { if (entries.some(e => e.isIntersecting)) { el.classList.add('revealed'); observer.disconnect() } }, { threshold: .08 })
    observer.observe(el); el._observer = observer
  }, unmounted(el) { el._observer?.disconnect() }
})
app.use(router).mount('#app')
