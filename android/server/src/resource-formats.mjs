import { SaxesParser } from 'saxes';

export const feedTypes = {
  rss: ['application/rss+xml', 'application/xml', 'text/xml'],
  atom: ['application/atom+xml', 'application/xml', 'text/xml']
};

export function decodeResource(bytes, contentType, format) {
  const unicodeBom = bytes[0] === 0xff && bytes[1] === 0xfe ? 'utf-16le' :
    bytes[0] === 0xfe && bytes[1] === 0xff ? 'utf-16be' : undefined;
  const httpCharset = /;\s*charset\s*=\s*["']?([\w-]+)/i.exec(contentType)?.[1];
  const xmlCharset = ['rss', 'atom'].includes(format) ?
    /^<\?xml\b[^>]{0,150}\bencoding\s*=\s*["']([\w-]+)/i.exec(bytes.subarray(0, 180).toString('latin1'))?.[1] :
    undefined;
  const text = new TextDecoder(unicodeBom || httpCharset || xmlCharset || 'utf-8', { fatal: true }).decode(bytes);
  return format === 'json' ? JSON.parse(text) : text;
}

export function sourceTime(value) {
  if (typeof value !== 'string' || !value.trim()) return null;
  const input = value.trim().replace(/\s(CET|CEST)$/, (_, zone) => zone === 'CEST' ? ' +0200' : ' +0100');
  const date = new Date(input);
  return Number.isFinite(+date) ? date.toISOString() : null;
}

export function isCalendarDate(value) {
  return /^\d{4}-\d{2}-\d{2}$/.test(value) && sourceTime(value)?.slice(0, 10) === value;
}

function cleanText(text = '') {
  return text.replace(/<(script|style)\b[^<>]*>[\s\S]*?<\/\1\s*>/gi, ' ')
    .replace(/<[^<>]*>|&nbsp;/gi, ' ').replace(/\s+/g, ' ').trim();
}

function linkUrl(text, endpoint) {
  if (!text?.trim()) return null;
  try {
    const url = new URL(text.trim(), endpoint);
    if (url.hostname === 'www.eia.gov' && url.pathname === '/todayinenergy/detail.php' &&
        !/^\d+$/.test(url.searchParams.get('id') ?? '')) return null;
    return ['https:', 'http:'].includes(url.protocol) && !url.username && !url.password ? url.href : null;
  } catch { return null; }
}

export function parseFeed(xml, format, endpoint) {
  const atom = format === 'atom';
  const xmlParser = new SaxesParser({ xmlns: true });
  const names = [];
  const rows = [];
  let entry, depth, capture, root;
  xmlParser.on('doctype', () => { throw new Error('Feed DTD is not allowed'); });
  xmlParser.on('opentag', tag => {
    const parent = names.at(-1);
    names.push(tag.local);
    const level = names.length;
    if (level === 1) {
      root = tag.local;
      if (atom ? root !== 'feed' || tag.uri !== 'http://www.w3.org/2005/Atom' :
        root !== 'rss' && (root !== 'RDF' || tag.uri !== 'http://www.w3.org/1999/02/22-rdf-syntax-ns#')) {
        throw new Error('Unexpected feed root');
      }
    }
    const isEntry = atom ? level === 2 && tag.local === 'entry' :
      tag.local === 'item' && (root === 'rss' ? level === 3 && parent === 'channel' : level === 2);
    if (isEntry) {
      entry = {};
      depth = level;
      return;
    }
    if (entry && level === depth + 1) {
      const name = tag.local;
      if (atom && name === 'link') {
        const href = tag.attributes.href?.value;
        const rel = tag.attributes.rel?.value ?? 'alternate';
        if (href && (rel === 'alternate' || (rel === 'self' && !entry.link))) entry.link = href;
      }
      if (['title', 'description', 'summary', 'pubDate', 'date', 'published', 'updated', 'sent', 'link'].includes(name)) {
        capture = { target: entry, name, level, text: '' };
      }
    }
    if (level > 64) throw new Error('Feed nesting limit exceeded');
  });
  const collect = value => {
    if (capture && capture.text.length < 8192) capture.text += value.slice(0, 8192 - capture.text.length);
  };
  xmlParser.on('text', collect);
  xmlParser.on('cdata', collect);
  xmlParser.on('closetag', () => {
    if (capture && names.length === capture.level) {
      if (capture.text.trim()) capture.target[capture.name] = capture.text;
      capture = undefined;
    }
    if (entry && names.length === depth) {
      if (rows.length >= 10000) throw new Error('Feed entry limit exceeded');
      const explicitTime = [entry.published, entry.pubDate, entry.date, entry.sent].map(value => sourceTime(value)).find(Boolean);
      const updatedTime = sourceTime(entry.updated);
      rows.push({
        title: cleanText(entry.title),
        summary: cleanText(entry.description || entry.summary),
        published: explicitTime ?? updatedTime,
        timeKind: explicitTime ? 'published' : updatedTime ? 'updated' : null,
        link: linkUrl(entry.link, endpoint)
      });
      entry = undefined;
    }
    names.pop();
  });
  xmlParser.write(xml).close();
  if (!root) throw new Error('Feed did not contain a document');
  return { rows };
}
