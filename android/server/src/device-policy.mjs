import { DatabaseSync } from 'node:sqlite';
import { existsSync } from 'node:fs';

export const authorizationDay = 86_400_000;
export const defaultAuthorizationDays = 7;
export const defaultTrafficLimitBytes = 1024 ** 3;
export const validAuthorization = (days, bytes) => Number.isInteger(days) && days >= 1 && days <= 365 &&
  Number.isSafeInteger(bytes) && bytes >= 1 && bytes <= 1024 ** 4;
const table = 'device_authorizations_v1';
const hasTable = (db, name) => Boolean(db.prepare("SELECT 1 FROM sqlite_schema WHERE type='table' AND name=?").get(name));
const prepared = new WeakMap();
function statements(db) {
  let sql = prepared.get(db);
  if (sql) return sql;
  if (!hasTable(db, table)) return null;
  sql = { policy: db.prepare(`SELECT * FROM ${table} WHERE id=?`),
    uploaded: db.prepare(`UPDATE ${table} SET uploaded=uploaded+? WHERE id=?`),
    downloaded: db.prepare(`UPDATE ${table} SET downloaded=downloaded+? WHERE id=?`) };
  prepared.set(db, sql);
  return sql;
}

export function ensureDevicePolicies(db, { now = Date.now, usagePath } = {}) {
  db.exec(`CREATE TABLE IF NOT EXISTS ${table} (
    id TEXT PRIMARY KEY REFERENCES devices(id), expires INTEGER NOT NULL,
    days INTEGER NOT NULL, traffic_limit INTEGER, uploaded INTEGER NOT NULL DEFAULT 0,
    downloaded INTEGER NOT NULL DEFAULT 0, legacy INTEGER NOT NULL DEFAULT 0,
    CHECK(traffic_limit IS NULL OR traffic_limit>0), CHECK(uploaded>=0 AND downloaded>=0)
  ) STRICT;
  CREATE TABLE IF NOT EXISTS windows_code_authorizations_v1 (
    id TEXT PRIMARY KEY, days INTEGER NOT NULL, traffic_limit INTEGER NOT NULL
  ) STRICT`);
  // Historical authorizations keep their existing expiry and unbounded quota.
  // Migration never creates a fresh seven-day entitlement for an old device.
  const ids = [];
  for (const name of ['windows_enrollment_v1', 'mihomo_subscriptions_v1']) {
    if (hasTable(db, name)) ids.push(`SELECT ${name === 'windows_enrollment_v1' ? 'device_id' : 'id'} AS id FROM ${name}`);
  }
  if (!ids.length) return;
  let historical = new Map();
  if (usagePath && existsSync(usagePath)) {
    const usage = new DatabaseSync(usagePath, { readOnly: true });
    try {
      usage.exec('PRAGMA busy_timeout=1000');
      if (hasTable(usage, 'device_usage')) historical = new Map(usage.prepare('SELECT id,uploaded,downloaded FROM device_usage').all().map(row => [row.id, row]));
    } finally { usage.close(); }
  }
  const insert = db.prepare(`INSERT OR IGNORE INTO ${table}(id,expires,days,traffic_limit,uploaded,downloaded,legacy)
    VALUES(?,?,?,NULL,?,?,1)`);
  for (const row of db.prepare(`SELECT id,created,renewal_until FROM devices WHERE id IN (${ids.join(' UNION ')})
    AND id NOT IN (SELECT id FROM ${table})`).all()) {
    const used = historical.get(row.id);
    insert.run(row.id, row.renewal_until, Math.max(1, Math.ceil((row.renewal_until - row.created) / authorizationDay)),
      used?.uploaded ?? 0, used?.downloaded ?? 0);
  }
  for (const [id, used] of historical) db.prepare(`UPDATE ${table}
    SET uploaded=max(uploaded,?),downloaded=max(downloaded,?) WHERE id=? AND legacy=1`)
    .run(used.uploaded, used.downloaded, id);
}

export function grantDevicePolicy(db, id, { created = Date.now(), authorizationDays = defaultAuthorizationDays,
  trafficLimitBytes = defaultTrafficLimitBytes } = {}) {
  if (!validAuthorization(authorizationDays, trafficLimitBytes)) throw new Error('Invalid authorization lifetime or quota');
  db.prepare(`INSERT INTO ${table}(id,expires,days,traffic_limit) VALUES(?,?,?,?)`)
    .run(id, created + authorizationDays * authorizationDay, authorizationDays, trafficLimitBytes);
  return devicePolicy(db, id, created);
}

export function devicePolicy(db, id, now = Date.now()) {
  const row = statements(db)?.policy.get(id);
  if (!row) return null;
  const usedBytes = row.uploaded + row.downloaded;
  return { authorizationExpiresAt: row.expires, authorizationDays: row.days, trafficLimitBytes: row.traffic_limit,
    uploadedBytes: row.uploaded, downloadedBytes: row.downloaded, usedBytes,
    remainingBytes: row.traffic_limit === null ? null : Math.max(0, row.traffic_limit - usedBytes),
    authorizationActive: now < row.expires && (row.traffic_limit === null || usedBytes < row.traffic_limit),
    legacyAuthorization: Boolean(row.legacy) };
}

export const policyAllowed = (db, id, now = Date.now()) => devicePolicy(db, id, now)?.authorizationActive ?? true;

// A transaction reserves bytes before forwarding. Independent connections and
// processes use the same durable counters; a crash cannot restore spent quota.
export function reserveDeviceTraffic(db, id, direction, bytes, now = Date.now()) {
  if (!['uploaded', 'downloaded'].includes(direction) || !Number.isSafeInteger(bytes) || bytes < 0) throw new Error('Invalid traffic reservation');
  db.exec('BEGIN IMMEDIATE');
  try {
    const policy = devicePolicy(db, id, now);
    const allowed = !policy ? bytes : !policy.authorizationActive ? 0 : Math.min(bytes, policy.remainingBytes ?? bytes);
    if (policy && allowed) statements(db)[direction].run(allowed, id);
    db.exec('COMMIT');
    return { accepted: allowed, exhausted: Boolean(policy && (!policy.authorizationActive ||
      (policy.remainingBytes !== null && allowed >= policy.remainingBytes))) };
  } catch (error) { db.exec('ROLLBACK'); throw error; }
}
export const reserveDeviceBytes = (...args) => reserveDeviceTraffic(...args).accepted;
