import { performance } from 'node:perf_hooks';
import { gzip, constants } from 'node:zlib';

export const KEEP_ALIVE_MS = 65_000;
let compressing = 0;

// The gateway receives loopback HTTP from Nginx and existing paired clients.
export function configureHttpTransport(server) {
  server.keepAliveTimeout = KEEP_ALIVE_MS;
  if ('keepAliveTimeoutBuffer' in server) server.keepAliveTimeoutBuffer = 5_000;
  server.on('connection', socket => {
    socket.setNoDelay(true);
    socket.setKeepAlive(true, 30_000);
  });
  return server;
}

function acceptsGzip(header = '') {
  const qualities = new Map();
  for (const entry of header.toLowerCase().split(',')) {
    const [coding, ...parameters] = entry.trim().split(';');
    let quality = 1;
    for (const parameter of parameters) {
      const [name, value] = parameter.trim().split('=');
      if (name === 'q') quality = /^(?:0(?:\.\d{0,3})?|1(?:\.0{0,3})?)$/.test(value || '') ? Number(value) : 0;
    }
    qualities.set(coding.trim(), Math.min(qualities.get(coding.trim()) ?? 1, quality));
  }
  const quality = qualities.get('gzip') ?? qualities.get('*') ?? 0;
  return quality > 0 && quality >= (qualities.get('identity') ?? 0);
}

export function respondJson(req, res, status, value, { metrics, started = performance.now() } = {}) {
  const encodeStarted = performance.now();
  const body = Buffer.from(JSON.stringify(value));
  metrics?.observe('jsonEncodeMs', performance.now() - encodeStarted);
  metrics?.increment('jsonOriginalBytes', body.length);
  const finish = (bytes, compressed = false) => {
    if (res.destroyed || res.writableEnded) return;
    res.writeHead(status, {
      'Content-Type': 'application/json; charset=utf-8',
      'Cache-Control': 'no-store',
      'X-Content-Type-Options': 'nosniff',
      'Vary': 'Accept-Encoding',
      'Content-Length': bytes.length,
      'Server-Timing': `app;dur=${(performance.now() - started).toFixed(2)}`,
      ...(compressed ? { 'Content-Encoding': 'gzip' } : {}),
    });
    metrics?.increment('jsonSentBytes', bytes.length);
    res.end(bytes);
  };
  // Bound CPU/memory work; old clients and small responses retain plain JSON.
  if (status < 400 && body.length >= 1024 && compressing < 4 && acceptsGzip(req.headers['accept-encoding'])) {
    compressing++;
    const compressStarted = performance.now();
    gzip(body, { level: constants.Z_BEST_SPEED }, (error, compressed) => {
      compressing--;
      metrics?.observe('jsonGzipMs', performance.now() - compressStarted);
      if (!error && compressed.length < body.length) finish(compressed, true);
      else finish(body);
    });
  } else finish(body);
}
