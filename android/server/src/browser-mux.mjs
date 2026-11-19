import net from 'node:net';

export const muxProtocol = 'workbench.mux.v1';
export const muxStreamsPerDevice = 384;
export const muxStreamsTotal = 640;
const window = 32 * 1024;
const chunkSize = 16 * 1024;
const [open, data, credit, close, reject] = [1, 2, 3, 4, 5];

export function browserMux({ port, idleMs = 240_000, deviceLimit = muxStreamsPerDevice,
  totalLimit = muxStreamsTotal } = {}) {
  let active = 0;
  let peak = 0;
  let rejected = 0;
  let sessions = 0;
  const counts = new Map();

  function attach(ws, device) {
    sessions++;
    const streams = new Map();
    const ping = setInterval(() => { if (ws.readyState === ws.OPEN) ws.ping(); }, 25_000).unref();

    function send(type, id, value, sent) {
      if (ws.readyState !== ws.OPEN) return;
      const size = typeof value === 'number' ? 4 : value?.length || 0;
      const packet = Buffer.allocUnsafe(5 + size);
      packet[0] = type;
      packet.writeUInt32BE(id, 1);
      if (typeof value === 'number') packet.writeUInt32BE(value, 5);
      else value?.copy(packet, 5);
      // Per-stream credit bounds data. This cap also bounds queued control frames from a stalled peer.
      if (ws.bufferedAmount > 8 * 1024 * 1024) return ws.terminate();
      ws.send(packet, { binary: true }, error => {
        if (error) ws.terminate();
        else sent?.();
      });
    }

    function release(flow) {
      if (!streams.delete(flow.id)) return false;
      active--;
      const remaining = counts.get(device) - 1;
      if (remaining) counts.set(device, remaining);
      else counts.delete(device);
      clearTimeout(flow.drain);
      flow.socket.destroy();
      return true;
    }

    function pump(flow) {
      if (flow.sending || !streams.has(flow.id)) return;
      const amount = Math.min(flow.sendCredit, flow.socket.readableLength, chunkSize);
      if (amount > 0) {
        const bytes = flow.socket.read(amount);
        if (bytes) {
          flow.sending = true;
          flow.sendCredit -= bytes.length;
          send(data, flow.id, bytes, () => { flow.sending = false; pump(flow); });
          return;
        }
      }
      if (flow.ended && !flow.socket.readableLength && release(flow)) send(close, flow.id);
    }

    ws.on('message', (packet, binary) => {
      if (!binary || packet.length < 5) return ws.terminate();
      const type = packet[0];
      const id = packet.readUInt32BE(1);
      const size = packet.length - 5;
      if (!id) return ws.terminate();

      if (type === open) {
        if (size || streams.has(id)) return ws.terminate();
        if (active >= totalLimit || (counts.get(device) || 0) >= deviceLimit) {
          rejected++;
          send(reject, id, 429);
          return;
        }
        const socket = net.connect({ host: '127.0.0.1', port, noDelay: true });
        const flow = { id, socket, sendCredit: window, receiveCredit: window, sending: false, ended: false, drain: null };
        streams.set(id, flow);
        counts.set(device, (counts.get(device) || 0) + 1);
        peak = Math.max(peak, ++active);
        socket.setTimeout(idleMs, () => {
          if (release(flow)) send(close, id);
        });
        socket.on('readable', () => pump(flow));
        const ended = () => {
          if (flow.ended || !streams.has(id)) return;
          flow.ended = true;
          flow.drain = setTimeout(() => { if (release(flow)) send(close, id); }, 15_000).unref();
          pump(flow);
        };
        socket.on('end', ended);
        socket.on('error', () => { if (release(flow)) send(close, id); });
        socket.on('close', ended);
        return;
      }

      if (type === data ? !size || size > chunkSize : type === credit ? size !== 4 : type !== close || size !== 0)
        return ws.terminate();
      const flow = streams.get(id);
      // Frames already in flight can arrive after the other side closes its stream.
      if (!flow) return;
      if (type === close) {
        release(flow);
      } else if (type === credit) {
        const amount = packet.readUInt32BE(5);
        if (!amount || flow.sendCredit + amount > window) return ws.terminate();
        flow.sendCredit += amount;
        pump(flow);
      } else if (!flow.ended) {
        if (size > flow.receiveCredit) return ws.terminate();
        flow.receiveCredit -= size;
        flow.socket.write(packet.subarray(5), error => {
          if (error || !streams.has(id)) return;
          flow.receiveCredit += size;
          send(credit, id, size);
        });
      }
    });
    ws.once('close', () => {
      clearInterval(ping);
      sessions--;
      for (const flow of streams.values()) release(flow);
    });
  }

  return { attach, snapshot: () => ({ active, peak, rejected, sessions, deviceLimit, totalLimit }) };
}
