import { randomUUID } from 'node:crypto';
import { decodeCursor, encodeCursor, requireInteger } from './cursors.mjs';

// Bounded identifiers only. Text stays in SQLite; restarts/expired cursors reset.
export class MessageChanges {
  generation = randomUUID(); sequence = 0; floor = 0; events = [];
  constructor(capacity = 2048) { this.capacity = capacity; }
  record(threadId, id) {
    this.events.push({ sequence: ++this.sequence, threadId, id });
    if (this.events.length > this.capacity) this.floor = this.events.shift().sequence;
  }
  cursor(threadId, sequence = this.sequence) {
    return encodeCursor({ kind: 'changes', threadId, generation: this.generation, sequence });
  }
  since(threadId, value, limit) {
    if (!value) return { reset: true, cursor: this.cursor(threadId) };
    const cursor = decodeCursor(value, 'changes', threadId);
    requireInteger(cursor.sequence);
    if (typeof cursor.generation !== 'string') throw Object.assign(new Error('增量游标无效'), { status: 400 });
    if (cursor.generation !== this.generation || cursor.sequence < this.floor || cursor.sequence > this.sequence) {
      return { reset: true, cursor: this.cursor(threadId) };
    }
    const events = this.events.filter(event => event.threadId === threadId && event.sequence > cursor.sequence);
    const ids = new Set();
    let consumed = 0, sequence = cursor.sequence;
    for (const event of events) {
      if (!ids.has(event.id) && ids.size >= limit) break;
      ids.add(event.id); sequence = event.sequence; consumed++;
    }
    const hasMore = consumed < events.length;
    return { reset: false, ids: [...ids], hasMore, cursor: this.cursor(threadId, hasMore ? sequence : this.sequence) };
  }
}
