const bad = () => Object.assign(new Error('分页参数无效，请重新获取第一页'), { status: 400 });
export const encodeCursor = value => Buffer.from(JSON.stringify(value)).toString('base64url');
export function decodeCursor(value, kind, threadId) {
  try {
    if (typeof value !== 'string' || !value || value.length > 1024 || !/^[A-Za-z0-9_-]+$/.test(value)) throw bad();
    const cursor = JSON.parse(Buffer.from(value, 'base64url').toString());
    if (!cursor || cursor.kind !== kind || (threadId !== undefined && cursor.threadId !== threadId)) throw bad();
    return cursor;
  } catch { throw bad(); }
}
export function pageLimit(value, maximum) {
  if (value === null || value === undefined) return Math.min(40, maximum);
  if (typeof value !== 'string' || !/^[1-9]\d{0,2}$/.test(value)) throw bad();
  const number = Number(value);
  if (number > maximum) throw bad();
  return number;
}
export function requireInteger(value, minimum = 0) {
  if (!Number.isSafeInteger(value) || value < minimum) throw bad();
  return value;
}
