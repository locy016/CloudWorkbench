import { open, readFile, stat } from 'node:fs/promises';
import { join } from 'node:path';
import { pipeline } from 'node:stream/promises';

const fail = (status, message) => Object.assign(new Error(message), { status });
const digest = /^[a-f0-9]{64}$/;
const APK_TYPE = 'application/vnd.android.package-archive';

function byteRange(header, size) {
  const match = /^bytes=(\d{0,16})-(\d{0,16})$/.exec(header);
  if (!match || (!match[1] && !match[2])) return null;
  const start = match[1] ? Number(match[1]) : Math.max(0, size - Number(match[2]));
  const end = match[1] && match[2] ? Math.min(size - 1, Number(match[2])) : size - 1;
  return Number.isSafeInteger(start) && Number.isSafeInteger(end) && start <= end ? { start, end } : null;
}

export function validateAppUpdate(update) {
  return update && update.packageId === 'cn.xux.cloudworkbench' &&
    Number.isSafeInteger(update.versionCode) && update.versionCode > 0 && update.versionCode <= 2100000000 &&
    typeof update.versionName === 'string' && /^[0-9A-Za-z][0-9A-Za-z.+-]{0,39}$/.test(update.versionName) &&
    typeof update.sha256 === 'string' && digest.test(update.sha256) &&
    typeof update.certificate === 'string' && digest.test(update.certificate) &&
    Number.isSafeInteger(update.size) && update.size > 0 && update.size <= 1024 ** 3 &&
    typeof update.notes === 'string' && update.notes.length <= 2000;
}

export async function readAppUpdate(directory) {
  let metadata;
  try {
    metadata = JSON.parse(await readFile(join(directory, 'current.json'), 'utf8'));
  } catch (error) {
    if (error.code === 'ENOENT') return null;
    if (error instanceof SyntaxError) throw fail(503, '在线更新清单无效');
    throw error;
  }
  if (!validateAppUpdate(metadata)) throw fail(503, '在线更新清单无效');
  return metadata;
}

export class Releases {
  constructor(data) {
    this.data = data;
  }
  async file(req, res, file, expectedSize, sha256) {
    const handle = await open(file, 'r').catch(() => { throw fail(404, '文件尚未准备好'); });
    try {
      const s = await handle.stat();
      if (!s.isFile() || s.size !== expectedSize) {
        throw fail(503, '安装包与发布清单不符');
      }
      const etag = `"${sha256}"`;
      const headers = { 'Content-Type': APK_TYPE, 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff',
        ETag: etag, 'Accept-Ranges': 'bytes' };
      let range;
      if (req.headers.range && (!req.headers['if-range'] || req.headers['if-range'] === etag)) {
        range = byteRange(req.headers.range, s.size);
        if (!range) {
          res.writeHead(416, { ...headers, 'Content-Range': `bytes */${s.size}`, 'Content-Length': 0 });
          res.end();
          return;
        }
        headers['Content-Range'] = `bytes ${range.start}-${range.end}/${s.size}`;
      }
      headers['Content-Length'] = range ? range.end - range.start + 1 : s.size;
      res.writeHead(range ? 206 : 200, headers);
      await pipeline(handle.createReadStream({ autoClose: false, ...range }), res);
    } finally {
      await handle.close();
    }
  }
  async handle(req, res, path, respond) {
    if (req.method !== 'GET') return false;
    const download = path.match(/^\/v1\/app-update\/([a-f0-9]{64})\/apk$/);
    if (path === '/v1/app-update' || download) {
      const directory = join(this.data, 'app-updates');
      const update = await readAppUpdate(directory);
      if (download) {
        if (!update || update.sha256 !== download[1]) throw fail(404, '此版本已撤下，请重新检查更新');
        await this.file(req, res, join(directory, update.sha256 + '.apk'), update.size, update.sha256);
      } else {
        if (update) {
          const apk = await stat(join(directory, update.sha256 + '.apk')).catch(() => null);
          if (!apk?.isFile() || apk.size !== update.size) throw fail(503, '安装包尚未准备好');
        }
        respond(200, { update });
      }
      return true;
    }
    return false;
  }
}
