import { mkdirSync, readFileSync } from 'node:fs';
import { access } from 'node:fs/promises';
import { Codex } from './codex.mjs';
import { Store } from './store.mjs';
import { Gateway } from './gateway.mjs';
import { Releases } from './releases.mjs';
import { Events } from './events.mjs';
import { mobileSessionAuthorizer, readMobileKey } from './mobile-session.mjs';
process.umask(0o077);
const data = process.env.DATA_DIR || '/home/xux/.local/share/cloud-workbench';
mkdirSync(data, { recursive: true, mode: 0o700 });
const token = readFileSync(process.env.TOKEN_FILE || data + '/access.token', 'utf8').trim();
if (token.length < 32) throw new Error('Access token must contain at least 32 characters');
let projects;
try { projects = JSON.parse(readFileSync(data + "/projects.json", "utf8")); } catch (e) { if (e.code !== "ENOENT") throw e; }
const cwd = process.env.WORKSPACE_DIR || projects?.find(project => !project.archived)?.path || '/home/xux/projects/server-ops';
mkdirSync(cwd, { recursive: true });
let sharedWritableRoots = [];
try {
  sharedWritableRoots = JSON.parse(readFileSync(data + '/codex-writable-roots.json', 'utf8'));
} catch (e) {
  if (e.code !== 'ENOENT') throw e;
}
let codexAccess = 'workspace-write';
try {
  codexAccess = readFileSync(data + '/codex-access-mode', 'utf8').trim();
} catch (e) {
  if (e.code !== 'ENOENT') throw e;
}
const events = new Events();
let sshMobile, mobileKey;
try {
  mobileKey = readMobileKey(data + '/ssh-mobile-session.key');
  sshMobile = mobileSessionAuthorizer(mobileKey);
}
catch (error) {
  if (error.code !== 'ENOENT') console.error('SSH mobile session key unavailable');
}
const gateway = new Gateway({ codex: new Codex(), store: new Store(data + '/workbench.db'), token, cwd, events, sshMobile, projects, sharedWritableRoots, codexAccess });
gateway.extension = new Releases(data);
gateway.draining = () => access(data + '/transport-maintenance').then(() => true, () => false);
gateway.http.requestTimeout = 1800000; gateway.http.headersTimeout = 10000;
gateway.http.listen(Number(process.env.PORT || 8766), '127.0.0.1', () => console.log('云端工作台网关已启动'));
const collectOverview = () => gateway.status().catch(() => console.error('服务器概览采集失败'));
const overviewTimer = setInterval(collectOverview, 60_000).unref();
void collectOverview();
let stopping = false;
for (const signal of ['SIGTERM', 'SIGINT']) process.on(signal, () => {
  if (stopping) return;
  stopping = true;
  clearInterval(overviewTimer);
  gateway.close().finally(() => process.exit(0));
});
