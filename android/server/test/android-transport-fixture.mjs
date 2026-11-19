// Local USB acceptance fixture. Start this with a one-off test certificate and use adb reverse.
import { readFileSync } from 'node:fs';
import { EventEmitter } from 'node:events';
import { createServer as createHttpsServer } from 'node:https';
import { createServer, request as httpRequest } from 'node:http';
import net from 'node:net';
import { once } from 'node:events';
import { Gateway } from '../src/gateway.mjs';
import { Store } from '../src/store.mjs';
import { Events } from '../src/events.mjs';
import { browserProxy } from '../src/browser-proxy.mjs';
import { browserTunnel } from '../src/browser-tunnel.mjs';

const [pfx, passphrase] = process.argv.slice(2);
if (!pfx || !passphrase) throw new Error('Pass a test PKCS#12 path and its password');
const listen = async (server, port = 0) => {
  server.listen(port, '127.0.0.1');
  await once(server, 'listening');
  return server.address().port;
};
const token = 'usb-fixture-access-token-for-local-use-only';
const sshMobile = req => req.headers.authorization === 'Bearer sshm1.android-usb-fixture' ? 'ssh-mobile:fixture' : null;
const codex = Object.assign(new EventEmitter(), { close() {} });
const store = new Store(':memory:');
const events = new Events({ heartbeatMs: 1500 });
const gateway = new Gateway({ codex, store, token, events, sshMobile, cwd: '.' });
gateway.collectStatus = async () => ({ running: 0, version: 'fixture' });
const originPort = await listen(createServer((req, res) => {
  if (req.url !== '/fixture-image' || req.headers.authorization || req.headers['proxy-authorization']) {
    res.writeHead(403).end();
    return;
  }
  res.writeHead(200, { 'Content-Type': 'image/png' }).end(Buffer.from('WORKBENCH', 'ascii'));
}));
const proxy = browserProxy({
  lookup: async () => [{ address: '1.1.1.1' }],
  request: (options, callback) => httpRequest({ ...options, host: '127.0.0.1', port: originPort }, callback)
});
const proxyPort = await listen(proxy);
browserTunnel(gateway.http, { authorize: gateway.authorizeRequest.bind(gateway), port: proxyPort });
const gatewayPort = await listen(gateway.http);
const stats = { activeFeeds: 0, newFeeds: 0, activeTunnels: 0 };
const updateFixture = Buffer.from('workbench APK resume fixture');
const updateDigest = 'b'.repeat(64);
const parallelDigest = 'd'.repeat(64);
const parallelFixture = Buffer.alloc(16 * 1024 * 1024);
for (let i = 0; i < parallelFixture.length; i++) parallelFixture[i] = (i >>> 16) ^ (i & 255);
let downloadInterrupted = false;
let seq = 0;
const tlsOptions = { pfx: readFileSync(pfx), passphrase };
const ingress = createHttpsServer(tlsOptions, (req, res) => {
  if (req.url === '/v1/app-update/' + parallelDigest + '/apk') {
    if (!sshMobile(req)) {
      res.writeHead(401).end();
      return;
    }
    const range = /^bytes=(\d+)-(\d+)$/.exec(req.headers.range || '');
    const first = Number(range?.[1]);
    const last = Number(range?.[2]);
    const etag = '"' + parallelDigest + '"';
    if (!range || first > last || last >= parallelFixture.length || req.headers['if-range'] !== etag) {
      res.writeHead(400).end();
      return;
    }
    res.writeHead(206, {
      'Content-Type': 'application/vnd.android.package-archive',
      'Content-Length': last - first + 1,
      'Content-Range': 'bytes ' + first + '-' + last + '/' + parallelFixture.length,
      ETag: etag,
    }).end(parallelFixture.subarray(first, last + 1));
    return;
  }
  if (req.url === `/v1/app-update/${updateDigest}/apk`) {
    const headers = {
      'Content-Type': 'application/vnd.android.package-archive',
      ETag: `"${updateDigest}"`,
    };
    if (!sshMobile(req)) {
      res.writeHead(401).end();
      return;
    }
    const offset = 9;
    if (!downloadInterrupted) {
      downloadInterrupted = true;
      res.writeHead(200, { ...headers, 'Content-Length': updateFixture.length });
      res.write(updateFixture.subarray(0, offset), () => setTimeout(() => res.destroy(), 250));
      return;
    }
    if (req.headers.range !== `bytes=${offset}-` || req.headers['if-range'] !== headers.ETag) {
      res.writeHead(400).end();
      return;
    }
    res.writeHead(206, {
      ...headers,
      'Content-Length': updateFixture.length - offset,
      'Content-Range': `bytes ${offset}-${updateFixture.length - 1}/${updateFixture.length}`,
    }).end(updateFixture.subarray(offset));
    return;
  }
  if (req.url === '/fixture/stats') {
    res.setHeader('Content-Type', 'application/json');
    res.end(JSON.stringify(stats));
    return;
  }
  if (req.url === '/fixture/append' && req.method === 'POST') {
    store.create('fixture-thread-' + ++seq, 'fixture update');
    res.end(String(seq));
    return;
  }
  if (req.url === '/v1/events') {
    stats.activeFeeds++;
    stats.newFeeds++;
    res.once('close', () => stats.activeFeeds--);
  }
  const upstream = httpRequest({
    host: '127.0.0.1', port: gatewayPort, path: req.url, method: req.method,
    headers: { ...req.headers, 'X-Workbench-Ingress': 'https' }
  }, response => {
    res.writeHead(response.statusCode, response.headers);
    response.pipe(res);
  });
  upstream.on('error', () => res.destroy());
  res.once('close', () => upstream.destroy());
  req.pipe(upstream);
});
ingress.on('upgrade', (req, socket, head) => {
  const upstream = net.connect(gatewayPort, '127.0.0.1');
  stats.activeTunnels++;
  socket.once('close', () => { stats.activeTunnels--; upstream.destroy(); });
  socket.on('error', () => upstream.destroy());
  upstream.on('error', () => socket.destroy());
  upstream.write(`GET ${req.url} HTTP/1.1\r\n` +
    Object.entries(req.headers).map(([key, value]) => `${key}: ${value}\r\n`).join('') + '\r\n');
  if (head.length) upstream.write(head);
  upstream.pipe(socket);
  socket.pipe(upstream);
});
await listen(ingress, 18443);
console.log('USB TLS fixture ready on 127.0.0.1:18443');
