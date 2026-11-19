import net from 'node:net';
import { WebSocketServer, createWebSocketStream } from 'ws';
import { browserMux, muxProtocol } from './browser-mux.mjs';

// All bytes go to the existing loopback HTTP/CONNECT parser and target policy.
// The upgrade credential is never written to the browser proxy socket.
export function browserTunnel(server, { authorize, port = 8768, checkIntervalMs = 45_000 } = {}) {
  const wss = new WebSocketServer({ noServer: true, perMessageDeflate: false, maxPayload: 32 * 1024 });
  const mux = browserMux({ port });
  const earlyConnects = new WeakMap();
  wss.on('headers', (headers, request) => {
    if (earlyConnects.has(request)) headers.push('X-Workbench-Early-Connect: 1');
    if (request.headers['sec-websocket-protocol'] === muxProtocol) headers.push('X-Workbench-Mux: 1');
  });
  const counts = new Map();
  const muxCounts = new Map();
  const authorizationCheck = setInterval(() => {
    for (const ws of wss.clients) {
      if (!ws.authorized()) ws.terminate();
    }
  }, checkIntervalMs).unref();

  server.on('upgrade', (request, socket, head) => {
    const reject = status => socket.end(`HTTP/1.1 ${status}\r\nConnection: close\r\nContent-Length: 0\r\n\r\n`);
    socket.on('error', () => socket.destroy());
    if (request.url !== '/v1/browser-tunnel') return reject('404 Not Found');
    if (request.headers.origin) return reject('403 Forbidden');
    const device = authorize(request, 'browser');
    if (!device) return reject('401 Unauthorized');
    const protocol = request.headers['sec-websocket-protocol'];
    if (protocol && protocol !== muxProtocol) return reject('400 Bad Request');
    const selected = protocol === muxProtocol ? muxCounts : counts;
    if (wss.clients.size >= 48 || (selected.get(device) || 0) >= (protocol ? 2 : 24))
      return reject('429 Too Many Requests');

    const authority = request.headers['x-workbench-connect'];
    if (!protocol && typeof authority === 'string' && authority.length <= 270 &&
        /^(?:[a-zA-Z0-9_.-]+|\[[0-9a-fA-F:]+\]):[0-9]{1,5}$/.test(authority)) {
      earlyConnects.set(request, authority);
    }

    wss.handleUpgrade(request, socket, head, ws => {
      selected.set(device, (selected.get(device) || 0) + 1);
      ws.authorized = () => authorize(request, 'browser') === device;
      ws.once('close', () => {
        const remaining = selected.get(device) - 1;
        if (remaining) selected.set(device, remaining);
        else selected.delete(device);
      });
      if (protocol) { mux.attach(ws, device); return; }
      ws.on('message', (_, binary) => { if (!binary) ws.terminate(); });
      const stream = createWebSocketStream(ws, { highWaterMark: 32 * 1024 });
      const upstream = net.connect({ host: '127.0.0.1', port, noDelay: true });
      // Let idle browser connections expire without sending keepalive traffic per tab.
      upstream.setTimeout(90_000, () => upstream.destroy());
      stream.on('error', () => upstream.destroy());
      upstream.on('error', () => stream.destroy());
      // Let data already accepted by the stream drain before the close frame.
      upstream.once('close', () => { if (!stream.writableEnded) stream.end(); });
      ws.once('close', () => upstream.destroy());
      upstream.pipe(stream);
      const earlyConnect = earlyConnects.get(request);
      if (earlyConnect) upstream.write('CONNECT ' + earlyConnect + ' HTTP/1.1\r\nHost: ' + earlyConnect + '\r\n\r\n');
      stream.pipe(upstream);
    });
  });

  const stop = () => {
    clearInterval(authorizationCheck);
    for (const ws of wss.clients) ws.terminate();
    wss.close();
  };
  stop.snapshot = mux.snapshot;
  return stop;
}
