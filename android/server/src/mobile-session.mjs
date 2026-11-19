import { createHmac, randomBytes, timingSafeEqual } from 'node:crypto';
import { closeSync, constants, fstatSync, openSync, readFileSync } from 'node:fs';

export const mobileCommand = 'workbench-ssh-session-v1';
export const mobileSubject = 'ssh-mobile';
export const mobileKeyPath = '/home/xux/.local/share/cloud-workbench/ssh-mobile-session.key';
const ttlMs = 4 * 60 * 60 * 1000;
const version = 'sshm1.';

export const privateLoopback = request => request.socket.remoteAddress === '127.0.0.1' &&
  !Object.hasOwn(request.headers, 'x-workbench-ingress');

export function readMobileKey(path) {
  const file = openSync(path, constants.O_RDONLY | constants.O_NOFOLLOW);
  try {
    const info = fstatSync(file);
    if (!info.isFile() || info.size !== 32 || info.uid !== process.getuid() || (info.mode & 0o777) !== 0o600) {
      throw new Error('Invalid SSH mobile session key');
    }
    const key = readFileSync(file);
    if (key.length !== 32) throw new Error('Invalid SSH mobile session key');
    return key;
  } finally {
    closeSync(file);
  }
}

const signature = (key, payload) => createHmac('sha256', key).update(payload).digest('base64url');

export function issueMobileSession(key, now = Date.now) {
  const expiresAt = now() + ttlMs;
  const payload = version + randomBytes(16).toString('base64url') + '.' + expiresAt;
  return { token: payload + '.' + signature(key, payload), expiresAt };
}

export function mobileSessionAuthorizer(key, now = Date.now) {
  return request => {
    if (!privateLoopback(request)) return null;
    const header = request.headers.authorization;
    if (typeof header !== 'string' || header.length > 100) return null;
    const match = /^Bearer (sshm1\.[A-Za-z0-9_-]{22}\.([0-9]{13}))\.([A-Za-z0-9_-]{43})$/.exec(header);
    if (!match) return null;
    const [, payload, expires, signed] = match;
    const time = now();
    if (Number(expires) <= time || Number(expires) > time + ttlMs) return null;
    return timingSafeEqual(Buffer.from(signed), Buffer.from(signature(key, payload))) ? mobileSubject : null;
  };
}
