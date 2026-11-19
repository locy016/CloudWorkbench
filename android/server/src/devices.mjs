import { createHash, createHmac, randomBytes } from 'node:crypto';
import { DatabaseSync } from 'node:sqlite';
import { ensureDevicePolicies, devicePolicy, policyAllowed } from './device-policy.mjs';

const day = 86_400_000;
const accessLifetime = day;
const renewalLifetime = 180 * day;
const newSecret = () => randomBytes(32).toString('base64url');
const digest = value => createHash('sha256').update(value).digest('hex');
const validSecret = value => typeof value === 'string' && /^[\w-]{43}$/.test(value);
const validDevice = value => typeof value === 'string' &&
  /^[a-f\d]{8}-(?:[a-f\d]{4}-){3}[a-f\d]{12}$/.test(value);
const validScopes = scopes => Array.isArray(scopes) && scopes.length > 0 && scopes.length <= 3 &&
  new Set(scopes).size === scopes.length && scopes.every(s => ['api', 'events', 'browser'].includes(s));
const fail = (status, message) => Object.assign(new Error(message), { status });

export class Devices {
  constructor(path, now = Date.now) {
    this.now = now;
    this.db = new DatabaseSync(path);
    this.db.exec(`PRAGMA busy_timeout=3000;
      CREATE TABLE IF NOT EXISTS devices (
        id TEXT PRIMARY KEY, label TEXT NOT NULL, scopes TEXT NOT NULL,
        access_hash TEXT UNIQUE NOT NULL, access_until INTEGER NOT NULL,
        renewal_hash TEXT UNIQUE NOT NULL, renewal_until INTEGER NOT NULL,
        created INTEGER NOT NULL, revoked INTEGER
      );`);
    ensureDevicePolicies(this.db, { now });
    this.lookup = this.db.prepare('SELECT id, scopes, access_until, revoked FROM devices WHERE access_hash=?');
  }

  pair({ id, label, renewalToken, scopes = ['api', 'events', 'browser'] }) {
    if (!validDevice(id) || !validSecret(renewalToken) || !validScopes(scopes)) {
      throw fail(400, '设备配对参数无效');
    }
    const now = this.now();
    const renewalHash = digest(renewalToken);
    const existing = this.db.prepare('SELECT renewal_hash, renewal_until, revoked FROM devices WHERE id=?').get(id);
    if (existing) {
      // Retry after a lost response. Recovery of a different device uses a new device ID.
      if (existing.revoked !== null || existing.renewal_hash !== renewalHash || existing.renewal_until <= now) {
        throw fail(409, '此设备标识已使用，请重新配对');
      }
      return this.renew(renewalToken);
    }
    if (this.db.prepare('SELECT count(*) AS total FROM devices WHERE revoked IS NULL AND renewal_until>?').get(now).total >= 32) {
      throw fail(429, '已达到设备上限，请撤销闲置设备');
    }
    const accessToken = newSecret();
    const expiresAt = now + accessLifetime;
    const renewalExpiresAt = now + renewalLifetime;
    const name = typeof label === 'string' ? label.replace(/[\u0000-\u001f\u007f]/g, '').slice(0, 48) : '';
    this.db.prepare('INSERT INTO devices VALUES(?,?,?,?,?,?,?,?,NULL)').run(id, name, scopes.join(','), digest(accessToken),
      expiresAt, renewalHash, renewalExpiresAt, now);
    return { accessToken, expiresAt, renewalExpiresAt };
  }

  renew(renewalToken, { browserOnly = false } = {}) {
    if (!validSecret(renewalToken)) throw fail(401, '设备续期凭据无效');
    const now = this.now();
    const device = this.db.prepare(`SELECT id,scopes,renewal_until FROM devices WHERE renewal_hash=?
      AND revoked IS NULL AND renewal_until>? AND (?=0 OR scopes='browser')`)
      .get(digest(renewalToken), now, Number(browserOnly));
    if (!device || !policyAllowed(this.db, device.id, now)) throw fail(401, '设备授权已失效或额度已用尽');
    const policy = devicePolicy(this.db, device.id, now);
    if (policy) {
      // Portable copies sharing a renewal secret receive the same token during
      // an access period. Refreshing one copy does not invalidate its peers.
      const epoch = Math.floor(now / day);
      const accessToken = createHmac('sha256', Buffer.from(renewalToken, 'base64url'))
        .update(`cloud-workbench/windows-access/v2\0${device.id}\0${epoch}`).digest('base64url');
      const expiresAt = Math.min((epoch + 1) * day, policy.authorizationExpiresAt);
      this.db.prepare('UPDATE devices SET access_hash=?,access_until=? WHERE id=?').run(digest(accessToken), expiresAt, device.id);
      return { accessToken, expiresAt, renewalExpiresAt: policy.authorizationExpiresAt };
    }
    const accessToken = newSecret();
    const expiresAt = now + accessLifetime;
    const renewalExpiresAt = now + renewalLifetime;
    const result = this.db.prepare(`UPDATE devices SET access_hash=?, access_until=?, renewal_until=?
      WHERE renewal_hash=? AND revoked IS NULL AND renewal_until>? AND (?=0 OR scopes='browser')`)
      .run(digest(accessToken), expiresAt, renewalExpiresAt, digest(renewalToken), now, Number(browserOnly));
    if (!result.changes) throw fail(401, '设备续期凭据无效');
    return { accessToken, expiresAt, renewalExpiresAt };
  }

  authorize(header, scope, { browserOnly = false } = {}) {
    const token = typeof header === 'string' && header.startsWith('Bearer ') ? header.slice(7) : '';
    if (!validSecret(token)) return null;
    const row = this.lookup.get(digest(token));
    if (!row || row.revoked !== null || row.access_until <= this.now() ||
        (browserOnly && row.scopes !== 'browser') || !row.scopes.split(',').includes(scope)) return null;
    if (!policyAllowed(this.db, row.id, this.now())) return null;
    return row.id;
  }

  list() {
    return this.db.prepare('SELECT id,label,scopes,created,renewal_until AS renewalExpiresAt,revoked FROM devices ORDER BY created').all();
  }

  revoke(id) {
    if (!validDevice(id)) throw new Error('Invalid device ID');
    return this.db.prepare('UPDATE devices SET revoked=? WHERE id=? AND revoked IS NULL').run(this.now(), id).changes === 1;
  }

  setScopes(id, scopes) {
    if (!validDevice(id) || !validScopes(scopes)) throw new Error('Invalid device ID or scopes');
    return this.db.prepare('UPDATE devices SET scopes=? WHERE id=? AND revoked IS NULL').run(scopes.join(','), id).changes === 1;
  }

  close() { this.db.close(); }
}
