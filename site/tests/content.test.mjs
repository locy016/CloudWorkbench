import { test } from 'node:test'
import assert from 'node:assert/strict'
import { filterItems, mailto, projects, articles, palettes } from '../src/content.js'
import { labTools } from '../src/experiments.js'
test('search combines category and case-insensitive keywords without changing source', () => {
  assert.equal(filterItems(projects, '  cOdEx ', '应用').length, 1)
  assert.equal(filterItems(projects, 'Codex', '工具').length, 0)
  assert.equal(filterItems(articles, '不存在的笔记').length, 0)
  assert.equal(filterItems(articles, '', '设计').length, 2)
  assert.equal(projects.length, 7)
})
test('email draft preserves Chinese, line breaks and query delimiters', () => {
  const url = new URL(mailto('  你好 & 想法?  ', '第一行\n第二行 + #测试'))
  assert.equal(url.pathname, 'lo.c@live.cn')
  assert.equal(url.searchParams.get('subject'), '你好 & 想法?')
  assert.equal(url.searchParams.get('body'), '第一行\n第二行 + #测试')
  assert.equal(new URL(mailto('', '')).searchParams.get('subject'), '你好，栖物里')
})
test('published content has distinct usable routes and complete reading content', () => {
  for (const collection of [projects, articles]) {
    assert.equal(new Set(collection.map(x => x.id)).size, collection.length)
    for (const item of collection) assert.match(item.id, /^[a-z0-9-]+$/)
  }
  for (const article of articles) assert.ok(article.sections.every(s => s.title && s.paragraphs.every(p => p.length > 20)))
  for (const project of projects) {
    assert.match(project.link, /^\/(lab|contact)(\?|$)/)
    if (project.link.startsWith('/lab')) assert.ok(labTools.some(t => t.id === new URL(project.link, 'https://qiwuli.com').searchParams.get('tool')))
  }
  for (const palette of palettes) { assert.equal(palette.colors.length, 5); palette.colors.forEach(c => assert.match(c, /^#[A-F0-9]{6}$/)) }
})
