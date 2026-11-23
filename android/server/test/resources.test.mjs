import assert from 'node:assert/strict';
import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { resourceCatalog } from '../src/resources.mjs';
import { decodeResource, parseFeed, sourceTime } from '../src/resource-formats.mjs';

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
  assert.equal(entries[0].id, 'un-geneva-headlines');
  assert.deepEqual([...new Set(entries.map(entry => entry.category))],
    ['headlines', 'current', 'trade', 'society', 'humanities', 'military', 'science', 'health', 'logistics', 'other']);
  const quakes = entries.find(entry => entry.id === 'usgs-earthquakes');
  assert.equal(quakes.endpoint, undefined);
  assert.equal(calls.length, 0);
  const result = await resources.download(quakes.id, new URLSearchParams('period=day'));
  assert.deepEqual(calls, ['https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/2.5_day.geojson']);
  assert.deepEqual(result.rows, [{ event: 'M 3.5', place: 'Pacific', magnitude: 3.5, time: '2026-07-25T17:20:00.000Z' }]);
  assert.equal(result.fetchedAt, '2026-11-23T00:00:00.000Z');
  assert.equal(result.updatedAt, result.rows[0].time);
  for (const invalid of ['period=..', 'period=day&period=week', 'url=https://example.test', 'period=ftp://example.test']) {
    await assert.rejects(resources.download(quakes.id, new URLSearchParams(invalid)), { status: 400 });
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

test('trade bulletin sends the official organization parameter and keeps public attribution', async () => {
  const feed = '<feed xmlns="http://www.w3.org/2005/Atom"><entry><title>Trade bulletin</title>' +
    '<published>2026-11-23T05:00:00Z</published></entry></feed>';
  const client = resourceCatalog({ getResource: async (url, format) => {
    assert.equal(url.origin, 'https://www.gov.uk');
    assert.equal(url.searchParams.get('organisations[]'), 'department-for-business-innovation-science-and-trade');
    assert.equal(format, 'atom');
    return feed;
  } });
  const result = await client.download('uk-business-trade', new URLSearchParams());
  assert.equal(result.publisher, 'GOV.UK');
  assert.equal(result.rows[0].headline, 'Trade bulletin');
});

test('UN Geneva publication timestamps retain their Central European offset', () => {
  assert.equal(sourceTime('Thu, 19 Nov 2026 20:09:08 CET'), '2026-11-19T19:09:08.000Z');
  assert.equal(sourceTime('Thu, 23 Jul 2026 20:09:08 CEST'), '2026-07-23T18:09:08.000Z');
});

test('RSS and Atom select dates and keywords before the preview limit and reuse the upstream response', async () => {
  let requests = 0, time = Date.parse('2026-11-23T03:30:00Z');
  const rss = `<rss version="2.0"><channel><title>WTO</title><lastBuildDate>Mon, 23 Nov 2026 03:00:00 GMT</lastBuildDate>
    <item><title>Trade report</title><description><![CDATA[<p>Port traffic</p>]]></description>
    <pubDate>Sun, 22 Nov 2026 22:15:00 -0500</pubDate><link>/official</link></item>
    <item><title>Trade yesterday</title><pubDate>Sun, 22 Nov 2026 03:00:00 GMT</pubDate></item>
    <item><title>Other news</title><pubDate>Mon, 23 Nov 2026 01:00:00 GMT</pubDate></item></channel></rss>`;
  const atom = `<feed xmlns="http://www.w3.org/2005/Atom"><updated>2026-11-23T03:26:00Z</updated><entry>
    <title>Gale Warning</title><updated>2026-11-23T03:00:00Z</updated><published>2026-11-23T02:00:00Z</published>
    <link rel="self" href="/self"/><link rel="alternate" href="/alert"/><summary>Marine wind</summary>
    </entry></feed>`;
  const client = resourceCatalog({ now: () => time, getResource: async (url, format) => {
    requests++;
    assert.ok(!url.searchParams.has('from') && !url.searchParams.has('q'));
    return format === 'rss' ? rss : atom;
  } });
  const first = await client.download('wto-trade-news', new URLSearchParams('q=TRADE+port&from=2026-11-23&to=2026-11-23'));
  assert.deepEqual(first.rows, [{ headline: 'Trade report', published: '2026-11-23T03:15:00.000Z',
    summary: 'Port traffic', link: 'https://www.wto.org/official' }]);
  assert.equal(first.matchedCount, 1);
  time += 5000;
  const refined = await client.download('wto-trade-news', new URLSearchParams('q=other'));
  assert.equal(refined.fetchedAt, first.fetchedAt);
  assert.equal(refined.rows[0].headline, 'Other news');
  assert.equal(requests, 1);
  const warning = await client.download('nws-marine-alerts', new URLSearchParams('region=AN&q=gale'));
  assert.equal(warning.rows[0].published, '2026-11-23T02:00:00.000Z');
  assert.equal(warning.rows[0].link, 'https://api.weather.gov/alert');
  for (const search of ['from=2026-02-30', 'from=2026-11-23&to=2026-11-22', 'from=2026-11-23&from=2026-11-22']) {
    await assert.rejects(client.download('wto-trade-news', new URLSearchParams(search)), { status: 400 });
  }
  assert.throws(() => parseFeed('<!DOCTYPE rss><rss/>', 'rss', 'https://test.example'));
  assert.throws(() => parseFeed(rss, 'atom', 'https://test.example'));
  const encoded = Buffer.from('\ufeff' + atom, 'utf16le');
  assert.equal(decodeResource(encoded, 'application/atom+xml', 'atom'), atom);
});

test('CSV parses quoted cells, filters on UTC observations and retains more download rows than preview rows', async () => {
  const head = '\ufeffDate Time, Water Level, Quality\r\n';
  const recent = Array.from({ length: 110 }, (_, index) =>
    `2026-11-23 ${String(Math.floor(index / 60)).padStart(2, '0')}:${String(index % 60).padStart(2, '0')},-0.10,"Prelim, ""A"""\r\n`);
  const csv = head + '2026-11-22 23:00,0.2,verified\r\n' + recent.join('');
  const client = resourceCatalog({ getResource: async (url, format) => {
    assert.equal(format, 'csv');
    assert.equal(url.searchParams.get('station'), '8518750');
    assert.equal(url.searchParams.get('q'), null);
    return csv;
  } });
  const legacy = await client.download('noaa-port-levels', new URLSearchParams('station=8518750&from=2026-11-23&q=prelim'));
  assert.equal(legacy.rows.length, 100);
  const data = await client.download('noaa-port-levels', new URLSearchParams('station=8518750&from=2026-11-23&q=prelim&export=1'));
  assert.equal(data.rows.length, 110);
  assert.equal(data.previewLimit, 100);
  assert.equal(data.updatedAt, '2026-11-23T01:49:00.000Z');
  assert.deepEqual(data.rows[0], { observed: data.updatedAt, level: '-0.10', quality: 'Prelim, "A"' });
  const invalid = resourceCatalog({ getResource: async () => 'Date Time,Date Time,Water Level,Quality\n' });
  await assert.rejects(invalid.download('noaa-port-levels', new URLSearchParams('station=8518750')), { status: 503 });
});
