import { randomBytes } from 'node:crypto';
import https from 'node:https';
import { performance } from 'node:perf_hooks';

export const speedProbeHost = 'speed-probe.workbench.invalid';
const targetHost = 'speed.cloudflare.com';
const downloadBytes = 5 * 1024 * 1024;
const uploadBytes = 1024 * 1024;
const timeoutMs = 20_000;
const payload = randomBytes(downloadBytes);

export function createSpeedProbe(resolve, request = https.request) {
  let running = 0;

  // The loopback HTTP proxy reaches this host only inside the existing device-authorized tunnel.
  return async (req, res, url) => {
    const route = /^\/probe-v1\/(device|target)\/(latency|download|upload)$/.exec(url.pathname);
    if (!route || url.port || url.search || url.hash || req.headers.origin) return reply(res, 404, { error: 'probe route' });
    const [, segment, step] = route;
    const uploadFromDevice = segment === 'device' && step === 'upload';
    if (req.method !== (uploadFromDevice ? 'POST' : 'GET')) return reply(res, 405, { error: 'probe method' });
    if (req.headers['transfer-encoding'] || Number(req.headers['content-length'] ?? 0) !== (uploadFromDevice ? uploadBytes : 0)) {
      return reply(res, 400, { error: 'probe size' });
    }
    if (running >= 4) return reply(res, 429, { error: 'probe busy' });
    running++;
    const abort = new AbortController();
    res.once('close', () => { running--; abort.abort(); });
    if (segment === 'target') return reply(res, 200, await measureTarget(step, resolve, request, abort.signal));
    if (step === 'latency') {
      res.writeHead(204, { 'cache-control': 'no-store' });
      return res.end();
    }
    if (step === 'download') {
      res.writeHead(200, {
        'content-type': 'application/octet-stream', 'content-length': payload.length, 'cache-control': 'no-store'
      });
      const deadline = setTimeout(() => res.destroy(), timeoutMs + 2000).unref();
      res.once('close', () => clearTimeout(deadline));
      return res.end(payload);
    }
    // Count the bytes actually received before acknowledging the upload.
    let bytes = 0;
    const deadline = setTimeout(() => req.destroy(), timeoutMs + 2000).unref();
    try {
      for await (const chunk of req) {
        bytes += chunk.length;
        if (bytes > uploadBytes) return reply(res, 400, { error: 'probe size' });
      }
      return reply(res, bytes === uploadBytes ? 200 : 400, { bytes });
    } finally {
      clearTimeout(deadline);
    }
  };
}

async function measureTarget(step, resolve, request, clientSignal) {
  const started = performance.now();
  const deadline = AbortSignal.timeout(timeoutMs);
  const signal = AbortSignal.any([clientSignal, deadline]);
  let bytes = 0;
  const send = step === 'upload';
  const amount = step === 'download' ? downloadBytes : 0;
  const metrics = (status, error) => {
    const milliseconds = Math.max(0.001, performance.now() - started);
    return { status, milliseconds, bytes: bytes || null, mbps: bytes ? bytes * 0.008 / milliseconds : null, error };
  };
  try {
    const response = await new Promise((resolveResponse, reject) => {
      const outgoing = request({
        hostname: targetHost, port: 443, family: 4, servername: targetHost,
        // Reuse the existing DNS policy and pin the checked public IPv4 to the TLS request.
        lookup: (hostname, _, callback) => resolve(hostname, 443).then(ip => callback(null, ip, 4), callback),
        method: send ? 'POST' : 'GET', path: send ? '/__up' : '/__down?bytes=' + amount,
        headers: send ? { 'content-type': 'application/octet-stream', 'content-length': uploadBytes } : {},
        agent: false, signal
      }, resolveResponse);
      outgoing.once('error', reject);
      outgoing.end(send ? payload.subarray(0, uploadBytes) : undefined);
    });
    if (response.statusCode < 200 || response.statusCode >= 300) {
      response.destroy();
      return metrics('failed', 'HTTP ' + response.statusCode);
    }
    if (step === 'latency' || send) {
      response.destroy();
      if (send) bytes = uploadBytes;
      return metrics('ok');
    }
    for await (const chunk of response) {
      bytes += Math.min(chunk.length, amount - bytes);
      if (bytes === amount) break;
    }
    return metrics(bytes === amount ? 'ok' : bytes ? 'partial' : 'failed', bytes === amount ? undefined : '数据提前结束');
  } catch {
    return metrics(bytes ? 'partial' : 'failed', deadline.aborted ? '超时' : '目标连接失败');
  }
}

function reply(res, status, data) {
  if (res.destroyed) return;
  const body = JSON.stringify(data);
  res.writeHead(status, {
    'content-type': 'application/json', 'content-length': Buffer.byteLength(body), 'cache-control': 'no-store'
  });
  res.end(body);
}
