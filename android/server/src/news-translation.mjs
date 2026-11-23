import { execFile } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';

const targets = new Set(['zh', 'ja', 'ko', 'fr', 'de', 'es']);
const fields = new Set(['source', 'target', 'title', 'summary']);
const worker = fileURLToPath(new URL('../ops/translate-news.py', import.meta.url));
const fail = (status, message) => Object.assign(new Error(message), { status });
const validText = value => typeof value === 'string' && value.length <= 512 && !/[\x00-\x1f\x7f]/.test(value);

function runTranslation(runtime, target, texts) {
  return new Promise((resolve, reject) => {
    const child = execFile(join(runtime, 'bin', 'python'), [worker, join(runtime, 'models'), target],
      { timeout: 12_000, killSignal: 'SIGKILL', maxBuffer: 16 * 1024, windowsHide: true }, (error, stdout) => {
        if (error) return reject(error);
        try { resolve(JSON.parse(stdout)); }
        catch (invalid) { reject(invalid); }
      });
    child.stdin.on('error', reject);
    child.stdin.end(JSON.stringify(texts));
  });
}

export function newsTranslation({ runtime, translate = (target, texts) => runTranslation(runtime, target, texts) } = {}) {
  const cache = new Map();
  let active;
  return async input => {
    if (!input || typeof input !== 'object' || Object.keys(input).some(field => !fields.has(field)) ||
        input.source !== 'en' || !targets.has(input.target) || !validText(input.title) || !input.title.trim() ||
        !validText(input.summary ?? '')) throw fail(400, '请选择支持的语言并提供有效的资讯标题和摘要');
    const { target, title, summary = '' } = input;
    const texts = summary ? [title, summary] : [title];
    const key = JSON.stringify([target, texts]);
    const existing = cache.get(key);
    if (existing) return existing;
    if (active?.key === key) return active.pending;
    if (active) throw fail(429, '翻译服务正在处理另一条资讯，请稍后重试');
    const pending = Promise.resolve().then(() => translate(target, texts)).then(values => {
      if (!Array.isArray(values) || values.length !== texts.length || values.some(value =>
        typeof value !== 'string' || !value.trim() || value.length > 2048)) throw new Error('Invalid translation');
      const result = { target, title: values[0], summary: values[1] ?? '' };
      cache.set(key, result);
      if (cache.size > 256) cache.delete(cache.keys().next().value);
      return result;
    }).catch(() => { throw fail(503, '翻译暂未完成，请稍后重试'); }).finally(() => { active = undefined; });
    active = { key, pending };
    return pending;
  };
}
