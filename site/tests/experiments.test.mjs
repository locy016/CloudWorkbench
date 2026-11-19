import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { projects } from '../src/content.js'
import { parseChoices, randomItem, flowerPixels, validPixels, pixelsSvg, breathingFrame, ideaThemes } from '../src/experiments.js'

test('choices ignore whitespace and duplicate lines; invalid lists never draw', () => {
  assert.deepEqual(parseChoices(' 散步 \r\n读书\n\n散步 \n'), { options: ['散步', '读书'], error: '' })
  for (const input of ['', '散步\n散步', Array.from({ length: 21 }, (_, i) => `选项${i}`).join('\n'), '散步\n' + '字'.repeat(41)]) {
    assert.ok(parseChoices(input).error)
  }
  assert.equal(parseChoices('字'.repeat(40) + '\n读书').error, '')
  assert.equal(parseChoices(Array.from({ length: 20 }, (_, i) => `选项${i}`).join('\n')).error, '')
})

test('random choices cover each option and inspiration excludes the previous prompt', () => {
  const options = ['散步', '读书', '泡茶']
  assert.deepEqual([0, .34, .67, .999].map(n => randomItem(options, undefined, () => n)), ['散步', '读书', '泡茶', '泡茶'])
  assert.equal(randomItem([], undefined), null)
  for (const prompts of Object.values(ideaThemes)) {
    assert.equal(new Set(prompts).size, prompts.length)
    for (const previous of prompts) for (const value of [0, .5, .999]) assert.notEqual(randomItem(prompts, previous, () => value), previous)
  }
})

test('pixel artwork rejects corrupt storage and exports only valid SVG color data', () => {
  const pixels = flowerPixels()
  assert.ok(validPixels(pixels))
  assert.equal(validPixels(null), false)
  assert.equal(validPixels(pixels.slice(1)), false)
  const corrupt = [...pixels]; corrupt[0] = '"><script>alert(1)</script>'
  assert.equal(validPixels(corrupt), false)
  assert.throws(() => pixelsSvg(corrupt))
  const svg = pixelsSvg(pixels)
  assert.equal((svg.match(/<rect /g) || []).length, 144)
  assert.match(svg, /width="600" height="600"/)
  assert.match(svg, /shape-rendering="crispEdges"/)
})

test('breathing transitions and completion stay correct after delayed browser ticks', () => {
  assert.equal(breathingFrame(0, 60).phase, '吸气')
  assert.equal(breathingFrame(3.99, 60).phaseRemaining, 1)
  assert.equal(breathingFrame(4, 60).phase, '呼气')
  assert.equal(breathingFrame(4, 60).phaseRemaining, 6)
  assert.equal(breathingFrame(10, 60).phase, '吸气')
  assert.equal(breathingFrame(47.5, 60).remaining, 13)
  assert.equal(breathingFrame(59.9, 60).done, false)
  assert.deepEqual([60, 75, 300].map(t => breathingFrame(t, 60).remaining), [0, 0, 0])
  assert.equal(breathingFrame(90, 60).phase, '完成')
  assert.equal(breathingFrame(-2, 60).remaining, 60)
  for (let t = 0; t <= 60; t += .25) assert.ok(breathingFrame(t, 60).scale >= .72 && breathingFrame(t, 60).scale <= 1)
})

test('every public project detail is discoverable in the sitemap', () => {
  const sitemap = readFileSync(new URL('../public/sitemap.xml', import.meta.url), 'utf8')
  for (const project of projects) assert.ok(sitemap.includes(`https://qiwuli.com/projects/${project.id}</loc>`), project.id)
})
