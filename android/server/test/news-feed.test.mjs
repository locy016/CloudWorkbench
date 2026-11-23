import assert from 'node:assert/strict';
import { test } from 'node:test';
import { resourceCatalog } from '../src/resources.mjs';

const rss = items => '<rss version="2.0"><channel>' + items.map(([title, time, link, summary = '']) =>
  `<item><title>${title}</title><pubDate>${time}</pubDate><link>${link}</link><description>${summary}</description></item>`)
  .join('') + '</channel></rss>';

function fixture() {
  const hosts = [];
  const catalog = resourceCatalog({ getResource: async (url, format) => {
    hosts.push(url.hostname);
    switch (url.hostname) {
      case 'www.ungeneva.org':
        return rss([
          ['International supply chain report', 'Mon, 23 Nov 2026 04:00:00 GMT', 'https://source.example/report?a=1&amp;utm_medium=ref#note'],
          ['Official trade meeting conclusions', 'Mon, 23 Nov 2026 03:00:00 GMT', 'https://source.example/summary'],
          ['Unscheduled announcement', '', 'https://source.example/no-date'],
          ['Official trade meeting conclusions', 'Thu, 19 Nov 2026 03:00:00 GMT', 'https://source.example/archive']
        ]);
      case 'ec.europa.eu':
        return rss([
          ['Shipping bulletin (duplicate link)', 'Mon, 23 Nov 2026 02:00:00 GMT', 'http://source.example/report/?utm_source=mail&amp;a=1', 'Port update'],
          ['OFFICIAL trade meeting: conclusions', 'Mon, 23 Nov 2026 01:00:00 GMT', 'https://example.eu/summary'],
          ['A different report edition', 'Mon, 23 Nov 2026 00:00:00 GMT', 'https://source.example/report?a=2']
        ]);
      case 'www.gov.uk':
        if (url.searchParams.get('organisations[]') !== 'department-for-business-innovation-science-and-trade') {
          return '<feed xmlns="http://www.w3.org/2005/Atom"/>';
        }
        return '<feed xmlns="http://www.w3.org/2005/Atom"><entry><title>UK trade bulletin</title>' +
          '<updated>2026-11-23T05:00:00Z</updated><link href="https://www.gov.uk/news/1"/></entry></feed>';
      case 'policy.trade.ec.europa.eu':
      case 'ustr.gov':
      case 'www.nasa.gov':
        return rss([]);
      case 'www.wto.org':
        throw new Error('Source unavailable');
      default:
        assert.equal(format, 'rss');
        return rss([]);
    }
  } });
  return { catalog, hosts };
}

test('news joins eligible sources, orders dates and deduplicates records without rewriting original links', async () => {
  const { catalog, hosts } = fixture();
  const news = await catalog.news(new URLSearchParams());
  assert.equal(hosts.length, 32);
  assert.equal(new Set(news.sources.map(source => source.id)).size, 32);
  const categories = Map.groupBy(news.sources, source => source.category);
  assert.deepEqual([...categories.keys()].sort(), ['current', 'diplomacy', 'education', 'energy', 'environment',
    'headlines', 'health', 'humanities', 'military', 'science', 'society', 'trade', 'transport']);
  for (const sources of categories.values()) assert(new Set(sources.map(source => source.publisher)).size >= 2);
  assert.deepEqual(news.unavailable, ['wto-trade-news']);
  assert.deepEqual(news.articles.map(article => article.title), [
    'UK trade bulletin', 'International supply chain report', 'Official trade meeting conclusions',
    'A different report edition', 'Official trade meeting conclusions', 'Unscheduled announcement'
  ]);
  assert.equal(news.matchedCount, 6);
  assert.equal(news.articles[0].timeKind, 'updated');
  assert.equal(news.articles[1].timeKind, 'published');
  assert.equal(news.articles[1].link, 'https://source.example/report?a=1&utm_medium=ref#note');
  assert.equal(news.articles[1].sourceId, 'un-geneva-headlines');
  assert.equal(news.articles.at(-1).time, null);
});

test('news category, inclusive UTC publication dates, source and keyword intersect before deduplication', async () => {
  const { catalog, hosts } = fixture();
  const news = await catalog.news(new URLSearchParams({
    category: 'current', from: '2026-11-23', to: '2026-11-23', source: 'eu-commission-briefings', q: 'different report'
  }));
  assert.deepEqual(hosts, ['ec.europa.eu']);
  assert.equal(news.sources.find(source => source.id === 'eu-commission-briefings').category, 'current');
  assert.deepEqual(news.articles.map(article => article.time), ['2026-11-23T00:00:00.000Z']);

  const since = await catalog.news(new URLSearchParams({ category: 'headlines', from: '2026-11-23' }));
  assert.equal(since.articles.length, 2);
  const until = await catalog.news(new URLSearchParams({ to: '2026-11-22', category: 'headlines' }));
  assert.deepEqual(until.articles.map(article => article.link), ['https://source.example/archive']);
  const updatedOnly = await catalog.news(new URLSearchParams({ from: '2026-11-23', category: 'trade', source: 'uk-business-trade' }));
  assert.deepEqual(updatedOnly.articles, []);
  assert.deepEqual(updatedOnly.unavailable, []);
  const conflicting = await catalog.news(new URLSearchParams({ category: 'trade', source: 'un-geneva-headlines' }));
  assert.equal(conflicting.matchedCount, 0);
  for (const invalid of ['from=2026-02-29', 'from=', 'to=2026-11-31', 'from=2026-11-23&to=2026-11-22',
    'category=logistics', 'category=', 'category=trade&category=current', 'to=2026-11-21&to=2026-11-21']) {
    await assert.rejects(catalog.news(new URLSearchParams(invalid)), { status: 400 });
  }
  assert.deepEqual(hosts, ['ec.europa.eu', 'www.ungeneva.org', 'feeds.bbci.co.uk', 'feeds.npr.org', 'www.gov.uk']);
});

test('news keyword and selected source combine before deduplication', async () => {
  const { catalog, hosts } = fixture();
  const news = await catalog.news(new URLSearchParams({ q: 'PORT update', source: 'eu-commission-briefings' }));
  assert.deepEqual(hosts, ['ec.europa.eu']);
  assert.equal(news.articles.length, 1);
  assert.equal(news.articles[0].title, 'Shipping bulletin (duplicate link)');
  assert.equal(news.articles[0].publisher, '欧盟委员会（European Commission）');
  for (const invalid of ['source=nws-marine-alerts', 'url=https://example.test', 'q=a&q=b', 'source=']) {
    await assert.rejects(catalog.news(new URLSearchParams(invalid)), { status: 400 });
  }
  assert.equal(hosts.length, 1);
});

test('news returns the last valid feed during upstream or parse failure and identifies its collection time', async () => {
  let clock = Date.parse('2026-11-23T08:00:00Z');
  let fetches = 0;
  let body = rss([['A published report', 'Mon, 23 Nov 2026 04:00:00 GMT', 'https://example.test/report']]);
  const catalog = resourceCatalog({ now: () => clock, getResource: async () => {
    fetches++;
    if (body instanceof Error) throw body;
    return body;
  } });
  const source = 'un-geneva-headlines';
  const query = new URLSearchParams({ source });
  const fresh = await catalog.news(query);
  assert.deepEqual(fresh.cached, []);
  assert.equal(fresh.articles[0].language, 'en');
  assert.equal(fresh.requestedSourceCount, 1);

  clock += 121_000;
  body = '<html>Unexpected upstream response</html>';
  const cached = await catalog.news(query);
  assert.deepEqual(cached.articles, fresh.articles);
  assert.deepEqual(cached.cached, [{ id: source, fetchedAt: '2026-11-23T08:00:00.000Z' }]);
  assert.deepEqual(cached.unavailable, []);
  assert.deepEqual((await catalog.news(query)).cached, cached.cached);
  await assert.rejects(catalog.download(source, new URLSearchParams()), { status: 503 });
  assert.equal(fetches, 2);

  clock += 16_000;
  body = rss([['An updated official bulletin', 'Mon, 23 Nov 2026 08:00:00 GMT', 'https://example.test/latest']]);
  const recovered = await catalog.news(query);
  assert.deepEqual(recovered.cached, []);
  assert.equal(recovered.articles[0].title, 'An updated official bulletin');

  clock += 121_000;
  body = new Error('Upstream timed out');
  assert.deepEqual((await catalog.news(query)).articles, recovered.articles);

  clock += 6 * 60 * 60 * 1000 + 1;
  body = '<broken';
  const expired = await catalog.news(query);
  assert.deepEqual(expired.unavailable, [source]);
  assert.deepEqual(expired.articles, []);
});
