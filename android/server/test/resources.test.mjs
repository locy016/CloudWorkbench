import assert from 'node:assert/strict';
import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { resourceCatalog } from '../src/resources.mjs';

test('resource directory exposes filters, retrieves fresh data and projects the registered fields', async () => {
  const calls = [];
  const resources = resourceCatalog({
    now: () => Date.UTC(2026, 10, 23),
    getResource: async url => {
      calls.push(url.href);
      return { metadata: { generated: 1785000000000 }, features: [
        { properties: { title: 'M 3.5', place: 'Pacific', mag: 3.5, time: 1785000000000, ignored: 'internal' } }
      ] };
    }
  });
  const entries = await resources.list();
  assert.equal(entries[0].id, 'usgs-earthquakes');
  assert.equal(entries[0].endpoint, undefined);
  assert.equal(calls.length, 0);
  const result = await resources.download(entries[0].id, new URLSearchParams('period=day'));
  assert.deepEqual(calls, ['https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/2.5_day.geojson']);
  assert.deepEqual(result.rows, [{ event: 'M 3.5', place: 'Pacific', magnitude: 3.5, time: '2026-07-25T17:20:00.000Z' }]);
  assert.equal(result.fetchedAt, '2026-11-23T00:00:00.000Z');
  assert.equal(result.updatedAt, result.rows[0].time);
  for (const invalid of ['period=..', 'period=day&period=week', 'url=https://example.test', 'period=ftp://example.test']) {
    await assert.rejects(resources.download(entries[0].id, new URLSearchParams(invalid)), { status: 400 });
  }
  assert.equal(calls.length, 1);
});

test('a source file is read at runtime and a keyword is only passed as one configured query value', async t => {
  const directory = await mkdtemp(join(tmpdir(), 'workbench-resources-'));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const configFile = join(directory, 'resources.json');
  const definition = {
    id: 'optional-site', title: '公开资源', publisher: 'Provider', sourceUrl: 'https://provider.example.org',
    endpoint: 'https://provider.example.org/records',
    query: { keyword: '{keyword}', limit: '5' },
    filters: [{ key: 'keyword', label: '关键词', type: 'text', maxLength: 20 }],
    fields: [{ key: 'name', label: '名称', path: 'name' }], rows: 'items'
  };
  let requested;
  const resources = resourceCatalog({ configFile, getResource: async url => {
    requested = url;
    return { items: [{ name: 'A' }] };
  } });
  assert.ok((await resources.list()).some(entry => entry.id === 'usgs-earthquakes'));
  await writeFile(configFile, JSON.stringify({ sources: [definition] }));
  assert.deepEqual((await resources.list()).map(entry => entry.id), [definition.id]);
  const params = new URLSearchParams({ keyword: '中国 & id=2' });
  assert.deepEqual((await resources.download(definition.id, params)).rows, [{ name: 'A' }]);
  assert.equal(requested.origin, 'https://provider.example.org');
  assert.deepEqual(Object.fromEntries(requested.searchParams), { keyword: '中国 & id=2', limit: '5' });
  await assert.rejects(resources.download(definition.id, new URLSearchParams({ keyword: 'a\nb' })), { status: 400 });
  await writeFile(configFile, JSON.stringify({ sources: [{ ...definition, endpoint: 'https://provider.example.org/{keyword}' }] }));
  await assert.rejects(resources.list(), { status: 503 });
});
