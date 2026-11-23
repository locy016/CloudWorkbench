import { execFile } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';

const targets = new Set(['zh', 'ja', 'ko', 'fr', 'de', 'es']);
const articleFields = new Set(['title', 'summary']);
const singleFields = new Set(['source', 'target', ...articleFields]);
const batchFields = new Set(['source', 'target', 'articles']);
const batchSize = 3;
const worker = fileURLToPath(new URL('../ops/translate-news.py', import.meta.url));
const fail = (status, message) => Object.assign(new Error(message), { status });
const validText = value => typeof value === 'string' && value.length <= 512 && !/[\x00-\x1f\x7f]/.test(value);
const validFields = (value, fields) => value && typeof value === 'object' && !Array.isArray(value) &&
  Object.keys(value).every(field => fields.has(field));
const badInput = () => fail(400, '请选择支持的语言并提供有效的资讯标题和摘要');

function runTranslation(runtime, target, texts) {
  return new Promise((resolve, reject) => {
    const child = execFile(join(runtime, 'bin', 'python'), [worker, join(runtime, 'models'), target],
      { timeout: 18_000, killSignal: 'SIGKILL', maxBuffer: 64 * 1024, windowsHide: true }, (error, stdout) => {
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

  async function translateArticles(source, target, articles) {
    if (source !== 'en' || !targets.has(target) || !Array.isArray(articles) ||
        articles.length < 1 || articles.length > batchSize) throw badInput();
    const jobs = articles.map(article => {
      if (!validFields(article, articleFields) || !validText(article.title) || !article.title.trim() ||
          !validText(article.summary ?? '')) throw badInput();
      const texts = article.summary?.trim() ? [article.title, article.summary] : [article.title];
      return { key: JSON.stringify([target, texts]), texts };
    });
    const results = new Map();
    const missing = new Map();
    for (const { key, texts } of jobs) {
      const saved = cache.get(key);
      if (saved) {
        cache.delete(key);
        cache.set(key, saved);
        results.set(key, saved);
      } else {
        missing.set(key, texts);
      }
    }
    if (missing.size) {
      if (active && [...missing.keys()].some(key => !active.keys.has(key))) {
        throw fail(429, '翻译服务正在处理另一组资讯，请稍后重试');
      }
      if (!active) {
        const texts = [...missing.values()].flat();
        const pending = Promise.resolve().then(() => translate(target, texts)).then(values => {
          if (!Array.isArray(values) || values.length !== texts.length || values.some(value =>
            typeof value !== 'string' || !value.trim() || value.length > 2048)) throw new Error('Invalid translation');
          let index = 0;
          const translated = new Map();
          for (const [key, inputs] of missing) {
            const value = { title: values[index], summary: inputs.length === 2 ? values[index + 1] : '' };
            translated.set(key, value);
            cache.set(key, value);
            if (cache.size > 256) cache.delete(cache.keys().next().value);
            index += inputs.length;
          }
          return translated;
        }).catch(() => { throw fail(503, '翻译暂未完成，请稍后重试'); }).finally(() => { active = undefined; });
        active = { keys: new Set(missing.keys()), pending };
      }
      const translated = await active.pending;
      for (const key of missing.keys()) results.set(key, translated.get(key));
    }
    return jobs.map(({ key }) => results.get(key));
  }

  return {
    async one(input) {
      if (!validFields(input, singleFields)) throw badInput();
      const { source, target, title, summary } = input;
      const [result] = await translateArticles(source, target, [{ title, summary }]);
      return { target, ...result };
    },
    async batch(input) {
      if (!validFields(input, batchFields)) throw badInput();
      const { source, target, articles } = input;
      return { target, articles: await translateArticles(source, target, articles) };
    }
  };
}
