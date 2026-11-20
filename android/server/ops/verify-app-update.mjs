import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';

// Run on the server. The SSH session credential stays in memory and is never printed.
const [expectedSha, expectedCode] = process.argv.slice(2);
assert.match(expectedSha, /^[a-f0-9]{64}$/);
assert.match(expectedCode, /^\d+$/);
const signer = spawnSync(process.execPath, ['/home/xux/.local/lib/cloud-workbench/ops/ssh-mobile-token.mjs'],
  { env: { SSH_ORIGINAL_COMMAND: 'workbench-ssh-session-v1' } });
assert.equal(signer.status, 0);
const { token } = JSON.parse(signer.stdout);
const origin = 'http://127.0.0.1:8766';
const headers = { Authorization: 'Bearer ' + token };
const get = (path, extra = {}) => fetch(origin + path, { headers: { ...headers, ...extra }, signal: AbortSignal.timeout(60_000) });
assert.equal((await fetch(origin + '/v1/app-update')).status, 401);
assert.equal((await get('/v1/app-update', { 'X-Workbench-Ingress': 'web' })).status, 404);
const manifest = await get('/v1/app-update');
assert.equal(manifest.status, 200);
const { update } = await manifest.json();
assert.equal(update.sha256, expectedSha);
assert.equal(update.versionCode, Number(expectedCode));
const path = '/v1/app-update/' + expectedSha + '/apk';
assert.equal((await fetch(origin + path)).status, 401);
const complete = await get(path);
assert.equal(complete.status, 200);
assert.equal(Number(complete.headers.get('content-length')), update.size);
const hash = createHash('sha256');
let received = 0;
for await (const chunk of complete.body) { hash.update(chunk); received += chunk.length; }
assert.equal(received, update.size);
assert.equal(hash.digest('hex'), expectedSha);
for (const [first, last] of [[0, 1048575], [update.size - 65536, update.size - 1]]) {
  const range = await get(path, { Range: `bytes=${first}-${last}`, 'If-Range': `"${expectedSha}"` });
  assert.equal(range.status, 206);
  assert.equal(range.headers.get('content-range'), `bytes ${first}-${last}/${update.size}`);
  assert.equal(range.headers.get('etag'), `"${expectedSha}"`);
  assert.equal((await range.arrayBuffer()).byteLength, last - first + 1);
}
console.log(JSON.stringify({ versionName: update.versionName, versionCode: update.versionCode,
  sha256: expectedSha, size: received, authenticatedFullBodySha256: true, headAndTail206: true,
  unauthorizedManifestAndApkRejected: true, webIngressRejected: true }));
