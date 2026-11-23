import { createHash } from 'node:crypto';
import { get } from 'node:https';
import { Readability } from '@mozilla/readability';
import { parseHTML } from 'linkedom';
import { decodeResource } from './resource-formats.mjs';

const maxPageBytes = 2 * 1024 * 1024;
const maxText = 80_000;
const maxBlocks = 500;
const referenceLifetime = 3 * 60 * 60 * 1000;
const cacheLifetime = 15 * 60 * 1000;
const blockKinds = new Map([
  ['P', 'paragraph'], ['H2', 'heading'], ['H3', 'heading'], ['H4', 'heading'], ['H5', 'heading'],
  ['LI', 'bullet'], ['BLOCKQUOTE', 'quote'], ['PRE', 'paragraph'], ['DT', 'heading'], ['DD', 'paragraph'],
  ['TR', 'paragraph']
]);
const blockSelector = [...blockKinds.keys()].join(',').toLowerCase();
const text = value => value?.replace(/\s+/g, ' ').trim() ?? '';
const fail = (status, message) => Object.assign(new Error(message), { status });
const unavailable = () => fail(503, '来源暂未提供可读取的正文');

function articleUrl(value, hosts, base) {
  try {
    const url = new URL(value, base);
    if (url.protocol === 'https:' && !url.port && !url.username && !url.password && hosts.includes(url.hostname)) {
      url.hash = '';
      return url;
    }
  } catch {}
  return null;
}

async function readPage(url, { hosts, lookup, json = false }) {
  const signal = AbortSignal.timeout(8_000);
  const types = json ? ['application/json'] : ['text/html', 'application/xhtml+xml'];
  for (let redirects = 0; redirects <= 2; redirects++) {
    const response = await new Promise((resolve, reject) => {
      get(url, { lookup, family: 4, autoSelectFamily: false, agent: false, signal,
        headers: { Accept: types.join(', '), 'Accept-Encoding': 'identity', 'User-Agent': 'CloudWorkbench/1.0' }
      }, resolve).on('error', reject);
    });
    if ([301, 302, 303, 307, 308].includes(response.statusCode)) {
      response.destroy();
      if (!response.headers.location) throw unavailable();
      url = articleUrl(response.headers.location, hosts, url);
      if (!url) throw unavailable();
      continue;
    }
    const type = response.headers['content-type'] ?? '';
    if (response.statusCode !== 200 || !types.includes(type.split(';')[0].trim().toLowerCase())) {
      response.destroy();
      throw unavailable();
    }
    const chunks = [];
    let size = 0;
    for await (const chunk of response) {
      size += chunk.length;
      if (size > maxPageBytes) throw unavailable();
      chunks.push(chunk);
    }
    const bytes = Buffer.concat(chunks);
    const charset = !json && !/;\s*charset\s*=/i.test(type)
      ? /<meta\b[^>]*\bcharset\s*=\s*["']?([\w.-]+)/i.exec(bytes.subarray(0, 8_192).toString('latin1'))?.[1]
      : null;
    return decodeResource(bytes, charset ? `${type}; charset=${charset}` : type, json ? 'json' : 'html');
  }
  throw unavailable();
}

function articleWords(value) {
  return new Set(value.normalize('NFKC').toLowerCase().match(/[\p{L}\p{N}]+/gu) ?? []);
}

function titleMatches(expected, actual) {
  const tokens = [...articleWords(expected)];
  const present = articleWords(actual);
  return tokens.length > 0 && tokens.filter(token => present.has(token)).length >= Math.ceil(tokens.length * 0.65);
}

function extractBlocks(container) {
  let chars = 0;
  let truncated = false;
  const blocks = [];
  for (const node of container.querySelectorAll(blockSelector)) {
    if (node.parentElement?.closest(blockSelector)) continue;
    const value = node.tagName === 'TR' ?
      [...node.querySelectorAll('th,td')].map(cell => text(cell.textContent)).filter(Boolean).join(' · ') :
      text(node.textContent);
    if (!value) continue;
    if (blocks.length === maxBlocks || chars === maxText) {
      truncated = true;
      break;
    }
    const remaining = maxText - chars;
    blocks.push({ kind: blockKinds.get(node.tagName), text: value.slice(0, remaining) });
    chars += Math.min(remaining, value.length);
    if (value.length > remaining) {
      truncated = true;
      break;
    }
  }
  return chars >= 80 && blocks.some(block => block.kind !== 'heading') ? { blocks, truncated } : null;
}

const cleanup = 'script,style,noscript,template,nav,aside,footer,form,iframe,svg,[hidden],[aria-hidden="true"],' +
  '.share-links,.social-share,.gem-c-share-links,.field--name-field-social-media';

function bodyFromHtml(html, expectedTitle, trustedTitle = null) {
  const { document } = parseHTML(html);
  const titles = [trustedTitle, document.querySelector('meta[property="og:title"]')?.getAttribute('content'),
    document.querySelector('h1')?.textContent, document.title].map(text);
  if (!titles.some(title => titleMatches(expectedTitle, title))) throw unavailable();
  document.querySelectorAll(cleanup).forEach(node => node.remove());
  if (trustedTitle) return extractBlocks(document);
  const primary = document.querySelector('.govuk-govspeak, .field--name-body, #contentarea, .ecl-editorial-article');
  if (primary) {
    const extracted = extractBlocks(primary);
    if (extracted) return extracted;
  }
  const article = new Readability(document, { maxElemsToParse: 12_000, charThreshold: 80 }).parse();
  if (!article?.content) return null;
  const cleaned = parseHTML(article.content).document;
  cleaned.querySelectorAll(cleanup).forEach(node => node.remove());
  return extractBlocks(cleaned);
}

function commissionDocument(url) {
  if (url.hostname !== 'ec.europa.eu') return null;
  const match = /^\/commission\/presscorner\/detail\/([a-z]{2})\/([a-z]{2,12})_(\d{2})_(\d+)\/?$/i.exec(url.pathname);
  if (!match) return null;
  const [, language, kind, year, number] = match;
  const reference = `${kind.toUpperCase()}/${year}/${number}`;
  const api = new URL('https://ec.europa.eu/commission/presscorner/api/documents');
  api.search = new URLSearchParams({ reference, language }).toString();
  return { api, reference };
}

export function newsReader({ lookup, getArticlePage = readPage, now = Date.now } = {}) {
  const references = new Map();
  const cache = new Map();
  const pending = new Map();

  return {
    remember(article, source) {
      const url = article.link && source && articleUrl(article.link, source.readerHosts);
      if (!url) return null;
      if ((['www.bbc.co.uk', 'www.bbc.com'].includes(url.hostname) && url.pathname.startsWith('/news/videos/')) ||
          (url.hostname === 'www.eia.gov' && url.pathname === '/todayinenergy/detail.php' && !url.searchParams.get('id'))) return null;
      const id = createHash('sha256').update(article.sourceId).update('\0').update(url.href).digest('hex');
      references.delete(id);
      references.set(id, { ...article, storedAt: now() });
      if (references.size > 640) references.delete(references.keys().next().value);
      return id;
    },
    async read(id, catalog) {
      if (!/^[0-9a-f]{64}$/.test(id ?? '')) throw fail(400, '资讯标识无效');
      const article = references.get(id);
      const source = article && catalog.get(article.sourceId);
      const url = source && articleUrl(article.link, source.readerHosts);
      if (!url || now() - article.storedAt > referenceLifetime) throw fail(404, '请刷新资讯后重新打开正文');
      const saved = cache.get(id);
      if (saved && now() - saved.createdAt < cacheLifetime) return saved.value;
      if (pending.has(id)) return pending.get(id);
      if (pending.size >= 4) throw fail(429, '正文读取请求较多，请稍后重试');
      const load = (async () => {
        const hosts = source.readerHosts;
        const commission = commissionDocument(url);
        let content;
        if (commission) {
          const document = await getArticlePage(commission.api, { hosts, lookup, json: true });
          const language = document.docuLanguageResource;
          if (document.refCd !== commission.reference || typeof language?.htmlContent !== 'string' ||
              typeof language.title !== 'string') throw unavailable();
          content = bodyFromHtml(language.htmlContent, article.title, language.title);
          if (content && typeof language.language === 'string') {
            content.language = language.language.toLowerCase().split(/[-_]/)[0];
          }
        } else {
          const html = await getArticlePage(url, { hosts, lookup });
          content = bodyFromHtml(html, article.title);
          const language = /<html\b[^>]*\blang\s*=\s*['"]?([a-z]{2,3})(?=['"\s>_-])/i.exec(html.slice(0, 8_192))?.[1];
          if (content && language) content.language = language.toLowerCase();
        }
        if (!content) throw unavailable();
        const value = { id, ...content };
        cache.delete(id);
        cache.set(id, { createdAt: now(), value });
        if (cache.size > 32) cache.delete(cache.keys().next().value);
        return value;
      })().catch(() => { throw unavailable(); }).finally(() => { pending.delete(id); });
      pending.set(id, load);
      return load;
    }
  };
}
