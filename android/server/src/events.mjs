// Events are invalidations. The existing HTTP cursors carry authoritative data.
export class Events {
  #peers = new Set();
  #threads = new Set();
  #overview = false;
  #timer;

  constructor({ heartbeatMs = 45_000 } = {}) {
    this.heartbeat = setInterval(() => {
      for (const peer of this.#peers) this.#write(peer, { type: 'ping' });
    }, heartbeatMs).unref();
  }

  subscribe(res, device, authorized) {
    if (this.#peers.size >= 32 || [...this.#peers].filter(peer => peer.device === device).length >= 3) return false;
    res.writeHead(200, {
      'Content-Type': 'application/x-ndjson',
      'Cache-Control': 'no-store, no-transform',
      'X-Accel-Buffering': 'no',
      'X-Content-Type-Options': 'nosniff',
    });
    const peer = { res, device, authorized };
    this.#peers.add(peer);
    res.once('close', () => this.#peers.delete(peer));
    this.#write(peer, { type: 'ready' });
    return true;
  }

  notify(id, overview = false) {
    if (this.#peers.size === 0) return;
    if (id && this.#threads.size < 64) this.#threads.add(id);
    this.#overview ||= overview || Boolean(id && !this.#threads.has(id));
    this.#timer ??= setTimeout(() => {
      this.#timer = undefined;
      const update = { type: 'change', threads: [...this.#threads], overview: this.#overview };
      this.#threads.clear();
      this.#overview = false;
      for (const peer of this.#peers) this.#write(peer, update);
    }, 200).unref();
  }

  #write(peer, event) {
    if (!peer.authorized() || !peer.res.write(JSON.stringify(event) + '\n')) peer.res.destroy();
  }

  close() {
    clearInterval(this.heartbeat);
    clearTimeout(this.#timer);
    for (const peer of this.#peers) peer.res.destroy();
  }
}
