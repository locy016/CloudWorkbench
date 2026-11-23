import assert from 'node:assert/strict';
import { test } from 'node:test';
import { resourceCatalog } from '../src/resources.mjs';

const rss = items => '<rss version="2.0"><channel>' + items.map(([title, time, link, summary = '']) =>
  `<item><title>${title}</title><pubDate>${time}</pubDate><link>${link}</link><description>${summary}</description></item>`)
  .join('') + '</channel></rss>';

function fixture() {
  const hosts = [];
  const catalog = resourceCatalog({ getResource: async url => {
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
        return '<feed xmlns="http://www.w3.org/2005/Atom"><entry><title>UK trade bulletin</title>' +
          '<updated>2026-11-23T05:00:00Z</updated><link href="https://www.gov.uk/news/1"/></entry></feed>';
      case 'www.wto.org':
        throw new Error('Source unavailable');
      default:
        throw new Error('Non-news resource was requested');
    }
  } });
  return { catalog, hosts };
}

test('news joins eligible sources, orders dates and deduplicates records without rewriting original links', async () => {
  const { catalog, hosts } = fixture();
  const news = await catalog.news(new URLSearchParams());
  assert.equal(hosts.length, 4);
  assert.deepEqual(new Set(news.sources.map(source => source.id)),
    new Set(['un-geneva-headlines', 'eu-commission-briefings', 'wto-trade-news', 'uk-business-trade']));
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
