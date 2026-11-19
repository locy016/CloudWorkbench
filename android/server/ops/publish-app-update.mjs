import { createHash } from 'node:crypto';
import { createReadStream } from 'node:fs';
import { chmod, link, readFile, rename, stat, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { readAppUpdate, validateAppUpdate } from '../src/releases.mjs';

const [manifest, apk, directory] = process.argv.slice(2);
if (!manifest || !apk || !directory || process.argv.length !== 5) {
  throw new Error('Usage: publish-app-update.mjs <manifest> <staged-apk> <app-updates-directory>');
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
await chmod(apk, 0o600);
try {
  await link(apk, target);
} catch (error) {
  if (error.code !== 'EEXIST' || !await matches(target)) throw error;
}
const pending = join(dirname(manifest), 'publish-current.json');
await writeFile(pending, JSON.stringify(update) + '\n', { mode: 0o600, flag: 'w' });
await rename(pending, join(directory, 'current.json'));
console.log(`Published ${update.versionName} (build ${update.versionCode}), ${update.size} bytes, SHA-256 ${update.sha256}`);
