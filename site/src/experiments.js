export const labTools = [
  { id: 'palette', name: '拾色之间', english: 'COLOR NOTES' },
  { id: 'focus', name: '片刻专注', english: 'QUIET FOCUS' },
  { id: 'garden', name: '像素花园', english: 'PIXEL GARDEN' },
  { id: 'ideas', name: '灵感抽签', english: 'IDEA LOTTERY' },
  { id: 'choice', name: '今天选什么', english: 'LITTLE DECISIONS' },
  { id: 'breath', name: '呼吸留白', english: 'BREATHING SPACE' }
]

export const ideaThemes = {
  '观察': ['找一件每天见到的小物，画出它的轮廓，不必画得像。', '记下此刻窗外的三种颜色，为它们各取一个名字。', '拍下身边的一处影子，想象它是一片陌生的风景。', '挑一本旧书，记录翻开那一页时想到的第一件事。', '用五句话描述一杯水，试着不使用“透明”这个词。', '找出房间里最安静的角落，为它写一句说明。'],
  '创作': ['用三个圆和一条线，画一个今天的心情符号。', '为一个不存在的小店取名，再写一句欢迎语。', '只选两种颜色，为一首喜欢的歌做一张小封面。', '用十分钟做一个只解决一件小事的纸上界面。', '写一个只有五十字的小故事，让一片叶子做主角。', '把今天遇到的一个小麻烦，画成三格漫画。'],
  '生活': ['整理桌上的一个小角落，给喜欢的东西留一个位置。', '给很久没联系的朋友写一句问候，是否发送由你决定。', '为下一次散步选一条稍微不同的路线。', '写下今天值得记住的一件小事，哪怕它很普通。', '泡一杯喜欢的饮品，留出五分钟只感受它的味道。', '列出三件不用花钱也让你开心的事，挑一件试试。']
}

export function randomItem(items, previous, random = Math.random) {
  const pool = items.length > 1 ? items.filter(item => item !== previous) : items
  return pool.length ? pool[Math.min(pool.length - 1, Math.floor(Math.max(0, random()) * pool.length))] : null
}

export function parseChoices(text) {
  const options = [...new Set(text.split(/\r?\n/).map(s => s.trim()).filter(Boolean))]
  let error = ''
  if (options.length < 2) error = '请至少输入两个不同的选项，每行一个。'
  else if (options.length > 20) error = '最多支持 20 个不同选项，请删减后再试。'
  else if (options.some(s => s.length > 40)) error = '每个选项请控制在 40 个字以内。'
  return { options, error }
}

export const gridSize = 12
export const pixelColors = ['#F5F3E9', '#355D45', '#8BA77A', '#CAD8AD', '#CE8F7A', '#E6BE71', '#8CAFB3', '#595B72']
export function flowerPixels() {
  const pixels = Array(gridSize ** 2).fill(pixelColors[0])
  const rows = ['............', '....44.44...', '...4444444..', '...4455544..', '....45554...', '.....44.....', '......1.....', '...22.1.....', '....221.....', '......122...', '......1.....', '..33333333..']
  rows.forEach((row, y) => [...row].forEach((c, x) => { if (c !== '.') pixels[y * gridSize + x] = pixelColors[Number(c)] }))
  return pixels
}
export function validPixels(value) {
  return Array.isArray(value) && value.length === gridSize ** 2 && value.every(c => pixelColors.includes(c))
}
export function pixelsSvg(pixels) {
  if (!validPixels(pixels)) throw new Error('Invalid pixel artwork')
  return `<svg xmlns="http://www.w3.org/2000/svg" width="600" height="600" viewBox="0 0 ${gridSize} ${gridSize}" shape-rendering="crispEdges"><title>像素花园 · QIWULI</title>${pixels.map((color, i) => `<rect x="${i % gridSize}" y="${Math.floor(i / gridSize)}" width="1" height="1" fill="${color}"/>`).join('')}</svg>`
}

export function breathingFrame(elapsed, duration) {
  const seconds = Math.min(duration, Math.max(0, elapsed))
  const done = seconds >= duration
  const cycle = seconds % 10
  const inhale = cycle < 4
  return { done, remaining: Math.ceil(duration - seconds), phase: done ? '完成' : inhale ? '吸气' : '呼气', phaseRemaining: Math.ceil((inhale ? 4 : 10) - cycle), scale: inhale ? .72 + cycle / 4 * .28 : 1 - (cycle - 4) / 6 * .28 }
}
