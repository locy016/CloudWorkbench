import { randomBytes, randomUUID } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { setTimeout as delay } from 'node:timers/promises';

const signer = spawnSync(process.execPath, ['/home/xux/.local/lib/cloud-workbench/ops/ssh-mobile-token.mjs'],
  { env: { SSH_ORIGINAL_COMMAND: 'workbench-ssh-session-v1' } });
const check = (condition, label) => { if (!condition) throw new Error(label); };
check(signer.status === 0, 'SSH signer failed');
const { token } = JSON.parse(signer.stdout);
const route = '/internal/windows-pairing';
const local = 'http://127.0.0.1:8766';
const publicOrigin = 'https://workbench.qiwuli.com';
const request = (url, token, body) => fetch(url, {
  method: body ? 'POST' : 'GET',
  headers: { ...(token ? { Authorization: 'Bearer ' + token } : {}),
    ...(body ? { 'Content-Type': 'application/json' } : {}) },
  body: body && JSON.stringify(body),
  signal: AbortSignal.timeout(5000),
});
const admin = (path = '', body) => request(local + route + path, token, body);
let codeId;
const deviceId = randomUUID();
try {
  check((await request(publicOrigin + route)).status === 404, 'Management is publicly reachable');
  const created = await admin('/codes', { minutes: 5 });
  check(created.status === 201, 'Issuing an authorization failed');
  const { id, code } = await created.json();
  codeId = id;
  const revealPath = '/codes/' + codeId + '/value';
  check((await request(local + route + revealPath)).status === 401, 'Anonymous code access was accepted');
  const revealed = await admin(revealPath);
  check(revealed.status === 200 && (await revealed.json()).code === code, 'Revealing a valid code failed');
  const enrolled = await request(publicOrigin + '/v3/device/enroll', null, {
    code, deviceId, renewalToken: randomBytes(32).toString('base64url'), label: 'Temporary authorization verification',
  });
  check(enrolled.status === 200, 'Enrolling a temporary device failed');
  const { accessToken } = await enrolled.json();
  check((await admin(revealPath)).status === 404, 'Consumed authorization code can still be revealed');
  check((await request(publicOrigin + '/v3/transport', accessToken)).status === 200, 'Verification of the new device failed');
  let recorded;
  for (let attempt = 0; attempt < 7; attempt++) {
    const response = await admin();
    check(response.status === 200, 'Authorization list failed');
    const { devices } = await response.json();
    const device = devices.find(value => value.id === deviceId);
    check(device?.codeCreatedAt > 0 && device.codeSuffix === code.slice(-6), 'Authorization details are missing');
    if (device.usage?.firstLoginAt > 0 && device.usage.lastLoginAt >= device.usage.firstLoginAt) {
      recorded = true;
      break;
    }
    await delay(2000);
  }
  check(recorded, 'Successful device verification was not recorded');
  console.log('Authorization issue, private reveal, redemption and login statistics verified.');
} finally {
  if (codeId) {
    for (const path of ['/devices/' + deviceId + '/remove', '/codes/' + codeId + '/remove']) {
      const response = await admin(path, {});
      check(response.status === 200, 'Temporary authorization cleanup failed');
      await response.arrayBuffer();
    }
  }
}
