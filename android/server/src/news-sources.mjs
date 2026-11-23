import { lookup } from 'node:dns';
import { open } from 'node:fs/promises';
import { get } from 'node:https';
import { BlockList, isIP } from 'node:net';
import { decodeResource, feedTypes, parseFeed } from './resource-formats.mjs';
import { aggregateNews, newsCategoryKeys } from './news-feed.mjs';
import { newsReader } from './news-reader.mjs';

const defaults = new URL('./resource-sources.json', import.meta.url);
const hostname = /^(?:[a-z0-9-]+\.)+[a-z]{2,}$/i;
const placeholder = /%7b|[{}]/i;
const feedCacheLimit = 6 * 60 * 60 * 1000;
const feedRetryDelay = 15_000;
const blocked = new BlockList();
for (const [address, prefix] of [
  ['0.0.0.0', 8], ['10.0.0.0', 8], ['100.64.0.0', 10], ['127.0.0.0', 8], ['169.254.0.0', 16],
  ['172.16.0.0', 12], ['192.0.0.0', 24], ['192.0.2.0', 24], ['192.168.0.0', 16], ['198.18.0.0', 15],
  ['198.51.100.0', 24], ['203.0.113.0', 24], ['224.0.0.0', 3]
]) blocked.addSubnet(address, prefix);

function checkUrl(value, allowFragment = false) {
  const url = new URL(value);
  if (url.protocol !== 'https:' || url.port || url.username || url.password ||
      isIP(url.hostname) || !hostname.test(url.hostname) || (!allowFragment && url.hash)) {
    throw new Error('Invalid news source URL');
  }
  return url;
}

function normalizeSource(spec) {
  const { id, title, publisher, sourceUrl, endpoint, language, readerHosts = [], format,
    query = {}, cacheSeconds = 0, category } = spec;
  const label = value => typeof value === 'string' && value.trim().length > 0 && value.length <= 100;
  if (typeof id !== 'string' || !/^[a-z][a-z0-9-]{0,47}$/.test(id) || !label(title) || !label(publisher) ||
      !newsCategoryKeys.includes(category) ||
      (language !== undefined && (typeof language !== 'string' || !/^[a-z]{2,3}$/.test(language))) ||
      !Array.isArray(readerHosts) || readerHosts.length > 4 || readerHosts.some(host =>
        typeof host !== 'string' || host !== host.toLowerCase() || !hostname.test(host)) ||
      !Number.isInteger(cacheSeconds) || cacheSeconds < 0 || cacheSeconds > 3600 ||
      !query || Array.isArray(query) || typeof query !== 'object') throw new Error('Invalid news source');
  checkUrl(sourceUrl, true);
  const url = checkUrl(endpoint);
  if (url.search || placeholder.test(url.pathname)) throw new Error('Declare fixed news query parameters separately');
  for (const [name, value] of Object.entries(query)) {
    if (!/^[a-zA-Z0-9_-]{1,50}(?:\[\])?$/.test(name) || typeof value !== 'string' || value.length > 200 ||
        placeholder.test(value)) throw new Error('Invalid news source query');
    url.searchParams.set(name, value);
  }
  return { id, title, publisher, url, category, format, cacheSeconds, language, readerHosts };
}

async function readDefinitions(file) {
  const handle = await open(file, 'r');
  try {
    const info = await handle.stat();
    if (!info.isFile() || info.size > 64 * 1024) throw new Error('Invalid news source configuration size');
    const { sources } = JSON.parse(await handle.readFile('utf8'));
    if (!Array.isArray(sources) || sources.length > 40) throw new Error('Invalid news source catalog');
    const catalog = new Map();
    for (const spec of sources) {
      // An existing custom catalog can still contain sources from the retired data tool.
      if (!Object.hasOwn(feedTypes, spec.format) || !spec.category || ['logistics', 'other'].includes(spec.category) ||
          (Array.isArray(spec.filters) && spec.filters.length > 0)) continue;
      if (spec.filters !== undefined && !Array.isArray(spec.filters)) throw new Error('Invalid news source');
      const source = normalizeSource(spec);
      if (catalog.has(source.id)) throw new Error('Duplicate news source');
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
      return done(new Error('News address is not public'));
    }
    const { address, family } = addresses[0];
    done(null, address, family);
  });
}

function getFeed(url, format) {
  return new Promise((resolve, reject) => {
    const types = feedTypes[format];
    const request = get(url, {
      family: 4, lookup: publicLookup, autoSelectFamily: false, agent: false,
      signal: AbortSignal.timeout(8000),
      headers: { Accept: types.join(', '), 'Accept-Encoding': 'identity', 'User-Agent': 'CloudWorkbench/1.0' }
    }, response => {
      const contentType = response.headers['content-type'] || '';
      if (response.statusCode !== 200 || !types.includes(contentType.split(';')[0].trim().toLowerCase())) {
        response.destroy();
        reject(new Error('Unexpected news source response'));
        return;
      }
      const chunks = [];
      let bytes = 0;
      response.on('data', chunk => {
        bytes += chunk.length;
        if (bytes > 2 * 1024 * 1024) {
          request.destroy(new Error('News source response exceeds limit'));
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

export function newsSourceCatalog({ configFile, getResource = getFeed, getArticlePage, now = Date.now } = {}) {
  const cache = new Map();
  const reader = newsReader({ lookup: publicLookup, getArticlePage, now });
  async function response({ url, format, cacheSeconds }) {
    const load = async () => {
      const data = await getResource(url, format);
      return { feed: parseFeed(data, format, url), fetched: now() };
    };
    if (!cacheSeconds) return load();
    const key = format + ':' + url.href;
    const entry = cache.get(key) ?? {};
    if (entry.value && now() - entry.value.fetched < cacheSeconds * 1000) return entry.value;
    cache.delete(key);
    cache.set(key, entry);
    if (cache.size > 36) cache.delete(cache.keys().next().value);
    const fallback = () => {
      if (entry.value && now() - entry.value.fetched <= feedCacheLimit) return { ...entry.value, stale: true };
      throw entry.error;
    };
    if (entry.error && now() < entry.retryAt) return fallback();
    entry.pending ??= load().then(value => {
      entry.value = value;
      entry.error = undefined;
      return value;
    }, error => {
      entry.error = error;
      entry.retryAt = now() + feedRetryDelay;
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
    } catch { throw Object.assign(new Error('资讯来源配置暂不可用，请联系管理员检查配置'), { status: 503 }); }
  }
  return {
    async news(parameters) {
      const catalog = await sources();
      const result = await aggregateNews([...catalog.values()], parameters, response);
      result.articles = result.articles.map(article => ({
        ...article, readerId: reader.remember(article, catalog.get(article.sourceId))
      }));
      return result;
    },
    async article(id) {
      return reader.read(id, await sources());
    }
  };
}
