export const email = 'lo.c@live.cn'
export const projects = [
  { id: 'cloud-workbench', name: '云端工作台', english: 'Cloud Workbench', category: '应用', status: '持续迭代', art: 'workbench', year: '2026', summary: '在手机上查看设备、继续 Codex 对话，连接自己的开发环境。', tags: ['Android', 'Codex', '私有云'], description: '连接手机、服务器与 Windows，在同一入口查看设备、处理开发对话、审批任务和升级应用。', features: ['跨设备查看运行状态与项目', '浏览网页，按需选择文字或图片交给 Codex', '从手机提出升级需求，再回到手机确认安装'], note: '仅用于个人部署，暂不开放注册或安装包下载。', link: '/contact?subject=聊聊云端工作台', action: '交流这个项目' },
  { id: 'color-notes', name: '拾色之间', english: 'Color Notes', category: '实验', status: '可以体验', art: 'palette', year: '2026', summary: '挑选自然配色，复制色值或下载 SVG 色卡。', tags: ['配色', '交互', '浏览器工具'], description: '从四组自然配色中选择，点击色块复制 HEX 色值，或下载整组 SVG 色卡。', features: ['四组原创自然色系', '复制 HEX 色值与整组配色', '导出可用于创作的 SVG 色卡'], note: '无需登录，复制与下载均在浏览器内完成。', link: '/lab?tool=palette', action: '打开色彩实验' },
  { id: 'quiet-focus', name: '片刻专注', english: 'A Quiet Moment', category: '工具', status: '可以体验', art: 'focus', year: '2026', summary: '选一段时间，专注完成手边的一件事。', tags: ['计时', '专注', '轻量工具'], description: '支持暂停、继续与重置。站内切换栏目继续计时，刷新或关闭页面后重置。', features: ['5、15、25 分钟三种时长', '暂停、继续与重置', '按实际时间计算，回到页面时校正剩余时间'], note: '不上传计时数据，无声音或通知。后台显示可能延迟，回到页面后自动校正。', link: '/lab?tool=focus', action: '开始一段专注' },
  { id: 'pixel-garden', name: '像素花园', english: 'Pixel Garden', category: '实验', status: '可以体验', art: 'garden', year: '2026', summary: '在 12 × 12 方格中绘画，保存或导出自己的像素作品。', tags: ['像素画', '绘画', 'SVG 导出'], description: '从花朵图案开始，或清空画布自由创作。支持鼠标、触摸与键盘操作。', features: ['支持鼠标拖绘、触摸和键盘绘画', '撤销笔画，保存到当前浏览器', '下载为可缩放的 SVG 像素作品'], note: '手动保存到当前浏览器，清除网站数据后删除。未保存内容离开实验室后重置。', link: '/lab?tool=garden', action: '种下第一朵花' },
  { id: 'idea-lottery', name: '灵感抽签', english: 'Idea Lottery', category: '实验', status: '可以体验', art: 'ideas', year: '2026', summary: '抽一条创作练习，从一个小动作寻找灵感。', tags: ['灵感', '创作练习', '随机发现'], description: '从观察、创作、生活三个主题中随机抽取练习，也可以复制签文留用。', features: ['观察、创作、生活三组原创练习', '同一主题连续抽取不重复上一张', '复制签文，留作自己的创作起点'], note: '从预设内容中随机抽取，不保存历史。', link: '/lab?tool=ideas', action: '抽一张灵感签' },
  { id: 'daily-choice', name: '今天选什么', english: 'Little Decisions', category: '工具', status: '可以体验', art: 'choice', year: '2026', summary: '写下几个候选项，随机选出今天的小安排。', tags: ['随机选择', '自定义清单', '生活小工具'], description: '输入候选项，或使用现成清单。每个不同选项的入选机会相同，结果可复制。', features: ['自定义 2 至 20 个候选项，自动去重', '一键填入午后或创作清单', '查看结果并复制选中的选项'], note: '输入与结果不上传、不保存，离开实验室后重置。适合日常小选择。', link: '/lab?tool=choice', action: '交给一点随机' },
  { id: 'breathing-space', name: '呼吸留白', english: 'Breathing Space', category: '工具', status: '可以体验', art: 'breath', year: '2026', summary: '跟着 4 秒吸气、6 秒呼气的节奏，放松片刻。', tags: ['呼吸节奏', '放松', '减少动效'], description: '选择练习时长，跟随圆形起伏呼吸。关闭动效后仍可查看文字与倒计时。', features: ['1、2、3 分钟三种练习时长', '暂停、继续、重置，按实际时间校正', '支持减少动效，离开当前实验时自动暂停'], note: '按舒适节奏自然呼吸，不适时停止。离开实验室或刷新后重置，无声音或通知。', link: '/lab?tool=breath', action: '留一刻呼吸' }
]

export const articles = [
  { id: 'a-home-for-ideas', title: '为什么叫「栖物里」', category: '随笔', date: '2026-11-12', minutes: 3, cover: 'seed', summary: '栖是安放，物是作品，里是容纳它们的空间。', sections: [
    { title: '先留一个位置', paragraphs: ['做一个项目，往往从一个很小的念头开始。也许是一件重复操作想变得省事一点，也许是一段阅读想留下笔记，也许只是想试试某种交互会是什么样子。它们不一定马上有结果，却值得先有一个安放的位置。', '“栖物里”想表达的，就是这样一个位置。栖，读作 qī，是停留与安放；物，可以是一件作品，也可以是尚未成形的想法；里，是容纳它们的小小空间。'] },
    { title: '不把名字绑在某一种技术上', paragraphs: ['工具会变，平台会变，最初的项目也可能被重新设计。一个长期使用的名字，不必跟着技术栈一同更换。因此，这里选择一个可以承载多个项目的独立名字，而不是某台机器、某项技术的代号。', '工具、笔记与实验是三个起点。工具回应实际需要，笔记留下思考过程，实验则允许一些暂时没有明确用途的尝试。它们可以交叉，也可以慢慢长成新的栏目。'] },
    { title: '慢慢做，让东西真正可用', paragraphs: ['本站会从少量具体内容开始。已经可以使用的东西提供入口，仍在个人环境迭代的项目说明边界，不把计划写成已经交付的能力。', '如果某篇笔记让你想到自己的实践，或者一个小工具还有可以改进的地方，欢迎写信交流。名字只是起点，持续发生在这里的事情，才会赋予它更多含义。'] }
  ] },
  { id: 'small-tools-clear-boundaries', title: '小工具，也值得有清楚的边界', category: '设计', date: '2026-11-12', minutes: 4, cover: 'grid', summary: '说清功能、反馈与数据去向，让每次点击都有预期。', sections: [
    { title: '先回答一个具体问题', paragraphs: ['小工具最容易失去方向的时刻，是开始把所有“以后可能用到”的功能装进去。一个计时器可以先帮助使用者安静地完成一件事，一张色卡可以先让人方便地复制颜色。把这个最初的问题回答完整，比菜单里多几个入口更有价值。', '明确的范围也让体验更容易理解。进入页面，知道它能做什么；开始操作，能够预期结果；需要离开时，也不必担心留下了什么未说明的数据。'] },
    { title: '反馈要对应真实结果', paragraphs: ['“已复制”应当出现在复制真正成功之后。下载按钮应该交付一个可以打开的文件。没有服务端接收能力的表单，就不应该显示“提交成功”。这些反馈看起来很小，却决定使用者是否还能信任下一次点击。', '在栖物里的联系页，填写内容只是准备一封邮件。点击后会打开本机邮件客户端，仍由你检查和发送。页面明确说明这一点，也直接提供邮箱地址作为另一种选择。'] },
    { title: '把数据的位置说明白', paragraphs: ['能在浏览器内完成的计算，可以先留在浏览器内完成。配色生成不需要知道是谁在使用，短暂的计时也不一定需要一个账户。这样既减少操作步骤，也让维护更简单。', '不过，本地处理并不等于永久保存。本站的动画偏好与文章收藏保存在当前浏览器；清理浏览器数据后它们会消失。计时在刷新页面后重置。这些限制应当在需要它们的地方被看见。'] }
  ] },
  { id: 'motion-with-purpose', title: '让动效服务于阅读，而不是打断它', category: '设计', date: '2026-11-12', minutes: 3, cover: 'orbit', summary: '用动效解释变化，也给阅读留出安静。', sections: [
    { title: '变化需要一点线索', paragraphs: ['当列表筛选后只剩几个结果，如果所有内容突然跳到新的位置，眼睛需要重新寻找线索。短暂的过渡可以说明哪些内容离开、哪些内容保留。动效的价值首先是解释变化，而不是制造装饰。', '同样，按钮的轻微反馈能够回应一次点击，阅读进度能够告诉你文章走到了哪里。这些变化不需要很大，但应该与操作有关。'] },
    { title: '保留停下来的选择', paragraphs: ['不同的人对运动画面的感受并不相同。有人喜欢轻轻浮动的卡片，也有人更希望页面完全安静。一个可用的界面，应当尊重系统的减少动画偏好，并提供明确的开关。', '栖物里右上角的暂停图标可以关闭装饰动画与页面过渡，关闭后图标变为播放，方便随时重新开启。关闭后，筛选、阅读和实验工具仍然可以使用；表达方式改变，功能不应该消失。'] },
    { title: '在小屏幕上重新看一遍', paragraphs: ['桌面上舒展的留白，到了手机上可能占掉整个首屏。悬停才能发现的信息，对触屏使用者也不够友好。因此，重要的文字和入口必须直接可见，动画只作为补充。', '让界面更灵动，并不意味着让所有元素都在动。有些地方负责引导，有些地方留给阅读。能在需要时安静下来，也是一种设计。'] }
  ] }
]

export const palettes = [
  { name: '林间来信', mood: '叶影与晨光', colors: ['#214C3C', '#60846B', '#A7BFA0', '#DAE8BE', '#F4F3EA'] },
  { name: '陶土午后', mood: '温暖而质朴', colors: ['#6B3D2E', '#AF6A4E', '#D9A17A', '#EAD2B6', '#FAF3E8'] },
  { name: '海岸微风', mood: '把目光放远一点', colors: ['#21475D', '#4E8391', '#94B7B5', '#D5DFCE', '#F4EBD8'] },
  { name: '暮色收藏', mood: '日落之后的安静', colors: ['#433F59', '#79718E', '#B1A1B8', '#DCC6C9', '#F4EAE6'] }
]

export function filterItems(items, query = '', category = '全部') {
  const q = query.trim().toLocaleLowerCase()
  return items.filter(item => (category === '全部' || item.category === category) && (!q || [item.name, item.title, item.summary, ...(item.tags || [])].filter(Boolean).join(' ').toLocaleLowerCase().includes(q)))
}
export function mailto(subject, message) { return `mailto:${email}?subject=${encodeURIComponent(subject.trim() || '你好，栖物里')}&body=${encodeURIComponent(message.trim())}` }
