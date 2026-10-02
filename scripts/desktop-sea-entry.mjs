import { spawn } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, renameSync, rmSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { isAbsolute, join } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';

function spawnDetached(executable, args = []) {
  return new Promise((resolve, reject) => {
    const child = spawn(executable, args, { detached: true, stdio: 'ignore' });
    child.once('error', reject);
    child.once('spawn', () => {
      child.unref();
      resolve();
    });
  });
}

async function applyUpdate(target, parentPid) {
  const pid = Number(parentPid);
  if (process.platform !== 'win32' || !isAbsolute(target ?? '') ||
      !Number.isSafeInteger(pid) || pid <= 0 || pid === process.pid ||
      process.execPath.toLowerCase() !== `${target}.updater.exe`.toLowerCase() ||
      !existsSync(`${target}.new`)) {
    throw new Error('Invalid executable update helper arguments');
  }
  if (!process.send) throw new Error('Update helper requires an IPC readiness channel');
  process.send('ready');
  process.disconnect();
  const deadline = Date.now() + 60000;
  for (;;) {
    try {
      process.kill(pid, 0);
    } catch (error) {
      if (error.code === 'ESRCH') break;
      throw error;
    }
    if (Date.now() >= deadline) throw new Error('Timed out waiting for Navi to exit before updating');
    await delay(200);
  }
  const previous = `${target}.old`;
  rmSync(previous, { force: true });
  renameSync(target, previous);
  try {
    renameSync(`${target}.new`, target);
    await spawnDetached(target);
  } catch (error) {
    if (existsSync(target)) rmSync(target);
    renameSync(previous, target);
    await spawnDetached(target);
    throw error;
  }
}

function extractNative() {
  const bytes = readFileSync(new URL('./native/koffi.node', import.meta.url));
  const hash = createHash('sha256').update(bytes).digest('hex');
  const base = process.platform === 'win32'
    ? (process.env.LOCALAPPDATA ?? join(homedir(), 'AppData', 'Local'))
    : process.platform === 'darwin'
      ? join(homedir(), 'Library', 'Caches')
      : (process.env.XDG_CACHE_HOME ?? join(homedir(), '.cache'));
  const directory = join(base, 'Navi', 'native', hash);
  const target = join(directory, 'koffi.node');
  mkdirSync(directory, { recursive: true, mode: 0o700 });
  if (!existsSync(target)) {
    const temporary = join(directory, `${randomUUID()}.tmp`);
    writeFileSync(temporary, bytes, { flag: 'wx', mode: 0o600 });
    try {
      // Concurrent instances may populate the same content-addressed cache.
      renameSync(temporary, target);
    } catch (error) {
      if (!['EEXIST', 'EPERM', 'EACCES'].includes(error.code) || !existsSync(target)) throw error;
    } finally {
      rmSync(temporary, { force: true });
    }
  }
  if (createHash('sha256').update(readFileSync(target)).digest('hex') !== hash) {
    throw new Error(`Cached Koffi addon failed integrity verification: ${target}`);
  }
  globalThis[Symbol.for('navi.koffi.path')] = target;
}

if (process.argv[2] === '--navi-apply-update') {
  try {
    await applyUpdate(process.argv[3], process.argv[4]);
  } catch (error) {
    console.error('Navi executable update failed:', error);
    if (isAbsolute(process.argv[3] ?? '') &&
        process.execPath.toLowerCase() === `${process.argv[3]}.updater.exe`.toLowerCase()) {
      writeFileSync(`${process.argv[3]}.update.log`, `${new Date().toISOString()} ${error.stack}\n`);
    }
    process.exitCode = 1;
  }
} else {
  extractNative();
  await import('./app/main.js');
}
