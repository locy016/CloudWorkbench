import assert from 'node:assert/strict';
import { test } from 'node:test';
import { newsSourceCatalog } from '../src/news-sources.mjs';

const title = 'Trade delegations announce final agreement';
const paragraph = 'Trade delegates released the final agreement today. It includes separate chapters on tariffs, ' +
  'customs procedures, digital trade and cooperation between the participating authorities.';
const rss = (link, headline = title) => '<rss version="2.0"><channel><item>' +
  '<title>' + headline + '</title><link>' + link + '</link></item></channel></rss>';

test('full-text reader only fetches a registered article and returns ordered text blocks', async () => {
  const calls = [];
  const link = 'https://www.wto.org/news/release';
  const catalog = newsSourceCatalog({
    getResource: async () => rss(link),
    getArticlePage: async (url, options) => {
      calls.push(url.href);
      assert.deepEqual(options.hosts, ['www.wto.org']);
      return '<html><head><title>' + title + '</title></head><body><main><div id="contentarea">' +
        '<p>' + paragraph + '</p><h2>Implementation</h2><ul><li>Measures begin next year.</li></ul>' +
        '<script>externalTracker()</script><p hidden>Hidden tracking marker</p></div></main></body></html>';
    }
  });
  await assert.rejects(catalog.article(link), { status: 400 });
  await assert.rejects(catalog.article('a'.repeat(64)), { status: 404 });
  const approved = await catalog.news(new URLSearchParams({ source: 'wto-trade-news' }));
  const id = approved.articles[0].readerId;
  assert.match(id, /^[a-f0-9]{64}$/);
  const result = await catalog.article(id);
  assert.equal(result.id, id);
  assert.deepEqual(result.blocks, [
    { kind: 'paragraph', text: paragraph }, { kind: 'heading', text: 'Implementation' },
    { kind: 'bullet', text: 'Measures begin next year.' }
  ]);
  assert.equal(result.truncated, false);
  assert.deepEqual(await catalog.article(id), result);
  assert.deepEqual(calls, [link]);

  const unapproved = await catalog.news(new URLSearchParams({ source: 'un-geneva-headlines' }));
  assert.equal(unapproved.articles[0].readerId, null);
});

test('European Commission web shell is resolved through its publishing API', async () => {
  const catalog = newsSourceCatalog({
    getResource: async () => rss('https://ec.europa.eu/commission/presscorner/detail/en/statement_26_2557'),
    getArticlePage: async (url, options) => {
      assert.equal(url.origin + url.pathname, 'https://ec.europa.eu/commission/presscorner/api/documents');
      assert.deepEqual(Object.fromEntries(url.searchParams), { reference: 'STATEMENT/26/2557', language: 'en' });
      assert.equal(options.json, true);
      return {
        refCd: 'STATEMENT/26/2557',
        docuLanguageResource: { title, htmlContent: '<p>' + paragraph + '</p><p>Second paragraph.</p>' }
      };
    }
  });
  const result = await catalog.news(new URLSearchParams({ source: 'eu-commission-briefings' }));
  const body = await catalog.article(result.articles[0].readerId);
  assert.deepEqual(body.blocks.map(block => block.text), [paragraph, 'Second paragraph.']);
});

test('full text retains nested list and quote paragraphs and the article ending', async () => {
  const catalog = newsSourceCatalog({
    getResource: async () => rss('https://www.wto.org/news/release'),
    getArticlePage: async () => '<html lang="en"><head><title>' + title + '</title></head><body><div id="contentarea">' +
      '<p>' + paragraph + '</p><ul><li>First item<ul><li>Nested item</li></ul>Continued item</li></ul>' +
      '<blockquote><p>First line<br>second line</p><p>Second paragraph of the quote</p></blockquote>' +
      '<h6>Conclusion</h6><p>The last line.</p></div></body></html>'
  });
  const feed = await catalog.news(new URLSearchParams({ source: 'wto-trade-news' }));
  const body = await catalog.article(feed.articles[0].readerId);
  assert.deepEqual(body.blocks, [
    { kind: 'paragraph', text: paragraph },
    { kind: 'bullet', text: 'First item' },
    { kind: 'bullet', text: 'Nested item' },
    { kind: 'bullet', text: 'Continued item' },
    { kind: 'quote', text: 'First line second line' },
    { kind: 'quote', text: 'Second paragraph of the quote' },
    { kind: 'heading', text: 'Conclusion' },
    { kind: 'paragraph', text: 'The last line.' }
  ]);
  assert.equal(body.truncated, false);
});

test('a malformed EIA detail URL produces no reading or original link', async () => {
  const catalog = newsSourceCatalog({ getResource: async () => rss('https://www.eia.gov/todayinenergy/detail.php?id=') });
  const feed = await catalog.news(new URLSearchParams({ source: 'eia-energy-analysis' }));
  assert.equal(feed.articles[0].link, null);
  assert.equal(feed.articles[0].readerId, null);
});
