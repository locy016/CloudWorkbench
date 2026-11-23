import { parseFeed } from './resource-formats.mjs';

const newsCategories = new Set(['headlines', 'current', 'trade']);
const trackingKeys = new Set(['fbclid', 'gclid', 'dclid', 'msclkid', 'mc_cid', 'mc_eid']);
const titleWindow = 48 * 60 * 60 * 1000;
const maxArticles = 120;
const normalize = value => value.normalize('NFKC').toLowerCase();
const badQuery = () => Object.assign(new Error('请检查资讯关键词或选择已登记的来源'), { status: 400 });

function readQuery(parameters, sources) {
  if ([...parameters.keys()].some(key => key !== 'source' && key !== 'q') ||
      new Set(parameters.keys()).size !== parameters.size) throw badQuery();
  const sourceId = parameters.get('source');
  const query = parameters.get('q') ?? '';
  if ((sourceId !== null && !sources.some(source => source.id === sourceId)) ||
      query.length > 100 || /[\x00-\x1f\x7f]/.test(query)) throw badQuery();
  return { sourceId, keyword: query.trim() };
}

function addressKey(link) {
  if (!link) return null;
  const address = new URL(link);
  for (const name of [...address.searchParams.keys()]) {
    const key = name.toLowerCase();
    if (key.startsWith('utm_') || trackingKeys.has(key)) address.searchParams.delete(name);
  }
  address.searchParams.sort();
  return address.host + (address.pathname.replace(/\/$/, '') || '/') + address.search;
}

function deduplicate(sorted) {
  const addresses = new Set();
  const titleDates = new Map();
  return sorted.filter(article => {
    const address = addressKey(article.link);
    if (address && addresses.has(address)) return false;
    if (address) addresses.add(address);
    const title = normalize(article.title).replace(/[^\p{L}\p{N}]/gu, '');
    if (title.length < 16 || !article.time) return true;
    const time = Date.parse(article.time);
    const last = titleDates.get(title);
    if (last !== undefined && last - time <= titleWindow) return false;
    titleDates.set(title, time);
    return true;
  });
}

export async function aggregateNews(catalog, parameters, readSource) {
  const sources = catalog.filter(source => newsCategories.has(source.summary.category) &&
    ['rss', 'atom'].includes(source.format) && source.filters.length === 0);
  const { sourceId, keyword } = readQuery(parameters, sources);
  const selected = sourceId === null ? sources : sources.filter(source => source.id === sourceId);
  const keywords = keyword ? normalize(keyword).split(/\s+/) : [];
  const results = await Promise.allSettled(selected.map(async source => {
    const { data, url } = await readSource(source);
    const feed = parseFeed(data, source.format, url);
    return feed.rows.filter(row => {
      if (!row.title) return false;
      if (!keywords.length) return true;
      const searchable = normalize(row.title + ' ' + row.summary);
      return keywords.every(word => searchable.includes(word));
    })
      .map(row => ({
        sourceId: source.id, publisher: source.publisher,
        title: row.title.slice(0, 512), summary: row.summary.slice(0, 512),
        time: row.published, timeKind: row.timeKind, link: row.link
      }));
  }));
  const articles = [], unavailable = [];
  for (const [index, result] of results.entries()) {
    if (result.status === 'fulfilled') articles.push(...result.value);
    else unavailable.push(selected[index].id);
  }
  articles.sort((a, b) => (b.time ?? '').localeCompare(a.time ?? ''));
  const unique = deduplicate(articles);
  return {
    sources: sources.map(({ id, title, publisher }) => ({ id, title, publisher })),
    articles: unique.slice(0, maxArticles), matchedCount: unique.length, unavailable
  };
}
