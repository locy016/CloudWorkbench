import { spawn } from 'node:child_process';
import { createInterface } from 'node:readline';
import { EventEmitter } from 'node:events';

// One app-server for this private gateway. Its stdio is never exposed publicly.
export class Codex extends EventEmitter {
  pending = new Map(); seq = 1; process = null; starting = null;
  async start() {
    if (this.starting) return this.starting;
    this.starting = this.connect();
    try { return await this.starting; } catch (e) { this.starting = null; throw e; }
  }
  async connect() {
    // A separately launched app-server must not inherit the desktop session's
    // transport, registered-Core launcher or per-tool sandbox identity.
    const env = Object.fromEntries(Object.entries(process.env).filter(([k]) => !k.startsWith('CODEX_') || k === 'CODEX_HOME'));
    const p = spawn(process.env.CODEX_BIN || '/usr/local/bin/codex', ['app-server', '--listen', 'stdio://'], {
      stdio: ['pipe', 'pipe', 'pipe'], env, windowsHide: true,
    });
    this.process = p;
    let failed = false;
    const fail = () => {
      if (failed) return; failed = true;
      for (const { reject, timer } of this.pending.values()) { clearTimeout(timer); reject(new Error('Codex 进程已断开，请重试')); }
      this.pending.clear(); this.process = null; this.starting = null; this.emit('disconnected');
    };
    p.on('error', fail); p.on('exit', fail);
    // Do not log raw process output: plugins and credentials may appear in it.
    p.stderr.on('data', () => {});
    createInterface({ input: p.stdout }).on('line', line => {
      let m; try { m = JSON.parse(line); } catch { return; }
      if (m.method) this.emit(m.id !== undefined ? 'request' : 'notification', m);
      else if (this.pending.has(m.id)) {
        const q = this.pending.get(m.id); this.pending.delete(m.id); clearTimeout(q.timer);
        m.error ? q.reject(new Error(m.error.message || 'Codex 请求失败')) : q.resolve(m.result);
      }
    });
    await this.rpc('initialize', { clientInfo: { name: 'cloud_workbench', title: '云端工作台', version: '0.3.0' }, capabilities: { experimentalApi: true } });
    this.send({ method: 'initialized' });
  }
  send(m) {
    if (!this.process?.stdin.writable) throw new Error('Codex 尚未连接');
    this.process.stdin.write(JSON.stringify(m) + '\n');
  }
  rpc(method, params = {}) {
    return new Promise((resolve, reject) => {
      const id = this.seq++;
      const timer = setTimeout(() => { this.pending.delete(id); reject(new Error(`Codex 请求超时：${method}`)); }, 45000);
      this.pending.set(id, { resolve, reject, timer });
      try { this.send({ id, method, params }); } catch (e) { clearTimeout(timer); this.pending.delete(id); reject(e); }
    });
  }
  reply(id, result) { this.send({ id, result }); }
  close() { this.process?.kill('SIGTERM'); }
}
