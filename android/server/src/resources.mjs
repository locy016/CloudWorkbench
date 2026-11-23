import { lookup } from 'node:dns';
import { open } from 'node:fs/promises';
import { get } from 'node:https';
import { BlockList, isIP } from 'node:net';
import { decodeResource, feedFields, isCalendarDate, parseCsv, parseFeed, resourceTypes, sourceTime } from './resource-formats.mjs';
import { aggregateNews, newsCategoryKeys } from './news-feed.mjs';
import { newsReader } from './news-reader.mjs';

const defaults = new URL('./resource-sources.json', import.meta.url);
const keyPattern = /^[a-z][a-z0-9-]{0,47}$/;
const optionPattern = /^[A-Za-z0-9._-]+(?:\/[A-Za-z0-9._-]+)?$/;
const pathPattern = /^(?:[A-Za-z0-9_-]+(?:\.[A-Za-z0-9_-]+)*)?$/;
const refinements = new Set(['q', 'from', 'to', 'export']);
const categories = [...newsCategoryKeys, 'logistics', 'other'];
const placeholder = /%7B([a-z][a-z0-9-]*)%7D|\{([a-z][a-z0-9-]*)\}/gi;
const feedCacheLimit = 6 * 60 * 60 * 1000;
const feedRetryDelay = 15_000;
const blocked = new BlockList();
for (const [address, prefix] of [
  ['0.0.0.0', 8], ['10.0.0.0', 8], ['100.64.0.0', 10], ['127.0.0.0', 8], ['169.254.0.0', 16],
  ['172.16.0.0', 12], ['192.0.0.0', 24], ['192.0.2.0', 24], ['192.168.0.0', 16], ['198.18.0.0', 15],
  ['198.51.100.0', 24], ['203.0.113.0', 24], ['224.0.0.0', 3]
]) blocked.addSubnet(address, prefix);
const fail = (status, message) => Object.assign(new Error(message), { status });

function checkUrl(value, allowFragment = false) {
  const url = new URL(value);
  if (url.protocol !== 'https:' || url.port || url.username || url.password ||
      isIP(url.hostname) || !/^(?:[a-z0-9-]+\.)+[a-z]{2,}$/i.test(url.hostname) || (!allowFragment && url.hash)) {
    throw new Error('Invalid resource URL');
  }
  return url;
}

function normalizeSource(spec) {
  const { id, title, publisher, sourceUrl, endpoint, rows, updated, language, readerHosts = [],
    format = 'json', filters = [], query = {}, limit = 50,
    exportLimit = limit, cacheSeconds = 0, category = 'other' } = spec;
  const feed = format === 'rss' || format === 'atom';
  const fields = feed ? feedFields : spec.fields;
  const dateField = feed ? 'published' : spec.dateField;
  const searchable = spec.searchable ?? format !== 'json';
  const label = value => typeof value === 'string' && value.trim().length > 0 && value.length <= 100;
  if (!keyPattern.test(id ?? '') || !label(title) || !label(publisher) || !Object.hasOwn(resourceTypes, format) ||
      !categories.includes(category) || (language !== undefined && (!feed || !/^[a-z]{2,3}$/.test(language))) ||
      !Array.isArray(readerHosts) || readerHosts.length > 4 || readerHosts.some(host =>
        typeof host !== 'string' || !/^(?:[a-z0-9-]+\.)+[a-z]{2,}$/.test(host)) ||
      (format === 'json' && (typeof rows !== 'string' || !pathPattern.test(rows))) ||
      (updated !== undefined && !pathPattern.test(updated)) || typeof searchable !== 'boolean' ||
      !Number.isInteger(cacheSeconds) || cacheSeconds < 0 || cacheSeconds > 3600 ||
      !Number.isInteger(limit) || limit < 1 || limit > 100 || !Number.isInteger(exportLimit) ||
      exportLimit < limit || exportLimit > 1000 || !Array.isArray(fields) || fields.length < 1 || fields.length > 8 ||
      !Array.isArray(filters) || filters.length > 8) throw new Error('Invalid resource definition');
  checkUrl(sourceUrl, true);
  const url = checkUrl(endpoint);
  if (url.search) throw new Error('Declare fixed query parameters separately');
  const keys = new Map();
  for (const filter of filters) {
    const type = filter.type ?? 'choice';
    if (!keyPattern.test(filter.key ?? '') || keys.has(filter.key) || refinements.has(filter.key) || !label(filter.label) ||
        !['choice', 'text'].includes(type)) throw new Error('Invalid resource filter');
    keys.set(filter.key, type);
    if (type === 'text') {
      if (!Number.isInteger(filter.maxLength) || filter.maxLength < 1 || filter.maxLength > 200) {
        throw new Error('Invalid resource text filter length');
      }
      continue;
    }
    if (!Array.isArray(filter.options) || filter.options.length < 1 || filter.options.length > 30) {
      throw new Error('Invalid resource options');
    }
    const values = new Set();
    for (const option of filter.options) {
      if (typeof option.value !== 'string' || !optionPattern.test(option.value) || option.value.length > 80 ||
          option.value.split('/').some(part => part === '.' || part === '..') ||
          values.has(option.value) || !label(option.label)) throw new Error('Invalid resource option');
      values.add(option.value);
    }
  }
  for (const token of url.pathname.matchAll(placeholder)) {
    if (keys.get(token[1] || token[2]) !== 'choice') throw new Error('Only fixed choices can fill endpoint paths');
  }
  if (query == null || Array.isArray(query) || typeof query !== 'object') throw new Error('Invalid resource query');
  for (const [name, value] of Object.entries(query)) {
    if (!/^[a-zA-Z0-9_-]{1,50}(?:\[\])?$/.test(name) || typeof value !== 'string' || value.length > 200) throw new Error('Invalid resource query');
    for (const token of value.matchAll(placeholder)) {
      if (!keys.has(token[1] || token[2])) throw new Error('Missing filter for query placeholder');
    }
  }
  const fieldKeys = new Set();
  for (const field of fields) {
    if (!keyPattern.test(field.key ?? '') || fieldKeys.has(field.key) || !label(field.label) ||
        typeof field.path !== 'string' || (format === 'csv' ? !label(field.path) : !pathPattern.test(field.path)) ||
        (field.format !== undefined && !['timestamp', 'utc-minute'].includes(field.format))) throw new Error('Invalid resource field');
    fieldKeys.add(field.key);
  }
  if (dateField !== undefined && !fields.some(field => field.key === dateField && field.format)) {
    throw new Error('Date filters require a timestamp field');
  }
  return {
    id, title, publisher, sourceUrl, url, rows, updated, fields, filters, query, limit, exportLimit, format, dateField,
    searchable, cacheSeconds, language, readerHosts,
    summary: { id, title, publisher, category, format, searchable, dateFilter: !!dateField, fullDownload: exportLimit > limit,
      filters: filters.map(({ key, label, type = 'choice', options, maxLength }) =>
      type === 'text' ? { key, label, type, maxLength } :
        { key, label, type, options: options.map(({ value, label }) => ({ value, label })) }) }
  };
}

async function readDefinitions(file) {
  const handle = await open(file, 'r');
  try {
    const info = await handle.stat();
    if (!info.isFile() || info.size > 64 * 1024) throw new Error('Invalid resource configuration size');
    const { sources } = JSON.parse(await handle.readFile('utf8'));
    if (!Array.isArray(sources) || sources.length > 40) throw new Error('Invalid resource catalog');
    const catalog = new Map();
    for (const spec of sources) {
      const source = normalizeSource(spec);
      if (catalog.has(source.id)) throw new Error('Duplicate resource source');
      catalog.set(source.id, source);
    }
    return catalog;
  } finally {
    await handle.close();
  }
}

function publicLookup(host, options, done) {
  lookup(host, { family: 4, all: true }, (error, addresses) => {
    if (error) return done(error);
    if (!addresses.length || addresses.some(({ address }) => blocked.check(address))) {
      return done(new Error('Resource address is not public'));
    }
    const { address, family } = addresses[0];
    done(null, address, family);
  });
}

function getPayload(url, format) {
  return new Promise((resolve, reject) => {
    const request = get(url, {
      family: 4, lookup: publicLookup, autoSelectFamily: false, agent: false,
      signal: AbortSignal.timeout(8000),
      headers: { Accept: resourceTypes[format].join(', '), 'Accept-Encoding': 'identity', 'User-Agent': 'CloudWorkbench/1.0' }
    }, response => {
      const contentType = response.headers['content-type'] || '';
      const mime = contentType.split(';')[0].trim().toLowerCase();
      if (response.statusCode !== 200 || (!resourceTypes[format].includes(mime) &&
          !(format === 'json' && /^application\/[a-z0-9.+-]+\+json$/.test(mime)))) {
        response.destroy();
        reject(new Error('Unexpected resource response'));
        return;
      }
      const chunks = [];
      let bytes = 0;
      response.on('data', chunk => {
        bytes += chunk.length;
        if (bytes > 2 * 1024 * 1024) {
          request.destroy(new Error('Resource response exceeds limit'));
          return;
        }
        chunks.push(chunk);
      });
      response.on('error', reject);
      response.on('end', () => {
        try { resolve(decodeResource(Buffer.concat(chunks), contentType, format)); }
        catch (error) { reject(error); }
      });
    });
    request.on('error', reject);
  });
}

function valueAt(value, path) {
  return path ? path.split('.').reduce((target, part) =>
    target && Object.hasOwn(target, part) ? target[part] : undefined, value) : value;
}

function resourceUrl(source, selected = {}) {
  const url = new URL(source.url.href);
  url.pathname = url.pathname.replace(placeholder, (_, encoded, raw) => selected[encoded || raw]);
  for (const [name, template] of Object.entries(source.query)) {
    url.searchParams.set(name, template.replace(placeholder, (_, encoded, raw) => selected[encoded || raw]));
  }
  return url;
}

function refine(parameters, source) {
  const selection = {};
  if (new Set(parameters.keys()).size !== parameters.size) throw fail(400, '筛选条件不能重复');
  const keys = new Set(source.filters.map(filter => filter.key));
  for (const [key, value] of parameters) {
    if (keys.has(key)) continue;
    if (key === 'export' && value === '1' && source.exportLimit > source.limit) continue;
    if (key === 'q' && source.searchable && value.trim() && value.length <= 100 && !/[\x00-\x1f\x7f]/.test(value)) {
      selection.q = value.trim();
    } else if (['from', 'to'].includes(key) && source.dateField && isCalendarDate(value)) {
      selection[key] = value;
    } else throw fail(400, '请检查关键词和日期筛选条件');
  }
  if (selection.from && selection.to && selection.from > selection.to) throw fail(400, '开始日期不能晚于结束日期');
  return selection;
}

function extractRows({ data: payload, feed }, source, filters, fullDownload) {
  const { format, fields, dateField } = source;
  const rows = feed?.rows ?? (format === 'csv' ? parseCsv(payload, fields) : valueAt(payload, source.rows));
  if (!Array.isArray(rows)) throw new Error('Configured data list not found');
  const keywords = filters.q?.normalize('NFKC').toLowerCase().split(/\s+/) ?? [];
  let latest = feed?.updatedAt ?? (source.updated ? sourceTime(valueAt(payload, source.updated)) : null);
  const matches = [];
  for (const row of rows) {
    const record = Object.fromEntries(fields.map(field => {
      const value = format === 'csv' ? row[field.path] : valueAt(row, field.path);
      const cell = field.format ? sourceTime(value, field.format) :
        typeof value === 'string' ? value.slice(0, 512) :
          typeof value === 'number' || typeof value === 'boolean' ? value : null;
      return [field.key, cell];
    }));
    const time = dateField ? record[dateField] : null;
    if (!feed?.updatedAt && !source.updated && time && (!latest || time > latest)) latest = time;
    const day = time?.slice(0, 10);
    if ((filters.from && (!day || day < filters.from)) || (filters.to && (!day || day > filters.to))) continue;
    if (keywords.length) {
      const text = Object.values(record).join(' ').normalize('NFKC').toLowerCase();
      if (!keywords.every(word => text.includes(word))) continue;
    }
    matches.push(record);
  }
  if (dateField) matches.sort((a, b) => (b[dateField] ?? '').localeCompare(a[dateField] ?? ''));
  const exported = [];
  let size = 0;
  for (const record of matches.slice(0, fullDownload ? source.exportLimit : source.limit)) {
    size += Buffer.byteLength(JSON.stringify(record)) + 1;
    if (size > 512 * 1024) break;
    exported.push(record);
  }
  return { rows: exported, matchedCount: matches.length, previewLimit: source.limit, updatedAt: latest };
}

export function resourceCatalog({ configFile, getResource = getPayload, getArticlePage, now = Date.now } = {}) {
  const cache = new Map();
  const reader = newsReader({ lookup: publicLookup, getArticlePage, now });
  async function response(url, source, allowStale = false) {
    const isFeed = source.format === 'rss' || source.format === 'atom';
    const load = async () => {
      const data = await getResource(url, source.format);
      const value = isFeed ? { feed: parseFeed(data, source.format, url) } : { data };
      return { ...value, fetched: now() };
    };
    if (!source.cacheSeconds) return load();
    const key = source.format + ':' + url.href;
    const entry = cache.get(key) ?? {};
    if (entry.value && now() - entry.value.fetched < source.cacheSeconds * 1000) return entry.value;
    cache.delete(key);
    cache.set(key, entry);
    if (cache.size > 36) cache.delete(cache.keys().next().value);
    const fallback = () => {
      if (allowStale && entry.value && now() - entry.value.fetched <= feedCacheLimit) {
        return { ...entry.value, stale: true };
      }
      throw entry.error;
    };
    if (entry.error && now() < entry.retryAt) return fallback();
    entry.pending ??= load().then(value => {
      entry.value = value;
      entry.error = undefined;
      return value;
    }, error => {
      entry.error = error;
      entry.retryAt = now() + (isFeed ? feedRetryDelay : 0);
      throw error;
    }).finally(() => { entry.pending = undefined; });
    return entry.pending.catch(fallback);
  }
  async function sources() {
    try {
      if (configFile) {
        try { return await readDefinitions(configFile); }
        catch (error) { if (error.code !== 'ENOENT') throw error; }
      }
      return await readDefinitions(defaults);
    } catch { throw fail(503, '资源目录暂不可用，请联系管理员检查配置'); }
  }
  return {
    async list() {
      return [...(await sources()).values()].map(({ summary }) => summary)
        .sort((first, second) => categories.indexOf(first.category) - categories.indexOf(second.category));
    },
    async news(parameters) {
      const catalog = await sources();
      const result = await aggregateNews([...catalog.values()], parameters, async source => {
        return response(resourceUrl(source), source, true);
      });
      result.articles = result.articles.map(article => ({
        ...article, readerId: reader.remember(article, catalog.get(article.sourceId))
      }));
      return result;
    },
    async article(id) {
      return reader.read(id, await sources());
    },
    async download(id, parameters) {
      const source = (await sources()).get(id);
      if (!source) throw fail(404, '资源已下线或不存在');
      const refined = refine(parameters, source);
      const selected = {};
      for (const filter of source.filters) {
        const values = parameters.getAll(filter.key);
        const valid = values.length === 1 && (filter.type === 'text' ?
          values[0].trim().length > 0 && values[0].length <= filter.maxLength && !/[\x00-\x1f\x7f]/.test(values[0]) :
          filter.options.some(option => option.value === values[0]));
        if (!valid) {
          throw fail(400, '请选择有效的资源筛选条件');
        }
        selected[filter.key] = values[0];
      }
      const url = resourceUrl(source, selected);
      try {
        const payload = await response(url, source);
        return {
          id, title: source.title, publisher: source.publisher, sourceUrl: source.sourceUrl,
          format: source.format, filters: { ...selected, ...refined }, fetchedAt: new Date(payload.fetched).toISOString(),
          columns: source.fields.map(({ key, label }) => ({ key, label })),
          ...extractRows(payload, source, refined, parameters.has('export'))
        };
      } catch { throw fail(503, '数据源暂不可达或响应格式已变更，请稍后重试'); }
    }
  };
}
