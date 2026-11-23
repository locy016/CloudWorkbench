import { isCalendarDate } from './resource-formats.mjs';

export const newsCategoryKeys = ['headlines', 'current', 'trade', 'society', 'humanities', 'military', 'science', 'health'];
const newsCategories = new Set(newsCategoryKeys);
const queryKeys = new Set(['source', 'q', 'category', 'from', 'to']);
const trackingKeys = new Set(['fbclid', 'gclid', 'dclid', 'msclkid', 'mc_cid', 'mc_eid']);
const titleWindow = 48 * 60 * 60 * 1000;
const maxArticles = 120;
const normalize = value => value.normalize('NFKC').toLowerCase();
const badQuery = () => Object.assign(new Error('请检查资讯分类、来源、关键词和 UTC 日期范围'), { status: 400 });

function readQuery(parameters, sources) {
  if ([...parameters.keys()].some(key => !queryKeys.has(key)) ||
      new Set(parameters.keys()).size !== parameters.size) throw badQuery();
  const sourceId = parameters.get('source');
  const category = parameters.get('category');
  const from = parameters.get('from');
  const to = parameters.get('to');
  const query = parameters.get('q') ?? '';
  if ((sourceId !== null && !sources.some(source => source.id === sourceId)) ||
      (category !== null && !newsCategories.has(category)) ||
      (from !== null && !isCalendarDate(from)) || (to !== null && !isCalendarDate(to)) ||
      (from && to && from > to) ||
      query.length > 100 || /[\x00-\x1f\x7f]/.test(query)) throw badQuery();
  return { sourceId, category, from, to, keyword: query.trim() };
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
  const { sourceId, category, from, to, keyword } = readQuery(parameters, sources);
  const selected = sources.filter(source => (sourceId === null || source.id === sourceId) &&
    (category === null || source.summary.category === category));
  const keywords = keyword ? normalize(keyword).split(/\s+/) : [];
  const results = await Promise.allSettled(selected.map(async source => {
    const { feed, stale, fetched } = await readSource(source);
    const articles = feed.rows.filter(row => {
      if (!row.title) return false;
      if (from || to) {
        const day = row.published?.slice(0, 10);
        if (row.timeKind !== 'published' || !day || (from && day < from) || (to && day > to)) return false;
      }
      if (!keywords.length) return true;
      const searchable = normalize(row.title + ' ' + row.summary);
      return keywords.every(word => searchable.includes(word));
    })
      .map(row => ({
        sourceId: source.id, publisher: source.publisher,
        title: row.title.slice(0, 512), summary: row.summary.slice(0, 512),
        time: row.published, timeKind: row.timeKind, link: row.link, language: source.language ?? null
      }));
    return { articles, cachedAt: stale ? new Date(fetched).toISOString() : null };
  }));
  const articles = [], unavailable = [], cached = [];
  for (const [index, result] of results.entries()) {
    const id = selected[index].id;
    if (result.status === 'fulfilled') {
      articles.push(...result.value.articles);
      if (result.value.cachedAt) cached.push({ id, fetchedAt: result.value.cachedAt });
    } else unavailable.push(id);
  }
  articles.sort((a, b) => (b.time ?? '').localeCompare(a.time ?? ''));
  const unique = deduplicate(articles);
  return {
    sources: sources.map(({ id, title, publisher, summary: { category } }) => ({ id, title, publisher, category })),
    articles: unique.slice(0, maxArticles), matchedCount: unique.length, unavailable, cached, requestedSourceCount: selected.length
  };
}
