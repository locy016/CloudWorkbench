import { ref, computed } from 'vue'
const read = (key, fallback) => { try { return JSON.parse(localStorage.getItem(key)) ?? fallback } catch { return fallback } }
const write = (key, value) => { try { localStorage.setItem(key, JSON.stringify(value)) } catch {} }
const system = window.matchMedia('(prefers-reduced-motion: reduce)')
export const reduceMotion = ref(system.matches || read('qiwuli-motion', false))
function applyMotion() { document.documentElement.dataset.motion = reduceMotion.value ? 'off' : 'on' }
applyMotion()
system.addEventListener('change', e => { reduceMotion.value = e.matches || read('qiwuli-motion', false); applyMotion() })
export function toggleMotion() { reduceMotion.value = !reduceMotion.value; write('qiwuli-motion', reduceMotion.value); applyMotion() }
const saved = read('qiwuli-bookmarks', [])
export const bookmarks = ref(Array.isArray(saved) ? saved.filter(x => typeof x === 'string') : [])
export function toggleBookmark(id) { bookmarks.value = bookmarks.value.includes(id) ? bookmarks.value.filter(x => x !== id) : [...bookmarks.value, id]; write('qiwuli-bookmarks', bookmarks.value) }
export const notice = ref('')
let noticeTimer
export function notify(message) { notice.value = message; clearTimeout(noticeTimer); noticeTimer = setTimeout(() => notice.value = '', 3500) }
export async function copyText(text) { try { await navigator.clipboard.writeText(text); notify('已复制到剪贴板'); return true } catch { notify('浏览器未允许复制，请选中文字手动复制'); return false } }

export const focusMinutes = ref(15)
export const remaining = ref(15 * 60)
export const running = ref(false)
export const focusDone = ref(false)
let deadline = 0
export function tickFocus(now = Date.now()) {
  if (!running.value) return
  remaining.value = Math.max(0, Math.ceil((deadline - now) / 1000))
  if (remaining.value === 0) { running.value = false; focusDone.value = true; notify('这一段专注结束了，休息一下吧。') }
}
setInterval(tickFocus, 250)
document.addEventListener('visibilitychange', () => { if (!document.hidden) tickFocus() })
export function resetFocus(minutes = focusMinutes.value) { running.value = false; focusMinutes.value = minutes; remaining.value = minutes * 60; focusDone.value = false }
export function toggleFocus() {
  if (running.value) { tickFocus(); running.value = false }
  else { if (remaining.value === 0) resetFocus(); deadline = Date.now() + remaining.value * 1000; running.value = true; focusDone.value = false }
}
export const clockText = computed(() => `${String(Math.floor(remaining.value / 60)).padStart(2, '0')}:${String(remaining.value % 60).padStart(2, '0')}`)
