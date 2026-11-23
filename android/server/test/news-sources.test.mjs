import assert from 'node:assert/strict';
import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { newsSourceCatalog } from '../src/news-sources.mjs';
import { decodeResource, parseFeed, sourceTime } from '../src/resource-formats.mjs';

const atom = '<feed xmlns="http://www.w3.org/2005/Atom"><entry><title>Trade bulletin</title>' +
  '<published>2026-11-23T05:00:00Z</published><summary>Port traffic</summary>' +
  '<link rel="self" href="/self"/><link rel="alternate" href="/bulletin"/></entry></feed>';

test('configured news feeds preserve fixed source parameters and omit retired resource entries', async t => {
  const directory = await mkdtemp(join(tmpdir(), 'workbench-news-'));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const configFile = join(directory, 'sources.json');
  const definition = {
    id: 'uk-business-trade', title: 'Trade', publisher: 'GOV.UK', category: 'trade', language: 'en',
    sourceUrl: 'https://www.gov.uk', endpoint: 'https://www.gov.uk/search/news-and-communications.atom',
    query: { 'organisations[]': 'department-for-business-innovation-science-and-trade' },
    format: 'atom', cacheSeconds: 120
  };
  await writeFile(configFile, JSON.stringify({ sources: [definition,
    { id: 'old-csv', format: 'csv' },
    { ...definition, id: 'old-atom', filters: [{ key: 'region' }] }
  ] }));
  let requests = 0;
  const catalog = newsSourceCatalog({ configFile, getResource: async (url, format) => {
    requests++;
    assert.equal(url.origin, 'https://www.gov.uk');
    assert.deepEqual(Object.fromEntries(url.searchParams), definition.query);
    assert.equal(format, 'atom');
    return atom;
  } });
  const first = await catalog.news(new URLSearchParams('category=trade&q=traffic&from=2026-11-23'));
  assert.deepEqual(first.sources.map(source => source.id), [definition.id]);
  assert.equal(first.articles[0].publisher, definition.publisher);
  assert.equal(first.articles[0].title, 'Trade bulletin');
  assert.equal(first.articles[0].link, 'https://www.gov.uk/bulletin');
  assert.equal((await catalog.news(new URLSearchParams('q=unmatched'))).articles.length, 0);
  assert.equal(requests, 1);
  await writeFile(configFile, JSON.stringify({ sources: [{ ...definition, title: 'Updated title' }] }));
  assert.equal((await catalog.news(new URLSearchParams())).sources[0].title, 'Updated title');
  await writeFile(configFile, JSON.stringify({ sources: [{ ...definition, endpoint: 'https://www.gov.uk/{keyword}' }] }));
  await assert.rejects(catalog.news(new URLSearchParams()), { status: 503 });
});

test('feed parsing retains Central European offsets and rejects DTD declarations', () => {
  assert.equal(sourceTime('Thu, 19 Nov 2026 20:09:08 CET'), '2026-11-19T19:09:08.000Z');
  assert.equal(sourceTime('Thu, 23 Jul 2026 20:09:08 CEST'), '2026-07-23T18:09:08.000Z');
  assert.throws(() => parseFeed('<!DOCTYPE rss><rss/>', 'rss', 'https://test.example'));
  assert.throws(() => parseFeed(atom, 'rss', 'https://test.example'));
  const encoded = Buffer.from('\ufeff' + atom, 'utf16le');
  assert.equal(decodeResource(encoded, 'application/atom+xml', 'atom'), atom);
});
