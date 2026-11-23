import { DatabaseSync } from 'node:sqlite';
import { createHash } from 'node:crypto';
import { performance } from 'node:perf_hooks';
import { Metrics } from './metrics.mjs';
import { MessageChanges } from './message-changes.mjs';
import { decodeCursor, encodeCursor, requireInteger } from './cursors.mjs';

const emptyThreadConditions = `WHERE COALESCE(projectId, ?) = ?
  AND status NOT IN ('starting', 'running', 'approval')
  AND NOT EXISTS (SELECT 1 FROM messages WHERE threadId = threads.id)
  AND NOT EXISTS (SELECT 1 FROM attachments WHERE threadId = threads.id)`;
const overviewIncidentStates = new Set(['failed', 'inactive', 'unknown', 'not-found', 'maintenance']);
const DAY_MS = 24 * 60 * 60 * 1000;

export class Store {
  statements = new Map(); pending = new Map(); pendingChars = 0; timer = null;
  changes = new MessageChanges();
  constructor(path, { flushMs = 100, metrics = new Metrics() } = {}) {
    if (!Number.isInteger(flushMs) || flushMs < 1 || flushMs > 1000) throw new Error('Invalid flush interval');
    this.flushMs = flushMs; this.metrics = metrics;
    this.db = new DatabaseSync(path);
    this.db.function('history_text_key', { deterministic: true }, value => value.normalize('NFKC').toLowerCase());
    this.db.exec(`PRAGMA journal_mode=WAL; PRAGMA synchronous=NORMAL;
      CREATE TABLE IF NOT EXISTS threads(id TEXT PRIMARY KEY,title TEXT NOT NULL,created INTEGER NOT NULL,updated INTEGER NOT NULL,status TEXT NOT NULL,turnId TEXT,error TEXT);
      CREATE TABLE IF NOT EXISTS messages(id TEXT PRIMARY KEY,threadId TEXT NOT NULL,role TEXT NOT NULL,text TEXT NOT NULL,status TEXT NOT NULL,created INTEGER NOT NULL);
      CREATE INDEX IF NOT EXISTS messages_thread ON messages(threadId,created);
      CREATE TABLE IF NOT EXISTS requests(id TEXT PRIMARY KEY,threadId TEXT NOT NULL,state TEXT NOT NULL,turnId TEXT);
      CREATE TABLE IF NOT EXISTS attachments(id TEXT PRIMARY KEY,threadId TEXT NOT NULL,path TEXT NOT NULL,mime TEXT NOT NULL,created INTEGER NOT NULL);
      CREATE TABLE IF NOT EXISTS overview_service_state(name TEXT PRIMARY KEY, state TEXT NOT NULL);
      CREATE TABLE IF NOT EXISTS overview_incidents(id INTEGER PRIMARY KEY, name TEXT NOT NULL, state TEXT NOT NULL, observedAt INTEGER NOT NULL);
      UPDATE threads SET status='interrupted',error='管理服务已重启，上次任务中断，可继续对话' WHERE status IN ('running','starting','approval');`);
    if (!this.prepare('PRAGMA table_info(threads)').all().some(c => c.name === 'codexId')) {
      this.db.exec('ALTER TABLE threads ADD COLUMN codexId TEXT; UPDATE threads SET codexId=id;');
    }
    if (!this.prepare('PRAGMA table_info(threads)').all().some(c => c.name === 'projectId')) this.db.exec('ALTER TABLE threads ADD COLUMN projectId TEXT;');
    this.db.exec(`CREATE INDEX IF NOT EXISTS threads_updated_id ON threads(updated DESC,id DESC);
      CREATE INDEX IF NOT EXISTS threads_codex_id ON threads(codexId);
      CREATE INDEX IF NOT EXISTS attachments_thread ON attachments(threadId);`);
  }
  prepare(sql) {
    let statement = this.statements.get(sql);
    if (!statement) {
      if (this.statements.size >= 256) this.statements.delete(this.statements.keys().next().value);
      statement = this.db.prepare(sql); this.statements.set(sql, statement);
    }
    return statement;
  }
  create(id, title) { const n = Date.now(); this.prepare('INSERT INTO threads(id,title,created,updated,status,turnId,error) VALUES(?,?,?,?,?,?,?)').run(id, title, n, n, 'idle', null, null); this.onChange?.(id, true); }
  remote(id, codexId) { this.prepare('UPDATE threads SET codexId=? WHERE id=?').run(codexId, id); }
  project(id, projectId) { this.prepare('UPDATE threads SET projectId=? WHERE id=?').run(projectId, id); }
  attachment(id, threadId, path, mime) { this.prepare('INSERT INTO attachments VALUES(?,?,?,?,?)').run(id, threadId, path, mime, Date.now()); }
  getAttachment(id) { return this.prepare('SELECT * FROM attachments WHERE id=?').get(id); }
  local(codexId) { return this.prepare('SELECT id FROM threads WHERE codexId=? OR id=?').get(codexId, codexId)?.id; }
  thread(id) { return this.prepare('SELECT * FROM threads WHERE id=?').get(id); }
  list() { return this.prepare('SELECT * FROM threads ORDER BY updated DESC LIMIT 100').all(); }
  recentResults() {
    return this.prepare(`SELECT id,title,projectId,status,updated FROM threads
      WHERE status IN ('idle','failed','interrupted')
        AND EXISTS (SELECT 1 FROM messages WHERE threadId=threads.id)
      ORDER BY updated DESC,id DESC LIMIT 5`).all();
  }
  observeOverviewServices(services, sampledAt) {
    const since = sampledAt - DAY_MS;
    this.db.exec('BEGIN IMMEDIATE');
    try {
      const previous = this.prepare('SELECT state FROM overview_service_state WHERE name=?');
      const update = this.prepare(`INSERT INTO overview_service_state(name,state) VALUES(?,?)
        ON CONFLICT(name) DO UPDATE SET state=excluded.state`);
      const incident = this.prepare('INSERT INTO overview_incidents(name,state,observedAt) VALUES(?,?,?)');
      for (const { name, state } of services) {
        if (previous.get(name)?.state === state) continue;
        update.run(name, state);
        if (overviewIncidentStates.has(state)) incident.run(name, state, sampledAt);
      }
      this.prepare('DELETE FROM overview_incidents WHERE observedAt < ?').run(since);
      this.prepare(`DELETE FROM overview_incidents WHERE id IN
        (SELECT id FROM overview_incidents ORDER BY observedAt DESC,id DESC LIMIT -1 OFFSET 100)`).run();
      this.db.exec('COMMIT');
    } catch (error) {
      this.db.exec('ROLLBACK');
      throw error;
    }
    return this.prepare(`SELECT name,state,observedAt FROM overview_incidents WHERE observedAt BETWEEN ? AND ?
      ORDER BY observedAt DESC,id DESC LIMIT 6`).all(since, sampledAt);
  }
  emptyThreads(projectId, defaultProjectId) {
    return this.prepare(`SELECT id FROM threads ${emptyThreadConditions}`)
      .all(defaultProjectId, projectId).map(row => row.id);
  }
  hasEmptyThreads(projectId, defaultProjectId) {
    return Boolean(this.prepare(`SELECT 1 FROM threads ${emptyThreadConditions} LIMIT 1`).get(defaultProjectId, projectId));
  }
  deleteThreads(ids) {
    this.db.exec('BEGIN IMMEDIATE');
    try {
      const remove = ['messages', 'requests', 'attachments'].map(table => this.prepare(`DELETE FROM ${table} WHERE threadId = ?`));
      const removeThread = this.prepare('DELETE FROM threads WHERE id = ?');
      for (const id of ids) {
        for (const statement of remove) statement.run(id);
        removeThread.run(id);
      }
      this.db.exec('COMMIT');
      for (const [key, message] of this.pending) if (ids.includes(message.threadId)) this.dropPending(key);
      for (const id of ids) this.onChange?.(id, true);
    } catch (error) {
      this.db.exec('ROLLBACK');
      throw error;
    }
  }
  title(id, text) { this.prepare('UPDATE threads SET title=? WHERE id=? AND title=?').run(text.replace(/\s+/g, ' ').slice(0, 36), id, '新的对话'); }
  state(id, status, turnId = null, error = null) { this.flush(id); this.prepare('UPDATE threads SET status=?,turnId=?,error=?,updated=? WHERE id=?').run(status, turnId, error, Date.now(), id); this.onChange?.(id, true); }
  message(threadId, id, role, text, status = 'completed', append = false) {
    text = String(text || '').slice(-64000);
    if (append) {
      const old = this.pending.get(id);
      if (old && old.threadId !== threadId) throw new Error('Message belongs to another thread');
      const nextText = ((old?.text || '') + text).slice(-64000);
      if ((!old && this.pending.size >= 64) || this.pendingChars - (old?.text.length || 0) + nextText.length > 262144) {
        this.flush();
        return this.message(threadId, id, role, text, status, true);
      }
      this.metrics.increment('messageDeltaEvents');
      this.pendingChars += nextText.length - (old?.text.length || 0);
      this.pending.set(id, { threadId, id, role, text: nextText, status, created: old?.created ?? Date.now() });
      this.scheduleFlush();
      return;
    }
    const pending = this.pending.get(id);
    if (pending && pending.threadId !== threadId) throw new Error('Message belongs to another thread');
    this.persistMessage({ threadId, id, role, text, status, created: pending?.created ?? Date.now() }, false);
    if (pending) this.dropPending(id);
    this.changes.record(threadId, id);
    this.onChange?.(threadId, false);
    this.metrics.increment('messageRowsWritten');
  }
  persistMessage({ threadId, id, role, text, status, created }, append) {
    const old = this.prepare('SELECT * FROM messages WHERE id=?').get(id);
    if (old && old.threadId !== threadId) throw new Error('Message belongs to another thread');
    if (old) this.prepare('UPDATE messages SET text=?,status=? WHERE id=?').run((append ? old.text + text : text).slice(-64000), status, id);
    else this.prepare('INSERT INTO messages VALUES(?,?,?,?,?,?)').run(id, threadId, role, text, status, created);
  }
  scheduleFlush() {
    if (this.timer || !this.pending.size) return;
    this.timer = setTimeout(() => {
      this.timer = null;
      try { this.flush(); }
      catch {
        this.metrics.increment('messageFlushErrors');
        // Pending data remains bounded and is retried; never print message content.
        this.scheduleFlush();
      }
    }, this.flushMs);
    this.timer.unref();
  }
  dropPending(id) {
    const message = this.pending.get(id);
    if (!message) return;
    this.pendingChars -= message.text.length; this.pending.delete(id);
    if (!this.pending.size && this.timer) { clearTimeout(this.timer); this.timer = null; }
  }
  flush(threadId) {
    const entries = [...this.pending.values()].filter(message => threadId === undefined || message.threadId === threadId);
    if (!entries.length) return;
    const started = performance.now();
    this.db.exec('BEGIN IMMEDIATE');
    try {
      for (const message of entries) this.persistMessage(message, true);
      this.db.exec('COMMIT');
    } catch (error) { this.db.exec('ROLLBACK'); throw error; }
    for (const message of entries) {
      this.dropPending(message.id);
      this.changes.record(message.threadId, message.id);
      this.onChange?.(message.threadId, false);
    }
    this.metrics.increment('messageFlushTransactions');
    this.metrics.increment('messageRowsWritten', entries.length);
    this.metrics.observe('messageFlushMs', performance.now() - started);
  }
  listPage({ limit, before, query = '', projectId = null, status = null, defaultProjectId = null }) {
    const conditions = [];
    const params = [];
    if (query) {
      conditions.push('instr(history_text_key(title), ?) > 0');
      params.push(query.normalize('NFKC').toLowerCase());
    }
    if (projectId !== null) {
      conditions.push('COALESCE(projectId, ?) = ?');
      params.push(defaultProjectId, projectId);
    }
    if (status !== null) {
      conditions.push('status = ?');
      params.push(status);
    }
    const scope = conditions.length ? createHash('sha256').update(JSON.stringify([query, projectId, status, defaultProjectId])).digest('hex') : null;
    if (before) {
      const cursor = decodeCursor(before, 'threads');
      requireInteger(cursor.updated);
      if ((cursor.scope ?? null) !== scope || typeof cursor.id !== 'string' || !/^[a-zA-Z0-9-]{1,128}$/.test(cursor.id))
        throw Object.assign(new Error('分页参数无效，请重新获取第一页'), { status: 400 });
      conditions.push('(updated,id) < (?,?)');
      params.push(cursor.updated, cursor.id);
    }
    const where = conditions.length ? 'WHERE ' + conditions.join(' AND ') : '';
    const rows = this.prepare(`SELECT * FROM threads ${where} ORDER BY updated DESC,id DESC LIMIT ?`).all(...params, limit + 1);
    const hasMore = rows.length > limit;
    const threads = rows.slice(0, limit);
    const last = threads.at(-1);
    return { threads, hasMore, nextBefore: hasMore ? encodeCursor({ kind: 'threads', updated: last.updated, id: last.id, scope }) : null };
  }
  messagePage(threadId, { limit, before }) {
    this.flush(threadId);
    let rows;
    if (before) {
      const cursor = decodeCursor(before, 'messages', threadId);
      requireInteger(cursor.created); requireInteger(cursor.sequence, 1);
      rows = this.prepare('SELECT rowid AS sequence,* FROM messages WHERE threadId=? AND (created,rowid) < (?,?) ORDER BY created DESC,rowid DESC LIMIT ?').all(threadId, cursor.created, cursor.sequence, limit + 1);
    } else rows = this.prepare('SELECT rowid AS sequence,* FROM messages WHERE threadId=? ORDER BY created DESC,rowid DESC LIMIT ?').all(threadId, limit + 1);
    const hasMore = rows.length > limit;
    const selected = rows.slice(0, limit);
    const oldest = selected.at(-1);
    return {
      messages: selected.reverse().map(({ sequence, ...message }) => message), hasMore,
      nextBefore: hasMore ? encodeCursor({ kind: 'messages', threadId, created: oldest.created, sequence: oldest.sequence }) : null,
    };
  }
  messageUpdates(threadId, { cursor, limit }) {
    this.flush(threadId);
    const result = this.changes.since(threadId, cursor, limit);
    if (result.reset) {
      const page = this.messagePage(threadId, { limit });
      return { ...result, messages: page.messages, historyBefore: page.nextBefore, hasMore: false };
    }
    const messages = result.ids.map(id => this.prepare('SELECT id,threadId,role,text,status,created FROM messages WHERE threadId=? AND id=?').get(threadId, id)).filter(Boolean);
    const { ids, ...metadata } = result;
    return { ...metadata, messages };
  }
  performanceSnapshot() {
    return { ...this.metrics.snapshot(), pendingMessages: this.pending.size, pendingChars: this.pendingChars,
      flushIntervalMs: this.flushMs, preparedStatements: this.statements.size, retainedChanges: this.changes.events.length };
  }
  messages(id) { this.flush(id); return this.prepare('SELECT id,threadId,role,text,status,created FROM (SELECT *,rowid AS seq FROM messages WHERE threadId=? ORDER BY created DESC,rowid DESC LIMIT 160) ORDER BY created,seq').all(id); }
  request(id) { return this.prepare('SELECT * FROM requests WHERE id=?').get(id); }
  beginRequest(id, threadId) { this.prepare('INSERT INTO requests VALUES(?,?,?,?) ON CONFLICT(id) DO UPDATE SET state=excluded.state,turnId=NULL').run(id, threadId, 'starting', null); }
  finishRequest(id, state, turnId = null) {
    const row = this.prepare('UPDATE requests SET state=?,turnId=? WHERE id=? RETURNING threadId').get(state, turnId, id);
    if (row) this.onChange?.(row.threadId, false);
  }
  close() { this.flush(); if (this.timer) clearTimeout(this.timer); this.statements.clear(); this.db.close(); }
}
