import { lookup } from 'node:dns';
import { open } from 'node:fs/promises';
import { get } from 'node:https';
import { BlockList, isIP } from 'node:net';

const defaults = new URL('./resource-sources.json', import.meta.url);
const keyPattern = /^[a-z][a-z0-9-]{0,47}$/;
const optionPattern = /^[A-Za-z0-9._-]+(?:\/[A-Za-z0-9._-]+)?$/;
const pathPattern = /^(?:[A-Za-z0-9_-]+(?:\.[A-Za-z0-9_-]+)*)?$/;
const placeholder = /%7B([a-z][a-z0-9-]*)%7D|\{([a-z][a-z0-9-]*)\}/gi;
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
  const { id, title, publisher, sourceUrl, endpoint, rows, fields, updated, filters = [], query = {}, limit = 50 } = spec;
  const label = value => typeof value === 'string' && value.trim().length > 0 && value.length <= 100;
  if (!keyPattern.test(id ?? '') || !label(title) || !label(publisher) || typeof rows !== 'string' ||
      !pathPattern.test(rows) || (updated !== undefined && !pathPattern.test(updated)) ||
      !Number.isInteger(limit) || limit < 1 || limit > 100 || !Array.isArray(fields) || fields.length < 1 || fields.length > 8 ||
      !Array.isArray(filters) || filters.length > 8) throw new Error('Invalid resource definition');
  checkUrl(sourceUrl, true);
  const url = checkUrl(endpoint);
  if (url.search) throw new Error('Declare fixed query parameters separately');
  const keys = new Map();
  for (const filter of filters) {
    const type = filter.type ?? 'choice';
    if (!keyPattern.test(filter.key ?? '') || keys.has(filter.key) || !label(filter.label) ||
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
    if (!/^[a-zA-Z0-9_-]{1,50}$/.test(name) || typeof value !== 'string' || value.length > 200) throw new Error('Invalid resource query');
    for (const token of value.matchAll(placeholder)) {
      if (!keys.has(token[1] || token[2])) throw new Error('Missing filter for query placeholder');
    }
  }
  const fieldKeys = new Set();
  for (const field of fields) {
    if (!keyPattern.test(field.key ?? '') || fieldKeys.has(field.key) || !label(field.label) ||
        typeof field.path !== 'string' || !pathPattern.test(field.path) ||
        (field.format !== undefined && field.format !== 'timestamp')) throw new Error('Invalid resource field');
    fieldKeys.add(field.key);
  }
  return {
    id, title, publisher, sourceUrl, url, rows, updated, fields, filters, query, limit,
    summary: { id, title, publisher, filters: filters.map(({ key, label, type = 'choice', options, maxLength }) =>
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

function getJson(url) {
  return new Promise((resolve, reject) => {
    const request = get(url, {
      family: 4, lookup: publicLookup, autoSelectFamily: false, agent: false,
      signal: AbortSignal.timeout(8000),
      headers: { Accept: 'application/json, application/geo+json', 'User-Agent': 'CloudWorkbench/1.0' }
    }, response => {
      if (response.statusCode !== 200 ||
          !/^application\/(?:json|[a-z0-9.+-]+\+json)(?:\s*;|$)/i.test(response.headers['content-type'] || '')) {
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
        try { resolve(JSON.parse(Buffer.concat(chunks).toString('utf8'))); }
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

function sourceTime(value) {
  if (typeof value !== 'number' && typeof value !== 'string') return null;
  const date = new Date(value);
  return Number.isFinite(+date) ? date.toISOString() : null;
}

function extractRows(payload, source) {
  const rows = valueAt(payload, source.rows);
  if (!Array.isArray(rows)) throw new Error('Configured data list not found');
  return rows.slice(0, source.limit).map(row => Object.fromEntries(source.fields.map(field => {
    const value = valueAt(row, field.path);
    const cell = field.format === 'timestamp' ? sourceTime(value) :
      typeof value === 'string' ? value.slice(0, 512) :
        typeof value === 'number' || typeof value === 'boolean' ? value : null;
    return [field.key, cell];
  })));
}

export function resourceCatalog({ configFile, getResource = getJson, now = Date.now } = {}) {
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
    async list() { return [...(await sources()).values()].map(({ summary }) => summary); },
    async download(id, parameters) {
      const source = (await sources()).get(id);
      if (!source) throw fail(404, '资源已下线或不存在');
      if (parameters.size !== source.filters.length) throw fail(400, '请选择有效的资源筛选条件');
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
      const url = new URL(source.url.href);
      url.pathname = url.pathname.replace(placeholder, (_, encoded, raw) => selected[encoded || raw]);
      for (const [name, template] of Object.entries(source.query)) {
        url.searchParams.set(name, template.replace(placeholder, (_, encoded, raw) => selected[encoded || raw]));
      }
      try {
        const payload = await getResource(url);
        return {
          id, title: source.title, publisher: source.publisher, sourceUrl: source.sourceUrl,
          filters: selected, fetchedAt: new Date(now()).toISOString(),
          updatedAt: source.updated ? sourceTime(valueAt(payload, source.updated)) : null,
          columns: source.fields.map(({ key, label }) => ({ key, label })),
          rows: extractRows(payload, source)
        };
      } catch { throw fail(503, '数据源暂不可达或响应格式已变更，请稍后重试'); }
    }
  };
}
