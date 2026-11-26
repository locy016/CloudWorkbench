import { createHash } from 'node:crypto';
import { createReadStream } from 'node:fs';
import { chmod, chown, link, readFile, readdir, rename, stat, unlink, writeFile } from 'node:fs/promises';
import { basename, dirname, join } from 'node:path';
import { readAppUpdate, validateAppUpdate } from '../src/releases.mjs';

const [manifest, apk, directory, mode, ...extra] = process.argv.slice(2);
if (!manifest || !apk || !directory || extra.length || (mode && mode !== '--group-readable')) {
  throw new Error('Usage: publish-app-update.mjs <manifest> <staged-apk> <app-updates-directory> [--group-readable]');
}
const group = mode ? (await stat(directory)).gid : null;
async function setAccess(path) {
  if (group !== null) await chown(path, -1, group);
  await chmod(path, group === null ? 0o600 : 0o640);
}
const update = JSON.parse(await readFile(manifest, 'utf8'));
if (!validateAppUpdate(update)) throw new Error('Invalid update manifest');

async function matches(file) {
  const info = await stat(file);
  if (!info.isFile() || info.size !== update.size) return false;
  const hash = createHash('sha256');
  for await (const bytes of createReadStream(file)) hash.update(bytes);
  return hash.digest('hex') === update.sha256;
}

if (!await matches(apk)) throw new Error('Staged APK does not match its manifest');
const previous = await readAppUpdate(directory);
if (previous && (previous.certificate !== update.certificate ||
    previous.versionCode > update.versionCode ||
    (previous.versionCode === update.versionCode && previous.sha256 !== update.sha256))) {
  throw new Error('Release must preserve the signing certificate and advance the build number');
}
const target = join(directory, update.sha256 + '.apk');
await setAccess(apk);
try {
  await link(apk, target);
} catch (error) {
  if (error.code !== 'EEXIST' || !await matches(target)) throw error;
}
const pending = join(dirname(manifest), 'publish-current.json');
await writeFile(pending, JSON.stringify(update) + '\n', { mode: 0o600, flag: 'w' });
await setAccess(pending);
await rename(pending, join(directory, 'current.json'));
for (const entry of await readdir(directory, { withFileTypes: true })) {
  if (entry.name !== basename(target) && /^[a-f0-9]{64}\.apk$/.test(entry.name) &&
      (entry.isFile() || entry.isSymbolicLink())) await unlink(join(directory, entry.name));
}
console.log(`Published ${update.versionName} (build ${update.versionCode}), ${update.size} bytes, SHA-256 ${update.sha256}`);
